"""warnings: warn() writes `<category>: <message>` to sys.stderr once per
message and place, unless a filter says otherwise."""

import sys as _sys

# Python's defaults: warnings meant for a library's own developers stay quiet.
filters = [
    ("ignore", "", DeprecationWarning),
    ("ignore", "", PendingDeprecationWarning),
    ("ignore", "", ImportWarning),
    ("ignore", "", ResourceWarning),
]
_seen = set()


def _matches(entry, message, category):
    action, text, cat = entry
    return issubclass(category, cat) and (not text or str(message).startswith(text))


def _action(message, category):
    for entry in filters:
        if _matches(entry, message, category):
            return entry[0]
    return "default"


def warn(message, category=None, stacklevel=1, source=None):
    if isinstance(message, Warning):
        category = type(message)
    if category is None:
        category = UserWarning
    action = _action(message, category)
    if action == "ignore":
        return
    if action == "error":
        raise message if isinstance(message, Warning) else category(message)
    key = (str(message), category)
    if action in ("default", "once", "module") and key in _seen:
        return
    _seen.add(key)
    showwarning(message, category, "<script>", 0)


def warn_explicit(message, category, filename, lineno, module=None, registry=None, module_globals=None, source=None):
    warn(message, category)


def formatwarning(message, category, filename, lineno, line=None):
    return f"{filename}:{lineno}: {category.__name__}: {message}\n"


def showwarning(message, category, filename, lineno, file=None, line=None):
    (file or _sys.stderr).write(f"{category.__name__}: {message}\n")


def filterwarnings(action, message="", category=Warning, module="", lineno=0, append=False):
    entry = (action, message, category)
    if append:
        filters.append(entry)
    else:
        filters.insert(0, entry)


def simplefilter(action, category=Warning, lineno=0, append=False):
    filterwarnings(action, "", category, append=append)


def resetwarnings():
    filters.clear()
    _seen.clear()


class catch_warnings:
    """Filters set inside the block are dropped when it ends; with
    record=True the block's warnings are collected in a list instead."""

    def __init__(self, *, record=False, action=None, category=Warning):
        self._record = record
        self._action = action
        self._category = category

    def __enter__(self):
        global showwarning
        self._filters = list(filters)
        self._show = showwarning
        if self._action is not None:
            simplefilter(self._action, self._category)
        if self._record:
            log = []

            def record(message, category, filename, lineno, file=None, line=None):
                log.append(WarningMessage(message, category, filename, lineno))

            showwarning = record
            return log
        return None

    def __exit__(self, *exc_info):
        global showwarning
        filters[:] = self._filters
        showwarning = self._show
        _seen.clear()
        return False


class WarningMessage:
    def __init__(self, message, category, filename, lineno, file=None, line=None, source=None):
        self.message = message if isinstance(message, Warning) else category(message)
        self.category = category
        self.filename = filename
        self.lineno = lineno

    def __str__(self):
        return f"{{message : {self.message!r}, category : {self.category.__name__!r}}}"


def deprecated(msg, *, category=DeprecationWarning, stacklevel=1):
    def decorate(f):
        def wrapper(*args, **kwargs):
            warn(msg, category)
            return f(*args, **kwargs)
        wrapper.__name__ = getattr(f, "__name__", "wrapper")
        wrapper.__wrapped__ = f
        return wrapper
    return decorate
