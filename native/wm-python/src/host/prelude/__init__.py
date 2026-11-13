"""WM Keyboard's plugin library (API 5).

    from wm import plugin, ui, storage, http

Every module here is ordinary Python running in the same sandbox as the
plugin. What reaches outside the sandbox goes through one host call, which
the keyboard checks against the permissions in the manifest and the ones the
user granted.
"""

import _wm

api_version = 5
plugin_id = _wm.plugin_id
plugin_version = _wm.plugin_version


def log(*values):
    """Write a line to the plugin's log, as print() does."""
    _wm.log(" ".join(str(v) for v in values))


def timestamp(year, month, day, hour=12, minute=0, second=0):
    """That moment in the phone's time zone, in seconds since 1970."""
    return _wm.timestamp(year, month, day, hour, minute, second)


_zones = {}


def zone_offset(zone=None, seconds=None):
    """The UTC offset in seconds of the time zone [zone] (an IANA name such
    as "Asia/Tokyo", or None for the phone's own) at [seconds] since 1970,
    now by default. Summer time is counted. Raises ValueError for a zone
    the keyboard does not know."""
    import time as _time
    key = "" if zone is None else str(zone)
    rows = _zones.get(key)
    if rows is None:
        rows = _wm.call("time.zone", {"zone": key})["rows"]
        _zones[key] = rows
    at = _time.time() if seconds is None else seconds
    offset = 0
    for start, value in rows:
        if start > at:
            break
        offset = value
    return offset


from wm._storage import Storage as _Storage

storage = _Storage()

from wm.i18n import _, gettext, ngettext
