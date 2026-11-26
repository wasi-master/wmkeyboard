"""traceback: the exception being handled, as text. Frames are not kept,
so a traceback names the exception and its message without the call stack."""

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
    return []


def print_stack(f=None, limit=None, file=None):
    pass
