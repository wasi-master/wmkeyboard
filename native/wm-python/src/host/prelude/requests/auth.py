"""Authentication for requests: auth=("user", "password") is HTTP Basic,
the same as auth=HTTPBasicAuth("user", "password")."""

from wm import _net


def _basic_auth_str(username, password):
    """The Authorization header value for HTTP Basic authentication."""
    return _net.basic_auth(username, password)


class AuthBase:
    """What an auth= object is: called with the request about to go, it
    returns it with its headers set."""

    def __call__(self, r):
        raise NotImplementedError("Auth hooks must be callable.")


class HTTPBasicAuth(AuthBase):
    """HTTP Basic authentication: the user name and password in the
    Authorization header."""

    def __init__(self, username, password):
        self.username = username
        self.password = password

    def __eq__(self, other):
        return self.username == getattr(other, "username", None) and self.password == getattr(other, "password", None)

    def __ne__(self, other):
        return not self == other

    def __call__(self, r):
        r.headers["Authorization"] = _basic_auth_str(self.username, self.password)
        return r


class HTTPProxyAuth(HTTPBasicAuth):
    """Proxy authentication. Plugins reach the internet only through the
    keyboard, so there is no proxy to sign in to."""

    def __call__(self, r):
        _net.note("proxyauth", "requests: HTTPProxyAuth does nothing here: plugins connect only through the keyboard")
        return r


class HTTPDigestAuth(AuthBase):
    """HTTP Digest authentication, which the keyboard's requests does not do."""

    def __init__(self, username, password):
        raise NotImplementedError(
            "the keyboard's requests has no Digest authentication; use HTTPBasicAuth or a token in a header"
        )
