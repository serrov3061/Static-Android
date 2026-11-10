"""Plugin metadata parser.

Plugin metadata are literal top-level assignments (``__id__ = "..."``). The file is
parsed with ``ast`` and never executed, so a broken plugin cannot run code while it
is only being inspected or installed.

``get_metadata(path)`` returns ``{str: str}`` with the keys the engine expects:
id, name, description, author, version, icon, app_version, sdk_version, requirements.
"""

import hashlib
import json
import os
import threading

_KEYS = {
    "__id__": "id",
    "__name__": "name",
    "__description__": "description",
    "__author__": "author",
    "__version__": "version",
    "__icon__": "icon",
    "__app_version__": "app_version",
    "__sdk_version__": "sdk_version",
    "__requirements__": "requirements",
    "__min_version__": "min_version",
}

MAX_FILE_SIZE = 8 * 1024 * 1024


class MetadataError(ValueError):
    pass


def _literal(node):
    import ast
    try:
        return ast.literal_eval(node)
    except Exception:
        return None


def _stringify(key, value):
    if value is None:
        return None
    if key == "requirements":
        if isinstance(value, str):
            items = [value]
        elif isinstance(value, (list, tuple, set)):
            items = [str(v) for v in value]
        else:
            return None
        items = [i.strip() for i in items if str(i).strip()]
        return ",".join(items) if items else None
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (str, int, float)):
        return str(value)
    return None


def parse_source(source, filename="<plugin>"):
    import ast  # lazy: not needed at all when every installed plugin hits the cache
    try:
        tree = ast.parse(source, filename=filename)
    except SyntaxError as e:
        raise MetadataError("Syntax error in plugin file at line %s: %s" % (e.lineno, e.msg))
    raw = {}
    for node in tree.body:
        targets = []
        value = None
        if isinstance(node, ast.Assign):
            targets = [t for t in node.targets if isinstance(t, ast.Name)]
            value = node.value
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and node.value is not None:
            targets = [node.target]
            value = node.value
        for t in targets:
            key = _KEYS.get(t.id)
            if key is None:
                continue
            s = _stringify(key, _literal(value))
            if s is not None:
                raw[key] = s
    min_version = raw.pop("min_version", None)
    if min_version and "app_version" not in raw:
        mv = min_version.strip()
        raw["app_version"] = mv if mv[:1] in "<>=" else ">=" + mv
    for k in ("app_version", "sdk_version"):
        if k in raw:
            v = raw[k].replace(" ", "")
            if v and v[0] not in "<>=":
                v = ">=" + v
            raw[k] = v
    return raw


def _read(path):
    with open(path, "rb") as f:
        data = f.read(MAX_FILE_SIZE + 1)
    if len(data) > MAX_FILE_SIZE:
        raise MetadataError("Plugin file is too large")
    return data


def _parse_bytes(data, path):
    return parse_source(data.decode("utf-8-sig", errors="replace"), filename=str(path))


def get_metadata(path):
    return _parse_bytes(_read(path), path)


# ---------------------------------------------------------------- persistent cache
#
# Parsing the whole plugin with ``ast`` on every start is the most expensive part of
# loading a big plugin before its code even runs. The engine asks for installed plugins
# through ``get_metadata_cached``: the result is reused while the file content (sha256)
# is the same, so an updated plugin is always parsed again. Errors are never cached.

CACHE_VERSION = 1
_cache_lock = threading.Lock()
_cache_file = None
_cache = None


def _load_cache(cache_file):
    global _cache_file, _cache
    if _cache is not None and _cache_file == cache_file:
        return _cache
    entries = {}
    try:
        with open(cache_file, "r", encoding="utf-8") as f:
            loaded = json.load(f)
        if isinstance(loaded, dict) and loaded.get("v") == CACHE_VERSION and isinstance(loaded.get("e"), dict):
            entries = loaded["e"]
    except Exception:
        entries = {}
    _cache_file, _cache = cache_file, entries
    return entries


def _save_cache(cache_file, entries):
    # drop entries of deleted plugins
    for p in [p for p in entries if not os.path.isfile(p)]:
        entries.pop(p, None)
    tmp = cache_file + ".tmp"
    try:
        os.makedirs(os.path.dirname(cache_file), exist_ok=True)
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"v": CACHE_VERSION, "e": entries}, f, ensure_ascii=False)
        os.replace(tmp, cache_file)
    except Exception:
        try:
            os.remove(tmp)
        except Exception:
            pass


def get_metadata_cached(path, cache_file):
    """Same result as ``get_metadata(path)``, reused from ``cache_file`` while the file
    content is unchanged."""
    path = str(path)
    cache_file = str(cache_file)
    data = _read(path)
    digest = hashlib.sha256(data).hexdigest()
    with _cache_lock:
        entries = _load_cache(cache_file)
        entry = entries.get(path)
        if isinstance(entry, dict) and entry.get("sha256") == digest and isinstance(entry.get("md"), dict):
            return dict(entry["md"])
    md = _parse_bytes(data, path)
    with _cache_lock:
        entries = _load_cache(cache_file)
        entries[path] = {"sha256": digest, "md": dict(md)}
        _save_cache(cache_file, entries)
    return md


def clear_cache():
    global _cache_file, _cache
    with _cache_lock:
        _cache_file, _cache = None, None
