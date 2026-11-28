"""importlib.abc: the abstract finders and loaders of CPython's import
system, for packages that subclass them or check against them. The
interpreter's own finders and loaders, in importlib.machinery, are
registered with them as CPython's are."""

import abc

from importlib import machinery

__all__ = [
    "Loader", "MetaPathFinder", "PathEntryFinder", "ResourceLoader", "InspectLoader", "ExecutionLoader",
    "FileLoader", "SourceLoader", "ResourceReader", "Traversable", "TraversableResources",
]


def _register(abstract_cls, *classes):
    for cls in classes:
        abstract_cls.register(cls)


class Loader(metaclass=abc.ABCMeta):
    """Abstract base class for import loaders."""

    def create_module(self, spec):
        """None, for the import system to make the module."""
        return None

    def load_module(self, fullname):
        """Load the module: here, by importing it by name."""
        if not hasattr(self, "exec_module"):
            raise ImportError
        import importlib

        return importlib.import_module(fullname)


class MetaPathFinder(metaclass=abc.ABCMeta):
    """Abstract base class for finders on sys.meta_path."""

    def invalidate_caches(self):
        pass


_register(MetaPathFinder, machinery.BuiltinImporter, machinery.FrozenImporter, machinery.PathFinder, machinery.WindowsRegistryFinder)


class PathEntryFinder(metaclass=abc.ABCMeta):
    """Abstract base class for path entry finders, used by PathFinder."""

    def invalidate_caches(self):
        pass


_register(PathEntryFinder, machinery.FileFinder)


class ResourceLoader(Loader):
    """A loader that can read data files beside its modules."""

    @abc.abstractmethod
    def get_data(self, path):
        raise OSError


class InspectLoader(Loader):
    """A loader that can tell about its modules: source, code, whether a package."""

    def is_package(self, fullname):
        raise ImportError

    def get_code(self, fullname):
        source = self.get_source(fullname)
        if source is None:
            return None
        return self.source_to_code(source)

    @abc.abstractmethod
    def get_source(self, fullname):
        raise ImportError

    @staticmethod
    def source_to_code(data, path="<string>"):
        if isinstance(data, (bytes, bytearray)):
            data = bytes(data).decode("utf-8")
        return compile(data, path, "exec")

    def exec_module(self, module):
        code = self.get_code(module.__name__)
        if code is None:
            raise ImportError(f"cannot load module {module.__name__!r} when get_code() returns None")
        exec(code, module.__dict__)


_register(InspectLoader, machinery.BuiltinImporter, machinery.FrozenImporter, machinery.NamespaceLoader)


class ExecutionLoader(InspectLoader):
    """An InspectLoader that knows its modules' file names."""

    @abc.abstractmethod
    def get_filename(self, fullname):
        raise ImportError

    def get_code(self, fullname):
        source = self.get_source(fullname)
        if source is None:
            return None
        try:
            path = self.get_filename(fullname)
        except ImportError:
            return self.source_to_code(source)
        return self.source_to_code(source, path)


_register(ExecutionLoader, machinery.ExtensionFileLoader)


class FileLoader(machinery.FileLoader, ResourceLoader, ExecutionLoader):
    """Abstract base class partially implementing the ResourceLoader and ExecutionLoader ABCs."""


_register(FileLoader, machinery.SourceFileLoader, machinery.SourcelessFileLoader)


class SourceLoader(ResourceLoader, ExecutionLoader):
    """A loader whose modules' source comes from get_data(): subclasses give
    get_data() and get_filename(), and path_mtime() if they like."""

    def path_mtime(self, path):
        raise OSError

    def path_stats(self, path):
        return {"mtime": self.path_mtime(path)}

    def set_data(self, path, data):
        pass

    def get_source(self, fullname):
        from importlib.util import decode_source

        return decode_source(self.get_data(self.get_filename(fullname)))


_register(SourceLoader, machinery.SourceFileLoader)


class ResourceReader(metaclass=abc.ABCMeta):
    """Abstract base class for loaders that provide resources."""

    @abc.abstractmethod
    def open_resource(self, resource):
        raise FileNotFoundError

    @abc.abstractmethod
    def resource_path(self, resource):
        raise FileNotFoundError

    @abc.abstractmethod
    def is_resource(self, path):
        raise FileNotFoundError

    @abc.abstractmethod
    def contents(self):
        return iter([])


class Traversable(metaclass=abc.ABCMeta):
    """An object with a subset of pathlib.Path methods, for a package's resources."""

    @abc.abstractmethod
    def iterdir(self):
        """Yield Traversable objects in self."""

    def read_bytes(self):
        with self.open("rb") as strm:
            return strm.read()

    def read_text(self, encoding=None):
        with self.open(encoding=encoding) as strm:
            return strm.read()

    @abc.abstractmethod
    def is_dir(self):
        """Whether self is a directory."""

    @abc.abstractmethod
    def is_file(self):
        """Whether self is a file."""

    def joinpath(self, *descendants):
        if not descendants:
            return self
        names = []
        for d in descendants:
            names.extend(str(d).replace("\\", "/").split("/"))
        target = names[0]
        matches = (traversable for traversable in self.iterdir() if traversable.name == target)
        try:
            match = next(matches)
        except StopIteration:
            raise FileNotFoundError(f"{target!r} not found in {self.name!r}") from None
        return match.joinpath(*names[1:])

    def __truediv__(self, child):
        return self.joinpath(child)

    @abc.abstractmethod
    def open(self, mode="r", *args, **kwargs):
        """Open the resource for reading."""

    @property
    @abc.abstractmethod
    def name(self):
        """The base name of this object without any parent references."""


class TraversableResources(ResourceReader):
    """The ResourceReader every resource reader can be, through files()."""

    @abc.abstractmethod
    def files(self):
        """A Traversable object for the loaded package."""

    def open_resource(self, resource):
        return self.files().joinpath(resource).open("rb")

    def resource_path(self, resource):
        raise FileNotFoundError(resource)

    def is_resource(self, path):
        return self.files().joinpath(path).is_file()

    def contents(self):
        return (item.name for item in self.files().iterdir())
