"""importlib: importing a module by name at run time.

Only the modules bundled with a plugin and this interpreter's own can be
imported; there is no file system to search, so finders, loaders and
reload() do what makes sense for that.
"""

import sys

__all__ = ["import_module", "invalidate_caches", "reload"]


def _resolve_name(name, package, level):
    bits = package.rsplit(".", level - 1)
    if len(bits) < level:
        raise ImportError("attempted relative import beyond top-level package")
    base = bits[0]
    return f"{base}.{name}" if name else base


def import_module(name, package=None):
    level = 0
    if name.startswith("."):
        if not package:
            raise TypeError(f"the 'package' argument is required to perform a relative import for {name!r}")
        for character in name:
            if character != ".":
                break
            level += 1
        name = _resolve_name(name[level:], package, level)
    try:
        return __import__(name, fromlist=["__name__"])
    except ImportError as e:
        # Only the module asked for being missing; an import inside it failing stays as it was.
        if "no module named" in str(e).lower() and any(f"'{name[:i]}'" in str(e) for i in range(len(name), 0, -1) if i == len(name) or name[i] == "."):
            raise ModuleNotFoundError(f"No module named {name!r}") from None
        raise


def invalidate_caches():
    pass


def reload(module):
    return module
