"""pprint: data structures printed across lines when they do not fit, laid
out as CPython's pprint lays them out."""

import io
import re
import sys

__all__ = ["pprint", "pformat", "isreadable", "isrecursive", "saferepr", "PrettyPrinter", "pp"]


def pprint(object, stream=None, indent=1, width=80, depth=None, *, compact=False, sort_dicts=True, underscore_numbers=False):
    PrettyPrinter(stream=stream, indent=indent, width=width, depth=depth, compact=compact, sort_dicts=sort_dicts, underscore_numbers=underscore_numbers).pprint(object)


def pformat(object, indent=1, width=80, depth=None, *, compact=False, sort_dicts=True, underscore_numbers=False):
    return PrettyPrinter(indent=indent, width=width, depth=depth, compact=compact, sort_dicts=sort_dicts, underscore_numbers=underscore_numbers).pformat(object)


def pp(object, *args, sort_dicts=False, **kwargs):
    pprint(object, *args, sort_dicts=sort_dicts, **kwargs)


def saferepr(object):
    return PrettyPrinter()._safe_repr(object, {}, None, 0)[0]


def isreadable(object):
    return PrettyPrinter()._safe_repr(object, {}, None, 0)[1]


def isrecursive(object):
    return PrettyPrinter()._safe_repr(object, {}, None, 0)[2]


class _SafeKey:
    """Orders values of any types: by value when they compare, else by type name."""

    def __init__(self, obj):
        self.obj = obj

    def __lt__(self, other):
        try:
            return self.obj < other.obj
        except TypeError:
            return (str(type(self.obj)), id(self.obj)) < (str(type(other.obj)), id(other.obj))


def _safe_tuple(t):
    return _SafeKey(t[0]), _SafeKey(t[1])


def _recursion(object):
    return f"<Recursion on {type(object).__name__} with id={id(object)}>"


def _collections():
    import collections

    return collections


def _is_dataclass_instance(object):
    return hasattr(type(object), "__dataclass_fields__") and not isinstance(object, type)


class PrettyPrinter:
    def __init__(self, indent=1, width=80, depth=None, stream=None, *, compact=False, sort_dicts=True, underscore_numbers=False):
        indent = int(indent)
        width = int(width)
        if indent < 0:
            raise ValueError("indent must be >= 0")
        if depth is not None and depth <= 0:
            raise ValueError("depth must be > 0")
        if not width:
            raise ValueError("width must be != 0")
        self._depth = depth
        self._indent_per_level = indent
        self._width = width
        self._stream = stream
        self._compact = bool(compact)
        self._sort_dicts = sort_dicts
        self._underscore_numbers = underscore_numbers

    def pprint(self, object):
        if self._stream is not None:
            self._stream.write(self.pformat(object) + "\n")
        else:
            print(self.pformat(object))

    def pformat(self, object):
        sio = io.StringIO()
        self._format(object, sio, 0, 0, {}, 0)
        return sio.getvalue()

    def isrecursive(self, object):
        return self.format(object, {}, 0, 0)[2]

    def isreadable(self, object):
        s, readable, recursive = self.format(object, {}, 0, 0)
        return readable and not recursive

    def _format(self, object, stream, indent, allowance, context, level):
        objid = id(object)
        if objid in context:
            stream.write(_recursion(object))
            self._recursive = True
            self._readable = False
            return
        rep = self._repr(object, context, level)
        max_width = self._width - indent - allowance
        if len(rep) > max_width:
            p = self._printer(object)
            if p is not None:
                context[objid] = 1
                p(object, stream, indent, allowance, context, level + 1)
                del context[objid]
                return
            if _is_dataclass_instance(object):
                context[objid] = 1
                self._pprint_dataclass(object, stream, indent, allowance, context, level + 1)
                del context[objid]
                return
        stream.write(rep)

    def _printer(self, object):
        t = type(object)
        if t is dict:
            return self._pprint_dict
        if t is list:
            return self._pprint_list
        if t is tuple:
            return self._pprint_tuple
        if t is set or t is frozenset:
            return self._pprint_set
        if t is str:
            return self._pprint_str
        if t is bytes:
            return self._pprint_bytes
        if t is bytearray:
            return self._pprint_bytearray
        c = _collections()
        if t is c.Counter:
            return self._pprint_counter
        if t is c.defaultdict:
            return self._pprint_default_dict
        if t is c.deque:
            return self._pprint_deque
        if t is c.ChainMap:
            return self._pprint_chain_map
        return None

    def _pprint_dataclass(self, object, stream, indent, allowance, context, level):
        import dataclasses

        cls_name = type(object).__name__
        indent += len(cls_name) + 1
        items = [(f.name, getattr(object, f.name)) for f in dataclasses.fields(object) if f.repr]
        stream.write(cls_name + "(")
        self._format_namespace_items(items, stream, indent, allowance, context, level)
        stream.write(")")

    def _pprint_dict(self, object, stream, indent, allowance, context, level):
        write = stream.write
        write("{")
        if self._indent_per_level > 1:
            write((self._indent_per_level - 1) * " ")
        if len(object):
            items = sorted(object.items(), key=_safe_tuple) if self._sort_dicts else list(object.items())
            self._format_dict_items(items, stream, indent, allowance + 1, context, level)
        write("}")

    def _pprint_list(self, object, stream, indent, allowance, context, level):
        stream.write("[")
        self._format_items(object, stream, indent, allowance + 1, context, level)
        stream.write("]")

    def _pprint_tuple(self, object, stream, indent, allowance, context, level):
        stream.write("(")
        endchar = ",)" if len(object) == 1 else ")"
        self._format_items(object, stream, indent, allowance + len(endchar), context, level)
        stream.write(endchar)

    def _pprint_set(self, object, stream, indent, allowance, context, level):
        if not len(object):
            stream.write(repr(object))
            return
        typ = type(object)
        if typ is set:
            stream.write("{")
            endchar = "}"
        else:
            stream.write(typ.__name__ + "({")
            endchar = "})"
            indent += len(typ.__name__) + 1
        object = sorted(object, key=_SafeKey)
        self._format_items(object, stream, indent, allowance + len(endchar), context, level)
        stream.write(endchar)

    def _pprint_str(self, object, stream, indent, allowance, context, level):
        write = stream.write
        if not len(object):
            write(repr(object))
            return
        chunks = []
        lines = object.splitlines(True)
        if level == 1:
            indent += 1
            allowance += 1
        max_width1 = max_width = self._width - indent
        rep = ""
        for i, line in enumerate(lines):
            rep = repr(line)
            if i == len(lines) - 1:
                max_width1 -= allowance
            if len(rep) <= max_width1:
                chunks.append(rep)
            else:
                parts = re.findall(r"\S*\s*", line)
                parts.pop()
                max_width2 = max_width
                current = ""
                for j, part in enumerate(parts):
                    candidate = current + part
                    if j == len(parts) - 1 and i == len(lines) - 1:
                        max_width2 -= allowance
                    if len(repr(candidate)) > max_width2:
                        if current:
                            chunks.append(repr(current))
                        current = part
                    else:
                        current = candidate
                if current:
                    chunks.append(repr(current))
        if len(chunks) == 1:
            write(rep)
            return
        if level == 1:
            write("(")
        for i, rep in enumerate(chunks):
            if i > 0:
                write("\n" + " " * indent)
            write(rep)
        if level == 1:
            write(")")

    def _pprint_bytes(self, object, stream, indent, allowance, context, level):
        write = stream.write
        if len(object) <= 4:
            write(repr(object))
            return
        parens = level == 1
        if parens:
            indent += 1
            allowance += 1
            write("(")
        delim = ""
        for rep in _wrap_bytes_repr(object, self._width - indent, allowance):
            write(delim)
            write(rep)
            if not delim:
                delim = "\n" + " " * indent
        if parens:
            write(")")

    def _pprint_bytearray(self, object, stream, indent, allowance, context, level):
        stream.write("bytearray(")
        self._pprint_bytes(bytes(object), stream, indent + 10, allowance + 1, context, level + 1)
        stream.write(")")

    def _pprint_counter(self, object, stream, indent, allowance, context, level):
        if not len(object):
            stream.write(repr(object))
            return
        cls_name = type(object).__name__
        stream.write(cls_name + "({")
        if self._indent_per_level > 1:
            stream.write((self._indent_per_level - 1) * " ")
        items = object.most_common()
        self._format_dict_items(items, stream, indent + len(cls_name) + 1, allowance + 2, context, level)
        stream.write("})")

    def _pprint_default_dict(self, object, stream, indent, allowance, context, level):
        if not len(object):
            stream.write(repr(object))
            return
        rdf = self._repr(object.default_factory, context, level)
        cls_name = type(object).__name__
        indent += len(cls_name) + 1
        stream.write(f"{cls_name}({rdf},\n{' ' * indent}")
        self._pprint_dict(dict(object.items()), stream, indent, allowance + 1, context, level)
        stream.write(")")

    def _pprint_deque(self, object, stream, indent, allowance, context, level):
        if not len(object):
            stream.write(repr(object))
            return
        cls_name = type(object).__name__
        indent += len(cls_name) + 1
        stream.write(cls_name + "([")
        if object.maxlen is None:
            self._format_items(object, stream, indent, allowance + 2, context, level)
            stream.write("])")
        else:
            self._format_items(object, stream, indent, 2, context, level)
            rml = self._repr(object.maxlen, context, level)
            stream.write(f"],\n{' ' * indent}maxlen={rml})")

    def _pprint_chain_map(self, object, stream, indent, allowance, context, level):
        if not len(object.maps):
            stream.write(repr(object))
            return
        cls_name = type(object).__name__
        stream.write(cls_name + "(")
        indent += len(cls_name) + 1
        for i, m in enumerate(object.maps):
            if i == len(object.maps) - 1:
                self._format(m, stream, indent, allowance + 1, context, level)
                stream.write(")")
            else:
                self._format(m, stream, indent, 1, context, level)
                stream.write(",\n" + " " * indent)

    def _format_dict_items(self, items, stream, indent, allowance, context, level):
        write = stream.write
        indent += self._indent_per_level
        delimnl = ",\n" + " " * indent
        last_index = len(items) - 1
        for i, (key, ent) in enumerate(items):
            last = i == last_index
            rep = self._repr(key, context, level)
            write(rep)
            write(": ")
            self._format(ent, stream, indent + len(rep) + 2, allowance if last else 1, context, level)
            if not last:
                write(delimnl)

    def _format_namespace_items(self, items, stream, indent, allowance, context, level):
        write = stream.write
        delimnl = ",\n" + " " * indent
        last_index = len(items) - 1
        for i, (key, ent) in enumerate(items):
            last = i == last_index
            write(key)
            write("=")
            if id(ent) in context:
                write("...")
            else:
                self._format(ent, stream, indent + len(key) + 1, allowance if last else 1, context, level)
            if not last:
                write(delimnl)

    def _format_items(self, items, stream, indent, allowance, context, level):
        write = stream.write
        indent += self._indent_per_level
        if self._indent_per_level > 1:
            write((self._indent_per_level - 1) * " ")
        delimnl = ",\n" + " " * indent
        delim = ""
        width = max_width = self._width - indent + 1
        it = iter(items)
        try:
            next_ent = next(it)
        except StopIteration:
            return
        last = False
        while not last:
            ent = next_ent
            try:
                next_ent = next(it)
            except StopIteration:
                last = True
                max_width -= allowance
                width -= allowance
            if self._compact:
                rep = self._repr(ent, context, level)
                w = len(rep) + 2
                if width < w:
                    width = max_width
                    if delim:
                        delim = delimnl
                if width >= w:
                    width -= w
                    write(delim)
                    delim = ", "
                    write(rep)
                    continue
            write(delim)
            delim = delimnl
            self._format(ent, stream, indent, allowance if last else 1, context, level)

    def _repr(self, object, context, level):
        repr_, readable, recursive = self.format(object, dict(context), self._depth, level)
        if not readable:
            self._readable = False
        if recursive:
            self._recursive = True
        return repr_

    def format(self, object, context, maxlevels, level):
        return self._safe_repr(object, context, maxlevels, level)

    def _safe_repr(self, object, context, maxlevels, level):
        typ = type(object)
        if typ in (str, bytes, bool, float, type(None)) or typ is int:
            if typ is int and self._underscore_numbers:
                return f"{object:_d}", True, False
            return repr(object), True, False
        if typ is dict:
            if not object:
                return "{}", True, False
            objid = id(object)
            if maxlevels and level >= maxlevels:
                return "{...}", False, objid in context
            if objid in context:
                return _recursion(object), False, True
            context[objid] = 1
            readable = True
            recursive = False
            components = []
            level += 1
            items = sorted(object.items(), key=_safe_tuple) if self._sort_dicts else object.items()
            for k, v in items:
                krepr, kreadable, krecur = self.format(k, context, maxlevels, level)
                vrepr, vreadable, vrecur = self.format(v, context, maxlevels, level)
                components.append(f"{krepr}: {vrepr}")
                readable = readable and kreadable and vreadable
                if krecur or vrecur:
                    recursive = True
            del context[objid]
            return "{" + ", ".join(components) + "}", readable, recursive
        if typ is list or typ is tuple:
            if typ is list:
                if not object:
                    return "[]", True, False
                fmt = "[%s]"
            elif len(object) == 1:
                fmt = "(%s,)"
            else:
                if not object:
                    return "()", True, False
                fmt = "(%s)"
            objid = id(object)
            if maxlevels and level >= maxlevels:
                return fmt % "...", False, objid in context
            if objid in context:
                return _recursion(object), False, True
            context[objid] = 1
            readable = True
            recursive = False
            components = []
            level += 1
            for o in object:
                orepr, oreadable, orecur = self.format(o, context, maxlevels, level)
                components.append(orepr)
                if not oreadable:
                    readable = False
                if orecur:
                    recursive = True
            del context[objid]
            return fmt % ", ".join(components), readable, recursive
        rep = repr(object)
        return rep, bool(rep) and not rep.startswith("<"), False


def _wrap_bytes_repr(object, width, allowance):
    current = b""
    last = len(object) // 4 * 4
    for i in range(0, len(object), 4):
        part = object[i : i + 4]
        candidate = current + part
        if i == last:
            width -= allowance
        if len(repr(candidate)) > width:
            if current:
                yield repr(current)
            current = part
        else:
            current = candidate
    if current:
        yield repr(current)
