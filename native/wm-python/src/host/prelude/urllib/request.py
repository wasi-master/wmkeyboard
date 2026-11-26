"""urllib.request's urlopen and Request, built into WM Keyboard's plugins
and sent through the keyboard.

    from urllib.request import urlopen
    import json

    with urlopen("https://api.example.com/v1/items", timeout=10) as reply:
        items = json.loads(reply.read())

Needs the `network` permission and each host in plugin.json's `hosts`.
https only, replies up to 1 MB, 15 seconds at most."""

import urllib.parse as _parse
from wm import _net
from urllib.error import HTTPError, URLError


class Request:
    """A request for urlopen: Request(url, data=None, headers={}, method=None).
    data (bytes) makes it a POST unless method says otherwise."""

    def __init__(self, url, data=None, headers={}, origin_req_host=None, unverifiable=False, method=None):
        self.full_url = str(url)
        self.data = data
        self.headers = {}
        for key, value in headers.items():
            self.add_header(key, value)
        self.origin_req_host = origin_req_host or _net.host_of(self.full_url)
        self.unverifiable = unverifiable
        self.method = method
        self.unredirected_hdrs = {}

    @property
    def type(self):
        return self.full_url.split("://", 1)[0].lower() if "://" in self.full_url else ""

    @property
    def host(self):
        return _parse.urlsplit(self.full_url)[1]

    @property
    def selector(self):
        parts = _parse.urlsplit(self.full_url)
        return (parts[2] or "/") + ("?" + parts[3] if parts[3] else "")

    def get_full_url(self):
        return self.full_url

    def get_method(self):
        """The method: method= when given, POST with data, GET otherwise."""
        if self.method is not None:
            return self.method.upper()
        return "POST" if self.data is not None else "GET"

    def add_header(self, key, val):
        self.headers[key.capitalize()] = val

    def add_unredirected_header(self, key, val):
        self.unredirected_hdrs[key.capitalize()] = val

    def has_header(self, header_name):
        return header_name in self.headers or header_name in self.unredirected_hdrs

    def get_header(self, header_name, default=None):
        return self.headers.get(header_name, self.unredirected_hdrs.get(header_name, default))

    def remove_header(self, header_name):
        self.headers.pop(header_name, None)
        self.unredirected_hdrs.pop(header_name, None)

    def header_items(self):
        merged = dict(self.unredirected_hdrs)
        merged.update(self.headers)
        return list(merged.items())


class HTTPMessage:
    """A reply's headers: msg["Content-Type"], msg.get(name),
    msg.get_content_charset(), msg.items()."""

    def __init__(self, headers):
        self._h = _net.CaseInsensitiveDict(headers or {})

    def __getitem__(self, name):
        return self._h.get(name)

    def __contains__(self, name):
        return name in self._h

    def __iter__(self):
        return iter(self._h.keys())

    def __len__(self):
        return len(self._h)

    def get(self, name, failobj=None):
        return self._h.get(name, failobj)

    def get_all(self, name, failobj=None):
        value = self._h.get(name)
        return [value] if value is not None else failobj

    def keys(self):
        return self._h.keys()

    def values(self):
        return self._h.values()

    def items(self):
        return self._h.items()

    def get_content_type(self):
        value = self._h.get("content-type") or "text/plain"
        return value.split(";", 1)[0].strip().lower()

    def get_content_charset(self, failobj=None):
        found = _net.charset_of(self._h.get("content-type"))
        return found.lower() if found else failobj

    def __str__(self):
        return "".join([f"{k}: {v}\n" for k, v in self._h.items()]) + "\n"


class HTTPResponse:
    """What urlopen returns: read(), status, reason, headers, getcode(),
    geturl(), getheader(name), and `with urlopen(...) as reply:`."""

    def __init__(self, reply, url):
        self._data, _ = _net.content_of(reply)
        self._at = 0
        self.status = int(reply.get("status") or 0)
        self.code = self.status
        self.reason = reply.get("reason") or _net.reason_for(self.status)
        self.msg = self.reason
        self.headers = HTTPMessage(reply.get("headers") or {})
        self.url = reply.get("url") or url
        self.closed = False
        self.version = 11

    def read(self, amt=None):
        """The body as bytes: all of what is left, or at most amt bytes."""
        if amt is None or amt < 0:
            out = self._data[self._at:]
            self._at = len(self._data)
            return out
        out = self._data[self._at:self._at + amt]
        self._at += len(out)
        return out

    def read1(self, amt=-1):
        return self.read(None if amt is None or amt < 0 else amt)

    def readline(self, limit=-1):
        rest = self._data[self._at:]
        at = rest.find(b"\n")
        end = len(rest) if at < 0 else at + 1
        if limit is not None and limit >= 0:
            end = min(end, limit)
        self._at += end
        return rest[:end]

    def readlines(self, hint=-1):
        lines = []
        while self._at < len(self._data):
            lines.append(self.readline())
        return lines

    def __iter__(self):
        return iter(self.readlines())

    def getcode(self):
        return self.status

    def geturl(self):
        return self.url

    def info(self):
        return self.headers

    def getheader(self, name, default=None):
        return self.headers.get(name, default)

    def getheaders(self):
        return self.headers.items()

    def close(self):
        self.closed = True

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def _error(kind, message):
    if kind == "timeout":
        return URLError(TimeoutError(message))
    return URLError(message)


def urlopen(url, data=None, timeout=None, context=None, then=None, on_error=None):
    """Opens [url] (text or a Request) and returns the reply. data= (bytes)
    is sent as the body, making it a POST. A status of 400 or more raises
    urllib.error.HTTPError; no answer raises urllib.error.URLError. With
    then=fn the call returns at once and fn(reply) runs later (an HTTPError
    reply too)."""
    req = url if isinstance(url, Request) else Request(url, data)
    if data is not None:
        req.data = data
    body = req.data
    headers = dict(req.unredirected_hdrs)
    headers.update(req.headers)
    if body is not None:
        if isinstance(body, str):
            raise TypeError("POST data should be bytes, an iterable of bytes, or a file object. It cannot be of type str.")
        body = _net.as_bytes(body)
        if not any(k.lower() == "content-type" for k in headers):
            headers["Content-Type"] = "application/x-www-form-urlencoded"
    _net.check_url(req.full_url, lambda kind, message: URLError(message))
    seconds = _net.timeout_of(timeout, None, "urllib.request")

    def done(reply, elapsed):
        r = HTTPResponse(reply, req.full_url)
        if r.status >= 400 and then is None:
            raise HTTPError(r.url, r.status, r.reason, r.headers, r)
        return r

    return _net.send(req.get_method(), req.full_url, headers, body, seconds, "urllib.request", _error,
                     then=then, on_error=on_error, done=done)


def build_opener(*handlers):
    """An opener whose open() is urlopen: the keyboard sends every request itself."""
    return OpenerDirector()


class OpenerDirector:
    """What build_opener gives: open(url, data=None, timeout=None)."""

    def __init__(self):
        self.addheaders = [("User-agent", "Python-urllib/3.12")]

    def add_handler(self, handler):
        pass

    def open(self, fullurl, data=None, timeout=None):
        req = fullurl if isinstance(fullurl, Request) else Request(fullurl, data)
        for key, value in self.addheaders:
            if not req.has_header(key.capitalize()):
                req.add_unredirected_header(key, value)
        return urlopen(req, data, timeout)


def install_opener(opener):
    """Changes nothing: the keyboard sends every request itself."""
    return None
