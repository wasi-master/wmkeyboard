"""The keyboard's own scanners. Needs `scan`; the camera opens over the
keyboard and the plugin gets only the result.

    scan.qr(then=found)         # found hears the code's text
    scan.text(then=read)        # recognised text
"""

from wm import _core


def qr(then, on_error=None):
    return _core.defer("scan.qr", {}, then, on_error)


barcode = qr


def text(then, on_error=None):
    return _core.defer("scan.text", {}, then, on_error)
