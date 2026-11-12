"""The plugin itself: its state, its lifecycle, and the decorators that
tell the keyboard what it offers.

    state = plugin.state(count=0, persist=True)

    @plugin.on_open
    def opened():
        state.count += 1

    @plugin.every(minutes=5)
    def tick():
        ...

    @plugin.action("shout", name="Shout")
    def shout(text):
        return text.upper()
"""

import json
import _wm
from wm import _core

id = _wm.plugin_id
version = _wm.plugin_version
api_version = 5
permissions = tuple(_wm.permissions)
_context = _wm.context
locale = _context.get("locale", "en")
theme = _context.get("theme", "light")
surface = _context.get("surface", "panel")
dark = theme == "dark"

_open_hooks = []
_close_hooks = []
_timers = {}
_actions = {}
_keywords = {}
_commands = {}
_setting_hooks = {}
_states = []
_pages = []
_title = [None]
_STATE_PREFIX = "__state__:"


# MARK: - state

class State:
    """Named values that live as long as the plugin runs, read and written as
    attributes. With persist=True they are saved to storage after every
    event and come back the next time the plugin opens."""

    def __init__(self, fields, persist, key):
        object.__setattr__(self, "_names", list(fields.keys()))
        object.__setattr__(self, "_persist", persist)
        object.__setattr__(self, "_key", _STATE_PREFIX + key)
        object.__setattr__(self, "_saved", None)
        for name, value in fields.items():
            object.__setattr__(self, name, value)
        if persist:
            if "storage" not in permissions:
                raise PermissionError("plugin.state(persist=True) needs the 'storage' permission")
            raw = _wm.storage_get(self._key)
            if raw is not None:
                try:
                    saved = json.loads(raw)
                except ValueError:
                    saved = {}
                if isinstance(saved, dict):
                    for name, value in saved.items():
                        if name in self._names:
                            object.__setattr__(self, name, value)
            object.__setattr__(self, "_saved", self._dump())

    def __setattr__(self, name, value):
        if name.startswith("_"):
            raise AttributeError(f"state fields can't start with '_': {name}")
        if name not in self._names:
            self._names.append(name)
        object.__setattr__(self, name, value)

    def __getitem__(self, name):
        return getattr(self, name)

    def __setitem__(self, name, value):
        setattr(self, name, value)

    def __contains__(self, name):
        return name in self._names

    def update(self, **fields):
        for name, value in fields.items():
            setattr(self, name, value)

    def as_dict(self):
        return {name: getattr(self, name) for name in self._names}

    def _dump(self):
        return json.dumps(self.as_dict())

    def save(self):
        """Save now rather than after the event."""
        if not self._persist:
            return
        text = self._dump()
        if text != self._saved:
            _wm.storage_set(self._key, text)
            object.__setattr__(self, "_saved", text)

    def __repr__(self):
        return f"plugin.state({self.as_dict()})"


def state(persist=False, key="state", **fields):
    """A State holding [fields] with their starting values."""
    s = State(fields, persist, key)
    _states.append(s)
    return s


def _save_states():
    for s in _states:
        s.save()


# MARK: - settings

class _Settings:
    """The values the user set on the plugin's settings page, by key, as the
    manifest's `settings` list declares them. Read-only."""

    def __getattr__(self, name):
        values = _wm.settings
        if name in values:
            return values[name]
        raise AttributeError(f"the manifest declares no setting '{name}'")

    def __getitem__(self, name):
        return self.__getattr__(name)

    def get(self, name, default=None):
        return _wm.settings.get(name, default)

    def __contains__(self, name):
        return name in _wm.settings

    def __repr__(self):
        return f"plugin.settings({_wm.settings})"


settings = _Settings()


# MARK: - lifecycle

def on_open(fn):
    """Called each time the plugin's panel opens, before it is drawn."""
    _open_hooks.append(fn)
    return fn


def on_close(fn):
    """Called when the panel closes. Keep it short."""
    _close_hooks.append(fn)
    return fn


def every(seconds=0, minutes=0, hours=0, name=None):
    """Call the function again and again while the panel is open, at least
    `seconds` apart (the shortest is one second)."""
    period = seconds + minutes * 60 + hours * 3600
    if period < 1:
        raise ValueError("plugin.every() needs a period of at least one second")

    def wrap(fn):
        _timers[name or fn.__name__] = {"fn": fn, "seconds": period, "repeat": True}
        return fn

    return wrap


def after(seconds, fn, name=None):
    """Call [fn] once, [seconds] from now, if the panel is still open."""
    if seconds < 0:
        raise ValueError("plugin.after() can't go back in time")
    key = name or _core.token("after:")
    _timers[key] = {"fn": fn, "seconds": max(seconds, 0.05), "repeat": False}
    return key


def cancel(name):
    """Stop a timer started with every() or after()."""
    return _timers.pop(name, None) is not None


# MARK: - what the plugin offers

def action(id, name=None, icon=None):
    """A selection action: [fn] receives the selected text and returns what
    replaces it (None leaves it). The manifest lists the same id."""
    def wrap(fn):
        _actions[id] = fn
        return fn
    return wrap


def keyword(word):
    """Called with what the user typed after `@word` when they tap the
    plugin's chip. The manifest's `tool.keyword` names the same word."""
    def wrap(fn):
        _keywords[word] = fn
        return fn
    return wrap


def command(label, icon=None, id=None):
    """A button the keyboard draws in the panel's header."""
    def wrap(fn):
        _commands[id or fn.__name__] = {"label": str(label), "icon": icon, "fn": fn}
        return fn
    return wrap


def setting_changed(key):
    """Called with the new value when the user changes setting [key]."""
    def wrap(fn):
        _setting_hooks.setdefault(key, []).append(fn)
        return fn
    return wrap


# MARK: - pages

def push(render, title=None):
    """Show another page drawn by [render]. The keyboard's back button, or
    plugin.back(), returns to the one before."""
    _pages.append((render, title))


def back():
    """Close the page on top. False when there was none."""
    if not _pages:
        return False
    _pages.pop()
    return True


def set_title(text):
    """The text in the panel's header, instead of the plugin's name."""
    _title[0] = None if text is None else str(text)


def close():
    """Close the plugin's panel."""
    _core.call("plugin.close", {})


def refresh():
    """Draw the panel again once this call finishes. Events and timers
    already do; use it after an answer arrives some other way."""
    _core.call("plugin.refresh", {})
