#!/usr/bin/env python3
"""Checks a built APK's classes.dex before it ever reaches a phone.

1. No invokedynamic call sites: Java 8 lambdas that need java.lang.invoke.LambdaMetafactory,
   which doesn't exist on Android. Lumen 1.0.0 crashed at start because of exactly this.
2. Every Android or Java class, method and field the dex uses exists in the given android.jar
   (searching superclasses and interfaces), so nothing fails with NoClassDefFoundError or
   NoSuchMethodError.

Usage: check-dex.py app.apk android.jar
"""
import struct
import sys
import zipfile


def read_dex(apk):
    d = zipfile.ZipFile(apk).read("classes.dex")

    def u32(o):
        return struct.unpack_from("<I", d, o)[0]

    def uleb(p):
        r = s = 0
        while True:
            b = d[p]
            p += 1
            r |= (b & 0x7F) << s
            s += 7
            if not b & 0x80:
                return r, p

    str_o, type_n, type_o = u32(60), u32(64), u32(68)
    proto_o, field_n, field_o = u32(76), u32(80), u32(84)
    meth_n, meth_o, cls_n, cls_o, map_o = u32(88), u32(92), u32(96), u32(100), u32(52)

    def string(i):
        _, p = uleb(u32(str_o + 4 * i))
        return d[p:d.index(b"\0", p)].decode("utf-8", "replace")

    types = [string(u32(type_o + 4 * i)) for i in range(type_n)]

    # Map list: item type 0x0007 = call site ids, 0x0008 = method handles.
    counts = {}
    for k in range(u32(map_o)):
        t, _, size, _ = struct.unpack_from("<HHII", d, map_o + 4 + 12 * k)
        counts[t] = size

    def proto(i):
        o = proto_o + 12 * i
        params = []
        po = u32(o + 8)
        if po:
            params = [types[struct.unpack_from("<H", d, po + 4 + 2 * k)[0]] for k in range(u32(po))]
        return "(" + "".join(params) + ")" + types[u32(o + 4)]

    defined = {types[u32(cls_o + 32 * i)] for i in range(cls_n)}
    refs = [("T", t.lstrip("[")[1:-1], "", "") for t in types
            if t.lstrip("[").startswith("L") and t.lstrip("[") not in defined]
    for i in range(meth_n):
        c, p, n = struct.unpack_from("<HHI", d, meth_o + 8 * i)
        if types[c] not in defined:
            refs.append(("M", types[c][1:-1], string(n), proto(p)))
    for i in range(field_n):
        c, t, n = struct.unpack_from("<HHI", d, field_o + 8 * i)
        if types[c] not in defined:
            refs.append(("F", types[c][1:-1], string(n), types[t]))
    return counts, refs


class Sdk:
    def __init__(self, jar):
        self.jar = zipfile.ZipFile(jar)
        self.cache = {}

    def load(self, name):
        if name in self.cache:
            return self.cache[name]
        try:
            b = self.jar.read(name + ".class")
        except KeyError:
            self.cache[name] = None
            return None
        p = 10
        n = struct.unpack_from(">H", b, 8)[0]
        cp = [None] * n
        i = 1
        while i < n:
            tag = b[p]
            p += 1
            if tag == 1:
                ln = struct.unpack_from(">H", b, p)[0]
                cp[i] = b[p + 2:p + 2 + ln].decode("utf-8", "replace")
                p += 2 + ln
            elif tag in (7, 8, 16, 19, 20):
                cp[i] = struct.unpack_from(">H", b, p)[0]
                p += 2
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                p += 4
            elif tag in (5, 6):
                p += 8
                i += 1
            elif tag == 15:
                p += 3
            i += 1

        def cname(idx):
            return cp[cp[idx]] if idx else None

        sup = cname(struct.unpack_from(">H", b, p + 4)[0])
        p += 6
        ic = struct.unpack_from(">H", b, p)[0]
        ifs = [cname(struct.unpack_from(">H", b, p + 2 + 2 * k)[0]) for k in range(ic)]
        p += 2 + 2 * ic
        members = set()
        for kind in "FM":
            count = struct.unpack_from(">H", b, p)[0]
            p += 2
            for _ in range(count):
                nm = cp[struct.unpack_from(">H", b, p + 2)[0]]
                ds = cp[struct.unpack_from(">H", b, p + 4)[0]]
                ac = struct.unpack_from(">H", b, p + 6)[0]
                p += 8
                members.add((kind, nm, ds if kind == "M" else ""))
                for _ in range(ac):
                    p += 6 + struct.unpack_from(">I", b, p + 2)[0]
        self.cache[name] = (sup, ifs, members)
        return self.cache[name]

    def has(self, cls, kind, name, desc, seen=None):
        seen = seen if seen is not None else set()
        if cls is None or cls in seen:
            return False
        seen.add(cls)
        info = self.load(cls)
        if info is None:
            return False
        sup, ifs, members = info
        if (kind, name, desc if kind == "M" else "") in members:
            return True
        return self.has(sup, kind, name, desc, seen) or any(self.has(i, kind, name, desc, seen) for i in ifs)


def main():
    apk, jar = sys.argv[1], sys.argv[2]
    counts, refs = read_dex(apk)
    problems = []
    if counts.get(0x0007) or counts.get(0x0008):
        problems.append(f"{counts.get(0x0007, 0)} invokedynamic call sites (Java 8 lambdas Android can't run)")
    sdk = Sdk(jar)
    for kind, cls, name, desc in refs:
        if kind == "T":
            # Annotation markers: built into the runtime, or compile-time only. Never loaded.
            if cls.startswith(("dalvik/annotation/", "org/jetbrains/annotations/")):
                continue
            if sdk.load(cls) is None:
                problems.append(f"missing class on Android: {cls}")
            continue
        if cls.startswith("[") or sdk.has(cls, kind, name, desc):
            continue
        problems.append(f"missing on Android: {cls}.{name} {desc}")
    if problems:
        print("dex check FAILED:")
        for p in problems:
            print("  " + p)
        sys.exit(1)
    print(f"dex check: ok ({len(refs)} external classes and calls verified, no invokedynamic)")


if __name__ == "__main__":
    main()
