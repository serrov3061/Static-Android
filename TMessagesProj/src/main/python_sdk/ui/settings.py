"""Plugin settings rows returned from ``BasePlugin.create_settings()``.

Every row is a dataclass with a ``type`` attribute; the Java engine reads the
attributes by name (see PythonPluginsEngine.parsePySettingDefinitions).
"""

import itertools
import threading
from dataclasses import dataclass, field
from typing import Any, Callable, List, Optional

__all__ = ["Header", "Divider", "Switch", "Selector", "Input", "Text", "EditText", "Custom",
           "SimpleSettingFactory"]


@dataclass
class Header:
    text: str
    type: str = field(default="header", init=False)


@dataclass
class Divider:
    text: Optional[str] = None
    type: str = field(default="divider", init=False)


@dataclass
class Switch:
    key: str
    text: str
    default: bool = False
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable[[bool], None]] = None
    on_long_click: Optional[Callable[[Any], None]] = None
    link_alias: Optional[str] = None
    type: str = field(default="switch", init=False)

    def __post_init__(self):
        self.default = bool(self.default)


@dataclass
class Selector:
    key: str
    text: str
    default: int = 0
    items: List[str] = field(default_factory=list)
    icon: Optional[str] = None
    on_change: Optional[Callable[[int], None]] = None
    on_long_click: Optional[Callable[[Any], None]] = None
    link_alias: Optional[str] = None
    type: str = field(default="selector", init=False)

    def __post_init__(self):
        self.items = [str(i) for i in (self.items or [])]
        self.default = int(self.default or 0)


@dataclass
class Input:
    key: str
    text: str
    default: Optional[str] = ""
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable[[str], None]] = None
    on_long_click: Optional[Callable[[Any], None]] = None
    link_alias: Optional[str] = None
    type: str = field(default="input", init=False)


@dataclass
class Text:
    text: str
    subtext: Optional[str] = None
    icon: Optional[str] = None
    accent: bool = False
    red: bool = False
    on_click: Optional[Callable[[Any], None]] = None
    on_long_click: Optional[Callable[[Any], None]] = None
    create_sub_fragment: Optional[Callable[[], List[Any]]] = None
    link_alias: Optional[str] = None
    type: str = field(default="text", init=False)


@dataclass
class EditText:
    key: str
    hint: str
    default: Optional[str] = ""
    multiline: bool = False
    max_length: int = 256
    mask: Optional[str] = None
    on_change: Optional[Callable[[str], None]] = None
    type: str = field(default="edit_text", init=False)


@dataclass
class Custom:
    item: Any = None
    view: Any = None
    factory: Any = None
    factory_args: Any = None
    on_click: Optional[Callable[[Any], None]] = None
    on_long_click: Optional[Callable[[Any], None]] = None
    create_sub_fragment: Optional[Callable[[], List[Any]]] = None
    link_alias: Optional[str] = None
    type: str = field(default="custom", init=False)

    def __post_init__(self):
        # exteraGram accepts the high-level SimpleSettingFactory wrapper as ``factory``
        if isinstance(self.factory, SimpleSettingFactory):
            self.factory = self.factory.java_factory


# ------------------------------------------------------------------ SimpleSettingFactory

_FACTORY_CLASS = "com.exteragram.messenger.plugins.models.CustomSetting$Factory"
_UITEM_CLASS = "org.telegram.ui.Components.UItem"
_factory_ids = itertools.count(1)
_registry_lock = threading.Lock()


def _call_flexible(fn, *args):
    """Calls ``fn`` with as many leading ``args`` as it accepts (plugins often omit trailing ones)."""
    import inspect
    try:
        params = inspect.signature(fn).parameters.values()
    except (TypeError, ValueError):
        return fn(*args)
    if any(p.kind == p.VAR_POSITIONAL for p in params):
        return fn(*args)
    n = sum(1 for p in params if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD))
    return fn(*args[:n])


def _gen_simple_custom_setting_factory_class(handlers, custom_name):
    """Generates a dedicated Java subclass of CustomSetting.Factory for one SimpleSettingFactory.

    UItem.UItemFactory.setup() registers factories per Java class, so every factory needs its own class.
    """
    from extera_utils.classes import Base, java_subclass, joverload, joverride
    from _sg_java import J

    UItem = J(_UITEM_CLASS)
    attrs = {}

    def _create(self, plugin, setting, args):
        fn = handlers.get("create_item")
        if fn is not None:
            return _call_flexible(fn, plugin, setting, args)
        item = UItem.ofFactory(self.java.getClass())
        try:
            item.object = args
        except Exception:
            pass
        return item
    attrs["create"] = joverride("create")(_create)

    if handlers.get("create_view") is not None:
        def _create_view(self, context, list_view, current_account, class_guid, resources_provider):
            return _call_flexible(handlers["create_view"], context, list_view, current_account, class_guid,
                                  resources_provider)
        attrs["createView"] = joverride("createView")(_create_view)
    if handlers.get("bind_view") is not None:
        def _bind_view(self, view, item, divider, adapter, list_view):
            _call_flexible(handlers["bind_view"], view, item, divider, adapter, list_view)
        attrs["bindView"] = joverride("bindView")(_bind_view)
    if handlers.get("attached_view") is not None:
        def _attached_view(self, list_view, view, item):
            _call_flexible(handlers["attached_view"], list_view, view, item)
        attrs["attachedView"] = joverride("attachedView")(_attached_view)
    if handlers.get("on_click") is not None:
        def _on_click(self, plugin, item, view):
            _call_flexible(handlers["on_click"], plugin, item, view)
        attrs["onClick"] = joverride("onClick")(_on_click)
    if handlers.get("on_long_click") is not None:
        def _on_long_click(self, plugin, item, view):
            _call_flexible(handlers["on_long_click"], plugin, item, view)
        attrs["onLongClick"] = joverride("onLongClick")(_on_long_click)
    if handlers.get("equals") is not None:
        def _equals(self, a, b):
            return bool(handlers["equals"](a, b))
        attrs["equals"] = joverload("equals", [_UITEM_CLASS, _UITEM_CLASS])(_equals)
    if handlers.get("content_equals") is not None:
        def _content_equals(self, a, b):
            return bool(handlers["content_equals"](a, b))
        attrs["contentsEquals"] = joverride("contentsEquals")(_content_equals)

    name = "SgSimpleSettingFactory_%s_%d" % ("".join(c for c in (custom_name or "") if c.isalnum()) or "f",
                                             next(_factory_ids))
    py_cls = type(name, (Base,), attrs)
    return java_subclass(_FACTORY_CLASS, name=name)(py_cls)


def _register_factory(java_factory):
    from _sg_java import J
    J(_UITEM_CLASS + "$UItemFactory").setup(java_factory)


def _unregister_factory(java_factory):
    """UItemFactory has no public unregister: drop the entries from its static maps."""
    from _sg_java import J
    with _registry_lock:
        for field_name, key in (("factories", None), ("factoryInstances", "class")):
            try:
                f = J("java.lang.Class").forName(_UITEM_CLASS).getDeclaredField(field_name)
                f.setAccessible(True)
                registry = f.get(None)
                if registry is None:
                    continue
                if key is None:
                    registry.remove(java_factory.viewType)
                else:
                    registry.remove(java_factory.getClass())
            except Exception:
                pass


class SimpleSettingFactory:
    """High-level custom setting row factory (exteraGram SDK API).

    ``create_view(context, list_view, current_account, class_guid, resources_provider)`` returns the row view,
    ``bind_view(view, item, divider, adapter, list_view)`` binds it. Use it as ``Custom(factory=f)`` or ``f(...)``.
    """

    def __init__(self, create_view: Optional[Callable] = None, bind_view: Optional[Callable] = None,
                 on_click: Optional[Callable] = None, on_long_click: Optional[Callable] = None,
                 equals: Optional[Callable] = None, content_equals: Optional[Callable] = None,
                 attached_view: Optional[Callable] = None, create_item: Optional[Callable] = None,
                 is_clickable: bool = True, is_shadow: bool = False, custom_name: Optional[str] = None):
        if create_view is None and create_item is None:
            raise ValueError("SimpleSettingFactory needs create_view (or create_item)")
        self._handlers = dict(create_view=create_view, bind_view=bind_view, on_click=on_click,
                              on_long_click=on_long_click, equals=equals, content_equals=content_equals,
                              attached_view=attached_view, create_item=create_item)
        self._factory_class = _gen_simple_custom_setting_factory_class(self._handlers, custom_name)
        self._peer = self._factory_class.new_instance()
        self.java_factory = self._peer.java
        # original SDK: instance is the proxy peer; plugins reach the Java factory via instance.java
        self.instance = self._peer
        self.java_factory.setClickableValue(bool(is_clickable))
        self.java_factory.setShadowValue(bool(is_shadow))
        _register_factory(self.java_factory)

    def __call__(self, factory_args: Any = None, on_click: Optional[Callable] = None,
                 on_long_click: Optional[Callable] = None, create_sub_fragment: Optional[Callable] = None,
                 link_alias: Optional[str] = None) -> "Custom":
        return Custom(factory=self.java_factory, factory_args=factory_args, on_click=on_click,
                      on_long_click=on_long_click, create_sub_fragment=create_sub_fragment, link_alias=link_alias)

    def release(self):
        if self.java_factory is None:
            return
        _unregister_factory(self.java_factory)
        try:
            self._factory_class.__java_runtime__.release(self._peer)
        except Exception:
            pass
        self.java_factory = None
