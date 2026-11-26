"""contextlib: contextmanager, suppress, nullcontext, closing, ExitStack,
redirect_stdout and ContextDecorator."""

import sys as _sys


class ContextDecorator:
    """A context manager that also works as a decorator."""

    def _recreate_cm(self):
        return self

    def __call__(self, func):
        def inner(*args, **kwds):
            with self._recreate_cm():
                return func(*args, **kwds)
        inner.__name__ = getattr(func, "__name__", "inner")
        inner.__wrapped__ = func
        return inner


class _GeneratorContextManager(ContextDecorator):
    def __init__(self, func, args, kwds):
        self.gen = func(*args, **kwds)
        self.func, self.args, self.kwds = func, args, kwds

    def _recreate_cm(self):
        return self.__class__(self.func, self.args, self.kwds)

    def __enter__(self):
        try:
            return next(self.gen)
        except StopIteration:
            raise RuntimeError("generator didn't yield") from None

    def __exit__(self, typ, value, traceback):
        if typ is None:
            try:
                next(self.gen)
            except StopIteration:
                return False
            raise RuntimeError("generator didn't stop")
        if value is None:
            value = typ()
        try:
            self.gen.throw(value)
        except StopIteration as exc:
            # Swallowed inside the generator: the with block's error is handled.
            return exc is not value
        except BaseException as exc:
            if exc is value:
                return False
            raise
        raise RuntimeError("generator didn't stop after throw()")


def contextmanager(func):
    """`@contextmanager` over a generator that yields once: the code before the
    yield runs on entry, the code after it on exit."""

    def helper(*args, **kwds):
        return _GeneratorContextManager(func, args, kwds)

    helper.__name__ = getattr(func, "__name__", "helper")
    helper.__doc__ = getattr(func, "__doc__", None)
    helper.__wrapped__ = func
    return helper


class suppress:
    """`with suppress(KeyError):` swallows the exceptions named."""

    def __init__(self, *exceptions):
        self._exceptions = exceptions

    def __enter__(self):
        return None

    def __exit__(self, exctype, excinst, exctb):
        return exctype is not None and issubclass(exctype, self._exceptions)


class nullcontext:
    def __init__(self, enter_result=None):
        self.enter_result = enter_result

    def __enter__(self):
        return self.enter_result

    def __exit__(self, *excinfo):
        return False


class closing:
    """`with closing(thing):` calls thing.close() on the way out."""

    def __init__(self, thing):
        self.thing = thing

    def __enter__(self):
        return self.thing

    def __exit__(self, *exc_info):
        self.thing.close()
        return False


class ExitStack:
    """Context managers and callbacks entered one by one, left in reverse."""

    def __init__(self):
        self._exit_callbacks = []

    def __enter__(self):
        return self

    def enter_context(self, cm):
        result = cm.__enter__()
        self._exit_callbacks.append((True, cm.__exit__))
        return result

    def push(self, exit):
        self._exit_callbacks.append((True, getattr(exit, "__exit__", exit)))
        return exit

    def callback(self, callback, *args, **kwds):
        def run(*exc):
            callback(*args, **kwds)
        self._exit_callbacks.append((False, run))
        return callback

    def pop_all(self):
        new = ExitStack()
        new._exit_callbacks = self._exit_callbacks
        self._exit_callbacks = []
        return new

    def close(self):
        self.__exit__(None, None, None)

    def __exit__(self, typ, value, tb):
        suppressed = False
        pending = None
        while self._exit_callbacks:
            is_exit, cb = self._exit_callbacks.pop()
            try:
                if is_exit:
                    if cb(typ, value, tb):
                        suppressed = True
                        typ, value, tb = None, None, None
                else:
                    cb(typ, value, tb)
            except BaseException as exc:
                typ, value, tb = type(exc), exc, None
                pending = exc
                suppressed = False
        if pending is not None:
            raise pending
        return suppressed


class _RedirectStream:
    _stream = "stdout"

    def __init__(self, new_target):
        self._new_target = new_target
        self._old_targets = []

    def __enter__(self):
        self._old_targets.append(getattr(_sys, self._stream))
        setattr(_sys, self._stream, self._new_target)
        return self._new_target

    def __exit__(self, exctype, excinst, exctb):
        setattr(_sys, self._stream, self._old_targets.pop())
        return False


class redirect_stdout(_RedirectStream):
    """`with redirect_stdout(buffer):` sends print's output to buffer.write."""


class redirect_stderr(_RedirectStream):
    _stream = "stderr"


class AbstractContextManager:
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return None


class aclosing:
    """Awaits thing.aclose() when the block ends."""

    def __init__(self, thing):
        self.thing = thing

    async def __aenter__(self):
        return self.thing

    async def __aexit__(self, *exc_info):
        await self.thing.aclose()
        return False


class AbstractAsyncContextManager:
    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc_value, traceback):
        return None


class _AsyncGeneratorContextManager:
    def __init__(self, gen):
        self.gen = gen

    async def __aenter__(self):
        try:
            return await self.gen.__anext__()
        except StopAsyncIteration:
            raise RuntimeError("generator didn't yield") from None

    async def __aexit__(self, typ, value, traceback):
        if typ is None:
            try:
                await self.gen.__anext__()
            except StopAsyncIteration:
                return False
            raise RuntimeError("generator didn't stop")
        if value is None:
            value = typ()
        try:
            await self.gen.athrow(value)
        except StopAsyncIteration:
            return True
        except BaseException as exc:
            if exc is value:
                return False
            raise
        raise RuntimeError("generator didn't stop after athrow()")


def asynccontextmanager(func):
    """An async generator that yields once, as an `async with` manager."""

    def helper(*args, **kwds):
        return _AsyncGeneratorContextManager(func(*args, **kwds))

    helper.__name__ = getattr(func, "__name__", "helper")
    helper.__doc__ = getattr(func, "__doc__", None)
    return helper


class AsyncExitStack:
    """ExitStack for async managers and callbacks, unwound in reverse."""

    def __init__(self):
        self._exits = []

    async def __aenter__(self):
        return self

    async def enter_async_context(self, cm):
        result = await cm.__aenter__()
        self._exits.append((True, cm.__aexit__))
        return result

    def enter_context(self, cm):
        result = cm.__enter__()
        self._exits.append((False, cm.__exit__))
        return result

    def push_async_callback(self, callback, /, *args, **kwds):
        async def _exit(exc_type, exc, tb):
            await callback(*args, **kwds)
        self._exits.append((True, _exit))
        return callback

    def callback(self, callback, /, *args, **kwds):
        def _exit(exc_type, exc, tb):
            callback(*args, **kwds)
        self._exits.append((False, _exit))
        return callback

    async def aclose(self):
        await self.__aexit__(None, None, None)

    async def __aexit__(self, exc_type, exc, tb):
        suppressed = False
        while self._exits:
            is_async, fn = self._exits.pop()
            try:
                result = (await fn(exc_type, exc, tb)) if is_async else fn(exc_type, exc, tb)
                if result:
                    suppressed = True
                    exc_type = exc = tb = None
            except BaseException as e:
                exc_type, exc, tb = type(e), e, None
                if not self._exits:
                    raise
        return suppressed
