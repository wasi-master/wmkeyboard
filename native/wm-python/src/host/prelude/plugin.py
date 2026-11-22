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

    @plugin.provider("search", "ddg")
    def ddg(request):
        return [{"title": "...", "url": "...", "snippet": "..."}]
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
_providers = {}
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
    """The values on the plugin's settings page, by key, as the manifest's
    `settings` list declares them. A `secret` row reads as its saved text,
    or None. Change one with `set(key, value)`; host rows are the user's."""

    def _read(self, name):
        v = _wm.settings[name]
        if isinstance(v, dict) and v.get("$secret"):
            return _core.call("secrets.get", {"name": name})
        return v

    def __getattr__(self, name):
        if name in _wm.settings:
            return self._read(name)
        raise AttributeError(f"the manifest declares no setting '{name}'")

    def __getitem__(self, name):
        return self.__getattr__(name)

    def get(self, name, default=None):
        if name not in _wm.settings:
            return default
        v = self._read(name)
        return default if v is None else v

    def set(self, key, value):
        """Change setting [key] to [value]: True or False for a switch, a
        number, one of a choice's options, or text. Saved at once and shown
        on the settings page. A host setting raises PermissionError: only
        the user types a server address."""
        key = str(key)
        if key not in _wm.settings:
            raise AttributeError(f"the manifest declares no setting '{key}'")
        if value is None:
            raise ValueError("use plugin.settings.reset(key) to put the default back")
        self._store(key, _core.call("settings.set", {"key": key, "value": value}))

    def reset(self, key):
        """Put setting [key] back to the manifest's default. A secret is
        forgotten."""
        key = str(key)
        if key not in _wm.settings:
            raise AttributeError(f"the manifest declares no setting '{key}'")
        self._store(key, _core.call("settings.set", {"key": key, "value": None}))

    def _store(self, key, v):
        # The keyboard answers the value as a fresh session would read it.
        _wm.settings[key] = v

    def __contains__(self, name):
        return name in _wm.settings

    def __repr__(self):
        shown = {}
        for k in _wm.settings:
            v = _wm.settings[k]
            shown[k] = "***" if isinstance(v, dict) and v.get("$secret") else v
        return f"plugin.settings({shown})"


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


def provider(tool, id=None):
    """Answers for one of the keyboard's provider lists: "search",
    "translate", "dictionary" and the rest. [fn] receives the request as a
    dict and returns the reply as dicts, lists, strings and numbers, in the
    shape the tool's guide gives. The manifest's `providers` lists the same
    tool and id; [id] defaults to the tool's name."""
    key = str(tool) + "/" + str(id or tool)

    def wrap(fn):
        _providers[key] = fn
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


def open_settings():
    """Open the plugin's own page in the keyboard's settings, where the user
    types its settings (a server address, a choice) and allows its risky
    permissions."""
    _core.call("plugin.open_settings", {})


def refresh():
    """Draw the panel again once this call finishes. Events and timers
    already do; use it after an answer arrives some other way."""
    _core.call("plugin.refresh", {})
