"""uuid: random (version 4) identifiers. They come from the interpreter's
random generator, so they are unique enough for keys and ids but are not a
secret."""

import random


class UUID:
    def __init__(self, hex=None, bytes=None):
        if hex is not None:
            h = hex.replace("-", "").replace("{", "").replace("}", "").lower()
            if h.startswith("urn:uuid:"):
                h = h[9:]
            if len(h) != 32:
                raise ValueError("badly formed hexadecimal UUID string")
            self.hex = h
        elif bytes is not None:
            self.hex = bytes.hex()
        else:
            raise TypeError("one of hex or bytes must be given")

    @property
    def version(self):
        return int(self.hex[12], 16)

    def __str__(self):
        h = self.hex
        return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"

    def __repr__(self):
        return f"UUID('{self}')"

    def __eq__(self, other):
        return isinstance(other, UUID) and other.hex == self.hex

    def __hash__(self):
        return hash(self.hex)


def uuid4():
    parts = [random.randint(0, 255) for _ in range(16)]
    parts[6] = (parts[6] & 0x0F) | 0x40
    parts[8] = (parts[8] & 0x3F) | 0x80
    return UUID(hex="".join(f"{b:02x}" for b in parts))
