"""The cookies a reply set. The keyboard sends no cookies with a plugin's
requests, so they are kept for reading (r.cookies["session"]) but not
sent back: see the differences table in the plugin docs."""


class RequestsCookieJar:
    """Cookies by name, like a dict: jar["name"], jar.get("name"),
    jar.get_dict(). Each also has the domain and path it came for."""

    def __init__(self):
        self._cookies = []

    def set(self, name, value, domain="", path="/", secure=False, **kwargs):
        """Sets cookie [name]; a value of None removes it."""
        self._cookies = [c for c in self._cookies if not (c["name"] == name and c["domain"] == domain and c["path"] == path)]
        if value is not None:
            self._cookies.append({"name": name, "value": str(value), "domain": domain, "path": path, "secure": secure})

    def _find(self, name, domain=None, path=None):
        for c in reversed(self._cookies):
            if c["name"] == name and (domain is None or c["domain"] == domain) and (path is None or c["path"] == path):
                return c
        return None

    def get(self, name, default=None, domain=None, path=None):
        c = self._find(name, domain, path)
        return default if c is None else c["value"]

    def __getitem__(self, name):
        c = self._find(name)
        if c is None:
            raise KeyError(f"name={name!r}, domain=None, path=None")
        return c["value"]

    def __setitem__(self, name, value):
        self.set(name, value)

    def __delitem__(self, name):
        self._cookies = [c for c in self._cookies if c["name"] != name]

    def __contains__(self, name):
        return self._find(name) is not None

    def __iter__(self):
        return iter([c["name"] for c in self._cookies])

    def __len__(self):
        return len(self._cookies)

    def keys(self):
        return [c["name"] for c in self._cookies]

    def values(self):
        return [c["value"] for c in self._cookies]

    def items(self):
        return [(c["name"], c["value"]) for c in self._cookies]

    def list_domains(self):
        out = []
        for c in self._cookies:
            if c["domain"] not in out:
                out.append(c["domain"])
        return out

    def list_paths(self):
        out = []
        for c in self._cookies:
            if c["path"] not in out:
                out.append(c["path"])
        return out

    def get_dict(self, domain=None, path=None):
        return {c["name"]: c["value"] for c in self._cookies if (domain is None or c["domain"] == domain) and (path is None or c["path"] == path)}

    def update(self, other):
        if isinstance(other, RequestsCookieJar):
            for c in other._cookies:
                self.set(c["name"], c["value"], domain=c["domain"], path=c["path"], secure=c["secure"])
        elif other is not None:
            for k, v in other.items():
                self.set(k, v)

    def clear(self, domain=None, path=None, name=None):
        self._cookies = [
            c for c in self._cookies
            if not ((domain is None or c["domain"] == domain) and (path is None or c["path"] == path) and (name is None or c["name"] == name))
        ]

    def copy(self):
        jar = RequestsCookieJar()
        jar.update(self)
        return jar

    def __repr__(self):
        inner = ", ".join(["<Cookie " + c["name"] + "=" + c["value"] + " for " + c["domain"] + c["path"] + ">" for c in self._cookies])
        return f"<RequestsCookieJar[{inner}]>"


def cookiejar_from_dict(cookie_dict, cookiejar=None, overwrite=True):
    """A RequestsCookieJar holding [cookie_dict]'s names and values."""
    jar = RequestsCookieJar() if cookiejar is None else cookiejar
    for name, value in (cookie_dict or {}).items():
        if overwrite or name not in jar:
            jar.set(name, value)
    return jar


def merge_cookies(cookiejar, cookies):
    """[cookiejar] with [cookies] (a dict or a jar) added."""
    cookiejar.update(cookies)
    return cookiejar
