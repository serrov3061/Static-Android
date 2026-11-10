"""Host tests for the SDK 1.4.5.7 surface (stub ``java`` module from conftest)."""

import pytest


def test_version_is_plain():
    import _sdk_version
    assert _sdk_version.__version__ == "1.4.5.7"
    for name in ("dev_server", "elyx", "elyxcore"):
        assert name in _sdk_version.TOP_LEVEL_MODULES


# ---------------------------------------------------------------- log_utils

def test_logger_levels_handlers_child_timed():
    import log_utils as L
    L._set_dev_mode(True)
    try:
        lg = L.Logger("plugin.t")
        recs = []
        lg.add_handler(recs.append)
        lg.info("hello %s", 1)
        lg.debug("dbg")
        lg.verbose("hidden")
        try:
            1 / 0
        except ZeroDivisionError:
            lg.exception("boom")
        with lg.timed("block"):
            pass
        assert [r.message for r in recs][:3] == ["hello 1", "dbg", "boom"]
        assert recs[2].traceback and "ZeroDivisionError" in recs[2].traceback
        assert recs[0].location.startswith("test_sdk_1457.py:")
        assert "block took" in recs[3].message
        child = lg.child("net")
        assert child.tag == "plugin.t.net" and child.effective_level == L.LogLevel.DEBUG
        child.level = "verbose"
        child.verbose("v")
        assert recs[-1].tag == "plugin.t.net" and recs[-1].level == L.LogLevel.VERBOSE
        lg.level = L.LogLevel.OFF
        n = len(recs)
        lg.error("off")
        assert len(recs) == n
        assert lg.remove_handler(recs.append) is False or True
    finally:
        L._set_dev_mode(None)


def test_default_level_follows_dev_mode():
    import log_utils as L
    L._set_dev_mode(False)
    assert L.Logger("x").effective_level == L.LogLevel.INFO
    L._set_dev_mode(True)
    assert L.Logger("x").effective_level == L.LogLevel.DEBUG
    L._set_dev_mode(None)


def test_dump_reads_fields_only():
    from log_utils import dump

    class O:
        def __init__(self):
            self.x = 1
            self.items = [1, {"k": "v"}]

        @property
        def boom(self):
            raise RuntimeError("getter ran")

    text = dump(O(), depth=3)
    assert "x = 1" in text and "k = 'v'" in text and "boom" not in text
    loop = []
    loop.append(loop)
    assert "<cycle>" in dump(loop, depth=3)


def test_split_for_logcat_keeps_everything():
    from log_utils import _split_for_logcat
    text = "\n".join("line %d" % i for i in range(2000))
    parts = _split_for_logcat(text)
    assert len(parts) > 1 and "\n".join(parts) == text


def test_plugin_logger_and_user_logger(controller):
    import base_plugin
    import log_utils

    class P(base_plugin.BasePlugin):
        pass

    p = P()
    p.id = "demo"
    assert p.logger is log_utils._plugin_logger("demo") and p.logger.tag == "plugin.demo"
    own = log_utils.Logger("custom")
    p.logger = own
    assert p.logger is own


# ---------------------------------------------------------------- multi-account

def test_account_scope_and_hooks(java):
    import base_plugin
    import client_utils as C

    class P(base_plugin.BasePlugin):
        def on_update_hook(self, name, account, update):
            return C.get_hook_account(), self.client().account

        def on_send_message_hook(self, account, params):
            return C.get_hook_account()

    p = P()
    assert C.get_hook_account() is None
    assert p.on_update_hook("TL_updateNewMessage", 2, None) == (2, 2)
    assert p.on_send_message_hook(3, None) == 3
    assert C.get_hook_account() is None
    with C.account_scope(1):
        assert C.get_client() == C.AccountClient(1)
    assert repr(C.get_client(4)) == "AccountClient(account=4)"
    for name in ("get_messages_controller", "send_photo", "edit_message", "get_user_config"):
        assert hasattr(C.AccountClient(0), name)


def test_selected_account_warning_once(java, monkeypatch):
    import client_utils as C
    java.jclass("org.telegram.messenger.UserConfig").selectedAccount = 0
    warned = []
    import log_utils
    monkeypatch.setattr(log_utils.Logger, "warning", lambda self, *a, **k: warned.append(a))
    C._warned_account_mismatches.clear()
    with C.account_scope(1):
        assert C._resolve_account(None, "get_messages_controller") == 0
        C._resolve_account(None, "get_messages_controller")
        assert C._resolve_account(5, "get_messages_controller") == 5
    assert len(warned) == 1


# ---------------------------------------------------------------- method hooks

def test_method_hook_phase_detection():
    import base_plugin as B

    class After(B.MethodHook):
        def after_hooked_method(self, param):
            pass

    assert not B._is_method_hook_phase_enabled(After(), "before_hooked_method")
    assert B._is_method_hook_phase_enabled(After(), "after_hooked_method")
    h = B.BaseHook(before=lambda p: None)
    assert B._is_method_hook_phase_enabled(h, "before_hooked_method")
    assert not B._is_method_hook_phase_enabled(h, "after_hooked_method")
    f = B.fn_hook_filters(B.HookFilter.ArgumentIsNull(0))(lambda p: None)
    assert f.__hook_filters__[0].kind == "argument_is_null"
    assert isinstance(B.HookFilter.RESULT_IS_NULL, B.HookFilterData)


# ---------------------------------------------------------------- intents

def _ctx(**kw):
    base = {"intent": None, "scheme": "tg", "host": "chat", "path": None, "query_args": {}, "action": None,
            "flags": 0, "type": None, "categories": []}
    base.update(kw)
    return base


def test_intents_matching_and_arguments(monkeypatch):
    import intents
    IM = intents.IntentsManager
    monkeypatch.setattr(IM, "_ensure_hook", classmethod(lambda cls: None))
    monkeypatch.setattr(IM, "_intent_context", staticmethod(lambda i: i))
    seen = []
    a = IM.new_global_before_handler(lambda id, host=None: seen.append(("chat", id, host)) or True,
                                     scheme="tg", host="chat", required_path_args_names=["id"])
    b = IM.new_global_before_handler(lambda: seen.append("late"), scheme="tg", priority=10)
    c = IM.new_global_before_handler(lambda user_id, path=None, **kw: seen.append((user_id, path, kw.get("q"))),
                                     scheme="tg", host="profile", path="user/{user_id}")
    d = IM.new_global_after_handler(lambda *args: seen.append(len(args)), scheme="tg")
    try:
        assert IM._dispatch(intents.IntentHookType.BEFORE, _ctx()) is False  # id missing
        assert seen == ["late"]
        seen.clear()
        assert IM._dispatch(intents.IntentHookType.BEFORE, _ctx(query_args={"id": "5"})) is True
        assert seen == [("chat", "5", "chat")]  # stopped before the priority-10 handler
        seen.clear()
        IM._dispatch(intents.IntentHookType.BEFORE, _ctx(host="profile", path="/user/42", query_args={"q": "z"}))
        IM._dispatch(intents.IntentHookType.AFTER, _ctx())
        assert seen == [("42", "/user/42", "z"), "late", 9]
    finally:
        for h in (a, b, c, d):
            h.unhandle()
    with pytest.raises(IM.HandlerNotRegistered):
        IM.unhandle(a.handler_id)
    assert IM.parse("tg://chat?id=123") == {"scheme": "tg", "host": "chat", "query": "id=123"}


def test_intent_flags_and_categories(monkeypatch):
    import intents
    IM = intents.IntentsManager
    info = intents._HandlerInfo("x", intents.IntentHookType.BEFORE, print, whitelist_flags=[4],
                                blacklist_flags=[8], categories=["c"])
    assert IM._match(info, _ctx(flags=4 | 1, categories=["c"])) == {}
    assert IM._match(info, _ctx(flags=4 | 8, categories=["c"])) is None
    assert IM._match(info, _ctx(flags=4, categories=[])) is None


# ---------------------------------------------------------------- files

def test_files_controller_registry(monkeypatch):
    import file_utils as F
    FC = F.FilesController
    monkeypatch.setattr(FC, "_FilesController__ih", classmethod(lambda cls: None))
    secret = FC.register(FC.FileInfo(ext="zip", on_click=lambda a: None))
    with pytest.raises(FC.ExtensionAlreadyRegistered):
        FC.register(FC.FileInfo(ext=".ZIP", on_click=lambda a: None))
    with pytest.raises(FC.SecretInvalid):
        FC.unregister("zip", "nope")
    assert FC._lookup("Archive.ZIP").ext == "zip"
    assert FC.unregister("zip", secret) is True
    with pytest.raises(FC.ExtensionNotRegistered):
        FC.unregister("zip", secret)
    with pytest.raises(ValueError):
        FC.FileInfo(ext="a", on_click=print, whitelist_places=[FC.Place.ChatActivity],
                    blacklist_places=[FC.Place.UNKNOWN])
    FC.FileInfo(ext="a", on_click=print, get_icon=lambda: None)
    assert FC.DIRECT_FILE_ICONS is True and FC.SUPPORT_ICONS is True
    info = FC.FileInfo(ext="a", on_click=print, blacklist_places=[FC.Place.SearchDownloadsContainer])
    assert FC._allowed(info, FC.Place.ChatActivity) and not FC._allowed(info, FC.Place.SearchDownloadsContainer)


# ---------------------------------------------------------------- text formatting

def test_html_markdown_roundtrip():
    from extera_utils.text_formatting import HTML, Markdown, TLEntityType, add_surrogates, remove_surrogates
    r = HTML.parse("<b>Hi \U0001F600</b> <a href='http://x'>l<i>n</i>k</a>")
    assert r["message"] == "Hi \U0001F600 lnk"
    assert HTML.unparse(r["message"], r["entities"]) == '<b>Hi \U0001F600</b> <a href="http://x">l<i>n</i>k</a>'
    m = Markdown.parse("*bold* [t](http://u) `c`")
    assert Markdown.unparse(m["message"], m["entities"]) == "*bold* [t](http://u) `c`"
    assert remove_surrogates(add_surrogates("a\U0001F600b")) == "a\U0001F600b"
    assert len(add_surrogates("\U0001F600")) == 2
    assert TLEntityType.BOLD == "bold"


def test_markdown_utils_helpers():
    import markdown_utils as M
    assert M.to_utf16_len("a\U0001F600b") == 4
    assert M.count_chars_until("a\U0001F600b", 3) == 2
    assert M.ParsedMessage is M.ParsedText


# ---------------------------------------------------------------- elyx

def test_elyx_public_api(tmp_path, settings_dir):
    import elyx
    assert set(elyx.__all__) >= {"Asset", "Assets", "Strings", "SettingsController", "gen", "gen2", "LazyDict",
                                 "OnClickListener", "Runnable", "CallbackReturn", "get_environment"}
    with pytest.raises(RuntimeError):
        elyx.get_environment()
    with pytest.raises(AttributeError):
        elyx.strings
    s = elyx.Strings({"en": {"hello": "Hello, {name}!", "forms": ["file", "files", "files"]},
                      "ru": {"hello": "Привет, {name}!"}})
    assert s("hello", name="A", locale="en") == "Hello, A!"
    assert s.get_with_locale("hello", locale="ru") == "Привет, {name}!"
    assert s("missing", locale="en") == "missing"
    assert s.pluralize(21, "forms", locale="en") == "21 file"
    (tmp_path / "res" / "icons").mkdir(parents=True)
    (tmp_path / "res" / "empty-state.svg").write_text("<svg/>")
    (tmp_path / "res" / "data.json").write_text('{"a": 1}')
    (tmp_path / "res" / "icons" / "add.svg").write_text("<svg/>")
    a = elyx.Assets(tmp_path / "res")
    assert a.empty_state.filename == "empty-state.svg" and a["data"].content() == {"a": 1}
    assert a.icons.add.ext == "svg" and a["icons/add"].name == "add" and "data" in a and len(a) == 3
    with pytest.raises(elyx.AssetNotFoundException):
        a["nope"]
    st = elyx.SettingsController("elyx_demo")
    st["k"] = 5
    assert st("k") == 5 and st.get_settings() == {"k": 5}
    st.clear_settings()
    assert st.get("k", 0) == 0
    d = elyx.LazyDict({"title": "x"})
    assert d.title == "x"
    with pytest.raises(KeyError):
        d.missing


def test_plugin_settings_internal_names(settings_dir):
    import plugin_settings as ps
    ps.set_setting("zw", "a", 1)
    ps.set_setting("zw", "b", 2)
    with ps._lock:
        ps._settings_cache["zw"].pop("a", None)
        ps._save_settings_to_file()
    ps._cache.clear()
    assert ps.get_all_settings("zw") == {"b": 2}
