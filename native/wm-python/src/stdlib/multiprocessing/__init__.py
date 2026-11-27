"""multiprocessing, for a plugin, which cannot start processes.

A plugin is one process on one thread. cpu_count(), current_process() and
the start-method calls answer for that; the locks, events and queues are
threading's and queue's, which work on one thread. Starting a Process
raises PermissionError. A Pool (and multiprocessing.pool.ThreadPool) runs
each task in the plugin itself, as concurrent.futures' executors do.
"""

import os as _os
import threading as _threading
import queue as _queue

from multiprocessing.process import Process, current_process, parent_process, active_children
from multiprocessing.context import (
    ProcessError, BufferTooShort, TimeoutError, AuthenticationError, get_context, get_start_method,
    set_start_method, get_all_start_methods, set_executable, set_forkserver_preload,
)

__all__ = [
    "Process", "current_process", "parent_process", "active_children", "ProcessError", "BufferTooShort",
    "TimeoutError", "AuthenticationError", "get_context", "get_start_method", "set_start_method",
    "get_all_start_methods", "cpu_count", "freeze_support", "Lock", "RLock", "Event", "Condition", "Semaphore",
    "BoundedSemaphore", "Barrier", "Queue", "SimpleQueue", "JoinableQueue", "Pool", "Manager", "Pipe", "Value",
    "Array", "log_to_stderr", "get_logger", "allow_connection_pickling", "set_executable",
]

SUBDEBUG = 5
SUBWARNING = 25


def cpu_count():
    return _os.cpu_count() or 1


def freeze_support():
    pass


def allow_connection_pickling():
    pass


Lock = _threading.Lock
RLock = _threading.RLock
Event = _threading.Event
Condition = _threading.Condition
Semaphore = _threading.Semaphore
BoundedSemaphore = _threading.BoundedSemaphore
Barrier = _threading.Barrier


class Queue(_queue.Queue):
    def close(self):
        pass

    def join_thread(self):
        pass

    def cancel_join_thread(self):
        pass


JoinableQueue = Queue
SimpleQueue = _queue.SimpleQueue if hasattr(_queue, "SimpleQueue") else Queue


def Pool(processes=None, initializer=None, initargs=(), maxtasksperchild=None, context=None):
    from multiprocessing.pool import Pool as _Pool

    return _Pool(processes, initializer, initargs, maxtasksperchild)


def Manager():
    raise PermissionError("plugins cannot start processes, and a Manager runs in one")


def Pipe(duplex=True):
    raise PermissionError("plugins cannot start processes, so there is nothing to make a pipe to")


class _Shared:
    def __init__(self, value, lock=True):
        self.value = value
        self._lock = _threading.RLock()

    def get_lock(self):
        return self._lock

    def get_obj(self):
        return self.value


def Value(typecode_or_type, *args, lock=True):
    return _Shared(args[0] if args else 0, lock)


def Array(typecode_or_type, size_or_initializer, *, lock=True):
    items = [0] * size_or_initializer if isinstance(size_or_initializer, int) else list(size_or_initializer)
    return items


def get_logger():
    import logging

    return logging.getLogger("multiprocessing")


def log_to_stderr(level=None):
    logger = get_logger()
    if level:
        logger.setLevel(level)
    return logger
