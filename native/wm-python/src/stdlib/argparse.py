"""argparse: command-line parsing, with CPython's rules for matching
arguments and its layout for usage and help text.

A plugin has no command line (sys.argv is ['']), so parse_args() with no
list gives the defaults; parse_args([...]) parses the list it is given.
Errors print the usage and the message to sys.stderr and raise SystemExit,
as CPython's do. FileType cannot open paths here, only "-".
"""

import re as _re
import sys as _sys
import textwrap as _textwrap

__all__ = [
    "ArgumentParser", "ArgumentError", "ArgumentTypeError", "BooleanOptionalAction", "FileType", "HelpFormatter",
    "ArgumentDefaultsHelpFormatter", "RawDescriptionHelpFormatter", "RawTextHelpFormatter", "MetavarTypeHelpFormatter",
    "Namespace", "Action", "ONE_OR_MORE", "OPTIONAL", "PARSER", "REMAINDER", "SUPPRESS", "ZERO_OR_MORE",
]

SUPPRESS = "==SUPPRESS=="
OPTIONAL = "?"
ZERO_OR_MORE = "*"
ONE_OR_MORE = "+"
PARSER = "A..."
REMAINDER = "..."
_UNRECOGNIZED_ARGS_ATTR = "_unrecognized_args"


class _AttributeHolder:
    def __repr__(self):
        type_name = type(self).__name__
        arg_strings = []
        star_args = {}
        for arg in self._get_args():
            arg_strings.append(repr(arg))
        for name, value in self._get_kwargs():
            if name.isidentifier():
                arg_strings.append(f"{name}={value!r}")
            else:
                star_args[name] = value
        if star_args:
            arg_strings.append(f"**{star_args!r}")
        return f"{type_name}({', '.join(arg_strings)})"

    def _get_kwargs(self):
        return list(vars(self).items())

    def _get_args(self):
        return []


class Namespace(_AttributeHolder):
    def __init__(self, **kwargs):
        for name, value in kwargs.items():
            setattr(self, name, value)

    def __eq__(self, other):
        if not isinstance(other, Namespace):
            return NotImplemented
        return vars(self) == vars(other)

    def __contains__(self, key):
        return key in vars(self)


def _get_action_name(argument):
    if argument is None:
        return None
    if argument.option_strings:
        return "/".join(argument.option_strings)
    if argument.metavar not in (None, SUPPRESS):
        metavar = argument.metavar
        if not isinstance(metavar, tuple):
            return metavar
        if argument.nargs == ZERO_OR_MORE and len(metavar) == 2:
            return "%s[, %s]" % metavar
        if argument.nargs == ONE_OR_MORE:
            return "%s[, %s]" % metavar
        return ", ".join(metavar)
    if argument.dest not in (None, SUPPRESS):
        return argument.dest
    if argument.choices:
        return "{%s}" % ",".join(argument.choices)
    return None


class ArgumentError(Exception):
    def __init__(self, argument, message):
        self.argument_name = _get_action_name(argument)
        self.message = message
        Exception.__init__(self, self.__str__())

    def __str__(self):
        if self.argument_name is None:
            return self.message
        return f"argument {self.argument_name}: {self.message}"


class ArgumentTypeError(Exception):
    pass


# MARK: - Actions


class Action(_AttributeHolder):
    def __init__(self, option_strings, dest, nargs=None, const=None, default=None, type=None, choices=None, required=False, help=None, metavar=None, deprecated=False):
        self.option_strings = option_strings
        self.dest = dest
        self.nargs = nargs
        self.const = const
        self.default = default
        self.type = type
        self.choices = choices
        self.required = required
        self.help = help
        self.metavar = metavar
        self.deprecated = deprecated

    def _get_kwargs(self):
        names = ["option_strings", "dest", "nargs", "const", "default", "type", "choices", "required", "help", "metavar", "deprecated"]
        return [(name, getattr(self, name)) for name in names]

    def format_usage(self):
        return self.option_strings[0]

    def __call__(self, parser, namespace, values, option_string=None):
        raise NotImplementedError(".__call__() not defined")


class BooleanOptionalAction(Action):
    def __init__(self, option_strings, dest, default=None, type=None, choices=None, required=False, help=None, metavar=None, deprecated=False):
        _option_strings = []
        for option_string in option_strings:
            _option_strings.append(option_string)
            if option_string.startswith("--"):
                if option_string.startswith("--no-"):
                    raise ValueError(f"invalid option name {option_string!r} for BooleanOptionalAction")
                _option_strings.append("--no-" + option_string[2:])
        super().__init__(_option_strings, dest, nargs=0, default=default, type=type, choices=choices, required=required, help=help, metavar=metavar, deprecated=deprecated)

    def __call__(self, parser, namespace, values, option_string=None):
        if option_string in self.option_strings:
            setattr(namespace, self.dest, not option_string.startswith("--no-"))

    def format_usage(self):
        return " | ".join(self.option_strings)


class _StoreAction(Action):
    def __init__(self, option_strings, dest, nargs=None, const=None, default=None, type=None, choices=None, required=False, help=None, metavar=None, deprecated=False):
        if nargs == 0:
            raise ValueError("nargs for store actions must be != 0; if you have nothing to store, actions such as store true or store const may be more appropriate")
        if const is not None and nargs != OPTIONAL:
            raise ValueError(f"nargs must be {OPTIONAL!r} to supply const")
        super().__init__(option_strings, dest, nargs, const, default, type, choices, required, help, metavar, deprecated)

    def __call__(self, parser, namespace, values, option_string=None):
        setattr(namespace, self.dest, values)


class _StoreConstAction(Action):
    def __init__(self, option_strings, dest, const=None, default=None, required=False, help=None, metavar=None, deprecated=False):
        super().__init__(option_strings, dest, nargs=0, const=const, default=default, required=required, help=help, deprecated=deprecated)

    def __call__(self, parser, namespace, values, option_string=None):
        setattr(namespace, self.dest, self.const)


class _StoreTrueAction(_StoreConstAction):
    def __init__(self, option_strings, dest, default=False, required=False, help=None, deprecated=False):
        super().__init__(option_strings, dest, const=True, default=default, required=required, help=help, deprecated=deprecated)


class _StoreFalseAction(_StoreConstAction):
    def __init__(self, option_strings, dest, default=True, required=False, help=None, deprecated=False):
        super().__init__(option_strings, dest, const=False, default=default, required=required, help=help, deprecated=deprecated)


def _copy_items(items):
    if items is None:
        return []
    if type(items) is list:
        return items[:]
    import copy

    return copy.copy(items)


class _AppendAction(Action):
    def __init__(self, option_strings, dest, nargs=None, const=None, default=None, type=None, choices=None, required=False, help=None, metavar=None, deprecated=False):
        if nargs == 0:
            raise ValueError("nargs for append actions must be != 0; if arg strings are not supplying the value to append, the append const action may be more appropriate")
        if const is not None and nargs != OPTIONAL:
            raise ValueError(f"nargs must be {OPTIONAL!r} to supply const")
        super().__init__(option_strings, dest, nargs, const, default, type, choices, required, help, metavar, deprecated)

    def __call__(self, parser, namespace, values, option_string=None):
        items = _copy_items(getattr(namespace, self.dest, None))
        items.append(values)
        setattr(namespace, self.dest, items)


class _AppendConstAction(Action):
    def __init__(self, option_strings, dest, const=None, default=None, required=False, help=None, metavar=None, deprecated=False):
        super().__init__(option_strings, dest, nargs=0, const=const, default=default, required=required, help=help, metavar=metavar, deprecated=deprecated)

    def __call__(self, parser, namespace, values, option_string=None):
        items = _copy_items(getattr(namespace, self.dest, None))
        items.append(self.const)
        setattr(namespace, self.dest, items)


class _CountAction(Action):
    def __init__(self, option_strings, dest, default=None, required=False, help=None, deprecated=False):
        super().__init__(option_strings, dest, nargs=0, default=default, required=required, help=help, deprecated=deprecated)

    def __call__(self, parser, namespace, values, option_string=None):
        count = getattr(namespace, self.dest, None)
        if count is None:
            count = 0
        setattr(namespace, self.dest, count + 1)


class _HelpAction(Action):
    def __init__(self, option_strings, dest=SUPPRESS, default=SUPPRESS, help=None, deprecated=False):
        super().__init__(option_strings, dest, default=default, nargs=0, help=help, deprecated=deprecated)

    def __call__(self, parser, namespace, values, option_string=None):
        parser.print_help()
        parser.exit()


class _VersionAction(Action):
    def __init__(self, option_strings, version=None, dest=SUPPRESS, default=SUPPRESS, help=None, deprecated=False):
        if help is None:
            help = "show program's version number and exit"
        super().__init__(option_strings, dest, default=default, nargs=0, help=help, deprecated=deprecated)
        self.version = version

    def __call__(self, parser, namespace, values, option_string=None):
        version = self.version
        if version is None:
            version = parser.version
        formatter = parser._get_formatter()
        formatter.add_text(version)
        parser._print_message(formatter.format_help(), _sys.stdout)
        parser.exit()


class _ChoicesPseudoAction(Action):
    def __init__(self, name, aliases, help):
        metavar = dest = name
        if aliases:
            metavar += " (%s)" % ", ".join(aliases)
        super().__init__(option_strings=[], dest=dest, help=help, metavar=metavar)


class _SubParsersAction(Action):
    def __init__(self, option_strings, prog, parser_class, dest=SUPPRESS, required=False, help=None, metavar=None):
        self._prog_prefix = prog
        self._parser_class = parser_class
        self._name_parser_map = {}
        self._choices_actions = []
        self._deprecated = set()
        super().__init__(option_strings=option_strings, dest=dest, nargs=PARSER, choices=self._name_parser_map, required=required, help=help, metavar=metavar)

    def add_parser(self, name, *, deprecated=False, **kwargs):
        if kwargs.get("prog") is None:
            kwargs["prog"] = f"{self._prog_prefix} {name}"
        aliases = kwargs.pop("aliases", ())
        if name in self._name_parser_map:
            raise ArgumentError(self, f"conflicting subparser: {name}")
        for alias in aliases:
            if alias in self._name_parser_map:
                raise ArgumentError(self, f"conflicting subparser alias: {alias}")
        if "help" in kwargs:
            help = kwargs.pop("help")
            self._choices_actions.append(_ChoicesPseudoAction(name, aliases, help))
        parser = self._parser_class(**kwargs)
        self._name_parser_map[name] = parser
        for alias in aliases:
            self._name_parser_map[alias] = parser
        return parser

    def _get_subactions(self):
        return self._choices_actions

    def __call__(self, parser, namespace, values, option_string=None):
        parser_name = values[0]
        arg_strings = values[1:]
        if self.dest is not SUPPRESS:
            setattr(namespace, self.dest, parser_name)
        try:
            subparser = self._name_parser_map[parser_name]
        except KeyError:
            choices = ", ".join(self._name_parser_map)
            raise ArgumentError(self, f"unknown parser {parser_name!r} (choices: {choices})") from None
        subnamespace, arg_strings = subparser.parse_known_args(arg_strings, None)
        for key, value in vars(subnamespace).items():
            setattr(namespace, key, value)
        if arg_strings:
            if not hasattr(namespace, _UNRECOGNIZED_ARGS_ATTR):
                setattr(namespace, _UNRECOGNIZED_ARGS_ATTR, [])
            getattr(namespace, _UNRECOGNIZED_ARGS_ATTR).extend(arg_strings)


class _ExtendAction(_AppendAction):
    def __call__(self, parser, namespace, values, option_string=None):
        items = _copy_items(getattr(namespace, self.dest, None))
        items.extend(values)
        setattr(namespace, self.dest, items)


class FileType:
    def __init__(self, mode="r", bufsize=-1, encoding=None, errors=None):
        self._mode = mode
        self._bufsize = bufsize
        self._encoding = encoding
        self._errors = errors

    def __call__(self, string):
        if string == "-":
            if "r" in self._mode:
                return _sys.stdin
            if any(c in self._mode for c in "wax"):
                return _sys.stdout
            raise ValueError(f'argument "-" with mode {self._mode!r}')
        raise ArgumentTypeError(f"can't open '{string}': plugins have no file paths")

    def __repr__(self):
        return f"{type(self).__name__}({self._mode!r})"


# MARK: - Help formatting


class HelpFormatter:
    def __init__(self, prog, indent_increment=2, max_help_position=24, width=None):
        if width is None:
            width = 80
            width -= 2
        self._prog = prog
        self._indent_increment = indent_increment
        self._max_help_position = min(max_help_position, max(width - 20, indent_increment * 2))
        self._width = width
        self._current_indent = 0
        self._action_max_length = 0
        self._blocks = []
        self._whitespace_matcher = _re.compile(r"\s+", _re.ASCII)
        self._long_break_matcher = _re.compile(r"\n\n\n+")

    # The parser hands sections and items over; here they are kept as blocks
    # and laid out when format_help() is asked for.
    def start_section(self, heading):
        self._blocks.append(("start", heading))

    def end_section(self):
        self._blocks.append(("end", None))

    def add_text(self, text):
        if text is not SUPPRESS and text is not None:
            self._blocks.append(("text", text))

    def add_usage(self, usage, actions, groups, prefix=None):
        if usage is not SUPPRESS:
            self._blocks.append(("usage", (usage, list(actions), list(groups), prefix)))

    def add_argument(self, action):
        if action.help is not SUPPRESS:
            invocations = [self._format_action_invocation(action)]
            for subaction in self._iter_indented_subactions_flat(action):
                invocations.append(" " * self._indent_increment + self._format_action_invocation(subaction))
            self._blocks.append(("action", action))
            depth = sum(1 for kind, _ in self._blocks if kind == "start") - sum(1 for kind, _ in self._blocks if kind == "end")
            indent = self._indent_increment * max(depth, 0)
            invocation_length = max(len(s) for s in invocations) + indent
            self._action_max_length = max(self._action_max_length, invocation_length)

    def add_arguments(self, actions):
        for action in actions:
            self.add_argument(action)

    def _iter_indented_subactions_flat(self, action):
        try:
            get_subactions = action._get_subactions
        except AttributeError:
            return []
        return list(get_subactions())

    def format_help(self):
        out = []
        # Sections nest: each keeps its own parts, and one with nothing in it vanishes.
        stack = [(None, out)]
        for kind, value in self._blocks:
            if kind == "start":
                self._current_indent += self._indent_increment
                stack.append((value, []))
            elif kind == "end":
                self._current_indent -= self._indent_increment
                heading, parts = stack.pop()
                body = "".join(parts)
                if body:
                    head = f"{' ' * self._current_indent}{heading}:\n" if heading is not SUPPRESS and heading is not None else ""
                    stack[-1][1].append("\n" + head + body + "\n")
            elif kind == "text":
                stack[-1][1].append(self._format_text(value))
            elif kind == "usage":
                stack[-1][1].append(self._format_usage(*value))
            elif kind == "action":
                stack[-1][1].append(self._format_action(value))
        text = "".join(out)
        if text:
            text = self._long_break_matcher.sub("\n\n", text)
            text = text.strip("\n") + "\n"
        return text

    def _format_usage(self, usage, actions, groups, prefix):
        if prefix is None:
            prefix = "usage: "
        if usage is not None:
            usage = usage % {"prog": self._prog}
        elif usage is None and not actions:
            usage = self._prog
        else:
            prog = self._prog
            optionals = [a for a in actions if a.option_strings]
            positionals = [a for a in actions if not a.option_strings]
            parts = self._get_actions_usage_parts(optionals + positionals, groups)
            usage = " ".join([s for s in [prog, " ".join(parts)] if s])
            text_width = self._width - self._current_indent
            if len(prefix) + len(usage) > text_width:
                opt_parts = self._get_actions_usage_parts(optionals, groups)
                pos_parts = self._get_actions_usage_parts(positionals, groups)

                def get_lines(parts, indent, prefix=None):
                    lines = []
                    line = []
                    indent_length = len(indent)
                    line_len = len(prefix) - 1 if prefix is not None else indent_length - 1
                    for part in parts:
                        if line_len + 1 + len(part) > text_width and line:
                            lines.append(indent + " ".join(line))
                            line = []
                            line_len = indent_length - 1
                        line.append(part)
                        line_len += len(part) + 1
                    if line:
                        lines.append(indent + " ".join(line))
                    if prefix is not None:
                        lines[0] = lines[0][len(indent) :]
                    return lines

                if len(prefix) + len(prog) <= 0.75 * text_width:
                    indent = " " * (len(prefix) + len(prog) + 1)
                    if opt_parts:
                        lines = get_lines([prog] + opt_parts, indent, prefix)
                        lines.extend(get_lines(pos_parts, indent))
                    elif pos_parts:
                        lines = get_lines([prog] + pos_parts, indent, prefix)
                    else:
                        lines = [prog]
                else:
                    indent = " " * len(prefix)
                    parts = opt_parts + pos_parts
                    lines = get_lines(parts, indent)
                    if len(lines) > 1:
                        lines = []
                        lines.extend(get_lines(opt_parts, indent))
                        lines.extend(get_lines(pos_parts, indent))
                    lines = [prog] + lines
                usage = "\n".join(lines)
        return f"{prefix}{usage}\n\n"

    def _get_actions_usage_parts(self, actions, groups):
        group_actions = set()
        inserts = {}
        for group in groups:
            if not group._group_actions:
                raise ValueError(f"empty group {group}")
            if all(action.help is SUPPRESS for action in group._group_actions):
                continue
            indices = [actions.index(item) for item in group._group_actions if item in actions]
            if len(indices) != len(group._group_actions):
                continue
            start = min(indices)
            end = start + len(group._group_actions)
            if set(actions[start:end]) == set(group._group_actions):
                group_actions.update(group._group_actions)
                inserts[start, end] = group
        parts = []
        for action in actions:
            if action.help is SUPPRESS:
                part = None
            elif not action.option_strings:
                part = self._format_args(action, self._get_default_metavar_for_positional(action))
                if action in group_actions and part[:1] == "[" and part[-1:] == "]":
                    part = part[1:-1]
            else:
                option_string = action.option_strings[0]
                if action.nargs == 0:
                    part = action.format_usage()
                else:
                    args_string = self._format_args(action, self._get_default_metavar_for_optional(action))
                    part = f"{option_string} {args_string}"
                if not action.required and action not in group_actions:
                    part = f"[{part}]"
            parts.append(part)
        inserted_separators_indices = set()
        for start, end in sorted(inserts, reverse=True):
            group = inserts[start, end]
            group_parts = [item for item in parts[start:end] if item is not None]
            group_size = len(group_parts)
            if group.required:
                open_, close = ("(", ")") if group_size > 1 else ("", "")
            else:
                open_, close = "[", "]"
            group_parts[0] = open_ + group_parts[0]
            group_parts[-1] = group_parts[-1] + close
            for i, part in enumerate(group_parts[:-1], start=start):
                if i not in inserted_separators_indices:
                    parts[i] = part + " |"
                    inserted_separators_indices.add(i)
            parts[start + group_size - 1] = group_parts[-1]
            for i in range(start + group_size, end):
                parts[i] = None
        return [item for item in parts if item is not None]

    def _format_text(self, text):
        if "%(prog)" in text:
            text = text % {"prog": self._prog}
        text_width = max(self._width - self._current_indent, 11)
        indent = " " * self._current_indent
        return self._fill_text(text, text_width, indent) + "\n\n"

    def _format_action(self, action, extra_indent=0):
        indent = self._current_indent + extra_indent
        help_position = min(self._action_max_length + 2, self._max_help_position)
        help_width = max(self._width - help_position, 11)
        action_width = help_position - indent - 2
        action_header = self._format_action_invocation(action)
        if not action.help:
            action_header = f"{' ' * indent}{action_header}\n"
            indent_first = 0
        elif len(action_header) <= action_width:
            action_header = f"{' ' * indent}{action_header.ljust(action_width)}  "
            indent_first = 0
        else:
            action_header = f"{' ' * indent}{action_header}\n"
            indent_first = help_position
        parts = [action_header]
        if action.help and action.help.strip():
            help_text = self._expand_help(action)
            if help_text:
                help_lines = self._split_lines(help_text, help_width)
                parts.append(f"{' ' * indent_first}{help_lines[0]}\n")
                for line in help_lines[1:]:
                    parts.append(f"{' ' * help_position}{line}\n")
        elif not action_header.endswith("\n"):
            parts.append("\n")
        for subaction in self._iter_indented_subactions_flat(action):
            parts.append(self._format_action(subaction, extra_indent + self._indent_increment))
        return "".join(parts)

    def _format_action_invocation(self, action):
        if not action.option_strings:
            default = self._get_default_metavar_for_positional(action)
            return " ".join(self._metavar_formatter(action, default)(1))
        if action.nargs == 0:
            return ", ".join(action.option_strings)
        default = self._get_default_metavar_for_optional(action)
        args_string = self._format_args(action, default)
        return ", ".join(action.option_strings) + " " + args_string

    def _metavar_formatter(self, action, default_metavar):
        if action.metavar is not None:
            result = action.metavar
        elif action.choices is not None:
            result = "{%s}" % ",".join([str(choice) for choice in action.choices])
        else:
            result = default_metavar

        def format(tuple_size):
            if isinstance(result, tuple):
                return result
            return (result,) * tuple_size

        return format

    def _format_args(self, action, default_metavar):
        get_metavar = self._metavar_formatter(action, default_metavar)
        if action.nargs is None:
            return "%s" % get_metavar(1)
        if action.nargs == OPTIONAL:
            return "[%s]" % get_metavar(1)
        if action.nargs == ZERO_OR_MORE:
            metavar = get_metavar(1)
            if len(metavar) == 2:
                return "[%s [%s ...]]" % metavar
            return "[%s ...]" % metavar
        if action.nargs == ONE_OR_MORE:
            return "%s [%s ...]" % get_metavar(2)
        if action.nargs == REMAINDER:
            return "..."
        if action.nargs == PARSER:
            return "%s ..." % get_metavar(1)
        if action.nargs == SUPPRESS:
            return ""
        try:
            formats = ["%s" for _ in range(action.nargs)]
        except TypeError:
            raise ValueError("invalid nargs value") from None
        return " ".join(formats) % get_metavar(action.nargs)

    def _expand_help(self, action):
        params = dict(vars(action), prog=self._prog)
        for name in list(params):
            if params[name] is SUPPRESS:
                del params[name]
        for name in list(params):
            value = params[name]
            if hasattr(value, "__name__") and not isinstance(value, str):
                params[name] = value.__name__
        if params.get("choices") is not None:
            params["choices"] = ", ".join([str(c) for c in params["choices"]])
        return self._get_help_string(action) % params

    def _split_lines(self, text, width):
        text = self._whitespace_matcher.sub(" ", text).strip()
        return _textwrap.wrap(text, width)

    def _fill_text(self, text, width, indent):
        text = self._whitespace_matcher.sub(" ", text).strip()
        return _textwrap.fill(text, width, initial_indent=indent, subsequent_indent=indent)

    def _get_help_string(self, action):
        return action.help

    def _get_default_metavar_for_optional(self, action):
        return action.dest.upper()

    def _get_default_metavar_for_positional(self, action):
        return action.dest


class RawDescriptionHelpFormatter(HelpFormatter):
    def _fill_text(self, text, width, indent):
        return "".join(indent + line for line in text.splitlines(keepends=True))


class RawTextHelpFormatter(RawDescriptionHelpFormatter):
    def _split_lines(self, text, width):
        return text.splitlines()


class ArgumentDefaultsHelpFormatter(HelpFormatter):
    def _get_help_string(self, action):
        help = action.help
        if help is None:
            help = ""
        if "%(default)" not in help and action.default is not SUPPRESS:
            if action.option_strings or action.nargs in (OPTIONAL, ZERO_OR_MORE):
                help += " (default: %(default)s)"
        return help


class MetavarTypeHelpFormatter(HelpFormatter):
    def _get_default_metavar_for_optional(self, action):
        return action.type.__name__

    def _get_default_metavar_for_positional(self, action):
        return action.type.__name__


# MARK: - Containers and the parser


class _ActionsContainer:
    def __init__(self, description, prefix_chars, argument_default, conflict_handler):
        self.description = description
        self.argument_default = argument_default
        self.prefix_chars = prefix_chars
        self.conflict_handler = conflict_handler
        self._registries = {}
        for name, cls in (
            (None, _StoreAction), ("store", _StoreAction), ("store_const", _StoreConstAction), ("store_true", _StoreTrueAction),
            ("store_false", _StoreFalseAction), ("append", _AppendAction), ("append_const", _AppendConstAction),
            ("count", _CountAction), ("help", _HelpAction), ("version", _VersionAction), ("parsers", _SubParsersAction),
            ("extend", _ExtendAction),
        ):
            self.register("action", name, cls)
        self._actions = []
        self._option_string_actions = {}
        self._action_groups = []
        self._mutually_exclusive_groups = []
        self._defaults = {}
        self._negative_number_matcher = _re.compile(r"-\.?\d")
        self._has_negative_number_optionals = []

    def register(self, registry_name, value, object):
        self._registries.setdefault(registry_name, {})[value] = object

    def _registry_get(self, registry_name, value, default=None):
        return self._registries.get(registry_name, {}).get(value, default)

    def set_defaults(self, **kwargs):
        self._defaults.update(kwargs)
        for action in self._actions:
            if action.dest in kwargs:
                action.default = kwargs[action.dest]

    def get_default(self, dest):
        for action in self._actions:
            if action.dest == dest and action.default is not None:
                return action.default
        return self._defaults.get(dest, None)

    def add_argument(self, *args, **kwargs):
        chars = self.prefix_chars
        if not args or len(args) == 1 and args[0][0] not in chars:
            if args and "dest" in kwargs:
                raise ValueError("dest supplied twice for positional argument, did you mean metavar?")
            kwargs = self._get_positional_kwargs(*args, **kwargs)
        else:
            kwargs = self._get_optional_kwargs(*args, **kwargs)
        if "default" not in kwargs:
            dest = kwargs["dest"]
            if dest in self._defaults:
                kwargs["default"] = self._defaults[dest]
            elif self.argument_default is not None:
                kwargs["default"] = self.argument_default
        action_class = self._pop_action_class(kwargs)
        if not callable(action_class):
            raise ValueError(f'unknown action "{action_class}"')
        action = action_class(**kwargs)
        type_func = self._registry_get("type", action.type, action.type)
        if not callable(type_func):
            raise ValueError(f"{type_func!r} is not callable")
        return self._add_action(action)

    def add_argument_group(self, *args, **kwargs):
        group = _ArgumentGroup(self, *args, **kwargs)
        self._action_groups.append(group)
        return group

    def add_mutually_exclusive_group(self, **kwargs):
        group = _MutuallyExclusiveGroup(self, **kwargs)
        self._mutually_exclusive_groups.append(group)
        return group

    def _add_action(self, action):
        self._check_conflict(action)
        self._actions.append(action)
        action.container = self
        for option_string in action.option_strings:
            self._option_string_actions[option_string] = action
        for option_string in action.option_strings:
            if self._negative_number_matcher.match(option_string):
                if not self._has_negative_number_optionals:
                    self._has_negative_number_optionals.append(True)
        return action

    def _remove_action(self, action):
        self._actions.remove(action)

    def _add_container_actions(self, container):
        title_group_map = {}
        for group in self._action_groups:
            title_group_map[group.title] = group
        group_map = {}
        for group in container._action_groups:
            if group.title not in title_group_map:
                title_group_map[group.title] = self.add_argument_group(title=group.title, description=group.description, conflict_handler=group.conflict_handler)
            for action in group._group_actions:
                group_map[action] = title_group_map[group.title]
        for group in container._mutually_exclusive_groups:
            mutex_group = self.add_mutually_exclusive_group(required=group.required)
            for action in group._group_actions:
                group_map[action] = mutex_group
        for action in container._actions:
            group_map.get(action, self)._add_action(action)

    def _get_positional_kwargs(self, dest, **kwargs):
        if "required" in kwargs:
            raise TypeError("'required' is an invalid argument for positionals")
        if kwargs.get("nargs") not in [OPTIONAL, ZERO_OR_MORE, REMAINDER, SUPPRESS, 0]:
            kwargs["required"] = True
        if kwargs.get("nargs") == ZERO_OR_MORE and "default" not in kwargs:
            kwargs["required"] = True
        return dict(kwargs, dest=dest, option_strings=[])

    def _get_optional_kwargs(self, *args, **kwargs):
        option_strings = []
        long_option_strings = []
        for option_string in args:
            if not option_string[0] in self.prefix_chars:
                raise ValueError(f"invalid option string {option_string!r}: must start with a character {self.prefix_chars!r}")
            option_strings.append(option_string)
            if len(option_string) > 1 and option_string[1] in self.prefix_chars:
                long_option_strings.append(option_string)
        dest = kwargs.pop("dest", None)
        if dest is None:
            dest_option_string = long_option_strings[0] if long_option_strings else option_strings[0]
            dest = dest_option_string.lstrip(self.prefix_chars)
            if not dest:
                raise ValueError(f"dest= is required for options like {option_string!r}")
            dest = dest.replace("-", "_")
        return dict(kwargs, dest=dest, option_strings=option_strings)

    def _pop_action_class(self, kwargs, default=None):
        action = kwargs.pop("action", default)
        return self._registry_get("action", action, action)

    def _get_handler(self):
        handler_func_name = f"_handle_conflict_{self.conflict_handler}"
        try:
            return getattr(self, handler_func_name)
        except AttributeError:
            raise ValueError(f"invalid conflict_resolution value: {self.conflict_handler!r}") from None

    def _check_conflict(self, action):
        confl_optionals = []
        for option_string in action.option_strings:
            if option_string in self._option_string_actions:
                confl_optionals.append((option_string, self._option_string_actions[option_string]))
        if confl_optionals:
            self._get_handler()(action, confl_optionals)

    def _handle_conflict_error(self, action, conflicting_actions):
        message = "conflicting option string: %s" if len(conflicting_actions) == 1 else "conflicting option strings: %s"
        conflict_string = ", ".join([option_string for option_string, action in conflicting_actions])
        raise ArgumentError(action, message % conflict_string)

    def _handle_conflict_resolve(self, action, conflicting_actions):
        for option_string, action in conflicting_actions:
            action.option_strings.remove(option_string)
            self._option_string_actions.pop(option_string, None)
            if not action.option_strings:
                action.container._remove_action(action)


class _ArgumentGroup(_ActionsContainer):
    def __init__(self, container, title=None, description=None, **kwargs):
        kwargs.setdefault("conflict_handler", container.conflict_handler)
        kwargs.setdefault("prefix_chars", container.prefix_chars)
        kwargs.setdefault("argument_default", container.argument_default)
        super().__init__(description=description, **kwargs)
        self.title = title
        self._group_actions = []
        self._registries = container._registries
        self._actions = container._actions
        self._option_string_actions = container._option_string_actions
        self._defaults = container._defaults
        self._has_negative_number_optionals = container._has_negative_number_optionals
        self._mutually_exclusive_groups = container._mutually_exclusive_groups
        self._container = container

    def _add_action(self, action):
        action = super()._add_action(action)
        self._group_actions.append(action)
        return action

    def _remove_action(self, action):
        super()._remove_action(action)
        self._group_actions.remove(action)

    def add_argument_group(self, *args, **kwargs):
        return self._container.add_argument_group(*args, **kwargs)


class _MutuallyExclusiveGroup(_ArgumentGroup):
    def __init__(self, container, required=False):
        super().__init__(container)
        self.required = required
        self._container = container

    def _add_action(self, action):
        if action.required:
            raise ValueError("mutually exclusive arguments must be optional")
        action = self._container._add_action(action)
        self._group_actions.append(action)
        return action

    def _remove_action(self, action):
        self._container._remove_action(action)
        self._group_actions.remove(action)


class ArgumentParser(_AttributeHolder, _ActionsContainer):
    def __init__(
        self,
        prog=None,
        usage=None,
        description=None,
        epilog=None,
        parents=[],
        formatter_class=HelpFormatter,
        prefix_chars="-",
        fromfile_prefix_chars=None,
        argument_default=None,
        conflict_handler="error",
        add_help=True,
        allow_abbrev=True,
        exit_on_error=True,
    ):
        _ActionsContainer.__init__(self, description=description, prefix_chars=prefix_chars, argument_default=argument_default, conflict_handler=conflict_handler)
        if prog is None:
            argv0 = _sys.argv[0] if _sys.argv else ""
            prog = argv0.rsplit("/", 1)[-1]
        self.prog = prog
        self.usage = usage
        self.epilog = epilog
        self.formatter_class = formatter_class
        self.fromfile_prefix_chars = fromfile_prefix_chars
        self.add_help = add_help
        self.allow_abbrev = allow_abbrev
        self.exit_on_error = exit_on_error
        self._positionals = self.add_argument_group("positional arguments")
        self._optionals = self.add_argument_group("options")
        self._subparsers = None

        def identity(string):
            return string

        self.register("type", None, identity)
        default_prefix = "-" if "-" in prefix_chars else prefix_chars[0]
        if self.add_help:
            self.add_argument(default_prefix + "h", default_prefix * 2 + "help", action="help", default=SUPPRESS, help="show this help message and exit")
        for parent in parents:
            if not isinstance(parent, ArgumentParser):
                raise TypeError("parents must be a list of ArgumentParser")
            self._add_container_actions(parent)
            self._defaults.update(parent._defaults)

    def _get_kwargs(self):
        names = ["prog", "usage", "description", "formatter_class", "conflict_handler", "add_help"]
        return [(name, getattr(self, name)) for name in names]

    def add_subparsers(self, **kwargs):
        if self._subparsers is not None:
            raise ArgumentError(None, "cannot have multiple subparser arguments")
        kwargs.setdefault("parser_class", type(self))
        if "title" in kwargs or "description" in kwargs:
            title = kwargs.pop("title", "subcommands")
            description = kwargs.pop("description", None)
            self._subparsers = self.add_argument_group(title, description)
        else:
            self._subparsers = self._positionals
        if kwargs.get("prog") is None:
            formatter = self._get_formatter()
            positionals = self._get_positional_actions()
            groups = self._mutually_exclusive_groups
            formatter.add_usage(None, positionals, groups, "")
            kwargs["prog"] = formatter.format_help().strip()
        parsers_class = self._pop_action_class(kwargs, "parsers")
        action = parsers_class(option_strings=[], **kwargs)
        self._subparsers._add_action(action)
        return action

    def _add_action(self, action):
        if action.option_strings:
            self._optionals._add_action(action)
        else:
            self._positionals._add_action(action)
        return action

    def _get_optional_actions(self):
        return [action for action in self._actions if action.option_strings]

    def _get_positional_actions(self):
        return [action for action in self._actions if not action.option_strings]

    def parse_args(self, args=None, namespace=None):
        args, argv = self.parse_known_args(args, namespace)
        if argv:
            msg = "unrecognized arguments: " + " ".join(argv)
            if self.exit_on_error:
                self.error(msg)
            raise ArgumentError(None, msg)
        return args

    def parse_known_args(self, args=None, namespace=None):
        return self._parse_known_args2(args, namespace, intermixed=False)

    def parse_intermixed_args(self, args=None, namespace=None):
        args, argv = self.parse_known_intermixed_args(args, namespace)
        if argv:
            self.error("unrecognized arguments: " + " ".join(argv))
        return args

    def parse_known_intermixed_args(self, args=None, namespace=None):
        return self._parse_known_args2(args, namespace, intermixed=True)

    def _parse_known_args2(self, args, namespace, intermixed):
        if args is None:
            args = _sys.argv[1:]
        else:
            args = list(args)
        if namespace is None:
            namespace = Namespace()
        for action in self._actions:
            if action.dest is not SUPPRESS and not hasattr(namespace, action.dest) and action.default is not SUPPRESS:
                setattr(namespace, action.dest, action.default)
        for dest in self._defaults:
            if not hasattr(namespace, dest):
                setattr(namespace, dest, self._defaults[dest])
        if self.exit_on_error:
            try:
                namespace, args = self._parse_known_args(args, namespace, intermixed)
            except ArgumentError as err:
                self.error(str(err))
        else:
            namespace, args = self._parse_known_args(args, namespace, intermixed)
        if hasattr(namespace, _UNRECOGNIZED_ARGS_ATTR):
            args.extend(getattr(namespace, _UNRECOGNIZED_ARGS_ATTR))
            delattr(namespace, _UNRECOGNIZED_ARGS_ATTR)
        return namespace, args

    def _parse_known_args(self, arg_strings, namespace, intermixed):
        action_conflicts = {}
        for mutex_group in self._mutually_exclusive_groups:
            group_actions = mutex_group._group_actions
            for i, mutex_action in enumerate(mutex_group._group_actions):
                conflicts = action_conflicts.setdefault(mutex_action, [])
                conflicts.extend(group_actions[:i])
                conflicts.extend(group_actions[i + 1 :])

        option_string_indices = {}
        arg_string_pattern_parts = []
        i = 0
        while i < len(arg_strings):
            arg_string = arg_strings[i]
            if arg_string == "--":
                arg_string_pattern_parts.append("-")
                for _ in arg_strings[i + 1 :]:
                    arg_string_pattern_parts.append("A")
                break
            option_tuple = self._parse_optional(arg_string)
            if option_tuple is None:
                pattern = "A"
            else:
                option_string_indices[i] = option_tuple
                pattern = "O"
            arg_string_pattern_parts.append(pattern)
            i += 1
        arg_strings_pattern = "".join(arg_string_pattern_parts)

        seen_actions = set()
        seen_non_default_actions = set()
        extras = []

        def take_action(action, argument_strings, option_string=None):
            seen_actions.add(action)
            argument_values = self._get_values(action, argument_strings)
            if argument_values is not action.default:
                seen_non_default_actions.add(action)
                for conflict_action in action_conflicts.get(action, []):
                    if conflict_action in seen_non_default_actions:
                        raise ArgumentError(action, f"not allowed with argument {_get_action_name(conflict_action)}")
            if argument_values is not SUPPRESS:
                action(self, namespace, argument_values, option_string)

        def consume_optional(start_index):
            action, option_string, sep, explicit_arg = option_string_indices[start_index]
            action_tuples = []
            stop = start_index + 1
            while True:
                if action is None:
                    extras.append(arg_strings[start_index])
                    return start_index + 1
                if explicit_arg is not None:
                    arg_count = self._match_argument(action, "A")
                    chars = self.prefix_chars
                    if arg_count == 0 and option_string[1] not in chars and explicit_arg != "":
                        if sep or explicit_arg[0] in chars:
                            raise ArgumentError(action, f"ignored explicit argument {explicit_arg!r}")
                        action_tuples.append((action, [], option_string))
                        char = option_string[0]
                        option_string = char + explicit_arg[0]
                        optionals_map = self._option_string_actions
                        if option_string in optionals_map:
                            action = optionals_map[option_string]
                            explicit_arg = explicit_arg[1:]
                            if not explicit_arg:
                                sep = explicit_arg = None
                            elif explicit_arg[0] == "=":
                                sep = "="
                                explicit_arg = explicit_arg[1:]
                            else:
                                sep = ""
                        else:
                            extras.append(char + explicit_arg)
                            stop = start_index + 1
                            break
                    elif arg_count == 1:
                        stop = start_index + 1
                        action_tuples.append((action, [explicit_arg], option_string))
                        break
                    else:
                        raise ArgumentError(action, f"ignored explicit argument {explicit_arg!r}")
                else:
                    start = start_index + 1
                    selected_patterns = arg_strings_pattern[start:]
                    arg_count = self._match_argument(action, selected_patterns)
                    stop = start + arg_count
                    action_tuples.append((action, arg_strings[start:stop], option_string))
                    break
            for action, args, option_string in action_tuples:
                take_action(action, args, option_string)
            return stop

        positionals = self._get_positional_actions()

        def consume_positionals(start_index):
            selected_pattern = arg_strings_pattern[start_index:]
            arg_counts = self._match_arguments_partial(positionals, selected_pattern)
            for action, arg_count in zip(positionals, arg_counts):
                args = arg_strings[start_index : start_index + arg_count]
                if action.nargs == PARSER:
                    if arg_strings_pattern[start_index : start_index + 1] == "-":
                        args.remove("--")
                elif action.nargs != REMAINDER:
                    if arg_strings_pattern.find("-", start_index, start_index + arg_count) >= 0:
                        args.remove("--")
                start_index += arg_count
                take_action(action, args)
            del positionals[: len(arg_counts)]
            return start_index

        start_index = 0
        max_option_string_index = max(option_string_indices) if option_string_indices else -1
        while start_index <= max_option_string_index:
            next_option_string_index = start_index
            while next_option_string_index <= max_option_string_index:
                if next_option_string_index in option_string_indices:
                    break
                next_option_string_index += 1
            if not intermixed and start_index != next_option_string_index:
                positionals_end_index = consume_positionals(start_index)
                if positionals_end_index > start_index:
                    start_index = positionals_end_index
                    continue
                start_index = positionals_end_index
            if start_index not in option_string_indices:
                strings = arg_strings[start_index:next_option_string_index]
                extras.extend(strings)
                start_index = next_option_string_index
            start_index = consume_optional(start_index)

        if not intermixed:
            stop_index = consume_positionals(start_index)
            extras.extend(arg_strings[stop_index:])
        else:
            extras.extend(arg_strings[start_index:])
            leftovers = [s for s in extras if s == "--" or not s[:1] or s[:1] not in self.prefix_chars or self._parse_optional(s) is None]
            others = [s for s in extras if s not in leftovers]
            extras = others
            saved = arg_strings_pattern
            arg_strings = leftovers
            arg_strings_pattern = "".join("-" if s == "--" else "A" for s in leftovers)
            stop_index = consume_positionals(0)
            extras.extend(arg_strings[stop_index:])
            arg_strings_pattern = saved

        required_actions = []
        for action in self._actions:
            if action not in seen_actions:
                if action.required:
                    required_actions.append(_get_action_name(action))
                elif (
                    action.default is not None
                    and isinstance(action.default, str)
                    and hasattr(namespace, action.dest)
                    and action.default is getattr(namespace, action.dest)
                ):
                    setattr(namespace, action.dest, self._get_value(action, action.default))
        if required_actions:
            raise ArgumentError(None, "the following arguments are required: " + ", ".join(required_actions))
        for group in self._mutually_exclusive_groups:
            if group.required:
                if not any(action in seen_non_default_actions for action in group._group_actions):
                    names = [_get_action_name(action) for action in group._group_actions if action.help is not SUPPRESS]
                    raise ArgumentError(None, "one of the arguments " + " ".join(names) + " is required")
        return namespace, extras

    def _match_argument(self, action, arg_strings_pattern):
        nargs_pattern = self._get_nargs_pattern(action)
        match = _re.match(nargs_pattern, arg_strings_pattern)
        if match is None:
            nargs_errors = {None: "expected one argument", OPTIONAL: "expected at most one argument", ONE_OR_MORE: "expected at least one argument"}
            if action.nargs in nargs_errors:
                msg = nargs_errors[action.nargs]
            else:
                msg = f"expected {action.nargs} argument" + ("" if action.nargs == 1 else "s")
            raise ArgumentError(action, msg)
        return len(match.group(1))

    def _match_arguments_partial(self, actions, arg_strings_pattern):
        for i in range(len(actions), 0, -1):
            actions_slice = actions[:i]
            pattern = "".join([self._get_nargs_pattern(action) for action in actions_slice])
            match = _re.match(pattern, arg_strings_pattern)
            if match is not None:
                result = [len(string) for string in match.groups()]
                # Before an option, an optional positional matching nothing waits for later strings.
                if match.end() < len(arg_strings_pattern) and arg_strings_pattern[match.end()] == "O":
                    while result and not result[-1]:
                        del result[-1]
                return result
        return []

    def _parse_optional(self, arg_string):
        if not arg_string:
            return None
        if arg_string[0] not in self.prefix_chars:
            return None
        if arg_string in self._option_string_actions:
            return self._option_string_actions[arg_string], arg_string, None, None
        if len(arg_string) == 1:
            return None
        option_string, sep, explicit_arg = arg_string.partition("=")
        if sep and option_string in self._option_string_actions:
            return self._option_string_actions[option_string], option_string, sep, explicit_arg
        option_tuples = self._get_option_tuples(arg_string)
        if len(option_tuples) > 1:
            options = ", ".join([option_string for action, option_string, sep, explicit_arg in option_tuples])
            self.error(f"ambiguous option: {arg_string} could match {options}")
        elif len(option_tuples) == 1:
            return option_tuples[0]
        if self._negative_number_matcher.match(arg_string):
            if not self._has_negative_number_optionals:
                return None
        if " " in arg_string:
            return None
        return None, arg_string, None, None

    def _get_option_tuples(self, option_string):
        result = []
        chars = self.prefix_chars
        if option_string[0] in chars and option_string[1] in chars:
            if self.allow_abbrev:
                option_prefix, sep, explicit_arg = option_string.partition("=")
                if not sep:
                    sep = explicit_arg = None
                for candidate in self._option_string_actions:
                    if candidate.startswith(option_prefix):
                        result.append((self._option_string_actions[candidate], candidate, sep, explicit_arg))
        elif option_string[0] in chars and option_string[1] not in chars:
            option_prefix = option_string
            short_option_prefix = option_string[:2]
            short_explicit_arg = option_string[2:]
            for candidate in self._option_string_actions:
                if candidate == short_option_prefix:
                    result.append((self._option_string_actions[candidate], candidate, "", short_explicit_arg))
                elif self.allow_abbrev and candidate.startswith(option_prefix):
                    result.append((self._option_string_actions[candidate], candidate, None, None))
        else:
            self.error(f"unexpected option string: {option_string}")
        return result

    def _get_nargs_pattern(self, action):
        nargs = action.nargs
        if nargs is None:
            nargs_pattern = "(-*A-*)"
        elif nargs == OPTIONAL:
            nargs_pattern = "(-*A?-*)"
        elif nargs == ZERO_OR_MORE:
            nargs_pattern = "(-*[A-]*)"
        elif nargs == ONE_OR_MORE:
            nargs_pattern = "(-*A[A-]*)"
        elif nargs == REMAINDER:
            nargs_pattern = "([AO]*)" if action.option_strings else "(.*)"
        elif nargs == PARSER:
            nargs_pattern = "(-*A[-AO]*)"
        elif nargs == SUPPRESS:
            nargs_pattern = "(-*-*)"
        else:
            nargs_pattern = "(-*%s-*)" % "-*".join("A" * nargs)
        if action.option_strings:
            nargs_pattern = nargs_pattern.replace("-*", "")
            nargs_pattern = nargs_pattern.replace("-", "")
        return nargs_pattern

    def _get_values(self, action, arg_strings):
        if not arg_strings and action.nargs == OPTIONAL:
            value = action.const if action.option_strings else action.default
            if isinstance(value, str) and value is not SUPPRESS:
                value = self._get_value(action, value)
        elif not arg_strings and action.nargs == ZERO_OR_MORE and not action.option_strings:
            value = action.default if action.default is not None else []
        elif len(arg_strings) == 1 and action.nargs in (None, OPTIONAL):
            (arg_string,) = arg_strings
            value = self._get_value(action, arg_string)
            self._check_value(action, value)
        elif action.nargs == REMAINDER:
            value = [self._get_value(action, v) for v in arg_strings]
        elif action.nargs == PARSER:
            value = [self._get_value(action, v) for v in arg_strings]
            self._check_value(action, value[0])
        elif action.nargs == SUPPRESS:
            value = SUPPRESS
        else:
            value = [self._get_value(action, v) for v in arg_strings]
            for v in value:
                self._check_value(action, v)
        return value

    def _get_value(self, action, arg_string):
        type_func = self._registry_get("type", action.type, action.type)
        if not callable(type_func):
            raise ArgumentError(action, f"{type_func!r} is not callable")
        try:
            return type_func(arg_string)
        except ArgumentTypeError as err:
            raise ArgumentError(action, str(err)) from None
        except (TypeError, ValueError):
            name = getattr(action.type, "__name__", repr(action.type))
            raise ArgumentError(action, f"invalid {name} value: {arg_string!r}") from None

    def _check_value(self, action, value):
        if action.choices is not None and value not in action.choices:
            choices = ", ".join(map(str, action.choices))
            raise ArgumentError(action, f"invalid choice: {value!r} (choose from {choices})")

    def format_usage(self):
        formatter = self._get_formatter()
        formatter.add_usage(self.usage, self._actions, self._mutually_exclusive_groups)
        return formatter.format_help()

    def format_help(self):
        formatter = self._get_formatter()
        formatter.add_usage(self.usage, self._actions, self._mutually_exclusive_groups)
        formatter.add_text(self.description)
        for action_group in self._action_groups:
            formatter.start_section(action_group.title)
            formatter.add_text(action_group.description)
            formatter.add_arguments(action_group._group_actions)
            formatter.end_section()
        formatter.add_text(self.epilog)
        return formatter.format_help()

    def _get_formatter(self):
        return self.formatter_class(prog=self.prog)

    def print_usage(self, file=None):
        if file is None:
            file = _sys.stdout
        self._print_message(self.format_usage(), file)

    def print_help(self, file=None):
        if file is None:
            file = _sys.stdout
        self._print_message(self.format_help(), file)

    def _print_message(self, message, file=None):
        if message:
            if file is None:
                file = _sys.stderr
            file.write(message)

    def exit(self, status=0, message=None):
        if message:
            self._print_message(message, _sys.stderr)
        _sys.exit(status)

    def error(self, message):
        self.print_usage(_sys.stderr)
        self.exit(2, f"{self.prog}: error: {message}\n")

    def convert_arg_line_to_args(self, arg_line):
        return [arg_line]
