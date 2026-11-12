"""Hand something to another app. Needs `share`; the keyboard always shows
what is being shared and where before it goes."""

from wm import _core


def text(text, title=None):
    _core.call("share.text", {"text": str(text), "title": title})


def open(url):
    """Open an https link in the browser, after the user confirms."""
    _core.call("share.open", {"url": str(url)})
