"""Engine speedups (docs/plugins-engine-13.1.1.md): caches must never change results."""

import json
import os
import sys

import pytest


# ---------------------------------------------------------------- metadata cache

from extera_utils import metadata_parser


PLUGIN = '__id__ = "cached"\n__name__ = "Cached"\n__version__ = "%s"\n'


@pytest.fixture
def md_cache(tmp_path):
    metadata_parser.clear_cache()
    yield str(tmp_path / ".cache" / "metadata.json")
    metadata_parser.clear_cache()


def test_metadata_cache_same_result_and_reused(tmp_path, md_cache, monkeypatch):
    p = tmp_path / "cached.py"
    p.write_text(PLUGIN % "1.0")
    first = metadata_parser.get_metadata_cached(str(p), md_cache)
    assert first == metadata_parser.get_metadata(str(p))
    assert os.path.isfile(md_cache)

    # a hit must not parse the source again (also after a "restart": in-memory cache dropped)
    metadata_parser.clear_cache()
    monkeypatch.setattr(metadata_parser, "parse_source", lambda *a, **k: pytest.fail("parsed on a cache hit"))
    assert metadata_parser.get_metadata_cached(str(p), md_cache) == first


def test_metadata_cache_invalidated_by_content(tmp_path, md_cache):
    p = tmp_path / "cached.py"
    p.write_text(PLUGIN % "1.0")
    assert metadata_parser.get_metadata_cached(str(p), md_cache)["version"] == "1.0"
    st = os.stat(p)
    p.write_text(PLUGIN % "2.0")
    os.utime(p, ns=(st.st_atime_ns, st.st_mtime_ns))  # same size and mtime, other content
    assert metadata_parser.get_metadata_cached(str(p), md_cache)["version"] == "2.0"


def test_metadata_cache_errors_not_cached(tmp_path, md_cache):
    p = tmp_path / "broken.py"
    p.write_text("__id__ = (\n")
    with pytest.raises(metadata_parser.MetadataError):
        metadata_parser.get_metadata_cached(str(p), md_cache)
    p.write_text('__id__ = "broken"\n__name__ = "B"\n')
    assert metadata_parser.get_metadata_cached(str(p), md_cache)["id"] == "broken"


def test_metadata_cache_drops_deleted_and_survives_garbage(tmp_path, md_cache):
    a, b = tmp_path / "a1.py", tmp_path / "b1.py"
    a.write_text('__id__ = "a1"\n__name__ = "A"\n')
    b.write_text('__id__ = "b1"\n__name__ = "B"\n')
    metadata_parser.get_metadata_cached(str(a), md_cache)
    os.remove(a)
    metadata_parser.get_metadata_cached(str(b), md_cache)
    with open(md_cache) as f:
        assert list(json.load(f)["e"]) == [str(b)]

    metadata_parser.clear_cache()
    with open(md_cache, "w") as f:
        f.write("{not json")
    assert metadata_parser.get_metadata_cached(str(b), md_cache)["id"] == "b1"


def test_metadata_cache_too_large(tmp_path, md_cache, monkeypatch):
    monkeypatch.setattr(metadata_parser, "MAX_FILE_SIZE", 10)
    p = tmp_path / "big.py"
    p.write_text('__id__ = "big"\n__name__ = "Big"\n')
    with pytest.raises(metadata_parser.MetadataError):
        metadata_parser.get_metadata_cached(str(p), md_cache)


# ---------------------------------------------------------------- reflection caches (hooks)

class _Field:
    def __init__(self, name, value=None):
        self.name, self.value, self.accessible = name, value, False

    def setAccessible(self, flag):
        self.accessible = flag

    def get(self, obj):
        return self.value

    def set(self, obj, value):
        self.value = value

    def getType(self):
        return types_ns(getName=lambda: "java.lang.Object")


def types_ns(**kw):
    import types
    return types.SimpleNamespace(**kw)


class _Class:
    """Minimal java.lang.Class: counts getDeclaredField calls."""

    def __init__(self, fields=None, superclass=None):
        self.fields, self.superclass, self.lookups = dict(fields or {}), superclass, 0

    def getDeclaredField(self, name):
        self.lookups += 1
        if name not in self.fields:
            raise Exception("NoSuchFieldException: " + name)
        return self.fields[name]

    def getSuperclass(self):
        return self.superclass


class _Obj:
    def __init__(self, cls):
        self._cls = cls

    def getClass(self):
        return self._cls


def test_private_field_lookup_is_cached_through_superclasses():
    import hook_utils
    base = _Class({"mValue": _Field("mValue", 42)})
    sub = _Class({}, superclass=base)
    obj = _Obj(sub)
    for _ in range(5):
        assert hook_utils.get_private_field(obj, "mValue") == 42
    assert (sub.lookups, base.lookups) == (1, 1)
    assert base.fields["mValue"].accessible is True

    assert hook_utils.set_private_field(obj, "mValue", "x") is True
    assert hook_utils.get_private_field(obj, "mValue") == "x"
    assert (sub.lookups, base.lookups) == (1, 1)


def test_missing_field_is_cached_and_still_none():
    import hook_utils
    cls = _Class({})
    obj = _Obj(cls)
    assert hook_utils.get_private_field(obj, "nope") is None
    assert hook_utils.get_private_field(obj, "nope") is None
    assert hook_utils.set_private_field(obj, "nope", 1) is False
    assert cls.lookups == 1


def test_field_cache_is_per_class():
    import hook_utils
    a = _Class({"f": _Field("f", "a")})
    b = _Class({"f": _Field("f", "b")})
    assert hook_utils.get_private_field(_Obj(a), "f") == "a"
    assert hook_utils.get_private_field(_Obj(b), "f") == "b"


def test_find_class_cached_including_misses(java):
    import hook_utils
    Class = java.jclass("java.lang.Class")
    calls = []

    def for_name(name, init, loader):
        calls.append(name)
        if name == "x.Missing":
            raise Exception("ClassNotFoundException")
        return types_ns(getName=lambda: name)

    Class.forName.side_effect = for_name
    try:
        a = hook_utils.find_class("x.Present")
        assert a is not None and hook_utils.find_class("x.Present") is a
        assert hook_utils.find_class("x.Missing") is None
        assert hook_utils.find_class("x.Missing") is None
        assert calls == ["x.Present", "x.Missing"]
    finally:
        Class.forName.side_effect = None


def test_class_for_name_reuses_loader_and_class(java):
    import _sg_java
    Class = java.jclass("java.lang.Class")
    app = java.jclass("org.telegram.messenger.ApplicationLoader")
    sentinel = object()
    original = Class.forName.return_value
    Class.forName.return_value = sentinel
    try:
        assert _sg_java.class_for_name("a.B") is sentinel
        assert _sg_java.class_for_name("a.B") is sentinel
        assert Class.forName.call_count == 1
        assert app.applicationContext.getClassLoader.call_count == 1
    finally:
        Class.forName.return_value = original


def test_plugins_controller_is_cached(java):
    import _sg_java
    ctrl_cls = java.jclass("com.exteragram.messenger.plugins.PluginsController")
    first = _sg_java.plugins_controller()
    assert _sg_java.plugins_controller() is first
    assert ctrl_cls.getInstance.call_count == 1


# ---------------------------------------------------------------- settings: no rewrite when unchanged

def test_set_setting_skips_unchanged_writes(settings_dir, monkeypatch):
    import plugin_settings
    saves = []
    real_save = plugin_settings._save
    monkeypatch.setattr(plugin_settings, "_save", lambda pid, data: (saves.append(dict(data)), real_save(pid, data)))

    assert plugin_settings.set_setting("p1", "flag", True) is True
    assert plugin_settings.set_setting("p1", "flag", True) is False
    assert plugin_settings.set_setting("p1", "flag", 1) is True      # bool -> int is a change
    assert plugin_settings.set_setting("p1", "flag", 1) is False
    assert plugin_settings.set_setting("p1", "missing", None) is False
    assert plugin_settings.set_setting("p1", "flag", None) is True   # removal
    assert plugin_settings.set_setting("p1", "lst", [1, "a"]) is True
    assert plugin_settings.set_setting("p1", "lst", (1, "a")) is False  # same JSON value
    assert len(saves) == 4

    # what is on disk is what get_setting returns after a "restart"
    plugin_settings.init(str(settings_dir))
    assert plugin_settings.get_setting("p1", "flag", "d") == "d"
    assert plugin_settings.get_setting("p1", "lst") == [1, "a"]


def test_base_plugin_set_setting_unchanged_does_not_invalidate(settings_dir, java, controller):
    import base_plugin

    class P(base_plugin.BasePlugin):
        pass

    p = P()
    p.id = "p2"
    engine = java.jclass("com.exteragram.messenger.plugins.PythonPluginsEngine")
    engine.invalidateSettingsCache.reset_mock()
    p.set_setting("k", "v")
    assert engine.invalidateSettingsCache.call_count == 1
    p.set_setting("k", "v")
    assert engine.invalidateSettingsCache.call_count == 1
    controller.loadPluginSettings.reset_mock()
    p.set_setting("k", "v", reload_settings=True)
    controller.loadPluginSettings.assert_called_once_with("p2")
    assert p.get_setting("k") == "v"
