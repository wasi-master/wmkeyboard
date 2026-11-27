"""asyncio.streams: stream readers and writers. They live in the asyncio module;
this is where CPython keeps them."""

from asyncio import (
    StreamReader,
    StreamWriter,
    StreamReaderProtocol,
    open_connection,
    start_server,
    open_unix_connection,
    start_unix_server,
)

__all__ = ("StreamReader", "StreamWriter", "StreamReaderProtocol", "open_connection", "start_server", "open_unix_connection", "start_unix_server")
