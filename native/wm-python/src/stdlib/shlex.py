"""shlex: shell-like splitting and quoting of command lines."""

import io
import re
import sys
from collections import deque

__all__ = ["shlex", "split", "quote", "join"]


class shlex:
    def __init__(self, instream=None, infile=None, posix=False, punctuation_chars=False):
        if isinstance(instream, str):
            instream = io.StringIO(instream)
        if instream is None:
            instream = sys.stdin
        self.instream = instream
        self.infile = infile
        self.posix = posix
        self.eof = None if posix else ""
        self.commenters = "#"
        self.wordchars = "abcdfeghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"
        if posix:
            self.wordchars += "ßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞ"
        self.whitespace = " \t\r\n"
        self.whitespace_split = False
        self.quotes = "'\""
        self.escape = "\\"
        self.escapedquotes = '"'
        self.state = " "
        self.pushback = deque()
        self.lineno = 1
        self.debug = 0
        self.token = ""
        self.filestack = deque()
        self.source = None
        if punctuation_chars is True:
            punctuation_chars = "();<>|&"
        elif not punctuation_chars:
            punctuation_chars = ""
        self._punctuation_chars = punctuation_chars
        if punctuation_chars:
            self._pushback_chars = deque()
            self.wordchars += "~-./*?="
            self.wordchars = "".join(c for c in self.wordchars if c not in punctuation_chars)
        else:
            self._pushback_chars = deque()

    @property
    def punctuation_chars(self):
        return self._punctuation_chars

    def push_token(self, tok):
        self.pushback.appendleft(tok)

    def push_source(self, newstream, newfile=None):
        if isinstance(newstream, str):
            newstream = io.StringIO(newstream)
        self.filestack.appendleft((self.infile, self.instream, self.lineno))
        self.infile, self.instream, self.lineno = newfile, newstream, 1

    def pop_source(self):
        self.infile, self.instream, self.lineno = self.filestack.popleft()
        self.state = " "

    def get_token(self):
        if self.pushback:
            return self.pushback.popleft()
        raw = self.read_token()
        while raw == self.eof:
            if not self.filestack:
                return self.eof
            self.pop_source()
            raw = self.get_token()
        return raw

    def _char(self):
        if self._pushback_chars:
            c = self._pushback_chars.pop()
        else:
            c = self.instream.read(1)
        if c == "\n":
            self.lineno += 1
        return c

    def _unread(self, c):
        if c == "\n":
            self.lineno -= 1
        self._pushback_chars.append(c)

    def _skip_comment(self):
        self.instream.readline()
        self.lineno += 1

    def read_token(self):
        if self.state is None:
            return self.eof
        # Leading whitespace and comments.
        while True:
            c = self._char()
            if not c:
                self.state = None
                return self.eof
            if c in self.whitespace:
                continue
            if c in self.commenters:
                self._skip_comment()
                continue
            break
        punct = self._punctuation_chars
        if punct and c in punct:
            token = c
            while True:
                n = self._char()
                if n and n in punct:
                    token += n
                    continue
                if n:
                    self._unread(n)
                return token
        starts_word = (
            c in self.wordchars
            or c in self.quotes
            or (self.posix and c in self.escape)
            or (self.whitespace_split and c not in punct)
        )
        if not starts_word:
            return c
        token = ""
        quoted = False
        quote = None
        if not self.posix and c in self.quotes:
            # Classic mode: a quoted token keeps its quotes and ends with them.
            token = c
            while True:
                n = self._char()
                if not n:
                    raise ValueError("No closing quotation")
                token += n
                if n == c:
                    return token
        self._unread(c)
        while True:
            c = self._char()
            if quote is not None:
                if not c:
                    raise ValueError("No closing quotation")
                if c == quote:
                    quote = None
                elif c in self.escape and quote in self.escapedquotes:
                    n = self._char()
                    if not n:
                        raise ValueError("No escaped character")
                    if n != quote and n not in self.escape:
                        token += c
                    token += n
                else:
                    token += c
                continue
            if not c:
                self.state = None
                break
            if c in self.whitespace:
                break
            if c in self.commenters:
                self._skip_comment()
                if self.posix:
                    break
                continue
            if self.posix and c in self.quotes:
                quote = c
                quoted = True
                continue
            if self.posix and c in self.escape:
                n = self._char()
                if not n:
                    raise ValueError("No escaped character")
                token += n
                continue
            if punct and c in punct:
                self._unread(c)
                break
            if c in self.wordchars or c in self.quotes or (self.whitespace_split and c not in punct):
                token += c
                continue
            self._unread(c)
            break
        if self.state is None and token == "" and not quoted:
            return self.eof
        return token

    def sourcehook(self, newfile):
        raise OSError("shlex cannot open files here")

    def error_leader(self, infile=None, lineno=None):
        if infile is None:
            infile = self.infile
        if lineno is None:
            lineno = self.lineno
        return f'"{infile}", line {lineno}: '

    def __iter__(self):
        return self

    def __next__(self):
        token = self.get_token()
        if token == self.eof:
            raise StopIteration
        return token


def split(s, comments=False, posix=True):
    if s is None:
        raise ValueError("s argument must not be None")
    lex = shlex(s, posix=posix)
    lex.whitespace_split = True
    if not comments:
        lex.commenters = ""
    return list(lex)


def join(split_command):
    return " ".join(quote(arg) for arg in split_command)


_find_unsafe = re.compile(r"[^\w@%+=:,./-]", re.ASCII).search


def quote(s):
    if not s:
        return "''"
    if not isinstance(s, str):
        raise TypeError(f"expected string object, got {type(s).__name__!r}")
    if _find_unsafe(s) is None:
        return s
    return "'" + s.replace("'", "'\"'\"'") + "'"
