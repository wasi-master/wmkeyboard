"""Small things the phone does. Needs `device`."""

from wm import _core


def vibrate(ms=30):
    _core.call("device.vibrate", {"ms": max(1, min(int(ms), 1000))})


def flashlight(on=True):
    _core.call("device.flashlight", {"on": bool(on)})


def speak(text, language=None):
    """Read [text] aloud with the phone's voice."""
    _core.call("device.speak", {"text": str(text), "language": language})


def battery():
    """{"level": 0-100, "charging": bool}."""
    return _core.call("device.battery", {})
