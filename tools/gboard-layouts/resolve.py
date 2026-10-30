"""Turns a Gboard APK's framework XML into keyboards: rows of keys, per key state."""
import json
import re

import axml
from axml import Ref

PHONE = {"device": "phone", "keyboard_mode": "normal", "orientation": "portrait"}


class Apk:
    """Compiled resources reached by id. `index` maps a resource id to its files."""

    def __init__(self, zf, index):
        self.zf = zf
        self.index = index
        self.cache = {}

    def doc(self, path):
        if path not in self.cache:
            self.cache[path] = axml.parse(self.zf.read(path))
        return self.cache[path]

    def res(self, rid):
        files = self.index.get(int(rid), [])
        return self.doc(files[0]) if files else None


def load_index(resources_dump):
    """id -> [files], from the text of `aapt2 dump resources`, default config first."""
    out = {}
    cur = None
    for line in open(resources_dump, errors="replace"):
        m = re.match(r"\s+resource (0x[0-9a-f]+) ", line)
        if m:
            cur = int(m.group(1), 16)
            continue
        m = re.match(r"\s+\(([^)]*)\) \(file\) (res/\S+) type=XML", line)
        if m and cur is not None:
            bucket = out.setdefault(cur, [])
            if m.group(1) == "":
                bucket.insert(0, m.group(2))
            else:
                bucket.append(m.group(2))
    return out


def load_strings(resources_dump):
    """id -> default string value."""
    out = {}
    cur = None
    for line in open(resources_dump, errors="replace"):
        m = re.match(r"\s+resource (0x[0-9a-f]+) string/", line)
        if m:
            cur = int(m.group(1), 16)
            continue
        if cur is not None:
            m = re.match(r'\s+\(\) "(.*)"\s*$', line)
            if m:
                out[cur] = m.group(1)
                cur = None
            elif re.match(r"\s+resource ", line):
                cur = None
    return out


def subst(value, params):
    if isinstance(value, str) and value.startswith("{") and value.endswith("}"):
        return params.get(value[1:-1], value)
    return value


def holds(attrs, env):
    """Whether an if/else_if's conditions hold. A condition nothing sets is off."""
    for k, want in attrs.items():
        want = subst(want, env)
        if k == "true":
            if want is not True:
                return False
            continue
        have = env.get(k, False)
        if isinstance(want, str) and "|" in want:
            if str(have) not in want.split("|"):
                return False
        elif str(have) != str(want):
            return False
    return True


def children(node, env):
    """A node's children with if/else chains settled and includes left in place."""
    out = []
    taken = None
    for c in node.children:
        if c.tag == "if":
            taken = holds(c.attrs, env)
            if taken:
                out += children(c, env)
        elif c.tag == "else_if":
            if not taken and holds(c.attrs, env):
                taken = True
                out += children(c, env)
        elif c.tag == "else":
            if not taken:
                out += children(c, env)
            taken = None
        else:
            out.append(c)
    return out


class Keyboard:
    def __init__(self):
        self.layout = None
        self.softkeys = {}
        self.mappings = []  # (state, exclude, key_id, view_id)
        self.merges = []  # (state, exclude, key_id, view_id)
        self.handlers = []
        self.attrs = {}


def read_softkey(node, env):
    key = dict(node.attrs)
    actions = []
    for c in children(node, env):
        if c.tag == "action":
            actions.append(dict(c.attrs))
    if actions:
        key["_actions"] = actions
    return key


def visit_softkeys(apk, node, env, kb, seen):
    for c in children(node, env):
        if c.tag == "include":
            visit_softkeys_ref(apk, c, env, kb, seen)
        elif c.tag == "softkeys":
            if "href" in c.attrs:
                visit_softkeys_ref(apk, c, env, kb, seen)
            visit_softkeys(apk, c, env, kb, seen)
        elif c.tag == "softkey_list":
            splitter = c.attrs.get("splitter", ",")
            for k in children(c, env):
                if k.tag == "softkey" and "id" in k.attrs:
                    key = read_softkey(k, env)
                    key["_splitter"] = splitter
                    kb.softkeys[int(k.attrs["id"])] = key
        elif c.tag == "softkey" and "id" in c.attrs:
            kb.softkeys[int(c.attrs["id"])] = read_softkey(c, env)
        elif c.tag == "framework":
            visit_softkeys(apk, c, env, kb, seen)


def visit_softkeys_ref(apk, node, env, kb, seen):
    href = node.attrs.get("href")
    if isinstance(href, Ref) and int(href) not in seen:
        seen.add(int(href))
        doc = apk.res(href)
        if doc is not None:
            visit_softkeys(apk, doc, env, kb, seen)


def visit_mapping(apk, node, env, kb, state=None, exclude=None, seen=None):
    seen = seen if seen is not None else set()
    for c in children(node, env):
        if c.tag == "include" or (c.tag in ("framework",)):
            href = c.attrs.get("href")
            if isinstance(href, Ref) and int(href) not in seen:
                seen.add(int(href))
                doc = apk.res(href)
                if doc is not None:
                    visit_mapping(apk, doc, env, kb, state, exclude, seen)
            else:
                visit_mapping(apk, c, env, kb, state, exclude, seen)
        elif c.tag in ("key_mapping", "merge_key_mapping"):
            s = c.attrs.get("state", state)
            e = c.attrs.get("exclude_state", exclude)
            sink = kb.mappings if c.tag == "key_mapping" else kb.merges
            for m in children(c, env):
                if m.tag in ("mapping", "merge") and "key_id" in m.attrs and "view_id" in m.attrs:
                    sink.append((s, e, int(m.attrs["key_id"]), int(m.attrs["view_id"])))


def visit_view(apk, view, env, kb, seen):
    if "layout" in view.attrs and isinstance(view.attrs["layout"], Ref):
        kb.layout = int(view.attrs["layout"])
    for c in children(view, env):
        if c.tag == "softkeys":
            visit_softkeys_ref(apk, c, env, kb, seen)
            visit_softkeys(apk, c, env, kb, seen)
        elif c.tag in ("key_mapping", "merge_key_mapping"):
            holder = axml.Node("x", {})
            holder.children = [c]
            visit_mapping(apk, holder, env, kb)
        elif c.tag == "include":
            href = c.attrs.get("href")
            if isinstance(href, Ref):
                doc = apk.res(href)
                if doc is not None:
                    visit_view(apk, doc, env, kb, seen)
        elif c.tag == "motion_event_handler":
            kb.handlers.append(c.attrs.get("class", ""))
        elif c.tag == "framework":
            visit_view(apk, c, env, kb, seen)


def read_keyboard(apk, rid, env, kb=None, seen=None):
    """Walks a keyboard definition and the bases it includes, base first."""
    kb = kb or Keyboard()
    seen = seen if seen is not None else set()
    if int(rid) in seen:
        return kb
    seen.add(int(rid))
    doc = apk.res(rid)
    if doc is None:
        return kb
    for c in children(doc, env):
        if c.tag == "include":
            href = c.attrs.get("href")
            if isinstance(href, Ref):
                read_keyboard(apk, href, env, kb, seen)
        elif c.tag == "keyboard":
            for k, v in c.attrs.items():
                kb.attrs.setdefault(k, v)
            for v in children(c, env):
                if v.tag == "view" and v.attrs.get("type") == "body":
                    visit_view(apk, v, env, kb, set())
                elif v.tag == "include" and isinstance(v.attrs.get("href"), Ref):
                    read_keyboard(apk, v.attrs["href"], env, kb, seen)
    return kb


def read_rows(apk, rid):
    """A layout's rows, top to bottom, each [(view_id, weight, style)]."""
    rows = []

    def walk(node):
        direct = []
        for c in node.children:
            if c.tag.endswith("SoftKeyView"):
                direct.append(
                    (int(c.attrs.get("id", 0)), c.attrs.get("layout_weight", 100.0), c.attrs.get("visibility", 0))
                )
            elif c.tag == "include":
                ref = c.attrs.get("layout")
                if isinstance(ref, Ref):
                    doc = apk.res(ref)
                    if doc is not None:
                        walk_group(doc)
            else:
                walk_group(c)
        if direct:
            rows.append(direct)

    def walk_group(node):
        if node.tag.endswith("SoftKeyView"):
            rows.append([(int(node.attrs.get("id", 0)), node.attrs.get("layout_weight", 100.0), 0)])
        else:
            walk(node)

    doc = apk.res(rid)
    if doc is not None:
        walk_group(doc)
    return rows


def applies(state, exclude, active):
    """Whether a mapping's state filter is met when exactly `active` are on."""
    if exclude:
        for alt in exclude.split(","):
            if all(s in active for s in alt.split("+")):
                return False
    if state:
        return any(all(s in active for s in alt.split("+")) for alt in state.split(","))
    return True


def grid(apk, kb, active=frozenset()):
    """The key on each view of the layout with `active` states on."""
    if kb.layout is None:
        return []
    by_view = {}
    for state, exclude, key_id, view_id in kb.mappings:
        if applies(state, exclude, active) and key_id in kb.softkeys:
            by_view[view_id] = kb.softkeys[key_id]
    # A merge fills a view nothing mapped outright: the comma, the full stop.
    for state, exclude, key_id, view_id in kb.merges:
        if applies(state, exclude, active) and key_id in kb.softkeys:
            by_view.setdefault(view_id, kb.softkeys[key_id])
    out = []
    for row in read_rows(apk, kb.layout):
        cells = []
        for view_id, weight, _vis in row:
            key = by_view.get(view_id)
            cells.append({"view": view_id, "weight": weight, "key": key})
        out.append(cells)
    return out


def walk_keyboards(apk, node, env, params, depth=0):
    """The prime keyboards an ime reaches, each with its definitions and its variant's attributes."""
    out = []
    if depth > 10:
        return out
    scope = {**env, **params}
    for c in children(node, scope):
        if c.tag == "include":
            href = subst(c.attrs.get("href"), params)
            if not isinstance(href, Ref):
                continue
            doc = apk.res(href)
            if doc is None:
                continue
            # The included file's root attributes are its parameters' defaults.
            p = {**params, **doc.attrs}
            for k, v in c.attrs.items():
                if k != "href":
                    p[k] = subst(v, params)
            out += walk_keyboards(apk, doc, env, p, depth + 1)
        elif c.tag == "keyboard_group":
            p = dict(params)
            for k, v in c.attrs.items():
                p[k] = subst(v, params)
            p["_group"] = True
            out += walk_keyboards(apk, c, env, p, depth + 1)
        elif c.tag == "keyboard" and c.attrs.get("type") == "prime":
            defs = [subst(c.attrs.get("def"), params)]
            merges = [subst(m.attrs.get("def"), params) for m in children(c, scope) if m.tag == "merge" and "def" in m.attrs]
            info = {k: params.get(k) for k in ("variant", "variant_label", "pk_language_tag", "pk_layout_type", "dynamic_layout")}
            info.update(defs=[d for d in defs if isinstance(d, Ref)], merges=[m for m in merges if isinstance(m, Ref)])
            out.append(info)
        elif c.tag == "framework":
            out += walk_keyboards(apk, c, env, params, depth + 1)
    return out


def keyboard_of(apk, info, env):
    """One keyboard from its language definition and the grid definition merged over it."""
    kb = Keyboard()
    lang = Keyboard()
    for d in info["defs"]:
        read_keyboard(apk, d, env, lang)
    for m in info["merges"]:
        read_keyboard(apk, m, env, kb)
    if not info["merges"]:
        return lang
    kb.softkeys.update(lang.softkeys)
    kb.mappings = lang.mappings + kb.mappings
    kb.merges = lang.merges + kb.merges
    kb.handlers = lang.handlers + kb.handlers
    kb.attrs = {**lang.attrs, **kb.attrs}
    kb.layout = kb.layout or lang.layout
    return kb
