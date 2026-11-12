"""html: escape and unescape."""

import re

_NAMED = {
    "amp": "&", "lt": "<", "gt": ">", "quot": '"', "apos": "'", "nbsp": " ",
    "copy": "©", "reg": "®", "trade": "™", "hellip": "…", "mdash": "—",
    "ndash": "–", "lsquo": "‘", "rsquo": "’", "ldquo": "“", "rdquo": "”",
    "euro": "€", "pound": "£", "yen": "¥", "cent": "¢", "deg": "°",
    "times": "×", "divide": "÷", "middot": "·", "bull": "•",
}


def escape(s, quote=True):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    if quote:
        s = s.replace('"', "&quot;").replace("'", "&#x27;")
    return s


def unescape(s):
    def repl(m):
        body = m.group(1)
        if body.startswith("#x") or body.startswith("#X"):
            try:
                return chr(int(body[2:], 16))
            except ValueError:
                return m.group(0)
        if body.startswith("#"):
            try:
                return chr(int(body[1:]))
            except ValueError:
                return m.group(0)
        return _NAMED.get(body, m.group(0))

    return re.sub(r"&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);", repl, s)
