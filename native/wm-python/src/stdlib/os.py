"""os: the parts that compute and touch nothing: path joining and
splitting (os.path), the separators, and an empty environment. Plugins
read and write files through wm.storage and wm.files instead: the file
functions are here so code that names them loads, and raise
PermissionError when called."""

import abc as _abc

from . import path

name = "posix"
sep = "/"
altsep = None
extsep = "."
pathsep = ":"
linesep = "\n"
curdir = "."
pardir = ".."
defpath = "/bin:/usr/bin"
devnull = "/dev/null"
environ = {}

# Constants code reads to talk to file objects (io.BytesIO.seek) and to
# os.open, which plugins have none of.
SEEK_SET = 0
SEEK_CUR = 1
SEEK_END = 2
O_RDONLY = 0
O_WRONLY = 1
O_RDWR = 2
O_CREAT = 64
O_EXCL = 128
O_NOCTTY = 256
O_TRUNC = 512
O_APPEND = 1024
O_NONBLOCK = 2048
O_CLOEXEC = 524288
O_BINARY = 0
F_OK = 0
X_OK = 1
W_OK = 2
R_OK = 4


class terminal_size(tuple):
    """A terminal's size, (columns, lines), as os.get_terminal_size gives it."""

    n_fields = n_sequence_fields = 2
    n_unnamed_fields = 0

    def __new__(cls, iterable):
        values = tuple(iterable)
        if len(values) != 2:
            raise TypeError(f"os.terminal_size() takes a 2-sequence ({len(values)}-sequence given)")
        return tuple.__new__(cls, values)

    @property
    def columns(self):
        return self[0]

    @property
    def lines(self):
        return self[1]

    def __repr__(self):
        return f"os.terminal_size(columns={self[0]}, lines={self[1]})"


def get_terminal_size(fd=1):
    """A plugin has no terminal: OSError, as CPython's is with none attached."""
    raise OSError(25, "Inappropriate ioctl for device")


def urandom(size, /):
    """size bytes from the system's randomness, for cryptographic use."""
    import _native
    if size < 0:
        raise ValueError("negative argument not allowed")
    return _native.urandom(size)


def getenv(key, default=None):
    return environ.get(key, default)


def fspath(p):
    if isinstance(p, (str, bytes)):
        return p
    if hasattr(p, "__fspath__"):
        return p.__fspath__()
    raise TypeError(f"expected str, bytes or os.PathLike object, not {type(p).__name__}")


class PathLike(_abc.ABC):
    """Abstract base class for implementing the file system path protocol."""

    __slots__ = ()

    @_abc.abstractmethod
    def __fspath__(self):
        """Return the file system path representation of the object."""
        raise NotImplementedError

    @classmethod
    def __subclasshook__(cls, subclass):
        if cls is PathLike:
            return hasattr(subclass, "__fspath__") or NotImplemented
        return NotImplemented

    def __class_getitem__(cls, item):
        from types import GenericAlias
        return GenericAlias(cls, item)

    __class_getitem__ = classmethod(__class_getitem__)


def cpu_count():
    return 1


def process_cpu_count():
    return 1


def getpid():
    return 0


def getppid():
    return 0


# ---- what a process asks about itself ----------------------------------------
# A plugin is not a process of its own: these answer as a sandboxed app does.

def getuid():
    return 0


geteuid = getgid = getegid = getuid


def getgroups():
    return []


def getlogin():
    raise OSError(6, "No such device or address")


def umask(mask, /):
    return 0o022


def getcwd():
    """A plugin has no working folder of its own: the root."""
    return "/"


def getcwdb():
    return b"/"


def get_exec_path(env=None):
    return []


def uname():
    return uname_result(("Linux", "localhost", "", "", "aarch64"))


class uname_result(tuple):
    n_fields = n_sequence_fields = 5
    n_unnamed_fields = 0

    def __new__(cls, iterable):
        return tuple.__new__(cls, tuple(iterable))

    sysname = property(lambda self: self[0])
    nodename = property(lambda self: self[1])
    release = property(lambda self: self[2])
    version = property(lambda self: self[3])
    machine = property(lambda self: self[4])


def getloadavg():
    raise OSError("Load averages are unobtainable")


def times():
    import time
    t = time.process_time()
    return times_result((t, 0.0, 0.0, 0.0, time.monotonic()))


class times_result(tuple):
    def __new__(cls, iterable):
        return tuple.__new__(cls, tuple(iterable))

    user = property(lambda self: self[0])
    system = property(lambda self: self[1])
    children_user = property(lambda self: self[2])
    children_system = property(lambda self: self[3])
    elapsed = property(lambda self: self[4])


def strerror(code, /):
    import errno as _errno
    name = _errno.errorcode.get(code)
    return _MESSAGES.get(name, f"Unknown error {code}")


_MESSAGES = {
    "EPERM": "Operation not permitted", "ENOENT": "No such file or directory", "ESRCH": "No such process",
    "EINTR": "Interrupted system call", "EIO": "Input/output error", "EBADF": "Bad file descriptor",
    "EAGAIN": "Resource temporarily unavailable", "ENOMEM": "Cannot allocate memory", "EACCES": "Permission denied",
    "EEXIST": "File exists", "ENOTDIR": "Not a directory", "EISDIR": "Is a directory", "EINVAL": "Invalid argument",
    "ENOSPC": "No space left on device", "EPIPE": "Broken pipe", "ENOTEMPTY": "Directory not empty",
    "ETIMEDOUT": "Connection timed out", "ECONNREFUSED": "Connection refused", "ECONNRESET": "Connection reset by peer",
}


# ---- the environment ---------------------------------------------------------

def putenv(key, value, /):
    environ[key] = value


def unsetenv(key, /):
    environ.pop(key, None)


class _Environ(dict):
    """The type of os.environ in CPython, for code that names it."""


environb = {}


def getenvb(key, default=None):
    return environb.get(key, default)


# ---- names as bytes ------------------------------------------------------------

def fsencode(filename):
    filename = fspath(filename)
    return filename.encode("utf-8", "surrogateescape") if isinstance(filename, str) else filename


def fsdecode(filename):
    filename = fspath(filename)
    return filename.decode("utf-8", "surrogateescape") if isinstance(filename, bytes) else filename


def get_blocking(fd, /):
    raise OSError(9, "Bad file descriptor")


# ---- files: plugins have none -------------------------------------------------
# Defined so code that names them loads; calling one raises, as it would in
# a folder the app may not read. Plugins keep data with wm.storage and open
# the user's files with wm.files. fork, exec*, spawn* and startfile stay
# out: code tests `hasattr(os, "fork")` to learn whether it may fork.

_NO_FILES = "plugins have no file system: keep data with wm.storage, and open the user's files with wm.files"


def _refusing(name):
    def refuse(*args, **kwargs):
        raise PermissionError(13, _NO_FILES)
    refuse.__name__ = refuse.__qualname__ = name
    return refuse


for _name in (
    "access", "chdir", "chmod", "chown", "lchown", "close", "closerange", "dup", "dup2", "fchmod", "fchown",
    "fdatasync", "fstat", "fsync", "ftruncate", "isatty", "link", "listdir", "lseek", "lstat", "mkdir", "mkfifo",
    "makedirs", "open", "pipe", "read", "readlink", "remove", "removedirs", "rename", "renames", "replace", "rmdir",
    "scandir", "stat", "statvfs", "symlink", "truncate", "unlink", "utime", "write", "sendfile", "fdopen",
    "chroot", "mknod", "pread", "pwrite", "readv", "writev", "kill", "killpg", "system", "popen",
    "waitpid", "wait", "nice", "get_inheritable", "set_inheritable", "set_blocking",
    "getxattr", "setxattr", "listxattr", "removexattr",
):
    globals()[_name] = _refusing(_name)
del _name


def walk(top, topdown=True, onerror=None, followlinks=False):
    """Walks nothing: listing a folder raises, and onerror hears of it."""
    try:
        listdir(top)
    except OSError as e:
        if onerror is not None:
            onerror(e)
    return iter(())


def fwalk(top=".", topdown=True, onerror=None, *, follow_symlinks=False, dir_fd=None):
    return walk(top, topdown, onerror)


class stat_result(tuple):
    n_fields = n_sequence_fields = 10
    n_unnamed_fields = 0

    def __new__(cls, iterable, dict=None):
        return tuple.__new__(cls, tuple(iterable))

    st_mode = property(lambda self: self[0])
    st_ino = property(lambda self: self[1])
    st_dev = property(lambda self: self[2])
    st_nlink = property(lambda self: self[3])
    st_uid = property(lambda self: self[4])
    st_gid = property(lambda self: self[5])
    st_size = property(lambda self: self[6])
    st_atime = property(lambda self: self[7])
    st_mtime = property(lambda self: self[8])
    st_ctime = property(lambda self: self[9])


class DirEntry:
    """The type os.scandir yields; there are none to yield."""

    def __init__(self, *args, **kwargs):
        raise TypeError("cannot create 'os.DirEntry' instances")


error = OSError
supports_bytes_environ = True
supports_dir_fd = set()
supports_effective_ids = set()
supports_fd = set()
supports_follow_symlinks = set()
P_WAIT = 0
P_NOWAIT = 1
P_NOWAITO = 1
WNOHANG = 1
EX_OK = 0
