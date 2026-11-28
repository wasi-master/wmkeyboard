"""pkgutil: listing and walking modules, and get_data, as CPython's has them.

The modules are the plugin's own and the interpreter's: iter_modules()
lists what can be imported at the top level or inside a package, from
its __path__. Data files beside modules can't be read here, so get_data()
raises FileNotFoundError, as reading a file that isn't there would.
"""

import importlib
import os
import sys
from collections import namedtuple

__all__ = [
    "get_importer", "iter_importers", "get_loader", "find_loader", "walk_packages", "iter_modules", "get_data",
    "read_code", "extend_path", "ModuleInfo", "resolve_name",
]

ModuleInfo = namedtuple("ModuleInfo", "module_finder name ispkg")
ModuleInfo.__doc__ = "A namedtuple with minimal info about a module."

_PLUGIN_ROOT = "/plugin"
_LIBRARY_ROOT = "/lib/python3.13"


class _Finder:
    """What iter_modules names as each module's finder: the place it was listed from."""

    def __init__(self, path):
        self.path = path

    def find_spec(self, fullname, target=None):
        import importlib.util

        return importlib.util.find_spec(fullname)

    def __repr__(self):
        return f"<pkgutil finder for {self.path!r}>"


def _package_of(path):
    # "/plugin/pkg/sub" and "/lib/python3.13/email/mime" name the packages pkg.sub and email.mime.
    for root in (_PLUGIN_ROOT, _LIBRARY_ROOT):
        if path == root:
            return ""
        if path.startswith(root + "/"):
            return path[len(root) + 1:].strip("/").replace("/", ".")
    return None


def iter_modules(path=None, prefix=""):
    """ModuleInfo for every module at the top level, or right inside the
    packages whose __path__ entries [path] lists."""
    if path is None:
        packages = [("", None)]
    elif isinstance(path, str):
        raise ValueError("path must be None or list of paths to look for modules in")
    else:
        packages = [(_package_of(os.fspath(p)), p) for p in path]
    yielded = set()
    for package, entry in packages:
        if package is None:
            continue
        finder = _Finder(entry)
        for name, ispkg in __module_names__(package):
            if name in yielded or name.startswith("__"):
                continue
            yielded.add(name)
            yield ModuleInfo(finder, prefix + name, ispkg)


def walk_packages(path=None, prefix="", onerror=None):
    """ModuleInfo for every module below [path], going into each package:
    those are imported, to read their __path__."""

    def seen(p, m={}):
        if p in m:
            return True
        m[p] = True

    for info in iter_modules(path, prefix):
        yield info
        if info.ispkg:
            try:
                __import__(info.name)
            except ImportError:
                if onerror is not None:
                    onerror(info.name)
            except Exception:
                if onerror is not None:
                    onerror(info.name)
                else:
                    raise
            else:
                path = getattr(sys.modules[info.name], "__path__", None) or []
                path = [p for p in path if not seen(p)]
                yield from walk_packages(path, info.name + ".", onerror)


def get_importer(path_item):
    """The finder for a path entry: here, one that finds what imports do."""
    return _Finder(os.fsdecode(path_item))


def iter_importers(fullname=""):
    """The finders an import of [fullname] would ask."""
    if fullname.startswith("."):
        raise ImportError(f"Relative module name {fullname!r} not supported")
    if "." in fullname:
        pkg_name = fullname.rpartition(".")[0]
        pkg = importlib.import_module(pkg_name)
        path = getattr(pkg, "__path__", None)
        if path is None:
            return
    else:
        yield from sys.meta_path
        path = [_PLUGIN_ROOT]
    for item in path:
        yield get_importer(item)


def get_loader(module_or_name):
    """The loader of a module, or None."""
    if module_or_name in sys.modules:
        module_or_name = sys.modules[module_or_name]
        if module_or_name is None:
            return None
    if not isinstance(module_or_name, str):
        module = module_or_name
        loader = getattr(module, "__loader__", None)
        if loader is not None:
            return loader
        if getattr(module, "__spec__", None) is None:
            return None
        fullname = module.__name__
    else:
        fullname = module_or_name
    return find_loader(fullname)


def find_loader(fullname):
    """The loader of the module [fullname] names, or None."""
    if fullname.startswith("."):
        raise ImportError(f"Relative module name {fullname!r} not supported")
    import importlib.util

    try:
        spec = importlib.util.find_spec(fullname)
    except (ImportError, AttributeError, TypeError, ValueError) as ex:
        raise ImportError(f"Error while finding loader for {fullname!r} ({type(ex)}: {ex})") from ex
    return spec.loader if spec is not None else None


def get_data(package, resource):
    """The bytes of data file [resource] beside package's modules: the
    data files that came with it (see _plugin_data). FileNotFoundError
    when it has no such file, and None, as CPython's does, when the
    package isn't there."""
    import importlib.util

    spec = importlib.util.find_spec(package)
    if spec is None:
        return None
    mod = sys.modules.get(package) or importlib.import_module(package)
    if mod is None or not hasattr(mod, "__file__"):
        return None
    parts = resource.split("/")
    parts.insert(0, os.path.dirname(mod.__file__))
    path = os.path.join(*parts)
    import _plugin_data

    data = _plugin_data.read(path)
    if data is None:
        raise FileNotFoundError(2, "No such file or directory", path)
    return data


def read_code(stream):
    """The code object in a .pyc stream: none can run here, so None, as for a stream of the wrong magic number."""
    return None


def extend_path(path, name):
    """A package's __path__, extended with the other folders of the same
    name on sys.path: here a package is in one place, so path as it is."""
    if not isinstance(path, list):
        return path
    return path


def resolve_name(name):
    """The object a "pkg.mod:attr.attr" (or dotted) name points to."""
    import re

    pattern = re.compile(r"^(?P<pkg>(?!\d)(\w+)(\.(?!\d)(\w+))*)(?P<cln>:(?P<obj>(?!\d)(\w+)(\.(?!\d)(\w+))*)?)?$", re.UNICODE)
    m = pattern.match(name)
    if not m:
        raise ValueError(f"invalid format: {name!r}")
    gd = m.groupdict()
    if gd.get("cln"):
        mod = importlib.import_module(gd["pkg"])
        parts = gd.get("obj")
        parts = parts.split(".") if parts else []
    else:
        parts = name.split(".")
        modname = parts.pop(0)
        mod = importlib.import_module(modname)
        while parts:
            p = parts[0]
            s = f"{modname}.{p}"
            try:
                mod = importlib.import_module(s)
                parts.pop(0)
                modname = s
            except ImportError:
                break
    result = mod
    for p in parts:
        result = getattr(result, p)
    return result
