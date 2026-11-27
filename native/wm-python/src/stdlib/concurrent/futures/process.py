"""ProcessPoolExecutor, for a plugin, which cannot start processes: each
call runs in the plugin itself, at submit()."""

from concurrent.futures import _base


class BrokenProcessPool(_base.BrokenExecutor):
    pass


class ProcessPoolExecutor(_base.Executor):
    def __init__(self, max_workers=None, mp_context=None, initializer=None, initargs=(), *, max_tasks_per_child=None):
        if max_workers is not None and max_workers <= 0:
            raise ValueError("max_workers must be greater than 0")
        self._max_workers = max_workers or 1
        if initializer is not None:
            if not callable(initializer):
                raise TypeError("initializer must be a callable")
            initializer(*initargs)
