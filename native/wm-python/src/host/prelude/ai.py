"""Ask the AI model the user chose in the keyboard's settings (on the phone
or a provider they signed in to). Needs the `ai` permission.

    reply = ai.ask("Write a haiku about rain")
    ai.ask("Summarise: " + text, then=show)        # without waiting
    ai.stream("Tell a story", on_chunk=add, then=done)
"""

from wm import _core


def ask(prompt, system=None, max_tokens=None, then=None, on_error=None):
    args = {"prompt": str(prompt), "system": system, "maxTokens": max_tokens}
    if then is not None:
        return _core.defer("ai.ask", args, then, on_error, _text)
    return _text(_core.call("ai.ask", args))


def stream(prompt, on_chunk, system=None, max_tokens=None, then=None, on_error=None):
    """on_chunk receives each piece of the reply as it arrives; then
    receives the whole reply at the end. The panel redraws after each."""
    args = {"prompt": str(prompt), "system": system, "maxTokens": max_tokens}
    return _core.stream("ai.stream", args, on_chunk, then, on_error, _text)


def available():
    """Whether the user has a model set up."""
    return bool(_core.call("ai.available", {}))


def _text(value):
    if isinstance(value, dict):
        return value.get("text", "")
    return "" if value is None else str(value)
