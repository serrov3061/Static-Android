"""``elyx``: the import surface for Elyx plugin code (see elyxcore)."""

from elyxcore import *  # noqa: F401,F403
from elyxcore import __all__, _public_api, get_environment  # noqa: F401

_ENV_NAMES = ("assets", "metainfo", "refmap", "settings", "strings")


def __getattr__(name):
    if name in _ENV_NAMES:
        try:
            env = get_environment()
        except RuntimeError as e:
            raise AttributeError("elyx.%s: %s" % (name, e)) from None
        if name not in env:
            raise AttributeError("elyx.%s is not available for this plugin" % name)
        return env[name]
    raise AttributeError("module 'elyx' has no attribute %r" % name)
