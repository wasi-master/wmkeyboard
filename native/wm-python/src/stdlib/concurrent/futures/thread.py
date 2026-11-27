"""ThreadPoolExecutor, on a plugin's one thread: each call runs at submit()."""

from concurrent.futures import _base


class BrokenThreadPool(_base.BrokenExecutor):
    pass


class ThreadPoolExecutor(_base.Executor):
    def __init__(self, max_workers=None, thread_name_prefix="", initializer=None, initargs=()):
        if max_workers is not None and max_workers <= 0:
            raise ValueError("max_workers must be greater than 0")
        self._max_workers = max_workers or 1
        self._thread_name_prefix = thread_name_prefix
        if initializer is not None:
            if not callable(initializer):
                raise TypeError("initializer must be a callable")
            initializer(*initargs)
