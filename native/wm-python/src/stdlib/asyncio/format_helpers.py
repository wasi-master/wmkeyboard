"""asyncio.format_helpers: naming a callback, for loop debugging output."""


def _format_callback_source(func, args, *, debug=False):
    return getattr(func, "__qualname__", repr(func))


def extract_stack(f=None, limit=None):
    import traceback
    return traceback.StackSummary()
