"""Elyx structured plugins: archive format, validation, install, import isolation, dev server."""

import base64
import binascii
import importlib.util
import io
import json
import marshal
import os
import struct
import sys
import zipfile
import zlib

import pytest


# ----- helpers


def _zipcrypto_keys(password):
    k0, k1, k2 = 0x12345678, 0x23456789, 0x34567890

    def crc(c, b):
        return (binascii.crc32(bytes([b]), c ^ 0xFFFFFFFF) ^ 0xFFFFFFFF) & 0xFFFFFFFF

    def update(b):
        nonlocal k0, k1, k2
        k0 = crc(k0, b)
        k1 = (k1 + (k0 & 0xFF)) & 0xFFFFFFFF
        k1 = (k1 * 134775813 + 1) & 0xFFFFFFFF
        k2 = crc(k2, (k1 >> 24) & 0xFF)

    for b in password:
        update(b)

    def encrypt(data):
        out = bytearray()
        for b in data:
            t = (k2 | 2) & 0xFFFF
            ks = ((t * (t ^ 1)) >> 8) & 0xFF
            out.append(b ^ ks)
            update(b)
        return bytes(out)
    return encrypt


def write_zipcrypto(path, files, password):
    """Minimal ZipCrypto (traditional PKWARE encryption) writer, stored entries."""
    pwd = password.encode()
    out = io.BytesIO()
    central = []
    for name, data in files.items():
        data = data.encode() if isinstance(data, str) else data
        crc = binascii.crc32(data) & 0xFFFFFFFF
        enc = _zipcrypto_keys(pwd)
        header = os.urandom(11) + bytes([(crc >> 24) & 0xFF])
        payload = enc(header + data)
        offset = out.tell()
        fname = name.encode()
        out.write(struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0x1, 0, 0, 0, crc, len(payload), len(data),
                              len(fname), 0))
        out.write(fname)
        out.write(payload)
        central.append((fname, crc, len(payload), len(data), offset))
    cd_start = out.tell()
    for fname, crc, csize, usize, offset in central:
        out.write(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, 0x1, 0, 0, 0, crc, csize, usize,
                              len(fname), 0, 0, 0, 0, 0, offset))
        out.write(fname)
    cd_size = out.tell() - cd_start
    out.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central), cd_size, cd_start, 0))
    with open(path, "wb") as f:
        f.write(out.getvalue())


def write_zip(path, files, dirs=()):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for d in dirs:
            zf.writestr(d.rstrip("/") + "/", b"")
        for name, data in files.items():
            zf.writestr(name, data)
    return str(path)


MAIN = '''
from base_plugin import BasePlugin
from elyx import assets, metainfo, settings, strings, get_environment
from src.greeting import build_greeting
import config
from . import helpers
from .helpers import VALUE


class HelloPlugin(BasePlugin):
    def on_plugin_load(self):
        self.loaded = True

    def greet(self, name):
        return build_greeting(name)

    def info(self):
        return {
            "logo": assets.logo.filename,
            "name": metainfo["name"],
            "api": config.api_url,
            "page": config["limits"].page_size,
            "helpers": helpers.VALUE,
            "value": VALUE,
            "env_keys": sorted(get_environment()),
            "settings_id": settings.plugin_id,
        }
'''

GREETING = '''
from elyx import strings

def build_greeting(name):
    return strings("greeting", name=name)

ORIGIN = "hello"
'''


def hello_files(plugin_id="elyx_hello", greeting=GREETING, extra=None):
    files = {
        "refmap.yml": "metainfo: metainfo.yml\nmain: main.py\nassets: assets\nstrings: strings\n",
        "metainfo.yml": ('id: %s\nname: Elyx Hello\ndescription: "{plugin_description}"\nauthor: Me\n'
                         'version: "1.2.0"\nicon: exteraPlugins/1\napp_version: ">=12.5.1"\nsdk_version: ">=1.4.5.3"\n'
                         'requirements: "requests>=2.32, tinydb>=4.8"\n'
                         'requires:\n  helper_plugin: https://example.com/helper.elyx\n'
                         '  shared_runtime (2.1.0): https://example.com/shared.elyx\n' % plugin_id),
        "main.py": MAIN,
        "helpers.py": "VALUE = 42\n",
        "config.json": json.dumps({"api_url": "https://example.com", "limits": {"page_size": 50}}),
        "src/__init__.py": "",
        "src/greeting.py": greeting,
        "assets/logo.svg": "<svg/>",
        "assets/icons/add.svg": "<svg/>",
        "strings/strings_en.yml": 'plugin_description: A small plugin\ngreeting: "Hello, {name}!"\n',
        "strings/strings_ru.yml": 'plugin_description: Маленький плагин\ngreeting: "Привет, {name}!"\n',
    }
    if extra:
        files.update(extra)
    return files


@pytest.fixture
def elyx(tmp_path, monkeypatch):
    from extera_utils import get_caller
    from elyxcore import _engine, _importer
    plugins = tmp_path / "plugins"
    plugins.mkdir()
    monkeypatch.setattr(get_caller, "_plugins_dir", str(plugins.resolve()))
    _engine.setup(str(plugins))
    yield _engine
    _engine.stop()
    get_caller._set_elyx_dir(None)


# ----- format / validation


def test_inspect_plain_archive(tmp_path):
    from elyxcore import _engine
    path = write_zip(tmp_path / "hello.elyx", hello_files(), dirs=["assets", "strings", "src"])
    res = json.loads(_engine.inspect_archive(path, None, "ru"))
    assert res["ok"], res
    info = res["plugin"]
    assert info["id"] == "elyx_hello" and info["name"] == "Elyx Hello" and info["version"] == "1.2.0"
    assert info["description"] == "Маленький плагин"
    assert info["icon"] == "exteraPlugins/1" and info["custom_icon"] is None
    assert info["requirements"] == ["requests>=2.32", "tinydb>=4.8"]
    assert {"id": "shared_runtime", "version": "2.1.0", "url": "https://example.com/shared.elyx"} in info["requires"]
    assert {"id": "helper_plugin", "version": None, "url": "https://example.com/helper.elyx"} in info["requires"]
    assert info["errors"] == [] and info["encrypted"] is False
    en = json.loads(_engine.inspect_archive(path, None, "de"))["plugin"]
    assert en["description"] == "A small plugin"  # English fallback


def test_defaults_without_refmap(tmp_path):
    from elyxcore._plugin import ElyxPlugin
    path = write_zip(tmp_path / "p.eaf", {"metainfo.json": json.dumps({"__id__": "plain_one", "__name__": "Plain"}),
                                          "main.py": "x = 1\n", "assets/a.txt": "a"})
    with ElyxPlugin.from_archive(path) as p:
        info = p.info()
        assert info["id"] == "plain_one" and info["main"] == "main.py" and info["version"] == "1.0.0"
        assert p.structure.assets_path == "assets" and not info["errors"]


@pytest.mark.parametrize("pid", ["a", "my-plugin", "my plugin", "плагин", "x" * 33])
def test_invalid_ids(tmp_path, pid):
    from elyxcore._plugin import ElyxPlugin
    path = write_zip(tmp_path / "p.elyx", {"metainfo.yml": "id: %s\nname: N\n" % json.dumps(pid), "main.py": ""})
    with ElyxPlugin.from_archive(path) as p:
        assert any("Invalid plugin id" in e for e in p.validate().errors)


def test_validation_errors(tmp_path):
    from elyxcore._plugin import ElyxPlugin
    files = {"refmap.yml": "main: src/main.py\nstrings: locales\nassets: res\n",
             "metainfo.yml": "id: ok_id\nname: N\nrequires:\n  bad-id (1.0): x\n"}
    with ElyxPlugin.from_archive(write_zip(tmp_path / "p.elyx", files)) as p:
        errors = p.validate().errors
    assert any('Main file with path "src/main.py" not found!' in e for e in errors)
    assert any('Strings with path "locales" not found!' in e for e in errors)
    assert any('Assets with path "res" not found!' in e for e in errors)
    assert any('Invalid plugin id requirement: "bad-id (1.0)"' in e for e in errors)
    with ElyxPlugin.from_archive(write_zip(tmp_path / "q.elyx", {"main.py": ""})) as p:
        assert any("Metainfo not found" in e for e in p.validate().errors)


def test_wrapper_directory_is_not_discovered(tmp_path):
    from elyxcore._plugin import ElyxPlugin
    files = {"my_plugin/" + k: v for k, v in hello_files().items()}
    with ElyxPlugin.from_archive(write_zip(tmp_path / "p.elyx", files)) as p:
        assert any("Metainfo not found" in e for e in p.validate().errors)


def test_path_traversal_rejected(tmp_path):
    from elyxcore import _engine
    path = tmp_path / "evil.elyx"
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("metainfo.yml", "id: evil_one\nname: E\n")
        zf.writestr("main.py", "")
        zf.writestr("../../escape.py", "boom")
    res = json.loads(_engine.inspect_archive(str(path)))
    assert not res["ok"] and res["error_kind"] == "security"


def test_not_a_zip(tmp_path):
    from elyxcore import _engine
    p = tmp_path / "x.elyx"
    p.write_bytes(b"not a zip")
    res = json.loads(_engine.inspect_archive(str(p)))
    assert not res["ok"] and "not a valid ZIP" in res["error"]


def test_password_protected_archive(tmp_path):
    from elyxcore import _engine
    path = str(tmp_path / "secret.elyx")
    write_zipcrypto(path, hello_files("secret_one"), "s3cret")
    res = json.loads(_engine.inspect_archive(path))
    assert not res["ok"] and res["error_kind"] == "password_required"
    res = json.loads(_engine.inspect_archive(path, "wrong"))
    assert not res["ok"] and res["error_kind"] == "wrong_password"
    res = json.loads(_engine.inspect_archive(path, "s3cret", "en"))
    assert res["ok"], res
    assert res["plugin"]["id"] == "secret_one" and res["plugin"]["encrypted"] is True


def test_custom_icon(tmp_path, elyx):
    files = hello_files("icon_one")
    files["metainfo.yml"] = files["metainfo.yml"].replace("icon: exteraPlugins/1", "icon: assets/icon.png")
    files["assets/icon.png"] = b"\x89PNG fake"
    path = write_zip(tmp_path / "i.elyx", files)
    info = json.loads(elyx.inspect_archive(path))["plugin"]
    assert info["icon"] is None and info["custom_icon"] == "assets/icon.png"
    assert open(info["custom_icon_file"], "rb").read() == b"\x89PNG fake"


def test_python_metainfo_is_not_executed(tmp_path):
    from elyxcore._plugin import ElyxPlugin
    files = {"refmap.json": json.dumps({"metainfo": "meta.py"}), "main.py": "",
             "meta.py": "import os\nos.system('touch %s')\n__id__ = 'py_meta'\n__name__ = 'Py'\nversion = '2.0'\n"
                        % (tmp_path / "pwned")}
    with ElyxPlugin.from_archive(write_zip(tmp_path / "p.elyx", files)) as p:
        info = p.info()
    assert info["id"] == "py_meta" and info["name"] == "Py" and info["version"] == "2.0"
    assert not (tmp_path / "pwned").exists()


# ----- install / load / isolation


def _install(elyx, tmp_path, name, files, password=None):
    path = str(tmp_path / name)
    if password:
        write_zipcrypto(path, files, password)
    else:
        write_zip(path, files)
    res = json.loads(elyx.install_archive(path, password))
    assert res["ok"], res
    elyx.commit_install(res["id"])
    return res["id"]


def test_install_and_load(tmp_path, elyx):
    pid = _install(elyx, tmp_path, "hello.elyx", hello_files())
    root = tmp_path / "plugins" / "ElyxPlugins" / pid
    assert (root / "refmap.yml").is_file() and (root / "src" / "greeting.py").is_file()
    assert (tmp_path / "plugins" / "archives" / (pid + ".elyx")).is_file()
    assert json.loads(elyx.installed_ids()) == [pid]
    instance = elyx.load_plugin(pid, "en")
    from base_plugin import BasePlugin
    assert isinstance(instance, BasePlugin) and type(instance).__name__ == "HelloPlugin"
    assert instance.greet("Alice") == "Hello, Alice!"
    info = instance.info()
    assert info == {"logo": "logo.svg", "name": "Elyx Hello", "api": "https://example.com", "page": 50,
                    "helpers": 42, "value": 42, "env_keys": ["assets", "metainfo", "refmap", "settings", "strings"],
                    "settings_id": pid}
    assert "ElyxPlugins.%s.src.greeting" % pid in sys.modules
    assert "src" not in sys.modules and "src.greeting" not in sys.modules and "helpers" not in sys.modules
    elyx.unload_plugin(pid)
    assert not [m for m in sys.modules if m.startswith("ElyxPlugins.%s" % pid)]


def test_two_plugins_are_isolated(tmp_path, elyx):
    a = _install(elyx, tmp_path, "a.elyx", hello_files("plugin_a"))
    b = _install(elyx, tmp_path, "b.elyx", hello_files("plugin_b", GREETING.replace('"hello"', '"other"')))
    ia, ib = elyx.load_plugin(a, "en"), elyx.load_plugin(b, "en")
    ga = sys.modules["ElyxPlugins.plugin_a.src.greeting"]
    gb = sys.modules["ElyxPlugins.plugin_b.src.greeting"]
    assert ga is not gb and ga.ORIGIN == "hello" and gb.ORIGIN == "other"
    assert ia.info()["settings_id"] == "plugin_a" and ib.info()["settings_id"] == "plugin_b"


def test_get_environment_and_caller(tmp_path, elyx):
    import elyx as elyx_public
    from extera_utils.get_caller import get_plugin_id
    with pytest.raises(RuntimeError):
        elyx_public.get_environment()
    pid = _install(elyx, tmp_path, "h.elyx", hello_files(extra={
        "probe.py": "from extera_utils.get_caller import get_plugin_id\nimport elyx\n"
                    "def who():\n    return get_plugin_id(depth=0), elyx.get_environment()['metainfo']['id']\n"
                    "def dyn():\n    return elyx.import_module('src.greeting').ORIGIN\n"}))
    elyx.load_plugin(pid, "en")
    probe = sys.modules.get("ElyxPlugins.%s.probe" % pid) or __import__("importlib").import_module(
        "ElyxPlugins.%s.probe" % pid)
    assert probe.who() == (pid, pid)
    assert probe.dyn() == "hello"
    assert get_plugin_id() is None


def test_nested_layout_and_namespace_dirs(tmp_path, elyx):
    files = {
        "refmap.yml": "metainfo: plugin/meta.yml\nmain: plugin/src/main.py\nstrings: plugin/locales/strings_en.json\n",
        "plugin/meta.yml": "id: nested_one\nname: Nested\n",
        "plugin/src/main.py": "from base_plugin import BasePlugin\nfrom utils import X\nfrom feat.media import images\n"
                              "from .utils import X as Y\nimport notice\nfrom elyx import strings\n"
                              "class P(BasePlugin):\n    pass\nRESULT = (X, Y, images.Z, notice.content.strip(), strings.t)\n",
        "plugin/src/utils.py": "X = 'x'\n",
        "plugin/src/feat/media/images.py": "Z = 'z'\n",
        "plugin/src/notice.txt": "hi there\n",
        "plugin/locales/strings_en.json": json.dumps({"t": "T"}),
    }
    pid = _install(elyx, tmp_path, "n.eaf", files)
    elyx.load_plugin(pid)
    main = sys.modules["ElyxPlugins.nested_one.plugin.src.main"]
    assert main.RESULT == ("x", "x", "z", "hi there", "T")


def test_missing_assets_env_and_no_class(tmp_path, elyx):
    pid = _install(elyx, tmp_path, "m.elyx", {"metainfo.yml": "id: no_class\nname: N\n",
                                             "main.py": "import elyx\nENV = sorted(elyx.get_environment())\n"})
    from elyxcore._plugin import InvalidPluginError
    with pytest.raises(InvalidPluginError, match="Could not find a class inheriting from BasePlugin"):
        elyx.load_plugin(pid)
    assert not [m for m in sys.modules if m.startswith("ElyxPlugins.no_class.")]


def test_bytecode_modules(tmp_path, elyx):
    code = compile("VALUE = 'from pyc'\n", "/build/machine/compiled.py", "exec")
    good = importlib.util.MAGIC_NUMBER + b"\0" * 12 + marshal.dumps(code)
    bad = b"\x00\x00\r\n" + b"\0" * 12 + marshal.dumps(code)
    pid = _install(elyx, tmp_path, "c.elyx", {
        "metainfo.yml": "id: pyc_one\nname: P\n",
        "main.py": "from base_plugin import BasePlugin\nimport compiled\nclass P(BasePlugin):\n    pass\n",
        "compiled.pyc": good, "broken.pyc": bad})
    elyx.load_plugin(pid)
    mod = sys.modules["ElyxPlugins.pyc_one.compiled"]
    assert mod.VALUE == "from pyc"
    assert mod.__file__.endswith("compiled.pyc")
    import importlib as il
    with pytest.raises(ImportError, match="Incompatible bytecode file"):
        il.import_module("ElyxPlugins.pyc_one.broken")


def _wheel(path, pkg, value):
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("%s/__init__.py" % pkg, "VALUE = %r\n" % value)
        zf.writestr("%s-1.0.dist-info/METADATA" % pkg, "Name: %s\n" % pkg)
    return path.read_bytes()


def test_bundled_wheels(tmp_path, elyx):
    whl = _wheel(tmp_path / "w.whl", "elyxwheelpkg", "one")
    files = {"refmap.yml": "wheels: wheels\n", "metainfo.yml": "id: wheel_one\nname: W\n",
             "main.py": "from base_plugin import BasePlugin\nimport elyxwheelpkg\nclass P(BasePlugin):\n"
                        "    def v(self):\n        return elyxwheelpkg.VALUE\n",
             "wheels/elyxwheelpkg-1.0-py3-none-any.whl": whl}
    pid = _install(elyx, tmp_path, "w.elyx", files)
    info = json.loads(elyx.read_installed(pid))["plugin"]
    assert info["wheels"] == ["elyxwheelpkg-1.0-py3-none-any.whl"]
    inst = elyx.load_plugin(pid)
    assert inst.v() == "one"
    libs = tmp_path / "plugins" / "elyx_local_libs" / pid
    assert (libs / "elyxwheelpkg-1.0-py3-none-any" / "elyxwheelpkg" / "__init__.py").is_file()
    elyx.unload_plugin(pid)
    assert "elyxwheelpkg" not in sys.modules
    assert str(libs / "elyxwheelpkg-1.0-py3-none-any") not in sys.path
    # update with a new wheel: the stale directory goes away
    files["wheels/elyxwheelpkg-1.1-py3-none-any.whl"] = _wheel(tmp_path / "w2.whl", "elyxwheelpkg", "two")
    del files["wheels/elyxwheelpkg-1.0-py3-none-any.whl"]
    _install(elyx, tmp_path, "w2.elyx", files)
    assert elyx.load_plugin(pid).v() == "two"
    assert not (libs / "elyxwheelpkg-1.0-py3-none-any").exists()


def test_wheel_traversal(tmp_path, elyx):
    whl = io.BytesIO()
    with zipfile.ZipFile(whl, "w") as zf:
        zf.writestr("../../evil.py", "x")
    pid = _install(elyx, tmp_path, "t.elyx", {"refmap.yml": "wheels: wheels\n",
                                             "metainfo.yml": "id: wheel_evil\nname: W\n",
                                             "main.py": "", "wheels/evil-1.0-py3-none-any.whl": whl.getvalue()})
    from elyxcore._plugin import SecurityError
    with pytest.raises(SecurityError, match="Path traversal in wheel"):
        elyx.load_plugin(pid)


def test_update_rollback_and_delete(tmp_path, elyx):
    pid = _install(elyx, tmp_path, "v1.elyx", hello_files("upd_one"))
    path = write_zip(tmp_path / "v2.elyx", hello_files("upd_one", extra={"helpers.py": "VALUE = 43\n"}))
    res = json.loads(elyx.install_archive(path))
    assert res["ok"] and res["had_previous"]
    root = tmp_path / "plugins" / "ElyxPlugins" / pid
    assert (root / "helpers.py").read_text() == "VALUE = 43\n"
    assert elyx.rollback_install(pid) is True
    assert (root / "helpers.py").read_text() == "VALUE = 42\n"
    elyx.delete_plugin(pid)
    assert not root.exists() and json.loads(elyx.installed_ids()) == []
    assert elyx.archive_path(pid) is None


def test_encrypted_install(tmp_path, elyx):
    pid = _install(elyx, tmp_path, "s.elyx", hello_files("enc_one"), password="pw")
    assert elyx.load_plugin(pid, "en").greet("Bob") == "Hello, Bob!"


def test_make_archive_roundtrip(tmp_path, elyx):
    pid = _install(elyx, tmp_path, "r.elyx", hello_files("round_one"))
    out = elyx.make_archive(pid, str(tmp_path / "out.elyx"))
    res = json.loads(elyx.inspect_archive(out))
    assert res["ok"] and res["plugin"]["id"] == "round_one"


def test_dev_compare_and_apply(tmp_path, elyx):
    pid = _install(elyx, tmp_path, "d.elyx", hello_files("dev_one"))
    import hashlib
    hashes = {"helpers.py": hashlib.md5(b"VALUE = 1\n").hexdigest(), "main.py": hashlib.md5(MAIN.encode()).hexdigest(),
              "new.py": "0"}
    res = elyx.compare_folder(pid, hashes)
    assert res["modified"] == ["helpers.py"] and res["missing"] == ["new.py"] and "config.json" in res["extra"]
    errors = elyx.apply_changes(pid, [
        {"#": "modified", "path": "helpers.py", "is_directory": False, "content": base64.b64encode(b"VALUE = 7\n").decode()},
        {"#": "created", "path": "pkg", "is_directory": True},
        {"#": "moved", "path": "config.json", "dest_path": "pkg/config.json"},
        {"#": "deleted", "path": "assets/icons", "is_directory": True},
        {"#": "modified", "path": "../escape.py", "content": ""},
    ])
    root = tmp_path / "plugins" / "ElyxPlugins" / pid
    assert (root / "helpers.py").read_text() == "VALUE = 7\n"
    assert (root / "pkg" / "config.json").is_file() and not (root / "config.json").exists()
    assert not (root / "assets" / "icons").exists()
    assert len(errors) == 1 and "escape.py" in errors[0]
    assert not (tmp_path / "plugins" / "ElyxPlugins" / "escape.py").exists()


def test_dev_server_payload_codec():
    import dev_server
    payload = {"plugin_id": "x", "changes": []}
    encoded = dev_server._encode_payload(payload)
    assert json.loads(zlib.decompress(base64.b64decode(encoded))) == payload
    assert dev_server._decode_payload({"@": "elyx_changes", "#": 1, "data": encoded}) == payload
    assert dev_server._decode_payload({"@": "elyx_changes", "#": 1, "plugin_id": "y"}) == {"plugin_id": "y"}
    assert hasattr(dev_server.DevServer, "_handle_elyx_ping")
    assert hasattr(dev_server.DevServer, "_handle_elyx_changes")


def test_reserved_roots_are_not_local(tmp_path, elyx):
    pid = _install(elyx, tmp_path, "res.elyx", {
        "metainfo.yml": "id: reserved_one\nname: R\n",
        "main.py": "from base_plugin import BasePlugin\nimport ui\nimport json\nclass P(BasePlugin):\n"
                   "    pass\nUI_FILE = getattr(ui, '__file__', '')\nJSON = json.LOCAL\n",
        "ui.py": "LOCAL = True\n", "json.py": "LOCAL = 'local json shadows stdlib'\n"})
    elyx.load_plugin(pid)
    main = sys.modules["ElyxPlugins.reserved_one.main"]
    assert "ElyxPlugins" not in main.UI_FILE  # SDK module, not the local ui.py
    assert main.JSON == "local json shadows stdlib"
    import json as real_json
    assert not hasattr(real_json, "LOCAL")
