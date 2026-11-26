"""urllib.error, as CPython has it: URLError when a request gets no
answer, HTTPError (a URLError, and a response too) for a status of 400 or
more."""


class URLError(OSError):
    """The request got no answer. e.reason says why."""

    def __init__(self, reason, filename=None):
        super().__init__(reason)
        self.reason = reason
        self.filename = filename

    def __str__(self):
        return f"<urlopen error {self.reason}>"


class HTTPError(URLError):
    """The reply's status was 400 or more. It is also the reply: e.code,
    e.reason, e.headers, e.read()."""

    def __init__(self, url, code, msg, hdrs, fp):
        super().__init__(msg)
        self.url = url
        self.filename = url
        self.code = code
        self.status = code
        self.msg = msg
        self.hdrs = hdrs
        self.headers = hdrs
        self.fp = fp

    def __str__(self):
        return f"HTTP Error {self.code}: {self.msg}"

    def __repr__(self):
        return f"<HTTPError {self.code}: {self.msg!r}>"

    def read(self, amt=None):
        return self.fp.read(amt) if self.fp is not None else b""

    def getcode(self):
        return self.code

    def geturl(self):
        return self.url

    def info(self):
        return self.hdrs


class ContentTooShortError(URLError):
    """Less data came than the reply said."""

    def __init__(self, message, content):
        super().__init__(message)
        self.content = content
