"""Reflection helpers. All functions return None/False instead of raising."""

from typing import Any

from _sg_java import J, class_for_name, to_java_class

__all__ = ["find_class", "get_private_field", "set_private_field",
           "get_static_private_field", "set_static_private_field"]


class _ClassRef:
    """Class handle returned by find_class: a Chaquopy class proxy plus the java.lang.Class API.

    exteraGram's find_class returns a Chaquopy class proxy, so plugins use it to
    * read static members: ``find_class("...UserConfig").selectedAccount``, ``.getInstance(a)``,
      ``find_class("...Theme").key_windowBackgroundWhite``, ``Bitmap$Config.ARGB_8888``;
    * construct objects: ``find_class("android.graphics.Paint")(1)``;
    * build proxies: ``dynamic_proxy(find_class("...AlertDialog$OnButtonClickListener"))``;
    * call java.lang.Class methods: ``getDeclaredField``/``getDeclaredMethods``, and
      ``find_class(n).getClass()`` returning the *target* class (HiRez Photo).
    java.lang.Class method names go to the java.lang.Class, everything else to the class proxy.
    """

    __slots__ = ("class_", "_proxy")

    def __init__(self, jcls):
        object.__setattr__(self, "class_", jcls)
        object.__setattr__(self, "_proxy", None)

    def _jproxy(self):
        p = object.__getattribute__(self, "_proxy")
        if p is None:
            p = J(str(self.class_.getName()))
            object.__setattr__(self, "_proxy", p)
        return p

    def getClass(self):
        return self.class_

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        if name in _class_method_names():
            return getattr(self.class_, name)
        try:
            return getattr(self._jproxy(), name)
        except AttributeError:
            return getattr(self.class_, name)

    def __setattr__(self, name, value):
        # static field assignment, as on a Chaquopy class proxy
        setattr(self._jproxy(), name, value)

    def __call__(self, *args, **kwargs):
        return self._jproxy()(*args, **kwargs)

    def __instancecheck__(self, obj):
        return isinstance(obj, self._jproxy())

    def __subclasscheck__(self, cls):
        return issubclass(_unwrap(cls), self._jproxy())

    def __bool__(self):
        return True

    def __eq__(self, other):
        return self.class_ == getattr(other, "class_", other)

    def __hash__(self):
        return hash(self.class_)

    def __str__(self):
        return str(self.class_)

    __repr__ = __str__


_CLASS_METHODS = None


def _class_method_names():
    global _CLASS_METHODS
    if _CLASS_METHODS is None:
        try:
            _CLASS_METHODS = frozenset(str(m.getName()) for m in J("java.lang.Class").class_.getMethods())
        except Exception:
            _CLASS_METHODS = frozenset()
    return _CLASS_METHODS


def _unwrap(obj):
    return obj._jproxy() if isinstance(obj, _ClassRef) else obj


def _patch_java_module():
    """Lets find_class() results go wherever Chaquopy expects a class proxy (dynamic_proxy, jarray, ...)."""
    try:
        import java
    except Exception:
        return

    def make(orig):
        def wrapper(*args, **kwargs):
            return orig(*[_unwrap(a) for a in args], **kwargs)
        wrapper._sg_unwraps = True
        wrapper.__name__ = getattr(orig, "__name__", "wrapper")
        wrapper.__doc__ = getattr(orig, "__doc__", None)
        return wrapper

    for fname in ("dynamic_proxy", "static_proxy", "jarray", "cast"):
        orig = getattr(java, fname, None)
        if orig is None or getattr(orig, "_sg_unwraps", False):
            continue
        try:
            setattr(java, fname, make(orig))
        except Exception:
            pass


_patch_java_module()


# Hooks call find_class / get_private_field / set_private_field on every hooked call, so the
# lookups are cached: classes by name (also "not found": the app class loader is fixed) and
# fields by (class, name), where a miss walks the superclass chain through Java exceptions.
_CACHE_MAX = 4096
_class_refs = {}
_missing_classes = set()
_fields = {}
_NO_FIELD = object()


def find_class(class_name: str):
    """Returns the class (Chaquopy class proxy API plus the java.lang.Class API), or None."""
    try:
        key = str(class_name)
    except Exception:
        return None
    ref = _class_refs.get(key)
    if ref is not None:
        return ref
    if key in _missing_classes:
        return None
    try:
        ref = _ClassRef(class_for_name(key))
    except Exception:
        if len(_missing_classes) >= _CACHE_MAX:
            _missing_classes.clear()
        _missing_classes.add(key)
        return None
    if len(_class_refs) >= _CACHE_MAX:
        _class_refs.clear()
    _class_refs[key] = ref
    return ref


def _lookup_field(cls, field_name):
    while cls is not None:
        try:
            f = cls.getDeclaredField(field_name)
            f.setAccessible(True)
            return f
        except Exception:
            cls = cls.getSuperclass()
    return None


def _find_field(cls, field_name):
    if cls is None:
        return None
    try:
        key = (cls, str(field_name))
        f = _fields.get(key)
    except TypeError:  # unhashable class handle: no caching
        return _lookup_field(cls, field_name)
    if f is None:
        f = _lookup_field(cls, field_name)
        if len(_fields) >= _CACHE_MAX:
            _fields.clear()
        _fields[key] = _NO_FIELD if f is None else f
        return f
    return None if f is _NO_FIELD else f


def _clear_caches():
    _class_refs.clear()
    _missing_classes.clear()
    _fields.clear()


_BOXES = {
    "int": "java.lang.Integer", "long": "java.lang.Long", "short": "java.lang.Short",
    "byte": "java.lang.Byte", "float": "java.lang.Float", "double": "java.lang.Double",
    "boolean": "java.lang.Boolean", "char": "java.lang.Character",
}


def _box(field, value):
    """Boxes Python numbers to the field's primitive wrapper (Field.set takes Object)."""
    if isinstance(value, (bool, int, float, str)):
        box = _BOXES.get(str(field.getType().getName()))
        if box is not None:
            return J(box)(value)
    return value


def get_private_field(obj, field_name: str):
    try:
        f = _find_field(obj.getClass(), field_name)
        return f.get(obj) if f is not None else None
    except Exception:
        return None


def set_private_field(obj, field_name: str, new_value: Any) -> bool:
    try:
        f = _find_field(obj.getClass(), field_name)
        if f is None:
            return False
        f.set(obj, _box(f, new_value))
        return True
    except Exception:
        return False


def get_static_private_field(clazz, field_name: str):
    try:
        f = _find_field(to_java_class(clazz), field_name)
        return f.get(None) if f is not None else None
    except Exception:
        return None


def set_static_private_field(clazz, field_name: str, new_value: Any) -> bool:
    try:
        f = _find_field(to_java_class(clazz), field_name)
        if f is None:
            return False
        f.set(None, _box(f, new_value))
        return True
    except Exception:
        return False
