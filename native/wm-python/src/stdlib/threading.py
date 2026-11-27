"""threading, for a plugin that runs on one thread.

There is no other thread, so nobody else ever holds a lock, sets an event or
notifies a condition. A Thread runs its target when start() is called and is
done before start() returns; an exception in it goes to excepthook, as a
thread's would. A Timer never fires, since nothing would run it later.

Code that takes locks to be safe, or starts a thread for a short job, works
as written. Code that would wait for another thread raises RuntimeError
instead of hanging: a blocking acquire of a held lock, wait() with no
timeout on a condition or an unset event.
"""

import _thread
from _thread import get_ident, get_native_id, TIMEOUT_MAX, stack_size, ThreadError, allocate_lock

__all__ = [
    "get_ident", "active_count", "Condition", "current_thread", "enumerate", "main_thread", "TIMEOUT_MAX", "Event", "Lock",
    "RLock", "Semaphore", "BoundedSemaphore", "Thread", "Barrier", "BrokenBarrierError", "Timer", "ThreadError",
    "ExceptHookArgs", "setprofile", "settrace", "local", "stack_size", "excepthook", "get_native_id", "getprofile",
    "gettrace", "setprofile_all_threads", "settrace_all_threads",
]

Lock = _thread.LockType
RLock = _thread.RLock
local = _thread._local

_NO_OTHER_THREAD = "plugins run on one thread, so nothing else would {}"


def _forever(what):
    raise RuntimeError(_NO_OTHER_THREAD.format(what))


class Condition:
    def __init__(self, lock=None):
        self._lock = RLock() if lock is None else lock
        self.acquire = self._lock.acquire
        self.release = self._lock.release

    def __enter__(self):
        return self._lock.__enter__()

    def __exit__(self, *args):
        return self._lock.__exit__(*args)

    def __repr__(self):
        return f"<Condition({self._lock!r})>"

    def wait(self, timeout=None):
        if timeout is None:
            _forever("notify this condition")
        return False

    def wait_for(self, predicate, timeout=None):
        result = predicate()
        if not result and timeout is None:
            _forever("make this condition's predicate true")
        return result

    def notify(self, n=1):
        pass

    def notify_all(self):
        pass

    notifyAll = notify_all


class Semaphore:
    def __init__(self, value=1):
        if value < 0:
            raise ValueError("semaphore initial value must be >= 0")
        self._value = value

    def __repr__(self):
        return f"<{type(self).__name__} value={self._value}>"

    def acquire(self, blocking=True, timeout=None):
        if not blocking and timeout is not None:
            raise ValueError("can't specify timeout for non-blocking acquire")
        if self._value > 0:
            self._value -= 1
            return True
        if blocking and timeout is None:
            _forever("release this semaphore")
        return False

    __enter__ = acquire

    def release(self, n=1):
        if n < 1:
            raise ValueError("n must be one or more")
        self._value += n

    def __exit__(self, t, v, tb):
        self.release()


class BoundedSemaphore(Semaphore):
    def __init__(self, value=1):
        super().__init__(value)
        self._initial_value = value

    def release(self, n=1):
        if n < 1:
            raise ValueError("n must be one or more")
        if self._value + n > self._initial_value:
            raise ValueError("Semaphore released too many times")
        self._value += n


class Event:
    def __init__(self):
        self._flag = False

    def __repr__(self):
        return f"<{type(self).__name__} {'set' if self._flag else 'unset'}>"

    def is_set(self):
        return self._flag

    isSet = is_set

    def set(self):
        self._flag = True

    def clear(self):
        self._flag = False

    def wait(self, timeout=None):
        if self._flag:
            return True
        if timeout is None:
            _forever("set this event")
        return False


class BrokenBarrierError(RuntimeError):
    pass


class Barrier:
    def __init__(self, parties, action=None, timeout=None):
        if parties < 1:
            raise ValueError("parties must be >= 1")
        self._parties = parties
        self._action = action
        self._broken = False

    def wait(self, timeout=None):
        if self._broken:
            raise BrokenBarrierError
        if self._parties > 1:
            self._broken = True
            _forever("reach this barrier with it")
        if self._action:
            self._action()
        return 0

    def reset(self):
        self._broken = False

    def abort(self):
        self._broken = True

    @property
    def parties(self):
        return self._parties

    @property
    def n_waiting(self):
        return 0

    @property
    def broken(self):
        return self._broken


class ExceptHookArgs:
    def __init__(self, exc_type, exc_value, exc_traceback, thread):
        self.exc_type = exc_type
        self.exc_value = exc_value
        self.exc_traceback = exc_traceback
        self.thread = thread

    def __iter__(self):
        return iter((self.exc_type, self.exc_value, self.exc_traceback, self.thread))


def excepthook(args):
    if args.exc_type is SystemExit:
        return
    name = args.thread.name if args.thread is not None else get_ident()
    print(f"Exception in thread {name}: {args.exc_type.__name__}: {args.exc_value}")


__excepthook__ = excepthook

_counter = [0]


def _newname(name_template="Thread-%d"):
    _counter[0] += 1
    return name_template % _counter[0]


class Thread:
    _initialized = False

    def __init__(self, group=None, target=None, name=None, args=(), kwargs=None, *, daemon=None):
        if group is not None:
            raise AssertionError("group argument must be None for now")
        self._target = target
        self._name = str(name) if name else _newname()
        if target is not None and not name:
            try:
                self._name += f" ({target.__name__})"
            except AttributeError:
                pass
        self._args = args
        self._kwargs = {} if kwargs is None else kwargs
        self._daemonic = bool(daemon) if daemon is not None else False
        self._started = False
        self._ident = None
        self._native_id = None
        self._initialized = True

    def __repr__(self):
        status = "stopped" if self._started else "initial"
        if self._daemonic:
            status += " daemon"
        return f"<{type(self).__name__}({self._name}, {status})>"

    def start(self):
        if not self._initialized:
            raise RuntimeError("thread.__init__() not called")
        if self._started:
            raise RuntimeError("threads can only be started once")
        self._started = True
        self._ident = get_ident()
        self._native_id = get_native_id()
        global _current
        before = _current
        _current = self
        try:
            self.run()
        except BaseException as e:
            excepthook(ExceptHookArgs(type(e), e, None, self))
        finally:
            _current = before

    def run(self):
        try:
            if self._target is not None:
                self._target(*self._args, **self._kwargs)
        finally:
            del self._target, self._args, self._kwargs

    def join(self, timeout=None):
        if not self._initialized:
            raise RuntimeError("Thread.__init__() not called")
        if not self._started:
            raise RuntimeError("cannot join thread before it is started")
        if self is current_thread():
            raise RuntimeError("cannot join current thread")

    @property
    def name(self):
        return self._name

    @name.setter
    def name(self, name):
        self._name = str(name)

    @property
    def ident(self):
        return self._ident

    @property
    def native_id(self):
        return self._native_id

    def is_alive(self):
        return False

    @property
    def daemon(self):
        return self._daemonic

    @daemon.setter
    def daemon(self, daemonic):
        if self._started:
            raise RuntimeError("cannot set daemon status of active thread")
        self._daemonic = bool(daemonic)

    def isDaemon(self):
        return self._daemonic

    def setDaemon(self, daemonic):
        self.daemon = daemonic

    def getName(self):
        return self._name

    def setName(self, name):
        self.name = name


class _MainThread(Thread):
    def __init__(self):
        super().__init__(name="MainThread")
        self._started = True
        self._ident = get_ident()
        self._native_id = get_native_id()

    def is_alive(self):
        return True


class Timer(Thread):
    """Never fires: there is no later for a plugin's one thread to run it in."""

    def __init__(self, interval, function, args=None, kwargs=None):
        super().__init__()
        self.interval = interval
        self.function = function
        self.args = args if args is not None else []
        self.kwargs = kwargs if kwargs is not None else {}
        self.finished = Event()

    def start(self):
        if self._started:
            raise RuntimeError("threads can only be started once")
        self._started = True

    def cancel(self):
        self.finished.set()


_main = _MainThread()
_current = _main


def current_thread():
    return _current


currentThread = current_thread


def main_thread():
    return _main


def active_count():
    return 1


activeCount = active_count


def enumerate():
    return [_main]


_trace = None
_profile = None


def settrace(func):
    global _trace
    _trace = func


def settrace_all_threads(func):
    settrace(func)


def gettrace():
    return _trace


def setprofile(func):
    global _profile
    _profile = func


def setprofile_all_threads(func):
    setprofile(func)


def getprofile():
    return _profile


def _register_atexit(func, *arg, **kwargs):
    pass


def _shutdown():
    pass
