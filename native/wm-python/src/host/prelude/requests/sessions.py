"""requests.Session: headers, params and auth kept from one request to the
next, the cookies replies set, and `with requests.Session() as s:`."""

from wm import _net
from requests.structures import CaseInsensitiveDict
from requests.cookies import RequestsCookieJar
from requests.models import PreparedRequest, Request, build_response
from requests.exceptions import ConnectionError, ConnectTimeout, InvalidSchema, InvalidURL, MissingSchema, ReadTimeout, TooManyRedirects

DEFAULT_REDIRECT_LIMIT = 30


def _error(kind, message):
    if kind == "timeout":
        return ReadTimeout(message)
    if kind == "connect_timeout":
        return ConnectTimeout(message)
    if kind == "redirects":
        return TooManyRedirects(message)
    if kind == "schema":
        return InvalidSchema(message)
    if kind == "missing_schema":
        return MissingSchema(message)
    if kind == "url":
        return InvalidURL(message)
    return ConnectionError(message)


def _merge(request_value, session_value):
    """A request's dict over the session's: a None value takes the session's key out."""
    if not session_value:
        return request_value
    if request_value is None:
        return session_value
    if isinstance(request_value, (str, bytes)):
        return _net.urlencode(session_value) + "&" + _net.text_of(request_value)
    merged = CaseInsensitiveDict(session_value) if isinstance(session_value, CaseInsensitiveDict) else dict(session_value.items())
    for k, v in (request_value.items() if hasattr(request_value, "items") else request_value):
        merged[k] = v
    for k in [k for k, v in merged.items() if v is None]:
        del merged[k]
    return merged


class Session:
    """Settings and cookies shared by several requests:

        with requests.Session() as s:
            s.headers["Authorization"] = "Bearer " + token
            r = s.get("https://api.example.com/me")
    """

    def __init__(self):
        self.headers = CaseInsensitiveDict({"Accept": "*/*"})
        self.auth = None
        self.proxies = {}
        self.hooks = {"response": []}
        self.params = {}
        self.stream = False
        self.verify = True
        self.cert = None
        self.max_redirects = DEFAULT_REDIRECT_LIMIT
        self.trust_env = True
        self.cookies = RequestsCookieJar()
        self.adapters = {}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    def close(self):
        pass

    def mount(self, prefix, adapter):
        """Kept for code that mounts an adapter; the keyboard sends every request itself."""
        self.adapters[prefix] = adapter

    def get_adapter(self, url):
        for prefix, adapter in self.adapters.items():
            if str(url).lower().startswith(prefix.lower()):
                return adapter
        from requests.adapters import HTTPAdapter
        return HTTPAdapter()

    def prepare_request(self, request):
        """[request] with the session's headers, params, auth and hooks added."""
        auth = request.auth
        if auth is None and self.auth is not None:
            auth = self.auth
        merged_hooks = {"response": list(self.hooks.get("response", [])) + list((request.hooks or {}).get("response", []))}
        p = PreparedRequest()
        p.prepare(
            method=str(request.method).upper(),
            url=request.url,
            files=request.files,
            data=request.data,
            json=request.json,
            headers=_merge(request.headers, self.headers),
            params=_merge(request.params, self.params),
            auth=auth,
            cookies=request.cookies,
            hooks=merged_hooks,
        )
        return p

    def request(self, method, url, params=None, data=None, headers=None, cookies=None, files=None, auth=None, timeout=None,
                allow_redirects=True, proxies=None, hooks=None, stream=None, verify=None, cert=None, json=None, then=None, on_error=None):
        """Sends one request. With then=fn the call returns at once and
        fn(response) runs when the reply comes (on_error=fn hears a failure):
        the panel keeps working meanwhile."""
        req = Request(method=str(method).upper(), url=url, headers=headers, files=files, data=data or {}, json=json,
                      params=params or {}, auth=auth, cookies=cookies, hooks=hooks)
        prep = self.prepare_request(req)
        return self.send(prep, timeout=timeout, allow_redirects=allow_redirects, proxies=proxies, stream=stream, verify=verify,
                         cert=cert, then=then, on_error=on_error)

    def send(self, request, timeout=None, allow_redirects=True, proxies=None, stream=None, verify=None, cert=None, then=None, on_error=None, **kwargs):
        """Sends a PreparedRequest and returns its Response."""
        if verify is False or self.verify is False:
            _net.note("verify", "requests: verify=False changes nothing: the keyboard always checks certificates")
        if proxies or self.proxies:
            _net.note("proxies", "requests: proxies= is not used: plugins connect only through the keyboard")
        if cert is not None or self.cert is not None:
            _net.note("cert", "requests: cert= is not used: plugins cannot send a client certificate")
        if allow_redirects is False:
            _net.note("redirects", "requests: allow_redirects=False changes nothing: the keyboard follows up to 5 redirects itself")
        if request._cookies or len(self.cookies) > 0:
            _net.note("cookies", "requests: the keyboard sends no cookies with a plugin's requests, so cookies kept in a session or given as cookies= are not sent")
        seconds = _net.timeout_of(timeout, None, "requests")
        hooks = request.hooks or {}

        def done(reply, elapsed):
            r = build_response(reply, request, elapsed)
            self.cookies.update(r.cookies)
            for hook in hooks.get("response", []):
                changed = hook(r)
                if changed is not None:
                    r = changed
            return r

        return _net.send(request.method, request.url, request.headers, request.body, seconds, "requests", _error,
                         then=then, on_error=on_error, done=done)

    def get(self, url, **kwargs):
        """A GET request. params= adds a query string."""
        return self.request("GET", url, **kwargs)

    def options(self, url, **kwargs):
        """An OPTIONS request, which the keyboard does not send: raises ValueError."""
        return self.request("OPTIONS", url, **kwargs)

    def head(self, url, **kwargs):
        """A HEAD request: the reply's status and headers, no body."""
        return self.request("HEAD", url, **kwargs)

    def post(self, url, data=None, json=None, **kwargs):
        """A POST request: data= sends a form, text or bytes, json= sends JSON, files= a multipart upload."""
        return self.request("POST", url, data=data, json=json, **kwargs)

    def put(self, url, data=None, **kwargs):
        """A PUT request, with data= or json= as for post."""
        return self.request("PUT", url, data=data, **kwargs)

    def patch(self, url, data=None, **kwargs):
        """A PATCH request, with data= or json= as for post."""
        return self.request("PATCH", url, data=data, **kwargs)

    def delete(self, url, **kwargs):
        """A DELETE request."""
        return self.request("DELETE", url, **kwargs)


def session():
    """A new Session: the same as Session()."""
    return Session()
