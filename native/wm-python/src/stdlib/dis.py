"""dis: CPython's disassembler, as names. This interpreter runs a tree of
the source rather than bytecode, so a function has no instructions: the
opcode tables and the Instruction and Bytecode types are here for packages
that read them, and disassembling finds nothing to show."""

import collections
import sys
import types

from opcode import *
from opcode import __all__ as _opcodes_all

__all__ = [
    "code_info", "dis", "disassemble", "distb", "disco", "findlinestarts", "findlabels", "show_code",
    "get_instructions", "Instruction", "Bytecode", "Positions",
] + _opcodes_all
del _opcodes_all

COMPILER_FLAG_NAMES = {
    1: "OPTIMIZED", 2: "NEWLOCALS", 4: "VARARGS", 8: "VARKEYWORDS", 16: "NESTED", 32: "GENERATOR",
    64: "NOFREE", 128: "COROUTINE", 256: "ITERABLE_COROUTINE", 512: "ASYNC_GENERATOR",
}
FUNCTION_ATTR_FLAGS = ("defaults", "kwdefaults", "annotations", "closure")

for _name in ("CACHE", "EXTENDED_ARG", "LOAD_CONST", "RETURN_CONST", "LOAD_GLOBAL", "BINARY_OP", "JUMP_BACKWARD",
              "FOR_ITER", "SEND", "LOAD_ATTR", "LOAD_SUPER_ATTR", "CALL_INTRINSIC_1", "CALL_INTRINSIC_2",
              "LOAD_FAST_LOAD_FAST", "STORE_FAST_LOAD_FAST", "STORE_FAST_STORE_FAST", "SET_FUNCTION_ATTRIBUTE",
              "CONVERT_VALUE", "ENTER_EXECUTOR"):
    globals()[_name] = opmap.get(_name, 0)
del _name
UNKNOWN = "<unknown>"

Positions = collections.namedtuple("Positions", ["lineno", "end_lineno", "col_offset", "end_col_offset"], defaults=[None] * 4)

_Instruction = collections.namedtuple(
    "_Instruction",
    ["opname", "opcode", "arg", "argval", "argrepr", "offset", "start_offset", "starts_line", "line_number", "label", "positions", "cache_info"],
    defaults=[None, None, None],
)


class Instruction(_Instruction):
    """One bytecode instruction, as CPython's dis describes it."""

    @property
    def oparg(self):
        return self.arg

    @property
    def baseopcode(self):
        return self.opcode

    @property
    def baseopname(self):
        return self.opname

    @property
    def cache_offset(self):
        return self.start_offset + 2

    @property
    def end_offset(self):
        return self.cache_offset

    @property
    def jump_target(self):
        return None

    @property
    def is_jump_target(self):
        return self.label is not None


def _get_code_object(x):
    if hasattr(x, "__func__"):
        x = x.__func__
    if hasattr(x, "__code__"):
        x = x.__code__
    elif hasattr(x, "gi_code"):
        x = x.gi_code
    elif hasattr(x, "ag_code"):
        x = x.ag_code
    elif hasattr(x, "cr_code"):
        x = x.cr_code
    if isinstance(x, str):
        x = compile(x, "<disassembly>", "exec")
    if hasattr(x, "co_name") or hasattr(x, "co_flags"):
        return x
    raise TypeError(f"don't know how to disassemble {type(x).__name__} objects")


def pretty_flags(flags):
    """The names of the flags set in a code object's co_flags."""
    names = []
    for i in range(32):
        flag = 1 << i
        if flags & flag:
            names.append(COMPILER_FLAG_NAMES.get(flag, hex(flag)))
            flags ^= flag
            if not flags:
                break
    else:
        names.append(hex(flags))
    return ", ".join(names)


def code_info(x):
    """A description of a code object: name, arguments, flags and names."""
    co = _get_code_object(x)
    lines = [
        f"Name:              {co.co_name}",
        f"Filename:          {getattr(co, 'co_filename', '<unknown>')}",
        f"Argument count:    {getattr(co, 'co_argcount', 0)}",
        f"Positional-only arguments: {getattr(co, 'co_posonlyargcount', 0)}",
        f"Kw-only arguments: {getattr(co, 'co_kwonlyargcount', 0)}",
        f"Number of locals:  {getattr(co, 'co_nlocals', 0)}",
        f"Flags:             {pretty_flags(getattr(co, 'co_flags', 0))}",
    ]
    names = getattr(co, "co_varnames", ())
    if names:
        lines.append("Variable names:")
        lines.extend(f"{i:4}: {n}" for i, n in enumerate(names))
    return "\n".join(lines)


def show_code(co, *, file=None):
    print(code_info(co), file=file)


def get_instructions(x, *, first_line=None, show_caches=None, adaptive=False):
    """The instructions of x's bytecode: there is none here, so nothing."""
    _get_code_object(x)
    return iter(())


def findlinestarts(code):
    """(offset, line) pairs of where each line's bytecode starts: none here."""
    return iter(())


def findlabels(code):
    """The offsets jumps go to: none, as there is no bytecode."""
    return []


def dis(x=None, *, file=None, depth=None, show_caches=False, adaptive=False, show_offsets=False):
    """Print the disassembly of x: there are no instructions to print."""
    if x is not None:
        _get_code_object(x)


def disassemble(co, lasti=-1, *, file=None, show_caches=False, adaptive=False, show_offsets=False):
    pass


disco = disassemble


def distb(tb=None, *, file=None, show_caches=False, adaptive=False, show_offsets=False):
    pass


class Bytecode:
    """The bytecode of a function or code object, iterated as Instructions: none here."""

    def __init__(self, x, *, first_line=None, current_offset=None, show_caches=False, adaptive=False, show_offsets=False):
        self.codeobj = _get_code_object(x)
        self.first_line = first_line if first_line is not None else getattr(self.codeobj, "co_firstlineno", 1)
        self.current_offset = current_offset

    def __iter__(self):
        return iter(())

    def __repr__(self):
        return f"{self.__class__.__name__}({self.codeobj!r})"

    @classmethod
    def from_traceback(cls, tb, *, show_caches=False, adaptive=False):
        raise TypeError("tracebacks carry no frames here")

    def info(self):
        return code_info(self.codeobj)

    def dis(self):
        return ""
