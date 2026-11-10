"""Settings pages built the way exteraGram plugins commonly do it (lazy SDK imports inside create_settings,
SimpleSettingFactory preview rows, UItem slider rows, icon switches, selectors)."""

import importlib.util
import textwrap
from unittest import mock

import pytest

from base_plugin import BasePlugin
from extera_utils.metadata_parser import get_metadata

CLASS_PROXY = "com.exteragram.messenger.plugins.utils.ClassProxy"

PLUGIN_SRC = textwrap.dedent('''
    __id__ = "sg_text_anim_like"
    __name__ = "Text anim like"
    __description__ = "settings page in the exteraGram style"
    __author__ = "sg"
    __version__ = "1.0"
    __min_version__ = "11.12.0"

    from base_plugin import BasePlugin


    class TextAnimLike(BasePlugin):
        def on_plugin_load(self):
            self.bound = []

        def _create_preview(self, context, list_view, current_account, class_guid, resources_provider):
            return "preview-view"

        def _bind_preview(self, view, item, divider, adapter, list_view):
            self.bound.append(view)

        def create_settings(self):
            # imports inside create_settings: an ImportError here only hides the settings page
            from ui.settings import Custom, Divider, Header, Selector, SimpleSettingFactory, Switch
            from java import jclass

            UItem = jclass("org.telegram.ui.Components.UItem")
            preview = SimpleSettingFactory(create_view=self._create_preview, bind_view=self._bind_preview,
                                           is_clickable=False, custom_name="preview")
            return [
                Header(text="Preview"),
                Custom(factory=preview),
                Header(text="Text behaviour"),
                Switch(key="smooth", text="Smooth appear", default=True, icon="msg_photo_text_framed"),
                Custom(item=UItem.asIntSlideView(1, 50, 300, 1000, None), link_alias="duration"),
                Selector(key="mode", text="Appear style", default=0, items=["Soft", "Sharp"],
                         icon="msg_media"),
                preview(factory_args={"compact": True}),
                Divider(text="footer"),
            ]
''')


@pytest.fixture
def proxy_java(java):
    """Makes ClassProxy.createProxyClass return a class whose no-arg constructor yields a fresh Java object."""
    made = []

    def new_java_obj(_args):
        obj = mock.MagicMock(name="factory_obj_%d" % len(made))
        made.append(obj)
        return obj

    ctor = mock.MagicMock()
    ctor.getParameterTypes.return_value = []
    ctor.newInstance.side_effect = new_java_obj
    java.jclass(CLASS_PROXY).createProxyClass.return_value.getDeclaredConstructors.return_value = [ctor]
    # identityHashCode drives peer lookup: give every object its own id
    java.jclass("java.lang.System").identityHashCode.side_effect = id
    yield made
    java.jclass("java.lang.System").identityHashCode.side_effect = None


def _load_plugin(tmp_path):
    path = tmp_path / "sg_text_anim_like.py"
    path.write_text(PLUGIN_SRC)
    md = get_metadata(str(path))
    spec = importlib.util.spec_from_file_location(md["id"], str(path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    inst = BasePlugin._findPluginClass(mod)()
    inst.id = md["id"]
    return inst


def test_simple_setting_factory_is_exported():
    import ui.settings as s
    assert "SimpleSettingFactory" in s.__all__


def test_exteragram_style_settings_page(tmp_path, settings_dir, controller, java, proxy_java):
    from ui.settings import Custom, SimpleSettingFactory

    inst = _load_plugin(tmp_path)
    inst.on_plugin_load()
    rows = inst.create_settings()
    assert [r.type for r in rows] == ["header", "custom", "header", "switch", "custom", "selector", "custom",
                                      "divider"]
    assert rows[3].icon == "msg_photo_text_framed" and rows[3].default is True
    assert rows[5].items == ["Soft", "Sharp"]

    # Custom(factory=SimpleSettingFactory) reaches Java as the real CustomSetting.Factory object
    java_factory = proxy_java[0]
    assert rows[1].factory is java_factory
    assert isinstance(rows[6], Custom) and rows[6].factory is java_factory
    assert rows[6].factory_args == {"compact": True}
    java_factory.setClickableValue.assert_called_with(False)
    java_factory.setShadowValue.assert_called_with(False)
    java.jclass("org.telegram.ui.Components.UItem$UItemFactory").setup.assert_any_call(java_factory)

    # a dedicated Java subclass of CustomSetting$Factory is generated per factory
    args = java.jclass(CLASS_PROXY).createProxyClass.call_args.args
    assert args[-1].startswith("SgSimpleSettingFactory_preview_")


def test_factory_dispatch_calls_python_handlers(settings_dir, java, proxy_java):
    from ui.settings import SimpleSettingFactory

    calls = []
    f = SimpleSettingFactory(
        create_view=lambda ctx, lv, acc, guid, rp: ("view", ctx, acc),
        bind_view=lambda view, item, divider: calls.append(("bind", view, item, divider)),  # fewer args is fine
        on_click=lambda plugin, item, view: calls.append(("click", view)),
        equals=lambda a, b: a == b,
    )
    rt = f._factory_class.__java_runtime__
    this = f.java_factory
    assert rt.dispatch(this, "createView:android.content.Context", ["ctx", None, 2, 7, None]) == ("view", "ctx", 2)
    rt.dispatch(this, "bindView:android.view.View", ["v", "item", True, None, None])
    rt.dispatch(this, "onClick:x", ["plugin", "item", "v"])
    assert calls == [("bind", "v", "item", True), ("click", "v")]
    # default create(): a UItem bound to this factory class, carrying factory_args
    UItem = java.jclass("org.telegram.ui.Components.UItem")
    item = rt.dispatch(this, "create:x", ["plugin", "setting", {"a": 1}])
    UItem.ofFactory.assert_called_with(this.getClass())
    assert item is UItem.ofFactory.return_value and item.object == {"a": 1}
    sig = [s for s in rt.overrides_by_sig if s.startswith("equals:")]
    assert len(sig) == 1 and rt.dispatch(this, sig[0], [1, 1]) is True

    f.release()
    assert f.java_factory is None
    f.release()  # idempotent


def test_factory_needs_a_view(settings_dir, java, proxy_java):
    from ui.settings import SimpleSettingFactory
    with pytest.raises(ValueError):
        SimpleSettingFactory()


def test_set_all_settings_alias(settings_dir):
    import plugin_settings as ps
    ps.set_all_settings("p2", {"a": 1, "b": None})
    assert ps.get_all_settings("p2") == {"a": 1}


def test_java_subclass_on_plain_class_registers_overrides(java):
    """@java_subclass on a class that does not inherit Base used to register no overrides."""
    from extera_utils.classes import java_subclass, joverride

    @java_subclass("android.view.View")
    class Plain:
        @joverride("onDraw")
        def on_draw(self, canvas):
            return "drawn"

    rt = Plain.__java_runtime__
    assert "onDraw" in rt.overrides_by_name
    assert rt.dispatch(mock.MagicMock(), "onDraw:android.graphics.Canvas", ["c"]) == "drawn"
