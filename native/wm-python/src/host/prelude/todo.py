"""The keyboard's own To-do list, the one its To-do tool shows. Needs the
risky `todo` permission.

    for task in todo.list(done=False):
        print(task["text"], len(task["steps"]))
    todo.add("Buy milk", due=time.time() + 3600)
"""

from wm import _core

# Leaves a task's date as it is in update().
_KEEP = object()


def list(done=True):
    """The tasks in the list's order, as dicts with id, text, done, due
    (seconds since 1970, or None), all_day and steps: the task's steps, the
    same dicts one level down. done=False leaves out finished tasks."""
    return _core.call("todo.list", {"done": bool(done)})


def add(text, parent=None, due=None, all_day=False):
    """Adds a task, or a step of the task parent, and returns it as a dict."""
    return _core.call("todo.add", {"text": str(text), "parent": parent, "due": due, "all_day": bool(all_day)})


def update(id, text=None, done=None, due=_KEEP, all_day=False):
    """Changes a task: its text, its tick, or its date (due=None clears it).
    Returns the task as a dict, or None when there is no such task."""
    args = {"id": id}
    if text is not None:
        args["text"] = str(text)
    if done is not None:
        args["done"] = bool(done)
    if due is not _KEEP:
        args["due"] = due
        args["all_day"] = bool(all_day)
    return _core.call("todo.update", args)


def remove(id):
    """Deletes a task and its steps. True when there was one."""
    return _core.call("todo.remove", {"id": id})
