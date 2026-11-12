"""base64: encode bytes as text and back."""

import _native


def _bytes(data, name):
    if isinstance(data, str):
        return bytes(data, "utf-8")
    if isinstance(data, bytes):
        return data
    raise TypeError(f"{name}() argument must be bytes or str, not {type(data)}")


def b64encode(data):
    if not isinstance(data, bytes):
        raise TypeError("a bytes-like object is required, not '" + str(type(data)) + "'")
    return _native.b64encode(data, False)


def b64decode(data):
    return _native.b64decode(_bytes(data, "b64decode"), False)


def standard_b64encode(data):
    return b64encode(data)


def standard_b64decode(data):
    return b64decode(data)


def urlsafe_b64encode(data):
    if not isinstance(data, bytes):
        raise TypeError("a bytes-like object is required, not '" + str(type(data)) + "'")
    return _native.b64encode(data, True)


def urlsafe_b64decode(data):
    return _native.b64decode(_bytes(data, "urlsafe_b64decode"), True)


def b16encode(data):
    return bytes(data.hex().upper(), "utf-8")


def b16decode(data):
    return bytes.fromhex(_bytes(data, "b16decode").decode("utf-8"))
