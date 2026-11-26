"""email.mime.image: an image/* MIME part."""

from email.mime.application import encode_base64
from email.mime.nonmultipart import MIMENonMultipart


class MIMEImage(MIMENonMultipart):
    def __init__(self, _imagedata, _subtype=None, _encoder=encode_base64, *, policy=None, **_params):
        if _subtype is None:
            data = bytes(_imagedata[:16])
            _subtype = "png" if data.startswith(b"\x89PNG") else "jpeg" if data[:2] == b"\xff\xd8" else "gif" if data[:4] == b"GIF8" else "webp" if data[8:12] == b"WEBP" else None
            if _subtype is None:
                raise TypeError("Could not guess image MIME subtype")
        MIMENonMultipart.__init__(self, "image", _subtype, policy=policy, **_params)
        self.set_payload(_imagedata)
        _encoder(self)
