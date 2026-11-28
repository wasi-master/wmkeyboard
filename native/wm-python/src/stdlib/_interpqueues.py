"""_interpqueues: CPython 3.13's queues between subinterpreters. A plugin
has one interpreter (see _interpreters), so there is nothing to send to:
making a queue raises QueueError."""

_CANNOT = "plugins run in one interpreter: there is no other to queue for"


class QueueError(RuntimeError):
    pass


class QueueNotFoundError(QueueError):
    pass


class QueueEmpty(QueueError):
    pass


class QueueFull(QueueError):
    pass


def create(maxsize, *args, **kwargs):
    raise QueueError(_CANNOT)


def list_all():
    return []


def _missing(qid, *args, **kwargs):
    raise QueueNotFoundError(f"queue {qid} not found")


destroy = put = get = bind = release = get_maxsize = get_queue_defaults = is_full = get_count = _missing


def _register_heldtype(cls):
    pass
