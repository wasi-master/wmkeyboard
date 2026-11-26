"""email.errors: the exceptions and defects the email package reports."""


class MessageError(Exception):
    pass


class MessageParseError(MessageError):
    pass


class HeaderParseError(MessageParseError):
    pass


class BoundaryError(MessageParseError):
    pass


class MultipartConversionError(MessageError, TypeError):
    pass


class CharsetError(MessageError):
    pass


class HeaderWriteError(MessageError):
    pass


class MessageDefect(ValueError):
    def __init__(self, line=None):
        if line is not None:
            super().__init__(line)
        self.line = line


class NoBoundaryInMultipartDefect(MessageDefect):
    pass


class StartBoundaryNotFoundDefect(MessageDefect):
    pass


class CloseBoundaryNotFoundDefect(MessageDefect):
    pass


class FirstHeaderLineIsContinuationDefect(MessageDefect):
    pass


class MisplacedEnvelopeHeaderDefect(MessageDefect):
    pass


class MissingHeaderBodySeparatorDefect(MessageDefect):
    pass


MalformedHeaderDefect = MissingHeaderBodySeparatorDefect


class MultipartInvariantViolationDefect(MessageDefect):
    pass


class InvalidMultipartContentTransferEncodingDefect(MessageDefect):
    pass


class UndecodableBytesDefect(MessageDefect):
    pass


class InvalidBase64PaddingDefect(MessageDefect):
    pass


class InvalidBase64CharactersDefect(MessageDefect):
    pass


class InvalidBase64LengthDefect(MessageDefect):
    pass


class HeaderDefect(MessageDefect):
    pass


class InvalidHeaderDefect(HeaderDefect):
    pass


class HeaderMissingRequiredValue(HeaderDefect):
    pass


class NonPrintableDefect(HeaderDefect):
    def __init__(self, non_printables):
        super().__init__(non_printables)
        self.non_printables = non_printables

    def __str__(self):
        return f"the following ASCII non-printables found in header: {self.non_printables}"


class ObsoleteHeaderDefect(HeaderDefect):
    pass


class NonASCIILocalPartDefect(HeaderDefect):
    pass


class InvalidDateDefect(HeaderDefect):
    pass
