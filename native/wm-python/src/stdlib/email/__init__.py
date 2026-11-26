"""email: e-mail messages and their headers.

email.utils (dates, addresses, quoting), email.header (encoded words),
email.message (Message and EmailMessage), email.parser and email.errors
are here. MIME building beyond plain text parts is not.
"""


def message_from_string(s, *args, **kws):
    from email.parser import Parser

    return Parser(*args, **kws).parsestr(s)


def message_from_bytes(s, *args, **kws):
    from email.parser import BytesParser

    return BytesParser(*args, **kws).parsebytes(s)


def message_from_file(fp, *args, **kws):
    from email.parser import Parser

    return Parser(*args, **kws).parse(fp)


def message_from_binary_file(fp, *args, **kws):
    from email.parser import BytesParser

    return BytesParser(*args, **kws).parse(fp)
