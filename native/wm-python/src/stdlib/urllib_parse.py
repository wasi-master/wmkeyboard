"""urllib.parse: quoting, query strings and splitting URLs."""

import _native


def quote(string, safe="/"):
    return _native.quote(string, safe)


def quote_plus(string, safe=""):
    return _native.quote(string, safe + " ").replace(" ", "+")


def unquote(string):
    return _native.unquote(string)


def unquote_plus(string):
    return _native.unquote(string.replace("+", " "))


def urlencode(query, doseq=False, safe="", quote_via=None):
    if quote_via is None:
        quote_via = quote_plus
    pairs = query.items() if isinstance(query, dict) else query
    out = []
    for k, v in pairs:
        if doseq and isinstance(v, (list, tuple)):
            for item in v:
                out.append(quote_via(str(k), safe) + "=" + quote_via(str(item), safe))
        else:
            out.append(quote_via(str(k), safe) + "=" + quote_via(str(v), safe))
    return "&".join(out)


def parse_qsl(qs, keep_blank_values=False):
    out = []
    for part in qs.replace(";", "&").split("&"):
        if not part:
            continue
        if "=" in part:
            k, v = part.split("=", 1)
        else:
            k, v = part, ""
        if v or keep_blank_values:
            out.append((unquote_plus(k), unquote_plus(v)))
    return out


def parse_qs(qs, keep_blank_values=False):
    out = {}
    for k, v in parse_qsl(qs, keep_blank_values):
        out.setdefault(k, []).append(v)
    return out


class SplitResult:
    def __init__(self, scheme, netloc, path, query, fragment):
        self.scheme = scheme
        self.netloc = netloc
        self.path = path
        self.query = query
        self.fragment = fragment
        self.params = ""

    @property
    def hostname(self):
        host = self.netloc.rpartition("@")[2]
        if host.startswith("["):
            return host[1:host.find("]")].lower() or None
        return host.partition(":")[0].lower() or None

    @property
    def port(self):
        host = self.netloc.rpartition("@")[2]
        if host.startswith("["):
            host = host[host.find("]") + 1:]
        head, sep, tail = host.rpartition(":")
        if sep and tail.isdigit():
            return int(tail)
        return None

    @property
    def username(self):
        if "@" not in self.netloc:
            return None
        return self.netloc.rpartition("@")[0].partition(":")[0] or None

    @property
    def password(self):
        if "@" not in self.netloc:
            return None
        userinfo = self.netloc.rpartition("@")[0]
        return userinfo.partition(":")[2] if ":" in userinfo else None

    def geturl(self):
        return urlunsplit((self.scheme, self.netloc, self.path, self.query, self.fragment))

    def _replace(self, **changes):
        values = {"scheme": self.scheme, "netloc": self.netloc, "path": self.path, "query": self.query, "fragment": self.fragment}
        values.update(changes)
        return SplitResult(values["scheme"], values["netloc"], values["path"], values["query"], values["fragment"])

    def __iter__(self):
        return [self.scheme, self.netloc, self.path, self.query, self.fragment]

    def __getitem__(self, i):
        return [self.scheme, self.netloc, self.path, self.query, self.fragment][i]

    def __repr__(self):
        return f"SplitResult(scheme={self.scheme!r}, netloc={self.netloc!r}, path={self.path!r}, query={self.query!r}, fragment={self.fragment!r})"


def urlsplit(url, scheme=""):
    fragment = ""
    query = ""
    if "#" in url:
        url, fragment = url.split("#", 1)
    if "?" in url:
        url, query = url.split("?", 1)
    colon = url.find(":")
    if colon > 0 and all(c.isalnum() or c in "+-." for c in url[:colon]) and url[0].isalpha():
        scheme = url[:colon].lower()
        url = url[colon + 1:]
    netloc = ""
    if url.startswith("//"):
        rest = url[2:]
        slash = rest.find("/")
        if slash < 0:
            netloc, url = rest, ""
        else:
            netloc, url = rest[:slash], rest[slash:]
    return SplitResult(scheme, netloc, url, query, fragment)


urlparse = urlsplit


def urlunsplit(parts):
    scheme, netloc, path, query, fragment = parts
    out = ""
    if scheme:
        out = scheme + ":"
    if netloc or scheme in ("http", "https", "ftp", "file", "ws", "wss"):
        out = out + "//" + netloc
    out = out + path
    if query:
        out = out + "?" + query
    if fragment:
        out = out + "#" + fragment
    return out


urlunparse = urlunsplit


def urljoin(base, url):
    if not base:
        return url
    if not url:
        return base
    target = urlsplit(url)
    if target.scheme:
        return url
    b = urlsplit(base)
    if url.startswith("//"):
        return b.scheme + ":" + url
    if target.netloc:
        return urlunsplit((b.scheme, target.netloc, target.path, target.query, target.fragment))
    if not target.path:
        return urlunsplit((b.scheme, b.netloc, b.path, target.query or b.query, target.fragment))
    if target.path.startswith("/"):
        path = target.path
    else:
        directory = b.path.rpartition("/")[0]
        path = directory + "/" + target.path
    segments = []
    for seg in path.split("/"):
        if seg == "..":
            if len(segments) > 1:
                segments.pop()
        elif seg != ".":
            segments.append(seg)
    path = "/".join(segments)
    if not path.startswith("/"):
        path = "/" + path
    return urlunsplit((b.scheme, b.netloc, path, target.query, target.fragment))
