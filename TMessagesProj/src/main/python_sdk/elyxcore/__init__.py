"""Elyx public API (StaticGram open SDK). Plugin code imports it as ``elyx``.

Structured plugins (.elyx / .eaf archives) are installed by the Elyx engine
(``elyxcore._engine`` + Java ``ElyxPluginsEngine``); their modules see a plugin-bound
``elyx`` module (``from elyx import assets, metainfo, refmap, settings, strings``). The
standalone helpers (assets, strings, settings, Java callback proxies, MVEL, LazyDict) work
in any plugin. ``get_environment()`` raises RuntimeError outside Elyx plugin code.
"""

from typing import Any, Dict

from java import jclass

from elyxcore.assets import Asset, AssetNotFoundException, Assets, AssetsDirNotFoundException
from elyxcore.localization import Strings
from elyxcore.settings import SettingsController
from elyxcore.utils import LazyDict, gen, gen2, mvel_execute

OnClickListener = gen(jclass("android.view.View$OnClickListener"), "onClick")
Runnable = gen(jclass("java.lang.Runnable"), "run")
Callback = gen(jclass("org.telegram.messenger.Utilities$Callback"), "run")
Callback2 = gen(jclass("org.telegram.messenger.Utilities$Callback2"), "run")
Callback3 = gen(jclass("org.telegram.messenger.Utilities$Callback3"), "run")
CallbackReturn = gen(jclass("org.telegram.messenger.Utilities$CallbackReturn"), "run", True)



def get_environment() -> Dict[str, Any]:
    """Assets, settings, strings and metadata of the calling Elyx plugin."""
    from elyxcore._importer import importer
    plugin = importer.get_caller_plugin()
    if plugin is None:
        raise RuntimeError("Elyx environment is only available from plugin code")
    return plugin.environment


def import_module(name: str, package: str = None):
    """Imports ``name`` relative to the calling plugin when it exists locally, else with the
    normal import rules."""
    from elyxcore._importer import importer
    return importer.import_module(name, package)


__all__ = (
    "Asset", "AssetNotFoundException", "Assets", "AssetsDirNotFoundException",
    "Callback", "Callback2", "Callback3", "CallbackReturn", "LazyDict", "OnClickListener", "Runnable",
    "SettingsController", "Strings", "gen", "gen2", "get_environment", "import_module", "mvel_execute",
)


def _public_api() -> Dict[str, Any]:
    return {name: globals()[name] for name in __all__}


def _setup(plugins_dir=None):
    from elyxcore._engine import setup
    return setup(plugins_dir)


def _stop():
    from elyxcore._engine import stop
    return stop()
