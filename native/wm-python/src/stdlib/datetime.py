"""datetime: dates, times of day, spans between them, and time zones.

A datetime with no tzinfo is naive and read as the phone's time zone:
`now()` reads the local clock, `timestamp()` and `fromtimestamp()` convert
through the local zone. One with a tzinfo (timezone.utc, or a timezone with
a fixed offset) is aware, and converts, compares and subtracts through UTC.
There is no zoneinfo: named zones with daylight saving are not carried.
`strftime` takes the C codes Python uses (%Y-%m-%d %H:%M %z).
"""

import math
import time as _time
import _native

MINYEAR = 1
MAXYEAR = 9999

_DAYS_BEFORE_MONTH = [0, 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334]
_DAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
_MONTH_NAMES = ["January", "February", "March", "April", "May", "June", "July", "August",
                "September", "October", "November", "December"]


def _is_leap(y):
    return y % 4 == 0 and (y % 100 != 0 or y % 400 == 0)


def _days_in_month(y, m):
    if m == 2:
        return 29 if _is_leap(y) else 28
    return [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]


def _days_before_year(y):
    y = y - 1
    return y * 365 + y // 4 - y // 100 + y // 400


def _ymd_to_ord(y, m, d):
    extra = 1 if m > 2 and _is_leap(y) else 0
    return _days_before_year(y) + _DAYS_BEFORE_MONTH[m] + extra + d


def _ord_to_ymd(n):
    n = n - 1
    n400, n = divmod(n, 146097)
    year = n400 * 400 + 1
    n100, n = divmod(n, 36524)
    n4, n = divmod(n, 1461)
    n1, n = divmod(n, 365)
    year = year + n100 * 100 + n4 * 4 + n1
    if n1 == 4 or n100 == 4:
        return year - 1, 12, 31
    leap = n1 == 3 and (n4 != 24 or n100 == 3)
    month = (n + 50) // 32
    preceding = _DAYS_BEFORE_MONTH[month] + (1 if month > 2 and leap else 0)
    if preceding > n:
        month = month - 1
        preceding = preceding - (_days_in_month(year, month))
    return year, month, n - preceding + 1


def _check_date(y, m, d):
    if not MINYEAR <= y <= MAXYEAR:
        raise ValueError(f"year {y} is out of range")
    if not 1 <= m <= 12:
        raise ValueError("month must be in 1..12")
    if not 1 <= d <= _days_in_month(y, m):
        raise ValueError("day is out of range for month")


def _check_time(h, mi, s, us):
    if not 0 <= h <= 23:
        raise ValueError("hour must be in 0..23")
    if not 0 <= mi <= 59:
        raise ValueError("minute must be in 0..59")
    if not 0 <= s <= 59:
        raise ValueError("second must be in 0..59")
    if not 0 <= us <= 999999:
        raise ValueError("microsecond must be in 0..999999")


class timedelta:
    """A span of time, kept as days, seconds (0..86399) and microseconds
    (0..999999), as Python keeps it."""

    def __init__(self, days=0, seconds=0, microseconds=0, milliseconds=0, minutes=0, hours=0, weeks=0):
        d = days + weeks * 7
        s = seconds + minutes * 60 + hours * 3600
        us = microseconds + milliseconds * 1000
        whole = math.floor(d)
        s = s + (d - whole) * 86400
        d = int(whole)
        whole = math.floor(s)
        us = us + (s - whole) * 1000000
        s = int(whole)
        us = int(round(us))
        carry, us = divmod(us, 1000000)
        s = s + carry
        carry, s = divmod(s, 86400)
        d = d + carry
        object.__setattr__(self, "days", d)
        object.__setattr__(self, "seconds", s)
        object.__setattr__(self, "microseconds", us)

    def _key(self):
        return (self.days, self.seconds, self.microseconds)

    def total_seconds(self):
        return self.days * 86400 + self.seconds + self.microseconds / 1000000

    def __add__(self, other):
        if isinstance(other, timedelta):
            return timedelta(self.days + other.days, self.seconds + other.seconds, self.microseconds + other.microseconds)
        return NotImplemented

    def __radd__(self, other):
        return self.__add__(other)

    def __sub__(self, other):
        if isinstance(other, timedelta):
            return timedelta(self.days - other.days, self.seconds - other.seconds, self.microseconds - other.microseconds)
        return NotImplemented

    def __neg__(self):
        return timedelta(-self.days, -self.seconds, -self.microseconds)

    def __pos__(self):
        return self

    def __abs__(self):
        return -self if self.days < 0 else self

    def __mul__(self, n):
        return timedelta(self.days * n, self.seconds * n, self.microseconds * n)

    def __rmul__(self, n):
        return self.__mul__(n)

    def __truediv__(self, other):
        if isinstance(other, timedelta):
            return self.total_seconds() / other.total_seconds()
        return timedelta(seconds=self.total_seconds() / other)

    def __floordiv__(self, other):
        if isinstance(other, timedelta):
            return int(self.total_seconds() // other.total_seconds())
        return timedelta(seconds=self.total_seconds() // other)

    def __eq__(self, other):
        return isinstance(other, timedelta) and self._key() == other._key()

    def __lt__(self, other):
        return self._key() < other._key()

    def __le__(self, other):
        return self._key() <= other._key()

    def __gt__(self, other):
        return self._key() > other._key()

    def __ge__(self, other):
        return self._key() >= other._key()

    def __hash__(self):
        return hash(self._key())

    def __bool__(self):
        return self._key() != (0, 0, 0)

    def __str__(self):
        mm, ss = divmod(self.seconds, 60)
        hh, mm = divmod(mm, 60)
        s = f"{hh}:{mm:02d}:{ss:02d}"
        if self.microseconds:
            s = s + f".{self.microseconds:06d}"
        if self.days:
            plural = "" if abs(self.days) == 1 else "s"
            s = f"{self.days} day{plural}, " + s
        return s

    def __repr__(self):
        parts = []
        if self.days:
            parts.append(f"days={self.days}")
        if self.seconds:
            parts.append(f"seconds={self.seconds}")
        if self.microseconds:
            parts.append(f"microseconds={self.microseconds}")
        return "datetime.timedelta(" + (", ".join(parts) or "0") + ")"

    def __setattr__(self, name, value):
        raise AttributeError("timedelta is immutable")


timedelta.min = timedelta(days=-999999999)
timedelta.max = timedelta(days=999999999, hours=23, minutes=59, seconds=59, microseconds=999999)
timedelta.resolution = timedelta(microseconds=1)


def _pad(n, width):
    return str(n).zfill(width)


_EPOCH_ORDINAL = 719163


def _format_offset(off, sep=":"):
    """`+05:30` for timedelta(hours=5, minutes=30); "" for None."""
    if off is None:
        return ""
    sign = "+"
    if off.days < 0:
        sign = "-"
        off = -off
    hh, rem = divmod(off.days * 86400 + off.seconds, 3600)
    mm, ss = divmod(rem, 60)
    out = f"{sign}{hh:02d}{sep}{mm:02d}"
    if ss or off.microseconds:
        out = out + f"{sep}{ss:02d}"
        if off.microseconds:
            out = out + f".{off.microseconds:06d}"
    return out


def _parse_offset(text):
    """The timezone for `Z`, `+05:30`, `-0800` or `+05`; None for ""."""
    if not text:
        return None
    if text in ("Z", "z"):
        return timezone.utc
    sign = -1 if text[0] == "-" else 1
    if text[0] not in "+-":
        raise ValueError(f"Invalid offset: {text!r}")
    body = text[1:].replace(":", "")
    frac = 0
    if "." in body:
        body, f = body.split(".", 1)
        frac = int((f + "000000")[:6])
    if len(body) not in (2, 4, 6) or not body.isdigit():
        raise ValueError(f"Invalid offset: {text!r}")
    hh = int(body[0:2])
    mm = int(body[2:4]) if len(body) >= 4 else 0
    ss = int(body[4:6]) if len(body) == 6 else 0
    off = timedelta(hours=hh, minutes=mm, seconds=ss, microseconds=frac)
    return timezone(off if sign > 0 else -off)


class tzinfo:
    """The base of time zones: subclasses say each moment's offset from UTC."""

    def utcoffset(self, dt):
        raise NotImplementedError("a tzinfo subclass must implement utcoffset()")

    def dst(self, dt):
        raise NotImplementedError("a tzinfo subclass must implement dst()")

    def tzname(self, dt):
        raise NotImplementedError("a tzinfo subclass must implement tzname()")

    def fromutc(self, dt):
        off = dt.utcoffset()
        if off is None:
            raise ValueError("fromutc: non-None utcoffset() result required")
        return dt + off


class timezone(tzinfo):
    """A fixed offset from UTC, with an optional name."""

    def __init__(self, offset, name=None):
        if not isinstance(offset, timedelta):
            raise TypeError("offset must be a timedelta")
        if not timedelta(hours=-24) < offset < timedelta(hours=24):
            raise ValueError("offset must be a timedelta strictly between -timedelta(hours=24) and timedelta(hours=24).")
        object.__setattr__(self, "_offset", offset)
        object.__setattr__(self, "_name", name)

    def utcoffset(self, dt):
        return self._offset

    def dst(self, dt):
        return None

    def tzname(self, dt):
        if self._name is not None:
            return self._name
        if not self._offset:
            return "UTC"
        return "UTC" + _format_offset(self._offset)

    def fromutc(self, dt):
        return dt + self._offset

    def __eq__(self, other):
        return isinstance(other, timezone) and self._offset == other._offset

    def __hash__(self):
        return hash(self._offset)

    def __repr__(self):
        if self is timezone.utc:
            return "datetime.timezone.utc"
        if self._name is None:
            return f"datetime.timezone({self._offset!r})"
        return f"datetime.timezone({self._offset!r}, {self._name!r})"

    def __str__(self):
        return self.tzname(None)

    def __setattr__(self, name, value):
        raise AttributeError("timezone is immutable")


def _strftime(fmt, y, m, d, hh, mi, ss, us, weekday, yearday, z="", zname=""):
    out = ""
    i = 0
    while i < len(fmt):
        c = fmt[i]
        if c != "%" or i + 1 >= len(fmt):
            out = out + c
            i = i + 1
            continue
        code = fmt[i + 1]
        i = i + 2
        if code == "Y":
            out = out + _pad(y, 4)
        elif code == "y":
            out = out + _pad(y % 100, 2)
        elif code == "m":
            out = out + _pad(m, 2)
        elif code == "d":
            out = out + _pad(d, 2)
        elif code == "e":
            out = out + str(d).rjust(2)
        elif code == "H":
            out = out + _pad(hh, 2)
        elif code == "I":
            out = out + _pad(hh % 12 or 12, 2)
        elif code == "M":
            out = out + _pad(mi, 2)
        elif code == "S":
            out = out + _pad(ss, 2)
        elif code == "f":
            out = out + _pad(us, 6)
        elif code == "p":
            out = out + ("AM" if hh < 12 else "PM")
        elif code == "A":
            out = out + _DAY_NAMES[weekday]
        elif code == "a":
            out = out + _DAY_NAMES[weekday][:3]
        elif code == "B":
            out = out + _MONTH_NAMES[m - 1]
        elif code == "b" or code == "h":
            out = out + _MONTH_NAMES[m - 1][:3]
        elif code == "j":
            out = out + _pad(yearday, 3)
        elif code == "w":
            out = out + str((weekday + 1) % 7)
        elif code == "u":
            out = out + str(weekday + 1)
        elif code == "F":
            out = out + _pad(y, 4) + "-" + _pad(m, 2) + "-" + _pad(d, 2)
        elif code == "T":
            out = out + _pad(hh, 2) + ":" + _pad(mi, 2) + ":" + _pad(ss, 2)
        elif code == "R":
            out = out + _pad(hh, 2) + ":" + _pad(mi, 2)
        elif code == "z":
            out = out + z
        elif code == "Z":
            out = out + zname
        elif code == "%":
            out = out + "%"
        else:
            out = out + "%" + code
    return out


class date:
    def __init__(self, year, month, day):
        _check_date(year, month, day)
        object.__setattr__(self, "year", year)
        object.__setattr__(self, "month", month)
        object.__setattr__(self, "day", day)

    @classmethod
    def today(cls):
        p = _time.parts()
        return date(p["year"], p["month"], p["day"])

    @classmethod
    def fromordinal(cls, n):
        y, m, d = _ord_to_ymd(n)
        return date(y, m, d)

    @classmethod
    def fromisoformat(cls, s):
        if len(s) != 10 or s[4] != "-" or s[7] != "-":
            raise ValueError(f"Invalid isoformat string: {s!r}")
        return date(int(s[:4]), int(s[5:7]), int(s[8:10]))

    @classmethod
    def fromtimestamp(cls, t):
        p = _time.parts(t)
        return date(p["year"], p["month"], p["day"])

    def toordinal(self):
        return _ymd_to_ord(self.year, self.month, self.day)

    def weekday(self):
        return (self.toordinal() + 6) % 7

    def isoweekday(self):
        return self.weekday() + 1

    def isocalendar(self):
        thursday = self + timedelta(days=3 - self.weekday())
        first = date(thursday.year, 1, 1)
        week = (thursday.toordinal() - first.toordinal()) // 7 + 1
        return (thursday.year, week, self.isoweekday())

    def timetuple(self):
        return (self.year, self.month, self.day, 0, 0, 0, self.weekday(), self.toordinal() - date(self.year, 1, 1).toordinal() + 1)

    def replace(self, year=None, month=None, day=None):
        return date(self.year if year is None else year, self.month if month is None else month, self.day if day is None else day)

    def isoformat(self):
        return f"{self.year:04d}-{self.month:02d}-{self.day:02d}"

    def strftime(self, fmt):
        yday = self.toordinal() - date(self.year, 1, 1).toordinal() + 1
        return _strftime(fmt, self.year, self.month, self.day, 0, 0, 0, 0, self.weekday(), yday)

    def __format__(self, spec):
        return self.strftime(spec) if spec else str(self)

    def __str__(self):
        return self.isoformat()

    def __repr__(self):
        return f"datetime.date({self.year}, {self.month}, {self.day})"

    def __add__(self, other):
        if isinstance(other, timedelta):
            return date.fromordinal(self.toordinal() + other.days)
        return NotImplemented

    def __radd__(self, other):
        return self.__add__(other)

    def __sub__(self, other):
        if isinstance(other, timedelta):
            return date.fromordinal(self.toordinal() - other.days)
        if isinstance(other, date) and not isinstance(other, datetime):
            return timedelta(days=self.toordinal() - other.toordinal())
        return NotImplemented

    def _key(self):
        return (self.year, self.month, self.day)

    def __eq__(self, other):
        if isinstance(other, datetime) or not isinstance(other, date):
            return False
        return self._key() == other._key()

    def __lt__(self, other):
        return self._key() < other._key()

    def __le__(self, other):
        return self._key() <= other._key()

    def __gt__(self, other):
        return self._key() > other._key()

    def __ge__(self, other):
        return self._key() >= other._key()

    def __hash__(self):
        return hash(self._key())

    def __setattr__(self, name, value):
        raise AttributeError("date is immutable; use replace()")


class time:
    def __init__(self, hour=0, minute=0, second=0, microsecond=0, tzinfo=None, *, fold=0):
        _check_time(hour, minute, second, microsecond)
        object.__setattr__(self, "hour", hour)
        object.__setattr__(self, "minute", minute)
        object.__setattr__(self, "second", second)
        object.__setattr__(self, "microsecond", microsecond)
        object.__setattr__(self, "tzinfo", tzinfo)
        object.__setattr__(self, "fold", fold)

    def utcoffset(self):
        return None if self.tzinfo is None else self.tzinfo.utcoffset(None)

    def tzname(self):
        return None if self.tzinfo is None else self.tzinfo.tzname(None)

    def dst(self):
        return None if self.tzinfo is None else self.tzinfo.dst(None)

    @classmethod
    def fromisoformat(cls, s):
        tz = None
        for i, c in enumerate(s):
            if c in "+-Zz":
                s, tz = s[:i], _parse_offset(s[i:])
                break
        parts = s.split(":")
        hh = int(parts[0])
        mi = int(parts[1]) if len(parts) > 1 else 0
        sec = parts[2] if len(parts) > 2 else "0"
        us = 0
        if "." in sec:
            sec, frac = sec.split(".", 1)
            us = int((frac + "000000")[:6])
        return time(hh, mi, int(sec), us, tz)

    def isoformat(self, timespec="auto"):
        return _time_iso(self.hour, self.minute, self.second, self.microsecond, timespec) + _format_offset(self.utcoffset())

    def strftime(self, fmt):
        z = _format_offset(self.utcoffset(), "")
        return _strftime(fmt, 1900, 1, 1, self.hour, self.minute, self.second, self.microsecond, 0, 1, z, self.tzname() or "")

    def replace(self, hour=None, minute=None, second=None, microsecond=None, tzinfo=True, *, fold=None):
        return time(self.hour if hour is None else hour, self.minute if minute is None else minute,
                    self.second if second is None else second, self.microsecond if microsecond is None else microsecond,
                    self.tzinfo if tzinfo is True else tzinfo, fold=self.fold if fold is None else fold)

    def _key(self):
        return (self.hour, self.minute, self.second, self.microsecond)

    def __eq__(self, other):
        return isinstance(other, time) and self._key() == other._key()

    def __lt__(self, other):
        return self._key() < other._key()

    def __le__(self, other):
        return self._key() <= other._key()

    def __gt__(self, other):
        return self._key() > other._key()

    def __ge__(self, other):
        return self._key() >= other._key()

    def __hash__(self):
        return hash(self._key())

    def __format__(self, spec):
        return self.strftime(spec) if spec else str(self)

    def __str__(self):
        return self.isoformat()

    def __repr__(self):
        parts = [str(self.hour), str(self.minute)]
        if self.second or self.microsecond:
            parts.append(str(self.second))
        if self.microsecond:
            parts.append(str(self.microsecond))
        if self.tzinfo is not None:
            parts.append(f"tzinfo={self.tzinfo!r}")
        return "datetime.time(" + ", ".join(parts) + ")"

    def __setattr__(self, name, value):
        raise AttributeError("time is immutable; use replace()")


def _time_iso(hh, mi, ss, us, timespec="auto"):
    if timespec == "auto":
        timespec = "microseconds" if us else "seconds"
    if timespec == "hours":
        return f"{hh:02d}"
    if timespec == "minutes":
        return f"{hh:02d}:{mi:02d}"
    s = f"{hh:02d}:{mi:02d}:{ss:02d}"
    if timespec == "seconds":
        return s
    if timespec == "milliseconds":
        return s + f".{us // 1000:03d}"
    if timespec == "microseconds":
        return s + f".{us:06d}"
    raise ValueError(f"Unknown timespec value: {timespec!r}")


def _naive_from_epoch(t):
    """The naive UTC datetime [t] seconds after the epoch."""
    whole = math.floor(t)
    us = int(round((t - whole) * 1000000))
    if us >= 1000000:
        whole = whole + 1
        us = us - 1000000
    days, secs = divmod(int(whole), 86400)
    return datetime._from_parts(_EPOCH_ORDINAL + days, secs, us)


class datetime(date):
    def __init__(self, year, month, day, hour=0, minute=0, second=0, microsecond=0, tzinfo=None, *, fold=0):
        _check_date(year, month, day)
        _check_time(hour, minute, second, microsecond)
        if tzinfo is not None and not isinstance(tzinfo, tzinfo_class):
            raise TypeError(f"tzinfo argument must be None or of a tzinfo subclass, not type '{type(tzinfo).__name__}'")
        object.__setattr__(self, "year", year)
        object.__setattr__(self, "month", month)
        object.__setattr__(self, "day", day)
        object.__setattr__(self, "hour", hour)
        object.__setattr__(self, "minute", minute)
        object.__setattr__(self, "second", second)
        object.__setattr__(self, "microsecond", microsecond)
        object.__setattr__(self, "tzinfo", tzinfo)
        object.__setattr__(self, "fold", fold)

    @classmethod
    def now(cls, tz=None):
        return datetime.fromtimestamp(_time.time(), tz)

    @classmethod
    def today(cls):
        return datetime.now()

    @classmethod
    def utcnow(cls):
        return _naive_from_epoch(_time.time())

    @classmethod
    def utcfromtimestamp(cls, t):
        return _naive_from_epoch(t)

    @classmethod
    def fromtimestamp(cls, t, tz=None):
        if tz is not None:
            return tz.fromutc(_naive_from_epoch(t).replace(tzinfo=tz))
        whole = int(t // 1)
        us = int(round((t - whole) * 1000000))
        if us >= 1000000:
            whole = whole + 1
            us = us - 1000000
        p = _time.parts(whole)
        return datetime(p["year"], p["month"], p["day"], p["hour"], p["minute"], p["second"], us)

    @classmethod
    def combine(cls, d, t, tzinfo=True):
        tz = t.tzinfo if tzinfo is True else tzinfo
        return datetime(d.year, d.month, d.day, t.hour, t.minute, t.second, t.microsecond, tz)

    @classmethod
    def fromisoformat(cls, s):
        if len(s) > 10 and s[10] in "T ":
            head, tail = s[:10], s[11:]
        else:
            head, tail = s[:10], s[10:]
        d = date.fromisoformat(head)
        if not tail:
            return datetime(d.year, d.month, d.day)
        t = time.fromisoformat(tail)
        return datetime.combine(d, t)

    @classmethod
    def strptime(cls, s, fmt):
        values = {"Y": 1900, "m": 1, "d": 1, "H": 0, "M": 0, "S": 0, "f": 0, "p": None, "z": None}
        i = 0
        j = 0
        while i < len(fmt):
            c = fmt[i]
            if c == "%" and i + 1 < len(fmt):
                code = fmt[i + 1]
                i = i + 2
                if code in ("b", "B"):
                    found = None
                    for k, name in enumerate(_MONTH_NAMES):
                        for candidate in (name, name[:3]):
                            if s[j:j + len(candidate)].lower() == candidate.lower():
                                found = (k + 1, len(candidate))
                                break
                        if found:
                            break
                    if not found:
                        raise ValueError(f"time data {s!r} does not match format {fmt!r}")
                    values["m"] = found[0]
                    j = j + found[1]
                    continue
                if code == "p":
                    values["p"] = s[j:j + 2].upper()
                    j = j + 2
                    continue
                if code in ("a", "A", "Z"):
                    while j < len(s) and s[j].isalpha():
                        j = j + 1
                    continue
                if code == "z":
                    start = j
                    if j < len(s) and s[j] in "Zz":
                        j = j + 1
                    else:
                        while j < len(s) and (s[j] in "+-:." or s[j].isdigit()):
                            j = j + 1
                    try:
                        values["z"] = _parse_offset(s[start:j])
                    except ValueError:
                        raise ValueError(f"time data {s!r} does not match format {fmt!r}") from None
                    continue
                width = 4 if code == "Y" else 6 if code == "f" else 2
                start = j
                while j < len(s) and j - start < width and s[j].isdigit():
                    j = j + 1
                if start == j:
                    raise ValueError(f"time data {s!r} does not match format {fmt!r}")
                key = "H" if code == "I" else code
                values[key] = int(s[start:j]) if code != "f" else int((s[start:j] + "000000")[:6])
            else:
                if j >= len(s) or s[j] != c:
                    raise ValueError(f"time data {s!r} does not match format {fmt!r}")
                i = i + 1
                j = j + 1
        if j != len(s):
            raise ValueError(f"unconverted data remains: {s[j:]}")
        hour = values["H"]
        if values["p"] == "PM" and hour < 12:
            hour = hour + 12
        if values["p"] == "AM" and hour == 12:
            hour = 0
        tz = values["z"]
        return datetime(values["Y"], values["m"], values["d"], hour, values["M"], values["S"], values["f"], tz)

    def utcoffset(self):
        return None if self.tzinfo is None else self.tzinfo.utcoffset(self)

    def tzname(self):
        return None if self.tzinfo is None else self.tzinfo.tzname(self)

    def dst(self):
        return None if self.tzinfo is None else self.tzinfo.dst(self)

    def timestamp(self):
        off = self.utcoffset()
        if off is not None:
            delta = self.replace(tzinfo=None) - off - datetime(1970, 1, 1)
            return delta.total_seconds()
        return _native.mktime(self.year, self.month, self.day, self.hour, self.minute, self.second) + self.microsecond / 1000000

    def astimezone(self, tz=None):
        if self.utcoffset() is None:
            utc = _naive_from_epoch(self.timestamp())
        else:
            utc = self.replace(tzinfo=None) - self.utcoffset()
        if tz is None:
            stamp = (utc - datetime(1970, 1, 1)).total_seconds()
            local = datetime.fromtimestamp(stamp)
            tz = timezone(local - utc, _time.strftime("%Z", _time.localtime(stamp)))
            return local.replace(tzinfo=tz)
        return tz.fromutc(utc.replace(tzinfo=tz))

    def date(self):
        return date(self.year, self.month, self.day)

    def time(self):
        return time(self.hour, self.minute, self.second, self.microsecond)

    def timetz(self):
        return time(self.hour, self.minute, self.second, self.microsecond, self.tzinfo)

    def utctimetuple(self):
        d = self if self.utcoffset() is None else self.replace(tzinfo=None) - self.utcoffset()
        return _time.struct_time(d.timetuple()[:6] + (d.weekday(), d.toordinal() - date(d.year, 1, 1).toordinal() + 1, 0))

    def timetuple(self):
        yday = self.toordinal() - date(self.year, 1, 1).toordinal() + 1
        return _time.struct_time((self.year, self.month, self.day, self.hour, self.minute, self.second, self.weekday(), yday, -1))

    def replace(self, year=None, month=None, day=None, hour=None, minute=None, second=None, microsecond=None, tzinfo=True, *, fold=None):
        return datetime(
            self.year if year is None else year, self.month if month is None else month, self.day if day is None else day,
            self.hour if hour is None else hour, self.minute if minute is None else minute,
            self.second if second is None else second, self.microsecond if microsecond is None else microsecond,
            self.tzinfo if tzinfo is True else tzinfo, fold=self.fold if fold is None else fold)

    def isoformat(self, sep="T", timespec="auto"):
        t = _time_iso(self.hour, self.minute, self.second, self.microsecond, timespec)
        return date.isoformat(self) + sep + t + _format_offset(self.utcoffset())

    def strftime(self, fmt):
        yday = self.toordinal() - date(self.year, 1, 1).toordinal() + 1
        z = _format_offset(self.utcoffset(), "")
        return _strftime(fmt, self.year, self.month, self.day, self.hour, self.minute, self.second, self.microsecond,
                         self.weekday(), yday, z, self.tzname() or "")

    def _parts(self):
        return self.toordinal(), self.hour * 3600 + self.minute * 60 + self.second, self.microsecond

    def _utc_parts(self):
        off = self.utcoffset()
        days, secs, micro = self._parts()
        if off is None:
            return days, secs, micro
        return days - off.days, secs - off.seconds, micro - off.microseconds

    @classmethod
    def _from_parts(cls, days, secs, micro, tz=None):
        carry, micro = divmod(micro, 1000000)
        secs = secs + carry
        carry, secs = divmod(secs, 86400)
        y, m, d = _ord_to_ymd(days + carry)
        hh, rem = divmod(secs, 3600)
        mi, ss = divmod(rem, 60)
        return datetime(y, m, d, hh, mi, ss, micro, tz)

    def __add__(self, other):
        if isinstance(other, timedelta):
            days, secs, micro = self._parts()
            return datetime._from_parts(days + other.days, secs + other.seconds, micro + other.microseconds, self.tzinfo)
        return NotImplemented

    def __radd__(self, other):
        return self.__add__(other)

    def _same_kind(self, other, verb):
        if (self.utcoffset() is None) != (other.utcoffset() is None):
            raise TypeError(f"can't {verb} offset-naive and offset-aware datetimes")

    def __sub__(self, other):
        if isinstance(other, timedelta):
            return self + (-other)
        if isinstance(other, datetime):
            self._same_kind(other, "subtract")
            if self.tzinfo is other.tzinfo:
                a, b = self._parts(), other._parts()
            else:
                a, b = self._utc_parts(), other._utc_parts()
            return timedelta(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        return NotImplemented

    def _key(self):
        d, s, us = self._utc_parts()
        return (d, s, us)

    def _cmp_key(self, other):
        if not isinstance(other, datetime):
            raise TypeError(f"can't compare datetime.datetime to {type(other).__name__}")
        self._same_kind(other, "compare")
        return self._key(), other._key()

    def __eq__(self, other):
        if not isinstance(other, datetime):
            return False
        if (self.utcoffset() is None) != (other.utcoffset() is None):
            return False
        return self._key() == other._key()

    def __lt__(self, other):
        a, b = self._cmp_key(other)
        return a < b

    def __le__(self, other):
        a, b = self._cmp_key(other)
        return a <= b

    def __gt__(self, other):
        a, b = self._cmp_key(other)
        return a > b

    def __ge__(self, other):
        a, b = self._cmp_key(other)
        return a >= b

    def __hash__(self):
        return hash(self._key())

    def __str__(self):
        return self.isoformat(" ")

    def __repr__(self):
        parts = [self.year, self.month, self.day, self.hour, self.minute]
        if self.second or self.microsecond:
            parts.append(self.second)
        if self.microsecond:
            parts.append(self.microsecond)
        inner = ", ".join(str(p) for p in parts)
        if self.tzinfo is not None:
            inner = inner + f", tzinfo={self.tzinfo!r}"
        return "datetime.datetime(" + inner + ")"


tzinfo_class = tzinfo


date.min = date(1, 1, 1)
date.max = date(9999, 12, 31)

timezone.utc = timezone(timedelta(0))
timezone.min = timezone(timedelta(hours=-23, minutes=-59))
timezone.max = timezone(timedelta(hours=23, minutes=59))
UTC = timezone.utc
datetime.min = datetime(1, 1, 1)
datetime.max = datetime(9999, 12, 31, 23, 59, 59, 999999)
datetime.resolution = timedelta(microseconds=1)
time.min = time(0, 0)
time.max = time(23, 59, 59, 999999)
