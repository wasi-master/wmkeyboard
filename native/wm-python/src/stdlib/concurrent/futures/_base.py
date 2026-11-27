"""Futures and executors for a plugin, which runs on one thread.

An executor runs each call when it is submitted and hands back a Future
that is already done, holding the result or the exception. Code written for
a pool of threads or processes therefore works, one call at a time, and
wait() and as_completed() find everything finished.
"""

import time as _time

FIRST_COMPLETED = "FIRST_COMPLETED"
FIRST_EXCEPTION = "FIRST_EXCEPTION"
ALL_COMPLETED = "ALL_COMPLETED"
_AS_COMPLETED = "_AS_COMPLETED"

PENDING = "PENDING"
RUNNING = "RUNNING"
CANCELLED = "CANCELLED"
CANCELLED_AND_NOTIFIED = "CANCELLED_AND_NOTIFIED"
FINISHED = "FINISHED"

_STATE_TO_DESCRIPTION_MAP = {PENDING: "pending", RUNNING: "running", CANCELLED: "cancelled", CANCELLED_AND_NOTIFIED: "cancelled", FINISHED: "finished"}


class Error(Exception):
    pass


class CancelledError(Error):
    pass


TimeoutError = TimeoutError


class InvalidStateError(Error):
    pass


class BrokenExecutor(RuntimeError):
    pass


class Future:
    def __init__(self):
        self._state = PENDING
        self._result = None
        self._exception = None
        self._done_callbacks = []

    def _invoke_callbacks(self):
        for callback in self._done_callbacks:
            try:
                callback(self)
            except Exception as e:
                import logging

                logging.getLogger("concurrent.futures").exception("exception calling callback for %r: %s", self, e)

    def __repr__(self):
        if self._state == FINISHED:
            if self._exception:
                return f"<{type(self).__name__} at {id(self):#x} state=finished raised {type(self._exception).__name__}>"
            return f"<{type(self).__name__} at {id(self):#x} state=finished returned {type(self._result).__name__}>"
        return f"<{type(self).__name__} at {id(self):#x} state={_STATE_TO_DESCRIPTION_MAP[self._state]}>"

    def cancel(self):
        if self._state in (RUNNING, FINISHED):
            return False
        if self._state in (CANCELLED, CANCELLED_AND_NOTIFIED):
            return True
        self._state = CANCELLED
        self._invoke_callbacks()
        return True

    def cancelled(self):
        return self._state in (CANCELLED, CANCELLED_AND_NOTIFIED)

    def running(self):
        return self._state == RUNNING

    def done(self):
        return self._state in (CANCELLED, CANCELLED_AND_NOTIFIED, FINISHED)

    def _wait(self):
        if self._state in (CANCELLED, CANCELLED_AND_NOTIFIED):
            raise CancelledError()
        if self._state != FINISHED:
            raise TimeoutError("plugins run on one thread, so nothing else will finish this future")

    def result(self, timeout=None):
        self._wait()
        if self._exception is not None:
            raise self._exception
        return self._result

    def exception(self, timeout=None):
        self._wait()
        return self._exception

    def add_done_callback(self, fn):
        if self.done():
            try:
                fn(self)
            except Exception as e:
                import logging

                logging.getLogger("concurrent.futures").exception("exception calling callback for %r: %s", self, e)
        else:
            self._done_callbacks.append(fn)

    def set_running_or_notify_cancel(self):
        if self._state == CANCELLED:
            self._state = CANCELLED_AND_NOTIFIED
            return False
        if self._state == PENDING:
            self._state = RUNNING
            return True
        raise RuntimeError(f"Future in unexpected state: {self._state}")

    def set_result(self, result):
        if self._state in (CANCELLED, CANCELLED_AND_NOTIFIED, FINISHED):
            raise InvalidStateError(f"{self._state}: {self!r}")
        self._result = result
        self._state = FINISHED
        self._invoke_callbacks()

    def set_exception(self, exception):
        if self._state in (CANCELLED, CANCELLED_AND_NOTIFIED, FINISHED):
            raise InvalidStateError(f"{self._state}: {self!r}")
        self._exception = exception
        self._state = FINISHED
        self._invoke_callbacks()

    @classmethod
    def __class_getitem__(cls, item):
        return cls


def _run(fn, args, kwargs):
    f = Future()
    f.set_running_or_notify_cancel()
    try:
        result = fn(*args, **kwargs)
    except BaseException as e:
        f.set_exception(e)
    else:
        f.set_result(result)
    return f


class Executor:
    _shutdown = False

    def submit(self, fn, /, *args, **kwargs):
        if self._shutdown:
            raise RuntimeError("cannot schedule new futures after shutdown")
        return _run(fn, args, kwargs)

    def map(self, fn, *iterables, timeout=None, chunksize=1, buffersize=None):
        futures = [self.submit(fn, *args) for args in zip(*iterables)]

        def results():
            for f in futures:
                yield f.result()

        return results()

    def shutdown(self, wait=True, *, cancel_futures=False):
        self._shutdown = True

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.shutdown(wait=True)
        return False


def as_completed(fs, timeout=None):
    fs = list(dict.fromkeys(fs))
    pending = [f for f in fs if not f.done()]
    for f in fs:
        if f.done():
            yield f
    if pending:
        raise TimeoutError(f"{len(pending)} (of {len(fs)}) futures unfinished")


class DoneAndNotDoneFutures(tuple):
    def __new__(cls, done, not_done):
        return tuple.__new__(cls, (done, not_done))

    @property
    def done(self):
        return self[0]

    @property
    def not_done(self):
        return self[1]


def wait(fs, timeout=None, return_when=ALL_COMPLETED):
    fs = set(fs)
    done = {f for f in fs if f.done()}
    return DoneAndNotDoneFutures(done, fs - done)
