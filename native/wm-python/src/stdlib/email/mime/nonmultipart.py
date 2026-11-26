"""email.mime.nonmultipart: a MIME message that is not multipart."""

from email import errors
from email.mime.base import MIMEBase


class MIMENonMultipart(MIMEBase):
    def attach(self, payload):
        raise errors.MultipartConversionError("Cannot attach additional subparts to non-multipart/*")
