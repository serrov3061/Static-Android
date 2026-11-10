"""StaticGram open plugin SDK: version and bootstrap.

This is an independent, clean-room implementation of the publicly documented
exteraGram plugin API (https://plugins.exteragram.app/docs, SDK 1.4.5.7).
It is plain Python and ships inside the exteraStatic APK as Chaquopy app sources.

The Java engine (PythonPluginsEngine.initSdk) imports this module, calls
``__start__()`` and reads ``__version__`` / ``__beta__``.
"""

import sys

__version__ = "1.4.5.7"
__api_version__ = "1.4.5.7"
__beta__ = False

# Top-level module names provided by the SDK. The engine uses this list to refuse
# plugin ids and pip dependencies that would shadow an SDK module.
TOP_LEVEL_MODULES = (
    "_sdk_version",
    "_sg_java",
    "_sg_native",
    "base_plugin",
    "plugin_settings",
    "hook_utils",
    "client_utils",
    "android_utils",
    "file_utils",
    "log_utils",
    "markdown_utils",
    "intents",
    "dev_server",
    "ui",
    "extera_utils",
    "elyx",
    "elyxcore",
    "ElyxPlugins",  # namespace of installed Elyx plugins (elyxcore._importer)
)

_started = False


def __start__():
    """Called by the engine once per Python runtime start. Returns True on success."""
    global _started
    _started = True
    return True


def __stop__():
    """Called by the engine on shutdown: removes the SDK's own Java hooks."""
    global _started
    _started = False
    for mod, attr in (("intents", "IntentsManager"), ("file_utils", "FilesController")):
        module = sys.modules.get(mod)
        if module is None:
            continue
        try:
            getattr(module, attr)._stop()
        except Exception:
            pass
    engine = sys.modules.get("elyxcore._engine")
    if engine is not None:
        try:
            engine.stop()
        except Exception:
            pass
    return True


def is_started():
    return _started
