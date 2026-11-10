"""extera_utils.classes: jfield / jgetmethod / jsetmethod / jMVELmethod / jMVELoverride /
jclassbuilder / release_plugin against a fake ClassProxy (the real one runs DexMaker on device)."""

import pytest

import _sg_java

PROXY = "com.exteragram.messenger.plugins.utils.ClassProxy"


class _Rec:
    """Records constructor arguments, like the Java spec records."""
    kind = None

    def __init__(self, *args):
        self.args = args


class ProxyMethodSpec(_Rec):
    kind = "method"

    @property
    def name(self):
        return self.args[0]

    @property
    def mvel(self):
        return len(self.args) > 5 and self.args[5] == "mvel"


class FieldSpec(_Rec):
    kind = "field"


class FieldMethodSpec(_Rec):
    kind = "accessor"


class JList(list):
    def add(self, item):
        self.append(item)


class FakeField:
    def __init__(self, cls, name):
        self.cls, self.name = cls, name

    def setAccessible(self, flag):
        pass

    def _store(self, obj):
        return self.cls.static_values if obj is None else obj.fields

    def __getattr__(self, item):
        if item.startswith("get"):
            return lambda obj: self._store(obj).get(self.name)
        if item.startswith("set"):
            def setter(obj, value):
                self._store(obj)[self.name] = (item, value)[1]
                self.cls.writes.append((item, self.name, value))
            return setter
        raise AttributeError(item)


class FakeObj:
    def __init__(self):
        self.fields = {}
        self.super_calls = []

    def __getattr__(self, item):
        if item.startswith("SUPER_"):
            def call(*args):
                self.super_calls.append((item, args))
                return "super:" + item
            return call
        raise AttributeError(item)


class FakeCtor:
    def __init__(self, cls, nparams):
        self.cls, self.n = cls, nparams

    def getParameterTypes(self):
        return [_Name("java.lang.Object")] * self.n

    def newInstance(self, args):
        handler = self.cls.handler
        sig = "<constructor>"
        new_args = handler.run(None, sig, list(args))
        args = list(new_args) if new_args is not None else list(args)
        obj = FakeObj()
        obj.ctor_args = args
        handler.run(obj, sig, args)
        return obj


class _Name:
    def __init__(self, n):
        self.n = n

    def getName(self):
        return self.n


class FakeJavaClass:
    def __init__(self, handler):
        self.handler = handler
        self.static_values = {}
        self.writes = []

    def getDeclaredField(self, name):
        return FakeField(self, name)

    def getDeclaredConstructors(self):
        return [FakeCtor(self, 0), FakeCtor(self, 1)]


class FakeClassProxy:
    calls = []

    @classmethod
    def createProxyClass(cls, parent, handler, hook, interfaces, methods, ctors, specs, fields, name):
        call = dict(parent=parent, handler=handler, hook=hook, interfaces=list(interfaces or []),
                    specs=list(specs or []), fields=list(fields or []), name=name)
        cls.calls.append(call)
        if hook is not None:
            hook.apply("DEX", "PROXY_TYPE", "SUPER_TYPE", ["IFACE"])
        jc = FakeJavaClass(handler)
        call["java_class"] = jc
        return jc


class FakeSystem:
    @staticmethod
    def identityHashCode(obj):
        return id(obj)


@pytest.fixture
def classes(java):
    _sg_java.J.cache_clear()
    java.classes[PROXY] = FakeClassProxy
    java.classes[PROXY + "$ProxyMethodSpec"] = ProxyMethodSpec
    java.classes[PROXY + "$FieldSpec"] = FieldSpec
    java.classes[PROXY + "$FieldMethodSpec"] = FieldMethodSpec
    java.classes["java.util.ArrayList"] = JList
    java.classes["java.lang.System"] = FakeSystem
    FakeClassProxy.calls = []
    from extera_utils import classes as mod
    yield mod
    for name in (PROXY, PROXY + "$ProxyMethodSpec", PROXY + "$FieldSpec", PROXY + "$FieldMethodSpec",
                 "java.util.ArrayList", "java.lang.System"):
        java.classes.pop(name, None)
    _sg_java.J.cache_clear()


def _parent():
    return "java.util.ArrayList"


def test_modifiers(classes):
    p = classes._parse_modifiers
    assert p(None) == 1
    assert p("public static") == 0x9
    assert p(["private", "final"]) == 0x12
    assert p("protected|static") == 0xC
    assert p(0x11) == 0x11
    with pytest.raises(ValueError, match="Unknown modifier"):
        p("publik")


def test_jfield_specs_defaults_and_access(classes):
    @classes.java_subclass(_parent())
    class Counting(classes.Base):
        added = classes.jfield("int", default=0)
        title = classes.jfield("java.lang.String", default="Hello")
        shadow = classes.jfield("boolean", default=True,
                                methods=[classes.jgetmethod("isShadow"), classes.jsetmethod()])
        items = classes.jfield("java.lang.Object", default=[])
        total = classes.jfield("long", default=7, modifiers="public static", methods=[classes.jgetmethod()])

    call = FakeClassProxy.calls[-1]
    fields = {f.args[0]: f for f in call["fields"]}
    assert set(fields) == {"added", "title", "shadow", "items", "total"}
    assert fields["total"].args[2] == 0x9
    acc = [(a.args[0], a.args[1], a.args[2]) for a in fields["shadow"].args[3]]
    assert acc == [("isShadow", 1, True), ("setShadow", 1, False)]
    assert [(a.args[0], a.args[1]) for a in fields["total"].args[3]] == [("getTotal", 0x9)]
    assert fields["added"].args[3] is None
    # static default is written once after class generation
    assert call["java_class"].static_values["total"] == 7
    assert Counting.total == 7

    a = Counting.new_instance()
    b = Counting.new_instance()
    assert a.java.fields["added"] == 0 and a.java.fields["title"] == "Hello"
    assert a.added == 0
    a.added += 2
    assert a.java.fields["added"] == 2 and b.added == 0
    a.shadow = 0
    assert a.java.fields["shadow"] is False
    # mutable defaults are copied per instance
    assert a.items is not b.items
    # typed reflection setters are used for primitives
    assert ("setInt", "added", 2) in call["java_class"].writes
    assert ("setBoolean", "shadow", False) in call["java_class"].writes


def test_jfield_value_before_attach_is_flushed(classes):
    @classes.java_subclass(_parent())
    class P(classes.Base):
        n = classes.jfield("int", default=1)

    peer = P.__new__(P)
    assert peer.n == 1
    peer.n = 5
    assert peer.n == 5
    rt = classes._ProxyRuntime.of(P)
    peer.java = object.__new__(FakeObj)
    peer.java.fields = {}
    rt.attach_fields(peer)
    assert peer.java.fields["n"] == 5
    assert "__jfield_value_n" not in peer.__dict__


def test_jfield_rejects_foreign_methods(classes):
    with pytest.raises(TypeError):
        classes.jfield("int", methods=["getX"])


def test_mvel_method_and_override_specs(classes):
    @classes.java_subclass(_parent())
    class M(classes.Base):
        add_calls = classes.jfield("int", default=0)
        debug_label = classes.jMVELmethod(
            return_type="java.lang.String",
            arguments=[("prefix", "java.lang.String"), ("value", "int")],
            code="return prefix + value;",
        )
        add = classes.jMVELoverride(arguments=[("value", "java.lang.Object")],
                                    code="add_calls = add_calls + 1; return SUPER_add(value);")
        size = classes.jMVELoverride(code="return 3;")
        isEmpty = classes.jMVELoverride(arguments=[], code="return false;")
        renamed = classes.jMVELmethod(name="javaName", code="return null;")

    call = FakeClassProxy.calls[-1]
    specs = {s.name: s for s in call["specs"]}
    lbl = specs["debug_label"].args
    assert lbl[4] is False and lbl[5] == "mvel" and lbl[6] == "return prefix + value;"
    assert list(lbl[7]) == ["prefix", "value"] and len(lbl[2]) == 2 and lbl[3] == 1
    add = specs["add"].args
    assert add[4] is True and add[5] == "mvel" and len(add[2]) == 1 and list(add[7]) == ["value"]
    assert specs["size"].args[2] is None  # every overload
    assert specs["isEmpty"].args[2] == [] and specs["isEmpty"].args[7] is None
    assert "javaName" in specs and "renamed" not in specs
    # the MVEL bodies read the Python peer from this generated field
    names = [f.args[0] for f in call["fields"]]
    assert classes._PYTHON_PEER_FIELD_NAME in names

    peer = M.new_instance()
    assert peer.java.fields[classes._PYTHON_PEER_FIELD_NAME] is peer
    peer.jrelease()
    assert peer.java.fields[classes._PYTHON_PEER_FIELD_NAME] is None


def test_no_peer_field_without_mvel(classes):
    @classes.java_subclass(_parent())
    class Plain(classes.Base):
        x = classes.jfield("int")

    assert [f.args[0] for f in FakeClassProxy.calls[-1]["fields"]] == ["x"]


def test_mvel_argument_validation(classes):
    with pytest.raises(ValueError, match="same number"):
        classes.jMVELmethod(code="1", argument_names=["a", "b"], argument_types=["int"]).resolve()
    with pytest.raises(ValueError, match="without argument types"):
        classes.jMVELmethod(code="1", argument_names=["a"]).resolve()
    with pytest.raises(ValueError):
        classes.jMVELoverride(code="  ")
    m = classes.jMVELmethod("return 1;")
    assert m.code == "return 1;" and m.return_type is None
    o = classes.jMVELoverride("onMeasure", code="return null;")
    assert o.name == "onMeasure" and o.override
    assert classes.jmvelmethod is classes.jMVELmethod and classes.jmveloverride is classes.jMVELoverride


def test_jclassbuilder_hook(classes):
    seen = []

    @classes.java_subclass(_parent())
    class B(classes.Base):
        @classes.jclassbuilder()
        def build(cls, dex_maker, proxy_type, super_type, interfaces):
            seen.append((cls, dex_maker, proxy_type, super_type, interfaces))

        @classes.jclassbuilder
        def short(cls, dex_maker):
            seen.append(("short", dex_maker))

    assert FakeClassProxy.calls[-1]["hook"] is not None
    assert (B, "DEX", "PROXY_TYPE", "SUPER_TYPE", ["IFACE"]) in seen
    assert ("short", "DEX") in seen
    assert classes._ProxyRuntime.of(B).build_hook is None


def test_no_hook_without_classbuilder(classes):
    @classes.java_subclass(_parent())
    class N(classes.Base):
        pass

    assert FakeClassProxy.calls[-1]["hook"] is None


def test_java_subclass_interfaces_and_custom_name(classes):
    @classes.java_subclass(_parent(), "java.lang.Runnable", "java.lang.Comparable", custom_name="Row")
    class R(classes.Base):
        pass

    call = FakeClassProxy.calls[-1]
    assert len(call["interfaces"]) == 2
    assert call["name"] == "Row"

    class Late(classes.Base):
        pass

    Late.bind(_parent(), custom_name="LateRow")
    assert FakeClassProxy.calls[-1]["name"] == "LateRow"


def test_jmethod_annotation_inference(classes):
    @classes.java_subclass(_parent())
    class A(classes.Base):
        @classes.jmethod()
        def label(self, value: "int") -> "java.lang.String":
            return "v=%s" % value

    spec = [s for s in FakeClassProxy.calls[-1]["specs"] if s.name == "label"][0]
    assert len(spec.args[2]) == 1


def test_release_plugin_retires_classes(classes):
    released = []

    @classes.java_subclass(_parent())
    class Owned(classes.Base):
        @classes.joverride()
        def size(self):
            return 42

        def on_release(self):
            released.append(self)

    Owned.__java_runtime__.owners = frozenset({"my_plugin"})
    peer = Owned.new_instance()
    handler = FakeClassProxy.calls[-1]["handler"]
    assert handler.run(peer.java, "size", []) == 42

    assert classes.release_plugin("other_plugin") == 0
    assert classes.release_plugin("my_plugin") == 1
    assert released == [peer]
    assert classes._ProxyRuntime.find(Owned) is None
    # the generated class now behaves like its Java parent
    assert handler.run(peer.java, "size", []) == "super:SUPER_size"
    assert handler.run(peer.java, "unknown:int", [1]) is None
    assert handler.run(None, "<constructor>", []) is None
    with pytest.raises(TypeError):
        Owned.new_instance()


def test_owner_from_helper_module(classes):
    rt = classes._ProxyRuntime.__new__(classes._ProxyRuntime)
    rt.owners = frozenset({"plugin_a.helpers"})
    assert classes._owned_by(rt, "plugin_a") and not classes._owned_by(rt, "plugin")


def test_preconstructor_single_value(classes):
    @classes.java_subclass(_parent())
    class C(classes.Base):
        @classes.jpreconstructor(["int"])
        def fix(cls, n):
            return max(0, n)

    peer = C.new_instance(-5)
    assert list(peer.java.ctor_args) == [0]


def test_all_exports_implemented(classes):
    for name in classes.__all__:
        assert hasattr(classes, name), name
    src = open(classes.__file__).read()
    assert "NotImplementedError" not in src
