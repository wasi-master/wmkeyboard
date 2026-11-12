"""Widgets: what a plugin's panel is made of.

Each builder returns a widget the keyboard draws in its own theme. Two ways
to put a panel together, and they mix:

    def render():
        with ui.column():
            ui.label("Hello", style="title")
            ui.button("Again", on_click=again)

    def render():
        return ui.column(ui.label("Hello"), ui.button("Again", on_click=again))

Inside a `with` block of a container (column, row, card, section, grid,
scroll), every widget made there goes into it. Interactive widgets take a
callback (on_click, on_change, on_submit, on_select); the keyboard calls it
when the user acts, then draws the panel again. Colours are roles
("primary", "muted", "danger", "success", "warning"), never raw values.
"""

from wm import _core

# `ui.list` and `ui.input` are widgets; keep Python's own under other names.
_pylist = list
_stack = []
_collect = [None]
_handlers = {}
_shown_values = {}
_counts = {}
COLOR_ROLES = ("primary", "secondary", "muted", "danger", "success", "warning", "accent")


class Widget:
    """One widget. Read and change its fields as attributes or items:
    w.text, w["text"]."""

    def __init__(self, _type, **fields):
        self.type = _type
        for k, v in fields.items():
            setattr(self, k, v)
        _place(self)

    def __getitem__(self, key):
        return getattr(self, key)

    def __setitem__(self, key, value):
        setattr(self, key, value)

    def __contains__(self, key):
        return hasattr(self, key)

    def get(self, key, default=None):
        return getattr(self, key, default)

    def __repr__(self):
        shown = {k: v for k, v in vars(self).items() if k not in ("type", "children") and v is not None and not callable(v)}
        return f"ui.{self.type}({shown})"


class Container(Widget):
    """A widget that holds others. Use it in a `with` block to fill it."""

    def __init__(self, _type, _children, **fields):
        Widget.__init__(self, _type, children=_adopt(_children), **fields)

    def __enter__(self):
        _stack.append(self.children)
        return self

    def __exit__(self, kind, value, tb):
        _stack.pop()
        return False

    def add(self, *widgets):
        self.children.extend(_adopt(widgets))
        return self


def _place(w):
    if _stack:
        _stack[-1].append(w)
    elif _collect[0] is not None:
        _collect[0].append(w)


def _detach(w):
    target = _stack[-1] if _stack else _collect[0]
    if target is None:
        return
    i = len(target) - 1
    while i >= 0:
        if target[i] is w:
            target.pop(i)
            return
        i = i - 1


def _adopt(children):
    out = []
    for c in children:
        if c is None:
            continue
        if isinstance(c, (_pylist, tuple)):
            out.extend(_adopt(c))
        else:
            _detach(c)
            out.append(c)
    return out


def _role(color):
    if color is None:
        return None
    if color not in COLOR_ROLES:
        raise ValueError(f"color must be one of {', '.join(COLOR_ROLES)}, not {color!r}")
    return color


# MARK: - layout

def column(*children, gap=None, key=None, align=None):
    return Container("column", children, gap=gap, id=key, align=align)


def row(*children, weights=None, gap=None, key=None, align=None):
    return Container("row", children, weights=weights, gap=gap, id=key, align=align)


def grid(*children, columns=3, key=None):
    return Container("grid", children, columns=columns, id=key)


def card(*children, title=None, on_click=None, key=None):
    return Container("card", children, title=title, on_click=on_click, id=key)


def section(title, *children, collapsible=False, open=True, key=None):
    return Container("section", children, title=title, collapsible=collapsible, open=open, id=key)


def scroll(*children, height=None, key=None):
    return Container("scroll", children, height=height, id=key)


def spacer(height=8):
    return Widget("spacer", height=height)


def divider():
    return Widget("divider")


def tabs(*pages, on_change=None, id=None, key=None):
    """`ui.tabs(ui.page("One", ...), ui.page("Two", ...))`: on_change hears the page index."""
    items = []
    for p in pages:
        _detach(p)
        items.append(p)
    return Widget("tabs", id=id or key, pages=items, on_change=on_change)


class _Page(Container):
    def __init__(self, title, children):
        Container.__init__(self, "page", children, title=title)


def page(title, *children):
    p = _Page(title, children)
    _detach(p)
    return p


# MARK: - text

def label(text, style=None, color=None, key=None):
    return Widget("label", text=text, style=style, color=_role(color), id=key)


def text(text, style=None, color=None, key=None):
    return label(text, style, color, key)


def title(text, key=None):
    return label(text, "title", None, key)


def caption(text, key=None):
    return label(text, "caption", None, key)


def markdown(text, key=None):
    return Widget("markdown", text=text, id=key)


def output(text, id=None, mono=False, insertable=True, copyable=True, key=None):
    return Widget("output", text=text, id=id or key, mono=mono, insertable=insertable, copyable=copyable)


def empty(text, icon=None, action=None, on_click=None):
    return Widget("empty", text=text, icon=icon, action=action, on_click=on_click)


# MARK: - controls

def button(text, on_click=None, style=None, primary=False, enabled=True, icon=None, color=None, id=None, key=None):
    """`ui.button("Go", on_click=go, primary=True)`."""
    return Widget("button", id=id or key, text=text, on_click=on_click, style="primary" if primary else style,
                  enabled=enabled, icon=icon, color=_role(color))


def icon_button(icon, on_click=None, label=None, enabled=True, id=None, key=None):
    return Widget("button", id=id or key, text=label or "", icon=icon, on_click=on_click, enabled=enabled, compact=True)


def toggle(label, value=False, on_change=None, id=None, key=None):
    """`ui.toggle("Dark", value=dark, on_change=set_dark)`: on_change hears True or False."""
    return Widget("toggle", id=id or key, label=label, checked=bool(value), on_change=on_change)


def checkbox(label, value=False, on_change=None, id=None, key=None):
    return Widget("checkbox", id=id or key, label=label, checked=bool(value), on_change=on_change)


def input(label=None, value=None, on_change=None, on_submit=None, placeholder=None, multiline=False, kind="text", id=None, key=None):
    """A text box the keyboard types into. `value` sets what it holds when it
    differs from what the box last showed; on_change hears every edit and
    on_submit the text when the user presses Enter. kind: "text", "number",
    "email", "url" or "password" (shown as dots, never logged)."""
    return Widget("input", id=id or key, label=label, placeholder=placeholder, value=value, on_change=on_change,
                  on_submit=on_submit, multiline=multiline, kind=kind)


def search(placeholder=None, value=None, on_change=None, on_submit=None, id=None, key=None):
    return Widget("search", id=id or key, placeholder=placeholder, value=value, on_change=on_change, on_submit=on_submit)


def slider(value, min=0, max=100, step=1, on_change=None, label=None, id=None, key=None):
    return Widget("slider", id=id or key, value=value, min=min, max=max, step=step, on_change=on_change, label=label)


def stepper(value, min=None, max=None, step=1, on_change=None, label=None, id=None, key=None):
    return Widget("stepper", id=id or key, value=value, min=min, max=max, step=step, on_change=on_change, label=label)


def _options(options):
    out = []
    for o in options:
        if isinstance(o, (_pylist, tuple)):
            out.append({"value": str(o[0]), "label": str(o[1])})
        else:
            out.append({"value": str(o), "label": str(o)})
    return out


def select(options, value=None, on_change=None, label=None, style="dropdown", id=None, key=None):
    """One of several options: `options` is a list of values, or of
    (value, label) pairs. style="segmented" draws them side by side."""
    return Widget("select", id=id or key, options=_options(options), value=None if value is None else str(value),
                  on_change=on_change, label=label, style=style)


def chips(options, value=None, on_change=None, multi=False, id=None, key=None):
    if multi:
        chosen = [str(v) for v in (value or [])]
    else:
        chosen = [] if value is None else [str(value)]
    return Widget("chips", id=id or key, options=_options(options), value=chosen, multi=multi, on_change=on_change)


def date(id=None, label=None, value=None, time=False, step=15, on_change=None, key=None):
    return Widget("date", id=id or key, label=label, value=value, time=time, step=step, on_change=on_change)


def keys(rows, on_press=None, id=None, key=None):
    """A grid of keys drawn like the keyboard's own: `rows` is a list of rows,
    each a list of labels. on_press hears the label pressed."""
    return Widget("keys", id=id or key, rows=[[str(k) for k in r] for r in rows], on_press=on_press)


# MARK: - lists

class _Item(Widget):
    pass


def item(title, subtitle=None, icon=None, trailing=None, on_click=None, actions=None, selected=False, key=None):
    """One row of a list. `actions` maps a label to a callback, offered when
    the row is swiped or held: {"Delete": remove, "Pin": pin}."""
    acts = []
    if actions:
        pairs = actions.items() if isinstance(actions, dict) else actions
        for name, cb in pairs:
            acts.append({"label": str(name), "callback": cb})
    return _Item("item", id=key, title=title, subtitle=subtitle, icon=icon, trailing=trailing, on_click=on_click,
                 actions=acts, selected=selected)


def list(items=None, on_select=None, empty=None, key=None):
    """Rows made with ui.item. on_select hears the index of the row tapped,
    for rows without their own on_click. `empty` is the text shown when
    there are no rows."""
    items = _pylist(items or [])
    for it in items:
        _detach(it)
    return Widget("list", id=key, items=items, on_select=on_select, empty=empty)


# MARK: - pictures and numbers

def image(src, alt=None, height=None, insertable=False, on_click=None, key=None):
    """A picture: a file bundled in the plugin ("assets/logo.png"), an https
    address the plugin is allowed to reach, or bytes."""
    if isinstance(src, bytes):
        src = "data:" + _core._wm.b64(src)
    return Widget("image", id=key, src=src, alt=alt, height=height, insertable=insertable, on_click=on_click)


def icon(name, size=None, color=None):
    return Widget("icon", name=name, size=size, color=_role(color))


def qr(text, size=None):
    return Widget("qr", text=text, size=size)


def stat(label, value, caption=None, key=None):
    return Widget("stat", id=key, label=label, value=str(value), caption=caption)


def ring(value, label=None, caption=None, key=None):
    return Widget("ring", id=key, value=value, label=label, caption=caption)


def progress(value=None):
    """A bar: `value` from 0 to 1, or None for one that only says 'working'."""
    return Widget("progress", value=value)


def chart(data, kind="line", labels=None, height=None, key=None):
    """`data` is a list of numbers, or a dict of series name to list."""
    if isinstance(data, dict):
        series = [{"name": str(k), "values": [float(x) for x in v]} for k, v in data.items()]
    else:
        series = [{"name": "", "values": [float(x) for x in data]}]
    return Widget("chart", id=key, kind=kind, series=series, labels=[str(l) for l in (labels or [])], height=height)


# MARK: - drawing

def canvas(width, height, shapes=None, on_tap=None, on_drag=None, key=None):
    """A drawing `width` by `height` units, scaled to the panel. `shapes` are
    made with ui.rect, ui.circle, ui.line and ui.text_at."""
    shapes = _pylist(shapes or [])
    return Widget("canvas", id=key, width=width, height=height, shapes=shapes, on_tap=on_tap, on_drag=on_drag)


def rect(x, y, w, h, color="primary", radius=0, fill=True):
    return {"shape": "rect", "x": x, "y": y, "w": w, "h": h, "color": _role(color), "radius": radius, "fill": fill}


def circle(x, y, r, color="primary", fill=True):
    return {"shape": "circle", "x": x, "y": y, "r": r, "color": _role(color), "fill": fill}


def line(x1, y1, x2, y2, color="primary", width=1):
    return {"shape": "line", "x": x1, "y": y1, "x2": x2, "y2": y2, "color": _role(color), "width": width}


def text_at(x, y, text, color="primary", size=12):
    return {"shape": "text", "x": x, "y": y, "text": str(text), "color": _role(color), "size": size}


# MARK: - messages

def toast(text):
    """A short message the keyboard shows over the panel."""
    _core.call("ui.toast", {"text": str(text)})


def dialog(title, text=None, buttons=("OK",), on_result=None):
    """Ask the user something. on_result hears the label they tapped, or
    None when they dismissed it."""
    _core.defer("ui.dialog", {"title": str(title), "text": text, "buttons": [str(b) for b in buttons]},
                on_result, None, None)


# MARK: - render bookkeeping (called by wm._host)

_CALLBACKS = ("on_click", "on_change", "on_submit", "on_select", "on_tap", "on_drag", "on_press")


def _begin():
    _stack.clear()
    _collect[0] = []
    _handlers.clear()
    _counts.clear()


def _end():
    collected = _collect[0]
    _collect[0] = None
    _stack.clear()
    return collected


def _auto_id(w):
    base = getattr(w, "type", "w")
    for f in ("label", "text", "title", "placeholder", "name"):
        v = getattr(w, f, None)
        if isinstance(v, str) and v:
            base = base + ":" + v[:24]
            break
    n = _counts.get(base, 0)
    _counts[base] = n + 1
    return base if n == 0 else f"{base}#{n}"


_NEEDS_ID = ("input", "search", "slider", "stepper", "select", "chips", "toggle", "checkbox", "date", "tabs", "keys",
             "canvas", "list", "section")


def _finish(tree):
    """Give every interactive widget an id, note its callbacks, and push a
    changed `value` into an input box."""
    for w in tree:
        if w is None or isinstance(w, dict):
            continue
        kind = getattr(w, "type", None)
        has_callback = any(getattr(w, c, None) is not None for c in _CALLBACKS) or bool(getattr(w, "actions", None))
        wid = getattr(w, "id", None)
        if (has_callback or kind in _NEEDS_ID) and not wid:
            wid = _auto_id(w)
            object.__setattr__(w, "id", wid)
        if wid:
            callbacks = {c: getattr(w, c) for c in _CALLBACKS if getattr(w, c, None) is not None}
            acts = getattr(w, "actions", None)
            if acts:
                callbacks["actions"] = {a["label"]: a["callback"] for a in acts}
            if callbacks:
                _handlers[wid] = callbacks
        if kind in ("input", "search"):
            value = getattr(w, "value", None)
            if value is not None and _shown_values.get(wid) != str(value):
                _shown_values[wid] = str(value)
                _core._wm.set_input(wid, str(value))
        children = getattr(w, "children", None)
        if children:
            _finish(children)
        for f in ("pages", "items"):
            inner = getattr(w, f, None)
            if inner:
                _finish(inner)
