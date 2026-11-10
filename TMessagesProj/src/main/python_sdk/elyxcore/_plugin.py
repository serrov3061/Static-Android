"""Elyx structured plugins: archive / project model (private runtime module).

An Elyx plugin is a ZIP-compatible archive (``.elyx``, ``.eaf``, ``.elyx.zip``, ``.eaf.zip``)
or, once installed, a directory. Its root holds a refmap (``refmap.yaml|yml|json``) that
points at the metadata file, the entry module, the localization files, the asset directory
and a directory of bundled wheels. Without a refmap (or for keys it omits) the defaults are
``main.py``, ``metainfo.yaml|yml|json``, ``assets/`` at the root.

Password-protected archives use the classic ZIP encryption (ZipCrypto, general purpose flag
bit 0) that Python's ``zipfile`` reads with ``setpassword``; AES-encrypted entries (method 99)
are reported as unsupported. Nothing in an archive is executed while it is inspected:
Python metadata / localization files are read with ``ast.literal_eval``.
"""

import ast
import io
import json
import os
import pathlib
import re
import shutil
import stat
import zipfile
import zlib
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

__all__ = [
    "ARCHIVE_EXTENSIONS", "ElyxError", "ArchiveEncryptionError", "InvalidPluginError", "SecurityError",
    "ValidationError", "RequiredPlugin", "ElyxPlugin", "is_archive_name", "parse_requirements",
    "parse_required_plugins", "safe_member_path", "extract_archive", "install_local_wheels",
]

ARCHIVE_EXTENSIONS = (".elyx.zip", ".eaf.zip", ".elyx", ".eaf")
REFMAP_NAMES = ("refmap.yaml", "refmap.yml", "refmap.json")
METAINFO_NAMES = ("metainfo.yaml", "metainfo.yml", "metainfo.json")
STRINGS_SUFFIXES = (".yaml", ".yml", ".json", ".py")
ICON_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg")
PLUGIN_ID_RE = re.compile(r"^[A-Za-z0-9_]{2,32}$")
STICKER_ICON_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*/\d+$")
_REQUIRE_KEY_RE = re.compile(r"^\s*([^\s()]+)\s*(?:\(\s*([^()]*?)\s*\))?\s*$")
_PLACEHOLDER_RE = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")
_REQUIREMENTS_SPLIT_RE = re.compile(r",(?!\s*[<>=!~(])")

# archive limits (zip bombs, absurd archives)
MAX_ENTRIES = 20000
MAX_TOTAL_SIZE = 1024 * 1024 * 1024
MAX_METADATA_SIZE = 4 * 1024 * 1024

DEFAULT_VERSION = "1.0.0"


class ElyxError(Exception):
    """Base class of Elyx archive / project errors."""


class ArchiveEncryptionError(ElyxError):
    """The archive is encrypted and no (or a wrong) password was given."""

    def __init__(self, message, wrong_password=False, unsupported=False):
        super().__init__(message)
        self.wrong_password = bool(wrong_password)
        self.unsupported = bool(unsupported)


class InvalidPluginError(ElyxError):
    """The project is structurally invalid (no metadata, no entry module, ...)."""

    @staticmethod
    def plugin_class_not_found(main_path):
        return InvalidPluginError("Could not find a class inheriting from BasePlugin in %s" % main_path)


class ValidationError(ElyxError):
    """Metadata failed validation; ``errors`` lists every problem."""

    def __init__(self, errors):
        self.errors = list(errors)
        super().__init__("\n".join(self.errors))


class SecurityError(ElyxError):
    """Unsafe archive content (path traversal, links, size limits)."""


def is_archive_name(name) -> bool:
    name = str(name or "").lower()
    return any(name.endswith(ext) for ext in ARCHIVE_EXTENSIONS)


def archive_extension(name) -> str:
    low = str(name or "").lower()
    for ext in ARCHIVE_EXTENSIONS:
        if low.endswith(ext):
            return ext
    return ".elyx"


def parse_requirements(value) -> List[str]:
    """``"requests>=2.32, tinydb>=4.8"`` (or a list) -> ``["requests>=2.32", "tinydb>=4.8"]``."""
    if value is None:
        return []
    if isinstance(value, (list, tuple)):
        items = []
        for v in value:
            items.extend(parse_requirements(v))
        return items
    out = []
    for part in _REQUIREMENTS_SPLIT_RE.split(str(value)):
        part = part.strip()
        if part:
            out.append(part)
    return out


@dataclass
class RequiredPlugin:
    id: str
    min_version: Optional[str] = None
    url: Optional[str] = None

    def to_dict(self):
        return {"id": self.id, "version": self.min_version, "url": self.url}


def parse_required_plugins(value, errors=None) -> List[RequiredPlugin]:
    """``requires`` mapping: ``{"plugin_id (min_version)": "download url" | None}``.
    A list of keys is accepted too."""
    if errors is None:
        errors = []
    if not value:
        return []
    if isinstance(value, (list, tuple)):
        value = {k: None for k in value}
    if not isinstance(value, dict):
        errors.append("'requires' must be a mapping of plugin ids to download links")
        return []
    out = []
    for key, url in value.items():
        m = _REQUIRE_KEY_RE.match(str(key))
        if m is None or not PLUGIN_ID_RE.match(m.group(1)):
            errors.append('Invalid plugin id requirement: "%s". Plugin \'__id__\' must be 2-32 characters long and '
                          'contain only letters, numbers, and underscores. Version must be in format '
                          '"plugin_id (min_version)".' % key)
            continue
        version = (m.group(2) or "").strip() or None
        out.append(RequiredPlugin(m.group(1), version, str(url).strip() if url else None))
    return out


def safe_member_path(name) -> Optional[str]:
    """Normalized relative path of an archive member, or None when it would leave the root."""
    name = str(name).replace("\\", "/")
    if name.startswith("/") or re.match(r"^[A-Za-z]:", name):
        return None
    parts = []
    for part in name.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            return None
        parts.append(part)
    return "/".join(parts)


def _is_symlink(info: zipfile.ZipInfo) -> bool:
    return stat.S_ISLNK((info.external_attr >> 16) & 0xFFFF)


def _literal_assignments(source: str) -> Dict[str, Any]:
    """Top-level ``NAME = <literal>`` assignments of a Python file, without executing it."""
    tree = ast.parse(source)
    out = {}
    for node in tree.body:
        targets = []
        if isinstance(node, ast.Assign):
            targets = node.targets
            value = node.value
        elif isinstance(node, ast.AnnAssign) and node.value is not None:
            targets = [node.target]
            value = node.value
        else:
            continue
        try:
            literal = ast.literal_eval(value)
        except Exception:
            continue
        if callable(literal):
            continue
        for t in targets:
            if isinstance(t, ast.Name) and (not t.id.startswith("_") or re.match(r"^__\w+__$", t.id)):
                out[t.id] = literal
    return out


def load_data(name: str, data: bytes):
    """Parses metadata / refmap / localization content by file extension."""
    low = name.lower()
    text = data.decode("utf-8-sig")
    if low.endswith(".json"):
        return json.loads(text)
    if low.endswith((".yaml", ".yml")):
        import yaml
        return yaml.safe_load(text)
    if low.endswith(".py"):
        return _literal_assignments(text)
    raise ValueError("Unsupported file type: %s" % name)


def _locale_of(filename: str) -> str:
    stem = filename.rsplit("/", 1)[-1].split(".", 1)[0]
    if "_" in stem:
        loc = stem.rsplit("_", 1)[1].strip()
        if loc:
            return loc.lower()
    return "en"


def _normalize_meta_key(key) -> str:
    key = str(key)
    m = re.match(r"^__(\w+?)__$", key)
    return m.group(1) if m else key


# ----- sources: an archive or a directory, addressed by relative POSIX paths


class _DirSource:
    def __init__(self, root):
        self.root = pathlib.Path(root)

    def _p(self, rel):
        return self.root / rel if rel else self.root

    def is_file(self, rel):
        return bool(rel) and self._p(rel).is_file()

    def is_dir(self, rel):
        return self._p(rel).is_dir()

    def read(self, rel):
        return self._p(rel).read_bytes()

    def listdir(self, rel):
        p = self._p(rel)
        return sorted(os.listdir(p)) if p.is_dir() else []

    def files_under(self, rel):
        base = self._p(rel)
        out = []
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d != "__pycache__"]
            for f in filenames:
                out.append(pathlib.Path(dirpath, f).relative_to(self.root).as_posix())
        return sorted(out)

    def size(self, rel):
        return self._p(rel).stat().st_size

    def close(self):
        pass


class _ZipSource:
    def __init__(self, path, password=None):
        self.path = str(path)
        try:
            self.zf = zipfile.ZipFile(self.path)
        except zipfile.BadZipFile as e:
            raise InvalidPluginError("File %s is not a valid ZIP archive (%s)" % (os.path.basename(self.path), e))
        self.files = {}
        self.dirs = {""}
        total = 0
        infos = self.zf.infolist()
        if len(infos) > MAX_ENTRIES:
            raise SecurityError("Archive has too many entries (%d)" % len(infos))
        encrypted = []
        for info in infos:
            rel = safe_member_path(info.filename)
            if rel is None:
                raise SecurityError("Path traversal in archive: %s" % info.filename)
            if _is_symlink(info):
                raise SecurityError("Symbolic links are not allowed in archives: %s" % info.filename)
            total += info.file_size
            if total > MAX_TOTAL_SIZE:
                raise SecurityError("Archive content is too large")
            if info.is_dir():
                if rel:
                    self.dirs.add(rel)
            else:
                if not rel:
                    continue
                self.files[rel] = info
                if info.flag_bits & 0x1:
                    encrypted.append(info)
            parts = rel.split("/")
            for i in range(1, len(parts)):
                self.dirs.add("/".join(parts[:i]))
        self.encrypted = bool(encrypted)
        if encrypted:
            if any(i.compress_type == 99 for i in encrypted):
                raise ArchiveEncryptionError("AES-encrypted archives are not supported, use ZipCrypto", unsupported=True)
            if not password:
                raise ArchiveEncryptionError("Archive is password protected")
            pwd = password.encode("utf-8") if isinstance(password, str) else bytes(password)
            self.zf.setpassword(pwd)
            probe = min(encrypted, key=lambda i: i.file_size)
            try:
                self.zf.read(probe)
            except RuntimeError as e:
                raise ArchiveEncryptionError("Wrong archive password", wrong_password=True) from e
            except (zipfile.BadZipFile, zlib.error) as e:  # ZipCrypto check byte collision -> CRC error
                raise ArchiveEncryptionError("Wrong archive password", wrong_password=True) from e

    def is_file(self, rel):
        return rel in self.files

    def is_dir(self, rel):
        return rel in self.dirs

    def read(self, rel):
        info = self.files[rel]
        try:
            return self.zf.read(info)
        except RuntimeError as e:
            raise ArchiveEncryptionError("Wrong archive password", wrong_password=True) from e
        except NotImplementedError as e:
            raise ArchiveEncryptionError("Unsupported compression in %s: %s" % (rel, e), unsupported=True) from e

    def listdir(self, rel):
        prefix = rel + "/" if rel else ""
        names = set()
        for p in list(self.files) + list(self.dirs):
            if p and p.startswith(prefix):
                rest = p[len(prefix):]
                if rest:
                    names.add(rest.split("/", 1)[0])
        return sorted(names)

    def files_under(self, rel):
        prefix = rel + "/" if rel else ""
        return sorted(p for p in self.files if p.startswith(prefix))

    def size(self, rel):
        return self.files[rel].file_size

    def close(self):
        try:
            self.zf.close()
        except Exception:
            pass


# ----- the plugin model


@dataclass
class Structure:
    refmap_file: Optional[str] = None
    metainfo_file: Optional[str] = None
    main_file: Optional[str] = None
    strings_path: Optional[str] = None
    assets_path: Optional[str] = None
    wheels_path: Optional[str] = None
    icon_file: Optional[str] = None


@dataclass
class PluginValidationResult:
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)

    @property
    def ok(self):
        return not self.errors


def _rel(value) -> Optional[str]:
    if value is None:
        return None
    value = str(value).strip()
    if not value:
        return None
    rel = safe_member_path(value)
    if rel is None:
        raise SecurityError("Path leaves the plugin root: %s" % value)
    return rel


class ElyxPlugin:
    """Metadata, structure, localization and validation of one Elyx project."""

    def __init__(self, source, origin=None, language=None):
        self._source = source
        self.origin = origin
        self.is_archive = isinstance(source, _ZipSource)
        self._language = language
        self.__refmap = None
        self.__metainfo = None
        self.__strings = None
        self.__structure = None
        self.__validation_result = None
        self.requires: List[RequiredPlugin] = []

    # ----- construction

    @classmethod
    def from_archive(cls, path, password=None, language=None):
        return cls(_ZipSource(path, password), origin=str(path), language=language)

    @classmethod
    def from_directory(cls, path, language=None):
        p = pathlib.Path(path)
        if not p.is_dir():
            raise InvalidPluginError("Plugin directory not found: %s" % p)
        return cls(_DirSource(p), origin=str(p), language=language)

    def close(self):
        self._source.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    @property
    def encrypted(self):
        return getattr(self._source, "encrypted", False)

    # ----- files

    def get_file_content(self, rel) -> bytes:
        if not self._source.is_file(rel):
            raise InvalidPluginError('File "%s" not found!' % rel)
        return self._source.read(rel)

    def _load_data_file(self, rel):
        if self._source.size(rel) > MAX_METADATA_SIZE:
            raise InvalidPluginError("File %s is too large" % rel)
        return load_data(rel, self.get_file_content(rel))

    def files(self, rel=""):
        return self._source.files_under(rel)

    # ----- refmap / structure

    def get_refmap(self) -> Dict[str, Any]:
        if self.__refmap is None:
            refmap = {}
            for name in REFMAP_NAMES:
                if self._source.is_file(name):
                    data = self._load_data_file(name)
                    if data is None:
                        data = {}
                    if not isinstance(data, dict):
                        raise InvalidPluginError("%s must contain a mapping" % name)
                    refmap = dict(data)
                    refmap.setdefault("__file__", name)
                    break
            self.__refmap = refmap
        return self.__refmap

    @property
    def refmap(self):
        return {k: v for k, v in self.get_refmap().items() if k != "__file__"}

    def load_structure(self) -> Structure:
        refmap = self.get_refmap()
        s = Structure(refmap_file=refmap.get("__file__"))
        s.metainfo_file = _rel(refmap.get("metainfo"))
        if s.metainfo_file is None:
            s.metainfo_file = next((n for n in METAINFO_NAMES if self._source.is_file(n)), None)
        s.main_file = _rel(refmap.get("main"))
        if s.main_file is None and self._source.is_file("main.py"):
            s.main_file = "main.py"
        if s.main_file is None and self._source.is_file("main.pyc"):
            s.main_file = "main.pyc"
        s.strings_path = _rel(refmap.get("strings"))
        s.assets_path = _rel(refmap.get("assets"))
        if s.assets_path is None and self._source.is_dir("assets"):
            s.assets_path = "assets"
        s.wheels_path = _rel(refmap.get("wheels"))
        return s

    @property
    def structure(self) -> Structure:
        if self.__structure is None:
            self.__structure = self.load_structure()
        return self.__structure

    # ----- localization

    def _strings_files(self):
        path = self.structure.strings_path
        if path is None:
            return []
        if self._source.is_file(path):
            return [path]
        if self._source.is_dir(path):
            return [path + "/" + n for n in self._source.listdir(path)
                    if self._source.is_file(path + "/" + n) and n.lower().endswith(STRINGS_SUFFIXES)]
        return []

    def get_strings(self) -> Dict[str, Dict[str, Any]]:
        """``{locale: {key: value}}`` from the localization path (empty when none)."""
        if self.__strings is None:
            out: Dict[str, Dict[str, Any]] = {}
            for rel in self._strings_files():
                try:
                    data = self._load_data_file(rel)
                except ElyxError:
                    raise
                except Exception as e:
                    raise InvalidPluginError("Failed to read strings file %s: %s" % (rel, e))
                if not isinstance(data, dict):
                    continue
                values = {str(k): v for k, v in data.items()
                          if not str(k).startswith("_") and not callable(v)}
                out.setdefault(_locale_of(rel), {}).update(values)
            self.__strings = {k: v for k, v in out.items() if v}
        return self.__strings

    def strings_object(self):
        strings = self.get_strings()
        if not strings:
            return None
        from elyxcore.localization import Strings
        return Strings(strings)

    def current_language(self):
        if self._language:
            return str(self._language).lower()
        try:
            from elyxcore.localization import Strings
            return Strings.get_current_language()
        except Exception:
            return "en"

    def _resolve_description(self, text):
        if not isinstance(text, str) or "{" not in text:
            return text
        strings = self.get_strings()
        if not strings:
            return text
        lang = self.current_language()
        tables = [strings.get(lang) or {}, strings.get("en") or {}]

        def replacer(m):
            key = m.group(1)
            for t in tables:
                if key in t:
                    return str(t[key])
            return key
        return _PLACEHOLDER_RE.sub(replacer, text)

    # ----- metadata

    def get_metainfo(self) -> Dict[str, Any]:
        if self.__metainfo is None:
            rel = self.structure.metainfo_file
            if rel is None or not self._source.is_file(rel):
                raise InvalidPluginError("Metainfo not found" + (': "%s"' % rel if rel else ""))
            data = self._load_data_file(rel)
            if not isinstance(data, dict) or not data:
                raise InvalidPluginError("Metainfo %s must be a non-empty mapping" % rel)
            meta = {}
            for k, v in data.items():
                meta[_normalize_meta_key(k)] = v
            for key in ("id", "name", "author", "version", "icon", "min_version", "app_version", "sdk_version",
                        "description"):
                if key in meta and meta[key] is not None and not isinstance(meta[key], str):
                    meta[key] = str(meta[key])
            meta.setdefault("version", DEFAULT_VERSION)
            if meta.get("description") is not None:
                meta["raw_description"] = meta["description"]
                meta["description"] = self._resolve_description(meta["description"])
            meta["requirements"] = parse_requirements(meta.get("requirements"))
            self.__metainfo = meta
        return self.__metainfo

    @property
    def metainfo(self):
        return self.get_metainfo()

    @property
    def id(self):
        return self.get_metainfo().get("id")

    # ----- icon

    def is_custom_icon(self) -> bool:
        icon = self.get_metainfo().get("icon")
        return bool(icon) and not STICKER_ICON_RE.match(icon) and self.get_icon_path() is not None

    def get_icon_path(self) -> Optional[str]:
        icon = self.get_metainfo().get("icon")
        if not icon or STICKER_ICON_RE.match(icon):
            return None
        try:
            rel = _rel(icon)
        except SecurityError:
            return None
        if rel and rel.lower().endswith(ICON_SUFFIXES) and self._source.is_file(rel):
            return rel
        return None

    # ----- validation

    def validate(self) -> PluginValidationResult:
        result = PluginValidationResult()
        try:
            meta = self.get_metainfo()
        except ElyxError as e:
            result.errors.append(str(e))
            self.__validation_result = result
            return result
        pid = meta.get("id")
        if not pid or not meta.get("name"):
            result.errors.append("Plugin metadata must contain non-empty 'id' and 'name'.")
        elif not PLUGIN_ID_RE.match(pid):
            result.errors.append("Invalid plugin id: %s. Plugin id must be 2-32 characters long and contain only "
                                 "letters, numbers, and underscores." % pid)
        s = self.structure
        if s.main_file is None:
            result.errors.append("Main file not found: set 'main' in refmap or add main.py to the archive root.")
        elif not self._source.is_file(s.main_file):
            result.errors.append('Main file with path "%s" not found!' % s.main_file)
        elif not s.main_file.lower().endswith((".py", ".pyc")):
            result.errors.append('Main file "%s" must be a .py or .pyc file' % s.main_file)
        if s.strings_path is not None and not (self._source.is_file(s.strings_path)
                                               or self._source.is_dir(s.strings_path)):
            result.errors.append('Strings with path "%s" not found!' % s.strings_path)
        if s.assets_path is not None and "assets" in self.get_refmap() and not self._source.is_dir(s.assets_path):
            result.errors.append('Assets with path "%s" not found!' % s.assets_path)
        if s.wheels_path is not None and not self._source.is_dir(s.wheels_path):
            result.warnings.append("wheels dir not found: %s" % s.wheels_path)
        errors = []
        self.requires = parse_required_plugins(meta.get("requires"), errors)
        result.errors.extend(errors)
        self.__validation_result = result
        return result

    def get_validation_result(self) -> PluginValidationResult:
        if self.__validation_result is None:
            self.validate()
        return self.__validation_result

    @property
    def validation_result(self):
        return self.get_validation_result()

    def wheel_files(self) -> List[str]:
        s = self.structure
        if s.wheels_path is None or not self._source.is_dir(s.wheels_path):
            return []
        return [s.wheels_path + "/" + n for n in self._source.listdir(s.wheels_path)
                if n.lower().endswith(".whl") and self._source.is_file(s.wheels_path + "/" + n)]

    def info(self) -> Dict[str, Any]:
        """JSON-friendly summary used by the installer and the engine."""
        meta = self.get_metainfo()
        result = self.get_validation_result()
        return {
            "id": meta.get("id"),
            "name": meta.get("name"),
            "description": meta.get("description"),
            "author": meta.get("author"),
            "version": meta.get("version") or DEFAULT_VERSION,
            "icon": meta.get("icon") if meta.get("icon") and STICKER_ICON_RE.match(meta["icon"]) else None,
            "custom_icon": self.get_icon_path(),
            "min_version": meta.get("min_version"),
            "app_version": meta.get("app_version"),
            "sdk_version": meta.get("sdk_version"),
            "requirements": list(meta.get("requirements") or []),
            "requires": [r.to_dict() for r in self.requires],
            "wheels": [w.rsplit("/", 1)[-1] for w in self.wheel_files()],
            "main": self.structure.main_file,
            "encrypted": self.encrypted,
            "errors": list(result.errors),
            "warnings": list(result.warnings),
        }

    # ----- runtime environment (installed plugins)

    def get_environment_vars(self, plugin_root=None) -> Dict[str, Any]:
        from elyxcore.assets import Assets
        from elyxcore.settings import SettingsController
        meta = self.get_metainfo()
        env: Dict[str, Any] = {"settings": SettingsController(meta.get("id"))}
        env["metainfo"] = dict(meta)
        env["refmap"] = self.refmap
        root = pathlib.Path(plugin_root or self.origin)
        if self.structure.assets_path is not None:
            assets_dir = root / self.structure.assets_path
            try:
                if assets_dir.is_dir() and any(assets_dir.iterdir()):
                    env["assets"] = Assets(assets_dir)
            except OSError:
                pass
        strings = self.strings_object()
        if strings is not None:
            env["strings"] = strings
        return env


# ----- extraction


def extract_archive(plugin: ElyxPlugin, target_dir) -> None:
    """Extracts every member of an (already validated) archive into ``target_dir``."""
    if not plugin.is_archive:
        raise ValueError("not an archive")
    target = pathlib.Path(target_dir).resolve()
    target.mkdir(parents=True, exist_ok=True)
    src = plugin._source
    for d in sorted(src.dirs):
        if d:
            (target / d).mkdir(parents=True, exist_ok=True)
    for rel in src.files:
        dest = (target / rel).resolve()
        if dest != target and target not in dest.parents:
            raise SecurityError("Path traversal in archive: %s" % rel)
        dest.parent.mkdir(parents=True, exist_ok=True)
        with open(dest, "wb") as f:
            f.write(src.read(rel))


# ----- bundled wheels


def _log(msg, error=False):
    try:
        from _sg_java import file_log
        file_log(msg, error=error)
    except Exception:
        pass


def _extract_wheel(wheel_path: pathlib.Path, target: pathlib.Path):
    tmp = target.with_name(target.name + ".tmp")
    if tmp.exists():
        shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    try:
        with zipfile.ZipFile(wheel_path) as zf:
            total = 0
            for info in zf.infolist():
                rel = safe_member_path(info.filename)
                if rel is None or _is_symlink(info):
                    raise SecurityError("Path traversal in wheel: %s" % info.filename)
                total += info.file_size
                if total > MAX_TOTAL_SIZE:
                    raise SecurityError("Wheel content is too large: %s" % wheel_path.name)
                if info.is_dir() or not rel:
                    (tmp / rel).mkdir(parents=True, exist_ok=True)
                    continue
                dest = tmp / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info) as r, open(dest, "wb") as w:
                    shutil.copyfileobj(r, w)
        if target.exists():
            shutil.rmtree(target, ignore_errors=True)
        os.replace(tmp, target)
    except Exception:
        shutil.rmtree(tmp, ignore_errors=True)
        raise


def install_local_wheels(plugin_id, plugin_root, wheels_rel, lib_root) -> List[str]:
    """Extracts the bundled wheels of a plugin into ``lib_root/<plugin_id>/<wheel stem>``.

    A wheel already extracted under the same stem is skipped, directories of wheels that are
    no longer bundled are removed. Returns the extracted directories (for ``sys.path``)."""
    lib_dir = pathlib.Path(lib_root) / plugin_id
    if not wheels_rel:
        if lib_dir.exists():
            shutil.rmtree(lib_dir, ignore_errors=True)
        return []
    wheels_dir = pathlib.Path(plugin_root) / wheels_rel
    if not wheels_dir.is_dir():
        _log("[WARN] [ElyxWheels] wheels dir not found: %s" % wheels_dir)
        return []
    _log("[INFO] [ElyxWheels] processing local wheels for %s" % plugin_id)
    whl_files = sorted(p for p in wheels_dir.iterdir() if p.is_file() and p.name.lower().endswith(".whl"))
    if not whl_files:
        _log("[INFO] [ElyxWheels] no .whl files in %s" % wheels_dir)
    lib_dir.mkdir(parents=True, exist_ok=True)
    installed_stems = set()
    paths = []
    for whl in whl_files:
        stem = whl.name[:-4]
        installed_stems.add(stem)
        target = lib_dir / stem
        if target.is_dir():
            _log("[INFO] [ElyxWheels] skip %s (already installed at %s)" % (whl.name, target))
        else:
            _log("[INFO] [ElyxWheels] extracting %s -> %s" % (whl.name, target))
            _extract_wheel(whl, target)
        paths.append(str(target))
    for entry in lib_dir.iterdir():
        if entry.is_dir() and entry.name not in installed_stems:
            if entry.name.endswith(".tmp"):
                _log("[INFO] [ElyxWheels] skipping stale dir: %s" % entry)
            else:
                _log("[INFO] [ElyxWheels] removing stale dir: %s" % entry)
            shutil.rmtree(entry, ignore_errors=True)
    _log("[INFO] [ElyxWheels] local wheels done for %s: %d wheel(s)" % (plugin_id, len(paths)))
    return paths
