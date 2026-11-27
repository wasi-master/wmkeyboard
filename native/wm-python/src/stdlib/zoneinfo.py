"""zoneinfo: time zones from the IANA database.

Plugins have no time zone files, so only UTC and its aliases (Etc/UTC, GMT,
Z, ...) can be made; any other key raises ZoneInfoNotFoundError, as
CPython does on a system without tzdata. For the phone's own zone, a naive
datetime is already read in it (see datetime).
"""

from datetime import timedelta, tzinfo

__all__ = ["ZoneInfo", "reset_tzpath", "available_timezones", "TZPATH", "ZoneInfoNotFoundError", "InvalidTZPathWarning"]

TZPATH = ()

_UTC_KEYS = {"UTC", "Etc/UTC", "Etc/UCT", "UCT", "Etc/Universal", "Universal", "Etc/Zulu", "Zulu", "GMT", "Etc/GMT", "Etc/GMT0", "Etc/GMT+0", "Etc/GMT-0", "GMT0", "GMT+0", "GMT-0", "Etc/Greenwich", "Greenwich"}


class ZoneInfoNotFoundError(KeyError):
    pass


class InvalidTZPathWarning(RuntimeWarning):
    pass


_ZERO = timedelta(0)
_cache = {}


class ZoneInfo(tzinfo):
    def __new__(cls, key):
        if key in _cache:
            return _cache[key]
        zone = cls.no_cache(key)
        _cache[key] = zone
        return zone

    @classmethod
    def no_cache(cls, key):
        if key not in _UTC_KEYS:
            raise ZoneInfoNotFoundError(f"No time zone found with key {key}")
        zone = object.__new__(cls)
        zone._key = key
        return zone

    def __init__(self, key):
        pass

    @classmethod
    def from_file(cls, fobj, key=None):
        raise ValueError("plugins have no time zone data to read")

    @classmethod
    def clear_cache(cls, *, only_keys=None):
        if only_keys is None:
            _cache.clear()
        else:
            for k in only_keys:
                _cache.pop(k, None)

    @property
    def key(self):
        return self._key

    def utcoffset(self, dt):
        return _ZERO

    def dst(self, dt):
        return _ZERO

    def tzname(self, dt):
        return "UTC"

    def fromutc(self, dt):
        return dt.replace(tzinfo=self)

    def __str__(self):
        return self._key

    def __repr__(self):
        return f"zoneinfo.ZoneInfo(key={self._key!r})"

    def __reduce__(self):
        return (type(self), (self._key,))


def available_timezones():
    return set(_UTC_KEYS)


def reset_tzpath(to=None):
    pass
