"""multiprocessing.Process, for a plugin, which cannot start processes."""

import os as _os

_CANNOT = "plugins cannot start processes"


class BaseProcess:
    def __init__(self, group=None, target=None, name=None, args=(), kwargs=None, *, daemon=None):
        self._target = target
        self._args = tuple(args)
        self._kwargs = dict(kwargs or {})
        self._name = name or "Process-1"
        self.daemon = bool(daemon)
        self.exitcode = None
        self.pid = None

    def run(self):
        if self._target:
            self._target(*self._args, **self._kwargs)

    def start(self):
        raise PermissionError(_CANNOT)

    def terminate(self):
        pass

    def kill(self):
        pass

    def join(self, timeout=None):
        raise AssertionError("can only join a started process")

    def is_alive(self):
        return False

    def close(self):
        pass

    @property
    def name(self):
        return self._name

    @name.setter
    def name(self, name):
        self._name = str(name)

    @property
    def ident(self):
        return self.pid

    @property
    def authkey(self):
        return b""

    @property
    def sentinel(self):
        raise ValueError("process not started")


Process = BaseProcess


class _MainProcess(BaseProcess):
    def __init__(self):
        super().__init__(name="MainProcess")
        self.pid = _os.getpid()

    def is_alive(self):
        return True


_current = _MainProcess()


def current_process():
    return _current


def parent_process():
    return None


def active_children():
    return []
