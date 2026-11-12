"""hashlib: sha256, sha1, md5, sha224, sha384, sha512."""

import _native

algorithms_available = {"md5", "sha1", "sha224", "sha256", "sha384", "sha512"}
algorithms_guaranteed = algorithms_available

_BLOCK = {"md5": 64, "sha1": 64, "sha224": 64, "sha256": 64, "sha384": 128, "sha512": 128}
_SIZE = {"md5": 16, "sha1": 20, "sha224": 28, "sha256": 32, "sha384": 48, "sha512": 64}


class _Hash:
    def __init__(self, name, data=b""):
        if name not in _BLOCK:
            raise ValueError(f"unsupported hash type {name}")
        self.name = name
        self.block_size = _BLOCK[name]
        self.digest_size = _SIZE[name]
        self._data = b""
        if data:
            self.update(data)

    def update(self, data):
        if isinstance(data, str):
            raise TypeError("Strings must be encoded before hashing")
        self._data = self._data + data

    def digest(self):
        return _native.digest(self.name, self._data)

    def hexdigest(self):
        return self.digest().hex()

    def copy(self):
        return _Hash(self.name, self._data)


def new(name, data=b""):
    return _Hash(name.lower(), data)


def md5(data=b""):
    return _Hash("md5", data)


def sha1(data=b""):
    return _Hash("sha1", data)


def sha224(data=b""):
    return _Hash("sha224", data)


def sha256(data=b""):
    return _Hash("sha256", data)


def sha384(data=b""):
    return _Hash("sha384", data)


def sha512(data=b""):
    return _Hash("sha512", data)
