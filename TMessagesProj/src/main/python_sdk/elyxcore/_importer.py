"""Elyx import isolation (private runtime module).

Every installed Elyx plugin gets its own module namespace ``ElyxPlugins.<plugin_id>.*`` that
maps 1:1 onto its installed directory, so two plugins may both ship ``src/feature.py``
without sharing ``sys.modules`` entries.

* ``ElyxImporter`` is a meta path finder/loader for the ``ElyxPlugins`` namespace: packages
  (``__init__.py`` / ``__init__.pyc``), namespace directories, ``.py`` and ``.pyc`` modules
  (bytecode must carry this runtime's magic number; ``co_filename`` is rewritten to the
  installed path) and data modules (``.json``, ``.yaml``, ``.yml``, ``.txt``).
* Modules of a plugin execute with plugin-scoped builtins whose ``__import__`` resolves
  top-level local names (``from src.feature import X``) to the plugin namespace, and
  ``elyx`` to a per-plugin environment module (``from elyx import assets, strings``).
  Reserved SDK / Java roots always go to the normal import system.
"""

import builtins
import contextvars
import importlib
import importlib.abc
import importlib.machinery
import importlib.util
import json
import marshal
import pathlib
import sys
import threading
import types
from typing import Dict, List, Optional

__all__ = ["ElyxImporter", "importer", "PLUGIN_NAMESPACE", "PUBLIC_MODULE", "setup", "stop"]

PLUGIN_NAMESPACE = "ElyxPlugins"
PUBLIC_MODULE = "elyx"
_DATA_SUFFIXES = (".json", ".yaml", ".yml", ".txt")
_EXTERNAL_ROOTS = frozenset({
    "android", "androidx", "com", "de", "org", "java", "javax", "kotlin", "dalvik", "importlib",
    "elyx", "elyxcore", PLUGIN_NAMESPACE,
    # plugin SDK modules
    "_sdk_version", "_sg_java", "_sg_native", "base_plugin", "plugin_settings", "hook_utils", "client_utils",
    "android_utils", "file_utils", "log_utils", "markdown_utils", "intents", "dev_server", "ui", "extera_utils",
    # interpreter internals that must never be shadowed
    "builtins", "sys", "marshal", "_io", "_thread", "_imp", "_frozen_importlib", "_frozen_importlib_external",
    "__future__",
})

_active_plugin_id: contextvars.ContextVar = contextvars.ContextVar("elyx_active_plugin_id", default=None)


class _DataModule(types.ModuleType):
    """A JSON / YAML / text file imported as a module."""

    def __init__(self, name, path):
        super().__init__(name)
        self.__file__ = str(path)
        suffix = pathlib.Path(path).suffix.lower()
        text = pathlib.Path(path).read_text(encoding="utf-8-sig")
        if suffix == ".txt":
            data = text
        elif suffix == ".json":
            data = json.loads(text)
        else:
            import yaml
            data = yaml.safe_load(text)
        object.__setattr__(self, "_data", _wrap(data))

    @property
    def content(self):
        return self._data

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        data = object.__getattribute__(self, "_data")
        if isinstance(data, dict) and name in data:
            return data[name]
        raise AttributeError("data module %r has no key %r" % (self.__name__, name))

    def __getitem__(self, key):
        return self._data[key]

    def __contains__(self, key):
        try:
            return key in self._data
        except TypeError:
            return False

    def get(self, key, default=None):
        data = self._data
        if isinstance(data, dict):
            return data.get(key, default)
        return default

    def __iter__(self):
        return iter(self._data)

    def __len__(self):
        return len(self._data)

    def __str__(self):
        return self._data if isinstance(self._data, str) else json.dumps(self._data, ensure_ascii=False)


def _wrap(value):
    from elyxcore.utils import LazyDict
    if isinstance(value, dict):
        return LazyDict({k: _wrap(v) for k, v in value.items()})
    if isinstance(value, list):
        return [_wrap(v) for v in value]
    return value


def _patch_code_filename(code, filename):
    """Rewrites ``co_filename`` of a code object tree (pyc files keep the build machine path)."""
    consts = tuple(_patch_code_filename(c, filename) if isinstance(c, types.CodeType) else c
                   for c in code.co_consts)
    return code.replace(co_filename=filename, co_consts=consts)


class _ElyxEnvironment(types.ModuleType):
    """The ``elyx`` module as seen by one plugin: the public API plus its bound environment."""

    _ENV_NAMES = ("assets", "metainfo", "refmap", "settings", "strings")

    def __init__(self, plugin):
        super().__init__(PUBLIC_MODULE)
        import elyxcore
        object.__setattr__(self, "_plugin", plugin)
        self.__dict__.update(elyxcore._public_api())
        self.__all__ = elyxcore.__all__
        self.__doc__ = elyxcore.__doc__
        self.__dict__["get_environment"] = self._get_environment
        self.__dict__["import_module"] = self.import_module

    def _get_environment(self):
        return self._plugin.environment

    def import_module(self, name, package=None):
        return importer.import_module(name, package, plugin=self._plugin)

    def __getattr__(self, name):
        if name in self._ENV_NAMES:
            env = self._plugin.environment
            if name in env:
                return env[name]
            raise AttributeError("elyx.%s is not available for plugin %s" % (name, self._plugin.plugin_id))
        raise AttributeError("module 'elyx' has no attribute %r" % name)


class _PluginEntry:
    """Importer state of one plugin."""

    def __init__(self, plugin_id, root, environment=None, entry_rel=None, wheel_roots=()):
        self.plugin_id = plugin_id
        self.root = pathlib.Path(root).resolve()
        self.prefix = "%s.%s" % (PLUGIN_NAMESPACE, plugin_id)
        self.environment = environment if environment is not None else {}
        self.wheel_roots = [str(p) for p in wheel_roots]
        self._builtins = None
        self._env_module = None
        # roots searched for top-level local imports: the entry module's directory, then the root
        roots = []
        if entry_rel:
            parent = pathlib.PurePosixPath(entry_rel).parent
            if str(parent) not in ("", "."):
                roots.append(tuple(parent.parts))
        roots.append(())
        self.search_roots = roots

    @property
    def env_module(self):
        if self._env_module is None:
            self._env_module = _ElyxEnvironment(self)
        return self._env_module

    def qualified(self, parts):
        return ".".join((self.prefix,) + tuple(parts)) if parts else self.prefix

    def builtins(self):
        if self._builtins is None:
            scoped = dict(builtins.__dict__)
            real_import = builtins.__import__
            entry = self

            def scoped_import(name, globals=None, locals=None, fromlist=(), level=0):
                if level == 0 and name:
                    top = name.partition(".")[0]
                    if top == PUBLIC_MODULE:
                        if name == PUBLIC_MODULE:
                            return entry.env_module
                    elif top not in _EXTERNAL_ROOTS:
                        root = importer.local_root(entry, top)
                        if root is not None:
                            qualified = entry.qualified(root + tuple(name.split(".")))
                            if fromlist:
                                # the real __import__ also imports submodules named in fromlist
                                return real_import(qualified, globals, locals, fromlist, 0)
                            importlib.import_module(qualified)
                            return sys.modules[entry.qualified(root + (top,))]
                return real_import(name, globals, locals, fromlist, level)

            scoped["__import__"] = scoped_import
            self._builtins = scoped
        return self._builtins


def _module_candidates(base: pathlib.Path):
    """(kind, path) of what ``base`` (a path without suffix) resolves to, in import priority."""
    if base.is_dir():
        for init in ("__init__.py", "__init__.pyc"):
            if (base / init).is_file():
                return "package", base / init
    py = base.with_name(base.name + ".py")
    if py.is_file():
        return "source", py
    pyc = base.with_name(base.name + ".pyc")
    if pyc.is_file():
        return "bytecode", pyc
    for suffix in _DATA_SUFFIXES:
        data = base.with_name(base.name + suffix)
        if data.is_file():
            return "data", data
    if base.is_dir():
        return "namespace", base
    return None


class ElyxImporter(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    def __init__(self):
        self._plugins: Dict[str, _PluginEntry] = {}
        self._lock = threading.RLock()
        self._plugins_dir: Optional[pathlib.Path] = None

    # ----- registration

    def set_plugins_dir(self, path):
        self._plugins_dir = pathlib.Path(path).resolve() if path else None

    @property
    def elyx_plugins_dir(self) -> Optional[pathlib.Path]:
        return self._plugins_dir / PLUGIN_NAMESPACE if self._plugins_dir is not None else None

    def register_plugin(self, plugin_id, root, environment=None, entry_rel=None, wheel_roots=()):
        with self._lock:
            self.unload_plugin(plugin_id)
            entry = _PluginEntry(plugin_id, root, environment, entry_rel, wheel_roots)
            self._plugins[plugin_id] = entry
            return entry

    def get_plugin(self, plugin_id) -> Optional[_PluginEntry]:
        return self._plugins.get(plugin_id)

    def plugin_ids(self) -> List[str]:
        return list(self._plugins)

    def install(self):
        if self not in sys.meta_path:
            sys.meta_path.insert(0, self)

    def uninstall(self):
        while self in sys.meta_path:
            sys.meta_path.remove(self)

    # ----- local resolution

    def local_root(self, entry: _PluginEntry, top: str):
        """The search root (as path parts relative to the plugin root) providing ``top``."""
        for root in entry.search_roots:
            if _module_candidates(entry.root.joinpath(*root, top)) is not None:
                return root
        return None

    def _resolve(self, fullname):
        """(entry, parts) for a module inside the namespace; parts are relative to the plugin root."""
        parts = fullname.split(".")
        if parts[0] != PLUGIN_NAMESPACE or len(parts) < 2:
            return None, None
        entry = self._plugins.get(parts[1])
        if entry is None:
            return None, None
        return entry, parts[2:]

    # ----- MetaPathFinder

    def find_spec(self, fullname, path=None, target=None):
        if fullname == PLUGIN_NAMESPACE:
            spec = importlib.machinery.ModuleSpec(fullname, self, is_package=True)
            spec.submodule_search_locations = []
            spec.loader_state = ("root", None, None)
            return spec
        if not fullname.startswith(PLUGIN_NAMESPACE + "."):
            return None
        entry, parts = self._resolve(fullname)
        if entry is None:
            return None
        if not parts:
            init = next((entry.root / n for n in ("__init__.py", "__init__.pyc") if (entry.root / n).is_file()), None)
            kind = ("package", init) if init is not None else ("namespace", entry.root)
        else:
            if any(p.startswith(".") or p in ("", "__pycache__") for p in parts):
                return None
            kind = _module_candidates(entry.root.joinpath(*parts))
            if kind is None:
                return None
        kind, file_path = kind
        is_package = kind in ("package", "namespace")
        spec = importlib.machinery.ModuleSpec(fullname, self, is_package=is_package,
                                              origin=str(file_path) if kind != "namespace" else None)
        if is_package:
            spec.submodule_search_locations = [str(entry.root.joinpath(*parts))]
        spec.has_location = kind != "namespace"
        spec.loader_state = (kind, file_path, entry.plugin_id)
        return spec

    def invalidate_caches(self):
        pass

    # ----- Loader

    def create_module(self, spec):
        kind = spec.loader_state[0]
        if kind == "data":
            return _DataModule(spec.name, spec.loader_state[1])
        return None

    def exec_module(self, module):
        kind, file_path, plugin_id = module.__spec__.loader_state
        if kind in ("root", "namespace", "data"):
            if kind != "data":
                module.__path__ = list(module.__spec__.submodule_search_locations or [])
            if plugin_id:
                module.__dict__["__elyx_plugin__"] = plugin_id
            return
        entry = self._plugins.get(plugin_id)
        if entry is None:
            raise ImportError("Elyx plugin %s is not loaded" % plugin_id, name=module.__name__)
        path = pathlib.Path(file_path)
        module.__file__ = str(path)
        module.__dict__["__builtins__"] = entry.builtins()
        module.__dict__["__elyx_plugin__"] = plugin_id
        if kind == "bytecode" or path.suffix == ".pyc":
            data = path.read_bytes()
            if data[:4] != importlib.util.MAGIC_NUMBER:
                raise ImportError("Incompatible bytecode file: %s (compiled for another Python version, "
                                  "build it with Python %d.%d)" % (path, sys.version_info[0], sys.version_info[1]),
                                  name=module.__name__, path=str(path))
            code = _patch_code_filename(marshal.loads(data[16:]), str(path))
        else:
            source = path.read_bytes()
            code = compile(source, str(path), "exec", dont_inherit=True)
        token = _active_plugin_id.set(plugin_id)
        try:
            exec(code, module.__dict__)
        except BaseException as e:
            if isinstance(e, Exception) and hasattr(e, "add_note"):
                e.add_note("Failed to load Elyx module %s from %s" % (module.__name__, path))
            raise
        finally:
            _active_plugin_id.reset(token)

    # ----- API

    def import_main(self, plugin_id, main_rel):
        entry = self._plugins[plugin_id]
        parts = list(pathlib.PurePosixPath(main_rel).parts)
        last = parts[-1]
        for suffix in (".pyc", ".py"):
            if last.endswith(suffix):
                last = last[: -len(suffix)]
                break
        parts[-1] = last
        token = _active_plugin_id.set(plugin_id)
        try:
            return importlib.import_module(entry.qualified(tuple(parts)))
        finally:
            _active_plugin_id.reset(token)

    def import_module(self, name, package=None, plugin=None):
        """Imports ``name`` from the calling plugin when it exists locally, else normally."""
        entry = plugin if plugin is not None else self.get_caller_plugin()
        if entry is not None and name and not name.startswith("."):
            top = name.partition(".")[0]
            if top == PUBLIC_MODULE and name == PUBLIC_MODULE:
                return entry.env_module
            if top not in _EXTERNAL_ROOTS:
                root = self.local_root(entry, top)
                if root is not None:
                    return importlib.import_module(entry.qualified(root + tuple(name.split("."))))
        if entry is not None and name.startswith(".") and package is None:
            frame_pkg = self._caller_package(entry)
            if frame_pkg:
                package = frame_pkg
        return importlib.import_module(name, package)

    def _caller_package(self, entry):
        frame = sys._getframe(1)
        while frame is not None:
            g = frame.f_globals
            if g.get("__elyx_plugin__") == entry.plugin_id:
                return g.get("__package__")
            frame = frame.f_back
        return None

    def _plugin_for_root(self, path) -> Optional[_PluginEntry]:
        try:
            real = pathlib.Path(path).resolve()
        except Exception:
            return None
        for entry in list(self._plugins.values()):
            if real == entry.root or entry.root in real.parents:
                return entry
        return None

    def _root_from_filename(self, filename):
        if not filename or filename.startswith("<"):
            return None
        return self._plugin_for_root(filename)

    def get_caller_plugin(self, frame=None) -> Optional[_PluginEntry]:
        """The plugin whose code is on the stack (nearest frame first)."""
        if frame is None:
            frame = sys._getframe(1)
        while frame is not None:
            pid = frame.f_globals.get("__elyx_plugin__")
            if pid and pid in self._plugins:
                return self._plugins[pid]
            entry = self._root_from_filename(frame.f_code.co_filename)
            if entry is not None:
                return entry
            frame = frame.f_back
        pid = _active_plugin_id.get()
        return self._plugins.get(pid) if pid else None

    def get_caller_root(self, frame=None) -> Optional[pathlib.Path]:
        entry = self.get_caller_plugin(frame)
        return entry.root if entry is not None else None

    def unload_plugin(self, plugin_id) -> int:
        """Drops the plugin's modules and import caches. Returns the number of removed modules."""
        with self._lock:
            entry = self._plugins.pop(plugin_id, None)
            prefix = "%s.%s" % (PLUGIN_NAMESPACE, plugin_id)
            removed = 0
            for name in list(sys.modules):
                if name == prefix or name.startswith(prefix + "."):
                    sys.modules.pop(name, None)
                    removed += 1
            if entry is not None:
                roots = [str(entry.root)] + list(entry.wheel_roots)
                for key in list(sys.path_importer_cache):
                    if any(str(key) == r or str(key).startswith(r + "/") for r in roots):
                        sys.path_importer_cache.pop(key, None)
                for wheel_root in entry.wheel_roots:
                    while wheel_root in sys.path:
                        sys.path.remove(wheel_root)
                    for name, mod in list(sys.modules.items()):
                        f = getattr(mod, "__file__", None) or ""
                        if f.startswith(wheel_root + "/"):
                            sys.modules.pop(name, None)
            importlib.invalidate_caches()
            return removed

    def unload_all(self):
        for pid in list(self._plugins):
            self.unload_plugin(pid)


importer = ElyxImporter()


def setup(plugins_dir=None):
    if plugins_dir is not None:
        importer.set_plugins_dir(plugins_dir)
    importer.install()
    return importer


def stop():
    importer.unload_all()
    importer.uninstall()
    sys.modules.pop(PLUGIN_NAMESPACE, None)
