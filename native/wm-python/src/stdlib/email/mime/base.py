"""email.mime.base: the base of the MIME message classes."""

from email import message


class MIMEBase(message.Message):
    def __init__(self, _maintype, _subtype, *, policy=None, **_params):
        from email.policy import compat32

        message.Message.__init__(self, policy=compat32 if policy is None else policy)
        ctype = f"{_maintype}/{_subtype}"
        self.add_header("Content-Type", ctype, **_params)
        self["MIME-Version"] = "1.0"
