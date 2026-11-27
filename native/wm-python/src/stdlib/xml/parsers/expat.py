"""xml.parsers.expat, written in Python.

The parser behind xml.etree.ElementTree, xml.sax and the packages built on
them, with the handlers and attributes of CPython's pyexpat: elements and
attributes, text, CDATA, comments, processing instructions, the XML
declaration, a doctype with general entities declared in its internal
subset, character references, and namespaces when a separator is given.
It checks that the document is well-formed and raises ExpatError with
expat's codes and messages when it is not. It does not validate against a
DTD or load external entities.

The document is parsed when the last piece arrives (Parse(data, True), or
ParseFile), so handlers are called then, all in order.
"""

import re

__all__ = ["ParserCreate", "ErrorString", "ExpatError", "error", "XMLParserType", "errors", "model", "version_info", "EXPAT_VERSION"]

version_info = (2, 6, 0)
EXPAT_VERSION = "expat_2.6.0"
native_encoding = "UTF-8"
features = [("sizeof(XML_Char)", 1), ("sizeof(XML_LChar)", 1), ("XML_NS", 0)]

XML_PARAM_ENTITY_PARSING_NEVER = 0
XML_PARAM_ENTITY_PARSING_UNLESS_STANDALONE = 1
XML_PARAM_ENTITY_PARSING_ALWAYS = 2


class ExpatError(Exception):
    pass


error = ExpatError


class _Namespace:
    pass


# Expat's error codes and messages, as pyexpat.errors has them.
_MESSAGES = [
    None, "out of memory", "syntax error", "no element found", "not well-formed (invalid token)", "unclosed token",
    "partial character", "mismatched tag", "duplicate attribute", "junk after document element",
    "illegal parameter entity reference", "undefined entity", "recursive entity reference", "asynchronous entity",
    "reference to invalid character number", "reference to binary entity", "reference to external entity in attribute",
    "XML or text declaration not at start of entity", "unknown encoding", "encoding specified in XML declaration is incorrect",
    "unclosed CDATA section", "error in processing external entity reference", "document is not standalone",
    "unexpected parser state - please send a bug report", "entity declared in parameter entity",
    "requested feature requires XML_DTD support in Expat", "cannot change setting once parsing has begun",
    "unbound prefix", "must not undeclare prefix", "incomplete markup in parameter entity",
    "XML declaration not well-formed", "text declaration not well-formed", "illegal character(s) in public id",
    "parser suspended", "parser not suspended", "parsing aborted", "parsing finished", "cannot suspend in external parameter entity",
    "reserved prefix (xml) must not be undeclared or bound to another namespace name",
    "reserved prefix (xmlns) must not be declared or undeclared",
    "prefix must not be bound to one of the reserved namespace names",
]

_NAMES = [
    None, "XML_ERROR_NO_MEMORY", "XML_ERROR_SYNTAX", "XML_ERROR_NO_ELEMENTS", "XML_ERROR_INVALID_TOKEN",
    "XML_ERROR_UNCLOSED_TOKEN", "XML_ERROR_PARTIAL_CHAR", "XML_ERROR_TAG_MISMATCH", "XML_ERROR_DUPLICATE_ATTRIBUTE",
    "XML_ERROR_JUNK_AFTER_DOC_ELEMENT", "XML_ERROR_PARAM_ENTITY_REF", "XML_ERROR_UNDEFINED_ENTITY",
    "XML_ERROR_RECURSIVE_ENTITY_REF", "XML_ERROR_ASYNC_ENTITY", "XML_ERROR_BAD_CHAR_REF", "XML_ERROR_BINARY_ENTITY_REF",
    "XML_ERROR_ATTRIBUTE_EXTERNAL_ENTITY_REF", "XML_ERROR_MISPLACED_XML_PI", "XML_ERROR_UNKNOWN_ENCODING",
    "XML_ERROR_INCORRECT_ENCODING", "XML_ERROR_UNCLOSED_CDATA_SECTION", "XML_ERROR_EXTERNAL_ENTITY_HANDLING",
    "XML_ERROR_NOT_STANDALONE", "XML_ERROR_UNEXPECTED_STATE", "XML_ERROR_ENTITY_DECLARED_IN_PE",
    "XML_ERROR_FEATURE_REQUIRES_XML_DTD", "XML_ERROR_CANT_CHANGE_FEATURE_ONCE_PARSING", "XML_ERROR_UNBOUND_PREFIX",
    "XML_ERROR_UNDECLARING_PREFIX", "XML_ERROR_INCOMPLETE_PE", "XML_ERROR_XML_DECL", "XML_ERROR_TEXT_DECL",
    "XML_ERROR_PUBLICID", "XML_ERROR_SUSPENDED", "XML_ERROR_NOT_SUSPENDED", "XML_ERROR_ABORTED", "XML_ERROR_FINISHED",
    "XML_ERROR_SUSPEND_PE", "XML_ERROR_RESERVED_PREFIX_XML", "XML_ERROR_RESERVED_PREFIX_XMLNS",
    "XML_ERROR_RESERVED_NAMESPACE_URI",
]

errors = _Namespace()
errors.__name__ = "pyexpat.errors"
errors.codes = {}
errors.messages = {}
for _code in range(1, len(_MESSAGES)):
    setattr(errors, _NAMES[_code], _MESSAGES[_code])
    errors.codes[_MESSAGES[_code]] = _code
    errors.messages[_code] = _MESSAGES[_code]

model = _Namespace()
model.__name__ = "pyexpat.model"
model.XML_CTYPE_EMPTY = 1
model.XML_CTYPE_ANY = 2
model.XML_CTYPE_MIXED = 3
model.XML_CTYPE_NAME = 4
model.XML_CTYPE_CHOICE = 5
model.XML_CTYPE_SEQ = 6
model.XML_CQUANT_NONE = 0
model.XML_CQUANT_OPT = 1
model.XML_CQUANT_REP = 2
model.XML_CQUANT_PLUS = 3


def ErrorString(code):
    if 0 < code < len(_MESSAGES):
        return _MESSAGES[code]
    return None


_NAME = re.compile(r"[^\s/>=<\"'&;!?]+")
_ATTR = re.compile(r"\s*([^\s/>=<\"']+)\s*=\s*(\"[^\"<]*\"|'[^'<]*')")
_SPACE = re.compile(r"\s*")
_ENTITY_DECL = re.compile(r"<!ENTITY\s+([^\s%]+)\s+(\"[^\"]*\"|'[^']*')\s*>")
_ENCODING = re.compile(r"""^<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")
_PREDEFINED = {"amp": "&", "lt": "<", "gt": ">", "quot": '"', "apos": "'"}
_XML_NS = "http://www.w3.org/XML/1998/namespace"

_HANDLERS = (
    "StartElementHandler", "EndElementHandler", "ProcessingInstructionHandler", "CharacterDataHandler",
    "UnparsedEntityDeclHandler", "NotationDeclHandler", "StartNamespaceDeclHandler", "EndNamespaceDeclHandler",
    "CommentHandler", "StartCdataSectionHandler", "EndCdataSectionHandler", "DefaultHandler", "DefaultHandlerExpand",
    "NotStandaloneHandler", "ExternalEntityRefHandler", "StartDoctypeDeclHandler", "EndDoctypeDeclHandler",
    "EntityDeclHandler", "XmlDeclHandler", "ElementDeclHandler", "AttlistDeclHandler", "SkippedEntityHandler",
)


class _Stop(Exception):
    def __init__(self, code, pos):
        self.code = code
        self.pos = pos


class XMLParserType:
    def __init__(self, encoding=None, namespace_separator=None, intern=None):
        for h in _HANDLERS:
            object.__setattr__(self, h, None)
        self._encoding = encoding
        self._sep = namespace_separator
        self.intern = {} if intern is None else intern
        self.buffer_text = False
        self.buffer_size = 8192
        self.buffer_used = 0
        self.ordered_attributes = False
        self.specified_attributes = False
        self.namespace_prefixes = False
        self._chunks = []
        self._done = False
        self._base = None
        self._text = ""
        self._pos = 0
        self.ErrorCode = 0
        self.ErrorLineNumber = 1
        self.ErrorColumnNumber = 0
        self.ErrorByteIndex = -1
        self.CurrentLineNumber = 1
        self.CurrentColumnNumber = 0
        self.CurrentByteIndex = -1
        self._reparse_deferral = True

    # ---- the API ----------------------------------------------------------

    def Parse(self, data, isfinal=False):
        if self._done:
            self._fail(36, 0)
        if data:
            self._chunks.append(data)
        if not isfinal:
            return 1
        self._done = True
        self._run(self._decode())
        return 1

    def ParseFile(self, file):
        return self.Parse(file.read(), True)

    def SetBase(self, base):
        self._base = base

    def GetBase(self):
        return self._base

    def GetInputContext(self):
        return None

    def ExternalEntityParserCreate(self, context, encoding=None):
        raise ExpatError("external entities are not loaded")

    def SetParamEntityParsing(self, flag):
        return 1

    def UseForeignDTD(self, flag=True):
        pass

    def GetReparseDeferralEnabled(self):
        return self._reparse_deferral

    def SetReparseDeferralEnabled(self, enabled):
        self._reparse_deferral = bool(enabled)

    # ---- reading ----------------------------------------------------------

    def _decode(self):
        if not self._chunks:
            return ""
        if isinstance(self._chunks[0], str):
            return "".join(self._chunks)
        data = b"".join(bytes(c) if not isinstance(c, bytes) else c for c in self._chunks)
        if data.startswith(b"\xef\xbb\xbf"):
            return data[3:].decode("utf-8")
        if data.startswith(b"\xff\xfe") or data.startswith(b"\xfe\xff"):
            return data.decode("utf-16")
        encoding = self._encoding
        if not encoding:
            head = data[:200].decode("latin-1")
            m = _ENCODING.match(head)
            encoding = m.group(1) if m else "utf-8"
        try:
            return data.decode(encoding)
        except LookupError:
            self._fail(18, 0)
        except UnicodeDecodeError:
            self._fail(4, 0)

    def _fail(self, code, pos):
        text = self._text
        line = text.count("\n", 0, pos) + 1
        col = pos - (text.rfind("\n", 0, pos) + 1)
        self.ErrorCode = code
        self.ErrorLineNumber = line
        self.ErrorColumnNumber = col
        self.ErrorByteIndex = pos
        e = ExpatError(f"{_MESSAGES[code]}: line {line}, column {col}")
        e.code = code
        e.lineno = line
        e.offset = col
        raise e

    def _here(self, pos):
        text = self._text
        self.CurrentLineNumber = text.count("\n", 0, pos) + 1
        self.CurrentColumnNumber = pos - (text.rfind("\n", 0, pos) + 1)
        self.CurrentByteIndex = pos

    def _run(self, text):
        self._text = text
        self._entities = dict(_PREDEFINED)
        self._ns_stack = [{"xml": _XML_NS}]
        try:
            self._document(text)
        except _Stop as s:
            self._fail(s.code, s.pos)

    def _document(self, text):
        n = len(text)
        pos = 0
        stack = []
        seen_root = False
        pending = []
        if text.startswith("<?xml") and (len(text) > 5 and text[5] in " \t\r\n?"):
            end = text.find("?>")
            if end < 0:
                raise _Stop(5, 0)
            self._xml_decl(text[5:end])
            pos = end + 2
        while pos < n:
            lt = text.find("<", pos)
            if lt < 0:
                lt = n
            if lt > pos:
                chunk = text[pos:lt]
                if stack:
                    pending.append(self._unescape(chunk, pos))
                elif chunk.strip():
                    raise _Stop(9 if seen_root else 2, pos + len(chunk) - len(chunk.lstrip()))
            if lt >= n:
                break
            if pending:
                self._chars("".join(pending), lt)
                pending = []
            pos = lt
            if text.startswith("<!--", pos):
                end = text.find("-->", pos + 4)
                if end < 0:
                    raise _Stop(5, pos)
                self._here(pos)
                if self.CommentHandler:
                    self.CommentHandler(text[pos + 4 : end])
                pos = end + 3
            elif text.startswith("<![CDATA[", pos):
                if not stack:
                    raise _Stop(2, pos)
                end = text.find("]]>", pos + 9)
                if end < 0:
                    raise _Stop(20, pos)
                self._here(pos)
                if self.StartCdataSectionHandler:
                    self.StartCdataSectionHandler()
                data = text[pos + 9 : end]
                if data:
                    self._chars(data, pos)
                if self.EndCdataSectionHandler:
                    self.EndCdataSectionHandler()
                pos = end + 3
            elif text.startswith("<?", pos):
                end = text.find("?>", pos + 2)
                if end < 0:
                    raise _Stop(5, pos)
                body = text[pos + 2 : end]
                target, _, data = body.partition(" ")
                if not target or target.lower() == "xml":
                    raise _Stop(17, pos)
                self._here(pos)
                if self.ProcessingInstructionHandler:
                    self.ProcessingInstructionHandler(target, data.lstrip())
                pos = end + 2
            elif text.startswith("<!DOCTYPE", pos):
                if stack or seen_root:
                    raise _Stop(2, pos)
                pos = self._doctype(text, pos)
            elif text.startswith("</", pos):
                m = _NAME.match(text, pos + 2)
                if not m:
                    raise _Stop(4, pos + 2)
                name = m.group(0)
                end = _SPACE.match(text, m.end()).end()
                if end >= n or text[end] != ">":
                    raise _Stop(5 if end >= n else 4, end)
                if not stack or stack[-1] != name:
                    raise _Stop(7, pos + 2)
                stack.pop()
                self._here(pos)
                self._end(name)
                pos = end + 1
            else:
                if seen_root and not stack:
                    raise _Stop(9, pos)
                m = _NAME.match(text, pos + 1)
                if not m:
                    raise _Stop(4, pos + 1)
                name = m.group(0)
                attrs = []
                p = m.end()
                while True:
                    am = _ATTR.match(text, p)
                    if not am:
                        break
                    attrs.append((am.group(1), self._unescape(am.group(2)[1:-1], am.start(2) + 1, True)))
                    p = am.end()
                p = _SPACE.match(text, p).end()
                if text.startswith("/>", p):
                    empty, close = True, p + 2
                elif text.startswith(">", p):
                    empty, close = False, p + 1
                else:
                    raise _Stop(5 if p >= n else 4, p)
                names = [a[0] for a in attrs]
                if len(set(names)) != len(names):
                    raise _Stop(8, pos)
                self._here(pos)
                seen_root = True
                self._start(name, attrs, pos)
                if empty:
                    self._end(name)
                else:
                    stack.append(name)
                pos = close
        if pending:
            self._chars("".join(pending), n)
        if stack:
            raise _Stop(3 if not seen_root else 5, n)
        if not seen_root:
            raise _Stop(3, n)

    def _xml_decl(self, body):
        found = dict(re.findall(r"""(\w+)\s*=\s*["']([^"']*)["']""", body))
        if "version" not in found:
            raise _Stop(30, 0)
        if self.XmlDeclHandler:
            standalone = {"yes": 1, "no": 0}.get(found.get("standalone"), -1)
            self.XmlDeclHandler(found["version"], found.get("encoding"), standalone)

    def _doctype(self, text, pos):
        start = pos
        m = _NAME.match(text, _SPACE.match(text, pos + 9).end())
        if not m:
            raise _Stop(2, pos)
        name = m.group(0)
        p = m.end()
        system_id = public_id = None
        rest = text[p:]
        ids = re.match(r"""\s*(SYSTEM|PUBLIC)\s*(?:(["'])(.*?)\2)?\s*(?:(["'])(.*?)\4)?""", rest)
        if ids and ids.group(1):
            p += ids.end()
            if ids.group(1) == "SYSTEM":
                system_id = ids.group(3)
            else:
                public_id, system_id = ids.group(3), ids.group(5)
        p = _SPACE.match(text, p).end()
        subset = None
        if p < len(text) and text[p] == "[":
            end = text.find("]", p)
            if end < 0:
                raise _Stop(5, p)
            subset = text[p + 1 : end]
            p = _SPACE.match(text, end + 1).end()
        if p >= len(text) or text[p] != ">":
            raise _Stop(5, p)
        self._here(start)
        if self.StartDoctypeDeclHandler:
            self.StartDoctypeDeclHandler(name, system_id, public_id, 1 if subset else 0)
        elif self.DefaultHandlerExpand or self.DefaultHandler:
            (self.DefaultHandlerExpand or self.DefaultHandler)(text[start : p + 1])
        if subset:
            for em in _ENTITY_DECL.finditer(subset):
                value = self._unescape(em.group(2)[1:-1], p)
                self._entities.setdefault(em.group(1), value)
                if self.EntityDeclHandler:
                    self.EntityDeclHandler(em.group(1), 0, value, self._base, None, None, None)
        if self.EndDoctypeDeclHandler:
            self.EndDoctypeDeclHandler()
        return p + 1

    def _unescape(self, s, at, attribute=False):
        if attribute and ("\t" in s or "\n" in s or "\r" in s):
            s = s.replace("\r\n", " ").replace("\r", " ").replace("\n", " ").replace("\t", " ")
        elif "\r" in s:
            s = s.replace("\r\n", "\n").replace("\r", "\n")
        if "&" not in s:
            return s
        out = []
        i = 0
        while True:
            amp = s.find("&", i)
            if amp < 0:
                out.append(s[i:])
                break
            out.append(s[i:amp])
            semi = s.find(";", amp)
            if semi < 0:
                raise _Stop(4, at + amp)
            ref = s[amp + 1 : semi]
            if ref.startswith("#x") or ref.startswith("#X"):
                try:
                    out.append(chr(int(ref[2:], 16)))
                except ValueError:
                    raise _Stop(14, at + amp)
            elif ref.startswith("#"):
                try:
                    out.append(chr(int(ref[1:])))
                except ValueError:
                    raise _Stop(14, at + amp)
            elif ref in self._entities:
                out.append(self._entities[ref])
            else:
                handler = self.DefaultHandlerExpand or self.DefaultHandler
                if self.SkippedEntityHandler:
                    self.SkippedEntityHandler(ref, 0)
                elif handler and not attribute:
                    handler("&" + ref + ";")
                else:
                    raise _Stop(11, at + amp)
            i = semi + 1
        return "".join(out)

    def _chars(self, data, pos):
        if data and self.CharacterDataHandler:
            self._here(pos)
            self.CharacterDataHandler(data)

    # ---- elements and namespaces ------------------------------------------

    def _qualify(self, name, scope, default_applies, pos):
        prefix, colon, local = name.partition(":")
        if not colon:
            uri = scope.get("") if default_applies else None
            if not uri:
                return name
            return uri + self._sep + name
        if prefix == "xmlns":
            return name
        uri = scope.get(prefix)
        if uri is None:
            raise _Stop(27, pos)
        out = uri + self._sep + local
        if self.namespace_prefixes:
            out += self._sep + prefix
        return out

    def _start(self, name, attrs, pos):
        if self._sep is not None:
            scope = dict(self._ns_stack[-1])
            declared = []
            kept = []
            for k, v in attrs:
                if k == "xmlns":
                    scope[""] = v
                    declared.append((None, v or None))
                elif k.startswith("xmlns:"):
                    scope[k[6:]] = v
                    declared.append((k[6:], v))
                else:
                    kept.append((k, v))
            self._ns_stack.append(scope)
            self._declared = getattr(self, "_declared", [])
            self._declared.append(declared)
            if self.StartNamespaceDeclHandler:
                for prefix, uri in declared:
                    self.StartNamespaceDeclHandler(prefix, uri)
            name = self._qualify(name, scope, True, pos)
            attrs = [(self._qualify(k, scope, False, pos), v) for k, v in kept]
        if self.StartElementHandler:
            if self.ordered_attributes:
                flat = []
                for k, v in attrs:
                    flat.append(k)
                    flat.append(v)
                self.StartElementHandler(name, flat)
            else:
                self.StartElementHandler(name, dict(attrs))
        if self._sep is not None:
            self._names = getattr(self, "_names", [])
            self._names.append(name)

    def _end(self, name):
        if self._sep is not None:
            name = self._names.pop()
        if self.EndElementHandler:
            self.EndElementHandler(name)
        if self._sep is not None:
            self._ns_stack.pop()
            declared = self._declared.pop()
            if self.EndNamespaceDeclHandler:
                for prefix, _ in reversed(declared):
                    self.EndNamespaceDeclHandler(prefix)


def ParserCreate(encoding=None, namespace_separator=None, intern=None):
    if namespace_separator is not None and len(namespace_separator) > 1:
        raise ValueError("namespace_separator must be at most one character, omitted, or None")
    return XMLParserType(encoding, namespace_separator, intern)
