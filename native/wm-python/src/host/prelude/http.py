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

Pass files= for a multipart form, the way a web page uploads a file:

    http.post(url, data={"code": "123"}, files={"photo": ("front.jpg", jpeg, "image/jpeg")})
"""

import json as _json
from wm import _core


class HTTPError(OSError):
    """A reply whose status is 400 or more, from raise_for_status()."""

    def __init__(self, message, response=None):
        super().__init__(message)
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


def _part_bytes(value):
    if isinstance(value, bytes):
        return value
    return str(value).encode("utf-8")


def _quoted(name):
    # As browsers write a form: a quote or a line break is percent-escaped.
    return str(name).replace('"', "%22").replace("\r", "%0D").replace("\n", "%0A")


def _file_part(field, value):
    """(file name, bytes, type) from what files= takes for one field: bytes,
    (name, content), (name, content, type), or what files.pick gives."""
    if isinstance(value, dict):
        return str(value.get("name") or field), _part_bytes(value.get("data", b"")), value.get("mime") or "application/octet-stream"
    if isinstance(value, (tuple, list)):
        if len(value) < 2 or len(value) > 3:
            raise ValueError("a file is (name, content) or (name, content, type)")
        mime = value[2] if len(value) == 3 and value[2] else "application/octet-stream"
        return str(value[0]), _part_bytes(value[1]), str(mime)
    return str(field), _part_bytes(value), "application/octet-stream"


def _multipart(data, files):
    """A multipart/form-data body: [data]'s fields as text, then [files]."""
    import random
    boundary = "wmform" + "".join([random.choice("0123456789abcdef") for _ in range(24)])
    dash = ("--" + boundary + "\r\n").encode("utf-8")
    parts = []
    for key, value in (data or {}).items():
        parts.append(dash)
        parts.append(('Content-Disposition: form-data; name="' + _quoted(key) + '"\r\n\r\n').encode("utf-8"))
        parts.append(_part_bytes(value))
        parts.append(b"\r\n")
    for key, value in files.items():
        name, content, mime = _file_part(key, value)
        parts.append(dash)
        parts.append(('Content-Disposition: form-data; name="' + _quoted(key) + '"; filename="' + _quoted(name) + '"\r\n').encode("utf-8"))
        parts.append(("Content-Type: " + mime + "\r\n\r\n").encode("utf-8"))
        parts.append(content)
        parts.append(b"\r\n")
    parts.append(("--" + boundary + "--\r\n").encode("utf-8"))
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


def request(method, url, params=None, data=None, json=None, headers=None, timeout=15, binary=False, then=None, on_error=None, files=None):
    """Send one request. [data] is a str, bytes or a dict sent as a form;
    [json] is any value sent as JSON; [files] makes the form multipart,
    {"field": (name, bytes, type)}, with a [data] dict as its text fields.
    [binary] keeps the reply's bytes in Response.content."""
    args = {"method": str(method).upper(), "url": str(url), "timeout": min(float(timeout), 15.0), "binary": bool(binary)}
    if params:
        args["params"] = {str(k): str(v) for k, v in params.items()}
    if headers:
        args["headers"] = {str(k): str(v) for k, v in headers.items()}
    if files:
        if json is not None or (data is not None and not isinstance(data, dict)):
            raise ValueError("files= goes with a dict of data=, not json= or a body")
        body, kind = _multipart(data, files)
        headers = {k: v for k, v in args.get("headers", {}).items() if k.lower() != "content-type"}
        headers["Content-Type"] = kind
        args["headers"] = headers
        args["bodyBytes"] = body
    elif json is not None:
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
