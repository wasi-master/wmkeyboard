"""The keyboard under the panel. Needs no permission: what it does is on
screen for the user, and undone when the panel closes.

    keyboard.use_language("ja")    # Japanese keys while the drill is open
    keyboard.restore()             # the user's own layout again
"""

from wm import _core


def use_language(language):
    """Show the keys of [language], a tag such as "ja", "ko" or "pt-BR", while
    the plugin's panel is open: the user's own layout for that language when
    they added one, else the keyboard's default for it. Returns the layout's
    name, or None when the keyboard has no layout for that language. The
    user's layout comes back by itself when the panel closes."""
    return _core.call("keyboard.use", {"language": str(language)})


def restore():
    """Back to the layout the user had before use_language(). Nothing happens
    when the plugin changed nothing."""
    _core.call("keyboard.restore", {})
