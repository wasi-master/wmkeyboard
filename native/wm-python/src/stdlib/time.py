"""time: the clock, and dates as struct_time, as Python's time module has
them. sleep() can't wait, as the keyboard has to keep answering: sleep(0)
returns, and longer refuses. A plugin waits with plugin.after() instead.

The keyboard's own additions stay: parts(t) and format(pattern, t).
"""

from _time import time, monotonic, parts, format, sleep
import _native

_DAYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
_FIELDS = ("tm_year", "tm_mon", "tm_mday", "tm_hour", "tm_min", "tm_sec", "tm_wday", "tm_yday", "tm_isdst")


def time_ns():
    return int(time() * 1_000_000_000)


def monotonic_ns():
    return int(monotonic() * 1_000_000_000)


perf_counter = monotonic
process_time = monotonic
thread_time = monotonic
perf_counter_ns = monotonic_ns
process_time_ns = monotonic_ns
thread_time_ns = monotonic_ns


class struct_time:
    """A date and time as nine numbers, which unpack and index like a tuple."""

    n_fields = 11
    n_sequence_fields = 9

    def __init__(self, values, zone=None, gmtoff=None):
        values = tuple(values)
        if len(values) < 9:
            raise TypeError(f"time.struct_time() takes an at least 9-sequence ({len(values)}-sequence given)")
        if len(values) >= 11:
            zone, gmtoff = values[9], values[10]
        object.__setattr__(self, "_values", values[:9])
        object.__setattr__(self, "tm_zone", zone)
        object.__setattr__(self, "tm_gmtoff", gmtoff)
        for name, v in zip(_FIELDS, values):
            object.__setattr__(self, name, v)

    def __setattr__(self, name, value):
        raise AttributeError("readonly attribute")

    def __getitem__(self, i):
        return self._values[i]

    def __len__(self):
        return 9

    def __iter__(self):
        return iter(self._values)

    def __eq__(self, other):
        if isinstance(other, struct_time):
            return self._values == other._values
        return self._values == other

    def __lt__(self, other):
        return self._values < tuple(other)

    def __hash__(self):
        return hash(self._values)

    def __repr__(self):
        inner = ", ".join(f"{n}={v}" for n, v in zip(_FIELDS, self._values))
        return f"time.struct_time({inner})"


def _offset(secs):
    """The local zone's offset from UTC at secs, in seconds."""
    p = parts(secs)
    local = _days_from_civil(p["year"], p["month"], p["day"]) * 86400 + p["hour"] * 3600 + p["minute"] * 60 + p["second"]
    return local - int(secs // 1)


def _days_from_civil(y, m, d):
    y -= m <= 2
    era = (y if y >= 0 else y - 399) // 400
    yoe = y - era * 400
    doy = (153 * (m + (-3 if m > 2 else 9)) + 2) // 5 + d - 1
    doe = yoe * 365 + yoe // 4 - yoe // 100 + doy
    return era * 146097 + doe - 719468


def _civil_from_days(z):
    z += 719468
    era = (z if z >= 0 else z - 146096) // 146097
    doe = z - era * 146097
    yoe = (doe - doe // 1460 + doe // 36524 - doe // 146096) // 365
    y = yoe + era * 400
    doy = doe - (365 * yoe + yoe // 4 - yoe // 100)
    mp = (5 * doy + 2) // 153
    d = doy - (153 * mp + 2) // 5 + 1
    m = mp + 3 if mp < 10 else mp - 9
    return (y + 1 if m <= 2 else y), m, d


def _zone_name(offset):
    if offset == 0:
        return "UTC"
    sign = "+" if offset > 0 else "-"
    h, rem = divmod(abs(offset), 3600)
    return f"{sign}{h:02d}" + (f"{rem // 60:02d}" if rem else "")


def gmtime(secs=None):
    if secs is None:
        secs = time()
    whole = int(secs // 1)
    days, rem = divmod(whole, 86400)
    y, m, d = _civil_from_days(days)
    yday = days - _days_from_civil(y, 1, 1) + 1
    return struct_time((y, m, d, rem // 3600, rem % 3600 // 60, rem % 60, (days + 3) % 7, yday, 0), "UTC", 0)


def localtime(secs=None):
    if secs is None:
        secs = time()
    p = parts(secs)
    off = _offset(secs)
    return struct_time((p["year"], p["month"], p["day"], p["hour"], p["minute"], p["second"], p["weekday"], p["yearday"], 0),
                       _zone_name(off), off)


def mktime(t):
    return float(_native.mktime(t[0], t[1], t[2], t[3], t[4], t[5]))


def _tuple(t):
    if t is None:
        return localtime()
    if not isinstance(t, struct_time):
        t = struct_time(t)
    return t


def strftime(fmt, t=None):
    t = _tuple(t)
    off = t.tm_gmtoff if t.tm_gmtoff is not None else _offset(mktime(t))
    # %z and %Z first, which the shared formatter does not know.
    out = []
    i = 0
    while i < len(fmt):
        if fmt[i] == "%" and i + 1 < len(fmt):
            code = fmt[i + 1]
            if code == "z":
                sign = "+" if off >= 0 else "-"
                out.append(f"{sign}{abs(off) // 3600:02d}{abs(off) % 3600 // 60:02d}")
            elif code == "Z":
                out.append(t.tm_zone or _zone_name(off))
            else:
                out.append(fmt[i:i + 2])
            i += 2
        else:
            out.append(fmt[i])
            i += 1
    from datetime import _strftime
    return _strftime("".join(out), t.tm_year, t.tm_mon, t.tm_mday, t.tm_hour, t.tm_min, t.tm_sec, 0, t.tm_wday, t.tm_yday)


def asctime(t=None):
    t = _tuple(t)
    return f"{_DAYS[t.tm_wday]} {_MONTHS[t.tm_mon - 1]} {t.tm_mday:2d} {t.tm_hour:02d}:{t.tm_min:02d}:{t.tm_sec:02d} {t.tm_year}"


def ctime(secs=None):
    return asctime(localtime(secs))


def strptime(string, format="%a %b %d %H:%M:%S %Y"):
    from datetime import datetime
    dt = datetime.strptime(string, format)
    yday = _days_from_civil(dt.year, dt.month, dt.day) - _days_from_civil(dt.year, 1, 1) + 1
    return struct_time((dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, dt.weekday(), yday, -1))


timezone = -_offset(time())
altzone = timezone
daylight = 0
tzname = (_zone_name(-timezone), _zone_name(-timezone))
