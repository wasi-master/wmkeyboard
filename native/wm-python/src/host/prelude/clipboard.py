"""The system clipboard. get() needs `clipboard.read` and, like the
field, works only while handling a user's action in the panel; it never
sees clips marked private. set() needs `clipboard.write`."""

from wm import _core


def get():
    return _core.call("clipboard.get", {}) or ""


def set(text):
    _core.call("clipboard.set", {"text": str(text)})


copy = set
paste = get
