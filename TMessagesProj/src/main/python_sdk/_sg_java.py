"""Internal helpers around Chaquopy's ``java`` module (not part of the public API)."""

import functools
import traceback

from java import jclass, dynamic_proxy

ENGINE_TAG = "sgPlugins"


@functools.lru_cache(maxsize=None)
def J(name):
    """Cached ``jclass`` lookup."""
    return jclass(name)


def try_class(name):
    try:
        return J(name)
    except Exception:
        return None


_controller = None


def plugins_controller():
    """The PluginsController singleton (cached: it never changes for the process)."""
    global _controller
    c = _controller
    if c is None:
        c = _controller = J("com.exteragram.messenger.plugins.PluginsController").getInstance()
    return c


def file_log(msg, error=False):
    if error:
        # FileLog is off in release builds: errors also go to logcat
        android_log(ENGINE_TAG, msg, "e")
    try:
        FileLog = J("org.telegram.messenger.FileLog")
        if error:
            FileLog.e(str(msg))
        else:
            FileLog.d(str(msg))
    except Exception:
        pass


def android_log(tag, msg, level="d"):
    try:
        Log = J("android.util.Log")
        getattr(Log, level)(str(tag), str(msg))
    except Exception:
        print("[%s] %s" % (tag, msg))


def safe_call(fn, *args, where="callback"):
    try:
        return fn(*args)
    except Exception:
        file_log("plugin %s failed: %s" % (where, traceback.format_exc()), error=True)
        return None


class PyRunnable(dynamic_proxy(J("java.lang.Runnable"))):
    def __init__(self, fn, *args):
        super().__init__()
        self._fn = fn
        self._args = args

    def run(self):
        safe_call(self._fn, *self._args, where="runnable")


def java_map_to_dict(m):
    """Converts a java.util.Map (or a dict) into a plain Python dict."""
    if m is None:
        return {}
    if isinstance(m, dict):
        return dict(m)
    out = {}
    try:
        it = m.entrySet().iterator()
        while it.hasNext():
            e = it.next()
            out[str(e.getKey())] = e.getValue()
    except Exception:
        try:
            for k in m.keySet().toArray():
                out[str(k)] = m.get(k)
        except Exception:
            pass
    return out


def to_java_class(obj):
    """Accepts a java.lang.Class, a Chaquopy jclass or a class name and returns java.lang.Class."""
    if obj is None:
        return None
    if isinstance(obj, str):
        return class_for_name(obj)
    Class = J("java.lang.Class")
    try:
        if isinstance(obj, Class):
            return obj
    except TypeError:
        pass
    cls = getattr(obj, "class_", None)
    if cls is not None:
        return cls
    name = getattr(obj, "_chaquopy_name", None) or getattr(obj, "__javaclass__", None)
    if name:
        return class_for_name(str(name).replace("/", "."))
    return obj


# Loaded classes by name. Hooks look classes up on every call (find_class(...).field); a lookup is
# ApplicationLoader.applicationContext + getClassLoader + Class.forName over JNI. The app class
# loader never changes, so a loaded class stays valid; failed lookups are not cached here.
_CLASS_CACHE_MAX = 4096
_class_cache = {}
_app_loader = None


def class_for_name(name):
    name = str(name)
    cls = _class_cache.get(name)
    if cls is not None:
        return cls
    global _app_loader
    loader = _app_loader
    if loader is None:
        loader = J("org.telegram.messenger.ApplicationLoader").applicationContext.getClassLoader()
        if loader is not None:
            _app_loader = loader
    cls = J("java.lang.Class").forName(name, False, loader)
    if cls is not None:
        if len(_class_cache) >= _CLASS_CACHE_MAX:
            _class_cache.clear()
        _class_cache[name] = cls
    return cls


def to_array_list(items):
    ArrayList = J("java.util.ArrayList")
    lst = ArrayList()
    for it in items or ():
        lst.add(it)
    return lst


def boxed_bool(value):
    return J("java.lang.Boolean")(bool(value))


def sdk_hook(method, handler, priority=50):
    """Hooks a Java method for the SDK itself (not owned by a plugin): ``handler`` provides
    ``before_hooked_method(param)`` and/or ``after_hooked_method(param)``.
    Returns the XC_MethodHook.Unhook."""
    cb = J("com.exteragram.messenger.plugins.xposed.PySdkHook")(handler, int(priority))
    return J("de.robv.android.xposed.XposedBridge").hookMethod(method, cb)


def find_methods(clazz, name):
    """Declared methods named ``name`` of a Java class (or class name)."""
    jcls = to_java_class(clazz)
    return [m for m in J("com.exteragram.messenger.plugins.utils.SafeReflection").getDeclaredMethods(jcls) if str(m.getName()) == name]
