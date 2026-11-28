"""zipimport: importing modules from zip archives on sys.path. Modules here
come from the plugin and the interpreter, never from an archive on disk,
so a zipimporter can't be made for any path."""

__all__ = ["ZipImportError", "zipimporter"]


class ZipImportError(ImportError):
    """Raised when a path isn't a zip archive modules can be imported from."""


class zipimporter:
    """A finder and loader for the modules in one zip archive. No archive
    can be read here, so making one raises ZipImportError, as for a path
    that is not a zip file."""

    def __init__(self, path):
        if not isinstance(path, str):
            raise TypeError(f"expected str, bytes or os.PathLike object, not {type(path).__name__}")
        if not path:
            raise ZipImportError("archive path is empty", path=path)
        raise ZipImportError(f"not a Zip file: {path!r}", path=path)

    def find_spec(self, fullname, target=None):
        return None

    def invalidate_caches(self):
        pass
