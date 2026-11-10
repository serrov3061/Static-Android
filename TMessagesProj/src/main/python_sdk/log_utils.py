"""SDK logger: levels, per-plugin logcat tags, tracebacks, object dumps, timers, handlers.

Every plugin gets ``self.logger`` (tag ``plugin.<id>``). ``get_logger()`` finds the calling
plugin from the stack, ``Logger(tag)`` makes a standalone logger and ``dump(obj)`` renders
the structure of Java and Python objects without running getters.

The legacy ``log(data)`` keeps working: text goes out at DEBUG under the SDK tag, any other
value is rendered with ``dump``.
"""

import itertools
import os
import sys
import threading
import time
import traceback
from enum import IntEnum
from typing import Any, Callable, List, Optional

from _sg_java import J

__all__ = ["LogLevel", "LogRecord", "Logger", "get_logger", "dump", "log", "PluginLogger"]

DEFAULT_TAG = "pysdk"
LOGCAT_CHUNK = 4000  # logcat cuts an entry at ~4 KB


class LogLevel(IntEnum):
    """Levels map to Android log priorities (``adb logcat *:E`` shows errors only)."""
    VERBOSE = 2
    DEBUG = 3
    INFO = 4
    WARNING = 5
    ERROR = 6
    OFF = 8


_LEVEL_NAMES = {"verbose": LogLevel.VERBOSE, "debug": LogLevel.DEBUG, "info": LogLevel.INFO,
                "warning": LogLevel.WARNING, "warn": LogLevel.WARNING, "error": LogLevel.ERROR,
                "off": LogLevel.OFF, "none": LogLevel.OFF}
_LETTERS = {LogLevel.VERBOSE: "V", LogLevel.DEBUG: "D", LogLevel.INFO: "I",
            LogLevel.WARNING: "W", LogLevel.ERROR: "E"}


def _coerce_level(level):
    if level is None:
        return None
    if isinstance(level, LogLevel):
        return level
    if isinstance(level, str):
        try:
            return _LEVEL_NAMES[level.strip().lower()]
        except KeyError:
            raise ValueError("Unknown log level: %r" % (level,))
    if isinstance(level, bool):
        raise TypeError("log level must be a LogLevel, str or int")
    if isinstance(level, int):
        try:
            return LogLevel(level)
        except ValueError:
            if level < LogLevel.VERBOSE:
                return LogLevel.VERBOSE
            if level > LogLevel.ERROR:
                return LogLevel.OFF
            raise
    raise TypeError("log level must be a LogLevel, str or int, got %r" % (type(level).__name__,))


# ---------------------------------------------------------------- developer mode

_dev_mode = None


def _read_dev_mode():
    global _dev_mode
    if _dev_mode is None:
        try:
            _dev_mode = bool(J("com.exteragram.messenger.ExteraConfig").getPluginsDevMode())
        except Exception:
            _dev_mode = False
    return _dev_mode


def _set_dev_mode(enabled):
    """Called when the developer mode switch changes (and by tests)."""
    global _dev_mode
    _dev_mode = None if enabled is None else bool(enabled)


def _default_level():
    return LogLevel.DEBUG if _read_dev_mode() else LogLevel.INFO


# ---------------------------------------------------------------- output

def _write_logcat(level, tag, text):
    priority = int(min(max(int(level), LogLevel.VERBOSE), LogLevel.ERROR))
    for chunk in _split_for_logcat(text):
        try:
            J("android.util.Log").println(priority, str(tag), chunk)
        except Exception:
            sys.stderr.write("%s/%s: %s\n" % (_LETTERS.get(level, "I"), tag, chunk))
    if level >= LogLevel.ERROR:
        try:
            J("org.telegram.messenger.FileLog").e("[%s] %s" % (tag, text))
        except Exception:
            pass


def _split_for_logcat(text, limit=LOGCAT_CHUNK):
    """Splits a long entry at line boundaries (hard-splits a single overlong line)."""
    if len(text.encode("utf-8", "replace")) <= limit:
        return [text]
    chunks, current, size = [], [], 0
    for line in text.split("\n"):
        while len(line.encode("utf-8", "replace")) > limit:
            if current:
                chunks.append("\n".join(current))
                current, size = [], 0
            cut = limit // 4 if any(ord(c) > 127 for c in line[:limit]) else limit
            chunks.append(line[:cut])
            line = line[cut:]
        n = len(line.encode("utf-8", "replace")) + 1
        if current and size + n > limit:
            chunks.append("\n".join(current))
            current, size = [], 0
        current.append(line)
        size += n
    if current:
        chunks.append("\n".join(current))
    return chunks


def _report_internal_error(where):
    try:
        _write_logcat(LogLevel.ERROR, DEFAULT_TAG, "%s\n%s" % (where, traceback.format_exc()))
    except Exception:
        pass


# ---------------------------------------------------------------- records

class LogRecord:
    __slots__ = ("level", "tag", "message", "location", "traceback", "created")

    def __init__(self, level, tag, message, location=None, traceback=None, created=None):
        self.level = level
        self.tag = tag
        self.message = message
        self.location = location
        self.traceback = traceback
        self.created = time.time() if created is None else created

    @property
    def text(self):
        """The entry exactly as written to logcat."""
        return _compose(self.message, self.location, self.traceback)

    def __repr__(self):
        return "LogRecord(level=%s, tag=%r, message=%r)" % (self.level.name, self.tag, self.message)


def _compose(message, location, tb):
    text = "[%s] %s" % (location, message) if location else message
    if tb:
        text = "%s\n%s" % (text, tb.rstrip("\n"))
    return text


def _format_message(msg, args):
    text = msg if isinstance(msg, str) else _scalar_or_dump(msg)
    if args:
        try:
            text = text % args
        except Exception:
            text = " ".join([text] + [a if isinstance(a, str) else _safe_repr(a) for a in args])
    return text


def _format_exc_info(exc_info):
    if exc_info is None or exc_info is False:
        return None
    if exc_info is True:
        info = sys.exc_info()
        if info[0] is None:
            return None
        return "".join(traceback.format_exception(*info))
    if isinstance(exc_info, BaseException):
        return "".join(traceback.format_exception(type(exc_info), exc_info, exc_info.__traceback__))
    if isinstance(exc_info, tuple) and len(exc_info) == 3:
        return "".join(traceback.format_exception(*exc_info))
    # e.g. a TL_error or another Java object
    return _safe_repr(exc_info)


def _caller_frame(stacklevel):
    """Frame of the code that called the public logging method (stacklevel 1); frames of
    this module (the logger itself, timers) are skipped, stacklevel=2 is the caller's caller."""
    try:
        frame = sys._getframe(1)
    except ValueError:
        return None
    this_file = __file__.rstrip("c")
    while frame is not None and frame.f_code.co_filename.rstrip("c") == this_file:
        frame = frame.f_back
    for _ in range(max(int(stacklevel), 1) - 1):
        if frame is None or frame.f_back is None:
            break
        frame = frame.f_back
    return frame


def _format_location(frame):
    if frame is None:
        return None
    code = frame.f_code
    return "%s:%s:%d" % (os.path.basename(code.co_filename), code.co_name, frame.f_lineno)


# ---------------------------------------------------------------- loggers

_handler_state = threading.local()


class _Timer:
    def __init__(self, logger, label, level, stacklevel):
        self._logger, self._label, self._level, self._stacklevel = logger, label, level, stacklevel
        self._start = None

    def __enter__(self):
        self._start = time.perf_counter()
        return self

    def __exit__(self, exc_type, exc, tb):
        elapsed = (time.perf_counter() - self._start) * 1000.0
        status = " (failed)" if exc_type is not None else ""
        self._logger.log(self._level, "%s took %.1f ms%s", self._label, elapsed, status,
                         stacklevel=self._stacklevel)
        return False


class Logger:
    """A tagged logger. ``level=None`` means: use the parent's level, else the default
    (DEBUG while developer mode is on, INFO otherwise)."""

    def __init__(self, tag: str, parent: Optional["Logger"] = None, show_location: bool = True):
        self._tag = str(tag)
        self._parent = parent
        self._level = None
        self._handlers = []
        self._children = {}
        self._lock = threading.RLock()
        self._show_location = show_location

    # ----- identity

    @property
    def tag(self) -> str:
        return self._tag

    def __repr__(self):
        return "<Logger %s level=%s>" % (self._tag, self.effective_level.name)

    # ----- levels

    @property
    def level(self) -> Optional[LogLevel]:
        return self._level

    @level.setter
    def level(self, value):
        self._level = _coerce_level(value)

    @property
    def effective_level(self) -> LogLevel:
        node = self
        while node is not None:
            if node._level is not None:
                return node._level
            node = node._parent
        return _default_level()

    def is_enabled_for(self, level) -> bool:
        level = _coerce_level(level)
        eff = self.effective_level
        return eff != LogLevel.OFF and level is not None and level != LogLevel.OFF and level >= eff

    # ----- hierarchy

    def child(self, name: str) -> "Logger":
        name = str(name).strip(".")
        if not name:
            return self
        with self._lock:
            lg = self._children.get(name)
            if lg is None:
                lg = self._children[name] = Logger("%s.%s" % (self._tag, name), parent=self,
                                                   show_location=self._show_location)
            return lg

    _child = child

    def add_handler(self, handler: Callable[[LogRecord], Any]):
        if not callable(handler):
            raise TypeError("handler must be callable")
        with self._lock:
            if handler not in self._handlers:
                self._handlers.append(handler)

    def remove_handler(self, handler) -> bool:
        with self._lock:
            try:
                self._handlers.remove(handler)
                return True
            except ValueError:
                return False

    def _reset(self):
        """Drops handlers and levels of this logger and its children (plugin unload)."""
        with self._lock:
            self._handlers = []
            self._level = None
            for c in self._children.values():
                c._reset()

    # ----- emitting

    def _dispatch(self, record):
        if getattr(_handler_state, "active", False):
            return
        _handler_state.active = True
        try:
            node = self
            while node is not None:
                for h in list(node._handlers):
                    try:
                        h(record)
                    except Exception:
                        _report_internal_error("log handler %r of %s failed" % (h, node._tag))
                node = node._parent
        finally:
            _handler_state.active = False

    def _emit(self, level, msg, args, exc_info=None, stacklevel=1):
        level = _coerce_level(level)
        if not self.is_enabled_for(level):
            return
        try:
            message = _format_message(msg, args)
            location = _format_location(_caller_frame(stacklevel)) if self._show_location else None
            tb = _format_exc_info(exc_info)
            record = LogRecord(level, self._tag, message, location, tb)
            _write_logcat(level, self._tag, record.text)
            self._dispatch(record)
        except Exception:
            _report_internal_error("logging failed in %s" % self._tag)

    def log(self, level, msg, *args, exc_info=None, stacklevel=1):
        self._emit(level, msg, args, exc_info, stacklevel)

    def verbose(self, msg, *args, exc_info=None, stacklevel=1):
        self._emit(LogLevel.VERBOSE, msg, args, exc_info, stacklevel)

    def debug(self, msg, *args, exc_info=None, stacklevel=1):
        self._emit(LogLevel.DEBUG, msg, args, exc_info, stacklevel)

    def info(self, msg, *args, exc_info=None, stacklevel=1):
        self._emit(LogLevel.INFO, msg, args, exc_info, stacklevel)

    def warning(self, msg, *args, exc_info=None, stacklevel=1):
        self._emit(LogLevel.WARNING, msg, args, exc_info, stacklevel)

    warn = warning

    def error(self, msg, *args, exc_info=None, stacklevel=1):
        self._emit(LogLevel.ERROR, msg, args, exc_info, stacklevel)

    def exception(self, msg="", *args, stacklevel=1):
        self._emit(LogLevel.ERROR, msg, args, True, stacklevel)

    def dump(self, obj, label=None, *, level=LogLevel.DEBUG, depth=2, max_items=20, stacklevel=1):
        if not self.is_enabled_for(level):
            return
        text = dump(obj, depth=depth, max_items=max_items)
        if label is not None:
            text = "%s: %s" % (label, text)
        self._emit(level, text, (), None, stacklevel)

    def timed(self, label, level=LogLevel.DEBUG, stacklevel=1):
        return _Timer(self, label, _coerce_level(level), stacklevel)


class PluginLogger(Logger):
    """Pre-1.4.5 name: ``PluginLogger(plugin_id)`` is the logger tagged ``plugin.<id>``."""

    def __new__(cls, plugin_id=None, *a, **k):
        if plugin_id is not None and not a and not k:
            return _plugin_logger(plugin_id)
        return super().__new__(cls)

    def __init__(self, plugin_id=None, *a, **k):
        if getattr(self, "_tag", None) is None:
            super().__init__("plugin.%s" % plugin_id)


# ---------------------------------------------------------------- plugin loggers

_plugin_loggers = {}
_plugin_loggers_lock = threading.Lock()


def _plugin_logger(plugin_id) -> Logger:
    pid = str(plugin_id)
    with _plugin_loggers_lock:
        lg = _plugin_loggers.get(pid)
        if lg is None:
            lg = Logger.__new__(Logger)
            Logger.__init__(lg, "plugin.%s" % pid)
            _plugin_loggers[pid] = lg
        return lg


def _reset_plugin_logger(plugin_id):
    with _plugin_loggers_lock:
        lg = _plugin_loggers.get(str(plugin_id))
    if lg is not None:
        lg._reset()


def _strip_plugin_prefix(name, plugin_id):
    if not name:
        return None
    parts = str(name).split(".")
    if parts and parts[0] == plugin_id:
        parts = parts[1:]
    if parts and parts[-1] == "__init__":
        parts = parts[:-1]
    return ".".join(parts) or None


def _plugin_id_from_frame(frame):
    from extera_utils.get_caller import get_plugin_id
    return get_plugin_id(frame)


def get_logger(name: Optional[str] = None) -> Logger:
    """Logger of the calling plugin. In the plugin's main file it is ``self.logger``; in other
    modules of the plugin it is a child named after the module. Outside plugins it returns a
    standalone logger tagged ``name`` (or ``pysdk``)."""
    try:
        frame = sys._getframe(1)
    except ValueError:
        frame = None
    pid = _plugin_id_from_frame(frame) if frame is not None else None
    if pid is None:
        return Logger(name or DEFAULT_TAG)
    root = _plugin_logger(pid)
    sub = _strip_plugin_prefix(name, pid)
    if sub is None or sub == "__main__":
        return root
    return root.child(sub)


# ---------------------------------------------------------------- dump

_PLATFORM_PREFIXES = ("java.", "javax.", "android.", "androidx.", "kotlin.", "dalvik.", "sun.",
                      "com.android.", "libcore.", "org.json.")
_JAVA_SCALARS = ("java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Boolean",
                 "java.lang.Double", "java.lang.Float", "java.lang.Short", "java.lang.Byte",
                 "java.lang.Character", "java.lang.CharSequence")


class _Unreadable:
    def __init__(self, error):
        self.error = error

    def __repr__(self):
        return "<unreadable: %s>" % (self.error,)


def _safe_repr(value, limit=200):
    try:
        text = repr(value)
    except Exception as e:
        text = "<repr failed: %s>" % (e,)
    return _truncate(text, limit)


def _truncate(text, limit=200):
    return text if len(text) <= limit else text[:limit - 1] + "…"


def _is_java(obj):
    return hasattr(obj, "getClass") and type(obj).__module__.startswith("java")


def _java_type_name(cls):
    try:
        name = str(cls.getName())
    except Exception:
        return "?"
    if name.startswith("["):
        try:
            return _java_type_name(cls.getComponentType()) + "[]"
        except Exception:
            return name
    simple = name.rsplit(".", 1)[-1]
    return simple.replace("$", ".")


def _scalar_repr(value):
    if isinstance(value, str):
        return _truncate(repr(value), 300)
    if value is None or isinstance(value, (bool, int, float, complex)):
        return repr(value)
    if isinstance(value, (bytes, bytearray)):
        return "%s[%d]" % (type(value).__name__, len(value))
    return None


def _scalar_or_dump(value):
    s = _scalar_repr(value)
    return s if s is not None else dump(value)


class _Dumper:
    def __init__(self, depth, max_items):
        self._depth = max(int(depth), 0)
        self._max_items = max(int(max_items), 0)
        self._lines = []
        self._seen = set()

    def render(self, obj):
        head, children = self._node(obj, 0)
        self._lines.append(head)
        self._take(children, 1)
        return "\n".join(self._lines)

    def _take(self, children, indent):
        for key, value, hidden in children:
            if hidden:
                self._lines.append("  " * indent + "...")
                continue
            head, sub = self._node(value, indent)
            self._lines.append("%s%s = %s" % ("  " * indent, key, head))
            self._take(sub, indent + 1)

    @staticmethod
    def _key(k):
        return k if isinstance(k, str) and k.isidentifier() else _safe_repr(k, 80)

    def _inline(self, obj):
        s = _scalar_repr(obj)
        if s is not None:
            return s
        if _is_java(obj):
            return "%s @%x" % (self._java_cls_name(obj), self._identity(obj))
        return "%s @%x" % (type(obj).__name__, id(obj) & 0xFFFFFF)

    @staticmethod
    def _identity(obj):
        try:
            return int(J("java.lang.System").identityHashCode(obj))
        except Exception:
            return id(obj) & 0xFFFFFF

    @staticmethod
    def _java_cls_name(obj):
        try:
            return _java_type_name(obj.getClass())
        except Exception:
            return type(obj).__name__

    def _node(self, obj, level):
        """Returns (head text, [(key, value, hidden)])."""
        s = _scalar_repr(obj)
        if s is not None:
            return s, []
        marker = id(obj)
        if marker in self._seen:
            return self._inline(obj) + " <cycle>", []
        if _is_java(obj):
            return self._inspect_java(obj, level, marker)
        return self._inspect(obj, level, marker)

    def _limited(self, pairs, total):
        out = []
        for i, (k, v) in enumerate(pairs):
            if i >= self._max_items:
                out.append((None, None, True))
                break
            out.append((k, v, False))
        return out

    def _inspect(self, obj, level, marker):
        if isinstance(obj, dict):
            head = "dict[%d]" % len(obj)
            pairs = ((self._key(k), v) for k, v in obj.items())
        elif isinstance(obj, (list, tuple, set, frozenset)):
            head = "%s[%d]" % (type(obj).__name__, len(obj))
            seq = sorted(obj, key=_safe_repr) if isinstance(obj, (set, frozenset)) else obj
            pairs = (("[%d]" % i, v) for i, v in enumerate(seq))
        else:
            fields = self._python_fields(obj)
            if fields is None:
                return _safe_repr(obj), []
            head = "%s @%x" % (type(obj).__name__, id(obj) & 0xFFFFFF)
            pairs = iter(fields)
        if level >= self._depth:
            return head + " {…}", []
        self._seen.add(marker)
        return head, self._limited(pairs, None)

    @staticmethod
    def _python_fields(obj):
        """Instance fields only: never runs properties or __getattr__."""
        if isinstance(obj, (type, type(len), type(_safe_repr))) or callable(obj) and not hasattr(obj, "__dict__"):
            return None
        out = []
        try:
            d = object.__getattribute__(obj, "__dict__")
        except Exception:
            d = None
        if isinstance(d, dict):
            out.extend((k, v) for k, v in sorted(d.items()) if not k.startswith("__"))
        for cls in type(obj).__mro__:
            for slot in getattr(cls, "__slots__", ()) or ():
                if isinstance(slot, str) and slot not in ("__dict__", "__weakref__"):
                    try:
                        out.append((slot, object.__getattribute__(obj, slot)))
                    except AttributeError:
                        pass
        if not out and d is None:
            return None
        return out

    def _java_fields(self, cls):
        Modifier = J("java.lang.reflect.Modifier")
        fields, names = [], set()
        while cls is not None:
            try:
                cname = str(cls.getName())
            except Exception:
                break
            if cname.startswith(_PLATFORM_PREFIXES):
                break
            for f in cls.getDeclaredFields():
                try:
                    if Modifier.isStatic(f.getModifiers()) or f.isSynthetic():
                        continue
                    n = str(f.getName())
                    if n in names:
                        continue
                    names.add(n)
                    fields.append(f)
                except Exception:
                    continue
            cls = cls.getSuperclass()
        fields.sort(key=lambda f: str(f.getName()))
        return fields

    def _java_iter(self, obj):
        """(head, pairs) for Java collections, maps, arrays and sparse arrays, else None."""
        cls = obj.getClass()
        try:
            if cls.isArray():
                comp = cls.getComponentType()
                length = len(obj)
                if comp.isPrimitive():
                    return "%s[%d]" % (str(comp.getName()), length), None
                return "%s[%d]" % (_java_type_name(comp), length), (("[%d]" % i, obj[i]) for i in range(length))
        except Exception:
            pass
        if J("java.util.Map").isInstance(obj):
            it = obj.entrySet().iterator()

            def gen():
                while it.hasNext():
                    e = it.next()
                    yield self._key_java(e.getKey()), e.getValue()
            return "%s[%d]" % (_java_type_name(cls), obj.size()), gen()
        if J("java.util.Collection").isInstance(obj):
            it = obj.iterator()

            def gen():
                i = 0
                while it.hasNext():
                    yield "[%d]" % i, it.next()
                    i += 1
            return "%s[%d]" % (_java_type_name(cls), obj.size()), gen()
        for sparse in ("android.util.SparseArray", "android.util.LongSparseArray", "androidx.collection.LongSparseArray"):
            try:
                if J(sparse).isInstance(obj):
                    n = obj.size()
                    return "%s[%d]" % (_java_type_name(cls), n), \
                        (("[%s]" % obj.keyAt(i), obj.valueAt(i)) for i in range(n))
            except Exception:
                continue
        return None

    def _key_java(self, k):
        s = _scalar_repr(k)
        if s is not None:
            return s if not isinstance(k, str) else (k if k.isidentifier() else s)
        try:
            return str(k.toString())
        except Exception:
            return _safe_repr(k, 80)

    def _inspect_java(self, obj, level, marker):
        try:
            cname = str(obj.getClass().getName())
        except Exception:
            return _safe_repr(obj), []
        if cname in _JAVA_SCALARS:
            return _truncate(repr(str(obj.toString())), 300), []
        coll = None
        try:
            coll = self._java_iter(obj)
        except Exception:
            coll = None
        if coll is not None:
            head, pairs = coll
            if pairs is None or level >= self._depth:
                return head + (" {…}" if pairs is not None else ""), []
            self._seen.add(marker)
            return head, self._limited(pairs, None)
        if cname.startswith(_PLATFORM_PREFIXES):
            try:
                return _truncate(str(obj.toString()), 300), []
            except Exception:
                return cname, []
        head = "%s @%x" % (_java_type_name(obj.getClass()), self._identity(obj))
        if level >= self._depth:
            return head + " {…}", []
        self._seen.add(marker)
        pairs = []
        for f in self._java_fields(obj.getClass()):
            try:
                f.setAccessible(True)
                v = f.get(obj)
            except Exception as e:
                v = _Unreadable(e)
            pairs.append((str(f.getName()), v))
        return head, self._limited(iter(pairs), None)


def dump(obj, depth: int = 2, max_items: int = 20) -> str:
    """Renders the structure of a Java or Python object. Only fields are read."""
    try:
        return _Dumper(depth, max_items).render(obj)
    except Exception as e:
        return "<dump failed: %s>" % (e,)


# ---------------------------------------------------------------- legacy

_legacy_logger = Logger(DEFAULT_TAG, show_location=False)
_legacy_logger.level = LogLevel.VERBOSE


def log(data, tag=None):
    """Legacy helper: DEBUG under the SDK tag (or ``tag``); non-text values are dumped."""
    text = data if isinstance(data, str) else _scalar_or_dump(data)
    if tag is None:
        _legacy_logger._emit(LogLevel.DEBUG, text, (), None, 1)
    else:
        _write_logcat(LogLevel.DEBUG, tag, text)
