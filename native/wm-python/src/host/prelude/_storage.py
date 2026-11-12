"""`wm.storage`: the plugin's own storage, kept on the phone, used like a
dict of JSON values.

    from wm import storage
    storage["notes"] = ["milk", "eggs"]
    for note in storage.get("notes", []):
        ...
    del storage["notes"]

Up to 1 MB per plugin (64 KB for one value) under API 5. Needs the
`storage` permission.
"""

import json
import _wm

_HIDDEN = "__state__:"


def _decode(raw):
    try:
        return json.loads(raw)
    except ValueError:
        # Written by an older plugin as a bare string.
        return raw


class Storage:
    def get(self, key, default=None):
        raw = _wm.storage_get(str(key))
        if raw is None:
            return default
        return _decode(raw)

    def set(self, key, value):
        _wm.storage_set(str(key), json.dumps(value))

    def remove(self, key):
        _wm.storage_remove(str(key))

    def keys(self):
        return [k for k in _wm.storage_keys() if not k.startswith(_HIDDEN)]

    def items(self):
        return [(k, self.get(k)) for k in self.keys()]

    def values(self):
        return [self.get(k) for k in self.keys()]

    def clear(self):
        for k in self.keys():
            self.remove(k)

    def setdefault(self, key, default=None):
        raw = _wm.storage_get(str(key))
        if raw is None:
            self.set(key, default)
            return default
        return _decode(raw)

    def pop(self, key, *default):
        raw = _wm.storage_get(str(key))
        if raw is None:
            if default:
                return default[0]
            raise KeyError(key)
        self.remove(key)
        return _decode(raw)

    def update(self, other=None, **more):
        pairs = dict(other or {})
        pairs.update(more)
        for k, v in pairs.items():
            self.set(k, v)

    def used(self):
        """Bytes used, out of `quota`."""
        return _wm.storage_used()

    @property
    def quota(self):
        return _wm.storage_quota

    def __getitem__(self, key):
        raw = _wm.storage_get(str(key))
        if raw is None:
            raise KeyError(key)
        return _decode(raw)

    def __setitem__(self, key, value):
        self.set(key, value)

    def __delitem__(self, key):
        if _wm.storage_get(str(key)) is None:
            raise KeyError(key)
        self.remove(key)

    def __contains__(self, key):
        return _wm.storage_get(str(key)) is not None

    def __len__(self):
        return len(self.keys())

    def __iter__(self):
        return iter(self.keys())

    def __repr__(self):
        return f"wm.storage({dict(self.items())})"
