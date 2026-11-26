"""email.message: a message as headers and a payload (or parts)."""

import base64
import binascii
import re

from email import utils
from email import errors
from email import charset as _charset
from email.policy import compat32

SEMISPACE = "; "
tspecials = re.compile(r'[ \(\)<>@,;:\\"/\[\]\?=]')


def _splitparam(param):
    a, sep, b = str(param).partition(";")
    if not sep:
        return a.strip(), None
    return a.strip(), b.strip()


def _formatparam(param, value=None, quote=True):
    if value is not None and len(value) > 0:
        if isinstance(value, tuple):
            param += "*"
            value = utils.encode_rfc2231(value[2], value[0], value[1])
            return f"{param}={value}"
        try:
            value.encode("ascii")
        except UnicodeEncodeError:
            param += "*"
            value = utils.encode_rfc2231(value, "utf-8", "")
            return f"{param}={value}"
        if quote or tspecials.search(value):
            return f'{param}="{utils.quote(value)}"'
        return f"{param}={value}"
    return param


def _parseparam(s):
    s = ";" + str(s)
    plist = []
    while s[:1] == ";":
        s = s[1:]
        end = s.find(";")
        while end > 0 and (s.count('"', 0, end) - s.count('\\"', 0, end)) % 2:
            end = s.find(";", end + 1)
        if end < 0:
            end = len(s)
        f = s[:end]
        if "=" in f:
            i = f.index("=")
            f = f[:i].strip().lower() + "=" + f[i + 1 :].strip()
        plist.append(f.strip())
        s = s[end:]
    return plist


def _unquotevalue(value):
    if isinstance(value, tuple):
        return value[0], value[1], utils.unquote(value[2])
    return utils.unquote(value)


def _qp_decode_body(data):
    out = bytearray()
    lines = data.split(b"\n")
    for n, line in enumerate(lines):
        soft = line.rstrip(b" \t\r").endswith(b"=")
        body = line.rstrip(b" \t\r")
        if soft:
            body = body[:-1]
        i = 0
        while i < len(body):
            c = body[i : i + 1]
            pair = body[i + 1 : i + 3]
            if c == b"=" and len(pair) == 2 and all(x in b"0123456789abcdefABCDEF" for x in pair):
                out.append(int(pair, 16))
                i += 3
            else:
                out += c
                i += 1
        if n < len(lines) - 1 and not soft:
            out += b"\n"
    return bytes(out)


class Message:
    def __init__(self, policy=compat32):
        self.policy = policy
        self._headers = []
        self._unixfrom = None
        self._payload = None
        self._charset = None
        self.preamble = self.epilogue = None
        self.defects = []
        self._default_type = "text/plain"

    def __str__(self):
        return self.as_string()

    def as_string(self, unixfrom=False, maxheaderlen=0, policy=None):
        policy = self.policy if policy is None else policy
        return self._flatten(unixfrom, policy.linesep)

    def as_bytes(self, unixfrom=False, policy=None):
        return self.as_string(unixfrom, policy=policy).encode("utf-8")

    def __bytes__(self):
        return self.as_bytes()

    def _flatten(self, unixfrom, linesep):
        out = []
        if unixfrom and self._unixfrom:
            out.append(self._unixfrom + linesep)
        for name, value in self._headers:
            value = str(value).replace("\n", linesep) if linesep != "\n" else str(value)
            out.append(f"{name}: {value}{linesep}")
        out.append(linesep)
        if self.is_multipart() and self.get_content_maintype() == "message":
            for part in self._payload:
                out.append(part._flatten(False, linesep))
        elif self.is_multipart():
            boundary = self.get_boundary()
            if boundary is None:
                boundary = "===============" + str(abs(hash(id(self))) % 10**19) + "=="
                self.set_boundary(boundary)
                return self._flatten(unixfrom, linesep)
            if self.preamble is not None:
                out.append(self.preamble + linesep)
            for part in self._payload:
                out.append(f"--{boundary}{linesep}")
                out.append(part._flatten(False, linesep))
                if not out[-1].endswith(linesep):
                    out.append(linesep)
            out.append(f"--{boundary}--{linesep}")
            if self.epilogue is not None:
                out.append(self.epilogue)
        elif self._payload is not None:
            payload = self._payload
            if isinstance(payload, bytes):
                payload = payload.decode("latin-1")
            out.append(payload)
        return "".join(out)

    def is_multipart(self):
        return isinstance(self._payload, list)

    def set_unixfrom(self, unixfrom):
        self._unixfrom = unixfrom

    def get_unixfrom(self):
        return self._unixfrom

    def attach(self, payload):
        if self._payload is None:
            self._payload = [payload]
        else:
            try:
                self._payload.append(payload)
            except AttributeError:
                raise TypeError("Attach is not valid on a message with a non-multipart payload") from None

    def get_payload(self, i=None, decode=False):
        if self.is_multipart():
            if decode:
                return None
            if i is None:
                return self._payload
            return self._payload[i]
        if i is not None and not isinstance(self._payload, list):
            raise TypeError(f"Expected list, got {type(self._payload)}")
        payload = self._payload
        if not decode:
            return payload
        cte = str(self.get("content-transfer-encoding", "")).lower()
        if isinstance(payload, str):
            try:
                bpayload = payload.encode("ascii")
            except UnicodeEncodeError:
                if getattr(self, "_raw_latin1", False):
                    bpayload = payload.encode("latin-1")
                else:
                    bpayload = payload.encode(self.get_content_charset() or "utf-8", "replace")
        else:
            bpayload = payload if payload is not None else b""
        if cte == "quoted-printable":
            return _qp_decode_body(bpayload)
        if cte == "base64":
            data = b"".join(bpayload.split())
            try:
                return base64.b64decode(data + b"=" * (-len(data) % 4))
            except binascii.Error:
                self.policy.handle_defect(self, errors.InvalidBase64CharactersDefect())
                return bpayload
        return bpayload

    def set_payload(self, payload, charset=None):
        self._payload = payload
        if charset is not None:
            self.set_charset(charset)

    def set_charset(self, charset):
        if charset is None:
            self.del_param("charset")
            self._charset = None
            return
        if not isinstance(charset, _charset.Charset):
            charset = _charset.Charset(charset)
        self._charset = charset
        if "MIME-Version" not in self:
            self.add_header("MIME-Version", "1.0")
        if "Content-Type" not in self:
            self.add_header("Content-Type", "text/plain", charset=charset.get_output_charset())
        else:
            self.set_param("charset", charset.get_output_charset())
        if "Content-Transfer-Encoding" not in self:
            cte = charset.get_body_encoding()
            if isinstance(self._payload, str) and cte != "7bit":
                self._payload = charset.body_encode(self._payload)
            if cte == "7bit" and isinstance(self._payload, str):
                try:
                    self._payload.encode("ascii")
                except UnicodeEncodeError:
                    cte = "8bit"
            self.add_header("Content-Transfer-Encoding", cte)

    def get_charset(self):
        return self._charset

    def __len__(self):
        return len(self._headers)

    def __getitem__(self, name):
        return self.get(name)

    def __setitem__(self, name, val):
        self._headers.append((name, val))

    def __delitem__(self, name):
        name = name.lower()
        self._headers = [(k, v) for k, v in self._headers if k.lower() != name]

    def __contains__(self, name):
        name = name.lower()
        return any(k.lower() == name for k, v in self._headers)

    def __iter__(self):
        for field, value in self._headers:
            yield field

    def keys(self):
        return [k for k, v in self._headers]

    def values(self):
        return [v for k, v in self._headers]

    def items(self):
        return list(self._headers)

    def get(self, name, failobj=None):
        name = name.lower()
        for k, v in self._headers:
            if k.lower() == name:
                return v
        return failobj

    def set_raw(self, name, value):
        self._headers.append((name, value))

    def raw_items(self):
        return iter(self._headers)

    def get_all(self, name, failobj=None):
        name = name.lower()
        values = [v for k, v in self._headers if k.lower() == name]
        return values if values else failobj

    def add_header(self, _name, _value, **_params):
        parts = []
        for k, v in _params.items():
            if v is None:
                parts.append(k.replace("_", "-"))
            else:
                parts.append(_formatparam(k.replace("_", "-"), v))
        if _value is not None:
            parts.insert(0, _value)
        self._headers.append((_name, SEMISPACE.join(parts)))

    def replace_header(self, _name, _value):
        _name = _name.lower()
        for i, (k, v) in enumerate(self._headers):
            if k.lower() == _name:
                self._headers[i] = (k, _value)
                return
        raise KeyError(_name)

    def get_content_type(self):
        missing = object()
        value = self.get("content-type", missing)
        if value is missing:
            return self.get_default_type()
        ctype = _splitparam(value)[0].lower()
        if ctype.count("/") != 1:
            return "text/plain"
        return ctype

    def get_content_maintype(self):
        return self.get_content_type().split("/")[0]

    def get_content_subtype(self):
        return self.get_content_type().split("/")[1]

    def get_default_type(self):
        return self._default_type

    def set_default_type(self, ctype):
        self._default_type = ctype

    def _get_params_preserve(self, failobj, header):
        missing = object()
        value = self.get(header, missing)
        if value is missing:
            return failobj
        params = []
        for p in _parseparam(value):
            try:
                name, val = p.split("=", 1)
                name = name.strip()
                val = val.strip()
            except ValueError:
                name = p.strip()
                val = ""
            params.append((name, val))
        return utils.decode_params(params)

    def get_params(self, failobj=None, header="content-type", unquote=True):
        missing = object()
        params = self._get_params_preserve(missing, header)
        if params is missing:
            return failobj
        if unquote:
            return [(k, _unquotevalue(v)) for k, v in params]
        return params

    def get_param(self, param, failobj=None, header="content-type", unquote=True):
        if header not in self:
            return failobj
        for k, v in self._get_params_preserve(failobj, header):
            if k.lower() == param.lower():
                return _unquotevalue(v) if unquote else v
        return failobj

    def set_param(self, param, value, header="Content-Type", requote=True, charset=None, language="", replace=False):
        if not isinstance(value, tuple) and charset:
            value = (charset, language, value)
        if header not in self and header.lower() == "content-type":
            ctype = "text/plain"
        else:
            ctype = self.get(header)
        if not self.get_param(param, header=header):
            if not ctype:
                ctype = _formatparam(param, value, requote)
            else:
                ctype = SEMISPACE.join([ctype, _formatparam(param, value, requote)])
        else:
            ctype = ""
            for old_param, old_value in self.get_params(header=header, unquote=requote):
                append_param = ""
                if old_param.lower() == param.lower():
                    append_param = _formatparam(param, value, requote)
                else:
                    append_param = _formatparam(old_param, old_value, requote)
                ctype = append_param if not ctype else SEMISPACE.join([ctype, append_param])
        if ctype != self.get(header):
            if replace and header in self:
                self.replace_header(header, ctype)
            else:
                del self[header]
                self[header] = ctype

    def del_param(self, param, header="content-type", requote=True):
        if header not in self:
            return
        new_ctype = ""
        for p, v in self.get_params(header=header, unquote=requote):
            if p.lower() != param.lower():
                new_ctype = _formatparam(p, v, requote) if not new_ctype else SEMISPACE.join([new_ctype, _formatparam(p, v, requote)])
        if new_ctype != self.get(header):
            del self[header]
            self[header] = new_ctype

    def set_type(self, type, header="Content-Type", requote=True):
        if not type.count("/") == 1:
            raise ValueError
        if header.lower() == "content-type":
            del self["mime-version"]
            self["MIME-Version"] = "1.0"
        if header not in self:
            self[header] = type
            return
        params = self.get_params(header=header, unquote=requote)
        del self[header]
        self[header] = type
        for p, v in params[1:]:
            self.set_param(p, v, header, requote)

    def get_filename(self, failobj=None):
        missing = object()
        filename = self.get_param("filename", missing, "content-disposition")
        if filename is missing:
            filename = self.get_param("name", missing, "content-type")
        if filename is missing:
            return failobj
        return utils.collapse_rfc2231_value(filename).strip()

    def get_boundary(self, failobj=None):
        missing = object()
        boundary = self.get_param("boundary", missing)
        if boundary is missing:
            return failobj
        return utils.collapse_rfc2231_value(boundary).rstrip()

    def set_boundary(self, boundary):
        if "content-type" not in self:
            raise errors.HeaderParseError("No Content-Type header found")
        self.set_param("boundary", boundary, replace=True)

    def get_content_charset(self, failobj=None):
        missing = object()
        charset = self.get_param("charset", missing)
        if charset is missing:
            return failobj
        if isinstance(charset, tuple):
            charset = utils.collapse_rfc2231_value(charset)
        return str(charset).lower()

    def get_charsets(self, failobj=None):
        return [part.get_content_charset(failobj) for part in self.walk()]

    def get_content_disposition(self):
        value = self.get("content-disposition")
        if value is None:
            return None
        return _splitparam(value)[0].lower()

    def walk(self):
        yield self
        if self.is_multipart():
            for subpart in self.get_payload():
                yield from subpart.walk()


class EmailMessage(Message):
    def __init__(self, policy=None):
        from email.policy import default

        super().__init__(default if policy is None else policy)

    def set_content(self, content, subtype=None, charset="utf-8", cte=None, disposition=None, filename=None, cid=None, params=None, headers=None):
        for name in ("Content-Type", "Content-Transfer-Encoding", "MIME-Version"):
            del self[name]
        if isinstance(content, str):
            self["Content-Type"] = f"text/{subtype or 'plain'}; charset=\"{charset}\""
            try:
                content.encode("ascii")
                cte = cte or "7bit"
            except UnicodeEncodeError:
                cte = cte or "8bit"
            if not content.endswith("\n"):
                content += "\n"
            self["Content-Transfer-Encoding"] = cte
            self._payload = content
        else:
            maintype, _, sub = (subtype or "application/octet-stream").partition("/") if subtype and "/" in subtype else ("application", "", subtype or "octet-stream")
            self["Content-Type"] = f"{maintype}/{sub}"
            self["Content-Transfer-Encoding"] = "base64"
            self._payload = base64.encodebytes(bytes(content)).decode("ascii")
        if disposition or filename:
            self.add_header("Content-Disposition", disposition or "attachment", filename=filename)
        if "MIME-Version" not in self:
            self["MIME-Version"] = "1.0"

    def get_content(self):
        if self.get_content_maintype() == "text":
            data = self.get_payload(decode=True)
            return data.decode(self.get_content_charset("utf-8"), "replace")
        return self.get_payload(decode=True)

    def get_body(self, preferencelist=("related", "html", "plain")):
        if not self.is_multipart():
            return self if self.get_content_maintype() == "text" else None
        for kind in preferencelist:
            for part in self.walk():
                if part.get_content_type() == f"text/{kind}":
                    return part
        return None

    def iter_attachments(self):
        if not self.is_multipart():
            return iter(())
        return iter([p for p in self.get_payload() if p.get_content_disposition() == "attachment"])

    def iter_parts(self):
        if self.is_multipart():
            yield from self.get_payload()

    def add_attachment(self, content, maintype=None, subtype=None, filename=None, **kw):
        part = EmailMessage(self.policy)
        if isinstance(content, str):
            part.set_content(content, subtype or "plain")
        else:
            part.set_content(content, f"{maintype or 'application'}/{subtype or 'octet-stream'}")
        part.add_header("Content-Disposition", "attachment", filename=filename)
        if not self.is_multipart():
            body = None
            if self._payload is not None:
                body = EmailMessage(self.policy)
                body._headers = [(k, v) for k, v in self._headers if k.lower().startswith("content-")]
                body._payload = self._payload
                self._headers = [(k, v) for k, v in self._headers if not k.lower().startswith("content-")]
            self._payload = []
            del self["Content-Type"]
            self["Content-Type"] = "multipart/mixed"
            if body is not None:
                self._payload.append(body)
        self._payload.append(part)

    def clear(self):
        self._headers = []
        self._payload = None

    def clear_content(self):
        self._headers = [(n, v) for n, v in self._headers if not n.lower().startswith("content-")]
        self._payload = None


class MIMEPart(EmailMessage):
    pass
