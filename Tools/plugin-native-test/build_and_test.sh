#!/bin/sh
# Self-made test for native (compiled) plugin code support in the exteraStatic plugin runtime
# (TMessagesProj/src/main/python_sdk/_sg_native.py). Builds a tiny Cython module and a ctypes library:
#   * host x86_64 build named *.cpython-311-x86_64-linux-android.so; run_test.py checks the path hook,
#     plugins/libs on sys.path, TMPDIR and ctypes loading on the host interpreter;
#   * optional arm64-v8a build against the Chaquopy target Python 3.11 (libpython3.11.so) with the NDK:
#     out/arm64/sghello.cpython-311-aarch64-linux-android.so, to drop next to a plugin on a device.
# Usage: PY=/path/python3.11 [NDK=/path/ndk CHAQUOPY_TARGET_ZIP=/path/target-3.11.x-arm64-v8a.zip] ./build_and_test.sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
PY=${PY:-python3.11}
OUT=$HERE/out
rm -rf "$OUT"; mkdir -p "$OUT/host" "$OUT/arm64"
"$PY" -m venv "$OUT/venv" && "$OUT/venv/bin/pip" -q install cython
"$OUT/venv/bin/cython" -3 "$HERE/sghello.pyx" -o "$OUT/sghello.c"
INC=$("$PY" -c "import sysconfig; print(sysconfig.get_paths()['include'])")
cc -shared -fPIC -O2 -I"$INC" "$OUT/sghello.c" -o "$OUT/host/sghello.cpython-311-x86_64-linux-android.so"
cc -shared -fPIC "$HERE/libsgadd.c" -o "$OUT/host/libsgadd.so"
(cd "$OUT/host" && "$PY" "$HERE/run_test.py" "$ROOT/TMessagesProj/src/main/python_sdk")
if [ -n "$NDK" ] && [ -n "$CHAQUOPY_TARGET_ZIP" ]; then
    mkdir -p "$OUT/target" && (cd "$OUT/target" && unzip -q -o "$CHAQUOPY_TARGET_ZIP")
    CLANG=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang
    "$CLANG" -shared -fPIC -O2 -I"$OUT/target/include/python3.11" "$OUT/sghello.c" \
        -L"$OUT/target/jniLibs/arm64-v8a" -lpython3.11 -o "$OUT/arm64/sghello.cpython-311-aarch64-linux-android.so"
    "$CLANG" -shared -fPIC "$HERE/libsgadd.c" -o "$OUT/arm64/libsgadd.so"
    echo "arm64 build: $OUT/arm64"
fi
