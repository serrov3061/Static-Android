"""Elyx helpers: Java callback proxy generators, MVEL execution, LazyDict."""

import functools
import threading
import traceback

from java import dynamic_proxy

from _sg_java import J, PyRunnable, file_log

__all__ = ["gen", "gen2", "mvel_execute", "LazyDict", "safe_func", "safe_run_on_queue", "hash_map"]


def _log_error(where):
    file_log("%s failed: %s" % (where, traceback.format_exc()), error=True)


def safe_func(fn, default=None, where="callback"):
    """Wraps ``fn`` so exceptions are logged instead of crossing into Java."""
    @functools.wraps(fn)
    def _fn(*args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except Exception:
            _log_error(where)
            return default
    return _fn


def safe_run_on_queue(fn, queue=None, delay=0):
    from client_utils import PLUGINS_QUEUE, run_on_queue
    run_on_queue(safe_func(fn, where="queued task"), queue or PLUGINS_QUEUE, delay)


_gen_cache = {}
_gen_lock = threading.Lock()


def _iface(java_class):
    name = getattr(java_class, "_chaquopy_name", None) or getattr(java_class, "__name__", None)
    return java_class, str(name)


def gen(java_class, method_name, return_value=False, default_value=None):
    """Cached proxy class implementing one method of a Java interface. Instances take
    ``(fn, *extra)``; the Java arguments are passed first, then ``extra``."""
    if default_value is not None and not return_value:
        raise ValueError("default_value is only valid with return_value=True")
    key = (_iface(java_class)[1], method_name, bool(return_value), default_value)
    with _gen_lock:
        cls = _gen_cache.get(key)
        if cls is not None:
            return cls

        def __init__(self, fn, *extra):
            dynamic_base.__init__(self)
            self._fn = fn
            self._extra = extra

        def _run(self, *args):
            try:
                result = self._fn(*(args + self._extra))
                return result if return_value else None
            except Exception:
                _log_error("%s.%s" % (key[0], method_name))
                return default_value if return_value else None

        dynamic_base = dynamic_proxy(java_class)
        cls = type("Gen_%s" % method_name, (dynamic_base,), {"__init__": __init__, "_run": _run, method_name: _run})
        _gen_cache[key] = cls
        return cls


def gen2(java_class, return_value=False, **methods):
    """Proxy class for an interface with several methods given as ``name=callable``."""
    dynamic_base = dynamic_proxy(java_class)

    def __init__(self, *extra):
        dynamic_base.__init__(self)
        self._extra = extra

    def _run(name, fn):
        def _fn(self, *args):
            try:
                result = fn(*(args + self._extra))
                return result if return_value else None
            except Exception:
                _log_error("%s.%s" % (_iface(java_class)[1], name))
                return None
        _fn.__name__ = name
        return _fn

    attrs = {"__init__": __init__}
    for name, fn in methods.items():
        if not callable(fn):
            raise TypeError("%s must be callable" % name)
        attrs[name] = _run(name, fn)
    return type("Gen2", (dynamic_base,), attrs)


def hash_map(data):
    if data is None:
        return J("java.util.HashMap")()
    if not isinstance(data, dict):
        return data
    m = J("java.util.HashMap")()
    for k, v in data.items():
        m.put(k, v)
    return m


_mvel_cache = {}


def mvel_execute(script, data, to_type=None, java_instance=None):
    """Compiles (cached) and runs an MVEL expression with ``data`` (dict or HashMap) as
    variables; ``to_type`` converts the result, ``java_instance`` is ``this``."""
    if not isinstance(data, dict) and not J("java.util.Map").isInstance(data):
        raise TypeError("data must be a dict or a java.util.HashMap")
    MVEL = J("org.mvel2.MVEL")
    compiled = _mvel_cache.get(script)
    if compiled is None:
        compiled = _mvel_cache[script] = MVEL.compileExpression(str(script))
    variables = hash_map(data)
    if java_instance is not None:
        result = MVEL.executeExpression(compiled, java_instance, variables)
    else:
        result = MVEL.executeExpression(compiled, variables)
    if to_type is not None and result is not None:
        try:
            result = J("org.mvel2.DataConversion").convert(result, to_type)
        except Exception:
            pass
    return result


class LazyDict(dict):
    """A dict with attribute access; missing attributes raise KeyError."""

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        return self[name]

    def __setattr__(self, name, value):
        self[name] = value
