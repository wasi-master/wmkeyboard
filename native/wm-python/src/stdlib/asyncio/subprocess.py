"""asyncio.subprocess: subprocesses, which plugins cannot start. They live in the asyncio module;
this is where CPython keeps them."""

from asyncio import (
    create_subprocess_exec,
    create_subprocess_shell,
)

__all__ = ("create_subprocess_exec", "create_subprocess_shell")
