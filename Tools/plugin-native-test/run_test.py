"""Host-side check of python_sdk/_sg_native.py (run from the directory with the built libraries)."""
import ctypes
import importlib
import os
import shutil
import sys
import tempfile

sys.path.insert(0, sys.argv[1])
import _sg_native  # noqa: E402

here = os.getcwd()
plugins = os.path.join(here, "plugins_dir")
shutil.rmtree(plugins, ignore_errors=True)
os.makedirs(plugins)
shutil.copy("sghello.cpython-311-x86_64-linux-android.so", plugins)
sys.path.append(plugins)
importlib.invalidate_caches()
try:
    import sghello  # noqa: F401
    raise SystemExit("FAIL: ABI-tagged module imported before install()")
except ImportError:
    print("ok: without the hook the ABI-tagged module is not importable")

os.environ["TMPDIR"] = os.path.join(here, "tmp_not_created_yet")
tmp = _sg_native.install(plugins, os.path.join(here, "cache"))
assert os.path.isdir(tmp), tmp
import sghello  # noqa: E402
assert sghello.add(2, 3) == 5 and sghello.greet("x") == "hello x from cython"
print("ok: Cython module imported from the plugins dir:", os.path.basename(sghello.__file__))
assert os.path.join(plugins, "libs") in sys.path
print("ok: plugins/libs on sys.path")

dst = os.path.join(tempfile.gettempdir(), "libsgadd_copy.so")
shutil.copy("libsgadd.so", dst)
assert ctypes.CDLL(dst).sg_add(1, 2) == 1003
print("ok: ctypes.CDLL from tempfile.gettempdir()")

_sg_native.install(plugins)
assert sum(1 for h in sys.path_hooks if "sg_native" in getattr(h, "__qualname__", "")) == 1
print("ok: install() is idempotent")
print("arm64 suffix:", _sg_native.extra_extension_suffixes("aarch64"))
