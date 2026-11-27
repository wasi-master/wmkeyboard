"""selectors, for a plugin, which has no files or sockets to wait on.

The classes are here to subclass and make. Registering a file object
raises PermissionError (see select); select() on an empty selector
returns at once with nothing ready.
"""

from abc import ABCMeta, abstractmethod
from collections import namedtuple
from collections.abc import Mapping

EVENT_READ = 1 << 0
EVENT_WRITE = 1 << 1

SelectorKey = namedtuple("SelectorKey", ["fileobj", "fd", "events", "data"])

_CANNOT = "plugins have no sockets or files to wait on"


class BaseSelector(metaclass=ABCMeta):
    @abstractmethod
    def register(self, fileobj, events, data=None):
        raise NotImplementedError

    @abstractmethod
    def unregister(self, fileobj):
        raise NotImplementedError

    def modify(self, fileobj, events, data=None):
        self.unregister(fileobj)
        return self.register(fileobj, events, data)

    @abstractmethod
    def select(self, timeout=None):
        raise NotImplementedError

    def close(self):
        pass

    def get_key(self, fileobj):
        mapping = self.get_map()
        if mapping is None:
            raise RuntimeError("Selector is closed")
        try:
            return mapping[fileobj]
        except KeyError:
            raise KeyError(f"{fileobj!r} is not registered") from None

    @abstractmethod
    def get_map(self):
        raise NotImplementedError

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class _BaseSelectorImpl(BaseSelector):
    def __init__(self):
        self._fd_to_key = {}

    def register(self, fileobj, events, data=None):
        if not events or events & ~(EVENT_READ | EVENT_WRITE):
            raise ValueError(f"Invalid events: {events!r}")
        raise PermissionError(_CANNOT)

    def unregister(self, fileobj):
        raise KeyError(f"{fileobj!r} is not registered")

    def select(self, timeout=None):
        if timeout is None:
            raise RuntimeError("plugins run on one thread, so waiting on nothing with no timeout would never end")
        return []

    def close(self):
        self._fd_to_key.clear()

    def get_map(self):
        return dict(self._fd_to_key)


class SelectSelector(_BaseSelectorImpl):
    pass


class PollSelector(_BaseSelectorImpl):
    pass


class EpollSelector(_BaseSelectorImpl):
    def fileno(self):
        raise ValueError("I/O operation on closed epoll object")


DefaultSelector = EpollSelector
