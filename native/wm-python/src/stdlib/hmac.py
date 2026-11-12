"""hmac: keyed hashes, for signing requests to an API."""

import hashlib


def _algorithm(digestmod):
    if isinstance(digestmod, str):
        return digestmod.lower()
    if callable(digestmod):
        return digestmod().name
    raise TypeError("digestmod must be a name like 'sha256' or hashlib.sha256")


class HMAC:
    def __init__(self, key, msg=None, digestmod="sha256"):
        if isinstance(key, str):
            raise TypeError("key must be bytes")
        self._name = _algorithm(digestmod)
        block = hashlib.new(self._name).block_size
        if len(key) > block:
            key = hashlib.new(self._name, key).digest()
        key = key + bytes(block - len(key))
        self._outer_key = bytes([b ^ 0x5C for b in key])
        self._inner = hashlib.new(self._name, bytes([b ^ 0x36 for b in key]))
        self.name = "hmac-" + self._name
        self.digest_size = self._inner.digest_size
        if msg is not None:
            self.update(msg)

    def update(self, msg):
        self._inner.update(msg)

    def digest(self):
        return hashlib.new(self._name, self._outer_key + self._inner.digest()).digest()

    def hexdigest(self):
        return self.digest().hex()


def new(key, msg=None, digestmod="sha256"):
    return HMAC(key, msg, digestmod)


def digest(key, msg, digest):
    return HMAC(key, msg, digest).digest()


def compare_digest(a, b):
    if len(a) != len(b):
        return False
    result = 0
    for x, y in zip(a, b):
        result = result | ((ord(x) if isinstance(x, str) else x) ^ (ord(y) if isinstance(y, str) else y))
    return result == 0
