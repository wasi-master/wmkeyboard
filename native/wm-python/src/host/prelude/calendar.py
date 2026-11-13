"""The phone's calendar. Needs the risky `calendar` permission, and Android's
calendar permission, which the keyboard asks for the first time.

    week = calendar.events(start, start + 7 * 86400)
    calendar.add("Focus time", start, start + 3600)
"""

from wm import _core


def events(start, end, then=None, on_error=None):
    """Events from start to end (seconds since 1970, at most 62 days apart),
    soonest first, as dicts with id, title, start, end, all_day, location,
    calendar and recurring. A repeating event comes once per occurrence."""
    args = {"start": float(start), "end": float(end)}
    if then is not None:
        return _core.defer("calendar.events", args, then, on_error)
    return _core.call("calendar.events", args)


def calendars():
    """The calendars an event can be added to, as dicts with id, name,
    account and primary, the primary ones first."""
    return _core.call("calendar.calendars", {})


def add(title, start, end, all_day=False, calendar=None, location=None, notes=None):
    """Adds an event and returns its id. calendar is an id from calendars();
    None means the first primary calendar. An all-day event covers the dates
    from start up to end."""
    args = {"title": str(title), "start": float(start), "end": float(end), "all_day": bool(all_day),
            "calendar": calendar, "location": location, "notes": notes}
    return _core.call("calendar.add", args)
