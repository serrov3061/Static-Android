"""StaticGram plugin runtime: native (compiled) plugin code support.

Lets plugins ship compiled code the way exteraGram's Chaquopy runtime allows:

* Cython / C extension modules (``*.so``) placed next to a plugin (the plugins
  directory) or in ``plugins/libs`` are importable. Besides Chaquopy's own
  suffixes (``.cpython-311.so``, ``.abi3.so``, ``.so``) the ABI-tagged names
  produced by standard Android cross builds (``.cpython-311-aarch64-linux-android.so``
  and friends) are accepted as well.
* ``tempfile.gettempdir()`` / ``$TMPDIR`` point to an existing, writable,
  app-private directory (Chaquopy sets ``<cache>/chaquopy/tmp``), so a plugin can
  write a library there and ``ctypes.CDLL`` it.

The engine calls :func:`install` once after the SDK has started.
"""

import importlib
import importlib.machinery as _machinery
import os
import platform
import sys
import tempfile

_ANDROID_TRIPLES = {
    "aarch64": "aarch64-linux-android",
    "arm64": "aarch64-linux-android",
    "armv7l": "arm-linux-androideabi",
    "armv8l": "arm-linux-androideabi",
    "x86_64": "x86_64-linux-android",
    "i686": "i686-linux-android",
    "x86": "i686-linux-android",
}

_installed_hook = None


def extra_extension_suffixes(machine=None):
    """ABI-tagged extension suffixes accepted in addition to the interpreter's own."""
    machine = (machine or platform.machine() or "").lower()
    tag = "cpython-%d%d" % sys.version_info[:2]
    suffixes = []
    triple = _ANDROID_TRIPLES.get(machine)
    if triple:
        suffixes.append(".%s-%s.so" % (tag, triple))
    return suffixes


def extension_suffixes():
    result = []
    for suffix in list(_machinery.EXTENSION_SUFFIXES) + extra_extension_suffixes():
        if suffix not in result:
            result.append(suffix)
    return result


def _is_default_file_finder_hook(hook):
    name = getattr(hook, "__qualname__", "") or getattr(hook, "__name__", "")
    return "FileFinder" in name or "path_hook_for_FileFinder" in name


def _install_path_hook():
    global _installed_hook
    for existing in sys.path_hooks:
        # also survives an SDK restart, which re-imports this module
        if getattr(existing, "__qualname__", "") == "sg_native_path_hook_for_FileFinder":
            _installed_hook = existing
            return False
    suffixes = extension_suffixes()
    for suffix in suffixes:
        if suffix not in _machinery.EXTENSION_SUFFIXES:
            _machinery.EXTENSION_SUFFIXES.append(suffix)
    hook = _machinery.FileFinder.path_hook(
        (_machinery.ExtensionFileLoader, suffixes),
        (_machinery.SourceFileLoader, list(_machinery.SOURCE_SUFFIXES)),
        (_machinery.SourcelessFileLoader, list(_machinery.BYTECODE_SUFFIXES)),
    )
    hook.__qualname__ = "sg_native_path_hook_for_FileFinder"
    index = len(sys.path_hooks)
    for i, existing in enumerate(sys.path_hooks):
        if _is_default_file_finder_hook(existing):
            index = i
            break
    sys.path_hooks.insert(index, hook)
    _installed_hook = hook
    # finders already cached for real directories were made by the old hook
    for path, finder in list(sys.path_importer_cache.items()):
        if finder is None or isinstance(finder, _machinery.FileFinder):
            sys.path_importer_cache.pop(path, None)
    importlib.invalidate_caches()
    return True


def _ensure_dir(path):
    try:
        os.makedirs(path, exist_ok=True)
        return os.path.isdir(path) and os.access(path, os.W_OK | os.X_OK)
    except OSError:
        return False


def _ensure_tmpdir(fallback=None):
    current = os.environ.get("TMPDIR")
    if not (current and _ensure_dir(current)):
        if fallback and _ensure_dir(fallback):
            os.environ["TMPDIR"] = fallback
    tempfile.tempdir = None  # recompute from TMPDIR
    return tempfile.gettempdir()


def _add_to_sys_path(path, front=False):
    if path and os.path.isdir(path) and path not in sys.path:
        if front:
            sys.path.insert(0, path)
        else:
            sys.path.append(path)


def install(plugins_dir=None, cache_dir=None):
    """Called by the engine; safe to call more than once."""
    _install_path_hook()
    fallback = os.path.join(cache_dir, "plugins_tmp") if cache_dir else None
    tmp = _ensure_tmpdir(fallback)
    if plugins_dir:
        _add_to_sys_path(plugins_dir)
        libs = os.path.join(plugins_dir, "libs")
        _ensure_dir(libs)
        _add_to_sys_path(libs)
    return tmp
