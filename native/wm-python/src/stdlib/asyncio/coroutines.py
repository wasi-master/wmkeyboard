"""asyncio.coroutines: telling coroutines apart. They live in the asyncio module;
this is where CPython keeps them."""

from asyncio import (
    iscoroutinefunction,
    iscoroutine,
)

__all__ = ("iscoroutinefunction", "iscoroutine")


# What @asyncio.coroutine once marked generator coroutines with.
_is_coroutine = object()
