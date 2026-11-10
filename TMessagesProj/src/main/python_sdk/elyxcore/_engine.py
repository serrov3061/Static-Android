"""Elyx engine, Python half (private runtime module).

The Java ``ElyxPluginsEngine`` (a ``PluginsController.PluginsEngine`` registered as ``elyx``)
owns the plugin list, lifecycle, settings and hooks; this module does everything that is
about the archive and the Python side of a plugin:

* ``inspect_archive`` reads an archive (optionally with its password) for the install sheet,
* ``install_archive`` / ``commit_install`` / ``rollback_install`` extract it into
  ``<plugins>/ElyxPlugins/<id>`` keeping the previous version as a backup until the new one
  is loaded,
* ``load_plugin`` / ``unload_plugin`` set up the isolated import namespace, bundled wheels
  and the ``elyx`` environment, import the entry module and return the plugin instance,
* ``delete_plugin``, ``installed_ids``, ``read_installed``, ``plugin_info`` for the Java side,
* ``compare_folder`` / ``apply_changes`` for the development server.

Functions called from Java return JSON strings (``{"ok": bool, ...}``) except
``load_plugin`` which returns the BasePlugin instance.

Installed layout (``<app files>/plugins``)::

    ElyxPlugins/<id>/        extracted project
    elyx_local_libs/<id>/    extracted bundled wheels
    archives/<id>.<ext>      the archive that was installed (for sharing)
    elyx_backups/<id>/       previous version during an update
"""

import base64
import hashlib
import json
import os
import pathlib
import shutil
import sys
import threading
import traceback
import uuid

from elyxcore import _importer
from elyxcore._plugin import (
    ArchiveEncryptionError, ElyxError, ElyxPlugin, InvalidPluginError, PLUGIN_ID_RE, SecurityError,
    archive_extension, extract_archive, install_local_wheels, safe_member_path,
)

__all__ = [
    "setup", "stop", "inspect_archive", "install_archive", "commit_install", "rollback_install", "load_plugin",
    "unload_plugin", "delete_plugin", "installed_ids", "read_installed", "plugin_dir", "main_file_path",
    "archive_path", "make_archive", "compare_folder", "apply_changes", "loaded_ids", "read_sources",
]

ELYX_DIR = "ElyxPlugins"
LIBS_DIR = "elyx_local_libs"
ARCHIVES_DIR = "archives"
BACKUP_DIR = "elyx_backups"
ICON_CACHE_DIR = "elyx_icons"

_lock = threading.RLock()
_plugins_dir = None
_loaded = {}  # plugin id -> ElyxPlugin (installed directory model)


def _log(msg, error=False):
    try:
        from _sg_java import file_log
        file_log("[ElyxCore] %s" % msg, error=error)
    except Exception:
        pass


def _ok(**kw):
    kw["ok"] = True
    return json.dumps(kw, ensure_ascii=False)


def _fail(error, kind="invalid", **kw):
    kw.update(ok=False, error=str(error), error_kind=kind)
    return json.dumps(kw, ensure_ascii=False)


def _error_kind(e):
    if isinstance(e, ArchiveEncryptionError):
        if e.unsupported:
            return "unsupported"
        return "wrong_password" if e.wrong_password else "password_required"
    if isinstance(e, SecurityError):
        return "security"
    return "invalid"


# ----- directories


def _root():
    if _plugins_dir is None:
        raise RuntimeError("Elyx engine is not set up")
    return _plugins_dir


def _dir(name):
    p = _root() / name
    p.mkdir(parents=True, exist_ok=True)
    return p


def plugin_dir(plugin_id) -> str:
    return str(_root() / ELYX_DIR / plugin_id)


def _check_id(plugin_id):
    if not isinstance(plugin_id, str) or not PLUGIN_ID_RE.match(plugin_id):
        raise InvalidPluginError("Invalid plugin id: %s" % (plugin_id,))
    return plugin_id


def setup(plugins_dir=None):
    """Called by the Java engine once the Python SDK runs (not in safe mode for imports)."""
    global _plugins_dir
    with _lock:
        if plugins_dir is None:
            from _sg_java import plugins_controller
            plugins_dir = str(plugins_controller().getPluginsDir().getAbsolutePath())
        _plugins_dir = pathlib.Path(plugins_dir).resolve()
        for name in (ELYX_DIR, LIBS_DIR, ARCHIVES_DIR):
            (_plugins_dir / name).mkdir(parents=True, exist_ok=True)
        # leftovers of an interrupted install
        for entry in (_plugins_dir / ELYX_DIR).iterdir():
            if entry.name.startswith(".tmp_"):
                shutil.rmtree(entry, ignore_errors=True)
        _importer.setup(str(_plugins_dir))
        try:
            from extera_utils import get_caller
            get_caller._set_elyx_dir(str(_plugins_dir / ELYX_DIR))
        except Exception:
            pass
    return True


def stop():
    with _lock:
        for pid in list(_loaded):
            unload_plugin(pid)
        _importer.stop()
    return True


# ----- inspection / install


def _language(language):
    return str(language) if language else None


def inspect_archive(path, password=None, language=None):
    """Reads an archive for the install sheet. Never executes archive code."""
    try:
        with ElyxPlugin.from_archive(path, password or None, language=_language(language)) as plugin:
            info = plugin.info()
            icon = plugin.get_icon_path()
            if icon is not None:
                info["custom_icon_file"] = _cache_icon(plugin, icon)
            return _ok(plugin=info)
    except ElyxError as e:
        return _fail(e, _error_kind(e))
    except Exception as e:
        _log("inspect failed for %s: %s" % (path, traceback.format_exc()), error=True)
        return _fail("%s: %s" % (type(e).__name__, e))


# exteraGram 13.1.1 static analysis: the analyzer reads the archive the same way the installer does
# (central directory, path checks, password); nothing from the archive is executed.
_SOURCE_EXTS = (".py", ".pyw", ".pyi")
_BINARY_EXTS = (".so", ".dex", ".jar", ".apk", ".pyc", ".pyo", ".whl", ".zip", ".elyx", ".bin", ".dll", ".dylib")
_MAX_SOURCE_BYTES = 4 * 1024 * 1024
_MAX_SOURCES_TOTAL = 32 * 1024 * 1024
_MAX_SOURCE_FILES = 2000


def read_sources(path, password=None):
    """JSON with the archive's Python sources for the static analyzer, the binary members it has
    (native libraries, compiled code, wheels) and the manifest summary. Never executes archive code."""
    try:
        with ElyxPlugin.from_archive(path, password or None) as plugin:
            sources, binaries, total, truncated = [], [], 0, False
            for rel in plugin.files(""):
                low = rel.lower()
                if low.endswith(_SOURCE_EXTS):
                    if len(sources) >= _MAX_SOURCE_FILES:
                        truncated = True
                        continue
                    size = plugin._source.size(rel)
                    if size > _MAX_SOURCE_BYTES or total + size > _MAX_SOURCES_TOTAL:
                        truncated = True
                        continue
                    data = plugin.get_file_content(rel)
                    total += len(data)
                    sources.append({"path": rel, "text": data.decode("utf-8", "replace")})
                elif low.endswith(_BINARY_EXTS):
                    binaries.append(rel)
            try:
                info = plugin.info()
            except Exception:
                info = {}
            return _ok(sources=sources, binaries=binaries, truncated=truncated,
                       main=info.get("main"), requirements=info.get("requirements") or [],
                       wheels=info.get("wheels") or [], id=info.get("id"))
    except ElyxError as e:
        return _fail(e, _error_kind(e))
    except Exception as e:
        _log("read_sources failed for %s: %s" % (path, traceback.format_exc()), error=True)
        return _fail("%s: %s" % (type(e).__name__, e))


def _cache_icon(plugin, rel):
    try:
        cache = _dir(ICON_CACHE_DIR)
        data = plugin.get_file_content(rel)
        name = hashlib.md5(data).hexdigest() + os.path.splitext(rel)[1].lower()
        target = cache / name
        if not target.exists():
            target.write_bytes(data)
        return str(target)
    except Exception:
        return None


def install_archive(path, password=None, language=None):
    """Extracts a validated archive into ``ElyxPlugins/<id>``; an installed version is moved to
    ``elyx_backups/<id>`` until ``commit_install`` / ``rollback_install``."""
    with _lock:
        try:
            with ElyxPlugin.from_archive(path, password or None, language=_language(language)) as plugin:
                result = plugin.validate()
                if result.errors:
                    raise InvalidPluginError("\n".join(result.errors))
                pid = _check_id(plugin.id)
                if pid in _loaded:
                    unload_plugin(pid)
                elyx_dir = _dir(ELYX_DIR)
                tmp = elyx_dir / (".tmp_%s_%s" % (pid, uuid.uuid4().hex[:8]))
                try:
                    extract_archive(plugin, tmp)
                    # the extracted tree must describe the same plugin
                    with ElyxPlugin.from_directory(tmp, language=_language(language)) as extracted:
                        check = extracted.validate()
                        if check.errors or extracted.id != pid:
                            raise InvalidPluginError("\n".join(check.errors) or "Plugin id changed during extraction")
                    target = elyx_dir / pid
                    backup = _dir(BACKUP_DIR) / pid
                    if backup.exists():
                        shutil.rmtree(backup, ignore_errors=True)
                    had_previous = target.exists()
                    if had_previous:
                        os.replace(target, backup)
                    os.replace(tmp, target)
                except Exception:
                    shutil.rmtree(tmp, ignore_errors=True)
                    raise
                archives = _dir(ARCHIVES_DIR)
                for old in archives.glob(pid + ".*"):
                    try:
                        old.unlink()
                    except OSError:
                        pass
                try:
                    shutil.copyfile(path, archives / (pid + archive_extension(path)))
                except OSError as e:
                    _log("could not keep the archive of %s: %s" % (pid, e))
                info = plugin.info()
            return _ok(id=pid, plugin=info, had_previous=had_previous)
        except ElyxError as e:
            return _fail(e, _error_kind(e))
        except Exception as e:
            _log("install failed for %s: %s" % (path, traceback.format_exc()), error=True)
            return _fail("%s: %s" % (type(e).__name__, e))


def commit_install(plugin_id):
    with _lock:
        backup = _root() / BACKUP_DIR / plugin_id
        if backup.exists():
            shutil.rmtree(backup, ignore_errors=True)
    return True


def rollback_install(plugin_id):
    """Restores the backup taken by ``install_archive`` (or removes a fresh install).
    Returns True when a previous version was restored."""
    with _lock:
        _check_id(plugin_id)
        if plugin_id in _loaded:
            unload_plugin(plugin_id)
        target = _root() / ELYX_DIR / plugin_id
        backup = _root() / BACKUP_DIR / plugin_id
        if target.exists():
            shutil.rmtree(target, ignore_errors=True)
        if backup.exists():
            os.replace(backup, target)
            _log("Restored plugin %s from backup" % plugin_id)
            return True
        for old in (_root() / ARCHIVES_DIR).glob(plugin_id + ".*"):
            try:
                old.unlink()
            except OSError:
                pass
        shutil.rmtree(_root() / LIBS_DIR / plugin_id, ignore_errors=True)
        return False


# ----- installed plugins


def installed_ids():
    base = _root() / ELYX_DIR
    out = []
    if base.is_dir():
        for entry in sorted(base.iterdir()):
            if entry.is_dir() and not entry.name.startswith(".") and PLUGIN_ID_RE.match(entry.name):
                out.append(entry.name)
    return json.dumps(out)


def read_installed(plugin_id, language=None):
    """Metadata of an installed plugin (``errors`` lists validation problems)."""
    try:
        _check_id(plugin_id)
        with ElyxPlugin.from_directory(plugin_dir(plugin_id), language=_language(language)) as plugin:
            info = plugin.info()
            icon = plugin.get_icon_path()
            if icon is not None:
                info["custom_icon_file"] = str(pathlib.Path(plugin_dir(plugin_id)) / icon)
            if info.get("id") != plugin_id:
                info["errors"] = list(info.get("errors") or []) + [
                    "Plugin id mismatch. Expected: %s, but found: %s in metadata." % (plugin_id, info.get("id"))]
            return _ok(plugin=info)
    except ElyxError as e:
        return _fail(e, _error_kind(e))
    except Exception as e:
        return _fail("%s: %s" % (type(e).__name__, e))


def main_file_path(plugin_id):
    try:
        with ElyxPlugin.from_directory(plugin_dir(plugin_id)) as plugin:
            main = plugin.structure.main_file
            return str(pathlib.Path(plugin_dir(plugin_id)) / main) if main else None
    except Exception:
        return None


def archive_path(plugin_id):
    for p in sorted((_root() / ARCHIVES_DIR).glob(plugin_id + ".*")):
        if p.is_file():
            return str(p)
    return None


def make_archive(plugin_id, target_path):
    """Packs the installed directory (files at the archive root) into ``target_path``."""
    import zipfile
    src = pathlib.Path(plugin_dir(plugin_id))
    with zipfile.ZipFile(target_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for dirpath, dirnames, filenames in os.walk(src):
            dirnames[:] = sorted(d for d in dirnames if d != "__pycache__")
            rel_dir = pathlib.Path(dirpath).relative_to(src).as_posix()
            if rel_dir != ".":
                zf.writestr(rel_dir + "/", b"")
            for f in sorted(filenames):
                full = pathlib.Path(dirpath, f)
                zf.write(full, full.relative_to(src).as_posix())
    return str(target_path)


def loaded_ids():
    return json.dumps(list(_loaded))


# ----- runtime


def load_plugin(plugin_id, language=None):
    """Imports the plugin and returns a new instance of its BasePlugin subclass.
    PIP requirements are installed by the Java engine before this call."""
    with _lock:
        _check_id(plugin_id)
        if plugin_id in _loaded:
            unload_plugin(plugin_id)
        root = pathlib.Path(plugin_dir(plugin_id))
        plugin = ElyxPlugin.from_directory(root, language=_language(language))
        result = plugin.validate()
        if result.errors:
            raise InvalidPluginError("\n".join(result.errors))
        if plugin.id != plugin_id:
            raise InvalidPluginError("Plugin id mismatch. Expected: %s, but found: %s in metadata."
                                     % (plugin_id, plugin.id))
        _log("[ElyxWheels] processing local wheels for %s" % plugin_id)
        wheel_roots = install_local_wheels(plugin_id, root, plugin.structure.wheels_path, _dir(LIBS_DIR))
        for p in reversed(wheel_roots):
            while p in sys.path:
                sys.path.remove(p)
            sys.path.insert(0, p)
            _log("[ElyxWheels] added to sys.path: %s" % p)
        environment = plugin.get_environment_vars(root)
        _importer.importer.register_plugin(plugin_id, root, environment, plugin.structure.main_file, wheel_roots)
        _loaded[plugin_id] = plugin
        try:
            module = _importer.importer.import_main(plugin_id, plugin.structure.main_file)
            from base_plugin import BasePlugin
            cls = BasePlugin._findPluginClass(module)
            if cls is None:
                raise InvalidPluginError.plugin_class_not_found(plugin.structure.main_file)
            _log("Loaded plugin %s" % plugin_id)
            return cls()
        except BaseException:
            unload_plugin(plugin_id)
            raise


def unload_plugin(plugin_id):
    """Drops the plugin's modules, wheel paths and environment."""
    with _lock:
        plugin = _loaded.pop(plugin_id, None)
        removed = _importer.importer.unload_plugin(plugin_id)
        if plugin is not None:
            plugin.close()
        _remove_pycache(pathlib.Path(plugin_dir(plugin_id)) if _plugins_dir is not None else None)
        return removed


def _remove_pycache(root):
    if root is None or not root.is_dir():
        return
    for dirpath, dirnames, _ in os.walk(root):
        if "__pycache__" in dirnames:
            shutil.rmtree(os.path.join(dirpath, "__pycache__"), ignore_errors=True)
            dirnames.remove("__pycache__")


def delete_plugin(plugin_id):
    with _lock:
        _check_id(plugin_id)
        unload_plugin(plugin_id)
        for p in (_root() / ELYX_DIR / plugin_id, _root() / LIBS_DIR / plugin_id, _root() / BACKUP_DIR / plugin_id):
            shutil.rmtree(p, ignore_errors=True)
        for old in (_root() / ARCHIVES_DIR).glob(plugin_id + ".*"):
            try:
                old.unlink()
            except OSError:
                pass
        _log("Deleted plugin %s" % plugin_id)
    return True


# ----- development server support


def _file_md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def compare_folder(plugin_id, hashes):
    """``hashes``: ``{relative path: md5}`` of the client project. Returns what differs."""
    _check_id(plugin_id)
    root = pathlib.Path(plugin_dir(plugin_id))
    if not root.is_dir():
        raise FileNotFoundError("Plugin with id %s not found!" % plugin_id)
    local = {}
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d != "__pycache__"]
        for f in filenames:
            full = pathlib.Path(dirpath, f)
            local[full.relative_to(root).as_posix()] = _file_md5(full)
    hashes = {str(k).replace("\\", "/"): str(v).lower() for k, v in dict(hashes or {}).items()}
    modified = sorted(p for p, h in hashes.items() if p in local and local[p] != h)
    missing = sorted(p for p in hashes if p not in local)
    extra = sorted(p for p in local if p not in hashes)
    return {"modified": modified, "missing": missing, "extra": extra, "hashes": local}


def _target(root, rel):
    safe = safe_member_path(rel or "")
    if not safe:
        raise SecurityError("Invalid path: %r" % (rel,))
    dest = (root / safe).resolve()
    if dest != root and root not in dest.parents:
        raise SecurityError("Path traversal: %r" % (rel,))
    return dest


def apply_changes(plugin_id, changes):
    """Applies dev-client change records (created / modified / deleted / moved) to the
    installed directory. Returns the list of per-change errors (reloading is up to Java)."""
    _check_id(plugin_id)
    root = pathlib.Path(plugin_dir(plugin_id)).resolve()
    if not root.is_dir():
        raise FileNotFoundError("Plugin with id %s not found!" % plugin_id)
    errors = []
    for change in list(changes or []):
        try:
            event = change.get("#") or change.get("event") or change.get("event_type")
            rel = change.get("path")
            is_dir = bool(change.get("is_directory"))
            dest = _target(root, rel)
            if event in ("created", "modified"):
                if is_dir:
                    dest.mkdir(parents=True, exist_ok=True)
                else:
                    content = change.get("content")
                    data = base64.b64decode(content) if content else b""
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    tmp = dest.with_name(dest.name + ".elyxtmp")
                    tmp.write_bytes(data)
                    os.replace(tmp, dest)
            elif event == "deleted":
                if dest.is_dir():
                    shutil.rmtree(dest, ignore_errors=True)
                elif dest.exists():
                    dest.unlink()
            elif event == "moved":
                new = _target(root, change.get("dest_path"))
                if dest.exists():
                    new.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(dest, new)
            else:
                raise ValueError("unknown change type %r" % (event,))
        except Exception as e:
            errors.append("Failed to apply change <%s %s>: %s" % (change.get("#"), change.get("path"), e))
    _remove_pycache(root)
    return errors
