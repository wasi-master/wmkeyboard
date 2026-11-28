# Text Tools: change case, encode, and count.
#
# Everything here works on text the user typed or pasted into the box at the
# top. That is how any plugin gets text: there is no API for reading what
# you are writing, so the user hands it over on purpose, and can see exactly
# what they handed over.
#
# An API 5 plugin: the standard library's base64 and urllib.parse do the
# encoding, plugin.state keeps the text and the result, and each button
# names the function it runs.

import base64
import binascii
import urllib.parse

from wm import plugin, ui

state = plugin.state(text="", output="")


def text_of(data):
    """Bytes back to text; a byte that is not part of valid UTF-8 comes out as �."""
    return bytes(data).decode("utf-8", errors="replace")


def base64_decode(s):
    data = "".join(s.split())
    # Base64 copied without its trailing "=" still decodes.
    data += "=" * (-len(data) % 4)
    try:
        return text_of(base64.b64decode(data))
    except (binascii.Error, ValueError) as e:
        return "Not Base64: " + str(e)


def hex_decode(s):
    try:
        return text_of(bytes.fromhex("".join(s.split())))
    except ValueError as e:
        return "Not hex: " + str(e)


def title_case(s):
    """The first letter of each word up, the rest of the word down."""
    out = []
    in_word = False
    for c in s:
        if in_word and (c.isalnum() or c == "'"):
            out.append(c.lower())
        elif c.isalpha():
            out.append(c.upper())
            in_word = True
        else:
            out.append(c)
            in_word = False
    return "".join(out)


def statistics(s):
    words = len(s.split())
    lines = s.count("\n") + 1 if s else 0
    letters = sum(1 for c in s if c.isalpha())
    return "%d characters\n%d letters\n%d words\n%d lines" % (len(s), letters, words, lines)


ACTIONS = {
    "upper": lambda s: s.upper(),
    "lower": lambda s: s.lower(),
    "title": title_case,
    "reverse": lambda s: s[::-1],
    "b64_encode": lambda s: base64.b64encode(s.encode("utf-8")).decode("ascii"),
    "b64_decode": base64_decode,
    "hex_encode": lambda s: s.encode("utf-8").hex(),
    "hex_decode": hex_decode,
    "url_encode": lambda s: urllib.parse.quote(s, safe=""),
    "url_decode": urllib.parse.unquote,
    "stats": statistics,
}


def set_text(text):
    state.text = text


def run(name):
    """The button's function: the action [name] on the text."""
    return lambda: state.update(output=ACTIONS[name](state.text))


def clear():
    # Setting the value the box shows empties it.
    state.update(text="", output="")


def render():
    with ui.column():
        ui.input(label="Text", value=state.text, placeholder="Type or paste, then pick an action", on_change=set_text, multiline=True)
        ui.tabs(
            ui.page(
                "Case",
                ui.row(ui.button("UPPER", on_click=run("upper")), ui.button("lower", on_click=run("lower"))),
                ui.row(ui.button("Title Case", on_click=run("title")), ui.button("esreveR", on_click=run("reverse"))),
            ),
            ui.page(
                "Encode",
                ui.row(ui.button("Base64", on_click=run("b64_encode")), ui.button("un-Base64", on_click=run("b64_decode"))),
                ui.row(ui.button("Hex", on_click=run("hex_encode")), ui.button("un-Hex", on_click=run("hex_decode"))),
                ui.row(ui.button("URL", on_click=run("url_encode")), ui.button("un-URL", on_click=run("url_decode"))),
            ),
            ui.page(
                "Count",
                ui.button("Count it", on_click=run("stats"), primary=True),
            ),
        )
        ui.divider()
        if state.output != "":
            ui.output(state.output, id="out", mono=True)
        else:
            ui.caption("Results appear here.")
        ui.button("Clear", on_click=clear)
