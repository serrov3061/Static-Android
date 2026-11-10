"""Java class proxies: real Java subclasses (generated with DexMaker by the engine's
ClassProxy) whose methods are implemented in Python.

Supported: ``Base``, ``java_subclass``, ``joverride``, ``joverload``, ``jmethod``,
``jconstructor``, ``jpreconstructor``, ``jfield`` (with Java-only ``jgetmethod`` /
``jsetmethod`` accessors), ``jMVELmethod`` / ``jMVELoverride`` (method bodies run by
MVEL on the Java side), ``jclassbuilder`` (DexMaker hook) and ``PyObj``. Python methods
call the Java parent implementation with ``super().<name>(...)``.

When a plugin is unloaded (``release_plugin``) the classes it defined are retired: their
peers are released and the generated Java classes behave like their Java parents.
"""

import copy
import inspect
import sys
import threading
import weakref

from java import dynamic_proxy, jarray

from _sg_java import J, file_log, to_array_list, to_java_class

__all__ = ["Base", "java_subclass", "jrelease", "joverride", "joverload", "jmethod", "jconstructor", "jpreconstructor",
           "jfield", "jgetmethod", "jsetmethod", "jclassbuilder", "jmvelmethod", "jMVELmethod",
           "jmveloverride", "jMVELoverride", "PyObj", "release_plugin"]

_PROXY = "com.exteragram.messenger.plugins.utils.ClassProxy"
_PYTHON_PEER_FIELD_NAME = "__extera_internal_python_peer__"
_UNSET = object()

# java.lang.reflect.Modifier flags
_MODIFIER_FLAGS = {
    "public": 0x1, "private": 0x2, "protected": 0x4, "static": 0x8, "final": 0x10,
    "synchronized": 0x20, "volatile": 0x40, "transient": 0x80, "native": 0x100, "abstract": 0x400,
}

_PRIMITIVES = {
    "void": "java.lang.Void", "int": "java.lang.Integer", "long": "java.lang.Long",
    "boolean": "java.lang.Boolean", "float": "java.lang.Float", "double": "java.lang.Double",
    "short": "java.lang.Short", "byte": "java.lang.Byte", "char": "java.lang.Character",
}
_PY_TYPES = {int: "int", bool: "boolean", float: "double", str: "java.lang.String", type(None): "void"}


def _type(t):
    if t is None:
        return J("java.lang.Void").TYPE
    if t in _PY_TYPES:
        t = _PY_TYPES[t]
    if isinstance(t, str):
        if t in _PRIMITIVES:
            return J(_PRIMITIVES[t]).TYPE
        if t.endswith("[]"):
            comp = _type(t[:-2])
            return J("java.lang.reflect.Array").newInstance(comp, 0).getClass()
        return to_java_class(t)
    return to_java_class(t)


def _types(seq):
    return jarray(J("java.lang.Class"))([_type(t) for t in (seq or [])])


def _mark(fn, kind, **info):
    fn.__jproxy__ = dict(kind=kind, **info)
    return fn


def joverride(name=None):
    """Overrides every overload of a parent method (by Java name; default: the Python name)."""
    if callable(name):
        return _mark(name, "override", java_name=name.__name__, params=None)
    return lambda fn: _mark(fn, "override", java_name=name or fn.__name__, params=None)


def joverload(java_name, parameter_types):
    return lambda fn: _mark(fn, "override", java_name=java_name, params=list(parameter_types))


def jmethod(*args):
    """New Java method: @jmethod(ret, [params]) or @jmethod(java_name, ret, [params])."""
    if len(args) == 1 and callable(args[0]):
        fn = args[0]
        return _mark(fn, "method", java_name=fn.__name__, ret=None, params=None)
    if len(args) == 3:
        java_name, ret, params = args
    elif len(args) == 2:
        java_name, (ret, params) = None, args
    elif len(args) == 0:
        java_name, ret, params = None, None, None
    else:
        raise TypeError("jmethod(java_name?, return_type, parameter_types)")
    return lambda fn: _mark(fn, "method", java_name=java_name or fn.__name__, ret=ret, params=params)


def jconstructor(parameter_types=None):
    return lambda fn: _mark(fn, "constructor", params=parameter_types)


def jpreconstructor(parameter_types=None):
    return lambda fn: _mark(fn, "preconstructor", params=parameter_types)


def _parse_modifiers(value, default=0x1):
    """``None`` -> default; an int (``java.lang.reflect.Modifier`` flags); a name such as
    ``"public static"`` / ``"private|final"``; or a list of names / ints."""
    if value is None:
        return default
    if isinstance(value, bool):
        raise ValueError("Unknown modifier: %r" % (value,))
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        parts = value.replace("|", " ").replace(",", " ").split()
    else:
        parts = list(value)
    flags = 0
    for part in parts:
        if isinstance(part, int) and not isinstance(part, bool):
            flags |= part
            continue
        flag = _MODIFIER_FLAGS.get(str(part).strip().lower())
        if flag is None:
            raise ValueError("Unknown modifier: %s" % (part,))
        flags |= flag
    return flags


def _copy_default(value):
    if isinstance(value, (list, dict, set, bytearray)):
        try:
            return copy.deepcopy(value)
        except Exception:
            return copy.copy(value)
    return value


def _title(name):
    return "".join(p[:1].upper() + p[1:] for p in str(name).split("_") if p)


def _type_name(t):
    if t is None:
        return "void"
    if t in _PY_TYPES:
        return _PY_TYPES[t]
    if isinstance(t, str):
        return t
    try:
        return str(_type(t).getName())
    except Exception:
        return str(t)


# ---- fields

_FIELD_GET = {"int": "getInt", "long": "getLong", "boolean": "getBoolean", "float": "getFloat",
              "double": "getDouble", "short": "getShort", "byte": "getByte", "char": "getChar"}
_BOXED = set(_PRIMITIVES.values()) - {"java.lang.Void"}


class _FieldAccessorMetadata:
    """A Java-only getter / setter generated for a ``jfield`` (no Python call at runtime)."""

    def __init__(self, kind, name=None, modifiers=None):
        if kind not in ("get", "set"):
            raise ValueError("Unknown field accessor kind: %s" % (kind,))
        self.kind = kind
        self.name = name
        self.modifiers = modifiers

    def resolve(self, field_name):
        name = self.name or (self.kind + _title(field_name))
        return name, _parse_modifiers(self.modifiers, default=0x1), self.kind == "get"

    def __repr__(self):
        return "j%smethod(%r)" % (self.kind, self.name)


def jgetmethod(name=None, modifiers=None):
    """Java getter returning the field directly (``name`` defaults to ``get<FieldName>``)."""
    return _FieldAccessorMetadata("get", name, modifiers)


def jsetmethod(name=None, modifiers=None):
    """Java setter writing the field directly (``name`` defaults to ``set<FieldName>``)."""
    return _FieldAccessorMetadata("set", name, modifiers)


class JField:
    """A real Java field on the generated class, readable / writable as a peer attribute."""

    def __init__(self, field_type="java.lang.Object", default=_UNSET, methods=None, modifiers=None, name=None):
        self.field_type = field_type
        self.default = default
        self.methods = list(methods or [])
        for m in self.methods:
            if not isinstance(m, _FieldAccessorMetadata):
                raise TypeError("jfield methods must be jgetmethod(...) / jsetmethod(...), got %r" % (m,))
        self.modifiers = _parse_modifiers(modifiers, default=0x1)
        self.name = name
        self.attr = name

    @property
    def static(self):
        return bool(self.modifiers & 0x8)

    @property
    def storage_name(self):
        return "__jfield_value_" + (self.name or "")

    def __set_name__(self, owner, name):
        self.attr = name
        if not self.name:
            self.name = name

    def _initial(self):
        return None if self.default is _UNSET else _copy_default(self.default)

    def __get__(self, inst, owner=None):
        if inst is None:
            rt = _ProxyRuntime.find(owner)
            if self.static and rt is not None and rt.java_class is not None:
                return rt.get_field(None, self.name)
            return self
        rt = _ProxyRuntime.find(type(inst))
        java = inst.__dict__.get("java")
        if rt is not None and rt.java_class is not None and (java is not None or self.static):
            return rt.get_field(java, self.name)
        if self.storage_name in inst.__dict__:
            return inst.__dict__[self.storage_name]
        return self._initial()

    def __set__(self, inst, value):
        rt = _ProxyRuntime.find(type(inst))
        java = inst.__dict__.get("java")
        if rt is not None and rt.java_class is not None and (java is not None or self.static):
            rt.set_field(java, self.name, value)
        else:
            inst.__dict__[self.storage_name] = value

    def __repr__(self):
        return "jfield(%r, name=%r)" % (self.field_type, self.name)


def jfield(field_type="java.lang.Object", default=_UNSET, methods=None, modifiers=None, name=None):
    """Declares a Java field: ``counter = jfield("int", default=0)``. ``methods`` takes
    ``jgetmethod(...)`` / ``jsetmethod(...)`` to generate Java-only accessors; ``modifiers``
    is a ``Modifier`` int or names such as ``"public static"``."""
    return JField(field_type, default=default, methods=methods, modifiers=modifiers, name=name)


# ---- MVEL methods

def _parse_argument_specs(owner_name, arguments, argument_names=None, argument_types=None):
    """Returns (names, types); types is None when no signature was given."""
    if arguments is None and argument_types is None:
        if argument_names:
            raise ValueError("MVEL method '%s' defines argument names without argument types" % owner_name)
        return None, None
    if arguments is not None:
        names, types = [], []
        for arg in arguments:
            if isinstance(arg, (tuple, list)):
                if len(arg) != 2:
                    raise ValueError("MVEL method '%s': argument must be (name, type), got %r" % (owner_name, arg))
                names.append(str(arg[0]) if arg[0] else "")
                types.append(arg[1])
            else:
                names.append("")
                types.append(arg)
        if argument_names:
            if len(argument_names) != len(types):
                raise ValueError("MVEL method '%s' must have the same number of argument names and argument types"
                                 % owner_name)
            names = [str(n) for n in argument_names]
        return names, types
    types = list(argument_types)
    names = [str(n) for n in (argument_names or [""] * len(types))]
    if len(names) != len(types):
        raise ValueError("MVEL method '%s' must have the same number of argument names and argument types"
                         % owner_name)
    return names, types


class JMvelMethod:
    """A generated Java method whose body is compiled MVEL code (no Python call at runtime).

    Inside the code: the root object and ``java`` are the Java instance, ``python`` / ``py`` /
    ``self`` the Python peer, ``args`` / ``argc`` / ``arg0``.., the named arguments, and for
    overrides ``SUPER_<name>(...)`` calls the Java parent implementation."""

    def __init__(self, code, return_type=None, arguments=None, override=False, name=None, modifiers=None,
                 argument_names=None, argument_types=None):
        if not isinstance(code, str) or not code.strip():
            raise ValueError("MVEL method needs non-empty code")
        self.code = code
        self.return_type = return_type
        self.arguments = arguments
        self.argument_names = argument_names
        self.argument_types = argument_types
        self.override = bool(override)
        self.name = name
        self.attr = name
        self.modifiers = modifiers
        if self.modifiers is not None and _parse_modifiers(self.modifiers) & 0x8:
            raise ValueError("MVEL methods can not be static")

    def __set_name__(self, owner, name):
        self.attr = name
        if not self.name:
            self.name = name

    def resolve(self):
        """(java_name, names, types) with types None for 'every overload' (overrides only)."""
        names, types = _parse_argument_specs(self.name, self.arguments, self.argument_names, self.argument_types)
        if types is None and not self.override:
            names, types = [], []
        return self.name, names, types

    def __get__(self, inst, owner=None):
        if inst is None:
            return self
        java = inst.__dict__.get("java")
        if java is None:
            raise AttributeError("MVEL method '%s': the peer is not attached to a Java object" % self.name)
        return getattr(java, self.name)

    def __repr__(self):
        return "%s(%r)" % ("jMVELoverride" if self.override else "jMVELmethod", self.name)


def jMVELmethod(return_type=None, arguments=None, code=None, name=None, modifiers=None,
                argument_names=None, argument_types=None):
    """New Java method backed by MVEL: ``label = jMVELmethod(return_type="java.lang.String",
    arguments=[("value", "int")], code="return 'v=' + value;")``. The Java name is the attribute
    name unless ``name`` is passed."""
    if code is None and isinstance(return_type, str) and arguments is None and argument_types is None \
            and not _looks_like_type(return_type):
        return_type, code = None, return_type
    return JMvelMethod(code, return_type=return_type, arguments=arguments, override=False, name=name,
                       modifiers=modifiers, argument_names=argument_names, argument_types=argument_types)


def jMVELoverride(arguments=None, code=None, name=None, argument_names=None, argument_types=None):
    """Overrides a parent Java method with MVEL code. ``arguments`` (``[(name, type), ...]``)
    selects one overload; omitted, every overload of that name is overridden."""
    if isinstance(arguments, str):
        if code is None:
            arguments, code = None, arguments          # jMVELoverride("return 1;")
        elif name is None:
            name, arguments = arguments, None          # jMVELoverride("javaName", code=...)
    return JMvelMethod(code, arguments=arguments, override=True, name=name,
                       argument_names=argument_names, argument_types=argument_types)


def _looks_like_type(text):
    t = text.strip()
    return bool(t) and (t in _PRIMITIVES or t.endswith("[]") or
                        all(part.isidentifier() for part in t.replace("$", "_").split(".")))


jmvelmethod = jMVELmethod
jmveloverride = jMVELoverride


# ---- class builder hooks

def jclassbuilder(fn=None):
    """``@jclassbuilder()``: ``fn(cls, dex_maker, proxy_type, super_type, interfaces)`` runs
    before the generated class is finalized (arguments it does not accept are dropped)."""
    if callable(fn) or isinstance(fn, (classmethod, staticmethod)):
        return _mark(fn, "classbuilder")
    return lambda f: _mark(f, "classbuilder")


def _call_with_supported_args(fn, *args):
    try:
        params = list(inspect.signature(fn).parameters.values())
    except (TypeError, ValueError):
        return fn(*args)
    if any(p.kind == p.VAR_POSITIONAL for p in params):
        return fn(*args)
    n = sum(1 for p in params if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD))
    return fn(*args[:n])


def _jproxy_info(obj):
    info = getattr(obj, "__jproxy__", None)
    if info is None:
        info = getattr(getattr(obj, "__func__", None), "__jproxy__", None)
    return info


class PyObj:
    """Carries an arbitrary Python object through Java APIs (as an opaque Java object)."""

    _store = {}
    _lock = threading.Lock()

    @classmethod
    def create(cls, value):
        holder = J("java.lang.Object")()
        with cls._lock:
            cls._store[int(J("java.lang.System").identityHashCode(holder))] = (holder, value)
        return holder

    @classmethod
    def get(cls, holder):
        entry = cls._store.get(int(J("java.lang.System").identityHashCode(holder)))
        return entry[1] if entry and entry[0] == holder else None


class Base:
    """Base class of Python peers of generated Java classes. ``self.java`` is the Java object."""

    java = None

    @property
    def this(self):
        """Alias of ``self.java``."""
        return self.__dict__.get("java")

    def __getattr__(self, item):
        j = self.__dict__.get("java")
        if j is None or item.startswith("__"):
            raise AttributeError(item)
        return getattr(j, item)

    @classmethod
    def new_instance(cls, *args, init_args=None):
        """Creates the Java object (``args`` go to the Java constructor) and returns its peer;
        ``init_args`` are passed to the Python ``__init__`` of the peer."""
        return _ProxyRuntime.of(cls).new_instance(*args, init_args=init_args)

    @classmethod
    def new_java_instance(cls, *args, init_args=None):
        peer = cls.new_instance(*args, init_args=init_args)
        return None if peer is None else peer.java

    @classmethod
    def get_java_class(cls):
        return _ProxyRuntime.of(cls).java_class

    java_class = get_java_class

    @classmethod
    def from_java(cls, java_obj):
        """The Python peer of a Java instance of this class (created on demand), or None
        for an object released with jrelease()."""
        return _ProxyRuntime.of(cls).peer_for(java_obj, create=True, revive=False)

    def jrelease(self):
        """Disconnects this peer: the Java object stays valid, its overridden methods then
        behave like the Java parent's."""
        rt = _ProxyRuntime.of(type(self))
        try:
            self.on_release()
        finally:
            rt.release(self, mark=True)

    def on_release(self):
        pass

    @classmethod
    def bind(cls, parent, *interfaces, methods=None, constructors=None, custom_name=None):
        """Same as decorating the class with ``@java_subclass(parent, *interfaces, ...)``;
        returns the bound class."""
        return java_subclass(parent, *interfaces, methods=methods, constructors=constructors,
                             custom_name=custom_name)(cls)

    extends = bind


def jrelease(peer):
    """Function form of ``Base.jrelease``."""
    peer.jrelease()


class _ProxyRuntime:
    _by_class = {}
    _registry_lock = threading.RLock()

    def __init__(self, py_cls, parent, interfaces, name, methods=None, constructors=None):
        self.py_cls = py_cls
        self.parent = to_java_class(parent)
        self.interfaces = [to_java_class(i) for i in interfaces or []]
        self.name = name or py_cls.__name__
        self.legacy_methods = list(methods) if methods else None
        self.legacy_constructors = list(constructors) if constructors else None
        self.overrides_by_sig = {}
        self.overrides_by_name = {}
        self.methods = {}
        self.constructor = None
        self.preconstructor = None
        self.fields = {}            # java name -> JField
        self.mvel = []              # JMvelMethod
        self.build_hooks = []       # (callable, pass_cls)
        self.has_peer_field = False
        self._reflect = {}          # java field name -> java.lang.reflect.Field
        self.peers = {}
        self.released = set()
        self.retired = False
        self.owners = _owner_modules(py_cls)
        self.local = threading.local()
        self.java_class = None
        self.handler = None
        self.build_hook = None
        self.lock = threading.RLock()

    @classmethod
    def of(cls, py_cls):
        rt = cls.find(py_cls)
        if rt is None:
            raise TypeError("%s is not decorated with @java_subclass" % py_cls.__name__)
        return rt

    @classmethod
    def find(cls, py_cls):
        if py_cls is None:
            return None
        rt = cls._by_class.get(py_cls)
        if rt is None:
            for klass in getattr(py_cls, "__mro__", ())[1:]:
                rt = cls._by_class.get(klass)
                if rt is not None:
                    break
        return rt

    # ---- class generation

    def build(self):
        Spec = J(_PROXY + "$ProxyMethodSpec")
        specs = []
        super_methods = {}
        # walk the MRO: java_subclass wraps classes that do not inherit Base into a subclass of them
        members = {}
        for klass in reversed(self.py_cls.__mro__):
            if klass is not object and klass is not Base:
                members.update(vars(klass))
        for attr, fn in list(members.items()):
            if isinstance(fn, JField):
                self.fields[fn.name or attr] = fn
                continue
            if isinstance(fn, JMvelMethod):
                self.mvel.append(fn)
                jname, names, types = fn.resolve()
                ptypes = None if types is None else _types(types)
                arg_names = to_array_list(names) if names else None
                if fn.override:
                    specs.append(Spec(jname, None, ptypes, 0, True, "mvel", fn.code, arg_names))
                    super_methods[attr] = jname
                else:
                    ret = _type(fn.return_type)
                    specs.append(Spec(jname, ret, ptypes, _parse_modifiers(fn.modifiers), False, "mvel",
                                      fn.code, arg_names))
                continue
            info = _jproxy_info(fn)
            if not info:
                continue
            kind = info["kind"]
            if kind == "override":
                jname = info["java_name"]
                if info["params"] is None:
                    self.overrides_by_name[jname] = fn
                    specs.append(Spec(jname, None, None, 0, True))
                else:
                    ptypes = _types(info["params"])
                    sig = jname + "".join(":" + str(c.getName()) for c in ptypes)
                    self.overrides_by_sig[sig] = fn
                    specs.append(Spec(jname, None, ptypes, 0, True))
                super_methods[attr] = jname
            elif kind == "method":
                ret, params = info["ret"], info["params"]
                if ret is None and params is None:
                    ret, params = _infer_signature(fn)
                ptypes = _types(params or [])
                ret = _type(ret) if ret is not None else J("java.lang.Object")
                self.methods[info["java_name"] + "".join(":" + str(c.getName()) for c in ptypes)] = fn
                specs.append(Spec(info["java_name"], ret, ptypes, 1, False))
            elif kind == "constructor":
                self.constructor = fn
            elif kind == "preconstructor":
                self.preconstructor = fn
            elif kind == "classbuilder":
                self.build_hooks.append((attr, fn))
        field_specs = self._field_specs()
        handler = _Handler(self)
        self.handler = handler  # keep alive
        hook = _BuildHook(self) if self.build_hooks else None
        self.build_hook = hook
        legacy_methods = to_array_list(self.legacy_methods) if self.legacy_methods else None
        legacy_ctors = to_array_list(self.legacy_constructors) if self.legacy_constructors else None
        self.java_class = J(_PROXY).createProxyClass(
            self.parent, handler, hook, to_array_list(self.interfaces), legacy_methods, legacy_ctors,
            to_array_list(specs), to_array_list(field_specs) if field_specs else None, self.name)
        self.build_hook = None  # only used while generating
        for jname, f in self.fields.items():
            if f.static and f.default is not _UNSET:
                self.set_field(None, jname, f._initial())
        return super_methods

    def _field_specs(self):
        FieldSpec = J(_PROXY + "$FieldSpec")
        FieldMethodSpec = J(_PROXY + "$FieldMethodSpec")
        out = []
        for jname, f in self.fields.items():
            accessors = []
            for acc in f.methods:
                mname, mods, getter = acc.resolve(jname)
                if f.static:
                    mods |= 0x8
                accessors.append(FieldMethodSpec(mname, mods, getter))
            out.append(FieldSpec(jname, _type(f.field_type), f.modifiers,
                                 to_array_list(accessors) if accessors else None))
        if any(not (_parse_modifiers(m.modifiers) & 0x8) for m in self.mvel):
            # the generated MVEL bodies read the Python peer from this field
            out.append(FieldSpec(_PYTHON_PEER_FIELD_NAME, J("java.lang.Object"), 0x1, None))
            self.has_peer_field = True
        return out

    def run_build_hooks(self, dex_maker, proxy_type, super_type, interfaces):
        for attr, fn in self.build_hooks:
            try:
                if isinstance(fn, staticmethod):
                    _call_with_supported_args(fn.__func__, dex_maker, proxy_type, super_type, interfaces)
                else:
                    func = fn.__func__ if isinstance(fn, classmethod) else fn
                    _call_with_supported_args(func, self.py_cls, dex_maker, proxy_type, super_type, interfaces)
            except Exception:
                import traceback
                file_log("Error in class build hook %s.%s:\n%s" % (self.name, attr, traceback.format_exc()),
                         error=True)
                raise

    # ---- Java fields

    def _field(self, jname):
        f = self._reflect.get(jname)
        if f is None:
            f = self.java_class.getDeclaredField(jname)
            f.setAccessible(True)
            self._reflect[jname] = f
        return f

    def get_field(self, java_obj, jname):
        spec = self.fields.get(jname)
        tname = _type_name(spec.field_type) if spec is not None else "java.lang.Object"
        return getattr(self._field(jname), _FIELD_GET.get(tname, "get"))(java_obj)

    def set_field(self, java_obj, jname, value):
        spec = self.fields.get(jname)
        tname = _type_name(spec.field_type) if spec is not None else "java.lang.Object"
        f = self._field(jname)
        getter = _FIELD_GET.get(tname)
        if getter is not None:
            if tname == "boolean":
                value = bool(value)
            elif tname in ("float", "double"):
                value = float(value)
            elif tname != "char":
                value = int(value)
            return getattr(f, "s" + getter[1:])(java_obj, value)
        if tname in _BOXED and isinstance(value, (bool, int, float, str)):
            value = J(tname)(value)
        return f.set(java_obj, value)

    def attach_fields(self, peer):
        """Initializes the Java fields of a newly attached peer (pending values, defaults)."""
        java = peer.__dict__.get("java")
        if java is None:
            return
        for jname, f in self.fields.items():
            if f.static:
                continue
            key = f.storage_name
            if key in peer.__dict__:
                self.set_field(java, jname, peer.__dict__.pop(key))
            elif f.default is not _UNSET:
                self.set_field(java, jname, f._initial())
        self.set_peer_field(java, peer)

    def set_peer_field(self, java, peer):
        if self.has_peer_field and java is not None:
            try:
                self._field(_PYTHON_PEER_FIELD_NAME).set(java, peer)
            except Exception:
                import traceback
                file_log("class proxy %s: can not attach the Python peer:\n%s" % (self.name, traceback.format_exc()),
                         error=True)

    # ---- runtime

    def peer_for(self, java_obj, create=True, revive=True):
        if java_obj is None:
            return None
        key = int(J("java.lang.System").identityHashCode(java_obj))
        with self.lock:
            entry = self.peers.get(key)
            if entry is not None and entry.java == java_obj:
                return entry
            if self.retired or not create or (not revive and key in self.released):
                return None
            peer = getattr(self.local, "pending", None) or self.py_cls.__new__(self.py_cls)
            self.local.pending = None
            peer.java = java_obj
            self.peers[key] = peer
            self.released.discard(key)
        return peer

    def release(self, peer, mark=False):
        with self.lock:
            for k, v in list(self.peers.items()):
                if v is peer:
                    del self.peers[k]
                    if mark:
                        self.released.add(k)
        if self.has_peer_field:
            self.set_peer_field(peer.__dict__.get("java"), None)

    def retire(self):
        """Plugin unload: releases every peer and turns the generated class into a plain
        subclass of its Java parent (overrides call the parent, new methods return null)."""
        with self.lock:
            if self.retired:
                return
            self.retired = True
            peers = list(self.peers.values())
        for peer in peers:
            try:
                peer.on_release()
            except Exception:
                import traceback
                file_log("class proxy %s.on_release: %s" % (self.name, traceback.format_exc()), error=True)
            self.release(peer, mark=True)
        if self.handler is not None:
            self.handler.retire(set(self.overrides_by_name) |
                                {sig.split(":", 1)[0] for sig in self.overrides_by_sig})
        with _ProxyRuntime._registry_lock:
            for k, v in list(_ProxyRuntime._by_class.items()):
                if v is self:
                    del _ProxyRuntime._by_class[k]
        self.overrides_by_sig.clear()
        self.overrides_by_name.clear()
        self.methods.clear()
        self.constructor = self.preconstructor = None
        self.build_hooks = []
        self.peers.clear()

    def new_instance(self, *args, init_args=None):
        if self.retired:
            raise RuntimeError("%s belongs to an unloaded plugin" % self.name)
        ctors = [c for c in self.java_class.getDeclaredConstructors() if len(c.getParameterTypes()) == len(args)]
        if not ctors:
            raise TypeError("No matching constructor found for %s with %d arguments" % (self.name, len(args)))
        ctor = ctors[0]
        peer = self.py_cls.__new__(self.py_cls)
        self.local.pending = peer
        self.local.init_args = None if init_args is None else list(init_args)
        try:
            boxed = [_box(t, a) for t, a in zip(ctor.getParameterTypes(), args)]
            obj = ctor.newInstance(jarray(J("java.lang.Object"))(boxed))
        finally:
            self.local.pending = None
            self.local.init_args = None
        return self.peer_for(obj)

    def dispatch(self, this, sig, args):
        args = list(args) if args is not None else []
        if sig.startswith("<constructor>"):
            if this is None:
                if self.preconstructor is not None:
                    new_args = self.preconstructor(self.py_cls, *args)
                    if new_args is not None:
                        if not isinstance(new_args, (list, tuple)):
                            new_args = [new_args]
                        return jarray(J("java.lang.Object"))(list(new_args))
                return None
            peer = self.peer_for(this)
            if peer is None:
                return None
            self.attach_fields(peer)
            # order: Python __init__, matched @jconstructor, on_post_init
            init = getattr(type(peer), "__init__", None)
            if init is not None and init is not object.__init__:
                init_args = getattr(self.local, "init_args", None)
                self.local.init_args = None
                init(peer, *(args if init_args is None else init_args))
            if self.constructor is not None:
                self.constructor(peer, *args)
            post = getattr(type(peer), "on_post_init", None)
            if post is not None:
                post(peer, *args)
            return None
        if this is not None and self.released and \
                int(J("java.lang.System").identityHashCode(this)) in self.released:
            # released peer: behave like the Java parent
            name = sig.split(":", 1)[0]
            if self.overrides_by_sig.get(sig) or name in self.overrides_by_name:
                return getattr(this, "SUPER_" + name)(*args)
            return None
        peer = self.peer_for(this)
        fn = self.overrides_by_sig.get(sig) or self.methods.get(sig)
        if fn is None:
            fn = self.overrides_by_name.get(sig.split(":", 1)[0])
        if fn is None and self.legacy_methods:
            fn = getattr(self.py_cls, sig.split(":", 1)[0], None)
        if fn is None:
            raise AttributeError("no Python implementation for %s" % sig)
        return fn(peer, *args)


def _box(jtype, value):
    name = str(jtype.getName())
    if name in _PRIMITIVES and isinstance(value, (bool, int, float, str)):
        return J(_PRIMITIVES[name])(value)
    return value


def _infer_signature(fn):
    """(return_type, parameter_types) from annotations of a ``@jmethod()`` function."""
    try:
        sig = inspect.signature(fn)
    except (TypeError, ValueError):
        return None, []
    params = list(sig.parameters.values())[1:]  # self
    types = []
    for p in params:
        if p.annotation is inspect.Parameter.empty:
            raise TypeError("jmethod '%s' requires arg_types or annotations" % fn.__name__)
        types.append(p.annotation)
    ret = None if sig.return_annotation is inspect.Signature.empty else sig.return_annotation
    return ret, types


class _Handler(dynamic_proxy(J("org.telegram.messenger.Utilities$Callback3Return"))):
    def __init__(self, runtime):
        super().__init__()
        self._rt = weakref.proxy(runtime)
        self._name = runtime.name
        self._retired = None

    def retire(self, override_names):
        # forget the runtime (and so the plugin's class and module); keep only what is needed
        # to forward overridden methods to the Java parent
        self._retired = frozenset(override_names)
        self._rt = None

    def run(self, this, sig, args):
        sig = str(sig)
        if self._retired is not None:
            if this is None or sig.startswith("<constructor>"):
                return None
            name = sig.split(":", 1)[0]
            if name in self._retired:
                return getattr(this, "SUPER_" + name)(*(list(args) if args is not None else []))
            return None
        try:
            return self._rt.dispatch(this, sig, args)
        except Exception:
            import traceback
            file_log("Error in managed proxy %s (%s):\n%s" % (self._name, sig, traceback.format_exc()), error=True)
            return None


class _BuildHook(dynamic_proxy(J(_PROXY + "$DexMakerHook"))):
    def __init__(self, runtime):
        super().__init__()
        self._rt = weakref.proxy(runtime)

    def apply(self, dex_maker, proxy_type, super_type, interfaces):
        self._rt.run_build_hooks(dex_maker, proxy_type, super_type, interfaces)


# ---- ownership / plugin unload

_SDK_MODULE_PREFIXES = ("extera_utils", "base_plugin", "elyx", "elyxcore", "_sg_", "ui.", "importlib", "java")


def _owner_modules(py_cls):
    """Modules a class belongs to: its own module plus the modules importing / defining it
    (so a class from a plugin's helper module is owned by that plugin as well)."""
    owners = {getattr(py_cls, "__module__", None)}
    try:
        frame = sys._getframe(1)
    except Exception:
        frame = None
    depth = 0
    while frame is not None and depth < 64:
        name = frame.f_globals.get("__name__")
        if name and not name.startswith(_SDK_MODULE_PREFIXES) and name != "__main__":
            owners.add(name)
        frame = frame.f_back
        depth += 1
    owners.discard(None)
    return frozenset(owners)


def _owned_by(rt, plugin_id):
    prefix = plugin_id + "."
    return any(m == plugin_id or m.startswith(prefix) for m in rt.owners)


def release_plugin(plugin_id):
    """Retires every class proxy defined by ``plugin_id`` (called by the engine on unload)."""
    if not plugin_id:
        return 0
    plugin_id = str(plugin_id)
    with _ProxyRuntime._registry_lock:
        runtimes = {id(rt): rt for rt in _ProxyRuntime._by_class.values() if _owned_by(rt, plugin_id)}
    for rt in runtimes.values():
        try:
            rt.retire()
        except Exception:
            import traceback
            file_log("class proxy release of %s failed: %s" % (rt.name, traceback.format_exc()), error=True)
    return len(runtimes)


def java_subclass(parent, *interfaces, methods=None, constructors=None, custom_name=None, name=None):
    """Class decorator: generates a Java subclass of ``parent`` (implementing ``interfaces``)
    whose methods are implemented by the Python class.

    ``custom_name`` is the middle part of the generated class name (default: the Python class
    name). ``methods`` / ``constructors`` are optional lists of ``java.lang.reflect.Method`` /
    ``Constructor`` to proxy explicitly. A single list or tuple of interfaces is accepted too."""
    if len(interfaces) == 1 and isinstance(interfaces[0], (list, tuple)):
        interfaces = tuple(interfaces[0])
    custom_name = custom_name or name

    def deco(py_cls):
        if not issubclass(py_cls, Base):
            py_cls = type(py_cls.__name__, (py_cls, Base), {"__module__": py_cls.__module__})
        rt = _ProxyRuntime(py_cls, parent, [i for i in interfaces if i is not None], custom_name,
                           methods=methods, constructors=constructors)
        with _ProxyRuntime._registry_lock:
            _ProxyRuntime._by_class[py_cls] = rt
        try:
            super_methods = rt.build()
        except Exception:
            with _ProxyRuntime._registry_lock:
                _ProxyRuntime._by_class.pop(py_cls, None)
            raise

        def make_super(jname):
            def call_super(self, *args):
                return getattr(self.java, "SUPER_" + jname)(*args)
            return call_super

        # super().<name>(...) inside an override reaches the Java parent implementation
        mixin = type(py_cls.__name__ + "_JavaSuper", (Base,),
                     {attr: make_super(jn) for attr, jn in super_methods.items()})
        if py_cls.__bases__ == (Base,):
            py_cls.__bases__ = (mixin,)
        py_cls.__java_runtime__ = rt
        return py_cls

    return deco
