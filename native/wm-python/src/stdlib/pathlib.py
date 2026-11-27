"""pathlib: paths as objects.

The pure paths work as CPython's do on Linux: joining with /, the name,
suffixes, parents, relative_to, matching. Plugins have no file system, so a
concrete Path answers its questions about the disk as for a path that is not
there (exists() is False, iterdir() and glob() find nothing), and reading,
writing or creating raises PermissionError.

PureWindowsPath and WindowsPath read and print paths the POSIX way.
"""

import fnmatch
import os

__all__ = ["PurePath", "PurePosixPath", "PureWindowsPath", "Path", "PosixPath", "WindowsPath", "UnsupportedOperation"]

_NO_FILES = "plugins have no file system"


class UnsupportedOperation(NotImplementedError):
    pass


def _split(text):
    root = "/" if text.startswith("/") else ""
    parts = [p for p in text.split("/") if p and p != "."]
    return root, parts


class PurePath:
    def __new__(cls, *args, **kwargs):
        if cls is PurePath:
            cls = PurePosixPath
        return object.__new__(cls)

    def __init__(self, *args):
        root = ""
        parts = []
        for a in args:
            if isinstance(a, PurePath):
                text = a._text()
            else:
                text = os.fspath(a)
                if not isinstance(text, str):
                    raise TypeError(f"argument should be a str or an os.PathLike object, not {type(a).__name__!r}")
            r, p = _split(text)
            if r:
                root = r
                parts = p
            else:
                parts = parts + p
        self._root = root
        self._parts = parts

    @classmethod
    def _make(cls, root, parts):
        p = object.__new__(cls)
        p._root = root
        p._parts = list(parts)
        return p

    def _text(self):
        body = "/".join(self._parts)
        if self._root:
            return self._root + body
        return body or "."

    def __str__(self):
        return self._text()

    def __fspath__(self):
        return self._text()

    def as_posix(self):
        return self._text()

    def __bytes__(self):
        return self._text().encode("utf-8")

    def as_uri(self):
        if not self._root:
            raise ValueError("relative path can't be expressed as a file URI")
        from urllib.parse import quote

        return "file://" + quote(self._text())

    def __repr__(self):
        return f"{type(self).__name__}({self.as_posix()!r})"

    def __hash__(self):
        return hash(self._text())

    def __eq__(self, other):
        if not isinstance(other, PurePath):
            return NotImplemented
        return self._text() == other._text()

    def __lt__(self, other):
        if not isinstance(other, PurePath):
            return NotImplemented
        return self._key() < other._key()

    def __le__(self, other):
        if not isinstance(other, PurePath):
            return NotImplemented
        return self._key() <= other._key()

    def __gt__(self, other):
        if not isinstance(other, PurePath):
            return NotImplemented
        return self._key() > other._key()

    def __ge__(self, other):
        if not isinstance(other, PurePath):
            return NotImplemented
        return self._key() >= other._key()

    def _key(self):
        return (self._root, self._parts)

    def __truediv__(self, key):
        try:
            return self.joinpath(key)
        except TypeError:
            return NotImplemented

    def __rtruediv__(self, key):
        try:
            return type(self)(key, self)
        except TypeError:
            return NotImplemented

    def joinpath(self, *pathsegments):
        return type(self)(self, *pathsegments)

    def with_segments(self, *pathsegments):
        return type(self)(*pathsegments)

    @property
    def parts(self):
        return tuple(([self._root] if self._root else []) + self._parts)

    @property
    def drive(self):
        return ""

    @property
    def root(self):
        return self._root

    @property
    def anchor(self):
        return self._root

    @property
    def name(self):
        return self._parts[-1] if self._parts else ""

    @property
    def suffix(self):
        name = self.name
        i = name.rfind(".")
        if 0 < i < len(name) - 1:
            return name[i:]
        return ""

    @property
    def suffixes(self):
        name = self.name
        if name.endswith("."):
            return []
        name = name.lstrip(".")
        return ["." + s for s in name.split(".")[1:]]

    @property
    def stem(self):
        name = self.name
        i = name.rfind(".")
        if 0 < i < len(name) - 1:
            return name[:i]
        return name

    def with_name(self, name):
        if not self.name:
            raise ValueError(f"{self!r} has an empty name")
        if not name or "/" in name or name == ".":
            raise ValueError(f"Invalid name {name!r}")
        return self._make(self._root, self._parts[:-1] + [name])

    def with_stem(self, stem):
        return self.with_name(stem + self.suffix)

    def with_suffix(self, suffix):
        if suffix and (not suffix.startswith(".") or suffix == "." or "/" in suffix):
            raise ValueError(f"Invalid suffix {suffix!r}")
        if not self.name:
            raise ValueError(f"{self!r} has an empty name")
        return self.with_name(self.stem + suffix)

    @property
    def parent(self):
        if not self._parts:
            return self
        return self._make(self._root, self._parts[:-1])

    @property
    def parents(self):
        out = []
        p = self
        while p._parts:
            p = p.parent
            out.append(p)
        return tuple(out)

    def is_absolute(self):
        return bool(self._root)

    def is_reserved(self):
        return False

    def is_relative_to(self, other, *_deprecated):
        other = type(self)(other)
        return self._root == other._root and self._parts[: len(other._parts)] == other._parts

    def relative_to(self, other, *_deprecated, walk_up=False):
        other = type(self)(other)
        if self._root != other._root:
            raise ValueError(f"{str(self)!r} and {str(other)!r} have different anchors")
        n = len(other._parts)
        if self._parts[:n] == other._parts:
            return self._make("", self._parts[n:])
        if not walk_up:
            raise ValueError(f"{str(self)!r} is not in the subpath of {str(other)!r}")
        common = 0
        while common < min(n, len(self._parts)) and self._parts[common] == other._parts[common]:
            common += 1
        return self._make("", [".."] * (n - common) + self._parts[common:])

    def match(self, path_pattern, *, case_sensitive=None):
        pat = type(self)(path_pattern)
        if not pat._parts:
            raise ValueError("empty pattern")
        if pat._root:
            if not self._root or len(pat._parts) != len(self._parts):
                return False
        elif len(pat._parts) > len(self._parts):
            return False
        for part, p in zip(reversed(self._parts), reversed(pat._parts)):
            if not fnmatch.fnmatchcase(part, p):
                return False
        return True

    def full_match(self, pattern, *, case_sensitive=None):
        return fnmatch.fnmatchcase(self._text(), str(pattern))


class PurePosixPath(PurePath):
    def __new__(cls, *args, **kwargs):
        return object.__new__(cls)


class PureWindowsPath(PurePath):
    def __new__(cls, *args, **kwargs):
        return object.__new__(cls)


class Path(PurePath):
    def __new__(cls, *args, **kwargs):
        if cls is Path:
            cls = PosixPath
        return object.__new__(cls)

    @classmethod
    def cwd(cls):
        return cls("/")

    @classmethod
    def home(cls):
        return cls("/")

    def absolute(self):
        return self if self._root else type(self)("/", self)

    def resolve(self, strict=False):
        if strict:
            raise FileNotFoundError(2, "No such file or directory", str(self))
        return type(self)(os.path.normpath(str(self.absolute())))

    def expanduser(self):
        return self

    def readlink(self):
        raise OSError(_NO_FILES)

    def exists(self, *, follow_symlinks=True):
        return False

    def is_file(self, *, follow_symlinks=True):
        return False

    def is_dir(self, *, follow_symlinks=True):
        return False

    def is_symlink(self):
        return False

    def is_mount(self):
        return False

    def is_socket(self):
        return False

    def is_fifo(self):
        return False

    def is_block_device(self):
        return False

    def is_char_device(self):
        return False

    def is_junction(self):
        return False

    def samefile(self, other_path):
        raise FileNotFoundError(2, "No such file or directory", str(self))

    def iterdir(self):
        return iter(())

    def glob(self, pattern, *, case_sensitive=None, recurse_symlinks=False):
        return iter(())

    def rglob(self, pattern, *, case_sensitive=None, recurse_symlinks=False):
        return iter(())

    def walk(self, top_down=True, on_error=None, follow_symlinks=False):
        return iter(())

    def stat(self, *, follow_symlinks=True):
        raise FileNotFoundError(2, "No such file or directory", str(self))

    def lstat(self):
        return self.stat()

    def owner(self, *, follow_symlinks=True):
        raise PermissionError(_NO_FILES)

    def group(self, *, follow_symlinks=True):
        raise PermissionError(_NO_FILES)

    def open(self, mode="r", buffering=-1, encoding=None, errors=None, newline=None):
        raise PermissionError(_NO_FILES)

    def read_text(self, encoding=None, errors=None, newline=None):
        raise PermissionError(_NO_FILES)

    def read_bytes(self):
        raise PermissionError(_NO_FILES)

    def write_text(self, data, encoding=None, errors=None, newline=None):
        raise PermissionError(_NO_FILES)

    def write_bytes(self, data):
        raise PermissionError(_NO_FILES)

    def touch(self, mode=0o666, exist_ok=True):
        raise PermissionError(_NO_FILES)

    def mkdir(self, mode=0o777, parents=False, exist_ok=False):
        raise PermissionError(_NO_FILES)

    def chmod(self, mode, *, follow_symlinks=True):
        raise PermissionError(_NO_FILES)

    def lchmod(self, mode):
        raise PermissionError(_NO_FILES)

    def unlink(self, missing_ok=False):
        if missing_ok:
            return
        raise FileNotFoundError(2, "No such file or directory", str(self))

    def rmdir(self):
        raise FileNotFoundError(2, "No such file or directory", str(self))

    def rename(self, target):
        raise PermissionError(_NO_FILES)

    def replace(self, target):
        raise PermissionError(_NO_FILES)

    def symlink_to(self, target, target_is_directory=False):
        raise PermissionError(_NO_FILES)

    def hardlink_to(self, target):
        raise PermissionError(_NO_FILES)


class PosixPath(Path, PurePosixPath):
    def __new__(cls, *args, **kwargs):
        return object.__new__(cls)


class WindowsPath(Path, PureWindowsPath):
    def __new__(cls, *args, **kwargs):
        return object.__new__(cls)
