"""The ascii codec, as a module of the encodings package: what
`import encodings.ascii` finds. The codec itself is codecs.lookup('ascii')."""

import codecs

_info = codecs.lookup("ascii")


class Codec(codecs.Codec):
    def encode(self, input, errors="strict"):
        return _info.encode(input, errors)

    def decode(self, input, errors="strict"):
        return _info.decode(input, errors)


class IncrementalEncoder(codecs.IncrementalEncoder):
    def encode(self, input, final=False):
        return _info.encode(input, self.errors)[0]


class IncrementalDecoder(codecs.IncrementalDecoder):
    def decode(self, input, final=False):
        return _info.decode(input, self.errors)[0]


class StreamWriter(Codec, codecs.StreamWriter):
    pass


class StreamReader(Codec, codecs.StreamReader):
    pass


encode = _info.encode
decode = _info.decode


def getregentry():
    return _info
