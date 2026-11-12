"""Keys and tokens the plugin needs for a service, kept encrypted like the
keyboard's own API keys and never shown in the storage inspector or a
backup. Needs `storage`.

    token = secrets.get("api_token")
"""

from wm import _core


def get(name, default=None):
    v = _core.call("secrets.get", {"name": str(name)})
    return default if v is None else v


def set(name, value):
    _core.call("secrets.set", {"name": str(name), "value": str(value)})


def remove(name):
    _core.call("secrets.remove", {"name": str(name)})
