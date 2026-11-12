"""datetime: dates, times of day, and spans between them.

Every datetime here is naive and in the phone's time zone: `now()` reads the
local clock, `timestamp()` and `fromtimestamp()` convert through the local
zone. `strftime` takes the C codes Python uses (%Y-%m-%d %H:%M).
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
    (0..999999), as Python keeps it. Numbers here are 64-bit, so the parts
    are carried separately rather than as one count of microseconds."""

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
        return "datetime.timedelta(" + ", ".join(parts) + ")"

    def __setattr__(self, name, value):
        raise AttributeError("timedelta is immutable")


timedelta.min = timedelta(days=-999999999)
timedelta.max = timedelta(days=999999999, hours=23, minutes=59, seconds=59, microseconds=999999)
timedelta.resolution = timedelta(microseconds=1)


def _pad(n, width):
    return str(n).zfill(width)


def _strftime(fmt, y, m, d, hh, mi, ss, us, weekday, yearday):
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
    def __init__(self, hour=0, minute=0, second=0, microsecond=0):
        _check_time(hour, minute, second, microsecond)
        object.__setattr__(self, "hour", hour)
        object.__setattr__(self, "minute", minute)
        object.__setattr__(self, "second", second)
        object.__setattr__(self, "microsecond", microsecond)

    @classmethod
    def fromisoformat(cls, s):
        parts = s.split(":")
        hh = int(parts[0])
        mi = int(parts[1]) if len(parts) > 1 else 0
        sec = parts[2] if len(parts) > 2 else "0"
        us = 0
        if "." in sec:
            sec, frac = sec.split(".", 1)
            us = int((frac + "000000")[:6])
        return time(hh, mi, int(sec), us)

    def isoformat(self):
        s = f"{self.hour:02d}:{self.minute:02d}:{self.second:02d}"
        if self.microsecond:
            s = s + f".{self.microsecond:06d}"
        return s

    def strftime(self, fmt):
        return _strftime(fmt, 1900, 1, 1, self.hour, self.minute, self.second, self.microsecond, 0, 1)

    def replace(self, hour=None, minute=None, second=None, microsecond=None):
        return time(self.hour if hour is None else hour, self.minute if minute is None else minute,
                    self.second if second is None else second, self.microsecond if microsecond is None else microsecond)

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
        return f"datetime.time({self.hour}, {self.minute}, {self.second}, {self.microsecond})"

    def __setattr__(self, name, value):
        raise AttributeError("time is immutable; use replace()")


class datetime(date):
    def __init__(self, year, month, day, hour=0, minute=0, second=0, microsecond=0):
        _check_date(year, month, day)
        _check_time(hour, minute, second, microsecond)
        object.__setattr__(self, "year", year)
        object.__setattr__(self, "month", month)
        object.__setattr__(self, "day", day)
        object.__setattr__(self, "hour", hour)
        object.__setattr__(self, "minute", minute)
        object.__setattr__(self, "second", second)
        object.__setattr__(self, "microsecond", microsecond)

    @classmethod
    def now(cls):
        return datetime.fromtimestamp(_time.time())

    @classmethod
    def today(cls):
        return datetime.now()

    @classmethod
    def fromtimestamp(cls, t):
        whole = int(t // 1)
        us = int(round((t - whole) * 1000000))
        if us >= 1000000:
            whole = whole + 1
            us = us - 1000000
        p = _time.parts(whole)
        return datetime(p["year"], p["month"], p["day"], p["hour"], p["minute"], p["second"], us)

    @classmethod
    def combine(cls, d, t):
        return datetime(d.year, d.month, d.day, t.hour, t.minute, t.second, t.microsecond)

    @classmethod
    def fromisoformat(cls, s):
        s = s.replace("T", " ")
        if s.endswith("Z"):
            s = s[:-1]
        head, _, tail = s.partition(" ")
        d = date.fromisoformat(head[:10])
        if not tail:
            return datetime(d.year, d.month, d.day)
        for sign in ["+", "-"]:
            if sign in tail:
                tail = tail.split(sign)[0]
        t = time.fromisoformat(tail)
        return datetime.combine(d, t)

    @classmethod
    def strptime(cls, s, fmt):
        values = {"Y": 1900, "m": 1, "d": 1, "H": 0, "M": 0, "S": 0, "f": 0, "p": None}
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
                if code in ("a", "A"):
                    while j < len(s) and s[j].isalpha():
                        j = j + 1
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
        return datetime(values["Y"], values["m"], values["d"], hour, values["M"], values["S"], values["f"])

    def timestamp(self):
        return _native.mktime(self.year, self.month, self.day, self.hour, self.minute, self.second) + self.microsecond / 1000000

    def date(self):
        return date(self.year, self.month, self.day)

    def time(self):
        return time(self.hour, self.minute, self.second, self.microsecond)

    def replace(self, year=None, month=None, day=None, hour=None, minute=None, second=None, microsecond=None):
        return datetime(
            self.year if year is None else year, self.month if month is None else month, self.day if day is None else day,
            self.hour if hour is None else hour, self.minute if minute is None else minute,
            self.second if second is None else second, self.microsecond if microsecond is None else microsecond)

    def isoformat(self, sep="T"):
        return date.isoformat(self) + sep + self.time().isoformat()

    def strftime(self, fmt):
        yday = self.toordinal() - date(self.year, 1, 1).toordinal() + 1
        return _strftime(fmt, self.year, self.month, self.day, self.hour, self.minute, self.second, self.microsecond, self.weekday(), yday)

    def _parts(self):
        return self.toordinal(), self.hour * 3600 + self.minute * 60 + self.second, self.microsecond

    @classmethod
    def _from_parts(cls, days, secs, micro):
        carry, micro = divmod(micro, 1000000)
        secs = secs + carry
        carry, secs = divmod(secs, 86400)
        y, m, d = _ord_to_ymd(days + carry)
        hh, rem = divmod(secs, 3600)
        mi, ss = divmod(rem, 60)
        return datetime(y, m, d, hh, mi, ss, micro)

    def __add__(self, other):
        if isinstance(other, timedelta):
            days, secs, micro = self._parts()
            return datetime._from_parts(days + other.days, secs + other.seconds, micro + other.microseconds)
        return NotImplemented

    def __radd__(self, other):
        return self.__add__(other)

    def __sub__(self, other):
        if isinstance(other, timedelta):
            return self + (-other)
        if isinstance(other, datetime):
            a = self._parts()
            b = other._parts()
            return timedelta(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        return NotImplemented

    def _key(self):
        return (self.year, self.month, self.day, self.hour, self.minute, self.second, self.microsecond)

    def __eq__(self, other):
        return isinstance(other, datetime) and self._key() == other._key()

    def __str__(self):
        return self.isoformat(" ")

    def __repr__(self):
        parts = [self.year, self.month, self.day, self.hour, self.minute]
        if self.second or self.microsecond:
            parts.append(self.second)
        if self.microsecond:
            parts.append(self.microsecond)
        return "datetime.datetime(" + ", ".join(str(p) for p in parts) + ")"


date.min = date(1, 1, 1)
date.max = date(9999, 12, 31)
