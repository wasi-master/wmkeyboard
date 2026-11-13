"""The keyboard's own Clock: alarms that ring and timers that count down,
even with the keyboard closed. Needs `clock`.

    clock.alarm(train - 10 * 60, "Train to Tokyo")
    clock.timer(25 * 60, "Get off at Shibuya")

The alarm or timer is the user's from then on: it shows in the Clock tool,
rings with their sound, and they change or delete it there. The keyboard
says so on screen each time a plugin sets one.
"""

from wm import _core


def alarm(seconds, label=None):
    """An alarm that rings once at [seconds] since 1970 (to the minute),
    which must be within the next 24 hours."""
    return _core.call("clock.alarm", {"at": float(seconds), "label": "" if label is None else str(label)})


def timer(seconds, label=None):
    """A timer that rings [seconds] from now, 1 second to 24 hours."""
    return _core.call("clock.timer", {"seconds": float(seconds), "label": "" if label is None else str(label)})
