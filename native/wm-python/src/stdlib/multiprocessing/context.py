"""multiprocessing's start methods and errors, for a plugin's one process."""


class ProcessError(Exception):
    pass


class BufferTooShort(ProcessError):
    pass


class TimeoutError(ProcessError):
    pass


class AuthenticationError(ProcessError):
    pass


_method = "spawn"


def get_start_method(allow_none=False):
    return _method


def set_start_method(method, force=False):
    global _method
    if method not in ("fork", "spawn", "forkserver", None):
        raise ValueError(f"cannot find context for {method!r}")
    _method = method or "spawn"


def get_all_start_methods():
    return ["spawn"]


def set_executable(executable):
    pass


def set_forkserver_preload(module_names):
    pass


class BaseContext:
    ProcessError = ProcessError
    BufferTooShort = BufferTooShort
    TimeoutError = TimeoutError
    AuthenticationError = AuthenticationError

    def __getattr__(self, name):
        import multiprocessing

        try:
            return getattr(multiprocessing, name)
        except AttributeError:
            raise AttributeError(f"'{type(self).__name__}' object has no attribute '{name}'") from None

    def get_start_method(self, allow_none=False):
        return get_start_method(allow_none)

    def get_context(self, method=None):
        return get_context(method)


class SpawnContext(BaseContext):
    _name = "spawn"


ForkContext = ForkServerContext = DefaultContext = SpawnContext


from multiprocessing.process import BaseProcess as _BaseProcess


class Process(_BaseProcess):
    """A process plugins cannot start: making one works, start() raises."""

    _start_method = None


class SpawnProcess(Process):
    _start_method = "spawn"


class ForkProcess(Process):
    _start_method = "fork"


class ForkServerProcess(Process):
    _start_method = "forkserver"


def get_context(method=None):
    if method not in (None, "fork", "spawn", "forkserver"):
        raise ValueError(f"cannot find context for {method!r}")
    return SpawnContext()
