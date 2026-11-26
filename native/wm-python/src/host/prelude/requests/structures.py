"""Dict-like objects requests hands back: headers whose names match in
any case, and the lookup table behind requests.codes."""

from wm._net import CaseInsensitiveDict


class LookupDict:
    """Names looked up as attributes or keys: codes.ok, codes["not_found"].
    A name it does not have is None, as in requests."""

    def __init__(self, name=None):
        self.name = name
        self._values = {}

    def __repr__(self):
        return f"<lookup '{self.name}'>"

    def __getattr__(self, key):
        if key.startswith("__"):
            raise AttributeError(key)
        return self._values.get(key)

    def __getitem__(self, key):
        return self._values.get(key)

    def __contains__(self, key):
        return key in self._values

    def get(self, key, default=None):
        return self._values.get(key, default)
