"""Request, PreparedRequest and Response, as requests has them."""

import datetime as _datetime
import json as _json
from wm import _net
from requests.structures import CaseInsensitiveDict
from requests.cookies import RequestsCookieJar
from requests.exceptions import HTTPError, InvalidJSONError, JSONDecodeError

REDIRECT_STATI = (301, 302, 303, 307, 308)
ITER_CHUNK_SIZE = 512


def _files_of(files):
    """requests' files= as (field, file name, bytes, type) tuples: a dict
    or a list of (field, value), each value bytes, a file-like object, text,
    (name, content), (name, content, type) or (name, content, type, headers),
    or what wm.files.pick gives."""
    items = files.items() if isinstance(files, dict) or hasattr(files, "items") else files
    out = []
    for field, value in items:
        if isinstance(value, dict):
            out.append((field, str(value.get("name") or field), _net.as_bytes(value.get("data", b"")), value.get("mime")))
        elif isinstance(value, (tuple, list)):
            if len(value) < 2:
                raise ValueError("a file is (name, content), (name, content, type) or (name, content, type, headers)")
            kind = value[2] if len(value) > 2 else None
            name = value[0] if value[0] is not None else field
            out.append((field, str(name), _net.as_bytes(value[1]), kind))
        else:
            name = getattr(value, "name", None)
            out.append((field, str(name).replace("\\", "/").rsplit("/", 1)[-1] if isinstance(name, str) else str(field), _net.as_bytes(value), None))
    return out


class Request:
    """A request to send: requests.Request("GET", url, params=...), then
    .prepare(), or session.send(session.prepare_request(req))."""

    def __init__(self, method=None, url=None, headers=None, files=None, data=None, params=None, auth=None, cookies=None, hooks=None, json=None):
        self.method = method
        self.url = url
        self.headers = headers if headers is not None else {}
        self.files = files if files is not None else []
        self.data = data if data is not None else []
        self.params = params if params is not None else {}
        self.auth = auth
        self.cookies = cookies
        self.hooks = hooks if hooks is not None else {"response": []}
        self.json = json

    def __repr__(self):
        return f"<Request [{self.method}]>"

    def prepare(self):
        """The request as it will go: a PreparedRequest."""
        p = PreparedRequest()
        p.prepare(method=self.method, url=self.url, headers=self.headers, files=self.files, data=self.data, params=self.params,
                  auth=self.auth, cookies=self.cookies, hooks=self.hooks, json=self.json)
        return p


class PreparedRequest:
    """What is sent: method, url (params included), headers and body."""

    def __init__(self):
        self.method = None
        self.url = None
        self.headers = None
        self.body = None
        self.hooks = {"response": []}
        self._cookies = None

    def prepare(self, method=None, url=None, headers=None, files=None, data=None, params=None, auth=None, cookies=None, hooks=None, json=None):
        self.prepare_method(method)
        self.prepare_url(url, params)
        self.prepare_headers(headers)
        self.prepare_cookies(cookies)
        self.prepare_body(data, files, json)
        self.prepare_auth(auth)
        self.prepare_hooks(hooks)

    def __repr__(self):
        return f"<PreparedRequest [{self.method}]>"

    def copy(self):
        p = PreparedRequest()
        p.method = self.method
        p.url = self.url
        p.headers = self.headers.copy() if self.headers is not None else None
        p.body = self.body
        p.hooks = self.hooks
        p._cookies = self._cookies
        return p

    def prepare_method(self, method):
        self.method = str(method).upper() if method is not None else None

    def prepare_url(self, url, params):
        from requests.exceptions import InvalidSchema, InvalidURL, MissingSchema
        if isinstance(url, bytes):
            url = url.decode("utf-8")
        url = "" if url is None else str(url).strip()

        def make(kind, message):
            if kind == "missing_schema":
                return MissingSchema(message, request=self)
            if kind == "schema":
                return InvalidSchema(message, request=self)
            return InvalidURL(message, request=self)

        _net.check_url(url, make)
        self.url = _net.with_params(url, params)

    def prepare_headers(self, headers):
        self.headers = CaseInsensitiveDict()
        if headers:
            for name, value in headers.items():
                self.headers[name] = value

    def prepare_body(self, data, files, json=None):
        body = None
        content_type = None
        if not data and json is not None:
            content_type = "application/json"
            try:
                body = _json.dumps(json).encode("utf-8")
            except (ValueError, TypeError) as ve:
                raise InvalidJSONError(str(ve), request=self)
        if files:
            fields = _net.pairs_of(data) if data and not isinstance(data, (str, bytes)) else []
            body, content_type = _net.multipart(fields, _files_of(files))
        elif data:
            if isinstance(data, (str, bytes)):
                body = data
            elif getattr(data, "read", None) is not None:
                body = _net.as_bytes(data)
            else:
                body = _net.urlencode(data)
                content_type = "application/x-www-form-urlencoded"
        if content_type and "content-type" not in self.headers:
            self.headers["Content-Type"] = content_type
        self.body = body

    def prepare_auth(self, auth, url=""):
        if auth:
            if isinstance(auth, (tuple, list)) and len(auth) == 2:
                from requests.auth import HTTPBasicAuth
                auth = HTTPBasicAuth(auth[0], auth[1])
            r = auth(self)
            if r is not None and r is not self:
                self.method = r.method
                self.url = r.url
                self.headers = r.headers
                self.body = r.body

    def prepare_cookies(self, cookies):
        self._cookies = cookies

    def prepare_hooks(self, hooks):
        for event, hook in (hooks or {}).items():
            self.register_hook(event, hook)

    def register_hook(self, event, hook):
        found = self.hooks.setdefault(event, [])
        if callable(hook):
            found.append(hook)
        else:
            found.extend([h for h in hook if callable(h)])

    @property
    def path_url(self):
        rest = self.url.split("://", 1)[-1]
        at = rest.find("/")
        path = rest[at:] if at >= 0 else "/"
        return path.split("#", 1)[0]


class _Raw:
    """r.raw: the body, already read, as a file to read from."""

    def __init__(self, data):
        self._data = data
        self._at = 0
        self.decode_content = True

    def read(self, amt=None, decode_content=None):
        if amt is None:
            out = self._data[self._at:]
            self._at = len(self._data)
            return out
        out = self._data[self._at:self._at + amt]
        self._at += len(out)
        return out

    def close(self):
        pass


class Response:
    """The reply: status_code, ok, reason, headers, text, content, json(),
    raise_for_status(), url, encoding, elapsed, cookies, links."""

    def __init__(self):
        self._content = None
        self._given = None
        self.status_code = None
        self.headers = CaseInsensitiveDict()
        self.raw = None
        self.url = None
        self.encoding = None
        self.history = []
        self.reason = None
        self.cookies = RequestsCookieJar()
        self.elapsed = _datetime.timedelta(0)
        self.request = None

    def __repr__(self):
        return f"<Response [{self.status_code}]>"

    def __bool__(self):
        return self.ok

    def __iter__(self):
        return iter(self.iter_content(128))

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    @property
    def ok(self):
        """False when the status is 400 to 599: what raise_for_status() raises for."""
        code = self.status_code or 0
        return not 400 <= code < 600

    @property
    def is_redirect(self):
        return "location" in self.headers and self.status_code in REDIRECT_STATI

    @property
    def is_permanent_redirect(self):
        return "location" in self.headers and self.status_code in (301, 308)

    @property
    def next(self):
        return None

    @property
    def content(self):
        """The body as bytes."""
        return self._content if self._content is not None else b""

    @property
    def apparent_encoding(self):
        """"utf-8" when the body is valid UTF-8, "windows-1252" otherwise."""
        return "utf-8" if _net.utf8_ok(self.content) else "windows-1252"

    @property
    def text(self):
        """The body as text, in r.encoding, or r.apparent_encoding when the
        reply names none."""
        encoding = self.encoding
        if self._given is not None and _net.is_utf8_name(encoding):
            return self._given
        data = self.content
        if not data:
            return ""
        return _net.decode(data, encoding or self.apparent_encoding)

    def json(self, **kwargs):
        """The body read as JSON. Raises requests.exceptions.JSONDecodeError
        (a ValueError) when it is not JSON."""
        text = self.text
        at = 0
        while at < len(text) and text[at] in " \t\n\r":
            at += 1
        if at == len(text) or text[at] not in "{[\"-0123456789tfnNI":
            raise JSONDecodeError("Expecting value", text, at, response=self, request=self.request)
        try:
            return _json.loads(text)
        except ValueError as e:
            raise JSONDecodeError(str(e), text, None, response=self, request=self.request)

    @property
    def links(self):
        """The Link header by rel: r.links["next"]["url"]."""
        header = self.headers.get("link")
        resolved = {}
        if header:
            for link in _net.parse_links(header):
                key = link.get("rel") or link.get("url")
                resolved[key] = link
        return resolved

    def raise_for_status(self):
        """Raises requests.exceptions.HTTPError when the status is 400 to 599."""
        reason = self.reason
        if isinstance(reason, bytes):
            reason = reason.decode("utf-8", "replace")
        message = ""
        code = self.status_code or 0
        if 400 <= code < 500:
            message = f"{code} Client Error: {reason} for url: {self.url}"
        elif 500 <= code < 600:
            message = f"{code} Server Error: {reason} for url: {self.url}"
        if message:
            raise HTTPError(message, response=self)

    def iter_content(self, chunk_size=1, decode_unicode=False):
        """The body in pieces of chunk_size bytes (all of it at once for
        None), as a list: the keyboard has already read the whole reply."""
        if decode_unicode:
            data = self.text
        else:
            data = self.content
        if chunk_size is None or chunk_size <= 0 or chunk_size >= len(data):
            return [data] if data else []
        return [data[i:i + chunk_size] for i in range(0, len(data), chunk_size)]

    def iter_lines(self, chunk_size=ITER_CHUNK_SIZE, decode_unicode=False, delimiter=None):
        """The body's lines, as a list, without their line ends."""
        if decode_unicode:
            text = self.text
            return text.split(delimiter) if delimiter is not None else text.splitlines()
        data = self.content
        if delimiter is not None:
            return data.split(delimiter if isinstance(delimiter, bytes) else str(delimiter).encode("utf-8"))
        lines = data.replace(b"\r\n", b"\n").replace(b"\r", b"\n").split(b"\n")
        if lines and lines[-1] == b"":
            lines.pop()
        return lines

    def close(self):
        pass


def build_response(reply, request, seconds):
    """A Response from the keyboard's reply to [request]."""
    r = Response()
    r.status_code = int(reply.get("status") or 0)
    r.headers = CaseInsensitiveDict(reply.get("headers") or {})
    r.url = reply.get("url") or request.url
    r.reason = reply.get("reason") or _net.reason_for(r.status_code)
    r._content, r._given = _net.content_of(reply)
    from requests.utils import get_encoding_from_headers
    r.encoding = get_encoding_from_headers(r.headers)
    r.elapsed = _datetime.timedelta(seconds=seconds)
    r.request = request
    r.raw = _Raw(r._content)
    host = _net.host_of(r.url)
    for c in _net.set_cookies(r.headers, host):
        if not c["expired"]:
            r.cookies.set(c["name"], c["value"], domain=c["domain"], path=c["path"], secure=c["secure"])
    return r
