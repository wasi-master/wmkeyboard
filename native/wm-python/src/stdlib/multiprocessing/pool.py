"""multiprocessing.pool, for a plugin's one thread: each task runs in the
plugin itself, when it is handed to the pool, and its result is ready."""

from multiprocessing.context import TimeoutError

RUN = 0
CLOSE = 1
TERMINATE = 2


class ApplyResult:
    def __init__(self, fn, args, kwds, callback=None, error_callback=None):
        try:
            self._value = fn(*args, **kwds)
            self._success = True
        except Exception as e:
            self._value = e
            self._success = False
        if self._success and callback:
            callback(self._value)
        if not self._success and error_callback:
            error_callback(self._value)

    def ready(self):
        return True

    def successful(self):
        return self._success

    def wait(self, timeout=None):
        pass

    def get(self, timeout=None):
        if self._success:
            return self._value
        raise self._value


AsyncResult = ApplyResult


class MapResult(ApplyResult):
    pass


class Pool:
    def __init__(self, processes=None, initializer=None, initargs=(), maxtasksperchild=None, context=None):
        if processes is not None and processes < 1:
            raise ValueError("Number of processes must be at least 1")
        self._processes = processes or 1
        self._state = RUN
        if initializer is not None:
            initializer(*initargs)

    def _check(self):
        if self._state != RUN:
            raise ValueError("Pool not running")

    def apply(self, func, args=(), kwds={}):
        self._check()
        return func(*args, **kwds)

    def apply_async(self, func, args=(), kwds={}, callback=None, error_callback=None):
        self._check()
        return ApplyResult(func, args, kwds, callback, error_callback)

    def map(self, func, iterable, chunksize=None):
        self._check()
        return [func(x) for x in iterable]

    def map_async(self, func, iterable, chunksize=None, callback=None, error_callback=None):
        self._check()
        return ApplyResult(lambda: [func(x) for x in iterable], (), {}, callback, error_callback)

    def imap(self, func, iterable, chunksize=1):
        self._check()
        return (func(x) for x in iterable)

    imap_unordered = imap

    def starmap(self, func, iterable, chunksize=None):
        self._check()
        return [func(*args) for args in iterable]

    def starmap_async(self, func, iterable, chunksize=None, callback=None, error_callback=None):
        self._check()
        return ApplyResult(lambda: [func(*args) for args in iterable], (), {}, callback, error_callback)

    def close(self):
        self._state = CLOSE

    def terminate(self):
        self._state = TERMINATE

    def join(self):
        if self._state == RUN:
            raise ValueError("Pool is still running")

    def __enter__(self):
        self._check()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.terminate()


class ThreadPool(Pool):
    pass
