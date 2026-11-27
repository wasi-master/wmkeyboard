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


class IncompleteReadError(EOFError):
    def __init__(self, partial, expected):
        r_expected = "undefined" if expected is None else repr(expected)
        super().__init__(f"{len(partial)} bytes read on a total of {r_expected} expected bytes")
        self.partial = partial
        self.expected = expected


class LimitOverrunError(Exception):
    def __init__(self, message, consumed):
        super().__init__(message)
        self.consumed = consumed


class SendfileNotAvailableError(RuntimeError):
    pass


class BrokenBarrierError(RuntimeError):
    pass


class QueueShutDown(Exception):
    pass


_NO_NETWORK = "plugins cannot open connections themselves; use wm.http (from wm import http), or requests or httpx"
_NO_PROCESSES = "plugins cannot start processes"


def isfuture(obj):
    return hasattr(obj.__class__, "_asyncio_future_blocking") and obj._asyncio_future_blocking is not None


def wrap_future(future, *, loop=None):
    if isfuture(future):
        return future
    done = Future()
    try:
        done.set_result(future.result())
    except BaseException as e:
        done.set_exception(e)
    return done


def run_coroutine_threadsafe(coro, loop):
    import concurrent.futures
    out = concurrent.futures.Future()
    try:
        out.set_result(_run_now(coro))
    except BaseException as e:
        out.set_exception(e)
    return out


# ---- protocols and transports: the classes network code subclasses ---------

class BaseProtocol:
    __slots__ = ()

    def connection_made(self, transport):
        pass

    def connection_lost(self, exc):
        pass

    def pause_writing(self):
        pass

    def resume_writing(self):
        pass


class Protocol(BaseProtocol):
    __slots__ = ()

    def data_received(self, data):
        pass

    def eof_received(self):
        pass


class BufferedProtocol(BaseProtocol):
    __slots__ = ()

    def get_buffer(self, sizehint):
        raise NotImplementedError

    def buffer_updated(self, nbytes):
        raise NotImplementedError

    def eof_received(self):
        pass


class DatagramProtocol(BaseProtocol):
    __slots__ = ()

    def datagram_received(self, data, addr):
        pass

    def error_received(self, exc):
        pass


class SubprocessProtocol(BaseProtocol):
    __slots__ = ()

    def pipe_data_received(self, fd, data):
        pass

    def pipe_connection_lost(self, fd, exc):
        pass

    def process_exited(self):
        pass


class BaseTransport:
    __slots__ = ("_extra",)

    def __init__(self, extra=None):
        self._extra = {} if extra is None else extra

    def get_extra_info(self, name, default=None):
        return self._extra.get(name, default)

    def is_closing(self):
        raise NotImplementedError

    def close(self):
        raise NotImplementedError

    def set_protocol(self, protocol):
        raise NotImplementedError

    def get_protocol(self):
        raise NotImplementedError


class ReadTransport(BaseTransport):
    __slots__ = ()

    def is_reading(self):
        raise NotImplementedError

    def pause_reading(self):
        raise NotImplementedError

    def resume_reading(self):
        raise NotImplementedError


class WriteTransport(BaseTransport):
    __slots__ = ()

    def set_write_buffer_limits(self, high=None, low=None):
        raise NotImplementedError

    def get_write_buffer_size(self):
        raise NotImplementedError

    def get_write_buffer_limits(self):
        raise NotImplementedError

    def write(self, data):
        raise NotImplementedError

    def writelines(self, list_of_data):
        self.write(b"".join(list_of_data))

    def write_eof(self):
        raise NotImplementedError

    def can_write_eof(self):
        raise NotImplementedError

    def abort(self):
        raise NotImplementedError


class Transport(ReadTransport, WriteTransport):
    __slots__ = ()


class DatagramTransport(BaseTransport):
    __slots__ = ()

    def sendto(self, data, addr=None):
        raise NotImplementedError

    def abort(self):
        raise NotImplementedError


class SubprocessTransport(BaseTransport):
    __slots__ = ()

    def get_pid(self):
        raise NotImplementedError

    def get_returncode(self):
        raise NotImplementedError

    def get_pipe_transport(self, fd):
        raise NotImplementedError

    def send_signal(self, signal):
        raise NotImplementedError

    def terminate(self):
        raise NotImplementedError

    def kill(self):
        raise NotImplementedError


class AbstractServer:
    def close(self):
        raise NotImplementedError

    def get_loop(self):
        raise NotImplementedError

    def is_serving(self):
        raise NotImplementedError

    async def start_serving(self):
        raise NotImplementedError

    async def serve_forever(self):
        raise NotImplementedError

    async def wait_closed(self):
        raise NotImplementedError

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        self.close()
        await self.wait_closed()


Server = AbstractServer


# ---- streams: reading and writing a connection, which plugins never have ----

class StreamReader:
    def __init__(self, limit=2 ** 16, loop=None):
        self._buffer = bytearray()
        self._eof = False
        self._exception = None
        self._limit = limit

    def exception(self):
        return self._exception

    def set_exception(self, exc):
        self._exception = exc

    def set_transport(self, transport):
        self._transport = transport

    def feed_eof(self):
        self._eof = True

    def at_eof(self):
        return self._eof and not self._buffer

    def feed_data(self, data):
        self._buffer.extend(data)

    def _take(self, n):
        data = bytes(self._buffer[:n])
        del self._buffer[:n]
        return data

    async def read(self, n=-1):
        if self._exception is not None:
            raise self._exception
        if n < 0:
            if not self._eof:
                raise _never("read() of a stream not yet at its end")
            return self._take(len(self._buffer))
        if not self._buffer and not self._eof:
            raise _never("read() of an empty stream")
        return self._take(n)

    async def readline(self):
        try:
            return await self.readuntil(b"\n")
        except IncompleteReadError as e:
            return e.partial

    async def readuntil(self, separator=b"\n"):
        i = self._buffer.find(separator)
        if i >= 0:
            return self._take(i + len(separator))
        if self._eof:
            raise IncompleteReadError(self._take(len(self._buffer)), None)
        raise _never("readuntil() past what the stream holds")

    async def readexactly(self, n):
        if len(self._buffer) >= n:
            return self._take(n)
        if self._eof:
            raise IncompleteReadError(self._take(len(self._buffer)), n)
        raise _never("readexactly() past what the stream holds")

    def __aiter__(self):
        return self

    async def __anext__(self):
        line = await self.readline()
        if line == b"":
            raise StopAsyncIteration
        return line


class StreamWriter:
    def __init__(self, transport, protocol, reader, loop):
        self._transport = transport
        self._protocol = protocol
        self._reader = reader
        self._loop = loop

    @property
    def transport(self):
        return self._transport

    def write(self, data):
        self._transport.write(data)

    def writelines(self, data):
        self._transport.writelines(data)

    def write_eof(self):
        return self._transport.write_eof()

    def can_write_eof(self):
        return self._transport.can_write_eof()

    def close(self):
        return self._transport.close()

    def is_closing(self):
        return self._transport.is_closing()

    async def wait_closed(self):
        pass

    def get_extra_info(self, name, default=None):
        return self._transport.get_extra_info(name, default)

    async def drain(self):
        pass


class StreamReaderProtocol(Protocol):
    def __init__(self, stream_reader, client_connected_cb=None, loop=None):
        self._stream_reader = stream_reader


async def open_connection(host=None, port=None, *, limit=2 ** 16, **kwds):
    raise PermissionError(_NO_NETWORK)


async def start_server(client_connected_cb, host=None, port=None, *, limit=2 ** 16, **kwds):
    raise PermissionError(_NO_NETWORK)


async def open_unix_connection(path=None, *, limit=2 ** 16, **kwds):
    raise PermissionError(_NO_NETWORK)


async def start_unix_server(client_connected_cb, path=None, *, limit=2 ** 16, **kwds):
    raise PermissionError(_NO_NETWORK)


async def create_subprocess_exec(program, *args, stdin=None, stdout=None, stderr=None, limit=2 ** 16, **kwds):
    raise PermissionError(_NO_PROCESSES)


async def create_subprocess_shell(cmd, stdin=None, stdout=None, stderr=None, limit=2 ** 16, **kwds):
    raise PermissionError(_NO_PROCESSES)


class Barrier:
    def __init__(self, parties):
        if parties < 1:
            raise ValueError("parties must be > 0")
        self._parties = parties
        self._count = 0

    @property
    def parties(self):
        return self._parties

    @property
    def n_waiting(self):
        return self._count

    @property
    def broken(self):
        return False

    async def wait(self):
        self._count += 1
        if self._count < self._parties:
            raise _never("Barrier.wait() before every party arrives")
        self._count = 0
        return self._parties - 1

    async def reset(self):
        self._count = 0

    async def abort(self):
        raise BrokenBarrierError


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
        self._handler = handler

    def get_exception_handler(self):
        return getattr(self, "_handler", None)

    def default_exception_handler(self, context):
        import sys
        print(context.get("message", "Unhandled exception in event loop"), file=sys.stderr)

    def call_exception_handler(self, context):
        handler = self.get_exception_handler()
        if handler is None:
            self.default_exception_handler(context)
        else:
            handler(self, context)

    def get_task_factory(self):
        return None

    def set_task_factory(self, factory):
        pass

    def set_default_executor(self, executor):
        pass

    async def create_connection(self, protocol_factory, host=None, port=None, **kwargs):
        raise PermissionError(_NO_NETWORK)

    async def create_server(self, protocol_factory, host=None, port=None, **kwargs):
        raise PermissionError(_NO_NETWORK)

    async def create_datagram_endpoint(self, protocol_factory, local_addr=None, remote_addr=None, **kwargs):
        raise PermissionError(_NO_NETWORK)

    async def create_unix_connection(self, protocol_factory, path=None, **kwargs):
        raise PermissionError(_NO_NETWORK)

    async def create_unix_server(self, protocol_factory, path=None, **kwargs):
        raise PermissionError(_NO_NETWORK)

    async def getaddrinfo(self, host, port, *, family=0, type=0, proto=0, flags=0):
        raise PermissionError(_NO_NETWORK)

    async def getnameinfo(self, sockaddr, flags=0):
        raise PermissionError(_NO_NETWORK)

    async def sock_connect(self, sock, address):
        raise PermissionError(_NO_NETWORK)

    async def subprocess_exec(self, protocol_factory, *args, **kwargs):
        raise PermissionError(_NO_PROCESSES)

    async def subprocess_shell(self, protocol_factory, cmd, **kwargs):
        raise PermissionError(_NO_PROCESSES)

    def add_signal_handler(self, sig, callback, *args):
        raise NotImplementedError("plugins get no signals")

    def remove_signal_handler(self, sig):
        return False

    def add_reader(self, fd, callback, *args):
        raise NotImplementedError("plugins have no file descriptors to watch")

    add_writer = add_reader

    def remove_reader(self, fd):
        return False

    remove_writer = remove_reader


BaseEventLoop = _Loop
SelectorEventLoop = _Loop
DefaultEventLoop = _Loop


class Handle:
    """A callback the loop was given; here it has already run."""

    def __init__(self, callback=None, args=(), loop=None, context=None):
        self._callback = callback
        self._args = args
        self._cancelled = False

    def cancel(self):
        self._cancelled = True

    def cancelled(self):
        return self._cancelled

    def get_context(self):
        return None


class TimerHandle(Handle):
    def __init__(self, when=0.0, callback=None, args=(), loop=None, context=None):
        super().__init__(callback, args, loop, context)
        self._when = when

    def when(self):
        return self._when


_Handle = Handle

_LOOP = _Loop()
_running = [None]


def _get_running_loop():
    return _running[0]


def _set_running_loop(loop):
    _running[0] = loop


class AbstractEventLoopPolicy:
    def get_event_loop(self):
        raise NotImplementedError

    def set_event_loop(self, loop):
        raise NotImplementedError

    def new_event_loop(self):
        raise NotImplementedError


class DefaultEventLoopPolicy(AbstractEventLoopPolicy):
    def get_event_loop(self):
        return get_event_loop()

    def set_event_loop(self, loop):
        set_event_loop(loop)

    def new_event_loop(self):
        return new_event_loop()


BaseDefaultEventLoopPolicy = DefaultEventLoopPolicy
_POLICY = [None]


def get_event_loop_policy():
    if _POLICY[0] is None:
        _POLICY[0] = DefaultEventLoopPolicy()
    return _POLICY[0]


def set_event_loop_policy(policy):
    _POLICY[0] = policy


def get_child_watcher():
    raise NotImplementedError(_NO_PROCESSES)


def set_child_watcher(watcher):
    raise NotImplementedError(_NO_PROCESSES)


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


def _register_task(task):
    pass


def _unregister_task(task):
    pass


def _enter_task(loop, task):
    pass


def _leave_task(loop, task):
    pass


def eager_task_factory(loop, coro, *, name=None, context=None):
    return Task(coro, name=name)


def create_eager_task_factory(custom_task_constructor):
    def factory(loop, coro, *, name=None, context=None):
        return custom_task_constructor(coro, loop=loop, name=name, context=context)
    return factory


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
