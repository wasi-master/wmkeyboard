"""email.mime.audio: an audio/* MIME part."""

from email.mime.application import encode_base64
from email.mime.nonmultipart import MIMENonMultipart


class MIMEAudio(MIMENonMultipart):
    def __init__(self, _audiodata, _subtype=None, _encoder=encode_base64, *, policy=None, **_params):
        if _subtype is None:
            data = bytes(_audiodata[:12])
            _subtype = "x-wav" if data[8:12] == b"WAVE" else "mpeg" if data[:3] == b"ID3" else "ogg" if data[:4] == b"OggS" else None
            if _subtype is None:
                raise TypeError("Could not find audio MIME subtype")
        MIMENonMultipart.__init__(self, "audio", _subtype, policy=policy, **_params)
        self.set_payload(_audiodata)
        _encoder(self)
