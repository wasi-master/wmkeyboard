"""email.mime.application: an application/* MIME part, base64 by default."""

import base64

from email.mime.nonmultipart import MIMENonMultipart


def encode_base64(msg):
    msg._payload = base64.encodebytes(bytes(msg._payload)).decode("ascii")
    msg["Content-Transfer-Encoding"] = "base64"


class MIMEApplication(MIMENonMultipart):
    def __init__(self, _data, _subtype="octet-stream", _encoder=encode_base64, *, policy=None, **_params):
        MIMENonMultipart.__init__(self, "application", _subtype, policy=policy, **_params)
        self.set_payload(_data)
        _encoder(self)
