"""Reader for Android's compiled binary XML, as found in an APK's res/ folder."""
import struct

TYPE_NULL, TYPE_REF, TYPE_ATTR, TYPE_STRING, TYPE_FLOAT, TYPE_DIM, TYPE_FRAC = 0, 1, 2, 3, 4, 5, 6
TYPE_INT_DEC, TYPE_INT_HEX, TYPE_BOOL = 16, 17, 18


class Node:
    __slots__ = ("tag", "attrs", "children")

    def __init__(self, tag, attrs):
        self.tag = tag
        self.attrs = attrs
        self.children = []

    def walk(self):
        yield self
        for child in self.children:
            yield from child.walk()


class Ref(int):
    """A @0x7f... reference, kept apart from plain integers."""


def _strings(data, off):
    _type, header, _size = struct.unpack_from("<HHI", data, off)
    count, _styles, flags, start, _styles_start = struct.unpack_from("<IIIII", data, off + 8)
    utf8 = flags & 0x100
    out = []
    for i in range(count):
        p = off + start + struct.unpack_from("<I", data, off + header + i * 4)[0]
        if utf8:
            p += 2 if data[p] & 0x80 else 1
            n = data[p]
            p += 1
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p]
                p += 1
            # aapt writes a character past the BMP as two surrogates, each UTF-8 encoded.
            raw = data[p:p + n].decode("utf-8", "surrogatepass")
            out.append(raw.encode("utf-16-le", "surrogatepass").decode("utf-16-le", "replace"))
        else:
            n = struct.unpack_from("<H", data, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                p += 2
            out.append(data[p:p + n * 2].decode("utf-16-le", "replace"))
    return out


def parse(data):
    """The root Node of one document, or None when `data` is not binary XML."""
    if len(data) < 8 or data[:2] != b"\x03\x00":
        return None
    total = struct.unpack_from("<I", data, 4)[0]
    off = 8
    pool = []
    resmap = []
    root = None
    stack = []
    while off + 8 <= min(total, len(data)):
        ctype, header, size = struct.unpack_from("<HHI", data, off)
        if size == 0:
            break
        if ctype == 0x0001:
            pool = _strings(data, off)
        elif ctype == 0x0180:
            resmap = list(struct.unpack_from("<%dI" % ((size - header) // 4), data, off + header))
        elif ctype == 0x0102:
            p = off + header
            _ns, name, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", data, p)
            attrs = {}
            a = p + attr_start
            for i in range(attr_count):
                _ans, aname, raw, _vs, _z, vtype, vdata = struct.unpack_from("<IIIHBBI", data, a + i * attr_size)
                key = pool[aname] if aname < len(pool) else str(aname)
                if vtype == TYPE_STRING or raw != 0xFFFFFFFF:
                    value = pool[raw] if raw != 0xFFFFFFFF else pool[vdata]
                elif vtype == TYPE_REF:
                    value = Ref(vdata)
                elif vtype == TYPE_BOOL:
                    value = vdata != 0
                elif vtype in (TYPE_INT_DEC, TYPE_INT_HEX):
                    value = vdata
                elif vtype == TYPE_FLOAT:
                    value = struct.unpack("<f", struct.pack("<I", vdata))[0]
                elif vtype == TYPE_DIM:
                    value = ("dim", vdata)
                elif vtype == TYPE_FRAC:
                    value = ("frac", vdata)
                else:
                    value = vdata
                attrs[key] = value
            node = Node(pool[name], attrs)
            if stack:
                stack[-1].children.append(node)
            elif root is None:
                root = node
            stack.append(node)
        elif ctype == 0x0103:
            if stack:
                stack.pop()
        off += size
    return root
