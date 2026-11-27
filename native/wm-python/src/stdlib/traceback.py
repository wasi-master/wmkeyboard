"""traceback: the exception being handled, as text. Frames are not kept,
so a traceback names the exception and its message without the call stack:
the stack summaries here are empty, and the walks yield nothing."""

import sys as _sys


def _exc_lines(exc):
    if exc is None:
        return ["NoneType: None\n"]
    lines = []
    cause = getattr(exc, "__cause__", None)
    context = getattr(exc, "__context__", None)
    if cause is not None:
        lines.extend(_exc_lines(cause))
        lines.append("\nThe above exception was the direct cause of the following exception:\n\n")
    elif context is not None:
        lines.extend(_exc_lines(context))
        lines.append("\nDuring handling of the above exception, another exception occurred:\n\n")
    lines.append("Traceback (most recent call last):\n")
    lines.extend(format_exception_only(exc))
    return lines


def format_exception_only(exc, value=None):
    if value is not None:
        exc = value
    text = str(exc)
    name = type(exc).__name__
    return [f"{name}: {text}\n" if text else f"{name}\n"]


def format_exception(exc, value=None, tb=None, limit=None, chain=True):
    if value is not None:
        exc = value
    return _exc_lines(exc)


def format_exc(limit=None, chain=True):
    return "".join(_exc_lines(_sys.exc_info()[1]))


def print_exc(limit=None, file=None, chain=True):
    (file or _sys.stderr).write(format_exc())


def print_exception(exc, value=None, tb=None, limit=None, file=None, chain=True):
    (file or _sys.stderr).write("".join(format_exception(exc, value)))


def format_tb(tb, limit=None):
    return []


def format_stack(f=None, limit=None):
    return []


def extract_stack(f=None, limit=None):
    return StackSummary()


def extract_tb(tb, limit=None):
    return StackSummary()


def format_list(extracted_list):
    return StackSummary.from_list(extracted_list).format()


def print_list(extracted_list, file=None):
    for item in format_list(extracted_list):
        (file or _sys.stderr).write(item)


def print_tb(tb, limit=None, file=None):
    pass


def print_stack(f=None, limit=None, file=None):
    pass


def print_last(limit=None, file=None, chain=True):
    exc = getattr(_sys, "last_exc", None)
    if exc is None:
        raise ValueError("no last exception")
    print_exception(exc, limit=limit, file=file, chain=chain)


def walk_tb(tb):
    """(frame, line) for each level of a traceback: none are kept."""
    while tb is not None:
        yield tb.tb_frame, tb.tb_lineno
        tb = tb.tb_next


def walk_stack(f):
    while f is not None:
        yield f, f.f_lineno
        f = f.f_back


def clear_frames(tb):
    pass


class FrameSummary:
    """One line of a traceback: where it was, and the source line."""

    __slots__ = ("filename", "lineno", "end_lineno", "colno", "end_colno", "name", "_line", "locals")

    def __init__(self, filename, lineno, name, *, lookup_line=True, locals=None, line=None, end_lineno=None, colno=None, end_colno=None):
        self.filename = filename
        self.lineno = lineno
        self.end_lineno = lineno if end_lineno is None else end_lineno
        self.colno = colno
        self.end_colno = end_colno
        self.name = name
        self._line = line
        self.locals = {k: repr(v) for k, v in locals.items()} if locals else None

    def __eq__(self, other):
        if isinstance(other, FrameSummary):
            return (self.filename, self.lineno, self.name, self.line, self.locals) == (other.filename, other.lineno, other.name, other.line, other.locals)
        if isinstance(other, tuple):
            return (self.filename, self.lineno, self.name, self.line) == other
        return NotImplemented

    def __getitem__(self, pos):
        return (self.filename, self.lineno, self.name, self.line)[pos]

    def __iter__(self):
        return iter([self.filename, self.lineno, self.name, self.line])

    def __len__(self):
        return 4

    def __repr__(self):
        return f"<FrameSummary file {self.filename}, line {self.lineno} in {self.name}>"

    @property
    def line(self):
        return (self._line or "").strip()


class StackSummary(list):
    """A list of FrameSummary objects, as extract_tb and extract_stack give."""

    @classmethod
    def extract(cls, frame_gen, *, limit=None, lookup_lines=True, capture_locals=False):
        result = cls()
        for f, lineno in frame_gen:
            code = getattr(f, "f_code", None)
            result.append(FrameSummary(getattr(code, "co_filename", "?"), lineno, getattr(code, "co_name", "?"), lookup_line=lookup_lines))
            if limit is not None and len(result) >= limit:
                break
        return result

    @classmethod
    def from_list(cls, a_list):
        result = cls()
        for frame in a_list:
            if isinstance(frame, FrameSummary):
                result.append(frame)
            else:
                filename, lineno, name, line = frame
                result.append(FrameSummary(filename, lineno, name, line=line))
        return result

    def format_frame_summary(self, frame_summary):
        row = [f'  File "{frame_summary.filename}", line {frame_summary.lineno}, in {frame_summary.name}\n']
        if frame_summary.line:
            row.append(f"    {frame_summary.line}\n")
        return "".join(row)

    def format(self):
        return [self.format_frame_summary(f) for f in self]


class TracebackException:
    """An exception made ready to format, with its cause and context."""

    def __init__(self, exc_type, exc_value, exc_traceback, *, limit=None, lookup_lines=True, capture_locals=False, compact=False, max_group_width=15, max_group_depth=10, _seen=None):
        if _seen is None:
            _seen = set()
        _seen.add(id(exc_value))
        self.exc_type = exc_type
        self.exc_type_str = getattr(exc_type, "__qualname__", getattr(exc_type, "__name__", str(exc_type)))
        self._str = str(exc_value) if exc_value is not None else ""
        self.stack = StackSummary()
        self.__notes__ = getattr(exc_value, "__notes__", None)
        self.__suppress_context__ = bool(getattr(exc_value, "__suppress_context__", False))
        self.exceptions = None
        self.filename = self.lineno = self.end_lineno = self.text = self.offset = self.end_offset = self.msg = None
        if exc_type is not None and issubclass(exc_type, SyntaxError):
            self.filename = getattr(exc_value, "filename", None)
            self.lineno = getattr(exc_value, "lineno", None)
            self.text = getattr(exc_value, "text", None)
            self.offset = getattr(exc_value, "offset", None)
            self.msg = getattr(exc_value, "msg", None)
        self._exc = exc_value
        cause = getattr(exc_value, "__cause__", None)
        context = getattr(exc_value, "__context__", None)
        self.__cause__ = TracebackException(type(cause), cause, None, _seen=_seen) if cause is not None and id(cause) not in _seen else None
        self.__context__ = TracebackException(type(context), context, None, _seen=_seen) if context is not None and id(context) not in _seen and not self.__suppress_context__ else None
        group = getattr(exc_value, "exceptions", None)
        if isinstance(exc_value, BaseExceptionGroup) and group is not None:
            self.exceptions = [TracebackException(type(e), e, None, _seen=_seen) for e in group]

    @classmethod
    def from_exception(cls, exc, *args, **kwargs):
        return cls(type(exc), exc, getattr(exc, "__traceback__", None), *args, **kwargs)

    @property
    def exc_type_qualname(self):
        return self.exc_type_str

    def __eq__(self, other):
        if isinstance(other, TracebackException):
            return self.__dict__ == other.__dict__
        return NotImplemented

    def __str__(self):
        return self._str

    def format_exception_only(self, *, show_group=False, _depth=0):
        if self.exc_type is None:
            yield "NoneType: None\n"
            return
        yield f"{self.exc_type_str}: {self._str}\n" if self._str else f"{self.exc_type_str}\n"
        for note in self.__notes__ or ():
            yield f"{note}\n"

    def format(self, *, chain=True, _ctx=None):
        if chain and self.__cause__ is not None:
            yield from self.__cause__.format(chain=chain)
            yield "\nThe above exception was the direct cause of the following exception:\n\n"
        elif chain and self.__context__ is not None:
            yield from self.__context__.format(chain=chain)
            yield "\nDuring handling of the above exception, another exception occurred:\n\n"
        yield "Traceback (most recent call last):\n"
        yield from self.stack.format()
        yield from self.format_exception_only()

    def print(self, *, file=None, chain=True):
        for line in self.format(chain=chain):
            (file or _sys.stderr).write(line)
