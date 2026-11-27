"""sysconfig, for a plugin: there is no installation to describe.

get_config_var() answers None for every build setting, as CPython does for
one it does not know, and the path and scheme calls answer an empty
installation. get_platform() and get_python_version() say what runs.
"""

import sys

__all__ = [
    "get_config_h_filename", "get_config_var", "get_config_vars", "get_makefile_filename", "get_path",
    "get_path_names", "get_paths", "get_platform", "get_python_version", "get_scheme_names", "parse_config_h",
    "get_default_scheme", "get_preferred_scheme",
]

_SCHEMES = ("posix_prefix", "posix_home", "posix_user", "posix_venv", "venv")
_PATH_NAMES = ("stdlib", "platstdlib", "purelib", "platlib", "include", "platinclude", "scripts", "data")
_VARS = {"EXT_SUFFIX": ".so", "SOABI": None, "Py_GIL_DISABLED": 0, "Py_DEBUG": 0, "Py_ENABLE_SHARED": 0, "WITH_DOC_STRINGS": 1}


def get_python_version():
    return f"{sys.version_info[0]}.{sys.version_info[1]}"


def get_platform():
    return "android-24-arm64_v8a"


def get_config_var(name):
    if name in ("py_version_short", "VERSION"):
        return get_python_version()
    if name == "py_version_nodot":
        return get_python_version().replace(".", "")
    return _VARS.get(name)


def get_config_vars(*args):
    values = dict(_VARS)
    values.update(VERSION=get_python_version(), py_version_short=get_python_version(), py_version_nodot=get_python_version().replace(".", ""), prefix="", exec_prefix="", base="", platbase="", installed_base="", installed_platbase="", userbase="", abiflags="")
    if args:
        return [values.get(a) for a in args]
    return values


def get_scheme_names():
    return _SCHEMES


def get_default_scheme():
    return "posix_prefix"


def get_preferred_scheme(key):
    if key not in ("prefix", "home", "user"):
        raise ValueError(f"unknown key {key!r}")
    return {"prefix": "posix_prefix", "home": "posix_home", "user": "posix_user"}[key]


def get_path_names():
    return _PATH_NAMES


def get_paths(scheme=None, vars=None, expand=True):
    return {name: "" for name in _PATH_NAMES}


def get_path(name, scheme=None, vars=None, expand=True):
    if name not in _PATH_NAMES:
        raise KeyError(name)
    return ""


def get_config_h_filename():
    return ""


def get_makefile_filename():
    return ""


def parse_config_h(fp, vars=None):
    return {} if vars is None else vars


def is_python_build(check_home=None):
    return False
