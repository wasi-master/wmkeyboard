"""The helpers from requests.utils that plugins use."""

import urllib.parse as _parse
from wm import _net
from requests.structures import CaseInsensitiveDict


def quote(string, safe="/"):
    """[string] with what a URL cannot hold percent-encoded."""
    return _parse.quote(string, safe=safe)


def unquote(string):
    """[string] with %xx escapes replaced by their characters."""
    return _parse.unquote(string)


def urlencode(query, doseq=False):
    """A dict or list of pairs as a query string: a=1&b=two+words."""
    return _parse.urlencode(query, doseq=doseq)


def requote_uri(uri):
    """[uri] with characters that need it percent-encoded, escapes kept."""
    return _parse.quote(uri, safe="!#$%&'()*+,/:;=?@[]~")


def get_encoding_from_headers(headers):
    """The charset a Content-Type names; "utf-8" for JSON; None otherwise.
    (requests says ISO-8859-1 for text without a charset; the keyboard
    reads that as UTF-8 when it is valid UTF-8.)"""
    content_type = headers.get("content-type")
    if not content_type:
        return None
    charset = _net.charset_of(content_type)
    if charset:
        return charset
    if "application/json" in content_type:
        return "utf-8"
    return None


def parse_header_links(value):
    """A Link header as a list of dicts: [{"url": ..., "rel": "next"}]."""
    return _net.parse_links(value)


def dict_from_cookiejar(cj):
    """A cookie jar's names and values as a dict."""
    return {k: v for k, v in cj.items()}


def add_dict_to_cookiejar(cj, cookie_dict):
    """[cj] with [cookie_dict] added."""
    for k, v in cookie_dict.items():
        cj.set(k, v)
    return cj


def default_user_agent(name="python-requests"):
    """The keyboard sends its own User-Agent, naming the plugin."""
    return name + "/2.32.3"


def default_headers():
    return CaseInsensitiveDict({"Accept": "*/*"})
