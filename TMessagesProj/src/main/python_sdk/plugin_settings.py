"""Persistent per-plugin settings storage (one JSON file per plugin).

Called by the Java engine: init(plugins_dir, prefs) -> list of migrated pref keys,
get_setting, set_setting, clear_settings, get_all_settings.
"""

import json
import os
import threading

_lock = threading.RLock()
_dir = None
_cache = {}

SETTINGS_DIR_NAME = ".settings"


def init(plugins_dir, prefs=None):
    """Sets the storage directory. Nothing is migrated from SharedPreferences."""
    global _dir
    with _lock:
        _dir = os.path.join(str(plugins_dir), SETTINGS_DIR_NAME)
        os.makedirs(_dir, exist_ok=True)
        _cache.clear()
    return []


def _path(plugin_id):
    if _dir is None:
        raise RuntimeError("plugin_settings is not initialized")
    safe = "".join(c for c in str(plugin_id) if c.isalnum() or c in "_-")
    if not safe:
        raise ValueError("invalid plugin id")
    return os.path.join(_dir, safe + ".json")


def _load(plugin_id):
    data = _cache.get(plugin_id)
    if data is not None:
        return data
    data = {}
    try:
        with open(_path(plugin_id), "r", encoding="utf-8") as f:
            loaded = json.load(f)
        if isinstance(loaded, dict):
            data = loaded
    except FileNotFoundError:
        pass
    except Exception:
        data = {}
    _cache[plugin_id] = data
    return data


def _save(plugin_id, data):
    path = _path(plugin_id)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, path)


def _plain(value):
    """Converts boxed Java values to JSON-friendly Python values."""
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value]
    if isinstance(value, dict):
        return {str(k): _plain(v) for k, v in value.items()}
    try:
        json.dumps(value)
        return value
    except TypeError:
        return str(value)


def get_setting(plugin_id, key, default=None):
    with _lock:
        data = _load(plugin_id)
        return data.get(str(key), default)


_MISSING = object()


def _same(a, b):
    # True == 1 in Python, but JSON keeps bool and int apart
    return type(a) is type(b) and a == b


def set_setting(plugin_id, key, value):
    """Stores ``value`` (None removes the key). Returns False when nothing changed: then the
    file is not rewritten (plugins often store the same flag/counter from hooks)."""
    key = str(key)
    with _lock:
        current = _load(plugin_id)
        old = current.get(key, _MISSING)
        if value is None:
            if old is _MISSING:
                return False
        else:
            value = _plain(value)
            if old is not _MISSING and _same(old, value):
                return False
        data = dict(current)
        if value is None:
            data.pop(key, None)
        else:
            data[key] = value
        _save(plugin_id, data)
        _cache[plugin_id] = data
        return True


def clear_settings(plugin_id):
    with _lock:
        _cache.pop(plugin_id, None)
        try:
            os.remove(_path(plugin_id))
        except FileNotFoundError:
            pass


def get_all_settings(plugin_id):
    with _lock:
        return dict(_load(plugin_id))


def replace_all_settings(plugin_id, values):
    with _lock:
        data = {str(k): _plain(v) for k, v in dict(values or {}).items() if v is not None}
        _save(plugin_id, data)
        _cache[plugin_id] = data


def set_all_settings(plugin_id, values):
    """exteraGram name: replaces all stored settings of ``plugin_id``."""
    replace_all_settings(plugin_id, values)


# exteraGram internal names some libraries (e.g. zwylib) touch directly:
# ``_settings_cache[plugin_id]`` is the live dict of a plugin and
# ``_save_settings_to_file()`` writes the cached plugins back to disk.
_settings_cache = _cache


def _save_settings_to_file(plugin_id=None):
    with _lock:
        ids = [plugin_id] if plugin_id is not None else list(_cache)
        for pid in ids:
            data = _cache.get(pid)
            if data is not None:
                _save(pid, data)
