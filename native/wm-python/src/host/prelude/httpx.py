"""httpx, built into WM Keyboard's plugins: its synchronous API, sent
through the keyboard.

    import httpx

    with httpx.Client(base_url="https://api.example.com", timeout=10) as client:
        r = client.get("/items", params={"q": "milk"})
        r.raise_for_status()
        items = r.json()

Needs the `network` permission and each host in plugin.json's `hosts`.
https only, replies up to 1 MB, 15 seconds at most; the keyboard always
follows redirects and sends no cookies. There is no AsyncClient: pass
then=fn to get the Response later instead of waiting for it.
"""

import datetime as _datetime
import json as _json
from wm import _net

__version__ = "0.28.1"


class HTTPError(Exception):
    """Every httpx error below, but InvalidURL."""

    def __init__(self, message, request=None):
        super().__init__(message)
        self.message = message
        self.request = request


class RequestError(HTTPError):
    """The request did not get an answer."""


class TransportError(RequestError):
    """The request could not be sent or answered."""


class TimeoutException(TransportError):
    """No reply in time."""


class ConnectTimeout(TimeoutException):
    """The connection took too long."""


class ReadTimeout(TimeoutException):
    """The host sent nothing in time."""


class WriteTimeout(TimeoutException):
    """Sending took too long."""


class PoolTimeout(TimeoutException):
    """No connection was free in time."""


class NetworkError(TransportError):
    """The network failed."""


class ConnectError(NetworkError):
    """The host could not be reached: no network, Data saver, or the reply was over 1 MB."""


class ReadError(NetworkError):
    """Reading the reply failed."""


class WriteError(NetworkError):
    """Sending the request failed."""


class CloseError(NetworkError):
    """Closing the connection failed."""


class ProtocolError(TransportError):
    """The host broke the HTTP protocol."""


class ProxyError(TransportError):
    """A proxy failed."""


class UnsupportedProtocol(TransportError):
    """The address's scheme is not https."""


class DecodingError(RequestError):
    """The reply could not be decoded."""


class TooManyRedirects(RequestError):
    """More redirects than the keyboard follows (5)."""


class HTTPStatusError(HTTPError):
    """The status was not 2xx: from r.raise_for_status()."""

    def __init__(self, message, request=None, response=None):
        super().__init__(message, request=request)
        self.response = response


class InvalidURL(Exception):
    """The address is not one httpx can request."""


class StreamError(RuntimeError):
    """A stream was used the wrong way."""


def _error(kind, message):
    if kind == "timeout":
        return ReadTimeout(message)
    if kind == "redirects":
        return TooManyRedirects(message)
    if kind == "schema" or kind == "missing_schema":
        return UnsupportedProtocol(message)
    if kind == "url":
        return InvalidURL(message)
    return ConnectError(message)


class _Codes:
    """httpx.codes: codes.OK == 200, codes.NOT_FOUND == 404."""

    def __init__(self):
        for code, name, _ in _net.STATUSES:
            setattr(self, name, code)

    def get_reason_phrase(self, value):
        return _net.reason_for(value)

    def is_informational(self, value):
        return 100 <= value <= 199

    def is_success(self, value):
        return 200 <= value <= 299

    def is_redirect(self, value):
        return 300 <= value <= 399

    def is_client_error(self, value):
        return 400 <= value <= 499

    def is_server_error(self, value):
        return 500 <= value <= 599

    def is_error(self, value):
        return 400 <= value <= 599


codes = _Codes()


class Timeout:
    """How long to wait: Timeout(10.0), or Timeout(10.0, connect=5.0). The
    keyboard uses the longest of them, 15 seconds at most."""

    def __init__(self, timeout=5.0, connect=None, read=None, write=None, pool=None):
        if isinstance(timeout, Timeout):
            connect, read, write, pool, timeout = timeout.connect, timeout.read, timeout.write, timeout.pool, None
        elif isinstance(timeout, (tuple, list)):
            parts = list(timeout) + [None, None, None, None]
            connect, read, write, pool, timeout = parts[0], parts[1], parts[2], parts[3], None
        self.connect = connect if connect is not None else timeout
        self.read = read if read is not None else timeout
        self.write = write if write is not None else timeout
        self.pool = pool if pool is not None else timeout

    def _seconds(self):
        parts = [p for p in (self.connect, self.read, self.write, self.pool) if p is not None]
        return max(parts) if parts else None

    def __repr__(self):
        return f"Timeout(connect={self.connect}, read={self.read}, write={self.write}, pool={self.pool})"


DEFAULT_TIMEOUT = 5.0


def _seconds(timeout):
    if isinstance(timeout, Timeout):
        timeout = timeout._seconds()
    return _net.timeout_of(timeout, None, "httpx")


class Auth:
    """What auth= takes besides a (user, password) pair."""

    def auth_flow(self, request):
        return request


class BasicAuth(Auth):
    """HTTP Basic authentication: BasicAuth("user", "password")."""

    def __init__(self, username, password):
        self._header = _net.basic_auth(username, password)

    def auth_flow(self, request):
        request.headers["Authorization"] = self._header
        return request


class URL:
    """An address, read into its parts: url.host, url.path, url.params."""

    def __init__(self, url="", params=None):
        import urllib.parse as parse
        self._text = _net.with_params(str(url), params) if params else str(url)
        parts = parse.urlsplit(self._text)
        self.scheme = parts[0]
        self.netloc = parts[1].encode("utf-8")
        self.path = parts[2] or "/"
        self.query = parts[3].encode("utf-8")
        self.fragment = parts[4]
        self.host = _net.host_of(self._text)
        self.port = None
        rest = parts[1].rsplit("@", 1)[-1]
        if ":" in rest and not rest.endswith("]"):
            tail = rest.rsplit(":", 1)[1]
            if tail.isdigit():
                self.port = int(tail)
        self.params = QueryParams(parts[3])

    @property
    def raw_path(self):
        query = self.query.decode("utf-8")
        return (self.path + ("?" + query if query else "")).encode("utf-8")

    def join(self, url):
        import urllib.parse as parse
        return URL(parse.urljoin(self._text, str(url)))

    def copy_with(self, **kwargs):
        return URL(self._text)

    def __str__(self):
        return self._text

    def __repr__(self):
        return f"URL('{self._text}')"

    def __eq__(self, other):
        return str(other) == self._text

    def __ne__(self, other):
        return not self.__eq__(other)


class QueryParams:
    """A query string's values by name."""

    def __init__(self, query=""):
        import urllib.parse as parse
        self._pairs = parse.parse_qsl(query) if isinstance(query, str) else _net.pairs_of(query)

    def get(self, key, default=None):
        for k, v in self._pairs:
            if k == key:
                return v
        return default

    def get_list(self, key):
        return [v for k, v in self._pairs if k == key]

    def __getitem__(self, key):
        for k, v in self._pairs:
            if k == key:
                return v
        raise KeyError(key)

    def __contains__(self, key):
        return any(k == key for k, _ in self._pairs)

    def keys(self):
        return [k for k, _ in self._pairs]

    def items(self):
        return list(self._pairs)

    def __str__(self):
        return _net.urlencode(self._pairs)


Headers = _net.CaseInsensitiveDict


class Request:
    """A request as it is sent: method, url (a URL), headers, content."""

    def __init__(self, method, url, params=None, headers=None, content=None, data=None, files=None, json=None):
        self.method = str(method).upper()
        self.url = url if isinstance(url, URL) else URL(url, params=params)
        self.headers = Headers(headers or {})
        body, kind = _body(content, data, files, json)
        if kind and "content-type" not in self.headers:
            self.headers["Content-Type"] = kind
        self.content = body if body is not None else b""
        self._body = body

    def read(self):
        return _net.as_bytes(self.content) if self.content else b""

    def __repr__(self):
        return f"<Request('{self.method}', '{self.url}')>"


def _body(content, data, files, json):
    if content is not None:
        return (content if isinstance(content, (str, bytes)) else _net.as_bytes(content)), None
    if files:
        out = []
        items = files.items() if isinstance(files, dict) or hasattr(files, "items") else files
        for field, value in items:
            if isinstance(value, (tuple, list)):
                out.append((field, str(value[0] if value[0] is not None else field), _net.as_bytes(value[1]), value[2] if len(value) > 2 else None))
            else:
                out.append((field, str(field), _net.as_bytes(value), None))
        return _net.multipart(_net.pairs_of(data) if data else [], out)
    if data is not None and not isinstance(data, (str, bytes)):
        return _net.urlencode(data), "application/x-www-form-urlencoded"
    if data is not None:
        return data, None
    if json is not None:
        return _json.dumps(json).encode("utf-8"), "application/json"
    return None, None


class Response:
    """The reply: status_code, reason_phrase, headers, text, content,
    json(), is_success, is_error, raise_for_status(), url, elapsed."""

    def __init__(self, status_code=200, headers=None, content=None, text=None, json=None, request=None):
        self.status_code = status_code
        self.headers = Headers(headers or {})
        if json is not None:
            content = _json.dumps(json).encode("utf-8")
            self.headers.setdefault("Content-Type", "application/json")
        elif text is not None:
            content = str(text).encode("utf-8")
        self._content = _net.as_bytes(content) if content is not None else b""
        self._given = None
        self.reason_phrase = _net.reason_for(status_code)
        self.http_version = "HTTP/1.1"
        self.history = []
        self.elapsed = _datetime.timedelta(0)
        self._request = request
        self.url = request.url if request is not None else URL("")
        self.cookies = {}
        self.default_encoding = "utf-8"
        self._encoding = None

    def __repr__(self):
        return f"<Response [{self.status_code} {self.reason_phrase}]>"

    @property
    def request(self):
        if self._request is None:
            raise RuntimeError("The request instance has not been set on this response.")
        return self._request

    @property
    def content(self):
        return self._content

    @property
    def encoding(self):
        """The charset the Content-Type names, or UTF-8."""
        if self._encoding is not None:
            return self._encoding
        return _net.charset_of(self.headers.get("content-type")) or self.default_encoding

    @encoding.setter
    def encoding(self, value):
        self._encoding = value

    @property
    def charset_encoding(self):
        return _net.charset_of(self.headers.get("content-type"))

    @property
    def text(self):
        if self._given is not None and _net.is_utf8_name(self.encoding):
            return self._given
        if not self._content:
            return ""
        return _net.decode(self._content, self.encoding)

    def json(self, **kwargs):
        """The body read as JSON. Raises ValueError when it is not JSON."""
        return _json.loads(self.text)

    def read(self):
        return self._content

    def iter_bytes(self, chunk_size=None):
        data = self._content
        if not chunk_size or chunk_size >= len(data):
            return [data] if data else []
        return [data[i:i + chunk_size] for i in range(0, len(data), chunk_size)]

    def iter_text(self, chunk_size=None):
        text = self.text
        if not chunk_size or chunk_size >= len(text):
            return [text] if text else []
        return [text[i:i + chunk_size] for i in range(0, len(text), chunk_size)]

    def iter_lines(self):
        return self.text.splitlines()

    def iter_raw(self, chunk_size=None):
        return self.iter_bytes(chunk_size)

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    @property
    def is_informational(self):
        return codes.is_informational(self.status_code)

    @property
    def is_success(self):
        """True for a 2xx status."""
        return codes.is_success(self.status_code)

    @property
    def is_redirect(self):
        return codes.is_redirect(self.status_code)

    @property
    def is_client_error(self):
        return codes.is_client_error(self.status_code)

    @property
    def is_server_error(self):
        return codes.is_server_error(self.status_code)

    @property
    def is_error(self):
        """True for a 4xx or 5xx status."""
        return codes.is_error(self.status_code)

    @property
    def has_redirect_location(self):
        return self.status_code in (301, 302, 303, 307, 308) and "location" in self.headers

    @property
    def links(self):
        resolved = {}
        for link in _net.parse_links(self.headers.get("link")):
            resolved[link.get("rel") or link.get("url")] = link
        return resolved

    def raise_for_status(self):
        """Returns the response for a 2xx status; raises httpx.HTTPStatusError otherwise."""
        request = self.request
        if self.is_success:
            return self
        line = "{kind} '" + str(self.status_code) + " " + self.reason_phrase + "' for url '" + str(self.url) + "'\n"
        if self.has_redirect_location:
            line = line + "Redirect location: '" + str(self.headers.get("location")) + "'\n"
        message = line + "For more information check: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/" + str(self.status_code)
        kinds = {1: "Informational response", 3: "Redirect response", 4: "Client error", 5: "Server error"}
        kind = kinds.get(self.status_code // 100, "Invalid status code")
        raise HTTPStatusError(message.replace("{kind}", kind), request=request, response=self)


def _build(reply, request, seconds):
    r = Response(status_code=int(reply.get("status") or 0), headers=reply.get("headers") or {}, request=request)
    r._content, r._given = _net.content_of(reply)
    if reply.get("url"):
        r.url = URL(reply.get("url"))
    if reply.get("reason"):
        r.reason_phrase = reply.get("reason")
    r.elapsed = _datetime.timedelta(seconds=seconds)
    for c in _net.set_cookies(r.headers, r.url.host):
        if not c["expired"]:
            r.cookies[c["name"]] = c["value"]
    return r


def _auth_header(auth, request):
    if auth is None:
        return
    if isinstance(auth, (tuple, list)) and len(auth) == 2:
        auth = BasicAuth(auth[0], auth[1])
    if isinstance(auth, Auth):
        auth.auth_flow(request)
    elif callable(auth):
        auth(request)


def _send(request, timeout, auth, then, on_error, cookies, follow_redirects):
    if cookies:
        _net.note("httpx.cookies", "httpx: the keyboard sends no cookies with a plugin's requests, so cookies= is not sent")
    text = str(request.url)

    def make(kind, message):
        return _error(kind, message)

    _net.check_url(text, make)
    _auth_header(auth, request)
    return _net.send(request.method, text, request.headers, request._body, _seconds(timeout), "httpx", make,
                     then=then, on_error=on_error, done=lambda reply, seconds: _build(reply, request, seconds))


class Client:
    """Settings shared by several requests:

        with httpx.Client(base_url="https://api.example.com", headers={"Authorization": "Bearer " + token}) as client:
            r = client.get("/me")

    base_url= goes before each relative address; headers=, params=,
    auth= and timeout= (5 seconds unless given, 15 at most) apply to every
    request. The keyboard follows redirects whatever follow_redirects says."""

    def __init__(self, auth=None, params=None, headers=None, cookies=None, verify=True, cert=None, http1=True, http2=False,
                 proxy=None, mounts=None, timeout=DEFAULT_TIMEOUT, follow_redirects=False, limits=None, max_redirects=20,
                 event_hooks=None, base_url="", transport=None, trust_env=True, default_encoding="utf-8"):
        self.auth = auth
        self.params = QueryParams(params or {})
        self.headers = Headers({"Accept": "*/*"})
        self.headers.update(headers or {})
        self.cookies = dict(cookies or {})
        self.timeout = timeout
        self.follow_redirects = follow_redirects
        self.base_url = URL(base_url) if base_url else URL("")
        self.event_hooks = event_hooks or {"request": [], "response": []}
        self.is_closed = False
        if verify is False:
            _net.note("httpx.verify", "httpx: verify=False changes nothing: the keyboard always checks certificates")
        if proxy:
            _net.note("httpx.proxy", "httpx: proxy= is not used: plugins connect only through the keyboard")

    def _url(self, url):
        text = str(url)
        if "://" in text or not str(self.base_url):
            return text
        base = str(self.base_url)
        if not base.endswith("/"):
            base = base + "/"
        return base + text.lstrip("/")

    def build_request(self, method, url, content=None, data=None, files=None, json=None, params=None, headers=None, cookies=None, timeout=None, extensions=None):
        merged = Headers(self.headers.items())
        merged.update(headers or {})
        query = self.params.items() + _net.pairs_of(params)
        return Request(method, _net.with_params(self._url(url), query), headers=merged, content=content, data=data, files=files, json=json)

    def send(self, request, auth=None, follow_redirects=None, then=None, on_error=None, timeout=None):
        hooks = self.event_hooks
        for hook in hooks.get("request", []):
            hook(request)
        seconds = self.timeout if timeout is None else timeout

        def finish(response):
            for hook in hooks.get("response", []):
                hook(response)
            self.cookies.update(response.cookies)
            return response

        if then is not None:
            return _send(request, seconds, auth if auth is not None else self.auth, lambda r: _core_call(then, finish(r)), on_error,
                         self.cookies, follow_redirects)
        return finish(_send(request, seconds, auth if auth is not None else self.auth, None, None, self.cookies, follow_redirects))

    def request(self, method, url, content=None, data=None, files=None, json=None, params=None, headers=None, cookies=None,
                auth=None, follow_redirects=None, timeout=None, extensions=None, then=None, on_error=None):
        """Sends one request. With then=fn the call returns at once and
        fn(response) runs when the reply comes (on_error=fn hears a failure)."""
        request = self.build_request(method, url, content=content, data=data, files=files, json=json, params=params, headers=headers)
        if cookies:
            _net.note("httpx.cookies", "httpx: the keyboard sends no cookies with a plugin's requests, so cookies= is not sent")
        return self.send(request, auth=auth, follow_redirects=follow_redirects, then=then, on_error=on_error, timeout=timeout)

    def get(self, url, params=None, headers=None, cookies=None, auth=None, follow_redirects=None, timeout=None, extensions=None, then=None, on_error=None):
        """A GET request."""
        return self.request("GET", url, params=params, headers=headers, cookies=cookies, auth=auth, timeout=timeout, then=then, on_error=on_error)

    def head(self, url, params=None, headers=None, cookies=None, auth=None, follow_redirects=None, timeout=None, extensions=None, then=None, on_error=None):
        """A HEAD request."""
        return self.request("HEAD", url, params=params, headers=headers, cookies=cookies, auth=auth, timeout=timeout, then=then, on_error=on_error)

    def options(self, url, params=None, headers=None, cookies=None, auth=None, follow_redirects=None, timeout=None, extensions=None, then=None, on_error=None):
        """An OPTIONS request, which the keyboard does not send: raises ValueError."""
        return self.request("OPTIONS", url, params=params, headers=headers, cookies=cookies, auth=auth, timeout=timeout, then=then, on_error=on_error)

    def post(self, url, content=None, data=None, files=None, json=None, params=None, headers=None, cookies=None, auth=None,
             follow_redirects=None, timeout=None, extensions=None, then=None, on_error=None):
        """A POST request: content= bytes or text, data= a form, files= an upload, json= JSON."""
        return self.request("POST", url, content=content, data=data, files=files, json=json, params=params, headers=headers,
                            cookies=cookies, auth=auth, timeout=timeout, then=then, on_error=on_error)

    def put(self, url, content=None, data=None, files=None, json=None, params=None, headers=None, cookies=None, auth=None,
            follow_redirects=None, timeout=None, extensions=None, then=None, on_error=None):
        """A PUT request, with a body as for post."""
        return self.request("PUT", url, content=content, data=data, files=files, json=json, params=params, headers=headers,
                            cookies=cookies, auth=auth, timeout=timeout, then=then, on_error=on_error)

    def patch(self, url, content=None, data=None, files=None, json=None, params=None, headers=None, cookies=None, auth=None,
              follow_redirects=None, timeout=None, extensions=None, then=None, on_error=None):
        """A PATCH request, with a body as for post."""
        return self.request("PATCH", url, content=content, data=data, files=files, json=json, params=params, headers=headers,
                            cookies=cookies, auth=auth, timeout=timeout, then=then, on_error=on_error)

    def delete(self, url, params=None, headers=None, cookies=None, auth=None, follow_redirects=None, timeout=None, extensions=None, then=None, on_error=None):
        """A DELETE request."""
        return self.request("DELETE", url, params=params, headers=headers, cookies=cookies, auth=auth, timeout=timeout, then=then, on_error=on_error)

    def close(self):
        self.is_closed = True

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def _core_call(fn, value):
    from wm import _core
    return _core.call_back(fn, value)


def request(method, url, params=None, content=None, data=None, files=None, json=None, headers=None, cookies=None, auth=None,
            proxy=None, timeout=DEFAULT_TIMEOUT, follow_redirects=False, verify=True, trust_env=True, then=None, on_error=None):
    """Sends one request and returns its Response. then=fn returns at once
    and calls fn(response) later."""
    with Client(cookies=cookies, proxy=proxy, verify=verify, timeout=timeout, follow_redirects=follow_redirects) as client:
        return client.request(method, url, content=content, data=data, files=files, json=json, params=params, headers=headers,
                              auth=auth, then=then, on_error=on_error)


def get(url, params=None, headers=None, cookies=None, auth=None, proxy=None, follow_redirects=False, verify=True, timeout=DEFAULT_TIMEOUT, trust_env=True, then=None, on_error=None):
    """A GET request. params= adds a query string."""
    return request("GET", url, params=params, headers=headers, cookies=cookies, auth=auth, proxy=proxy, follow_redirects=follow_redirects,
                   verify=verify, timeout=timeout, then=then, on_error=on_error)


def head(url, params=None, headers=None, cookies=None, auth=None, proxy=None, follow_redirects=False, verify=True, timeout=DEFAULT_TIMEOUT, trust_env=True, then=None, on_error=None):
    """A HEAD request."""
    return request("HEAD", url, params=params, headers=headers, cookies=cookies, auth=auth, proxy=proxy, follow_redirects=follow_redirects,
                   verify=verify, timeout=timeout, then=then, on_error=on_error)


def options(url, params=None, headers=None, cookies=None, auth=None, proxy=None, follow_redirects=False, verify=True, timeout=DEFAULT_TIMEOUT, trust_env=True, then=None, on_error=None):
    """An OPTIONS request, which the keyboard does not send: raises ValueError."""
    return request("OPTIONS", url, params=params, headers=headers, cookies=cookies, auth=auth, proxy=proxy, follow_redirects=follow_redirects,
                   verify=verify, timeout=timeout, then=then, on_error=on_error)


def post(url, content=None, data=None, files=None, json=None, params=None, headers=None, cookies=None, auth=None, proxy=None,
         follow_redirects=False, verify=True, timeout=DEFAULT_TIMEOUT, trust_env=True, then=None, on_error=None):
    """A POST request: content= bytes or text, data= a form, files= an upload, json= JSON."""
    return request("POST", url, params=params, content=content, data=data, files=files, json=json, headers=headers, cookies=cookies,
                   auth=auth, proxy=proxy, follow_redirects=follow_redirects, verify=verify, timeout=timeout, then=then, on_error=on_error)


def put(url, content=None, data=None, files=None, json=None, params=None, headers=None, cookies=None, auth=None, proxy=None,
        follow_redirects=False, verify=True, timeout=DEFAULT_TIMEOUT, trust_env=True, then=None, on_error=None):
    """A PUT request, with a body as for post."""
    return request("PUT", url, params=params, content=content, data=data, files=files, json=json, headers=headers, cookies=cookies,
                   auth=auth, proxy=proxy, follow_redirects=follow_redirects, verify=verify, timeout=timeout, then=then, on_error=on_error)


def patch(url, content=None, data=None, files=None, json=None, params=None, headers=None, cookies=None, auth=None, proxy=None,
          follow_redirects=False, verify=True, timeout=DEFAULT_TIMEOUT, trust_env=True, then=None, on_error=None):
    """A PATCH request, with a body as for post."""
    return request("PATCH", url, params=params, content=content, data=data, files=files, json=json, headers=headers, cookies=cookies,
                   auth=auth, proxy=proxy, follow_redirects=follow_redirects, verify=verify, timeout=timeout, then=then, on_error=on_error)


def delete(url, params=None, headers=None, cookies=None, auth=None, proxy=None, follow_redirects=False, verify=True, timeout=DEFAULT_TIMEOUT, trust_env=True, then=None, on_error=None):
    """A DELETE request."""
    return request("DELETE", url, params=params, headers=headers, cookies=cookies, auth=auth, proxy=proxy, follow_redirects=follow_redirects,
                   verify=verify, timeout=timeout, then=then, on_error=on_error)
