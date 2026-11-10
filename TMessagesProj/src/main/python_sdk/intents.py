"""IntentsManager: global before/after handlers for links, deeplinks and other intents.

Handlers are matched against the Intent that LaunchActivity handles (scheme, host, path
template, required query arguments, action, flags, MIME type, categories), run in ascending
priority order and receive only the arguments their signature asks for. A before handler
returning True stops later handlers and the client's own handling.
"""

import inspect
import itertools
import re
import secrets
import threading
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional
from urllib.parse import parse_qsl, urlsplit

from _sg_java import J, file_log, find_methods, sdk_hook

__all__ = ["IntentsManager", "IntentHookType"]

_STANDARD_ARGS = ("intent", "scheme", "host", "path", "query_args", "action", "flags", "type", "categories")


class IntentHookType(Enum):
    BEFORE = "before"
    AFTER = "after"

    @classmethod
    def of(cls, value):
        if isinstance(value, cls):
            return value
        return cls(str(value).lower())


@dataclass
class _HandlerInfo:
    handler_id: str
    hook_type: IntentHookType
    callback: Callable
    scheme: Optional[str] = None
    host: Optional[str] = None
    path: Optional[str] = None
    path_regex: Any = None
    path_args: List[str] = field(default_factory=list)
    required_path_args_names: List[str] = field(default_factory=list)
    action: Optional[str] = None
    whitelist_flags: List[int] = field(default_factory=list)
    blacklist_flags: List[int] = field(default_factory=list)
    type: Optional[str] = None
    categories: List[str] = field(default_factory=list)
    priority: int = 0
    order: int = 0


class IntentsManager:
    SUPPORTED = True

    HandlerInfo = _HandlerInfo
    HookType = IntentHookType

    class HandlerNotRegistered(Exception):
        def __init__(self, handler_id):
            super().__init__("Handler '%s' is not registered." % (handler_id,))
            self.handler_id = handler_id

    class HandlerHandle:
        __slots__ = ("handler_id",)

        def __init__(self, handler_id: str):
            self.handler_id = handler_id

        def unhandle(self):
            IntentsManager.unhandle(self.handler_id)

        def remove(self):  # pre-1.4.5 name
            try:
                self.unhandle()
            except IntentsManager.HandlerNotRegistered:
                pass

        @property
        def id(self):
            return self.handler_id

        def __repr__(self):
            return "HandlerHandle(%r)" % (self.handler_id,)

    _handlers: Dict[str, _HandlerInfo] = {}
    _lock = threading.RLock()
    _order = itertools.count()
    _unhook = None

    # ----- registration

    @classmethod
    def new_global_before_handler(cls, callback: Callable, scheme: Optional[str] = None, host: Optional[str] = None,
                                  path: Optional[str] = None, required_path_args_names: Optional[List[str]] = None,
                                  action: Optional[str] = None, whitelist_flags: Optional[List[int]] = None,
                                  blacklist_flags: Optional[List[int]] = None, type: Optional[str] = None,
                                  categories: Optional[List[str]] = None, priority: int = 0) -> "IntentsManager.HandlerHandle":
        return cls._install_handler(IntentHookType.BEFORE, callback, scheme, host, path, required_path_args_names,
                                    action, whitelist_flags, blacklist_flags, type, categories, priority)

    @classmethod
    def new_global_after_handler(cls, callback: Callable, scheme: Optional[str] = None, host: Optional[str] = None,
                                 path: Optional[str] = None, required_path_args_names: Optional[List[str]] = None,
                                 action: Optional[str] = None, whitelist_flags: Optional[List[int]] = None,
                                 blacklist_flags: Optional[List[int]] = None, type: Optional[str] = None,
                                 categories: Optional[List[str]] = None, priority: int = 0) -> "IntentsManager.HandlerHandle":
        return cls._install_handler(IntentHookType.AFTER, callback, scheme, host, path, required_path_args_names,
                                    action, whitelist_flags, blacklist_flags, type, categories, priority)

    @classmethod
    def unhandle(cls, handler_id) -> None:
        handler_id = getattr(handler_id, "handler_id", handler_id)
        with cls._lock:
            if cls._handlers.pop(handler_id, None) is None:
                raise cls.HandlerNotRegistered(handler_id)

    @classmethod
    def remove_handler(cls, handle):
        """Pre-1.4.5 name; ignores unknown handlers."""
        try:
            cls.unhandle(handle)
        except cls.HandlerNotRegistered:
            pass

    @staticmethod
    def parse(url: str) -> Dict[str, Any]:
        parts = urlsplit(str(url))
        out = {"scheme": parts.scheme or None, "host": parts.hostname or (parts.netloc or None)}
        if parts.path:
            out["path"] = parts.path
        if parts.query:
            out["query"] = parts.query
        return out

    # ----- internals

    @staticmethod
    def _normalize_path(path):
        if path is None:
            return None
        path = str(path)
        return path if path.startswith("/") else "/" + path

    @staticmethod
    def _build_path_regex(path):
        names = []

        def repl(m):
            names.append(m.group(1))
            return "(?P<%s>[^/]+)" % m.group(1)
        parts = re.split(r"(\{[A-Za-z_][A-Za-z0-9_]*\})", path)
        pattern = "".join(repl(re.match(r"\{(.+)\}", p)) if p.startswith("{") and p.endswith("}") else re.escape(p)
                          for p in parts)
        return re.compile(pattern), names

    @staticmethod
    def _normalize_flags(flags):
        return [int(f) for f in (flags or ())]

    @staticmethod
    def _normalize_categories(categories):
        return [str(c) for c in (categories or ())]

    @classmethod
    def _install_handler(cls, hook_type, callback, scheme, host, path, required, action, whitelist_flags,
                         blacklist_flags, type_, categories, priority):
        if not callable(callback):
            raise TypeError("callback must be callable")
        if isinstance(required, str):
            required = [required]
        info = _HandlerInfo(
            handler_id=secrets.token_hex(8), hook_type=hook_type, callback=callback,
            scheme=str(scheme).lower() if scheme else None, host=str(host).lower() if host else None,
            path=cls._normalize_path(path), required_path_args_names=[str(n) for n in (required or ())],
            action=str(action) if action else None, whitelist_flags=cls._normalize_flags(whitelist_flags),
            blacklist_flags=cls._normalize_flags(blacklist_flags), type=str(type_) if type_ else None,
            categories=cls._normalize_categories(categories), priority=int(priority or 0), order=next(cls._order))
        if info.path is not None and "{" in info.path:
            info.path_regex, info.path_args = cls._build_path_regex(info.path)
        with cls._lock:
            cls._handlers[info.handler_id] = info
            cls._ensure_hook()
        return cls.HandlerHandle(info.handler_id)

    @classmethod
    def _ensure_hook(cls):
        if cls._unhook is not None:
            return
        try:
            methods = find_methods("org.telegram.ui.LaunchActivity", "handleIntent")
            if not methods:
                raise RuntimeError("LaunchActivity.handleIntent not found")
            target = max(methods, key=lambda m: len(m.getParameterTypes()))
            cls._unhook = sdk_hook(target, _IntentHook(), 50)
        except Exception as e:
            file_log("IntentsManager: cannot hook LaunchActivity.handleIntent: %r" % (e,), error=True)

    @classmethod
    def _stop(cls):
        with cls._lock:
            cls._handlers.clear()
            unhook, cls._unhook = cls._unhook, None
        if unhook is not None:
            try:
                unhook.unhook()
            except Exception:
                pass

    @staticmethod
    def _extract_query_args(uri):
        if uri is None:
            return {}
        try:
            query = uri.getEncodedQuery()
        except Exception:
            query = None
        if not query:
            return {}
        return dict(parse_qsl(str(query), keep_blank_values=True))

    @staticmethod
    def _intent_context(intent):
        uri = intent.getData()
        scheme = host = path = None
        if uri is not None:
            scheme = uri.getScheme()
            host = uri.getHost()
            path = uri.getPath()
        cats = intent.getCategories()
        categories = [str(c) for c in cats.toArray()] if cats is not None else []
        return {
            "intent": intent,
            "scheme": str(scheme) if scheme is not None else None,
            "host": str(host) if host is not None else None,
            "path": str(path) if path is not None else None,
            "query_args": IntentsManager._extract_query_args(uri),
            "action": str(intent.getAction()) if intent.getAction() is not None else None,
            "flags": int(intent.getFlags()),
            "type": str(intent.getType()) if intent.getType() is not None else None,
            "categories": categories,
        }

    @staticmethod
    def _match(info, ctx):
        """Returns the path-template variables (dict) when ``info`` matches, else None."""
        if info.scheme and (ctx["scheme"] or "").lower() != info.scheme:
            return None
        if info.host and (ctx["host"] or "").lower() != info.host:
            return None
        path_args = {}
        if info.path is not None:
            actual = ctx["path"] or "/"
            if info.path_regex is not None:
                m = info.path_regex.fullmatch(actual)
                if m is None:
                    return None
                path_args = m.groupdict()
            elif actual.rstrip("/") != info.path.rstrip("/"):
                return None
        for name in info.required_path_args_names:
            if name not in ctx["query_args"]:
                return None
        if info.action and ctx["action"] != info.action:
            return None
        flags = ctx["flags"]
        for f in info.whitelist_flags:
            if flags & f != f:
                return None
        for f in info.blacklist_flags:
            if flags & f:
                return None
        if info.type and ctx["type"] != info.type:
            return None
        for c in info.categories:
            if c not in ctx["categories"]:
                return None
        return path_args

    @staticmethod
    def _invoke_handler_callback(callback, ctx, path_args):
        named = dict(ctx["query_args"])
        named.update(path_args)
        named.update({k: ctx[k] for k in _STANDARD_ARGS})
        try:
            sig = inspect.signature(callback)
        except (TypeError, ValueError):
            return callback(**named)
        params = list(sig.parameters.values())
        has_var_args = any(p.kind is p.VAR_POSITIONAL for p in params)
        has_var_kwargs = any(p.kind is p.VAR_KEYWORD for p in params)
        args, kwargs = [], {}
        if has_var_args:
            bundle = [ctx[k] for k in _STANDARD_ARGS]
            positional = [p for p in params if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
            for p in positional:
                args.append(named.get(p.name))
            args.extend(bundle)
        for p in params:
            if p.kind in (p.VAR_POSITIONAL, p.VAR_KEYWORD):
                continue
            if has_var_args and p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD):
                continue
            if p.kind is p.POSITIONAL_ONLY:
                args.append(named.get(p.name))
            elif p.name in named:
                kwargs[p.name] = named[p.name]
            elif p.default is p.empty:
                kwargs[p.name] = None
        if has_var_kwargs:
            for k, v in named.items():
                kwargs.setdefault(k, v)
        return callback(*args, **kwargs)

    @classmethod
    def _dispatch(cls, hook_type, intent):
        """Runs the matching handlers; returns True when a before handler stopped handling."""
        with cls._lock:
            handlers = sorted((h for h in cls._handlers.values() if h.hook_type is hook_type),
                              key=lambda h: (h.priority, h.order))
        if not handlers or intent is None:
            return False
        ctx = cls._intent_context(intent)
        for info in handlers:
            path_args = cls._match(info, ctx)
            if path_args is None:
                continue
            try:
                result = cls._invoke_handler_callback(info.callback, ctx, path_args)
            except Exception:
                import traceback
                file_log("intent handler %s failed: %s" % (info.handler_id, traceback.format_exc()), error=True)
                continue
            if hook_type is IntentHookType.BEFORE and result is True:
                return True
        return False


class _IntentHook:
    def before_hooked_method(self, param):
        intent = param.args[0] if len(param.args) else None
        if IntentsManager._dispatch(IntentHookType.BEFORE, intent):
            param.setResult(J("java.lang.Boolean")(True))

    def after_hooked_method(self, param):
        intent = param.args[0] if len(param.args) else None
        IntentsManager._dispatch(IntentHookType.AFTER, intent)
