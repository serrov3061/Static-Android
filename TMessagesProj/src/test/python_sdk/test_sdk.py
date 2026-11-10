import importlib.util
import json
import os
import types

import pytest

from conftest import SAMPLES


# ---------------------------------------------------------------- bootstrap

def test_sdk_version_bootstrap():
    import _sdk_version
    assert _sdk_version.__start__() is True
    assert _sdk_version.__version__ == "1.4.5.7" == _sdk_version.__api_version__
    assert _sdk_version.__beta__ is False
    for name in ("base_plugin", "plugin_settings", "ui", "extera_utils"):
        assert name in _sdk_version.TOP_LEVEL_MODULES


# ---------------------------------------------------------------- metadata

from extera_utils.metadata_parser import MetadataError, get_metadata, parse_source


def test_metadata_full():
    md = parse_source('''
__id__ = "my_plugin"
__name__ = "My Plugin"
__description__ = "Does **things**"
__author__ = "@me"
__version__ = "1.2.3"
__icon__ = "exteraPlugins/1"
__app_version__ = ">=12.5.1"
__sdk_version__ = ">= 1.4.4.3"
__requirements__ = ["requests>=2,<3", "pyyaml"]
''')
    assert md == {
        "id": "my_plugin", "name": "My Plugin", "description": "Does **things**", "author": "@me",
        "version": "1.2.3", "icon": "exteraPlugins/1", "app_version": ">=12.5.1",
        "sdk_version": ">=1.4.4.3", "requirements": "requests>=2,<3,pyyaml",
    }


def test_metadata_min_version_and_non_literals():
    md = parse_source('''
import os
__id__ = "x1"
__name__ = "X"
__version__ = os.environ.get("V")   # not a literal: ignored
__min_version__ = "11.12.0"
def f():
    __author__ = "inner"            # not top-level: ignored
''')
    assert md == {"id": "x1", "name": "X", "app_version": ">=11.12.0"}


def test_metadata_app_version_wins_over_min_version():
    md = parse_source('__id__="ab"\n__name__="A"\n__min_version__="1.0"\n__app_version__="<=13.0"\n')
    assert md["app_version"] == "<=13.0"


def test_metadata_syntax_error(tmp_path):
    p = tmp_path / "bad.py"
    p.write_text("__id__ = 'x'\ndef (:\n")
    with pytest.raises(MetadataError):
        get_metadata(str(p))


def test_metadata_is_not_executed(tmp_path):
    p = tmp_path / "evil.py"
    p.write_text("__id__='ev'\n__name__='E'\nopen(%r,'w').write('ran')\n" % str(tmp_path / "flag"))
    assert get_metadata(str(p))["id"] == "ev"
    assert not (tmp_path / "flag").exists()


# ---------------------------------------------------------------- settings rows

from ui.settings import Custom, Divider, EditText, Header, Input, Selector, Switch, Text


def test_settings_rows_types_and_defaults():
    rows = [
        Header("General"), Divider(), Divider("caption"),
        Switch(key="s", text="S", default=1),
        Selector(key="sel", text="Sel", default="2", items=["a", "b", 3]),
        Input(key="i", text="I"),
        Text(text="T", accent=True),
        EditText(key="e", hint="H", multiline=True),
        Custom(view="v"),
    ]
    assert [r.type for r in rows] == ["header", "divider", "divider", "switch", "selector", "input", "text",
                                      "edit_text", "custom"]
    assert rows[3].default is True
    assert rows[4].default == 2 and rows[4].items == ["a", "b", "3"]
    assert rows[5].default == "" and rows[7].max_length == 256
    with pytest.raises(TypeError):
        Header(text="x", type="switch")  # type is not an init argument


# ---------------------------------------------------------------- settings storage

def test_plugin_settings_roundtrip(settings_dir):
    import plugin_settings as ps
    assert ps.get_setting("p1", "k", "dflt") == "dflt"
    ps.set_setting("p1", "k", True)
    ps.set_setting("p1", "n", 5)
    assert ps.get_setting("p1", "k") is True
    assert ps.get_all_settings("p1") == {"k": True, "n": 5}
    with open(os.path.join(str(settings_dir), ".settings", "p1.json")) as f:
        assert json.load(f) == {"k": True, "n": 5}
    ps.set_setting("p1", "n", None)
    assert ps.get_all_settings("p1") == {"k": True}
    ps._cache.clear()  # re-read from disk
    assert ps.get_setting("p1", "k") is True
    ps.clear_settings("p1")
    assert ps.get_all_settings("p1") == {}


def test_plugin_settings_rejects_path_tricks(settings_dir):
    import plugin_settings as ps
    ps.set_setting("../../etc", "a", 1)
    assert os.path.exists(os.path.join(str(settings_dir), ".settings", "etc.json"))


# ---------------------------------------------------------------- base plugin

import base_plugin
from base_plugin import (AppEvent, BasePlugin, HookFilter, HookResult, HookStrategy, MenuItemData, MenuItemType,
                         MethodHook, MethodReplacement, hook_filters, split_filters)


def _module_from_source(name, src):
    mod = types.ModuleType(name)
    exec(compile(src, name + ".py", "exec"), mod.__dict__)
    return mod


def test_find_plugin_class_with_overridden_module_name():
    mod = _module_from_source("p_mod", '''
from base_plugin import BasePlugin
__id__ = "p_mod"
__name__ = "Pretty Name"          # plugins overwrite the module __name__
class Helper(BasePlugin):
    pass
class Real(Helper):
    pass
''')
    assert BasePlugin._findPluginClass(mod).__name__ == "Real"
    assert BasePlugin._findPluginClass(types.ModuleType("empty")) is None


def test_hook_strategy_and_result():
    assert str(HookStrategy.MODIFY).endswith("MODIFY")
    assert str(HookStrategy.CANCEL).endswith("CANCEL")
    assert str(HookStrategy.MODIFY_FINAL).endswith("MODIFY_FINAL")
    r = HookResult()
    assert r.strategy is HookStrategy.DEFAULT and r.params is None
    assert AppEvent("app_resume") is AppEvent.RESUME


def _plugin(pid="tp"):
    p = BasePlugin()
    p.id = pid
    return p


def test_add_hooks_register_in_controller(controller):
    p = _plugin()
    p.add_hook("TL_messages_setTyping")
    p.add_hook("updateNew", match_substring=True, priority=5)
    p.add_on_send_message_hook(priority=2)
    calls = [c.args for c in controller.addEventHook.call_args_list]
    assert calls == [("tp", "TL_messages_setTyping", False, 0), ("tp", "updateNew", True, 5),
                     ("tp", "send_message_hook", False, 2)]
    p.remove_hook("updateNew")
    controller.removeEventHook.assert_called_with("tp", "updateNew")


def test_add_menu_item_payload_and_click(controller):
    seen = {}
    controller.addMenuItem.return_value = "id-1"
    p = _plugin()
    item_id = p.add_menu_item(MenuItemData(menu_type=MenuItemType.DRAWER_MENU, text="Hi",
                                           on_click=lambda ctx: seen.update(ctx), priority=3))
    assert item_id == "id-1"
    pid, payload = controller.addMenuItem.call_args.args
    assert pid == "tp"
    assert payload["menu_type"] == "drawer_menu" and payload["text"] == "Hi" and payload["priority"] == 3
    assert "item_id" not in payload  # None values are dropped
    payload["on_click"]({"account": 1})
    assert seen == {"account": 1}


def test_default_hooks_return_hook_result():
    p = _plugin()
    assert isinstance(p.pre_request_hook("TL_x", 0, object()), HookResult)
    assert isinstance(p.on_send_message_hook(0, object()), HookResult)
    assert p.create_settings() == []


def test_settings_api_on_plugin(settings_dir, java):
    p = _plugin("sp")
    assert p.get_setting("a", 7) == 7
    p.set_setting("a", 8)
    assert p.get_setting("a") == 8
    p.import_settings({"x": "y"}, reload_settings=False)
    assert p.export_settings() == {"x": "y"}
    java.jclass("com.exteragram.messenger.plugins.PythonPluginsEngine").invalidateSettingsCache.assert_called_with("sp")


def test_split_filters():
    class H(MethodHook):
        __hook_filters__ = [HookFilter.ArgumentNotNull(0), HookFilter.RESULT_NOT_NULL]

        @hook_filters(HookFilter.ArgumentEqual(1, 5))
        def before_hooked_method(self, param):
            pass

        @hook_filters(HookFilter.ResultEqual(True))
        def after_hooked_method(self, param):
            pass

    before, after = split_filters(H())
    assert [f.kind for f in before] == ["argument_not_null", "argument_equal"]
    assert [f.kind for f in after] == ["argument_not_null", "result_not_null", "result_equal"]


def test_hook_method_builds_java_callbacks(controller, java):
    class Before(MethodHook):
        def before_hooked_method(self, param):
            pass

    class Repl(MethodReplacement):
        def replace_hooked_method(self, param):
            return 1

    p = _plugin("hk")
    PyMethodHook = java.jclass("com.exteragram.messenger.plugins.xposed.PyMethodHook")
    PyMethodReplacement = java.jclass("com.exteragram.messenger.plugins.xposed.PyMethodReplacement")
    XposedBridge = java.jclass("de.robv.android.xposed.XposedBridge")
    XposedBridge.hookMethod.return_value = "unhook-1"

    h = Before()
    assert p.hook_method("m", h, priority=10) == "unhook-1"
    assert PyMethodHook.call_args.args == ("hk", h, 10, True, False)
    controller.addXposedHook.assert_called_with("hk", "unhook-1")

    r = Repl()
    p.hook_method("m2", r)
    assert PyMethodReplacement.call_args.args == ("hk", r, 50)

    p.hook_method("m3", after=lambda param: None)
    assert PyMethodHook.call_args.args[3:] == (False, True)

    with pytest.raises(TypeError):
        p.hook_method("m4", object())
    assert p.unhook_method("unhook-1") is True
    controller.removeXposedHook.assert_called_with("hk", "unhook-1")


# ---------------------------------------------------------------- text formatting

from markdown_utils import parse_html, parse_markdown, utf16_len


def _ents(parsed):
    return [(e.type, e.offset, e.length) + ((e.url,) if e.url else ()) for e in parsed.entities]


def test_html_entities_utf16():
    p = parse_html('<b>hi 😀</b> <i>x</i> <a href="https://t.me">link</a> <tg-spoiler>s</tg-spoiler>'
                   '<pre><code class="language-py">a=1</code></pre>')
    assert p.text == "hi 😀 x link sa=1"
    assert _ents(p) == [("bold", 0, 5), ("italic", 6, 1), ("text_link", 8, 4, "https://t.me"), ("spoiler", 13, 1),
                        ("pre", 14, 3)]
    assert p.entities[-1].language == "py"
    assert utf16_len("😀") == 2


def test_html_unescape_and_br():
    p = parse_html("a &amp; b<br>c")
    assert p.text == "a & b\nc" and p.entities == []


def test_markdown_entities():
    p = parse_markdown(r"**bold** _it_ __u__ ~~s~~ ||sp|| `c` [t](https://e.x) \*lit\*")
    assert p.text == "bold it u s sp c t *lit*"
    assert _ents(p) == [("bold", 0, 4), ("italic", 5, 2), ("underline", 8, 1), ("strikethrough", 10, 1),
                        ("spoiler", 12, 2), ("code", 15, 1), ("text_link", 17, 1, "https://e.x")]


def test_markdown_pre_and_nesting():
    p = parse_markdown("```python\nprint(1)\n```")
    assert p.text == "print(1)" and p.entities[0].type == "pre" and p.entities[0].language == "python"
    p = parse_markdown("*a _b_ c*")
    assert p.text == "a b c" and _ents(p) == [("bold", 0, 5), ("italic", 2, 1)]
    assert parse_markdown("2*3 = 6").text == "2*3 = 6"


def test_parse_text_mode_errors():
    from extera_utils.text_formatting import parse_text_raw
    with pytest.raises(ValueError):
        parse_text_raw("x", "bbcode")
    assert parse_text_raw("<b>x</b>", "html").entities[0].type == "bold"


# ---------------------------------------------------------------- sample plugins

def _load_sample(name):
    path = os.path.join(SAMPLES, name + ".py")
    md = get_metadata(path)
    spec = importlib.util.spec_from_file_location(md["id"], path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    cls = BasePlugin._findPluginClass(mod)
    inst = cls()
    inst.id = md["id"]
    return md, inst


@pytest.mark.parametrize("name", ["sg_hello", "sg_send_suffix", "sg_method_hook"])
def test_samples_load(name, settings_dir, controller):
    md, inst = _load_sample(name)
    assert md["id"] == name and md["sdk_version"] == ">=1.4.4.3"
    inst.on_plugin_load()
    for row in inst.create_settings():
        assert row.type


def test_sample_send_suffix_hook(settings_dir, controller):
    _, inst = _load_sample("sg_send_suffix")
    inst.on_plugin_load()
    controller.addEventHook.assert_called_with("sg_send_suffix", "send_message_hook", False, 0)
    params = types.SimpleNamespace(message="hello")
    r = inst.on_send_message_hook(0, params)
    assert r.strategy is HookStrategy.MODIFY and r.params.message == "hello ✨"
    assert inst.on_send_message_hook(0, types.SimpleNamespace(message="/start")).strategy is HookStrategy.DEFAULT
    assert inst.on_send_message_hook(0, types.SimpleNamespace(message=None)).strategy is HookStrategy.DEFAULT
    inst.set_setting("enabled", False)
    assert inst.on_send_message_hook(0, types.SimpleNamespace(message="x")).strategy is HookStrategy.DEFAULT


def test_sample_hello_menu(settings_dir, controller):
    _, inst = _load_sample("sg_hello")
    inst.on_plugin_load()
    payload = controller.addMenuItem.call_args.args[1]
    assert payload["menu_type"] == "drawer_menu" and payload["item_id"] == "sg_hello_drawer"
    inst.set_setting("style", 1)
    inst.set_setting("greeting", "Hey")
    assert inst.greeting() == "Hey!!!"


def test_simple_setting_factory_instance_alias(monkeypatch):
    import ui.settings as st
    from ui.settings import SimpleSettingFactory, Custom
    class _Peer:
        pass
    f = object.__new__(SimpleSettingFactory)
    f._peer = _Peer()
    f._peer.java = object()
    f.java_factory = f._peer.java
    f.instance = f._peer
    assert f.instance is f._peer
    assert f.instance.java is f.java_factory
    assert Custom(factory=f).factory is f.java_factory


# ---- _set_java_field / send_message notify clash ----

class _FakeField:
    def __init__(self, tname):
        self.tname = tname
        self.calls = []

    def getType(self):
        return types.SimpleNamespace(getName=lambda: self.tname)

    def setBoolean(self, o, v):
        self.calls.append(("setBoolean", v))

    def setInt(self, o, v):
        self.calls.append(("setInt", v))

    def setLong(self, o, v):
        self.calls.append(("setLong", v))

    def set(self, o, v):
        self.calls.append(("set", v))

    def get(self, o):
        return "got"


class _FakeSmp:
    def __init__(self):
        self.fields = {"notify": _FakeField("boolean"), "count": _FakeField("int"),
                       "id": _FakeField("long"), "txt": _FakeField("java.lang.String")}

    def __setattr__(self, name, value):
        if name in ("notify", "count", "id", "txt"):
            raise AttributeError("Java member java.lang.Object.notify is not a field")
        object.__setattr__(self, name, value)

    def getClass(self):
        return types.SimpleNamespace(getField=lambda n: self.fields[n])


def test_set_java_field_reflection():
    import client_utils as cu
    o = _FakeSmp()
    cu._set_java_field(o, "notify", 1)
    cu._set_java_field(o, "count", "5")
    cu._set_java_field(o, "id", 7)
    cu._set_java_field(o, "txt", "m")
    assert o.fields["notify"].calls == [("setBoolean", True)]
    assert o.fields["count"].calls == [("setInt", 5)]
    assert o.fields["id"].calls == [("setLong", 7)]
    assert o.fields["txt"].calls == [("set", "m")]
    assert cu._get_java_field(o, "notify") == "got"


def test_set_java_field_plain_and_other_errors():
    import client_utils as cu
    o = types.SimpleNamespace()
    cu._set_java_field(o, "x", 3)
    assert o.x == 3

    class Bad:
        def __setattr__(self, n, v):
            raise ValueError("boom")
    with pytest.raises(ValueError):
        cu._set_java_field(Bad(), "x", 1)


def test_send_message_sets_notify_via_reflection(java):
    import client_utils as cu
    smp = _FakeSmp()
    java.jclass("org.telegram.messenger.SendMessagesHelper$SendMessageParams").of.return_value = smp
    cu.send_message({"peer": 1, "message": "hi"}, account=0)
    assert smp.fields["notify"].calls == [("setBoolean", True)]
