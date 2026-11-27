"""email.parser: messages from text or bytes: headers, then a body that a
multipart content type splits into parts."""

from email.message import Message
from email.policy import compat32

__all__ = ["Parser", "HeaderParser", "BytesParser", "BytesHeaderParser", "FeedParser", "BytesFeedParser"]


def _parse(text, factory, headersonly, policy):
    msg = factory(policy=policy) if factory is not None else Message(policy)
    lines = text.splitlines(True)
    i = 0
    if lines and lines[0].startswith("From "):
        msg.set_unixfrom(lines[0].rstrip("\r\n"))
        i = 1
    name = None
    value = []
    while i < len(lines):
        line = lines[i]
        stripped = line.rstrip("\r\n")
        if stripped == "":
            i += 1
            break
        if stripped[:1] in (" ", "\t") and name is not None:
            value.append(stripped)
            i += 1
            continue
        if name is not None:
            msg._headers.append((name, "\n".join(value)))
            name = None
        if ":" in stripped:
            name, _, first = stripped.partition(":")
            if name != name.strip() or not name:
                from email.errors import MissingHeaderBodySeparatorDefect

                msg.defects.append(MissingHeaderBodySeparatorDefect())
                name = None
                break
            value = [first.lstrip()]
        else:
            from email.errors import MissingHeaderBodySeparatorDefect

            msg.defects.append(MissingHeaderBodySeparatorDefect())
            break
        i += 1
    if name is not None:
        msg._headers.append((name, "\n".join(value)))
    body = "".join(lines[i:])
    if headersonly:
        msg._payload = body
        return msg
    if msg.get_content_maintype() == "multipart":
        boundary = msg.get_boundary()
        if boundary:
            _split_multipart(msg, body, boundary, factory, policy)
            return msg
        from email.errors import NoBoundaryInMultipartDefect

        msg.defects.append(NoBoundaryInMultipartDefect())
    elif msg.get_content_type() == "message/rfc822" and body:
        msg._payload = [_parse(body, factory, False, policy)]
        return msg
    msg._payload = body
    return msg


def _split_multipart(msg, body, boundary, factory, policy):
    separator = "--" + boundary
    parts = []
    current = None
    preamble = []
    epilogue = None
    for line in body.splitlines(True):
        stripped = line.rstrip("\r\n")
        if stripped == separator + "--" and epilogue is None:
            if current is not None:
                parts.append(current)
            current = None
            epilogue = []
            continue
        if stripped == separator and epilogue is None:
            if current is not None:
                parts.append(current)
            current = []
            continue
        if epilogue is not None:
            epilogue.append(line)
        elif current is None:
            preamble.append(line)
        else:
            current.append(line)
    if current is not None:
        parts.append(current)
        from email.errors import CloseBoundaryNotFoundDefect

        msg.defects.append(CloseBoundaryNotFoundDefect())
    msg.preamble = "".join(preamble).rstrip("\r\n") if preamble else None
    msg.epilogue = "".join(epilogue) if epilogue else None
    payload = []
    for part in parts:
        text = "".join(part)
        if text.endswith("\r\n"):
            text = text[:-2]
        elif text.endswith("\n"):
            text = text[:-1]
        sub = _parse(text, factory, False, policy)
        if msg.get_content_subtype() == "digest" and "content-type" not in sub:
            sub.set_default_type("message/rfc822")
        payload.append(sub)
    msg._payload = payload


class Parser:
    def __init__(self, _class=None, *, policy=compat32):
        self._class = _class
        self.policy = policy

    def parse(self, fp, headersonly=False):
        return self.parsestr(fp.read(), headersonly)

    def parsestr(self, text, headersonly=False):
        return _parse(text, self._class, headersonly, self.policy)


class HeaderParser(Parser):
    def parse(self, fp, headersonly=True):
        return Parser.parse(self, fp, True)

    def parsestr(self, text, headersonly=True):
        return Parser.parsestr(self, text, True)


class BytesParser:
    def __init__(self, *args, **kw):
        self.parser = Parser(*args, **kw)

    def parse(self, fp, headersonly=False):
        return self.parsebytes(fp.read(), headersonly)

    def parsebytes(self, text, headersonly=False):
        msg = self.parser.parsestr(bytes(text).decode("latin-1"), headersonly)
        for part in msg.walk():
            part._raw_latin1 = True
        return msg


class BytesHeaderParser(BytesParser):
    def parse(self, fp, headersonly=True):
        return BytesParser.parse(self, fp, headersonly=True)

    def parsebytes(self, text, headersonly=True):
        return BytesParser.parsebytes(self, text, headersonly=True)


from email.feedparser import FeedParser, BytesFeedParser
