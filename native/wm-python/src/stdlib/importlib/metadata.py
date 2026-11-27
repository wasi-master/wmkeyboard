"""importlib.metadata: installed distributions' metadata.

Plugins carry their packages inside them, without .dist-info folders to
read, so no distribution is found: version() and the rest raise
PackageNotFoundError, which code that asks usually expects and handles.
"""

__all__ = ["Distribution", "PackageNotFoundError", "distribution", "distributions", "entry_points", "files", "metadata", "packages_distributions", "requires", "version"]


class PackageNotFoundError(ModuleNotFoundError):
    def __str__(self):
        return f"No package metadata was found for {self.name}"

    @property
    def name(self):
        return self.args[0] if self.args else ""


class EntryPoint:
    def __init__(self, name, value, group):
        self.name = name
        self.value = value
        self.group = group

    def load(self):
        import importlib

        module, _, attr = self.value.partition(":")
        obj = importlib.import_module(module.strip())
        for part in attr.strip().split(".") if attr else []:
            obj = getattr(obj, part)
        return obj

    @property
    def module(self):
        return self.value.partition(":")[0].strip()

    @property
    def attr(self):
        return self.value.partition(":")[2].strip()

    def __repr__(self):
        return f"EntryPoint(name={self.name!r}, value={self.value!r}, group={self.group!r})"


class EntryPoints:
    def __init__(self, eps=()):
        self._eps = list(eps)

    def __iter__(self):
        return iter(self._eps)

    def __len__(self):
        return len(self._eps)

    def __getitem__(self, name):
        for ep in self._eps:
            if ep.name == name:
                return ep
        raise KeyError(name)

    def select(self, **params):
        return EntryPoints([ep for ep in self._eps if all(getattr(ep, k) == v for k, v in params.items())])

    @property
    def names(self):
        return {ep.name for ep in self._eps}

    @property
    def groups(self):
        return {ep.group for ep in self._eps}


class DistributionFinder:
    """Finds distributions: a plugin's vendored packages carry no metadata, so none."""

    class Context:
        name = None

        def __init__(self, **kwargs):
            for k, v in kwargs.items():
                setattr(self, k, v)

        @property
        def path(self):
            return []

    def find_distributions(self, context=None):
        return iter(())


class MetadataPathFinder(DistributionFinder):
    @classmethod
    def find_distributions(cls, context=None):
        return iter(())

    @classmethod
    def invalidate_caches(cls):
        pass


class Distribution:
    @classmethod
    def from_name(cls, name):
        raise PackageNotFoundError(name)

    @classmethod
    def discover(cls, **kwargs):
        return iter(())


def distribution(distribution_name):
    return Distribution.from_name(distribution_name)


def distributions(**kwargs):
    return Distribution.discover(**kwargs)


def metadata(distribution_name):
    raise PackageNotFoundError(distribution_name)


def version(distribution_name):
    raise PackageNotFoundError(distribution_name)


def entry_points(**params):
    return EntryPoints()


def files(distribution_name):
    raise PackageNotFoundError(distribution_name)


def requires(distribution_name):
    raise PackageNotFoundError(distribution_name)


def packages_distributions():
    return {}
