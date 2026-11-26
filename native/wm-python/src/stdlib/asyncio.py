"""asyncio: coroutines, run one after another.

There is no event loop here. Awaiting a coroutine runs it to its end there
and then, so tasks never overlap: create_task() runs its coroutine at once,
gather() awaits each in turn, and sleep() returns without waiting. Code
written for asyncio runs, in order; waiting on something only another task
could finish (an unset Event, an empty Queue) raises RuntimeError rather
than waiting forever.
"""

import _native

ALL_COMPLETED = "ALL_COMPLETED"
FIRST_COMPLETED = "FIRST_COMPLETED"
FIRST_EXCEPTION = "FIRST_EXCEPTION"


class CancelledError(BaseException):
    pass


class InvalidStateError(Exception):
    pass


class QueueEmpty(Exception):
    pass


class QueueFull(Exception):
    pass


TimeoutError = TimeoutError


def _never(what):
    return RuntimeError(f"{what} would wait forever: nothing else runs here to finish it")


def iscoroutine(obj):
    return type(obj).__name__ == "coroutine"


def iscoroutinefunction(func):
    import inspect

    # inspect also knows functions marked as coroutine functions (AsyncMock).
    return inspect.iscoroutinefunction(func) or _native.iscoroutinefunction(getattr(func, "__func__", func))


def _run_now(aw):
    """Runs an awaitable to its end from code that is not a coroutine."""
    if iscoroutine(aw):
        try:
            aw.send(None)
        except StopIteration as e:
            return e.value
        raise RuntimeError("coroutine did not finish")
    if isinstance(aw, Future):
        return aw.result()
    it = aw.__await__()
    try:
        while True:
            next(it)
    except StopIteration as e:
        return e.value


async def sleep(delay, result=None):
    return result


class Future:
    """A result to come. Here it has always come by the time anything awaits it."""

    _asyncio_future_blocking = False

    def __init__(self, *, loop=None):
        self._done = False
        self._result = None
        self._exception = None
        self._cancelled = False
        self._callbacks = []

    def done(self):
        return self._done

    def cancelled(self):
        return self._cancelled

    def cancel(self, msg=None):
        if self._done:
            return False
        self._cancelled = True
        self._exception = CancelledError(msg) if msg is not None else CancelledError()
        self._finish()
        return True

    def result(self):
        if not self._done:
            raise InvalidStateError("Result is not set.")
        if self._exception is not None:
            raise self._exception
        return self._result

    def exception(self):
        if not self._done:
            raise InvalidStateError("Exception is not set.")
        if self._cancelled:
            raise self._exception
        return self._exception

    def set_result(self, result):
        if self._done:
            raise InvalidStateError("invalid state")
        self._result = result
        self._finish()

    def set_exception(self, exception):
        if self._done:
            raise InvalidStateError("invalid state")
        if isinstance(exception, type):
            exception = exception()
        self._exception = exception
        self._finish()

    def add_done_callback(self, fn, *, context=None):
        if self._done:
            fn(self)
        else:
            self._callbacks.append(fn)

    def remove_done_callback(self, fn):
        before = len(self._callbacks)
        self._callbacks = [c for c in self._callbacks if c != fn]
        return before - len(self._callbacks)

    def get_loop(self):
        return _LOOP

    def _finish(self):
        self._done = True
        callbacks, self._callbacks = self._callbacks, []
        for cb in callbacks:
            cb(self)

    def __await__(self):
        if not self._done:
            raise _never("awaiting a future nothing has set")
        return self.result()
        yield

    __iter__ = __await__


_TASKS = [0]


class Task(Future):
    """A coroutine, run at once to its end when the task is made."""

    def __init__(self, coro, *, loop=None, name=None, context=None):
        super().__init__()
        _TASKS[0] += 1
        self._name = name if name is not None else f"Task-{_TASKS[0]}"
        self._coro = coro
        try:
            self.set_result(_run_now(coro))
        except CancelledError as e:
            self._cancelled = True
            self._exception = e
            self._finish()
        except BaseException as e:
            self.set_exception(e)

    def get_name(self):
        return self._name

    def set_name(self, value):
        self._name = str(value)

    def get_coro(self):
        return self._coro

    def __repr__(self):
        state = "cancelled" if self._cancelled else "finished"
        return f"<Task {state} name={self._name!r}>"


def create_task(coro, *, name=None, context=None):
    return Task(coro, name=name)


def ensure_future(coro_or_future, *, loop=None):
    if isinstance(coro_or_future, Future):
        return coro_or_future
    return Task(coro_or_future)


async def gather(*aws, return_exceptions=False):
    results = []
    for aw in aws:
        try:
            results.append(await aw)
        except Exception as e:
            if not return_exceptions:
                raise
            results.append(e)
    return results


async def wait_for(aw, timeout):
    return await aw


async def shield(aw):
    return await aw


async def wait(aws, *, timeout=None, return_when=ALL_COMPLETED):
    return set(ensure_future(a) for a in aws), set()


def as_completed(aws, *, timeout=None):
    return iter([ensure_future(a) for a in aws])


async def to_thread(func, /, *args, **kwargs):
    return func(*args, **kwargs)


def run(main, *, debug=None, loop_factory=None):
    if not iscoroutine(main):
        raise ValueError(f"a coroutine was expected, got {main!r}")
    return _run_now(main)


class Timeout:
    def __init__(self, when=None):
        self._when = when

    def when(self):
        return self._when

    def reschedule(self, when):
        self._when = when

    def expired(self):
        return False

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False


def timeout(delay):
    return Timeout(delay)


def timeout_at(when):
    return Timeout(when)


class TaskGroup:
    """Tasks started inside it; here each has finished when create_task returns."""

    def __init__(self):
        self._tasks = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        errors = [t._exception for t in self._tasks if t._exception is not None and not t._cancelled]
        if errors and exc_type is None:
            raise errors[0]
        return False

    def create_task(self, coro, *, name=None, context=None):
        task = Task(coro, name=name)
        self._tasks.append(task)
        return task


class Event:
    def __init__(self):
        self._set = False

    def is_set(self):
        return self._set

    def set(self):
        self._set = True

    def clear(self):
        self._set = False

    async def wait(self):
        if not self._set:
            raise _never("Event.wait()")
        return True


class Lock:
    def __init__(self):
        self._locked = False

    def locked(self):
        return self._locked

    async def acquire(self):
        if self._locked:
            raise _never("acquiring a held Lock")
        self._locked = True
        return True

    def release(self):
        if not self._locked:
            raise RuntimeError("Lock is not acquired.")
        self._locked = False

    async def __aenter__(self):
        await self.acquire()
        return None

    async def __aexit__(self, exc_type, exc, tb):
        self.release()
        return False


class Semaphore:
    def __init__(self, value=1):
        if value < 0:
            raise ValueError("Semaphore initial value must be >= 0")
        self._value = value

    def locked(self):
        return self._value == 0

    async def acquire(self):
        if self._value == 0:
            raise _never("acquiring a Semaphore at zero")
        self._value -= 1
        return True

    def release(self):
        self._value += 1

    async def __aenter__(self):
        await self.acquire()
        return None

    async def __aexit__(self, exc_type, exc, tb):
        self.release()
        return False


class BoundedSemaphore(Semaphore):
    def __init__(self, value=1):
        super().__init__(value)
        self._bound = value

    def release(self):
        if self._value >= self._bound:
            raise ValueError("BoundedSemaphore released too many times")
        self._value += 1


class Condition(Lock):
    async def wait(self):
        raise _never("Condition.wait()")

    def notify(self, n=1):
        pass

    def notify_all(self):
        pass


class Queue:
    def __init__(self, maxsize=0):
        self.maxsize = maxsize
        self._items = []
        self._unfinished = 0

    def _take(self):
        return self._items.pop(0)

    def qsize(self):
        return len(self._items)

    def empty(self):
        return not self._items

    def full(self):
        return 0 < self.maxsize <= len(self._items)

    def put_nowait(self, item):
        if self.full():
            raise QueueFull
        self._items.append(item)
        self._unfinished += 1

    def get_nowait(self):
        if not self._items:
            raise QueueEmpty
        return self._take()

    async def put(self, item):
        if self.full():
            raise _never("Queue.put() on a full queue")
        self.put_nowait(item)

    async def get(self):
        if not self._items:
            raise _never("Queue.get() on an empty queue")
        return self._take()

    def task_done(self):
        if self._unfinished <= 0:
            raise ValueError("task_done() called too many times")
        self._unfinished -= 1

    async def join(self):
        if self._unfinished:
            raise _never("Queue.join() with unfinished items")


class LifoQueue(Queue):
    def _take(self):
        return self._items.pop()


class PriorityQueue(Queue):
    def _take(self):
        self._items.sort()
        return self._items.pop(0)


class AbstractEventLoop:
    pass


class _Loop(AbstractEventLoop):
    """The one loop there is: it runs what it is given at once."""

    def __init__(self):
        self._closed = False

    def run_until_complete(self, future):
        return _run_now(future)

    def run_forever(self):
        raise _never("run_forever()")

    def create_task(self, coro, *, name=None, context=None):
        return Task(coro, name=name)

    def create_future(self):
        return Future()

    def call_soon(self, callback, *args, context=None):
        callback(*args)
        return _Handle()

    call_soon_threadsafe = call_soon

    def call_later(self, delay, callback, *args, context=None):
        callback(*args)
        return _Handle()

    def call_at(self, when, callback, *args, context=None):
        callback(*args)
        return _Handle()

    def time(self):
        import time

        return time.monotonic()

    async def run_in_executor(self, executor, func, *args):
        return func(*args)

    def is_running(self):
        return False

    def is_closed(self):
        return self._closed

    def close(self):
        self._closed = True

    async def shutdown_asyncgens(self):
        pass

    async def shutdown_default_executor(self):
        pass

    def set_debug(self, enabled):
        pass

    def get_debug(self):
        return False

    def set_exception_handler(self, handler):
        pass


class _Handle:
    def cancel(self):
        pass

    def cancelled(self):
        return False


_LOOP = _Loop()


def get_event_loop():
    return _LOOP


def get_running_loop():
    return _LOOP


def new_event_loop():
    return _Loop()


def set_event_loop(loop):
    global _LOOP
    if loop is not None:
        _LOOP = loop


def current_task(loop=None):
    return None


def all_tasks(loop=None):
    return set()


class Runner:
    def __init__(self, *, debug=None, loop_factory=None):
        self._loop = _Loop()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self._loop.close()
        return False

    def run(self, coro, *, context=None):
        return _run_now(coro)

    def get_loop(self):
        return self._loop

    def close(self):
        self._loop.close()
