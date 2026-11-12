"""A notification, titled with the plugin's name. Needs `notifications`."""

from wm import _core


def show(text, title=None, id=None):
    _core.call("notify.show", {"text": str(text), "title": title, "id": id})


def cancel(id):
    _core.call("notify.cancel", {"id": id})
