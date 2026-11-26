"""importlib.util: finding modules without importing them."""

import importlib


class ModuleSpec:
    def __init__(self, name, loader=None, *, origin=None, loader_state=None, is_package=None):
        self.name = name
        self.loader = loader
        self.origin = origin
        self.loader_state = loader_state
        self.submodule_search_locations = [] if is_package else None
        self.cached = None
        self.has_location = False

    @property
    def parent(self):
        if self.submodule_search_locations is None:
            return self.name.rpartition(".")[0]
        return self.name

    def __repr__(self):
        return f"ModuleSpec(name={self.name!r}, loader={self.loader!r}, origin={self.origin!r})"


def find_spec(name, package=None):
    if name.startswith("."):
        name = importlib._resolve_name(name.lstrip("."), package, len(name) - len(name.lstrip(".")))
    try:
        module = importlib.import_module(name)
    except ImportError:
        return None
    return ModuleSpec(name, None, origin="built-in", is_package=hasattr(module, "__path__"))


def resolve_name(name, package):
    if not name.startswith("."):
        return name
    level = len(name) - len(name.lstrip("."))
    return importlib._resolve_name(name[level:], package, level)


def module_from_spec(spec):
    return importlib.import_module(spec.name)


def spec_from_loader(name, loader, *, origin=None, is_package=None):
    return ModuleSpec(name, loader, origin=origin, is_package=is_package)


def spec_from_file_location(name, location=None, *, loader=None, submodule_search_locations=None):
    return None


class LazyLoader:
    def __init__(self, loader):
        self.loader = loader

    @classmethod
    def factory(cls, loader):
        return lambda *args, **kwargs: cls(loader(*args, **kwargs))


MAGIC_NUMBER = b"\xf3\r\r\n"


def source_hash(source_bytes):
    import hashlib

    return hashlib.sha256(source_bytes).digest()[:8]
