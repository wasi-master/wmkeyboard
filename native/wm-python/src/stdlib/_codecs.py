"""_codecs: what CPython keeps of codecs in C. The same functions as
codecs has, so a pickle that names `_codecs.encode` loads."""

from codecs import (
    decode,
    encode,
    escape_decode,
    escape_encode,
    lookup,
    lookup_error,
    register,
    register_error,
    unicode_escape_decode,
    unicode_escape_encode,
)
