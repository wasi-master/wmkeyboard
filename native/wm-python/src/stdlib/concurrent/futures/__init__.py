"""concurrent.futures for a plugin, which runs on one thread (see _base):
an executor runs each call when it is submitted."""

from concurrent.futures._base import (
    FIRST_COMPLETED, FIRST_EXCEPTION, ALL_COMPLETED, CancelledError, TimeoutError, InvalidStateError,
    BrokenExecutor, Future, Executor, wait, as_completed,
)
from concurrent.futures.thread import ThreadPoolExecutor
from concurrent.futures.process import ProcessPoolExecutor

__all__ = (
    "FIRST_COMPLETED", "FIRST_EXCEPTION", "ALL_COMPLETED", "CancelledError", "TimeoutError", "InvalidStateError",
    "BrokenExecutor", "Future", "Executor", "wait", "as_completed", "ProcessPoolExecutor", "ThreadPoolExecutor",
)
