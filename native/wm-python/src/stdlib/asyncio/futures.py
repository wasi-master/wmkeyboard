"""asyncio.futures: Future. They live in the asyncio module;
this is where CPython keeps them."""

from asyncio import (
    Future,
    wrap_future,
    isfuture,
)

__all__ = ("Future", "wrap_future", "isfuture")


def _chain_future(source, destination):
    """Copies source's outcome to destination; here source is always done."""
    if source.cancelled():
        destination.cancel()
    elif source.exception() is not None:
        destination.set_exception(source.exception())
    else:
        destination.set_result(source.result())
