#!/usr/bin/env python3
"""Rebuild the patched Aliuhook in the in-repo Maven repo (TMessagesProj/libs/maven, version 1.1.4-sg1).

exteraGram plugins call XposedBridge.log(Throwable) as public static, but in
com.aliucord:Aliuhook:1.1.4 it is private static (IllegalAccessError in plugins).
This only flips that one method's access flag to public inside classes.jar
(behaviour unchanged).

Usage: scripts/patch-aliuhook.py [Aliuhook-1.1.4.aar]
Default input: the Gradle cache copy of the Maven artifact (the .pom next to it
is reused, only the version is changed so transitive lsplant/dobby stay).
"""
import glob, io, os, struct, sys, zipfile

CLS = "de/robv/android/xposed/XposedBridge.class"
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = sys.argv[1] if len(sys.argv) > 1 else glob.glob(os.path.expanduser(
    "~/.gradle/caches/modules-2/files-2.1/com.aliucord/Aliuhook/1.1.4/*/Aliuhook-1.1.4.aar"))[0]
outdir = os.path.join(root, "TMessagesProj/libs/maven/com/aliucord/Aliuhook/1.1.4-sg1")
dst = os.path.join(outdir, "Aliuhook-1.1.4-sg1.aar")
os.makedirs(outdir, exist_ok=True)


def patch_class(raw):
    b = bytearray(raw)
    n = struct.unpack(">H", b[8:10])[0]
    pos, utf, i = 10, {}, 1
    while i < n:
        t = b[pos]
        if t == 1:
            l = struct.unpack(">H", b[pos + 1:pos + 3])[0]
            utf[i] = bytes(b[pos + 3:pos + 3 + l]).decode("utf8", "replace")
            pos += 3 + l
        elif t in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 5
        elif t in (5, 6):
            pos += 9
            i += 1
        elif t in (7, 8, 16, 19, 20):
            pos += 3
        elif t == 15:
            pos += 4
        else:
            raise SystemExit("bad constant pool tag %d" % t)
        i += 1
    pos += 6  # access, this, super
    pos += 2 + 2 * struct.unpack(">H", b[pos:pos + 2])[0]  # interfaces

    def members(p, visit):
        cnt = struct.unpack(">H", b[p:p + 2])[0]
        p += 2
        for _ in range(cnt):
            visit(p)
            # attributes: count at q, each = name(2) len(4) data
            c = struct.unpack(">H", b[p + 6:p + 8])[0]
            q = p + 8
            for _ in range(c):
                q += 6 + struct.unpack(">I", b[q + 2:q + 6])[0]
            p = q
        return p

    pos = members(pos, lambda p: None)  # fields
    done = []

    def visit(p):
        acc, ni, di = struct.unpack(">HHH", b[p:p + 6])
        if utf[ni] == "log" and utf[di] == "(Ljava/lang/Throwable;)V":
            assert acc & 0x0008, "log(Throwable) is not static"
            b[p:p + 2] = struct.pack(">H", (acc & ~0x0002) | 0x0001)
            done.append(p)

    members(pos, visit)
    assert len(done) == 1, "log(Throwable) not found"
    return bytes(b)


def patch_jar(data):
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as zi, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zo:
        for it in zi.infolist():
            d = zi.read(it.filename)
            if it.filename == CLS:
                d = patch_class(d)
            zo.writestr(zipfile.ZipInfo(it.filename, (1981, 1, 1, 0, 0, 0)), d, zipfile.ZIP_DEFLATED)
    return out.getvalue()


with zipfile.ZipFile(src) as zi, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zo:
    for it in zi.infolist():
        d = zi.read(it.filename)
        if it.filename == "classes.jar":
            d = patch_jar(d)
        zo.writestr(zipfile.ZipInfo(it.filename, (1981, 1, 1, 0, 0, 0)), d, zipfile.ZIP_DEFLATED)
print("written", dst)

pom = glob.glob(os.path.join(os.path.dirname(os.path.dirname(src)), "*", "Aliuhook-1.1.4.pom"))[0]
with open(pom) as f, open(os.path.join(outdir, "Aliuhook-1.1.4-sg1.pom"), "w") as g:
    g.write(f.read().replace("<version>1.1.4</version>", "<version>1.1.4-sg1</version>", 1)
                .replace("<!-- do_not_remove: published-with-gradle-metadata -->", ""))  # no .module file here
