"""WM Keyboard's plugin library (API 5).

    from wm import plugin, ui, storage, http

Every module here is ordinary Python running in the same sandbox as the
plugin. What reaches outside the sandbox goes through one host call, which
the keyboard checks against the permissions in the manifest and the ones the
user granted.
"""

import _wm

api_version = 5
plugin_id = _wm.plugin_id
plugin_version = _wm.plugin_version


def log(*values):
    """Write a line to the plugin's log, as print() does."""
    _wm.log(" ".join(str(v) for v in values))


def timestamp(year, month, day, hour=12, minute=0, second=0):
    """That moment in the phone's time zone, in seconds since 1970."""
    return _wm.timestamp(year, month, day, hour, minute, second)


from wm._storage import Storage as _Storage

storage = _Storage()

from wm.i18n import _, gettext, ngettext
