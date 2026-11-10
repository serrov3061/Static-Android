"""Finds the plugin a piece of code belongs to, from the Python call stack.

A regular plugin is the module ``<plugins dir>/<id>.py`` (the engine imports it by its id),
so the plugin id of a frame is the file stem of a code object that lives directly in the
plugins directory. An Elyx plugin lives in ``<plugins dir>/ElyxPlugins/<id>/``: any file
below that directory belongs to plugin ``<id>``. Code in the SDK, the standard library or
pip packages has no plugin id.
"""

import os
import sys

__all__ = ["get_plugin_id"]

_plugins_dir = None
_elyx_dir = None


def _get_plugins_dir():
    global _plugins_dir
    if _plugins_dir is None:
        try:
            from _sg_java import plugins_controller
            path = str(plugins_controller().getPluginsDir().getAbsolutePath())
            if os.path.isabs(path):
                _plugins_dir = os.path.realpath(path)
        except Exception:
            return None
    return _plugins_dir


def _set_plugins_dir(path):
    """Used by tests and by the engine bootstrap."""
    global _plugins_dir
    _plugins_dir = os.path.realpath(path) if path else None


def _set_elyx_dir(path):
    """Set by the Elyx engine (elyxcore._engine.setup) and by tests."""
    global _elyx_dir
    _elyx_dir = os.path.realpath(path) if path else None


def _get_elyx_dir():
    if _elyx_dir is not None:
        return _elyx_dir
    plugins_dir = _get_plugins_dir()
    return os.path.join(plugins_dir, "ElyxPlugins") if plugins_dir else None


def _plugin_id_from_location(filename):
    if not filename or filename.startswith("<"):
        return None
    plugins_dir = _get_plugins_dir()
    if plugins_dir is None and _elyx_dir is None:
        return None
    try:
        real = os.path.realpath(filename)
    except Exception:
        return None
    elyx_dir = _get_elyx_dir()
    if elyx_dir and real.startswith(elyx_dir + os.sep):
        pid = real[len(elyx_dir) + 1:].split(os.sep, 1)[0]
        return pid if pid and not pid.startswith(".") else None
    if os.path.dirname(real) != plugins_dir:
        return None
    stem, ext = os.path.splitext(os.path.basename(real))
    if ext not in (".py", ".plugin", ".pyc") or not stem:
        return None
    return stem


def _format_location(frame):
    code = frame.f_code
    return "%s:%s:%d" % (os.path.basename(code.co_filename), code.co_name, frame.f_lineno)


def _get_callable_location(fn):
    code = getattr(fn, "__code__", None)
    if code is None:
        fn = getattr(fn, "__func__", None) or getattr(fn, "__call__", None)
        code = getattr(fn, "__code__", None)
    if code is None:
        return None
    return "%s:%s:%d" % (os.path.basename(code.co_filename), code.co_name, code.co_firstlineno)


def get_plugin_id(frame=None, depth=1):
    """Returns the id of the nearest plugin on the stack (starting ``depth`` frames above
    the caller, or at ``frame``), or None when no plugin code is involved."""
    if frame is None:
        try:
            frame = sys._getframe(depth + 1)
        except ValueError:
            return None
    while frame is not None:
        pid = _plugin_id_from_location(frame.f_code.co_filename)
        if pid is not None:
            return pid
        frame = frame.f_back
    return None
