"""configparser: INI-style configuration files, read from strings, file
objects and dicts, with %(name)s or ${section:name} interpolation."""

import io
import re
from collections.abc import MutableMapping

__all__ = [
    "NoSectionError", "DuplicateOptionError", "DuplicateSectionError", "NoOptionError", "InterpolationError",
    "InterpolationDepthError", "InterpolationMissingOptionError", "InterpolationSyntaxError", "ParsingError",
    "MissingSectionHeaderError", "ConfigParser", "RawConfigParser", "Interpolation", "BasicInterpolation",
    "ExtendedInterpolation", "SectionProxy", "ConverterMapping", "DEFAULTSECT", "MAX_INTERPOLATION_DEPTH", "UNNAMED_SECTION",
]

DEFAULTSECT = "DEFAULT"
MAX_INTERPOLATION_DEPTH = 10


class _UnnamedSection:
    def __repr__(self):
        return "<UNNAMED_SECTION>"


UNNAMED_SECTION = _UnnamedSection()


class Error(Exception):
    def __init__(self, msg=""):
        self.message = msg
        Exception.__init__(self, msg)

    def __str__(self):
        return self.message

    __repr__ = __str__


class NoSectionError(Error):
    def __init__(self, section):
        Error.__init__(self, f"No section: {section!r}")
        self.section = section
        self.args = (section,)


class DuplicateSectionError(Error):
    def __init__(self, section, source=None, lineno=None):
        msg = [repr(section), " already exists"]
        if source is not None:
            message = [f"While reading from {source!r}"]
            if lineno is not None:
                message.append(f" [line {lineno:2d}]")
            message.append(": section ")
            message.extend(msg)
            msg = message
        else:
            msg.insert(0, "Section ")
        Error.__init__(self, "".join(msg))
        self.section = section
        self.source = source
        self.lineno = lineno
        self.args = (section, source, lineno)


class DuplicateOptionError(Error):
    def __init__(self, section, option, source=None, lineno=None):
        msg = [repr(option), " in section ", repr(section), " already exists"]
        if source is not None:
            message = [f"While reading from {source!r}"]
            if lineno is not None:
                message.append(f" [line {lineno:2d}]")
            message.append(": option ")
            message.extend(msg)
            msg = message
        else:
            msg.insert(0, "Option ")
        Error.__init__(self, "".join(msg))
        self.section = section
        self.option = option
        self.source = source
        self.lineno = lineno
        self.args = (section, option, source, lineno)


class NoOptionError(Error):
    def __init__(self, option, section):
        Error.__init__(self, f"No option {option!r} in section: {section!r}")
        self.option = option
        self.section = section
        self.args = (option, section)


class InterpolationError(Error):
    def __init__(self, option, section, msg):
        Error.__init__(self, msg)
        self.option = option
        self.section = section
        self.args = (option, section, msg)


class InterpolationMissingOptionError(InterpolationError):
    def __init__(self, option, section, rawval, reference):
        msg = (
            f"Bad value substitution: option {option!r} in section {section!r} contains an interpolation key "
            f"{reference!r} which is not a valid option name. Raw value: {rawval!r}"
        )
        InterpolationError.__init__(self, option, section, msg)
        self.reference = reference
        self.args = (option, section, rawval, reference)


class InterpolationSyntaxError(InterpolationError):
    pass


class InterpolationDepthError(InterpolationError):
    def __init__(self, option, section, rawval):
        msg = (
            f"Recursion limit exceeded in value substitution: option {option!r} in section {section!r} contains an "
            f"interpolation key which cannot be substituted in {MAX_INTERPOLATION_DEPTH} steps. Raw value: {rawval!r}"
        )
        InterpolationError.__init__(self, option, section, msg)
        self.args = (option, section, rawval)


class ParsingError(Error):
    def __init__(self, source, *args):
        Error.__init__(self, f"Source contains parsing errors: {source!r}")
        self.source = source
        self.errors = []
        self.args = (source,)
        if args:
            self.append(*args)

    def append(self, lineno, line):
        self.errors.append((lineno, line))
        self.message += f"\n\t[line {lineno:2d}]: {line!r}"

    def combine(self, others):
        for other in others:
            for error in other.errors:
                self.append(*error)
        return self


class MissingSectionHeaderError(ParsingError):
    def __init__(self, filename, lineno, line):
        Error.__init__(self, f"File contains no section headers.\nfile: {filename!r}, line: {lineno}\n{line!r}")
        self.source = filename
        self.lineno = lineno
        self.line = line
        self.errors = []
        self.args = (filename, lineno, line)


_UNSET = object()


class Interpolation:
    def before_get(self, parser, section, option, value, defaults):
        return value

    def before_set(self, parser, section, option, value):
        return value

    def before_read(self, parser, section, option, value):
        return value

    def before_write(self, parser, section, option, value):
        return value


class BasicInterpolation(Interpolation):
    _KEYCRE = re.compile(r"%\(([^)]+)\)s")

    def before_get(self, parser, section, option, value, defaults):
        L = []
        self._interpolate_some(parser, option, L, value, section, defaults, 1)
        return "".join(L)

    def before_set(self, parser, section, option, value):
        tmp_value = value.replace("%%", "")
        tmp_value = self._KEYCRE.sub("", tmp_value)
        if "%" in tmp_value:
            raise ValueError(f"invalid interpolation syntax in {value!r} at position {tmp_value.find('%')}")
        return value

    def _interpolate_some(self, parser, option, accum, rest, section, map, depth):
        rawval = parser.get(section, option, raw=True, fallback=rest)
        if depth > MAX_INTERPOLATION_DEPTH:
            raise InterpolationDepthError(option, section, rawval)
        while rest:
            p = rest.find("%")
            if p < 0:
                accum.append(rest)
                return
            if p > 0:
                accum.append(rest[:p])
                rest = rest[p:]
            c = rest[1:2]
            if c == "%":
                accum.append("%")
                rest = rest[2:]
            elif c == "(":
                m = self._KEYCRE.match(rest)
                if m is None:
                    raise InterpolationSyntaxError(option, section, f"bad interpolation variable reference {rest!r}")
                var = parser.optionxform(m.group(1))
                rest = rest[m.end() :]
                try:
                    v = map[var]
                except KeyError:
                    raise InterpolationMissingOptionError(option, section, rawval, var) from None
                if "%" in v:
                    self._interpolate_some(parser, option, accum, v, section, map, depth + 1)
                else:
                    accum.append(v)
            else:
                raise InterpolationSyntaxError(option, section, f"'%' must be followed by '%' or '(', found: {rest!r}")


class ExtendedInterpolation(Interpolation):
    _KEYCRE = re.compile(r"\$\{([^}]+)\}")

    def before_get(self, parser, section, option, value, defaults):
        L = []
        self._interpolate_some(parser, option, L, value, section, defaults, 1)
        return "".join(L)

    def before_set(self, parser, section, option, value):
        tmp_value = value.replace("$$", "")
        tmp_value = self._KEYCRE.sub("", tmp_value)
        if "$" in tmp_value:
            raise ValueError(f"invalid interpolation syntax in {value!r} at position {tmp_value.find('$')}")
        return value

    def _interpolate_some(self, parser, option, accum, rest, section, map, depth):
        rawval = parser.get(section, option, raw=True, fallback=rest)
        if depth > MAX_INTERPOLATION_DEPTH:
            raise InterpolationDepthError(option, section, rawval)
        while rest:
            p = rest.find("$")
            if p < 0:
                accum.append(rest)
                return
            if p > 0:
                accum.append(rest[:p])
                rest = rest[p:]
            c = rest[1:2]
            if c == "$":
                accum.append("$")
                rest = rest[2:]
            elif c == "{":
                m = self._KEYCRE.match(rest)
                if m is None:
                    raise InterpolationSyntaxError(option, section, f"bad interpolation variable reference {rest!r}")
                path = m.group(1).split(":")
                rest = rest[m.end() :]
                sect = section
                opt = option
                try:
                    if len(path) == 1:
                        opt = parser.optionxform(path[0])
                        v = map[opt]
                    elif len(path) == 2:
                        sect = path[0]
                        opt = parser.optionxform(path[1])
                        v = parser.get(sect, opt, raw=True)
                    else:
                        raise InterpolationSyntaxError(option, section, f"More than one ':' found: {rest!r}")
                except (KeyError, NoSectionError, NoOptionError):
                    raise InterpolationMissingOptionError(option, section, rawval, ":".join(path)) from None
                if "$" in v:
                    self._interpolate_some(parser, opt, accum, v, sect, dict(parser.items(sect, raw=True)), depth + 1)
                else:
                    accum.append(v)
            else:
                raise InterpolationSyntaxError(option, section, f"'$' must be followed by '$' or '{{', found: {rest!r}")


class _ChainMap:
    def __init__(self, *maps):
        self.maps = maps

    def __getitem__(self, key):
        for m in self.maps:
            if key in m:
                return m[key]
        raise KeyError(key)

    def __contains__(self, key):
        return any(key in m for m in self.maps)

    def keys(self):
        seen = []
        for m in reversed(self.maps):
            for k in m:
                if k not in seen:
                    seen.append(k)
        return seen


class RawConfigParser(MutableMapping):
    _DEFAULT_INTERPOLATION = Interpolation()
    BOOLEAN_STATES = {"1": True, "yes": True, "true": True, "on": True, "0": False, "no": False, "false": False, "off": False}

    def __init__(
        self,
        defaults=None,
        dict_type=dict,
        allow_no_value=False,
        *,
        delimiters=("=", ":"),
        comment_prefixes=("#", ";"),
        inline_comment_prefixes=None,
        strict=True,
        empty_lines_in_values=True,
        default_section=DEFAULTSECT,
        interpolation=_UNSET,
        converters=_UNSET,
        allow_unnamed_section=False,
    ):
        self._dict = dict_type
        self._sections = self._dict()
        self._defaults = self._dict()
        self._proxies = self._dict()
        self._delimiters = tuple(delimiters)
        self._comment_prefixes = tuple(comment_prefixes or ())
        self._inline_comment_prefixes = tuple(inline_comment_prefixes or ())
        self._strict = strict
        self._allow_no_value = allow_no_value
        self._empty_lines_in_values = empty_lines_in_values
        self._allow_unnamed_section = allow_unnamed_section
        self.default_section = default_section
        self._interpolation = interpolation
        if self._interpolation is _UNSET:
            self._interpolation = self._DEFAULT_INTERPOLATION
        if self._interpolation is None:
            self._interpolation = Interpolation()
        self._converters = {}
        if converters is not _UNSET:
            self._converters.update(converters)
        self._proxies[default_section] = SectionProxy(self, default_section)
        if defaults:
            self._read_defaults(defaults)

    def _read_defaults(self, defaults):
        self.read_dict({self.default_section: defaults})

    def defaults(self):
        return self._defaults

    def sections(self):
        return list(self._sections.keys())

    def add_section(self, section):
        if section == self.default_section:
            raise ValueError(f"Invalid section name: {section!r}")
        if section in self._sections:
            raise DuplicateSectionError(section)
        self._sections[section] = self._dict()
        self._proxies[section] = SectionProxy(self, section)

    def has_section(self, section):
        return section in self._sections

    def options(self, section):
        try:
            opts = self._sections[section].copy()
        except KeyError:
            raise NoSectionError(section) from None
        opts.update(self._defaults)
        return list(opts.keys())

    def read(self, filenames, encoding=None):
        return []

    def read_file(self, f, source=None):
        if source is None:
            source = getattr(f, "name", "<???>")
        self._read(f, source)

    def read_string(self, string, source="<string>"):
        self.read_file(io.StringIO(string), source)

    def read_dict(self, dictionary, source="<dict>"):
        elements_added = set()
        for section, keys in dictionary.items():
            section = str(section)
            try:
                self.add_section(section)
            except (DuplicateSectionError, ValueError):
                if self._strict and section in elements_added:
                    raise
            elements_added.add(section)
            for key, value in keys.items():
                key = self.optionxform(str(key))
                if value is not None:
                    value = str(value)
                if self._strict and (section, key) in elements_added:
                    raise DuplicateOptionError(section, key, source)
                elements_added.add((section, key))
                self.set(section, key, value)

    readfp = read_file

    def get(self, section, option, *, raw=False, vars=None, fallback=_UNSET):
        try:
            d = self._unify_values(section, vars)
        except NoSectionError:
            if fallback is _UNSET:
                raise
            return fallback
        option = self.optionxform(option)
        try:
            value = d[option]
        except KeyError:
            if fallback is _UNSET:
                raise NoOptionError(option, section) from None
            return fallback
        if raw or value is None:
            return value
        return self._interpolation.before_get(self, section, option, value, d)

    def _get(self, section, conv, option, **kwargs):
        return conv(self.get(section, option, **kwargs))

    def _get_conv(self, section, option, conv, *, raw=False, vars=None, fallback=_UNSET, **kwargs):
        try:
            return self._get(section, conv, option, raw=raw, vars=vars, **kwargs)
        except (NoSectionError, NoOptionError):
            if fallback is _UNSET:
                raise
            return fallback

    def getint(self, section, option, *, raw=False, vars=None, fallback=_UNSET, **kwargs):
        return self._get_conv(section, option, int, raw=raw, vars=vars, fallback=fallback, **kwargs)

    def getfloat(self, section, option, *, raw=False, vars=None, fallback=_UNSET, **kwargs):
        return self._get_conv(section, option, float, raw=raw, vars=vars, fallback=fallback, **kwargs)

    def getboolean(self, section, option, *, raw=False, vars=None, fallback=_UNSET, **kwargs):
        return self._get_conv(section, option, self._convert_to_boolean, raw=raw, vars=vars, fallback=fallback, **kwargs)

    def __getattr__(self, name):
        if name.startswith("get") and name[3:] in vars(self).get("_converters", {}):
            conv = self._converters[name[3:]]

            def getter(section, option, *, raw=False, vars=None, fallback=_UNSET, **kwargs):
                return self._get_conv(section, option, conv, raw=raw, vars=vars, fallback=fallback, **kwargs)

            return getter
        raise AttributeError(f"'{type(self).__name__}' object has no attribute '{name}'")

    @property
    def converters(self):
        return self._converters

    def items(self, section=_UNSET, raw=False, vars=None):
        if section is _UNSET:
            return [(name, self._proxies[name]) for name in [self.default_section] + self.sections()]
        d = dict(self._defaults)
        try:
            d.update(self._sections[section])
        except KeyError:
            if section != self.default_section:
                raise NoSectionError(section) from None
        orig_keys = list(d.keys())
        if vars:
            for key, value in vars.items():
                d[self.optionxform(key)] = value
        if raw:
            return [(option, d[option]) for option in orig_keys]
        return [(option, self._interpolation.before_get(self, section, option, d[option], d) if d[option] is not None else None) for option in orig_keys]

    def popitem(self):
        for key in self.sections():
            value = self[key]
            del self[key]
            return key, value
        raise KeyError

    def optionxform(self, optionstr):
        return optionstr.lower()

    def has_option(self, section, option):
        if not section or section == self.default_section:
            option = self.optionxform(option)
            return option in self._defaults
        if section not in self._sections:
            return False
        option = self.optionxform(option)
        return option in self._sections[section] or option in self._defaults

    def set(self, section, option, value=None):
        if value:
            value = self._interpolation.before_set(self, section, option, value)
        if not section or section == self.default_section:
            sectdict = self._defaults
        else:
            try:
                sectdict = self._sections[section]
            except KeyError:
                raise NoSectionError(section) from None
        sectdict[self.optionxform(option)] = value

    def write(self, fp, space_around_delimiters=True):
        d = f" {self._delimiters[0]} " if space_around_delimiters else self._delimiters[0]
        if self._defaults:
            self._write_section(fp, self.default_section, self._defaults.items(), d)
        for section in self._sections:
            self._write_section(fp, section, self._sections[section].items(), d)

    def _write_section(self, fp, section_name, section_items, delimiter):
        fp.write(f"[{section_name}]\n")
        for key, value in section_items:
            value = self._interpolation.before_write(self, section_name, key, value)
            if value is not None or not self._allow_no_value:
                value = delimiter + str(value).replace("\n", "\n\t")
            else:
                value = ""
            fp.write(f"{key}{value}\n")
        fp.write("\n")

    def remove_option(self, section, option):
        if not section or section == self.default_section:
            sectdict = self._defaults
        else:
            try:
                sectdict = self._sections[section]
            except KeyError:
                raise NoSectionError(section) from None
        option = self.optionxform(option)
        existed = option in sectdict
        if existed:
            del sectdict[option]
        return existed

    def remove_section(self, section):
        existed = section in self._sections
        if existed:
            del self._sections[section]
            del self._proxies[section]
        return existed

    def __getitem__(self, key):
        if key != self.default_section and not self.has_section(key):
            raise KeyError(key)
        return self._proxies[key]

    def __setitem__(self, key, value):
        if key in self and self[key] is value:
            return
        if key == self.default_section:
            self._defaults.clear()
        elif key in self._sections:
            self._sections[key].clear()
        self.read_dict({key: value})

    def __delitem__(self, key):
        if key == self.default_section:
            raise ValueError("Cannot remove the default section.")
        if not self.has_section(key):
            raise KeyError(key)
        self.remove_section(key)

    def __contains__(self, key):
        return key == self.default_section or self.has_section(key)

    def __len__(self):
        return len(self._sections) + 1

    def __iter__(self):
        return iter([self.default_section] + list(self._sections.keys()))

    def keys(self):
        return list(self)

    def values(self):
        return [self[k] for k in self]

    def _read(self, fp, fpname):
        elements_added = set()
        cursect = None
        sectname = None
        optname = None
        indent_level = 0
        e = None
        lines = fp.read().splitlines(True) if hasattr(fp, "read") else list(fp)
        for lineno, line in enumerate(lines, start=1):
            comment_start = None
            for prefix in self._inline_comment_prefixes:
                idx = -1
                while True:
                    idx = line.find(prefix, idx + 1)
                    if idx < 0:
                        break
                    if idx == 0 or line[idx - 1].isspace():
                        comment_start = idx if comment_start is None else min(comment_start, idx)
                        break
            for prefix in self._comment_prefixes:
                if line.strip().startswith(prefix):
                    comment_start = 0
                    break
            value = line[:comment_start].strip() if comment_start is not None else line.strip()
            if not value:
                if self._empty_lines_in_values:
                    if comment_start is None and cursect is not None and optname and cursect[optname] is not None:
                        cursect[optname].append("")
                else:
                    indent_level = 10**9
                continue
            cur_indent_level = len(line) - len(line.lstrip())
            if cursect is not None and optname and cur_indent_level > indent_level:
                if cursect[optname] is None:
                    raise MultilineContinuationError(fpname, lineno, line)
                cursect[optname].append(value)
                continue
            indent_level = cur_indent_level
            m = re.match(r"\[(?P<header>.+)\]", value)
            if m:
                sectname = m.group("header")
                if sectname in self._sections:
                    if self._strict and sectname in elements_added:
                        raise DuplicateSectionError(sectname, fpname, lineno)
                    cursect = self._sections[sectname]
                    elements_added.add(sectname)
                elif sectname == self.default_section:
                    cursect = self._defaults
                else:
                    cursect = self._dict()
                    self._sections[sectname] = cursect
                    self._proxies[sectname] = SectionProxy(self, sectname)
                    elements_added.add(sectname)
                optname = None
                continue
            if cursect is None:
                raise MissingSectionHeaderError(fpname, lineno, line)
            pos = -1
            for delim in self._delimiters:
                p = value.find(delim)
                if p >= 0 and (pos < 0 or p < pos):
                    pos = p
            if pos > 0:
                delim = next(d for d in self._delimiters if value.startswith(d, pos))
                optname = self.optionxform(value[:pos].rstrip())
                optval = value[pos + len(delim) :].strip()
                if self._strict and (sectname, optname) in elements_added:
                    raise DuplicateOptionError(sectname, optname, fpname, lineno)
                elements_added.add((sectname, optname))
                cursect[optname] = [optval]
            elif self._allow_no_value and pos < 0:
                optname = self.optionxform(value.rstrip())
                if self._strict and (sectname, optname) in elements_added:
                    raise DuplicateOptionError(sectname, optname, fpname, lineno)
                elements_added.add((sectname, optname))
                cursect[optname] = None
            else:
                e = e or ParsingError(fpname)
                e.append(lineno, line)
        self._join_multiline_values()
        if e:
            raise e

    def _join_multiline_values(self):
        all_sections = [(self.default_section, self._defaults)] + list(self._sections.items())
        for section, options in all_sections:
            for name, val in list(options.items()):
                if isinstance(val, list):
                    val = "\n".join(val).rstrip()
                options[name] = self._interpolation.before_read(self, section, name, val)

    def _unify_values(self, section, vars):
        sectiondict = {}
        try:
            sectiondict = self._sections[section]
        except KeyError:
            if section != self.default_section:
                raise NoSectionError(section) from None
        vardict = {}
        if vars:
            for key, value in vars.items():
                if value is not None:
                    value = str(value)
                vardict[self.optionxform(key)] = value
        return _ChainMap(vardict, sectiondict, self._defaults)

    def _convert_to_boolean(self, value):
        if value.lower() not in self.BOOLEAN_STATES:
            raise ValueError(f"Not a boolean: {value}")
        return self.BOOLEAN_STATES[value.lower()]

    def __repr__(self):
        return f"<configparser.{type(self).__name__} object>"


class MultilineContinuationError(ParsingError):
    def __init__(self, filename, lineno, line):
        Error.__init__(self, f"Key without value continued with an indented line.\nfile: {filename!r}, line: {lineno}\n{line!r}")
        self.source = filename
        self.lineno = lineno
        self.line = line
        self.errors = []
        self.args = (filename, lineno, line)


class ConfigParser(RawConfigParser):
    _DEFAULT_INTERPOLATION = BasicInterpolation()

    def set(self, section, option, value=None):
        self._validate_value_types(option=option, value=value)
        super().set(section, option, value)

    def add_section(self, section):
        self._validate_value_types(section=section)
        super().add_section(section)

    def _read_defaults(self, defaults):
        hold_interpolation = self._interpolation
        try:
            self._interpolation = Interpolation()
            self.read_dict({self.default_section: defaults})
        finally:
            self._interpolation = hold_interpolation

    def _validate_value_types(self, *, section="", option="", value=""):
        if not isinstance(section, str):
            raise TypeError("section names must be strings")
        if not isinstance(option, str):
            raise TypeError("option keys must be strings")
        if not self._allow_no_value or value:
            if not isinstance(value, str):
                raise TypeError("option values must be strings")


SafeConfigParser = ConfigParser


class SectionProxy(MutableMapping):
    def __init__(self, parser, name):
        self._parser = parser
        self._name = name

    def __repr__(self):
        return f"<Section: {self._name}>"

    def __getitem__(self, key):
        if not self._parser.has_option(self._name, key):
            raise KeyError(key)
        return self._parser.get(self._name, key)

    def __setitem__(self, key, value):
        if isinstance(self._parser, ConfigParser):
            self._parser._validate_value_types(option=key, value=value)
        return self._parser.set(self._name, key, value)

    def __delitem__(self, key):
        if not (self._parser.has_option(self._name, key) and self._parser.remove_option(self._name, key)):
            raise KeyError(key)

    def __contains__(self, key):
        return self._parser.has_option(self._name, key)

    def __len__(self):
        return len(self._options())

    def __iter__(self):
        return iter(self._options())

    def _options(self):
        if self._name != self._parser.default_section:
            return self._parser.options(self._name)
        return list(self._parser.defaults())

    def keys(self):
        return self._options()

    def items(self):
        return [(k, self[k]) for k in self._options()]

    def values(self):
        return [self[k] for k in self._options()]

    @property
    def parser(self):
        return self._parser

    @property
    def name(self):
        return self._name

    def get(self, option, fallback=None, *, raw=False, vars=None, _impl=None, **kwargs):
        if not _impl:
            _impl = self._parser.get
        return _impl(self._name, option, raw=raw, vars=vars, fallback=fallback, **kwargs)

    def getint(self, option, fallback=None, *, raw=False, vars=None, **kwargs):
        return self._parser.getint(self._name, option, raw=raw, vars=vars, fallback=fallback, **kwargs)

    def getfloat(self, option, fallback=None, *, raw=False, vars=None, **kwargs):
        return self._parser.getfloat(self._name, option, raw=raw, vars=vars, fallback=fallback, **kwargs)

    def getboolean(self, option, fallback=None, *, raw=False, vars=None, **kwargs):
        return self._parser.getboolean(self._name, option, raw=raw, vars=vars, fallback=fallback, **kwargs)


class ConverterMapping:
    def __init__(self, parser):
        self._parser = parser
        self._data = parser._converters

    def __getitem__(self, key):
        return self._data[key]

    def __setitem__(self, key, value):
        self._data[key] = value

    def __contains__(self, key):
        return key in self._data

    def __iter__(self):
        return iter(self._data)

    def __len__(self):
        return len(self._data)
