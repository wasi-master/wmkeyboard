# Todo List: storage, due dates and reminders.
#
# Shows both capabilities a plugin can ask for, and the date widget.
#
# `storage` is a small dict of JSON values that belongs to this plugin alone,
# lives on this device only, and is deleted when the plugin is uninstalled.
# `reminders` asks the app to show a notification at a time you pick. The
# app shows it, not this script: nothing here runs in the background, and the
# notification always carries the plugin's name. Both are declared in
# plugin.json, so the user sees them before installing.
#
# Note what is *not* here: no network, no reading your messages. This list
# cannot leave the phone, because there is nothing in the sandbox to send it
# with.

import datetime
import time

from wm import plugin, reminders, storage, ui

# items: dicts with id, text, done, due (seconds, absent when none) and remind.
# The draft is the item being typed; selected is the id the Details page shows.
state = plugin.state(items=[], next_id=1, draft="", draft_due=None, draft_remind=True, selected=None, notice="")

# Reminders need their permission; the list still works without it.
has_reminders = "reminders" in plugin.permissions

# ---------------------------------------------------------------- storage ---


def whole(value):
    """A stored number as a whole number of seconds or an id, or None."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return int(value)


def load():
    """Reads the saved list: one key, `items`, a list of dicts. Items saved by
    the first version of this plugin have no id, so they get one here."""
    saved = storage.get("items")
    if not isinstance(saved, list):
        return
    items = []
    for entry in saved:
        if not isinstance(entry, dict):
            continue
        item = {
            "id": whole(entry.get("id")) or 0,
            "text": str(entry.get("text", "")),
            "done": bool(entry.get("done")),
            "remind": bool(entry.get("remind")),
        }
        due = whole(entry.get("due"))
        if due:
            item["due"] = due
        items.append(item)
    next_id = max([item["id"] for item in items] + [0]) + 1
    for item in items:
        if item["id"] <= 0:
            item["id"] = next_id
            next_id += 1
    state.update(items=items, next_id=next_id)


def save():
    try:
        storage["items"] = state.items
    except ValueError as error:
        # Over quota, most likely. Say so rather than silently losing an item.
        state.notice = "Could not save: " + str(error)
        print("could not save:", error)


@plugin.on_open
def opened():
    load()


# -------------------------------------------------------------- reminders ---


def reminder_id(item):
    """One reminder per item, named after it, so setting it again replaces it."""
    return "item:" + str(item["id"])


def sync(item):
    """Makes the app's reminder for an item match the item: set while it is
    due later, not done, and asked for; cancelled otherwise."""
    if not has_reminders:
        return
    due = item.get("due")
    if item["remind"] and due and not item["done"] and due > time.time():
        try:
            reminders.set(reminder_id(item), due, item["text"])
        except ValueError as error:
            state.notice = "No reminder: " + str(error)
    else:
        reminders.cancel(reminder_id(item))


# ------------------------------------------------------------------ dates ---


def when(due):
    """"today 09:00", "tomorrow 18:30", or "Fri 24 Oct 09:00"."""
    moment = datetime.datetime.fromtimestamp(due)
    today = datetime.date.today()
    clock = moment.strftime("%H:%M")
    if moment.date() == today:
        return "today " + clock
    if moment.date() == today + datetime.timedelta(days=1):
        return "tomorrow " + clock
    return moment.strftime("%a %d %b") + " " + clock


def overdue(item):
    due = item.get("due")
    return due is not None and not item["done"] and due < time.time()


def status(item):
    """The line under an item's text, or None when it has no due date."""
    due = item.get("due")
    if not due:
        return None
    line = ("overdue, was due " if overdue(item) else "due ") + when(due)
    # Only while the app will actually show one: not for a time already past.
    if item["remind"] and not item["done"] and due > time.time() and has_reminders:
        line += " · reminder"
    return line


# ------------------------------------------------------------------ order ---


def order_key(item):
    due = item.get("due")
    return (item["done"], due is None, due or 0, item["id"])


def ordered():
    """Open items first, soonest due first and undated last; finished items
    after them. The saved list keeps the order things were added in."""
    return sorted(state.items, key=order_key)


def find(item_id):
    for item in state.items:
        if item["id"] == item_id:
            return item
    return None


def current():
    """The item the Details page shows: the one picked, or the first in order."""
    item = find(state.selected) if state.selected is not None else None
    if item:
        return item
    shown = ordered()
    return shown[0] if shown else None


def position(item, in_order):
    for index, other in enumerate(in_order):
        if other["id"] == item["id"]:
            return index
    return 0


def step(by):
    """Moves the Details page by one item, round the end."""
    in_order = ordered()
    if not in_order:
        return
    at = (position(current(), in_order) + by) % len(in_order)
    state.selected = in_order[at]["id"]


# ---------------------------------------------------------------- changes ---
#
# Each item is a dict inside state.items. Changing one in place and saving
# is enough: the panel draws again after every callback.


def changed(item):
    state.notice = ""
    sync(item)
    save()


def set_done(item, done):
    item["done"] = done
    changed(item)


def set_due(item, value):
    if value is None:
        item.pop("due", None)
    else:
        item["due"] = int(value)


def delete(item):
    if has_reminders:
        reminders.cancel(reminder_id(item))
    state.items = [other for other in state.items if other["id"] != item["id"]]
    if state.selected == item["id"]:
        state.selected = None
    save()


def add():
    if state.draft.strip() == "":
        return
    item = {
        "id": state.next_id,
        "text": state.draft.strip(),
        "done": False,
        "remind": state.draft_due is not None and state.draft_remind,
    }
    set_due(item, state.draft_due)
    state.next_id += 1
    state.items = state.items + [item]
    sync(item)
    # Setting the value the box shows empties it.
    state.update(draft="", draft_due=None, notice="")
    save()


def clear_done():
    state.items = [item for item in state.items if not item["done"]]
    save()


def set_draft(text):
    state.draft = text


def set_draft_due(value):
    state.draft_due = int(value) if value is not None else None


def set_draft_remind(on):
    state.draft_remind = on


def on_current(fn):
    """A callback that applies [fn] to the item the Details page shows."""

    def run(*value):
        item = current()
        if item:
            fn(item, *value)

    return run


def set_current_due(item, value):
    set_due(item, value)
    changed(item)


def set_current_remind(item, on):
    item["remind"] = on
    changed(item)


# ----------------------------------------------------------------- render ---


def list_page():
    rows = [
        ui.row(
            ui.input(value=state.draft, placeholder="Something to do", on_change=set_draft, on_submit=lambda text: add()),
            ui.button("Add", on_click=add, primary=True),
            weights=[3, 1],
        ),
        ui.date(label="Due (optional)", value=state.draft_due, time=True, on_change=set_draft_due),
    ]
    if state.draft_due is not None and has_reminders:
        rows.append(ui.toggle("Remind me when it is due", value=state.draft_remind, on_change=set_draft_remind))
    rows.append(ui.divider())
    if state.notice:
        rows.append(ui.caption(state.notice))
    if not state.items:
        rows.append(ui.caption("Nothing on the list."))
        return ui.page("List", *rows)

    remaining = 0
    late = 0
    for item in ordered():
        if not item["done"]:
            remaining += 1
        if overdue(item):
            late += 1
        line = status(item)
        rows.append(ui.toggle(
            item["text"] + "\n" + line if line else item["text"],
            value=item["done"],
            on_change=lambda done, item=item: set_done(item, done),
            key="item" + str(item["id"]),
        ))
    summary = str(remaining) + " of " + str(len(state.items)) + " left"
    if late > 0:
        summary += ", " + str(late) + " overdue"
    rows.append(ui.caption(summary))
    rows.append(ui.button("Clear finished", on_click=clear_done))
    return ui.page("List", *rows)


def details_page():
    item = current()
    if not item:
        return ui.page("Details", ui.caption("Add something on the List page first."))
    in_order = ordered()
    at = position(item, in_order) + 1
    rows = [
        ui.row(
            ui.button("‹ Previous", on_click=lambda: step(-1), enabled=len(in_order) > 1),
            ui.caption(str(at) + " of " + str(len(in_order))),
            ui.button("Next ›", on_click=lambda: step(1), enabled=len(in_order) > 1),
        ),
        ui.title(item["text"]),
    ]
    if state.notice:
        rows.append(ui.caption(state.notice))
    rows.append(ui.date(label="Due", value=item.get("due"), time=True, on_change=on_current(set_current_due)))
    if has_reminders:
        if item.get("due"):
            rows.append(ui.toggle("Remind me when it is due", value=item["remind"], on_change=on_current(set_current_remind)))
        else:
            rows.append(ui.caption("Set a due date to get a reminder."))
    rows.append(ui.toggle("Done", value=item["done"], on_change=on_current(set_done)))
    rows.append(ui.button("Delete", on_click=on_current(delete)))
    return ui.page("Details", *rows)


def render():
    ui.tabs(list_page(), details_page())
