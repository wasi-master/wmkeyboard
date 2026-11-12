# Text generator: placeholder words, sentences and paragraphs to paste into a
# field you are testing.
#
# An API 5 plugin. It imports what it uses from the wm package, keeps its
# state in plugin.state, and gives each widget a function to call instead of
# an id to match in on_event. Nothing here needs a permission: the result
# reaches your text only through the Insert button under the output.

import random
from wm import plugin, ui

WORDS = (
    "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor "
    "incididunt ut labore et dolore magna aliqua enim ad minim veniam quis nostrud "
    "exercitation ullamco laboris nisi aliquip ex ea commodo consequat duis aute irure "
    "in reprehenderit voluptate velit esse cillum fugiat nulla pariatur excepteur sint "
    "occaecat cupidatat non proident sunt culpa qui officia deserunt mollit anim id est"
).split()

KINDS = ["Words", "Sentences", "Paragraphs"]

state = plugin.state(kind="Sentences", count=3, classic=True, text="")


def sentence():
    words = [random.choice(WORDS) for _ in range(random.randint(6, 14))]
    words[0] = words[0].capitalize()
    # A comma in about half the sentences, so the text reads less like a list.
    if len(words) > 8 and random.random() < 0.5:
        at = random.randint(3, len(words) - 4)
        words[at] += ","
    return " ".join(words) + "."


def paragraph():
    return " ".join(sentence() for _ in range(random.randint(3, 6)))


def generate():
    if state.kind == "Words":
        text = " ".join(random.choice(WORDS) for _ in range(state.count))
    elif state.kind == "Sentences":
        text = " ".join(sentence() for _ in range(state.count))
    else:
        text = "\n\n".join(paragraph() for _ in range(state.count))
    if state.classic and state.kind != "Words":
        text = "Lorem ipsum dolor sit amet, " + text[0].lower() + text[1:]
    state.text = text


def set_kind(kind):
    state.kind = kind
    generate()


def set_count(n):
    state.count = int(n)
    generate()


def set_classic(on):
    state.classic = on
    generate()


@plugin.on_open
def opened():
    if not state.text:
        generate()


@plugin.command("New text", icon="refresh")
def again():
    generate()


def render():
    with ui.column():
        ui.select(KINDS, value=state.kind, on_change=set_kind, style="segmented")
        ui.stepper(state.count, min=1, max=20, on_change=set_count, label="How many")
        ui.toggle("Start with “Lorem ipsum”", state.classic, on_change=set_classic)
        ui.output(state.text)
