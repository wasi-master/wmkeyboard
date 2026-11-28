# UI Kitchen Sink: every widget, and what each one calls, in one place.
#
# Not useful on its own. It exists so you can see what each widget looks like
# on a real keyboard, and watch what each callback hears when you touch it:
# the Events tab lists them. Copy the bits you want.
#
# An API 5 plugin: each widget takes the function to call (on_click,
# on_change, on_submit) instead of an id to match in on_event, and the panel
# draws again after every call.

import datetime

from wm import plugin, ui

state = plugin.state(
    log=[],
    checked=False,
    disabled=False,
    typed="",
    working=False,
    picked=None,
    level=40,
    count=3,
    size="Medium",
    tags=[],
)


def note(line):
    """Keeps the last few events, newest first."""
    state.log = [line] + state.log[:7]


def clicked(name):
    return lambda: note("click " + name)


def toggle_enabled():
    state.disabled = not state.disabled
    note("click toggle_enabled")


def set_checked(value):
    state.checked = value
    note("toggle = " + str(value))


def set_typed(text):
    # Every edit of *this plugin's own box*. A plugin never sees keys typed
    # anywhere else.
    state.typed = text
    note("input = " + text)


def set_picked(value):
    # Seconds since 1970, or None when the date was cleared.
    state.picked = value
    note("date = " + (shown(value) if value is not None else "None"))


def set_level(value):
    state.level = value
    note("slider = " + str(value))


def set_count(value):
    state.count = value
    note("stepper = " + str(value))


def set_size(value):
    state.size = value
    note("select = " + value)


def set_tags(value):
    state.tags = value
    note("chips = " + ", ".join(value))


def work():
    state.working = not state.working
    note("click work")


def clear_log():
    state.log = []


def shown(seconds):
    return datetime.datetime.fromtimestamp(seconds).strftime("%A %d %B %Y, %H:%M")


def text_page():
    return ui.page(
        "Text",
        ui.title("A title"),
        ui.label("A body label. This is the default style."),
        ui.caption("A caption, for asides."),
        ui.divider(),
        ui.output("An output block.\nIt gets Insert and Copy buttons.", id="out"),
        ui.output("mono=True keeps columns lined up", id="mono", mono=True),
        ui.markdown("Markdown: **bold**, *italic* and `code`."),
    )


def controls_page():
    return ui.page(
        "Controls",
        ui.button("Primary button", on_click=clicked("primary"), primary=True),
        ui.button("Secondary button", on_click=clicked("secondary")),
        ui.button("Enabled toggles me", on_click=clicked("maybe"), enabled=not state.disabled),
        ui.button("Enable it" if state.disabled else "Disable it", on_click=toggle_enabled),
        ui.divider(),
        ui.toggle("A toggle", value=state.checked, on_change=set_checked),
        ui.caption("The toggle is on." if state.checked else "The toggle is off."),
        ui.slider(state.level, min=0, max=100, step=10, on_change=set_level, label="A slider"),
        ui.stepper(state.count, min=1, max=9, on_change=set_count, label="A stepper"),
        ui.select(["Small", "Medium", "Large"], value=state.size, on_change=set_size, style="segmented"),
        ui.chips(["red", "green", "blue"], value=state.tags, on_change=set_tags, multi=True),
    )


def input_page():
    return ui.page(
        "Input",
        ui.input(label="A text box", value=state.typed, placeholder="Type or paste here", on_change=set_typed),
        ui.label("You typed: " + (state.typed if state.typed != "" else "(nothing yet)")),
        ui.caption("The keyboard owns this box. Tap it, then use the keys; Paste puts the clipboard in it."),
    )


def date_page():
    return ui.page(
        "Date",
        ui.date(label="A date and time", value=state.picked, time=True, on_change=set_picked),
        ui.label("Picked: " + shown(state.picked) if state.picked is not None else "Nothing picked yet."),
        ui.caption("The keyboard draws the buttons. You get each new value in on_change and draw whatever you keep. Leave out time for a date alone."),
    )


def layout_page():
    return ui.page(
        "Layout",
        ui.label("A row splits the width evenly:"),
        ui.row(
            ui.button("One", on_click=clicked("one")),
            ui.button("Two", on_click=clicked("two")),
            ui.button("Three", on_click=clicked("three")),
        ),
        ui.spacer(12),
        ui.label("Above is a 12pt spacer. Below is a divider."),
        ui.divider(),
        ui.button("Stop pretending" if state.working else "Pretend to work", on_click=work),
        ui.progress() if state.working else ui.caption("(no progress bar)"),
    )


def events_page():
    if state.log:
        shown_log = ui.output("\n".join(state.log), id="log", mono=True, insertable=False)
    else:
        shown_log = ui.caption("Touch something and what it called shows up here.")
    return ui.page("Events", shown_log, ui.button("Clear log", on_click=clear_log))


def render():
    ui.tabs(
        text_page(),
        controls_page(),
        input_page(),
        date_page(),
        layout_page(),
        events_page(),
        on_change=lambda index: note("tab #" + str(index)),
    )
