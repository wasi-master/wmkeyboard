"""importlib.resources: data files inside packages.

A plugin's packages are not folders on disk here, so there are no
resource files to open: files() and the readers raise
FileNotFoundError.
"""


def _missing(package, name=""):
    raise FileNotFoundError(f"no resource files here: package {package!r}{' ' + repr(name) if name else ''} is not on disk")


def files(anchor=None):
    _missing(anchor)


def as_file(path):
    _missing(path)


def open_binary(package, resource):
    _missing(package, resource)


def open_text(package, resource, encoding="utf-8", errors="strict"):
    _missing(package, resource)


def read_binary(package, resource):
    _missing(package, resource)


def read_text(package, resource, encoding="utf-8", errors="strict"):
    _missing(package, resource)


def path(package, resource):
    _missing(package, resource)


def is_resource(package, name):
    return False


def contents(package):
    return iter(())
