"""The file a preview draws, and helpers for the containers most formats
come in: zip (docx, xlsx, pptx, odt, epub, cbz, jar), XML and deflate.

    from wm import plugin, ui
    from wm.preview import xml

    @plugin.preview("docx")
    def show(file):
        body = xml(file.zip_read("word/document.xml")).find("w:body")
        for p in body.findall("w:p"):
            ui.label(p.all_text())

A preview never sees a path, only this one file: its name and type, and its
first bytes (up to the manifest's `maxBytes`). `read()` reaches past them.
"""

import _wm
from wm import _core


class PreviewFile:
    """The file being previewed.

    name: "Report.docx"; extension: "docx" (lower case, no dot); mime;
    size: bytes on disk; modified: seconds since 1970, or None;
    data: the first bytes, all of them unless `truncated`."""

    def __init__(self, meta, data):
        self.name = str(meta.get("name", ""))
        ext = meta.get("extension")
        if ext is None:
            dot = self.name.rfind(".")
            ext = self.name[dot + 1:] if dot > 0 else ""
        self.extension = str(ext).lower()
        self.mime = str(meta.get("mime") or "application/octet-stream")
        self.size = int(meta.get("size", len(data)))
        self.modified = meta.get("modified")
        self.data = data
        self.truncated = bool(meta.get("truncated", False))
        self._text = None

    def text(self, encoding=None):
        """The bytes as text: a byte-order mark first, then [encoding]
        ("utf-8", "utf-16", "latin-1", "cp1252"), else UTF-8, UTF-16 when it
        looks like it, or Windows-1252."""
        if encoding is not None:
            return _wm.decode_text(self.data, encoding)
        if self._text is None:
            self._text = _wm.decode_text(self.data, None)
        return self._text

    def lines(self, limit=None):
        """The text's lines, the first [limit] of them when given. Windows
        and old Mac line ends count too: splitlines() keeps "\\r\\n" whole."""
        all_lines = self.text().replace("\r\n", "\n").replace("\r", "\n").split("\n")
        if all_lines and all_lines[-1] == "":
            all_lines.pop()
        return all_lines if limit is None else all_lines[:limit]

    def json(self):
        """The text read as JSON."""
        import json
        return json.loads(self.text())

    def read(self, offset, length):
        """[length] bytes from [offset]. Past the bytes in `data` this asks the
        keyboard for them, a megabyte at most per call."""
        if offset < 0 or length < 0:
            raise ValueError("read() needs an offset and a length of 0 or more")
        end = offset + length
        if end <= len(self.data) or not self.truncated:
            return self.data[offset:end]
        reply = _core.call("preview.read", {"offset": offset, "length": min(length, 1048576)})
        if not reply:
            return b""
        import base64
        return base64.b64decode(reply)

    def _whole(self, what):
        if self.truncated:
            raise ValueError(f"{what} needs the whole file, and {self.name} is bigger than the manifest's maxBytes")
        return self.data

    def zip(self):
        """The entries of a zip: [{"name", "size", "compressed", "folder",
        "encrypted", "modified"}], in the zip's own order."""
        return zip_list(self._whole("zip()"))

    def zip_names(self):
        """The names of a zip's entries, in order."""
        return [e["name"] for e in self.zip()]

    def zip_read(self, name, max=4194304):
        """The bytes of one entry, inflated, up to [max]. Raises ValueError
        when there is no such entry or it grows past [max]."""
        return zip_read(self._whole("zip_read()"), name, max)

    def zip_text(self, name, max=4194304):
        """One zip entry as text, decoded as text() decodes."""
        return _wm.decode_text(self.zip_read(name, max), None)

    def xml(self, encoding=None):
        """The file parsed as XML: an Element."""
        return xml(self.text(encoding))

    def __repr__(self):
        return f"PreviewFile({self.name!r}, {self.size} bytes)"


def zip_list(data):
    """The entries of the zip in [data]: [{"name", "size", "compressed",
    "folder", "encrypted", "modified"}]."""
    return _wm.zip_list(data)


def zip_read(data, name, max=4194304):
    """The bytes of entry [name] in the zip [data], inflated, up to [max]."""
    return _wm.zip_read(data, name, max)


def inflate(data, raw=False, max=16777216):
    """gzip, zlib or (with raw=True) bare deflate, up to [max] bytes out."""
    return _wm.inflate(data, raw, max)


class Element:
    """One XML element. tag: as written ("w:p"); name: without its prefix
    ("p"); attrs: a dict; nodes: children in order, text as strings among
    the elements; children: the elements alone; text: the text directly
    inside it."""

    def __init__(self, d):
        self.tag = d["tag"]
        colon = self.tag.find(":")
        self.name = self.tag[colon + 1:] if colon >= 0 else self.tag
        self.attrs = d["attrs"]
        self.nodes = [Element(n) if isinstance(n, dict) else n for n in d["nodes"]]

    @property
    def children(self):
        return [n for n in self.nodes if isinstance(n, Element)]

    @property
    def text(self):
        return "".join(n for n in self.nodes if isinstance(n, str))

    def get(self, attr, default=None):
        """An attribute by its full name ("r:id") or without its prefix ("id")."""
        if attr in self.attrs:
            return self.attrs[attr]
        for k, v in self.attrs.items():
            colon = k.find(":")
            if colon >= 0 and k[colon + 1:] == attr:
                return v
        return default

    def _is(self, tag):
        return tag is None or self.tag == tag or self.name == tag

    def find(self, tag):
        """The first child named [tag] ("w:p" or "p"), or None."""
        for c in self.children:
            if c._is(tag):
                return c
        return None

    def findall(self, tag):
        """Every child named [tag], in order."""
        return [c for c in self.children if c._is(tag)]

    def iter(self, tag=None):
        """This element and every one inside it named [tag] (all of them
        with None), in document order."""
        out = []
        stack = [self]
        while stack:
            e = stack.pop()
            if e._is(tag):
                out.append(e)
            kids = e.children
            i = len(kids) - 1
            while i >= 0:
                stack.append(kids[i])
                i = i - 1
        return out

    def first(self, tag):
        """The first element anywhere inside named [tag], or None."""
        found = self.iter(tag)
        for e in found:
            if e is not self:
                return e
        return None

    def all_text(self, separator=""):
        """All the text inside, in order."""
        parts = []
        stack = [self]
        while stack:
            n = stack.pop()
            if isinstance(n, str):
                parts.append(n)
                continue
            i = len(n.nodes) - 1
            while i >= 0:
                stack.append(n.nodes[i])
                i = i - 1
        return separator.join(parts)

    def __repr__(self):
        return f"<{self.tag} {len(self.nodes)} nodes>"


def xml(source, max_nodes=20000):
    """Parse XML or XHTML (text or bytes) into an Element. It forgives what
    a browser would: unclosed tags close at their parent's end, unknown
    entities stay as written."""
    return Element(_wm.xml(source, max_nodes))
