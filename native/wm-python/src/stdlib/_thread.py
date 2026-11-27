"""_thread, for a plugin that runs on one thread (see threading).

A lock that is held stays held until this same thread releases it, so a
blocking acquire of one raises RuntimeError rather than waiting forever.
start_new_thread runs the function before it returns.
"""

TIMEOUT_MAX = 4294967.0

error = RuntimeError
ThreadError = RuntimeError


def get_ident():
    return 1


def get_native_id():
    return 1


def stack_size(size=0):
    return 0


def _count():
    return 0


def daemon_threads_allowed():
    return True


def interrupt_main(signum=2):
    raise KeyboardInterrupt


def exit():
    raise SystemExit


exit_thread = exit


class LockType:
    def __init__(self):
        self._locked = False

    def __repr__(self):
        return f"<{'locked' if self._locked else 'unlocked'} _thread.lock object>"

    def acquire(self, blocking=True, timeout=-1):
        if not blocking and timeout != -1:
            raise ValueError("can't specify a timeout for a non-blocking call")
        if not self._locked:
            self._locked = True
            return True
        if blocking and timeout < 0:
            raise RuntimeError("this lock is already held, and plugins run on one thread, so nothing else would release it")
        return False

    acquire_lock = acquire

    def release(self):
        if not self._locked:
            raise RuntimeError("release unlocked lock")
        self._locked = False

    release_lock = release

    def locked(self):
        return self._locked

    locked_lock = locked

    def _at_fork_reinit(self):
        self._locked = False

    def __enter__(self):
        return self.acquire()

    def __exit__(self, *args):
        self.release()


lock = LockType


def allocate_lock():
    return LockType()


allocate = allocate_lock


class RLock:
    def __init__(self):
        self._count = 0

    def __repr__(self):
        return f"<{'locked' if self._count else 'unlocked'} _thread.RLock object owner={1 if self._count else 0} count={self._count}>"

    def acquire(self, blocking=True, timeout=-1):
        self._count += 1
        return True

    def release(self):
        if not self._count:
            raise RuntimeError("cannot release un-acquired lock")
        self._count -= 1

    def locked(self):
        return self._count > 0

    def _is_owned(self):
        return self._count > 0

    def _at_fork_reinit(self):
        self._count = 0

    def __enter__(self):
        return self.acquire()

    def __exit__(self, *args):
        self.release()


def start_new_thread(function, args, kwargs=None):
    try:
        function(*args, **(kwargs or {}))
    except SystemExit:
        pass
    except BaseException as e:
        print(f"Unhandled exception in thread started by {function!r}: {type(e).__name__}: {e}")
    return get_ident()


start_new = start_new_thread


class _local:
    """Attributes of the one thread there is: an ordinary object."""
