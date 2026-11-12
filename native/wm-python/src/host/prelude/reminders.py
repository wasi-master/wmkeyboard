"""Notifications at a set time, even after the keyboard closes. Needs
`reminders`.

    reminders.set("tea", time.time() + 240, "Tea is ready")
"""

import _wm


def set(id, seconds, text):
    _wm.reminders_set(str(id), seconds, str(text))


def cancel(id):
    return _wm.reminders_cancel(str(id))


def list():
    return _wm.reminders_list()
