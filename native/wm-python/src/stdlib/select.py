"""select, for a plugin, which has no files or sockets to wait on.

The constants and the error are here. Waiting on nothing with a timeout
returns at once with nothing ready; waiting on anything raises
PermissionError, since a plugin can hold no socket or file.
"""

error = OSError

POLLIN = 1
POLLPRI = 2
POLLOUT = 4
POLLERR = 8
POLLHUP = 16
POLLNVAL = 32
POLLRDNORM = 64
POLLRDBAND = 128
POLLWRNORM = 256
POLLWRBAND = 512
POLLMSG = 1024
POLLRDHUP = 8192
PIPE_BUF = 4096
EPOLLIN = 1
EPOLLPRI = 2
EPOLLOUT = 4
EPOLLERR = 8
EPOLLHUP = 16
EPOLLRDNORM = 64
EPOLLRDBAND = 128
EPOLLWRNORM = 256
EPOLLWRBAND = 512
EPOLLMSG = 1024
EPOLLRDHUP = 8192
EPOLLEXCLUSIVE = 1 << 28
EPOLLONESHOT = 1 << 30
EPOLLET = 1 << 31
EPOLL_CLOEXEC = 524288

_CANNOT = "plugins have no sockets or files to wait on"


def select(rlist, wlist, xlist, timeout=None):
    if not rlist and not wlist and not xlist:
        if timeout is None:
            raise RuntimeError("plugins run on one thread, so waiting on nothing with no timeout would never end")
        return [], [], []
    raise PermissionError(_CANNOT)


class poll:
    def __init__(self):
        self._fds = {}

    def register(self, fd, eventmask=POLLIN | POLLPRI | POLLOUT):
        raise PermissionError(_CANNOT)

    def modify(self, fd, eventmask):
        raise PermissionError(_CANNOT)

    def unregister(self, fd):
        raise KeyError(fd)

    def poll(self, timeout=None):
        return []


class epoll:
    def __init__(self, sizehint=-1, flags=0):
        self.closed = False

    def close(self):
        self.closed = True

    def fileno(self):
        raise ValueError("I/O operation on closed epoll object")

    def register(self, fd, eventmask=EPOLLIN | EPOLLPRI | EPOLLOUT):
        raise PermissionError(_CANNOT)

    def modify(self, fd, eventmask):
        raise PermissionError(_CANNOT)

    def unregister(self, fd):
        raise FileNotFoundError(2, "No such file or directory")

    def poll(self, timeout=None, maxevents=-1):
        return []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
