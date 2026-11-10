"""BasePlugin and the hook/menu data types of the StaticGram open plugin SDK.

Contract with the Java engine (PythonPluginsEngine):
* ``BasePlugin._findPluginClass(module)`` returns the plugin class of a module;
* the engine instantiates it without arguments and sets the attributes id, name,
  description, author, version, icon, app_version, sdk_version, requirements,
  enabled, initialized, error_message;
* it calls on_plugin_load / on_plugin_unload / on_app_event / create_settings and
  the hook methods, reading ``strategy`` and the result field of the HookResult.
"""

import functools
import inspect
from dataclasses import dataclass
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Union

import plugin_settings
from _sg_java import J, file_log, java_map_to_dict, plugins_controller, safe_call, to_array_list, to_java_class
from log_utils import Logger, _plugin_logger, _reset_plugin_logger, log as _log

__all__ = [
    "BasePlugin", "HookResult", "HookStrategy", "AppEvent", "MenuItemData", "MenuItemType",
    "MethodHook", "MethodReplacement", "HookFilter", "hook_filters", "fn_hook_filters", "BaseHook",
    "HookFilterData", "PluginError",
]


class PluginError(Exception):
    """Raised by SDK helpers for a plugin-level failure; ``plugin_id`` names the plugin."""

    def __init__(self, message: str = "", plugin_id: Optional[str] = None):
        super().__init__(message)
        self.message = message
        self.plugin_id = plugin_id

    def __str__(self):
        return "[%s] %s" % (self.plugin_id, self.message) if self.plugin_id else str(self.message)

SEND_MESSAGE_HOOK = "send_message_hook"
DEFAULT_XPOSED_PRIORITY = 50


class HookStrategy(Enum):
    DEFAULT = "DEFAULT"
    CANCEL = "CANCEL"
    MODIFY = "MODIFY"
    MODIFY_FINAL = "MODIFY_FINAL"

    def __str__(self):
        # the engine matches the strategy with endsWith(...)
        return "HookStrategy." + self.value


@dataclass
class HookResult:
    strategy: HookStrategy = HookStrategy.DEFAULT
    request: Any = None
    response: Any = None
    error: Any = None
    update: Any = None
    updates: Any = None
    params: Any = None


class AppEvent(Enum):
    START = "app_start"
    STOP = "app_stop"
    PAUSE = "app_pause"
    RESUME = "app_resume"


class MenuItemType(Enum):
    MESSAGE_CONTEXT_MENU = "message_context_menu"
    DRAWER_MENU = "drawer_menu"
    MAIN_MENU = "main_menu"
    CHAT_ACTION_MENU = "chat_action_menu"
    PROFILE_ACTION_MENU = "profile_action_menu"


@dataclass
class MenuItemData:
    menu_type: MenuItemType
    text: str
    on_click: Callable[[Dict[str, Any]], None]
    item_id: Optional[str] = None
    icon: Optional[str] = None
    subtext: Optional[str] = None
    condition: Optional[str] = None
    priority: int = 0


# ---------------------------------------------------------------- Xposed-style hooks

class MethodHook:
    """Subclass and override ``before_hooked_method(self, param)`` and/or
    ``after_hooked_method(self, param)``. Only the phases you override are hooked."""

    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


class MethodReplacement:
    """Subclass and define ``replace_hooked_method(self, param)``; its return value
    becomes the result of the hooked method."""

    def replace_hooked_method(self, param):
        return None


def _is_method_hook_phase_enabled(handler, name) -> bool:
    """True when ``handler`` provides its own ``name`` phase (instance attribute, an
    override of MethodHook's no-op, or a duck-typed object defining it)."""
    try:
        if name in vars(handler):
            return callable(vars(handler)[name])
    except TypeError:
        pass
    impl = getattr(type(handler), name, None)
    if impl is None:
        return callable(getattr(handler, name, None))
    return impl is not getattr(MethodHook, name, None)


# exteraGram SDK name: any Xposed hook object accepted by hook_method (used in plugin type hints)
XposedHook = Union[MethodHook, MethodReplacement]


class BaseHook(MethodHook):
    """Functional hook used by ``hook_method(before=..., after=...)``."""

    def __init__(self, before=None, after=None):
        if before is not None:
            self.before_hooked_method = before
        if after is not None:
            self.after_hooked_method = after


class HookFilterTypes:
    """Filter kinds understood by the Java HookFilter."""
    RESULT_EQUAL = "result_equal"
    RESULT_NOT_EQUAL = "result_not_equal"
    RESULT_IS_NULL = "result_is_null"
    RESULT_NOT_NULL = "result_not_null"
    RESULT_IS_TRUE = "result_is_true"
    RESULT_IS_FALSE = "result_is_false"
    RESULT_IS_INSTANCE_OF = "result_is_instance_of"
    ARGUMENT_IS_NULL = "argument_is_null"
    ARGUMENT_NOT_NULL = "argument_not_null"
    ARGUMENT_IS_TRUE = "argument_is_true"
    ARGUMENT_IS_FALSE = "argument_is_false"
    ARGUMENT_IS_INSTANCE_OF = "argument_is_instance_of"
    ARGUMENT_EQUAL = "argument_equal"
    ARGUMENT_NOT_EQUAL = "argument_not_equal"
    CONDITION = "condition"
    OR = "or"


class _Filter:
    """One hook filter (public name: HookFilterData)."""
    __slots__ = ("kind", "index", "value", "clazz", "children")

    RESULT_KINDS = ("result_equal", "result_not_equal", "result_is_null", "result_not_null",
                    "result_is_true", "result_is_false", "result_is_instance_of")

    def __init__(self, kind, index=None, value=None, clazz=None, children=None):
        self.kind, self.index, self.value, self.clazz, self.children = kind, index, value, clazz, children

    @property
    def is_result_filter(self):
        if self.kind == "or":
            return all(c.is_result_filter for c in self.children or ())
        return self.kind in self.RESULT_KINDS

    def __repr__(self):
        return "HookFilter(%s, index=%r, value=%r)" % (self.kind, self.index, self.value)

    def to_java(self):
        f = J("com.exteragram.messenger.plugins.hooks.HookFilter")(self.kind)
        if self.index is not None:
            f.setArgIndex(J("java.lang.Integer")(int(self.index)))
        if self.kind == "condition":
            f.setMvelExpression(str(self.value))
            if self.clazz is not None:
                f.setObject(self.clazz)
        elif self.value is not None:
            f.setObject(self.value)
        if self.kind.endswith("instance_of"):
            f.setInstanceOf(to_java_class(self.clazz))
        if self.children:
            f.setOrFilters(to_array_list([c.to_java() for c in self.children]))
        return f

    # exteraGram names
    to_java_filter = to_java

    @property
    def filter_type(self):
        return self.kind

    @property
    def arg_index(self):
        return self.index


HookFilterData = _Filter


class HookFilter:
    @staticmethod
    def filter_data(filter_type, arg_index=None, value=None, instance_of=None, mvel_expression=None,
                    or_filters=None):
        """Builds a raw HookFilterData (the helpers below are the usual way)."""
        if filter_type == "condition":
            return _Filter("condition", index=arg_index, value=mvel_expression, clazz=value)
        return _Filter(str(filter_type), index=arg_index, value=value, clazz=instance_of,
                       children=list(or_filters) if or_filters else None)

    RESULT_IS_NULL = _Filter("result_is_null")
    RESULT_IS_TRUE = _Filter("result_is_true")
    RESULT_IS_FALSE = _Filter("result_is_false")
    RESULT_NOT_NULL = _Filter("result_not_null")

    @staticmethod
    def ResultIsInstanceOf(clazz):
        return _Filter("result_is_instance_of", clazz=clazz)

    @staticmethod
    def ResultEqual(value):
        return _Filter("result_equal", value=value)

    @staticmethod
    def ResultNotEqual(value):
        return _Filter("result_not_equal", value=value)

    @staticmethod
    def ArgumentIsNull(index):
        return _Filter("argument_is_null", index=index)

    @staticmethod
    def ArgumentNotNull(index):
        return _Filter("argument_not_null", index=index)

    @staticmethod
    def ArgumentIsFalse(index):
        return _Filter("argument_is_false", index=index)

    @staticmethod
    def ArgumentIsTrue(index):
        return _Filter("argument_is_true", index=index)

    @staticmethod
    def ArgumentIsInstanceOf(index, clazz):
        return _Filter("argument_is_instance_of", index=index, clazz=clazz)

    @staticmethod
    def ArgumentEqual(index, value):
        return _Filter("argument_equal", index=index, value=value)

    @staticmethod
    def ArgumentNotEqual(index, value):
        return _Filter("argument_not_equal", index=index, value=value)

    @staticmethod
    def Condition(condition, object=None):
        return _Filter("condition", value=condition, clazz=object)

    @staticmethod
    def Or(*filters):
        return _Filter("or", children=list(filters))


def hook_filters(*filters):
    """Decorator for ``before_hooked_method`` / ``after_hooked_method`` (or a hook class)."""
    def deco(target):
        existing = list(getattr(target, "__hook_filters__", ()))
        target.__hook_filters__ = existing + list(filters)
        return target
    return deco


def fn_hook_filters(*filters):
    """Decorator for plain functions passed as ``hook_method(before=..., after=...)``."""
    def deco(fn):
        existing = list(getattr(fn, "__hook_filters__", ()))
        try:
            fn.__hook_filters__ = existing + list(filters)
            return fn
        except AttributeError:
            @functools.wraps(fn)
            def wrapper(*a, **k):
                return fn(*a, **k)
            wrapper.__hook_filters__ = existing + list(filters)
            return wrapper
    return deco


def split_filters(handler, before_filters=None, after_filters=None):
    """Returns (before, after) Python filter lists for a handler."""
    before = list(before_filters or ())
    after = list(after_filters or ())
    common = list(getattr(type(handler), "__hook_filters__", ()) or ())
    for f in common:
        if not f.is_result_filter:
            before.append(f)
        after.append(f)
    b = getattr(handler, "before_hooked_method", None)
    a = getattr(handler, "after_hooked_method", None)
    before += [f for f in getattr(b, "__hook_filters__", ()) or () if not f.is_result_filter]
    after += list(getattr(a, "__hook_filters__", ()) or ())
    return before, after


# ---------------------------------------------------------------- account scoping

# hook method -> index of its ``account`` argument (after self)
_ACCOUNT_HOOKS = {
    "pre_request_hook": 1,
    "post_request_hook": 1,
    "on_update_hook": 1,
    "on_updates_hook": 1,
    "on_send_message_hook": 0,
}


def _account_scoped_hook(fn, account_index):
    """Runs a hook with the account it fired for as the client_utils hook account."""
    if getattr(fn, "__sg_account_scoped__", False):
        return fn

    @functools.wraps(fn)
    def wrapper(self, *args, **kwargs):
        account = args[account_index] if len(args) > account_index else kwargs.get("account")
        if account is None:
            return fn(self, *args, **kwargs)
        from client_utils import account_scope
        try:
            account = int(account)
        except Exception:
            return fn(self, *args, **kwargs)
        with account_scope(account):
            return fn(self, *args, **kwargs)

    wrapper.__sg_account_scoped__ = True
    return wrapper


_INSTANCE_DEFAULTS = (
    ("id", None), ("name", None), ("description", None), ("author", None), ("version", None),
    ("icon", None), ("app_version", None), ("sdk_version", None), ("requirements", None),
    ("enabled", False), ("initialized", False), ("error_message", None),
)


# ---------------------------------------------------------------- BasePlugin

class BasePlugin:
    def __new__(cls, *args, **kwargs):
        # defaults exist even when a plugin overrides __init__ without calling super()
        self = super().__new__(cls)
        d = self.__dict__
        for k, v in _INSTANCE_DEFAULTS:
            d.setdefault(k, v)
        d.setdefault("_user_logger", None)
        d.setdefault("_BasePlugin__fh", {})
        d.setdefault("_BasePlugin__ih", {})
        return self

    def __init_subclass__(cls, **kwargs):
        super().__init_subclass__(**kwargs)
        for name, index in _ACCOUNT_HOOKS.items():
            fn = cls.__dict__.get(name)
            if inspect.isfunction(fn):
                setattr(cls, name, _account_scoped_hook(fn, index))

    def __init__(self):
        for k, v in _INSTANCE_DEFAULTS:
            setattr(self, k, v)

    # ----- discovery (called by the engine)

    @classmethod
    def _findPluginClass(cls, module):
        mod_name = getattr(module, "__name__", None)
        candidates = []
        for value in list(vars(module).values()):
            if inspect.isclass(value) and issubclass(value, BasePlugin) and value is not BasePlugin \
                    and value.__module__ not in ("base_plugin",) and not value.__module__.startswith("elyx"):
                candidates.append(value)
        if not candidates:
            return None
        own = [c for c in candidates if c.__module__ == mod_name]
        pool = own or candidates
        # the most derived class wins (a plugin may subclass its own base class)
        for c in pool:
            if not any(o is not c and issubclass(o, c) for o in pool):
                return c
        return pool[-1]

    # ----- lifecycle (override)

    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def on_app_event(self, event_type: AppEvent):
        pass

    def create_settings(self) -> List[Any]:
        return []

    def _cleanup(self):
        """Called by the engine after on_plugin_unload: drops file/intent handlers, resets
        the plugin logger (handlers and levels) and retires the plugin's class proxies."""
        for ext in list(self.__dict__.get("_BasePlugin__fh", {})):
            try:
                self.remove_file_hook(ext)
            except Exception:
                pass
        for handler_id in list(self.__dict__.get("_BasePlugin__ih", {})):
            try:
                self.remove_intent_hook(handler_id)
            except Exception:
                pass
        if self.id is not None:
            _reset_plugin_logger(self.id)
            # class proxies defined by this plugin: release peers, generated classes fall back to the Java parent
            try:
                from extera_utils.classes import release_plugin
                release_plugin(self.id)
            except Exception:
                pass

    # ----- hooks (override)

    def pre_request_hook(self, request_name: str, account: int, request: Any) -> HookResult:
        return HookResult()

    def post_request_hook(self, request_name: str, account: int, response: Any, error: Any) -> HookResult:
        return HookResult()

    def on_update_hook(self, update_name: str, account: int, update: Any) -> HookResult:
        return HookResult()

    def on_updates_hook(self, container_name: str, account: int, updates: Any) -> HookResult:
        return HookResult()

    def on_send_message_hook(self, account: int, params: Any) -> HookResult:
        return HookResult()

    # ----- logging

    @property
    def logger(self) -> Logger:
        own = self.__dict__.get("_user_logger")
        if own is not None:
            return own
        return _plugin_logger(self.id)

    @logger.setter
    def logger(self, value):
        # a plugin that assigns its own self.logger keeps getting it back
        self.__dict__["_user_logger"] = value

    def log(self, message):
        """Legacy: DEBUG under ``plugin.<id>`` (prefer ``self.logger``)."""
        _log(message, tag="plugin.%s" % self.id)

    # ----- settings

    def get_setting(self, key: str, default=None):
        return plugin_settings.get_setting(self.id, key, default)

    def _settings_changed(self, reload_settings):
        try:
            J("com.exteragram.messenger.plugins.PythonPluginsEngine").invalidateSettingsCache(self.id)
        except Exception:
            pass
        if reload_settings:
            try:
                plugins_controller().loadPluginSettings(self.id)
            except Exception:
                pass

    def set_setting(self, key: str, value, reload_settings: bool = False):
        if plugin_settings.set_setting(self.id, key, value) is False:
            # unchanged: the Java settings cache is still valid, only honour an explicit reload
            if reload_settings:
                try:
                    plugins_controller().loadPluginSettings(self.id)
                except Exception:
                    pass
            return
        self._settings_changed(reload_settings)

    def export_settings(self) -> Dict[str, Any]:
        return plugin_settings.get_all_settings(self.id)

    def import_settings(self, settings: Dict[str, Any], reload_settings: bool = True):
        plugin_settings.replace_all_settings(self.id, settings)
        self._settings_changed(reload_settings)

    # ----- high-level hooks

    def add_hook(self, name: str, match_substring: bool = False, priority: int = 0):
        plugins_controller().addEventHook(self.id, str(name), bool(match_substring), int(priority))

    def remove_hook(self, name: str):
        plugins_controller().removeEventHook(self.id, str(name))

    def add_on_send_message_hook(self, priority: int = 0):
        self.add_hook(SEND_MESSAGE_HOOK, False, priority)

    def remove_on_send_message_hook(self):
        self.remove_hook(SEND_MESSAGE_HOOK)

    # ----- file handlers (removed automatically on unload)

    def add_file_hook(self, file_info) -> str:
        """Registers a ``FilesController.FileInfo`` for this plugin; returns its secret."""
        from file_utils import FilesController
        secret = FilesController.register(file_info)
        self.__dict__.setdefault("_BasePlugin__fh", {})[FilesController._norm_ext(file_info.ext)] = secret
        return secret

    def remove_file_hook(self, ext: str) -> bool:
        from file_utils import FilesController
        key = FilesController._norm_ext(ext)
        secret = self.__dict__.setdefault("_BasePlugin__fh", {}).pop(key, None)
        if secret is None:
            return False
        try:
            FilesController.unregister(key, secret)
            return True
        except Exception:
            return False

    # ----- intent handlers (removed automatically on unload)

    def add_intent_hook(self, callback: Callable, hook_type="before", **filters):
        """Registers an IntentsManager handler (``hook_type`` "before"/"after" or an
        ``IntentHookType``) owned by this plugin; returns its HandlerHandle."""
        from intents import IntentHookType, IntentsManager
        kind = IntentHookType.of(hook_type)
        if kind is IntentHookType.AFTER:
            handle = IntentsManager.new_global_after_handler(callback, **filters)
        else:
            handle = IntentsManager.new_global_before_handler(callback, **filters)
        self.__dict__.setdefault("_BasePlugin__ih", {})[handle.handler_id] = handle
        return handle

    def remove_intent_hook(self, handle) -> bool:
        """Accepts the HandlerHandle returned by add_intent_hook or its handler id."""
        from intents import IntentsManager
        handler_id = getattr(handle, "handler_id", handle)
        self.__dict__.setdefault("_BasePlugin__ih", {}).pop(handler_id, None)
        try:
            IntentsManager.unhandle(handler_id)
            return True
        except IntentsManager.HandlerNotRegistered:
            return False

    # ----- menu items

    def add_menu_item(self, menu_item_data: MenuItemData) -> Optional[str]:
        data = menu_item_data
        menu_type = data.menu_type.value if isinstance(data.menu_type, MenuItemType) else str(data.menu_type)
        user_cb = data.on_click

        def on_click(context):
            ctx = java_map_to_dict(context)
            account = ctx.get("account")
            if account is not None:
                from client_utils import account_scope
                try:
                    with account_scope(int(account)):
                        safe_call(user_cb, ctx, where="menu item %s" % data.text)
                    return
                except (TypeError, ValueError):
                    pass
            safe_call(user_cb, ctx, where="menu item %s" % data.text)

        payload = {
            "menu_type": menu_type,
            "text": data.text,
            "on_click": on_click,
            "item_id": data.item_id,
            "icon": data.icon,
            "subtext": data.subtext,
            "condition": data.condition,
            "priority": int(data.priority or 0),
        }
        payload = {k: v for k, v in payload.items() if v is not None}
        result = plugins_controller().addMenuItem(self.id, payload)
        return None if result is None else str(result)

    def remove_menu_item(self, item_id: str) -> bool:
        return bool(plugins_controller().removeMenuItem(self.id, str(item_id)))

    # ----- Xposed-style method hooks

    def _make_callback(self, handler, priority, before_filters=None, after_filters=None):
        if isinstance(handler, MethodReplacement) or (
                not isinstance(handler, MethodHook) and callable(getattr(handler, "replace_hooked_method", None))):
            return J("com.exteragram.messenger.plugins.xposed.PyMethodReplacement")(self.id, handler, int(priority))
        has_before = _is_method_hook_phase_enabled(handler, "before_hooked_method")
        has_after = _is_method_hook_phase_enabled(handler, "after_hooked_method")
        if not (has_before or has_after):
            raise TypeError("hook handler must define before_hooked_method, after_hooked_method "
                            "or replace_hooked_method")
        cb = J("com.exteragram.messenger.plugins.xposed.PyMethodHook")(
            self.id, handler, int(priority), has_before, has_after)
        before, after = split_filters(handler, before_filters, after_filters)
        if before:
            cb.setBeforeHookedFilters(to_array_list([f.to_java() for f in before]))
        if after:
            cb.setAfterHookedFilters(to_array_list([f.to_java() for f in after]))
        return cb

    def _register_unhook(self, unhook):
        if unhook is not None:
            plugins_controller().addXposedHook(self.id, unhook)
        return unhook

    def hook_method(self, method, handler=None, priority: int = DEFAULT_XPOSED_PRIORITY, before=None, after=None,
                    before_filters=None, after_filters=None):
        """Hooks one java.lang.reflect.Method/Constructor. Returns the Unhook object."""
        if handler is None:
            if before is None and after is None:
                raise TypeError("hook_method needs a handler or before=/after= callables")
            handler = BaseHook(before, after)
        cb = self._make_callback(handler, priority, before_filters, after_filters)
        unhook = J("de.robv.android.xposed.XposedBridge").hookMethod(method, cb)
        return self._register_unhook(unhook)

    def hook_all_methods(self, clazz, method_name: str, handler, priority: int = DEFAULT_XPOSED_PRIORITY,
                         before_filters=None, after_filters=None):
        jcls = to_java_class(clazz)
        result = []
        for m in J("com.exteragram.messenger.plugins.utils.SafeReflection").getDeclaredMethods(jcls):
            if str(m.getName()) == method_name:
                result.append(self.hook_method(m, handler, priority,
                                               before_filters=before_filters, after_filters=after_filters))
        return result

    def hook_all_constructors(self, clazz, handler, priority: int = DEFAULT_XPOSED_PRIORITY,
                              before_filters=None, after_filters=None):
        jcls = to_java_class(clazz)
        return [self.hook_method(c, handler, priority, before_filters=before_filters, after_filters=after_filters)
                for c in jcls.getDeclaredConstructors()]

    def unhook_method(self, unhook) -> bool:
        if unhook is None:
            return False
        if isinstance(unhook, (list, tuple, set)):
            return all([self.unhook_method(u) for u in unhook])
        try:
            plugins_controller().removeXposedHook(self.id, unhook)
            return True
        except Exception as e:
            file_log("unhook_method failed: %r" % (e,), error=True)
            return False

    # ----- account helper

    def client(self, account: Optional[int] = None):
        """AccountClient for ``account``; without it, the account the current hook is
        handling, else the account selected in the UI."""
        from client_utils import get_client
        return get_client(account)
