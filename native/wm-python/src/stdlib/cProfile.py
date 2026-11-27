"""cProfile: the same Profile as profile's. See that module: the keyboard's
Python records no calls, so the statistics are empty."""

from profile import Profile, run, runctx

__all__ = ["run", "runctx", "Profile"]


def label(code):
    if isinstance(code, str):
        return ("~", 0, code)
    return (code.co_filename, code.co_firstlineno, code.co_name)
