"""atexit: functions to run when the program ends.

A plugin does not end the way a program does: the keyboard stops it. The
functions are kept, in order, and _run_exitfuncs() runs them for code that
asks, but nothing calls it by itself.
"""

_exitfuncs = []


def register(func, *args, **kwargs):
    _exitfuncs.append((func, args, kwargs))
    return func


def unregister(func):
    _exitfuncs[:] = [entry for entry in _exitfuncs if entry[0] != func]


def _run_exitfuncs():
    while _exitfuncs:
        func, args, kwargs = _exitfuncs.pop()
        try:
            func(*args, **kwargs)
        except SystemExit:
            pass


def _clear():
    del _exitfuncs[:]


def _ncallbacks():
    return len(_exitfuncs)
