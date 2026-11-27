"""email.feedparser: a message fed in pieces. The parser here reads a whole
message at once, so FeedParser keeps what it is fed and parses it on
close(); the message comes out the same as Parser's."""

from email.message import Message
from email.policy import compat32

__all__ = ["FeedParser", "BytesFeedParser"]


class FeedParser:
    def __init__(self, _factory=None, *, policy=compat32):
        self._factory = _factory
        self.policy = policy
        self._pieces = []
        self._headersonly = False

    def _set_headersonly(self):
        self._headersonly = True

    def feed(self, data):
        self._pieces.append(data)

    def close(self):
        from email.parser import Parser
        return Parser(self._factory, policy=self.policy).parsestr("".join(self._pieces), self._headersonly)


class BytesFeedParser(FeedParser):
    def feed(self, data):
        super().feed(bytes(data).decode("ascii", "surrogateescape"))

    def close(self):
        msg = super().close()
        for part in msg.walk():
            part._raw_latin1 = True
        return msg
