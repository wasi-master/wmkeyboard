"""_interpreters: CPython 3.13's subinterpreters. A plugin runs in one
interpreter and can start no other: this one is listed, and making
another raises InterpreterError."""

_CANNOT = "plugins run in one interpreter and cannot start another"


class InterpreterError(Exception):
    pass


class InterpreterNotFoundError(InterpreterError):
    pass


class NotShareableError(ValueError):
    pass


class CrossInterpreterBufferView:
    def __init__(self, *args, **kwargs):
        raise TypeError("cannot create 'CrossInterpreterBufferView' instances")


WHENCE_UNKNOWN = 0
WHENCE_RUNTIME = 1
WHENCE_LEGACY_CAPI = 2
WHENCE_CAPI = 3
WHENCE_XI = 4
WHENCE_STDLIB = 5


def get_current():
    return (0, WHENCE_RUNTIME)


def get_main():
    return (0, WHENCE_RUNTIME)


def list_all(*, require_ready=False):
    return [(0, WHENCE_RUNTIME)]


def whence(id):
    if id != 0:
        raise InterpreterNotFoundError(f"unrecognized interpreter ID {id}")
    return WHENCE_RUNTIME


def is_running(id, *, restrict=False):
    return id == 0


def create(config=None, *, reqrefs=False):
    raise InterpreterError(_CANNOT)


def new_config(name="isolated", /, **overrides):
    return {"name": name, **overrides}


def destroy(id, *, restrict=False):
    raise InterpreterError("cannot destroy the current interpreter" if id == 0 else f"unrecognized interpreter ID {id}")


def _refuse(id, *args, **kwargs):
    raise InterpreterNotFoundError(f"unrecognized interpreter ID {id}")


exec = run_string = run_func = call = set___main___attrs = incref = decref = _refuse


def is_shareable(obj):
    return obj is None or isinstance(obj, (bool, int, float, str, bytes)) or (isinstance(obj, tuple) and all(is_shareable(x) for x in obj))


def capture_exception(exc=None):
    return exc
