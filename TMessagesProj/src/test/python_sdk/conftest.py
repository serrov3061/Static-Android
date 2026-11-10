"""Host-side test setup: a stub ``java`` module (Chaquopy is only available on device)."""

import os
import sys
import types
from unittest import mock

import pytest

HERE = os.path.dirname(os.path.abspath(__file__))
SDK = os.path.normpath(os.path.join(HERE, "..", "..", "main", "python_sdk"))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", "..", ".."))
SAMPLES = os.path.join(REPO, "docs", "plugins-samples")


class _JavaStub(types.ModuleType):
    def __init__(self):
        super().__init__("java")
        self.classes = {}

    def jclass(self, name):
        cls = self.classes.get(name)
        if cls is None:
            cls = mock.MagicMock(name=name)
            self.classes[name] = cls
        return cls

    @staticmethod
    def dynamic_proxy(*interfaces):
        class _Proxy:
            def __init__(self, *a, **k):
                pass
        return _Proxy

    @staticmethod
    def jarray(element_type):
        return lambda items: list(items)

    @staticmethod
    def cast(cls, obj):
        return obj


JAVA = _JavaStub()
sys.modules["java"] = JAVA
if SDK not in sys.path:
    sys.path.insert(0, SDK)


@pytest.fixture(autouse=True)
def _fresh_sdk_caches():
    """SDK lookup caches (classes, fields, controller) must not leak between tests."""
    import _sg_java
    _sg_java._class_cache.clear()
    _sg_java._app_loader = None
    _sg_java._controller = None
    hook_utils = sys.modules.get("hook_utils")
    if hook_utils is not None:
        hook_utils._clear_caches()
    yield


@pytest.fixture
def java():
    for c in JAVA.classes.values():
        c.reset_mock()
    return JAVA


@pytest.fixture
def controller(java):
    ctrl = java.jclass("com.exteragram.messenger.plugins.PluginsController").getInstance.return_value
    ctrl.reset_mock()
    return ctrl


@pytest.fixture
def settings_dir(tmp_path):
    import plugin_settings
    plugin_settings.init(str(tmp_path))
    return tmp_path
