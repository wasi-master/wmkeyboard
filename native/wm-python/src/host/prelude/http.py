"""HTTPS requests, shaped like `requests`.

    r = http.get("https://api.example.com/v1/items", params={"q": "milk"})
    r.raise_for_status()
    items = r.json()["items"]

Needs the `network` permission, and the address's host has to be listed in
the manifest's `hosts` (`api.example.com`, or `*.example.com`). Only https,
replies up to 1 MB, 15 seconds at most. Every request appears in the
keyboard's Network activity under the plugin's name, and none is sent while
Data saver is on.

Pass then=fn to get the answer later instead of waiting for it: the call
returns at once and fn receives the Response (on_error=fn hears a failure).
"""

import json as _json
from wm import _core


class HTTPError(OSError):
    """A reply whose status is 400 or more, from raise_for_status()."""

    def __init__(self, message, response=None):
        OSError.__init__(self, message)
        self.response = response


class Response:
    def __init__(self, data):
        self.status_code = data.get("status", 0)
        self.status = self.status_code
        self.headers = {str(k).lower(): v for k, v in (data.get("headers") or {}).items()}
        self.url = data.get("url", "")
        self.text = data.get("text", "")
        self._content = data.get("content")
        self.reason = data.get("reason", "")

    @property
    def ok(self):
        return self.status_code < 400

    @property
    def content(self):
        if self._content is not None:
            import base64
            return base64.b64decode(self._content)
        return self.text.encode("utf-8")

    def json(self):
        return _json.loads(self.text)

    def raise_for_status(self):
        if self.status_code >= 400:
            raise HTTPError(f"{self.status_code} {self.reason} for {self.url}".strip(), self)
        return self

    def __bool__(self):
        return self.ok

    def __repr__(self):
        return f"<Response [{self.status_code}]>"


def request(method, url, params=None, data=None, json=None, headers=None, timeout=15, binary=False, then=None, on_error=None):
    """Send one request. [data] is a str, bytes or a dict sent as a form;
    [json] is any value sent as JSON. [binary] keeps the reply's bytes in
    Response.content."""
    args = {"method": str(method).upper(), "url": str(url), "timeout": min(float(timeout), 15.0), "binary": bool(binary)}
    if params:
        args["params"] = {str(k): str(v) for k, v in params.items()}
    if headers:
        args["headers"] = {str(k): str(v) for k, v in headers.items()}
    if json is not None:
        args["body"] = _json.dumps(json)
        args.setdefault("headers", {})["Content-Type"] = "application/json"
    elif isinstance(data, dict):
        args["form"] = {str(k): str(v) for k, v in data.items()}
    elif isinstance(data, bytes):
        args["bodyBytes"] = data
    elif data is not None:
        args["body"] = str(data)
    if then is not None:
        return _core.defer("http.request", args, then, on_error, Response)
    return Response(_core.call("http.request", args))


def get(url, params=None, **options):
    return request("GET", url, params=params, **options)


def post(url, data=None, json=None, **options):
    return request("POST", url, data=data, json=json, **options)


def put(url, data=None, json=None, **options):
    return request("PUT", url, data=data, json=json, **options)


def patch(url, data=None, json=None, **options):
    return request("PATCH", url, data=data, json=json, **options)


def delete(url, **options):
    return request("DELETE", url, **options)
