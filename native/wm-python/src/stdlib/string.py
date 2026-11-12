"""string: character classes, capwords and Template."""

import re

ascii_lowercase = "abcdefghijklmnopqrstuvwxyz"
ascii_uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
ascii_letters = ascii_lowercase + ascii_uppercase
digits = "0123456789"
hexdigits = "0123456789abcdefABCDEF"
octdigits = "01234567"
punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
whitespace = " \t\n\r" + chr(11) + chr(12)
printable = digits + ascii_letters + punctuation + whitespace


def capwords(s, sep=None):
    if sep is None:
        return " ".join(w.capitalize() for w in s.split())
    return sep.join(w.capitalize() for w in s.split(sep))


class Template:
    def __init__(self, template):
        self.template = template

    def _sub(self, mapping, safe):
        def repl(m):
            name = m.group(1) or m.group(2)
            if m.group(0) == "$$":
                return "$"
            if name in mapping:
                return str(mapping[name])
            if safe:
                return m.group(0)
            raise KeyError(name)

        return re.sub(r"\$\$|\$([_a-zA-Z][_a-zA-Z0-9]*)|\$\{([_a-zA-Z][_a-zA-Z0-9]*)\}", repl, self.template)

    def substitute(self, mapping=None, **kwargs):
        values = dict(mapping or {})
        values.update(kwargs)
        return self._sub(values, False)

    def safe_substitute(self, mapping=None, **kwargs):
        values = dict(mapping or {})
        values.update(kwargs)
        return self._sub(values, True)
