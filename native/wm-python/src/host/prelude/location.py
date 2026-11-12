"""Where the phone is. Needs `location`, and Android's location permission,
which the keyboard asks for the first time.

    here = location.get()          # {"lat", "lon", "accuracy", "time"}
    location.get(then=show)        # without waiting
"""

from wm import _core


def get(accuracy="coarse", then=None, on_error=None):
    args = {"accuracy": "fine" if accuracy == "fine" else "coarse"}
    if then is not None:
        return _core.defer("location.get", args, then, on_error)
    return _core.call("location.get", args)
