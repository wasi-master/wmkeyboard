"""signal, for a plugin.

The signal numbers and handler constants are here, and signal() keeps the
handler it is given and hands back the one before, as CPython's does in
its main thread. The keyboard never delivers a signal to a plugin, so no
handler runs unless raise_signal() asks for it. alarm() and setitimer()
set nothing.
"""

from enum import IntEnum as _IntEnum


class Signals(_IntEnum):
    SIGHUP = 1
    SIGINT = 2
    SIGQUIT = 3
    SIGILL = 4
    SIGTRAP = 5
    SIGABRT = 6
    SIGBUS = 7
    SIGFPE = 8
    SIGKILL = 9
    SIGUSR1 = 10
    SIGSEGV = 11
    SIGUSR2 = 12
    SIGPIPE = 13
    SIGALRM = 14
    SIGTERM = 15
    SIGSTKFLT = 16
    SIGCHLD = 17
    SIGCONT = 18
    SIGSTOP = 19
    SIGTSTP = 20
    SIGTTIN = 21
    SIGTTOU = 22
    SIGURG = 23
    SIGXCPU = 24
    SIGXFSZ = 25
    SIGVTALRM = 26
    SIGPROF = 27
    SIGWINCH = 28
    SIGIO = 29
    SIGPWR = 30
    SIGSYS = 31
    SIGRTMIN = 34
    SIGRTMAX = 64


class Handlers(_IntEnum):
    SIG_DFL = 0
    SIG_IGN = 1


class Sigmasks(_IntEnum):
    SIG_BLOCK = 0
    SIG_UNBLOCK = 1
    SIG_SETMASK = 2


for _enum in (Signals, Handlers, Sigmasks):
    for _name, _member in _enum.__members__.items():
        globals()[_name] = _member
del _enum, _name, _member

SIGIOT = Signals.SIGABRT
SIGCLD = Signals.SIGCHLD
SIGPOLL = Signals.SIGIO
NSIG = 65
ITIMER_REAL = 0
ITIMER_VIRTUAL = 1
ITIMER_PROF = 2


class ItimerError(OSError):
    pass


def default_int_handler(signalnum, frame):
    raise KeyboardInterrupt


_handlers = {}


def _check(signalnum):
    if not 1 <= int(signalnum) < NSIG:
        raise ValueError("signal number out of range")


def getsignal(signalnum):
    _check(signalnum)
    if signalnum in _handlers:
        return _handlers[signalnum]
    return default_int_handler if signalnum == SIGINT else SIG_DFL


def signal(signalnum, handler):
    _check(signalnum)
    if signalnum in (SIGKILL, SIGSTOP):
        raise OSError(22, "Invalid argument")
    if not callable(handler) and handler not in (SIG_DFL, SIG_IGN):
        raise TypeError("signal handler must be signal.SIG_IGN, signal.SIG_DFL, or a callable object")
    previous = getsignal(signalnum)
    _handlers[signalnum] = handler
    return previous


def raise_signal(signalnum):
    handler = getsignal(signalnum)
    if callable(handler):
        handler(signalnum, None)
    elif handler == SIG_DFL and signalnum in (SIGINT,):
        raise KeyboardInterrupt


def strsignal(signalnum):
    try:
        return {
            1: "Hangup", 2: "Interrupt", 3: "Quit", 4: "Illegal instruction", 6: "Aborted", 8: "Floating point exception",
            9: "Killed", 10: "User defined signal 1", 11: "Segmentation fault", 12: "User defined signal 2",
            13: "Broken pipe", 14: "Alarm clock", 15: "Terminated", 17: "Child exited",
        }[int(signalnum)]
    except (KeyError, ValueError):
        return None


def valid_signals():
    return {Signals(n) if n in Signals._value2member_map_ else n for n in range(1, NSIG)}


def alarm(seconds):
    return 0


def pause():
    raise RuntimeError("plugins run on one thread, and no signal ever arrives, so pause() would never return")


def setitimer(which, seconds, interval=0.0):
    return (0.0, 0.0)


def getitimer(which):
    return (0.0, 0.0)


def siginterrupt(signalnum, flag):
    _check(signalnum)


def set_wakeup_fd(fd, *, warn_on_full_buffer=True):
    return -1


def pthread_kill(thread_id, signalnum):
    raise_signal(signalnum)


def pthread_sigmask(how, mask):
    return set()


def sigpending():
    return set()


def sigwait(sigset):
    raise RuntimeError("plugins run on one thread, and no signal ever arrives, so sigwait() would never return")
