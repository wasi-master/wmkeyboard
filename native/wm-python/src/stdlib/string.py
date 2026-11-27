"""string: character classes, capwords, Template and Formatter."""

import re

ascii_lowercase = "abcdefghijklmnopqrstuvwxyz"
ascii_uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
ascii_letters = ascii_lowercase + ascii_uppercase
digits = "0123456789"
hexdigits = "0123456789abcdefABCDEF"
octdigits = "01234567"
punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
whitespace = " \t\n\r" + chr(11) + chr(12)
printable = digits + ascii_letters + punctuation + whitespace


def capwords(s, sep=None):
    if sep is None:
        return " ".join(w.capitalize() for w in s.split())
    return sep.join(w.capitalize() for w in s.split(sep))


class Template:
    def __init__(self, template):
        self.template = template

    def _sub(self, mapping, safe):
        def repl(m):
            name = m.group(1) or m.group(2)
            if m.group(0) == "$$":
                return "$"
            if name in mapping:
                return str(mapping[name])
            if safe:
                return m.group(0)
            raise KeyError(name)

        return re.sub(r"\$\$|\$([_a-zA-Z][_a-zA-Z0-9]*)|\$\{([_a-zA-Z][_a-zA-Z0-9]*)\}", repl, self.template)

    def substitute(self, mapping=None, **kwargs):
        values = dict(mapping or {})
        values.update(kwargs)
        return self._sub(values, False)

    def safe_substitute(self, mapping=None, **kwargs):
        values = dict(mapping or {})
        values.update(kwargs)
        return self._sub(values, True)


# ---- string.Formatter: CPython 3.13's Lib/string.py class, unchanged but for
# the two helpers it takes from the C module _string, written here in
# Python. Python Software Foundation License Version 2; the text ships as
# app/src/main/assets/licenses/psf-2.0.txt.


def _split_field(field):
    """`name!conv:spec` into its three parts; `!` and `:` inside [] belong to the name."""
    depth = 0
    for i, c in enumerate(field):
        if c == "[":
            depth += 1
        elif c == "]":
            depth -= 1
        elif depth == 0 and c in "!:":
            name = field[:i]
            if c == ":":
                return name, None, field[i + 1 :]
            if i + 1 >= len(field):
                raise ValueError("end of string while looking for conversion specifier")
            conv = field[i + 1]
            rest = field[i + 2 :]
            if rest and not rest.startswith(":"):
                raise ValueError("expected ':' after conversion specifier")
            return name, conv, rest[1:]
    return field, None, ""


def _formatter_parser(s):
    """(literal text, field name, format spec, conversion) for each piece of [s], as _string does."""
    literal = []
    i = 0
    n = len(s)
    while i < n:
        brace = min((p for p in (s.find("{", i), s.find("}", i)) if p >= 0), default=n)
        literal.append(s[i:brace])
        i = brace
        if i >= n:
            break
        if s[i] == "}":
            if i + 1 < n and s[i + 1] == "}":
                literal.append("}")
                i += 2
                continue
            raise ValueError("Single '}' encountered in format string")
        if i + 1 < n and s[i + 1] == "{":
            literal.append("{")
            i += 2
            continue
        depth = 1
        j = i + 1
        while j < n and depth:
            if s[j] == "{":
                depth += 1
            elif s[j] == "}":
                depth -= 1
            j += 1
        if depth:
            raise ValueError("expected '}' before end of string")
        name, conv, spec = _split_field(s[i + 1 : j - 1])
        yield ("".join(literal), name, spec, conv)
        literal = []
        i = j
    if literal and "".join(literal):
        yield ("".join(literal), None, None, None)


def _formatter_field_name_split(name):
    """The first part of a field name, and the `.attr` and `[key]` steps after it."""
    end = len(name)
    for i, c in enumerate(name):
        if c in ".[":
            end = i
            break
    first = name[:end]
    if first.isdigit():
        first = int(first)
    rest = []
    i = end
    while i < len(name):
        if name[i] == ".":
            j = i + 1
            while j < len(name) and name[j] not in ".[":
                j += 1
            if j == i + 1:
                raise ValueError("Empty attribute in format string")
            rest.append((True, name[i + 1 : j]))
            i = j
        elif name[i] == "[":
            j = name.find("]", i)
            if j < 0:
                raise ValueError("Missing ']' in format string")
            key = name[i + 1 : j]
            if key == "":
                raise ValueError("Empty attribute in format string")
            rest.append((False, int(key) if key.isdigit() else key))
            i = j + 1
        else:
            raise ValueError("Only '.' or '[' may follow ']' in format field specifier")
    return first, iter(rest)


class Formatter:
    def format(self, format_string, /, *args, **kwargs):
        return self.vformat(format_string, args, kwargs)

    def vformat(self, format_string, args, kwargs):
        used_args = set()
        result, _ = self._vformat(format_string, args, kwargs, used_args, 2)
        self.check_unused_args(used_args, args, kwargs)
        return result

    def _vformat(self, format_string, args, kwargs, used_args, recursion_depth,
                 auto_arg_index=0):
        if recursion_depth < 0:
            raise ValueError('Max string recursion exceeded')
        result = []
        for literal_text, field_name, format_spec, conversion in \
                self.parse(format_string):

            # output the literal text
            if literal_text:
                result.append(literal_text)

            # if there's a field, output it
            if field_name is not None:
                # this is some markup, find the object and do
                #  the formatting

                # handle arg indexing when empty field_names are given.
                if field_name == '':
                    if auto_arg_index is False:
                        raise ValueError('cannot switch from manual field '
                                         'specification to automatic field '
                                         'numbering')
                    field_name = str(auto_arg_index)
                    auto_arg_index += 1
                elif field_name.isdigit():
                    if auto_arg_index:
                        raise ValueError('cannot switch from manual field '
                                         'specification to automatic field '
                                         'numbering')
                    # disable auto arg incrementing, if it gets
                    # used later on, then an exception will be raised
                    auto_arg_index = False

                # given the field_name, find the object it references
                #  and the argument it came from
                obj, arg_used = self.get_field(field_name, args, kwargs)
                used_args.add(arg_used)

                # do any conversion on the resulting object
                obj = self.convert_field(obj, conversion)

                # expand the format spec, if needed
                format_spec, auto_arg_index = self._vformat(
                    format_spec, args, kwargs,
                    used_args, recursion_depth-1,
                    auto_arg_index=auto_arg_index)

                # format the object and append to the result
                result.append(self.format_field(obj, format_spec))

        return ''.join(result), auto_arg_index


    def get_value(self, key, args, kwargs):
        if isinstance(key, int):
            return args[key]
        else:
            return kwargs[key]


    def check_unused_args(self, used_args, args, kwargs):
        pass


    def format_field(self, value, format_spec):
        return format(value, format_spec)


    def convert_field(self, value, conversion):
        # do any conversion on the resulting object
        if conversion is None:
            return value
        elif conversion == 's':
            return str(value)
        elif conversion == 'r':
            return repr(value)
        elif conversion == 'a':
            return ascii(value)
        raise ValueError("Unknown conversion specifier {0!s}".format(conversion))


    # returns an iterable that contains tuples of the form:
    # (literal_text, field_name, format_spec, conversion)
    # literal_text can be zero length
    # field_name can be None, in which case there's no
    #  object to format and output
    # if field_name is not None, it is looked up, formatted
    #  with format_spec and conversion and then used
    def parse(self, format_string):
        return _formatter_parser(format_string)


    # given a field_name, find the object it references.
    #  field_name:   the field being looked up, e.g. "0.name"
    #                 or "lookup[3]"
    #  used_args:    a set of which args have been used
    #  args, kwargs: as passed in to vformat
    def get_field(self, field_name, args, kwargs):
        first, rest = _formatter_field_name_split(field_name)

        obj = self.get_value(first, args, kwargs)

        # loop through the rest of the field_name, doing
        #  getattr or getitem as needed
        for is_attr, i in rest:
            if is_attr:
                obj = getattr(obj, i)
            else:
                obj = obj[i]

        return obj, first
