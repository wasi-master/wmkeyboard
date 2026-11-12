"""The text field the keyboard is typing into.

Reading (`field.read`) works only while the plugin handles something the
user did in its own panel: a tap, a choice, a submitted box. A timer, an
answer from the network or on_open cannot read the field. Writing
(`field.write`) is allowed any time the panel is open. Neither works in a
password field or in incognito; both raise PermissionError there.
"""

from wm import _core


def selection():
    """The selected text, or "" when nothing is selected."""
    return _core.call("field.selection", {}) or ""


def text_before(n=1000):
    return _core.call("field.before", {"n": int(n)}) or ""


def text_after(n=1000):
    return _core.call("field.after", {"n": int(n)}) or ""


def info():
    """{"app": package, "kind": "text"|"email"|"url"|"number"|..., "hint": str, "multiline": bool}."""
    return _core.call("field.info", {}) or {}


def insert(text):
    """Type [text] at the cursor, replacing any selection."""
    _core.call("field.insert", {"text": str(text)})


def replace_selection(text):
    _core.call("field.replace", {"text": str(text)})


def delete(before=1, after=0):
    _core.call("field.delete", {"before": int(before), "after": int(after)})


def move_cursor(by):
    """Move the cursor [by] characters, back when negative."""
    _core.call("field.move", {"by": int(by)})


def send():
    """Press the field's action key (Send, Search, Go)."""
    _core.call("field.action", {})


def insert_image(data, mime="image/png"):
    """Send a picture to apps that take one (a messenger's attach)."""
    _core.call("field.image", {"data": data, "mime": str(mime)})
