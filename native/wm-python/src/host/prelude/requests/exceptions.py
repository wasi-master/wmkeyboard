"""The errors requests raises, as requests names them. Each carries the
request and, when there was one, the response: e.request, e.response.

RequestException is an OSError, as in requests. Where requests gives a
class two bases (ConnectTimeout is a ConnectionError and a Timeout), the
keyboard's Python gives it the first one that matters for catching it:
see the differences table in the plugin docs."""


class RequestException(OSError):
    """Something went wrong with a request. Every error below is one."""

    def __init__(self, *args, **kwargs):
        response = kwargs.pop("response", None)
        self.response = response
        self.request = kwargs.pop("request", None)
        if response is not None and self.request is None:
            self.request = getattr(response, "request", None)
        super().__init__(*args)


class InvalidJSONError(RequestException):
    """The json= value could not be written as JSON."""


class JSONDecodeError(ValueError):
    """r.json() found no JSON in the reply. A ValueError, so `except
    ValueError` catches it: "Expecting value: line 1 column 1 (char 0)"."""

    def __init__(self, msg, doc="", pos=0, response=None, request=None):
        self.msg = msg
        self.doc = doc
        self.pos = pos
        if pos is None:
            self.lineno = None
            self.colno = None
            text = msg
        else:
            self.lineno = doc[:pos].count("\n") + 1
            self.colno = pos - doc.rfind("\n", 0, pos)
            text = f"{msg}: line {self.lineno} column {self.colno} (char {pos})"
        self.response = response
        self.request = request
        super().__init__(text)


class HTTPError(RequestException):
    """The reply's status was 400 or more: from r.raise_for_status()."""


class ConnectionError(RequestException):
    """The request did not get through: no network, Data saver, the host
    could not be reached, or the reply was over 1 MB."""


class ProxyError(ConnectionError):
    """A proxy error occurred."""


class SSLError(ConnectionError):
    """The host's certificate was refused."""


class Timeout(RequestException):
    """No reply in time. Catching it catches ConnectTimeout and ReadTimeout."""


class ConnectTimeout(Timeout):
    """The connection took too long."""


class ReadTimeout(Timeout):
    """The host sent nothing in time."""


class URLRequired(RequestException):
    """A valid URL is required to make a request."""


class TooManyRedirects(RequestException):
    """More redirects than the keyboard follows (5)."""


class MissingSchema(RequestException):
    """The address has no https:// in front."""


class InvalidSchema(RequestException):
    """The address starts with something other than https://."""


class InvalidURL(RequestException):
    """The address is not one the keyboard can request."""


class InvalidHeader(RequestException):
    """A header name or value is not valid."""


class ChunkedEncodingError(RequestException):
    """The server declared chunked encoding but sent an invalid chunk."""


class ContentDecodingError(RequestException):
    """The reply could not be decoded."""


class StreamConsumedError(RequestException):
    """The content of this response was already consumed."""


class RetryError(RequestException):
    """Custom retries logic failed."""


class UnrewindableBodyError(RequestException):
    """Requests encountered an error when trying to rewind a body."""


class RequestsWarning(Exception):
    """Base warning for requests. (The keyboard's Python has no Warning
    class, so this is an Exception.)"""


class FileModeWarning(RequestsWarning):
    """A file was opened in text mode, but requests determined its binary length."""


class RequestsDependencyWarning(RequestsWarning):
    """An imported dependency does not match the expected version range."""
