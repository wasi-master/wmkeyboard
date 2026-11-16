"""Hand something to another app. Needs `share`; the keyboard always shows
what is being shared and where before it goes."""

from wm import _core


def text(text, title=None):
    _core.call("share.text", {"text": str(text), "title": title})


def open(url, ask=True):
    """Open a link. With ask=True (the default) an https link goes through
    Android's chooser, which shows the address and lets the user pick the app.
    With ask=False an http or https link opens straight in the phone's own app
    for it, and only while the plugin answers something the user did in its
    panel, such as a tap on a saved link."""
    if ask:
        _core.call("share.open", {"url": str(url)})
    else:
        _core.call("share.visit", {"url": str(url)})
