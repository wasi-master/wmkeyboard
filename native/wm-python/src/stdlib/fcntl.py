"""fcntl, for a plugin, which has no files. The constants are here; every
call raises PermissionError."""

LOCK_SH = 1
LOCK_EX = 2
LOCK_NB = 4
LOCK_UN = 8
F_DUPFD = 0
F_GETFD = 1
F_SETFD = 2
F_GETFL = 3
F_SETFL = 4
F_GETLK = 5
F_SETLK = 6
F_SETLKW = 7
F_RDLCK = 0
F_WRLCK = 1
F_UNLCK = 2
FD_CLOEXEC = 1

_NO_FILES = "plugins have no file system"


def fcntl(fd, cmd, arg=0):
    raise PermissionError(_NO_FILES)


def ioctl(fd, request, arg=0, mutate_flag=True):
    raise PermissionError(_NO_FILES)


def flock(fd, operation):
    raise PermissionError(_NO_FILES)


def lockf(fd, cmd, len=0, start=0, whence=0):
    raise PermissionError(_NO_FILES)
