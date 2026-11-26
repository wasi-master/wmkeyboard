"""email.utils: RFC 2822 dates, addresses and quoting."""

import datetime
import os
import random
import re
import time
import urllib.parse

__all__ = [
    "collapse_rfc2231_value", "decode_params", "decode_rfc2231", "encode_rfc2231", "formataddr", "formatdate",
    "format_datetime", "getaddresses", "make_msgid", "mktime_tz", "parseaddr", "parsedate", "parsedate_tz",
    "parsedate_to_datetime", "quote", "unquote", "localtime",
]

COMMASPACE = ", "
EMPTYSTRING = ""
UEMPTYSTRING = ""
CRLF = "\r\n"
TICK = "'"

specialsre = re.compile(r'[][\\()<>@,:;".]')
escapesre = re.compile(r'[\\"]')

_DAYNAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
_MONTHNAMES = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
_MONTH_TITLES = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
_TIMEZONES = {
    "UT": 0, "UTC": 0, "GMT": 0, "Z": 0,
    "AST": -400, "ADT": -300, "EST": -500, "EDT": -400, "CST": -600, "CDT": -500,
    "MST": -700, "MDT": -600, "PST": -800, "PDT": -700,
}


def quote(str):
    return str.replace("\\", "\\\\").replace('"', '\\"')


def unquote(str):
    if len(str) > 1:
        if str.startswith('"') and str.endswith('"'):
            return str[1:-1].replace("\\\\", "\\").replace('\\"', '"')
        if str.startswith("<") and str.endswith(">"):
            return str[1:-1]
    return str


def _format_timetuple_and_zone(timetuple, zone):
    return "%s, %02d %s %04d %02d:%02d:%02d %s" % (
        _DAYNAMES[timetuple[6]], timetuple[2], _MONTH_TITLES[timetuple[1] - 1], timetuple[0], timetuple[3], timetuple[4], timetuple[5], zone,
    )


def formatdate(timeval=None, localtime=False, usegmt=False):
    if timeval is None:
        timeval = time.time()
    dt = datetime.datetime.fromtimestamp(timeval, datetime.timezone.utc)
    if localtime:
        dt = dt.astimezone()
        usegmt = False
    elif not usegmt:
        dt = dt.replace(tzinfo=None)
    return format_datetime(dt, usegmt)


def format_datetime(dt, usegmt=False):
    now = dt.timetuple()
    if usegmt:
        if dt.tzinfo is None or dt.tzinfo != datetime.timezone.utc:
            raise ValueError("usegmt option requires a UTC datetime")
        zone = "GMT"
    elif dt.tzinfo is None:
        zone = "-0000"
    else:
        zone = dt.strftime("%z")
    return _format_timetuple_and_zone(now, zone)


def make_msgid(idstring=None, domain=None):
    timeval = int(time.time() * 100)
    pid = os.getpid()
    randint = random.randrange(1 << 62)
    idstring = "" if idstring is None else "." + idstring
    if domain is None:
        domain = "localhost"
    return f"<{timeval}.{pid}.{randint}{idstring}@{domain}>"


def _parsedate_tz(data):
    if not data:
        return None
    data = data.split()
    if not data:
        return None
    if data[0].endswith(",") or data[0].lower() in [d.lower() for d in _DAYNAMES]:
        del data[0]
    else:
        i = data[0].rfind(",")
        if i >= 0:
            data[0] = data[0][i + 1 :]
    if len(data) == 3:
        stuff = data[0].split("-")
        if len(stuff) == 3:
            data = stuff + data[1:]
    if len(data) == 4:
        s = data[3]
        i = s.find("+")
        if i == -1:
            i = s.find("-")
        if i > 0:
            data[3:] = [s[:i], s[i:]]
        else:
            data.append("")
    if len(data) < 5:
        return None
    data = data[:5]
    dd, mm, yy, tm, tz = data
    if not (dd and mm and yy):
        return None
    mm = mm.lower()
    if mm not in _MONTHNAMES:
        dd, mm = mm, dd.lower()
        if mm not in _MONTHNAMES:
            return None
    mm = _MONTHNAMES.index(mm) + 1
    if mm > 12:
        mm -= 12
    if dd[-1] == ",":
        dd = dd[:-1]
    i = yy.find(":")
    if i > 0:
        yy, tm = tm, yy
    if yy[-1] == ",":
        yy = yy[:-1]
        if not yy:
            return None
    if not yy[0].isdigit():
        yy, tz = tz, yy
    if tm[-1:] == ",":
        tm = tm[:-1]
    tm = tm.split(":")
    if len(tm) == 2:
        thh, tmm = tm
        tss = "0"
    elif len(tm) == 3:
        thh, tmm, tss = tm
    elif len(tm) == 1 and "." in tm[0]:
        tm = tm[0].split(".")
        if len(tm) == 2:
            thh, tmm = tm
            tss = 0
        elif len(tm) == 3:
            thh, tmm, tss = tm
        else:
            return None
    else:
        return None
    try:
        yy = int(yy)
        dd = int(dd)
        thh = int(thh)
        tmm = int(tmm)
        tss = int(tss)
    except ValueError:
        return None
    if yy < 100:
        yy += 1900 if yy > 68 else 2000
    tzoffset = None
    tz = tz.upper()
    if tz in _TIMEZONES:
        tzoffset = _TIMEZONES[tz]
    else:
        try:
            tzoffset = int(tz)
        except ValueError:
            pass
        if tzoffset == 0 and tz.startswith("-"):
            tzoffset = None
    if tzoffset:
        if tzoffset < 0:
            tzsign = -1
            tzoffset = -tzoffset
        else:
            tzsign = 1
        tzoffset = tzsign * ((tzoffset // 100) * 3600 + (tzoffset % 100) * 60)
    return [yy, mm, dd, thh, tmm, tss, 0, 1, -1, tzoffset]


def parsedate_tz(data):
    t = _parsedate_tz(data)
    if isinstance(t, list):
        t = tuple(t)
    return t


def parsedate(data):
    t = parsedate_tz(data)
    if isinstance(t, tuple):
        return t[:9]
    return t


def mktime_tz(data):
    if data[9] is None:
        return time.mktime(data[:8] + (-1,))
    import calendar

    return calendar.timegm(data) - data[9]


def parsedate_to_datetime(data):
    parsed = _parsedate_tz(data)
    if parsed is None:
        raise ValueError(f'Invalid date value or format "{data}"')
    *dtuple, tz = parsed
    if tz is None:
        return datetime.datetime(*dtuple[:6])
    return datetime.datetime(*dtuple[:6], tzinfo=datetime.timezone(datetime.timedelta(seconds=tz)))


def localtime(dt=None, isdst=-1):
    if dt is None:
        dt = datetime.datetime.now(datetime.timezone.utc)
    return dt.astimezone()


# MARK: - Addresses


def _split_addresses(text):
    """Splits on commas outside quotes, comments and angle brackets."""
    out = []
    current = []
    depth_paren = 0
    in_quote = False
    in_angle = False
    i = 0
    while i < len(text):
        c = text[i]
        if in_quote:
            current.append(c)
            if c == "\\" and i + 1 < len(text):
                current.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_quote = False
        elif depth_paren:
            current.append(c)
            if c == "\\" and i + 1 < len(text):
                current.append(text[i + 1])
                i += 2
                continue
            if c == "(":
                depth_paren += 1
            elif c == ")":
                depth_paren -= 1
        elif c == '"':
            in_quote = True
            current.append(c)
        elif c == "(":
            depth_paren = 1
            current.append(c)
        elif c == "<":
            in_angle = True
            current.append(c)
        elif c == ">":
            in_angle = False
            current.append(c)
        elif c == "," and not in_angle:
            out.append("".join(current))
            current = []
        else:
            current.append(c)
        i += 1
    out.append("".join(current))
    return out


def _strip_comments(text):
    comments = []
    out = []
    depth = 0
    buf = []
    in_quote = False
    i = 0
    while i < len(text):
        c = text[i]
        if depth:
            if c == "\\" and i + 1 < len(text):
                buf.append(text[i + 1])
                i += 2
                continue
            if c == "(":
                depth += 1
                buf.append(c)
            elif c == ")":
                depth -= 1
                if depth == 0:
                    comments.append("".join(buf))
                    buf = []
                else:
                    buf.append(c)
            else:
                buf.append(c)
        elif in_quote:
            out.append(c)
            if c == "\\" and i + 1 < len(text):
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_quote = False
        elif c == '"':
            in_quote = True
            out.append(c)
        elif c == "(":
            depth = 1
        else:
            out.append(c)
        i += 1
    return "".join(out), comments


def _unquote_phrase(text):
    out = []
    i = 0
    in_quote = False
    while i < len(text):
        c = text[i]
        if c == '"':
            in_quote = not in_quote
        elif c == "\\" and in_quote and i + 1 < len(text):
            out.append(text[i + 1])
            i += 1
        else:
            out.append(c)
        i += 1
    return " ".join("".join(out).split())


def _addr_spec(text):
    text = text.strip()
    if "@" not in text:
        return "".join(text.split())
    local, _, domain = text.rpartition("@")
    local = local.strip()
    if not (local.startswith('"') and local.endswith('"')):
        local = "".join(local.split())
    return local + "@" + "".join(domain.split())


def _parse_one(text):
    body, comments = _strip_comments(text)
    body = body.strip()
    if not body and not comments:
        return []
    colon = -1
    if "<" not in body:
        in_quote = False
        for i, c in enumerate(body):
            if c == '"':
                in_quote = not in_quote
            elif c == ":" and not in_quote:
                colon = i
                break
    if colon >= 0:
        # A group: "name: a@b, c@d;"
        members = body[colon + 1 :].rstrip().rstrip(";")
        out = []
        for part in _split_addresses(members):
            out.extend(_parse_one(part))
        return out
    lt = body.find("<")
    if lt >= 0:
        gt = body.find(">", lt)
        inner = body[lt + 1 : gt if gt >= 0 else len(body)]
        if ":" in inner and inner.startswith("@"):
            inner = inner.split(":", 1)[1]
        name = _unquote_phrase(body[:lt])
        if not name and comments:
            name = comments[-1].strip()
        return [(name, _addr_spec(inner))]
    name = comments[-1].strip() if comments else ""
    return [(name, _addr_spec(body))]


def parseaddr(addr, *, strict=True):
    if not isinstance(addr, str):
        addr = str(addr)
    addrs = []
    for part in _split_addresses(addr):
        addrs.extend(_parse_one(part))
    if not addrs:
        return ("", "")
    return addrs[0]


def getaddresses(fieldvalues, *, strict=True):
    text = COMMASPACE.join(str(v) for v in fieldvalues)
    out = []
    for part in _split_addresses(text):
        out.extend(_parse_one(part))
    return out


def formataddr(pair, charset="utf-8"):
    name, address = pair
    address.encode("ascii")
    if name:
        try:
            name.encode("ascii")
        except UnicodeEncodeError:
            from email.charset import Charset

            if isinstance(charset, str):
                charset = Charset(charset)
            return f"{charset.header_encode(name)} <{address}>"
        quotes = ""
        if specialsre.search(name):
            quotes = '"'
        name = escapesre.sub(r"\\\g<0>", name)
        return f"{quotes}{name}{quotes} <{address}>"
    return address


# MARK: - RFC 2231


def decode_rfc2231(s):
    parts = s.split(TICK, 2)
    if len(parts) <= 2:
        return None, None, s
    return tuple(parts)


def encode_rfc2231(s, charset=None, language=None):
    s = urllib.parse.quote(s, safe="")
    if charset is None and language is None:
        return s
    if language is None:
        language = ""
    return f"{charset}'{language}'{s}"


def collapse_rfc2231_value(value, errors="replace", fallback_charset="us-ascii"):
    if not isinstance(value, tuple) or len(value) != 3:
        return unquote(value)
    charset, language, text = value
    if charset is None:
        charset = fallback_charset
    rawbytes = bytes(text, "raw-unicode-escape")
    try:
        return str(rawbytes, charset, errors)
    except LookupError:
        return unquote(text)


def decode_params(params):
    new_params = [params[0]]
    for name, value in params[1:]:
        if name.endswith("*"):
            name = name[:-1]
            charset, language, raw = decode_rfc2231(unquote(value))
            value = (charset, language, urllib.parse.unquote(raw)) if charset is not None else unquote(value)
            new_params.append((name, value))
        else:
            new_params.append((name, '"%s"' % quote(unquote(value))))
    return new_params
