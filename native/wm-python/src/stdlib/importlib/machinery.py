"""importlib.machinery: the finders, loaders and suffixes of CPython's import
system, as names packages can read.

Modules here come from the plugin and from this interpreter, never from
files on a disk, so the path-based finders find nothing and the file
loaders can't read. There are no compiled extension modules, so
EXTENSION_SUFFIXES is empty.
"""

import sys

from importlib.util import ModuleSpec

SOURCE_SUFFIXES = [".py"]
DEBUG_BYTECODE_SUFFIXES = [".pyc"]
OPTIMIZED_BYTECODE_SUFFIXES = [".pyc"]
BYTECODE_SUFFIXES = [".pyc"]
EXTENSION_SUFFIXES = []


def all_suffixes():
    """Every suffix a module file could have: source, bytecode and extension."""
    return SOURCE_SUFFIXES + BYTECODE_SUFFIXES + EXTENSION_SUFFIXES


def _importable(fullname):
    import importlib.util

    try:
        return importlib.util.find_spec(fullname) is not None
    except (ImportError, ValueError):
        return False


class BuiltinImporter:
    """Finds the modules built into the interpreter: those sys.builtin_module_names lists."""

    _ORIGIN = "built-in"

    @classmethod
    def find_spec(cls, fullname, path=None, target=None):
        if path is not None:
            return None
        names = getattr(sys, "builtin_module_names", ())
        if fullname in names or (not names and fullname in ("sys", "math", "time", "json", "re", "random")):
            return ModuleSpec(fullname, cls, origin=cls._ORIGIN)
        return None

    @staticmethod
    def create_module(spec):
        return None

    @staticmethod
    def exec_module(module):
        pass

    @classmethod
    def is_package(cls, fullname):
        return False

    @classmethod
    def get_code(cls, fullname):
        return None

    @classmethod
    def get_source(cls, fullname):
        return None


class FrozenImporter:
    """CPython's frozen modules: there are none here."""

    _ORIGIN = "frozen"

    @classmethod
    def find_spec(cls, fullname, path=None, target=None):
        return None

    @staticmethod
    def create_module(spec):
        return None

    @staticmethod
    def exec_module(module):
        pass

    @classmethod
    def is_package(cls, fullname):
        return False

    @classmethod
    def get_code(cls, fullname):
        return None

    @classmethod
    def get_source(cls, fullname):
        return None


class WindowsRegistryFinder:
    """Finds nothing: there is no Windows registry."""

    @classmethod
    def find_spec(cls, fullname, path=None, target=None):
        return None


class PathFinder:
    """CPython's finder over sys.path entries. Nothing here is on a path, so it finds nothing."""

    @staticmethod
    def invalidate_caches():
        pass

    @classmethod
    def find_spec(cls, fullname, path=None, target=None):
        return None

    @staticmethod
    def find_distributions(*args, **kwargs):
        from importlib.metadata import MetadataPathFinder

        return MetadataPathFinder.find_distributions(*args, **kwargs)


class FileFinder:
    """CPython's finder for one directory. No directory can be listed, so it finds nothing."""

    def __init__(self, path, *loader_details):
        self.path = path or "."
        self._loaders = [(suffix, loader) for loader, suffixes in loader_details for suffix in suffixes]

    def invalidate_caches(self):
        pass

    def find_spec(self, fullname, target=None):
        return None

    @classmethod
    def path_hook(cls, *loader_details):
        def path_hook_for_FileFinder(path):
            return cls(path, *loader_details)

        return path_hook_for_FileFinder

    def __repr__(self):
        return f"FileFinder({self.path!r})"


class FileLoader:
    """The base of the file loaders: it knows its module's name and path, but can't read the file."""

    def __init__(self, fullname, path):
        self.name = fullname
        self.path = path

    def __eq__(self, other):
        return self.__class__ == other.__class__ and self.__dict__ == other.__dict__

    def __hash__(self):
        return hash(self.name) ^ hash(self.path)

    def get_filename(self, name=None):
        return self.path

    def get_data(self, path):
        import _plugin_data

        data = _plugin_data.read(path)
        if data is None:
            raise PermissionError(f"a plugin can't read {path!r}: modules come from the plugin, not from files")
        return data

    def get_resource_reader(self, module):
        return None

    def is_package(self, fullname):
        filename = self.path.rpartition("/")[2]
        return filename.rpartition(".")[0] == "__init__"

    def create_module(self, spec):
        return None

    def exec_module(self, module):
        code = self.get_code(module.__name__)
        if code is None:
            raise ImportError(f"cannot load module {module.__name__!r} from {self.path!r}", name=module.__name__)
        exec(code, module.__dict__)

    def load_module(self, fullname=None):
        import importlib

        return importlib.import_module(fullname or self.name)


class SourceFileLoader(FileLoader):
    """Loads a module from a .py file: here, only from source handed to it some other way."""

    def path_stats(self, path):
        raise PermissionError(f"a plugin can't read {path!r}")

    def set_data(self, path, data, *, _mode=0o666):
        raise PermissionError(f"a plugin can't write {path!r}")

    def source_to_code(self, data, path="<string>"):
        if isinstance(data, (bytes, bytearray)):
            data = bytes(data).decode("utf-8")
        return compile(data, path, "exec")

    def get_source(self, fullname):
        from importlib.util import decode_source

        return decode_source(self.get_data(self.path))

    def get_code(self, fullname):
        return self.source_to_code(self.get_data(self.path), self.path)


class SourcelessFileLoader(FileLoader):
    """Loads a module from a .pyc file, which this interpreter can't run."""

    def get_code(self, fullname):
        raise ImportError(f"no bytecode can run here: {self.path!r}", name=fullname)

    def get_source(self, fullname):
        return None


class ExtensionFileLoader(FileLoader):
    """Loads a compiled extension module, which this interpreter can't run."""

    def create_module(self, spec):
        raise ImportError(f"compiled extension modules can't load in a plugin: {self.path!r}", name=spec.name)

    def exec_module(self, module):
        pass

    def is_package(self, fullname):
        return False

    def get_code(self, fullname):
        return None

    def get_source(self, fullname):
        return None


class NamespaceLoader:
    """The loader of a namespace package, a folder of modules with no __init__.py."""

    def __init__(self, name, path, path_finder):
        self._name = name
        self._path = path
        self._path_finder = path_finder

    def is_package(self, fullname):
        return True

    def get_source(self, fullname):
        return ""

    def get_code(self, fullname):
        return compile("", "<string>", "exec")

    def create_module(self, spec):
        return None

    def exec_module(self, module):
        pass

    def get_resource_reader(self, module):
        return None
