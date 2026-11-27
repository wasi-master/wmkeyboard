"""profile: a profiler for code that imports one. The keyboard's Python
gives no hook into calls (sys.setprofile), so a Profile runs what it is
given and records nothing: its statistics are empty. To see where a
plugin spends its time, run `wmpy profile` on a computer."""

import sys as _sys

__all__ = ["run", "runctx", "Profile"]


class Profile:
    def __init__(self, timer=None, bias=None, *args, **kwargs):
        self.stats = {}
        self.timer = timer

    def enable(self, subcalls=True, builtins=True):
        pass

    def disable(self):
        pass

    def __enter__(self):
        self.enable()
        return self

    def __exit__(self, *exc_info):
        self.disable()

    def runcall(self, func, /, *args, **kw):
        return func(*args, **kw)

    def run(self, cmd):
        import __main__
        return self.runctx(cmd, __main__.__dict__, __main__.__dict__)

    def runctx(self, cmd, globals, locals):
        exec(cmd, globals, locals)
        return self

    def create_stats(self):
        self.stats = {}

    def snapshot_stats(self):
        self.stats = {}

    def getstats(self):
        return []

    def print_stats(self, sort=-1):
        import pstats
        pstats.Stats(self).strip_dirs().sort_stats(sort).print_stats()

    def dump_stats(self, file):
        raise PermissionError(13, "plugins have no file system")


def run(statement, filename=None, sort=-1):
    prof = Profile()
    prof.run(statement)
    if filename is not None:
        prof.dump_stats(filename)
    else:
        prof.print_stats(sort)


def runctx(statement, globals, locals, filename=None, sort=-1):
    prof = Profile()
    prof.runctx(statement, globals, locals)
    if filename is not None:
        prof.dump_stats(filename)
    else:
        prof.print_stats(sort)
