"""getpass, for a plugin, which cannot read the terminal.

getpass() raises EOFError, as input() does, with no terminal to read.
getuser() reads LOGNAME, USER, LNAME and USERNAME, which plugins do not
have, and raises OSError then, as CPython 3.13 does when it cannot tell.
"""

import os

__all__ = ["getpass", "getuser", "GetPassWarning"]


class GetPassWarning(UserWarning):
    pass


def getpass(prompt="Password: ", stream=None):
    raise EOFError("plugins cannot read the terminal")


unix_getpass = win_getpass = fallback_getpass = getpass


def getuser():
    for name in ("LOGNAME", "USER", "LNAME", "USERNAME"):
        user = os.environ.get(name)
        if user:
            return user
    raise OSError("No username set in the environment")
