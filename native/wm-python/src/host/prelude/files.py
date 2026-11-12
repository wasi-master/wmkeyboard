"""Files the user picks. Needs `files`. A plugin never sees a path or a
folder, only what the user chose in the system picker.

    files.pick(kind="image", then=got)   # then hears {"name", "mime", "data": bytes}
    files.save("notes.txt", text)         # the user picks where
"""

from wm import _core


def _decoded(value):
    if isinstance(value, dict) and isinstance(value.get("data"), str):
        import base64
        value["data"] = base64.b64decode(value["data"])
    return value


def pick(kind="any", then=None, on_error=None):
    """kind: "any", "image", "text", "audio", "video" or a MIME type."""
    return _core.defer("files.pick", {"kind": str(kind)}, then, on_error, _decoded)


def save(name, data, mime=None, then=None, on_error=None):
    if isinstance(data, str):
        data = data.encode("utf-8")
        mime = mime or "text/plain"
    return _core.defer("files.save", {"name": str(name), "data": data, "mime": mime or "application/octet-stream"},
                       then, on_error)
