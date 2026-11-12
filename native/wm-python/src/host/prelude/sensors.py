"""Motion and light sensors while the panel is open. Needs `sensors`.

    sensors.subscribe("accelerometer", on_reading=moved, hz=10)

kind: "accelerometer", "gyroscope", "magnetometer", "light", "steps",
"proximity", "pressure". on_reading hears a dict ({"x","y","z"} or
{"value"}). Readings stop when the panel closes.
"""

from wm import _core


def subscribe(kind, on_reading, hz=5):
    hz = max(1, min(int(hz), 30))
    return _core.stream("sensors.subscribe", {"kind": str(kind), "hz": hz}, on_reading)


def unsubscribe(token):
    _core.end_stream(token)
    _core.call("sensors.unsubscribe", {"token": token})


def available():
    return list(_core.call("sensors.list", {}) or [])
