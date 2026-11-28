# Selection actions: buttons on the bar over text you select.
#
# plugin.json lists them under "actions" and asks for the "selection"
# permission. Each @plugin.action function gets the selected text, and the
# string it returns takes the selection's place; returning None leaves the
# text as it was. Undo on the bar takes the change back.
#
# The panel picks the quotes that Wrap in quotes uses. The choice is kept with
# plugin.state(persist=True), so an action reads it as well as the panel.

from wm import plugin, ui

STYLES = [
    {"id": "curly", "open": "“", "close": "”", "label": "“Curly”"},
    {"id": "straight", "open": '"', "close": '"', "label": '"Straight"'},
    {"id": "guillemets", "open": "«", "close": "»", "label": "«Guillemets»"},
    {"id": "corner", "open": "「", "close": "」", "label": "「Corner」"},
]

UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
LOWER = "abcdefghijklmnopqrstuvwxyz"

state = plugin.state(quotes="curly", persist=True)


def current_style():
    for style in STYLES:
        if style["id"] == state.quotes:
            return style
    return STYLES[0]


def rot13(text):
    """Each Latin letter moves 13 places along the alphabet, so the same
    action undoes itself."""
    out = []
    for c in text:
        if c in UPPER:
            out.append(UPPER[(UPPER.index(c) + 13) % 26])
        elif c in LOWER:
            out.append(LOWER[(LOWER.index(c) + 13) % 26])
        else:
            out.append(c)
    return "".join(out)


@plugin.action("rot13")
def shift_letters(text):
    return rot13(text)


@plugin.action("quote")
def wrap_in_quotes(text):
    style = current_style()
    return style["open"] + text + style["close"]


def pick(style_id):
    state.quotes = style_id


def render():
    chosen = current_style()
    with ui.column():
        ui.title("Quotes for Wrap in quotes")
        for style in STYLES:
            ui.button(style["label"], on_click=lambda s=style["id"]: pick(s), primary=style["id"] == chosen["id"])
        ui.caption("Select some text, then tap ROT13 or Wrap in quotes on the bar.")
