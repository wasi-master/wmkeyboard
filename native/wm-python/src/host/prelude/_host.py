"""What the keyboard calls: drawing, events, timers, selection actions and
providers. Plugins never import this."""

import json
import _wm
from wm import _core, plugin, ui

_main = {}
# Ops a handler may only call while answering the user's own action.
_user_event = [False]


def bind(render=None, on_event=None, transform=None):
    """The plugin's own top-level functions, after its source ran."""
    _main["render"] = render
    _main["on_event"] = on_event
    _main["transform"] = transform


def _meta():
    timers = []
    for name, t in plugin._timers.items():
        timers.append({"id": name, "seconds": t["seconds"], "repeat": t["repeat"]})
    commands = [{"id": k, "label": c["label"], "icon": c["icon"]} for k, c in plugin._commands.items()]
    page_title = plugin._pages[-1][1] if plugin._pages else None
    return {
        "timers": timers,
        "commands": commands,
        "canBack": len(plugin._pages) > 0,
        "title": page_title or plugin._title[0],
        "actions": sorted(plugin._actions.keys()),
        "keywords": sorted(plugin._keywords.keys()),
        "providers": sorted(plugin._providers.keys()),
        "pending": len(_core._pending),
    }


def render():
    """Draw the page on top. Returns (tree, meta)."""
    fn = plugin._pages[-1][0] if plugin._pages else _main.get("render")
    if fn is None:
        return None, _meta()
    ui._begin()
    try:
        returned = fn()
        collected = ui._end()
    except BaseException:
        ui._end()
        raise
    tree = returned if returned is not None else collected
    if not isinstance(tree, (list, tuple)):
        tree = [tree]
    ui._finish(tree)
    return tree, _meta()


def _handler(wid, name):
    handlers = ui._handlers.get(wid)
    if handlers is None:
        return None
    return handlers.get(name)


_CALLBACK_FOR = {
    "click": "on_click",
    "change": "on_change",
    "submit": "on_submit",
    "select": "on_select",
    "tap": "on_tap",
    "drag": "on_drag",
    "hold": "on_hold",
    "press": "on_press",
}


def _value_of(e):
    kind = e.get("type")
    if kind in ("tap", "hold"):
        return (e.get("x", 0), e.get("y", 0))
    if kind == "drag":
        return {"x": e.get("x", 0), "y": e.get("y", 0), "dx": e.get("dx", 0), "dy": e.get("dy", 0),
                "phase": e.get("phase", "move")}
    if kind == "select" and "index" in e and "value" not in e:
        return e.get("index")
    return e.get("value")


def dispatch(e):
    """Route one event. Returns True when something handled it."""
    kind = e.get("type", "")
    handled = _route(kind, e)
    if not handled and _main.get("on_event") is not None:
        _main["on_event"](e)
        handled = True
    plugin._save_states()
    return handled


def _route(kind, e):
    if kind == "result":
        return _core.resolve(e.get("token"), e.get("value"))
    if kind == "stream":
        return _core.piece(e.get("token"), e.get("value"))
    if kind == "timer":
        name = e.get("id")
        t = plugin._timers.get(name)
        if t is None:
            return False
        if not t["repeat"]:
            plugin._timers.pop(name, None)
        t["fn"]()
        return True
    if kind == "open":
        for fn in plugin._open_hooks:
            fn()
        return True
    if kind == "close":
        for fn in plugin._close_hooks:
            fn()
        return True
    if kind == "back":
        return plugin.back()
    if kind == "command":
        c = plugin._commands.get(e.get("id"))
        if c is None:
            return False
        c["fn"]()
        return True
    if kind == "setting":
        if e.get("key") in _wm.settings:
            _wm.settings[e.get("key")] = e.get("value")
        for fn in plugin._setting_hooks.get(e.get("key"), []):
            _core.call_back(fn, e.get("value"))
        return True
    if kind == "keyword":
        fn = plugin._keywords.get(e.get("id"))
        if fn is None:
            return False
        _core.call_back(fn, e.get("value", ""))
        return True
    if kind == "item_action":
        acts = _handler(e.get("id"), "actions")
        if not acts:
            return False
        fn = acts.get(e.get("value"))
        if fn is None:
            return False
        fn()
        return True
    name = _CALLBACK_FOR.get(kind)
    if name is None:
        return False
    wid = e.get("id")
    fn = _handler(wid, name)
    if fn is None and kind == "change" and wid in ui._shown_values:
        # Typed into a box with no on_change: remember the text so the next
        # draw does not put the old value back.
        ui._shown_values[wid] = str(e.get("value", ""))
        return False
    if fn is None:
        return False
    value = _value_of(e)
    if kind in ("change", "submit") and wid in ui._shown_values:
        ui._shown_values[wid] = str(value)
    if kind == "click":
        _core.call_back(fn)
    elif kind in ("tap", "hold"):
        _core.call_back(fn, value[0], value[1])
    else:
        _core.call_back(fn, value)
    return True


# The action id the keyboard calls a provider by. No manifest action id can
# hold a colon, so the two never meet.
_PROVIDER = "provider:"


def provide(key, request):
    """One provider call: [request] is the JSON the keyboard sent, and the
    reply goes back as JSON, wrapped so that None and a bare string survive."""
    fn = plugin._providers.get(key)
    if fn is None:
        raise NotImplementedError("no provider " + key)
    reply = _core.call_back(fn, json.loads(request) if request else {})
    return json.dumps({"value": reply})


def transform(text, action_id):
    """A selection action: the @plugin.action function for [action_id],
    else the plugin's own transform(). A provider call comes the same way."""
    if action_id.startswith(_PROVIDER):
        return provide(action_id[len(_PROVIDER):], text)
    fn = plugin._actions.get(action_id)
    if fn is not None:
        return _core.call_back(fn, text)
    fn = _main.get("transform")
    if fn is None:
        raise NotImplementedError("no action")
    if _wm.arity(fn) >= 2 or _wm.arity(fn) < 0:
        return fn(text, action_id)
    return fn(text)
