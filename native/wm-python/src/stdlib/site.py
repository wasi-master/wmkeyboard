"""site: where CPython looks for installed packages. A plugin's packages
are inside the plugin and nothing is installed beside the interpreter, so
there are no site-packages folders and no user site."""

import sys

ENABLE_USER_SITE = False
USER_SITE = None
USER_BASE = None
PREFIXES = [sys.prefix, sys.exec_prefix]


def getsitepackages(prefixes=None):
    """The site-packages folders: none here."""
    return []


def getuserbase():
    """The user base folder, which there is none of: an empty path."""
    return ""


def getusersitepackages():
    """The user's site-packages folder, which there is none of: an empty path."""
    return ""


def check_enableusersite():
    return False


def addsitedir(sitedir, known_paths=None):
    """Add a folder to sys.path: here it holds no modules, so only the path is added."""
    if sitedir not in sys.path:
        sys.path.append(sitedir)
    return known_paths


def addsitepackages(known_paths, prefixes=None):
    return known_paths


def addusersitepackages(known_paths):
    return known_paths


def removeduppaths():
    seen = set()
    kept = []
    for p in sys.path:
        if p not in seen:
            seen.add(p)
            kept.append(p)
    sys.path[:] = kept
    return seen


def abs_paths():
    pass


def setquit():
    pass


def setcopyright():
    pass


def sethelper():
    pass


def enablerlcompleter():
    pass


def venv(known_paths):
    return known_paths


def execsitecustomize():
    pass


def execusercustomize():
    pass


def main():
    """What CPython runs at start-up: nothing to set up here."""
