"""What requests, httpx and urllib.request share. Internal: plugins import
those, not this.

Each of them builds a request its own way; this sends it as the keyboard's
one `http.request` call, turns the keyboard's refusals into errors that say
what to change in plugin.json, and reads the reply's text, headers, cookies
and links the way CPython's libraries read them."""

import re as _re
import time as _time
import urllib.parse as _parse
import _wm
from wm import _core

# The keyboard's limits on one request.
MAX_TIMEOUT = 15.0
MAX_REDIRECTS = 5
METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

_noted = {}


def note(key, line):
    """Writes [line] to the plugin's log, once per run."""
    if key in _noted:
        return
    _noted[key] = True
    _wm.log(line)


class CaseInsensitiveDict:
    """A dict whose keys are looked up whatever their case: headers["Content-Type"]
    and headers["content-type"] are the same entry. Iterating gives each key
    as it was last set."""

    def __init__(self, data=None, **kwargs):
        self._store = {}
        if data is not None:
            self.update(data)
        if kwargs:
            self.update(kwargs)

    def __setitem__(self, key, value):
        self._store[str(key).lower()] = (key, value)

    def __getitem__(self, key):
        found = self._store.get(str(key).lower())
        if found is None:
            raise KeyError(key)
        return found[1]

    def __delitem__(self, key):
        lower = str(key).lower()
        if lower not in self._store:
            raise KeyError(key)
        del self._store[lower]

    def __contains__(self, key):
        return str(key).lower() in self._store

    def __iter__(self):
        return iter([k for k, _ in self._store.values()])

    def __len__(self):
        return len(self._store)

    def __eq__(self, other):
        if not hasattr(other, "items"):
            return False
        mine = {k: v for k, v in self.lower_items()}
        theirs = {str(k).lower(): v for k, v in other.items()}
        return mine == theirs

    def __ne__(self, other):
        return not self.__eq__(other)

    def __repr__(self):
        return str(dict(self.items()))

    def keys(self):
        return [k for k, _ in self._store.values()]

    def values(self):
        return [v for _, v in self._store.values()]

    def items(self):
        return [(k, v) for k, v in self._store.values()]

    def lower_items(self):
        """(lowercase key, value) pairs."""
        return [(lower, kv[1]) for lower, kv in self._store.items()]

    def get(self, key, default=None):
        found = self._store.get(str(key).lower())
        return default if found is None else found[1]

    def setdefault(self, key, default=None):
        if key not in self:
            self[key] = default
        return self[key]

    def pop(self, key, *default):
        lower = str(key).lower()
        if lower in self._store:
            return self._store.pop(lower)[1]
        if default:
            return default[0]
        raise KeyError(key)

    def update(self, other=None, **kwargs):
        if other is not None:
            pairs = other.items() if hasattr(other, "items") else other
            for k, v in pairs:
                self[k] = v
        for k, v in kwargs.items():
            self[k] = v

    def clear(self):
        self._store = {}

    def copy(self):
        return CaseInsensitiveDict(self.items())


# (code, name, phrase): http.HTTPStatus's names and phrases.
STATUSES = [
    (100, "CONTINUE", "Continue"),
    (101, "SWITCHING_PROTOCOLS", "Switching Protocols"),
    (102, "PROCESSING", "Processing"),
    (103, "EARLY_HINTS", "Early Hints"),
    (200, "OK", "OK"),
    (201, "CREATED", "Created"),
    (202, "ACCEPTED", "Accepted"),
    (203, "NON_AUTHORITATIVE_INFORMATION", "Non-Authoritative Information"),
    (204, "NO_CONTENT", "No Content"),
    (205, "RESET_CONTENT", "Reset Content"),
    (206, "PARTIAL_CONTENT", "Partial Content"),
    (207, "MULTI_STATUS", "Multi-Status"),
    (208, "ALREADY_REPORTED", "Already Reported"),
    (226, "IM_USED", "IM Used"),
    (300, "MULTIPLE_CHOICES", "Multiple Choices"),
    (301, "MOVED_PERMANENTLY", "Moved Permanently"),
    (302, "FOUND", "Found"),
    (303, "SEE_OTHER", "See Other"),
    (304, "NOT_MODIFIED", "Not Modified"),
    (305, "USE_PROXY", "Use Proxy"),
    (307, "TEMPORARY_REDIRECT", "Temporary Redirect"),
    (308, "PERMANENT_REDIRECT", "Permanent Redirect"),
    (400, "BAD_REQUEST", "Bad Request"),
    (401, "UNAUTHORIZED", "Unauthorized"),
    (402, "PAYMENT_REQUIRED", "Payment Required"),
    (403, "FORBIDDEN", "Forbidden"),
    (404, "NOT_FOUND", "Not Found"),
    (405, "METHOD_NOT_ALLOWED", "Method Not Allowed"),
    (406, "NOT_ACCEPTABLE", "Not Acceptable"),
    (407, "PROXY_AUTHENTICATION_REQUIRED", "Proxy Authentication Required"),
    (408, "REQUEST_TIMEOUT", "Request Timeout"),
    (409, "CONFLICT", "Conflict"),
    (410, "GONE", "Gone"),
    (411, "LENGTH_REQUIRED", "Length Required"),
    (412, "PRECONDITION_FAILED", "Precondition Failed"),
    (413, "REQUEST_ENTITY_TOO_LARGE", "Request Entity Too Large"),
    (414, "REQUEST_URI_TOO_LONG", "Request-URI Too Long"),
    (415, "UNSUPPORTED_MEDIA_TYPE", "Unsupported Media Type"),
    (416, "REQUESTED_RANGE_NOT_SATISFIABLE", "Requested Range Not Satisfiable"),
    (417, "EXPECTATION_FAILED", "Expectation Failed"),
    (418, "IM_A_TEAPOT", "I'm a teapot"),
    (421, "MISDIRECTED_REQUEST", "Misdirected Request"),
    (422, "UNPROCESSABLE_ENTITY", "Unprocessable Entity"),
    (423, "LOCKED", "Locked"),
    (424, "FAILED_DEPENDENCY", "Failed Dependency"),
    (425, "TOO_EARLY", "Too Early"),
    (426, "UPGRADE_REQUIRED", "Upgrade Required"),
    (428, "PRECONDITION_REQUIRED", "Precondition Required"),
    (429, "TOO_MANY_REQUESTS", "Too Many Requests"),
    (431, "REQUEST_HEADER_FIELDS_TOO_LARGE", "Request Header Fields Too Large"),
    (451, "UNAVAILABLE_FOR_LEGAL_REASONS", "Unavailable For Legal Reasons"),
    (500, "INTERNAL_SERVER_ERROR", "Internal Server Error"),
    (501, "NOT_IMPLEMENTED", "Not Implemented"),
    (502, "BAD_GATEWAY", "Bad Gateway"),
    (503, "SERVICE_UNAVAILABLE", "Service Unavailable"),
    (504, "GATEWAY_TIMEOUT", "Gateway Timeout"),
    (505, "HTTP_VERSION_NOT_SUPPORTED", "HTTP Version Not Supported"),
    (506, "VARIANT_ALSO_NEGOTIATES", "Variant Also Negotiates"),
    (507, "INSUFFICIENT_STORAGE", "Insufficient Storage"),
    (508, "LOOP_DETECTED", "Loop Detected"),
    (510, "NOT_EXTENDED", "Not Extended"),
    (511, "NETWORK_AUTHENTICATION_REQUIRED", "Network Authentication Required"),
]

_PHRASES = {code: phrase for code, _, phrase in STATUSES}


def reason_for(code):
    """The standard phrase for status [code], or "" for one without."""
    return _PHRASES.get(code, "")


def timeout_of(value, default, who):
    """The seconds the keyboard waits for [value], a number, a (connect, read)
    pair or None. The keyboard waits 15 seconds at most."""
    if value is None:
        value = default
    if isinstance(value, (tuple, list)):
        parts = [float(v) for v in value if v is not None]
        value = max(parts) if parts else None
    if value is None:
        return MAX_TIMEOUT
    seconds = float(value)
    if seconds > MAX_TIMEOUT:
        note("timeout", f"{who}: the keyboard waits 15 seconds at most for a reply, so timeout={value} counts as 15")
        return MAX_TIMEOUT
    return seconds


def text_of(value):
    """[value] as text: bytes read as UTF-8, anything else through str()."""
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return str(value)


def pairs_of(data):
    """(key, value) pairs of a dict, a list of pairs or anything with items(),
    a list value giving the key once for each item and a None value none."""
    if data is None:
        return []
    items = data.items() if isinstance(data, dict) or hasattr(data, "items") else data
    out = []
    for k, v in items:
        if v is None:
            continue
        values = v if isinstance(v, (list, tuple)) else [v]
        for one in values:
            if one is not None:
                out.append((text_of(k), text_of(one)))
    return out


def urlencode(data):
    """[data] as a query string or form body."""
    if isinstance(data, str):
        return data
    if isinstance(data, bytes):
        return data.decode("utf-8", "replace")
    return _parse.urlencode(pairs_of(data))


def with_params(url, params):
    """[url] with [params] added to its query, before any #fragment."""
    query = urlencode(params) if params else ""
    if not query:
        return url
    fragment = ""
    if "#" in url:
        url, fragment = url.split("#", 1)
        fragment = "#" + fragment
    if url.endswith("?") or url.endswith("&"):
        joiner = ""
    elif "?" in url:
        joiner = "&"
    else:
        joiner = "?"
    return url + joiner + query + fragment


def host_of(url):
    """The host an address names, lowercase, or "" when it names none."""
    rest = str(url).split("://", 1)
    if len(rest) < 2:
        return ""
    netloc = rest[1].split("/", 1)[0].split("?", 1)[0].split("#", 1)[0]
    netloc = netloc.rsplit("@", 1)[-1]
    if netloc.startswith("["):
        return netloc.split("]", 1)[0] + "]"
    return netloc.split(":", 1)[0].lower().rstrip(".")


def basic_auth(user, password):
    """The Authorization header value for HTTP Basic authentication."""
    import base64
    pair = text_of(user) + ":" + text_of(password)
    return "Basic " + base64.b64encode(pair.encode("utf-8")).decode("utf-8")


def quoted(name):
    # As browsers write a form: a quote or a line break is percent-escaped.
    return str(name).replace('"', "%22").replace("\r", "%0D").replace("\n", "%0A")


def as_bytes(value):
    if isinstance(value, bytes):
        return value
    if isinstance(value, bytearray):
        return bytes(value)
    reader = getattr(value, "read", None)
    if reader is not None:
        return as_bytes(reader())
    return str(value).encode("utf-8")


def multipart(fields, files):
    """A multipart/form-data body and its Content-Type: [fields] as text
    parts, then [files], each `(field, name, bytes, type or None)`."""
    import random
    boundary = "wmform" + "".join([random.choice("0123456789abcdef") for _ in range(24)])
    dash = ("--" + boundary + "\r\n").encode("utf-8")
    parts = []
    for key, value in fields:
        parts.append(dash)
        parts.append(('Content-Disposition: form-data; name="' + quoted(key) + '"\r\n\r\n').encode("utf-8"))
        parts.append(as_bytes(value))
        parts.append(b"\r\n")
    for field, name, content, kind in files:
        parts.append(dash)
        head = 'Content-Disposition: form-data; name="' + quoted(field) + '"; filename="' + quoted(name) + '"\r\n'
        if kind:
            head = head + "Content-Type: " + str(kind) + "\r\n"
        parts.append((head + "\r\n").encode("utf-8"))
        parts.append(content)
        parts.append(b"\r\n")
    parts.append(("--" + boundary + "--\r\n").encode("utf-8"))
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


# ---- reading replies ---------------------------------------------------------

_UTF8 = ("utf-8", "utf8", "u8", "utf", "us-ascii", "ascii", "646")
_LATIN1 = ("iso-8859-1", "iso8859-1", "latin-1", "latin1", "l1", "8859", "cp819", "iso-ir-100")
_CP1252 = ("windows-1252", "cp1252")
_CP1252_HIGH = {
    0x80: "€", 0x82: "‚", 0x83: "ƒ", 0x84: "„", 0x85: "…", 0x86: "†", 0x87: "‡",
    0x88: "ˆ", 0x89: "‰", 0x8a: "Š", 0x8b: "‹", 0x8c: "Œ", 0x8e: "Ž", 0x91: "‘",
    0x92: "’", 0x93: "“", 0x94: "”", 0x95: "•", 0x96: "–", 0x97: "—", 0x98: "˜",
    0x99: "™", 0x9a: "š", 0x9b: "›", 0x9c: "œ", 0x9e: "ž", 0x9f: "Ÿ",
}


def is_utf8_name(encoding):
    return encoding is None or str(encoding).strip().lower().replace("_", "-") in _UTF8


def utf8_ok(data):
    """Whether [data] is valid UTF-8."""
    try:
        data.decode("utf-8")
        return True
    except ValueError:
        return False


def decode(data, encoding):
    """[data] read as text in [encoding]. The keyboard reads UTF-8, ASCII,
    ISO-8859-1 and Windows-1252; anything else is read as UTF-8, with
    U+FFFD for what does not fit."""
    name = str(encoding or "utf-8").strip().lower().replace("_", "-")
    if name in _LATIN1 or name in _CP1252:
        try:
            text = data.decode("utf-8")
            if len(text) == len(data):
                return text
        except ValueError:
            pass
        if name in _CP1252:
            return "".join([_CP1252_HIGH.get(b) or chr(b) for b in data])
        return "".join([chr(b) for b in data])
    text = data.decode("utf-8", "replace")
    if name == "utf-8-sig" and text.startswith("﻿"):
        return text[1:]
    return text


def charset_of(content_type):
    """The charset= of a Content-Type, without quotes, or None."""
    if not content_type:
        return None
    m = _re.search(r"charset\s*=\s*[\"']?([^\"';\s]+)", str(content_type), _re.I)
    return m.group(1) if m else None


# ---- cookies -----------------------------------------------------------------

_MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]


def _days(y, m, d):
    # Days from 1970-01-01 to y-m-d.
    if m <= 2:
        y -= 1
    era = (y if y >= 0 else y - 399) // 400
    yoe = y - era * 400
    doy = (153 * (m - 3 if m > 2 else m + 9) + 2) // 5 + d - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


def _past(expires):
    m = _re.search(r"(\d{1,2})[ -]([A-Za-z]{3})[ -](\d{2,4}) (\d{1,2}):(\d{2}):(\d{2})", expires)
    if m is None or m.group(2).lower() not in _MONTHS:
        return False
    year = int(m.group(3))
    if year < 100:
        year += 1900 if year >= 70 else 2000
    month = _MONTHS.index(m.group(2).lower()) + 1
    at = _days(year, month, int(m.group(1))) * 86400 + int(m.group(4)) * 3600 + int(m.group(5)) * 60 + int(m.group(6))
    return at <= _time.time()


def set_cookies(headers, host):
    """Each cookie the reply's Set-Cookie headers set, as a dict of name,
    value, domain, path, secure and expired. The keyboard joins several
    Set-Cookie headers with ", "."""
    value = headers.get("set-cookie")
    if not value:
        return []
    out = []
    for one in _re.split(r",\s*(?=[^;,=\s]+=)", str(value)):
        pieces = one.split(";")
        name, eq, val = pieces[0].strip().partition("=")
        if not eq or not name:
            continue
        attrs = {}
        for p in pieces[1:]:
            k, _, v = p.strip().partition("=")
            attrs[k.strip().lower()] = v.strip()
        expired = False
        age = attrs.get("max-age")
        if age is not None and age.lstrip("-").isdigit():
            expired = int(age) <= 0
        elif attrs.get("expires"):
            expired = _past(attrs["expires"])
        out.append({
            "name": name.strip(), "value": val.strip().strip('"'),
            "domain": attrs.get("domain", "").lstrip(".").lower() or host,
            "path": attrs.get("path") or "/", "secure": "secure" in attrs, "expired": expired,
        })
    return out


def parse_links(value):
    """A Link header as a list of dicts: {"url", "rel", ...}."""
    links = []
    strip = " '\""
    value = str(value or "").strip(strip)
    if not value:
        return links
    for val in _re.split(r", *<", value):
        url, _, params = val.partition(";")
        link = {"url": url.strip("<> '\"")}
        for param in params.split(";"):
            key, eq, v = param.partition("=")
            if not eq:
                break
            link[key.strip(strip)] = v.strip(strip)
        links.append(link)
    return links


# ---- sending -----------------------------------------------------------------

def check_url(url, make):
    """Raises [make]'s error for an address that is not one: no scheme, a
    scheme other than http and https, no host."""
    text = str(url)
    if "://" not in text:
        raise make("missing_schema", "Invalid URL " + repr(text) + ": No scheme supplied. Perhaps you meant https://" + text + "?")
    scheme = text.split("://", 1)[0].lower()
    if scheme not in ("http", "https"):
        raise make("schema", "No connection adapters were found for " + repr(text) + ": plugins can request https addresses only")
    if not host_of(text):
        raise make("url", "Invalid URL " + repr(text) + ": No host supplied")


def refused(e, url, who, make):
    """The error to raise for [e], which the keyboard raised for a request to
    [url]: said the way [who] (requests, httpx, urllib.request) says it,
    with what to change. [e] itself when there is nothing to add."""
    message = str(e)
    host = host_of(url) or str(url)
    if isinstance(e, PermissionError):
        if "'network' permission" in message:
            return PermissionError(
                who + " needs the \"network\" permission, which plugin.json does not list. Add \"network\" to "
                "\"permissions\" in plugin.json; `wmpy check --fix` or the editor's quick fix adds it."
            )
        if "not allowed to connect to" in message:
            named = message.rsplit(" ", 1)[-1]
            return PermissionError(
                named + " is not in plugin.json's \"hosts\", so the keyboard did not connect to it. Add \"" + named
                + "\" to \"hosts\" in plugin.json; `wmpy check --fix` or the editor's quick fix adds it."
            )
        return e
    if isinstance(e, TimeoutError):
        return make("timeout", message + " (" + str(url) + "); the keyboard waits 15 seconds at most")
    if isinstance(e, ConnectionError):
        if "too many redirects" in message:
            return make("redirects", "Exceeded " + str(MAX_REDIRECTS) + " redirects: the keyboard follows 5 at most (" + str(url) + ")")
        if "1 MB" in message:
            return make("connection", "the reply from " + host + " is larger than 1 MB, the most a plugin can receive")
        if "Data saver" in message or "too many requests" in message:
            return make("connection", message)
        return make("connection", "could not reach " + host + ": " + message + ". Check that the phone is online.")
    if isinstance(e, ValueError):
        if "only https addresses can be requested" in message:
            return make(
                "schema",
                "plugins can request https addresses only, not plain http: " + str(url) + ". Change it to https://; "
                "plain http works only with a server on the local network that the user typed into the plugin's settings.",
            )
        if "only https addresses with a host name" in message:
            return make("url", message)
    return e


def send(method, url, headers, body, timeout, who, make, then=None, on_error=None, done=None):
    """Sends one request. [body] is None, text or bytes. Without [then],
    waits and returns done(reply, seconds); with it, returns at once and
    calls then(done(reply, seconds)) when the reply comes, or on_error(e)
    when it fails. Errors are [make]'s, as [refused] words them."""
    method = str(method).upper()
    if method not in METHODS:
        raise ValueError("the keyboard sends GET, POST, PUT, PATCH, DELETE and HEAD requests, not " + method)
    args = {"method": method, "url": str(url), "timeout": float(timeout), "binary": True}
    if headers:
        args["headers"] = {str(k): text_of(v) for k, v in headers.items() if v is not None}
    if body is not None and method in ("GET", "DELETE", "HEAD"):
        note("body:" + method, who + ": the keyboard sends no body with a " + method + " request, so this one went without its data")
    elif isinstance(body, bytes):
        args["bodyBytes"] = body
    elif body is not None:
        args["body"] = str(body)
    started = _time.monotonic()
    if then is not None:
        def arrived(reply):
            return done(reply, _time.monotonic() - started)

        def failed(error):
            err = refused(error, url, who, make)
            if on_error is None:
                raise err
            _core.call_back(on_error, err)

        return _core.defer("http.request", args, then, failed, arrived)
    try:
        reply = _core.call("http.request", args)
    except Exception as error:
        raise refused(error, url, who, make)
    return done(reply, _time.monotonic() - started)


def content_of(reply):
    """The reply's body as bytes, and the text the keyboard read from it when
    it sent text rather than bytes (a mock, an older keyboard)."""
    raw = reply.get("content")
    if raw is not None:
        import base64
        return base64.b64decode(raw), None
    text = reply.get("text") or ""
    return text.encode("utf-8"), text
