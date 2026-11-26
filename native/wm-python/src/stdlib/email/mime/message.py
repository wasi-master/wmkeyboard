"""email.mime.message: a message/rfc822 MIME part holding another message."""

from email.mime.nonmultipart import MIMENonMultipart


class MIMEMessage(MIMENonMultipart):
    def __init__(self, _msg, _subtype="rfc822", *, policy=None):
        MIMENonMultipart.__init__(self, "message", _subtype, policy=policy)
        self._payload = [_msg]
        self.set_default_type("message/rfc822")
