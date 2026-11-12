"""The one channel to the keyboard, and the bookkeeping for answers that
arrive later as events. Internal: plugins use wm.* instead."""

import _wm

_pending = {}
_streams = {}
_counter = [0]


def call(op, args=None):
    """Ask the keyboard to do [op]. Raises PermissionError when the plugin
    did not declare, or the user did not grant, what [op] needs."""
    return _wm.call(op, args or {})


def token(prefix):
    _counter[0] = _counter[0] + 1
    return f"{prefix}{_counter[0]}"


def defer(op, args, then, on_error=None, convert=None):
    """Start [op] without waiting. [then] is called with its result when the
    keyboard sends it back, and the panel is drawn again."""
    t = token(op.split(".")[0] + ":")
    _pending[t] = (then, on_error, convert)
    payload = dict(args or {})
    payload["async"] = t
    _wm.call(op, payload)
    return t


def stream(op, args, on_item, then=None, on_error=None, convert=None):
    """Start [op], which answers many times: each piece goes to [on_item],
    and the end (if the op has one) to [then]."""
    t = token(op.split(".")[0] + ":")
    _streams[t] = on_item
    _pending[t] = (then, on_error, convert)
    payload = dict(args or {})
    payload["async"] = t
    payload["stream"] = True
    _wm.call(op, payload)
    return t


def end_stream(t):
    _streams.pop(t, None)
    _pending.pop(t, None)


def piece(t, value):
    on_item = _streams.get(t)
    if on_item is None:
        return False
    call_back(on_item, value)
    return True


def resolve(t, value):
    _streams.pop(t, None)
    entry = _pending.pop(t, None)
    if entry is None:
        return False
    then, on_error, convert = entry
    if isinstance(value, dict) and "error" in value and isinstance(value["error"], dict):
        err = value["error"]
        exc = error_of(err.get("kind", "RuntimeError"), err.get("message", ""))
        if on_error is not None:
            on_error(exc)
            return True
        raise exc
    if then is not None:
        call_back(then, convert(value) if convert is not None else value)
    return True


_KINDS = {
    "PermissionError": PermissionError,
    "TimeoutError": TimeoutError,
    "ConnectionError": ConnectionError,
    "ValueError": ValueError,
    "TypeError": TypeError,
    "OSError": OSError,
    "RuntimeError": RuntimeError,
    "NotImplementedError": NotImplementedError,
    "FileNotFoundError": FileNotFoundError,
}


def error_of(kind, message):
    maker = _KINDS.get(kind)
    if maker is None:
        return RuntimeError(f"{kind}: {message}")
    return maker(message)


def accepts_arguments(fn):
    """Whether [fn] takes at least one positional argument."""
    return _wm.arity(fn) != 0


def call_back(fn, *args):
    """Call [fn] with [args] if it takes them, or with none if it takes none."""
    if fn is None:
        return None
    if args and not accepts_arguments(fn):
        return fn()
    return fn(*args)
