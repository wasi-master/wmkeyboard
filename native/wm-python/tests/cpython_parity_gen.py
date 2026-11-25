"""Writes tests/cpython_parity.rs: each case's expected value is what CPython
itself gives for the snippet's last expression (a str). Run from
native/wm-python after changing a case:

    python3 -I tests/cpython_parity_gen.py tests/cpython_parity.rs
"""
import ast
import subprocess
import sys

CASES = [
    ("sort_is_n_log_n_and_stable", "sorting", r'''
import random
random.seed(5)
xs = [random.random() for _ in range(8000)]
ys = sorted(xs)
pairs = sorted([(1, 'b'), (0, 'a'), (1, 'a')], key=lambda t: t[0], reverse=True)
zs = [3, 1, 2]
zs.sort(reverse=True)
repr((all(ys[i] <= ys[i + 1] for i in range(len(ys) - 1)), pairs, zs, sorted(['b', 'A', 'c'], key=str.lower)))
'''),
    ("sort_refuses_mixed_types", "sorting", r'''
try:
    sorted([1, 'a'])
    r = 'sorted'
except TypeError:
    r = 'TypeError'
r
'''),
    ("equal_numbers_are_one_key", "keys", r'''
d = {1: 'a'}
d[True] = 'b'
d[1.0] = 'c'
repr((d, len({1, True, 1.0}), {1: 'x'} == {1.0: 'x'}, 2.0 in {2}))
'''),
    ("hash_and_eq_decide_keys", "keys", r'''
class V:
    def __init__(self, v):
        self.v = v
    def __eq__(self, o):
        return isinstance(o, V) and self.v == o.v
    def __hash__(self):
        return hash(self.v)
s = {V(1), V(1), V(2)}
d = {V(1): 'x'}
d[V(1)] = 'y'
repr((len(s), d[V(1)], len(d), V(1) in s, V(3) in s, (V(1), 2) in {(V(1), 2)}))
'''),
    ("constant_hash_still_separates_unequal", "keys", r'''
class C:
    def __init__(self, v):
        self.v = v
    def __eq__(self, o):
        return self.v == o.v
    def __hash__(self):
        return 0
s = {C(1), C(2), C(1)}
repr(len(s))
'''),
    ("hash_none_is_unhashable", "keys", r'''
class U:
    __hash__ = None
try:
    {U()}
    r = 'hashed'
except TypeError:
    r = 'TypeError'
r
'''),
    ("frozen_dataclass_and_enum_keys", "keys", r'''
from dataclasses import dataclass
from enum import Enum
@dataclass(frozen=True)
class P:
    x: int
class Color(Enum):
    RED = 1
repr((len({P(1), P(1), P(2)}), {Color.RED: 'r'}[Color.RED]))
'''),
    ("frozenset_builtin", "builtins", r'''
f = frozenset([1, 2])
d = {f: 'x'}
repr((d[frozenset([2, 1])], len(f), 1 in f, f == {1, 2}, sorted(f | {3}), repr(frozenset()), f.issubset({1, 2, 3}), isinstance(f, frozenset)))
'''),
    ("bytearray_builtin", "builtins", r'''
b = bytearray(b'abc')
b[0] = 65
b.append(33)
b.extend([63])
repr((bytes(b), len(b), b[1], b.decode(), b.find(b'c'), list(b[1:3]), repr(bytearray(b'x')), b == bytearray(b'Abc!?')))
'''),
    ("ascii_builtin", "builtins", r'''
repr((ascii('é€😀'), '%a' % 'é', f'{"é"!a}', '%r|%s' % ('q', 'q')))
'''),
    ("keyword_arguments_of_builtins", "builtins", r'''
from collections import OrderedDict
d = dict(a=1)
d.update({'b': 2}, c=3)
repr((d, dict([('x', 1)], y=2), dict(OrderedDict(z=1)), list(enumerate('ab', start=1)), sum([[1], [2]], start=[]), int('ff', base=16), str(b'hi', 'utf-8'), dict.fromkeys('ab', 0), list(map(str.strip, [' a ', 'b ']))))
'''),
    ("dict_and_set_operators", "operators", r'''
a = {'x': 1}
alias = a
a |= {'y': 2}
s = {1}
t = s
s |= {2}
repr((a | {'z': 3}, alias, t, {1} <= {1, 2}, {1, 2} < {1, 2}, {1, 2} >= {2}, {1} > {1}))
'''),
    ("slice_assignment_and_deletion", "assignment", r'''
a = [1, 2, 3, 4, 5]
a[1:3] = [9]
b = [0, 1, 2, 3, 4, 5]
del b[::2]
c = [0, 0, 0, 0]
c[::2] = [1, 1]
e = [1, 2, 3]
e[len(e):] = [4]
repr((a, b, c, e))
'''),
    ("starred_targets", "assignment", r'''
a, *b = [1, 2, 3]
*c, d = 'xyz'
[e, *f, g] = [0, 1, 2, 3, 4]
for x, *rest in [(1, 2, 3)]:
    pass
repr((a, b, c, d, e, f, g, x, rest))
'''),
    ("slice_objects", "assignment", r'''
class S:
    def __getitem__(self, k):
        return k
s = S()
repr((s[1], s[1:3], s[::2].step, [0, 1, 2, 3][slice(1, 3)], slice(1, 5, 2).indices(3), slice(None, None, -1).indices(4)))
'''),
    ("raise_from_keeps_the_cause", "exceptions", r'''
class E(Exception):
    pass
try:
    try:
        raise KeyError('k')
    except KeyError as inner:
        raise ValueError('wrapped') from inner
except ValueError as e:
    first = (str(e), repr(e.__cause__))
try:
    raise E('x') from first and None
except E as e:
    second = e.__cause__
repr((first, second))
'''),
    ("exception_args_and_messages", "exceptions", r'''
try:
    {}['k']
except KeyError as e:
    k = (str(e), repr(e), e.args)
v = ValueError('a', 1)
msgs = []
for f in (lambda: [][1], lambda: 'ab'[5], lambda: (1,)[3], lambda: '1' + 1, lambda: int('x')):
    try:
        f()
    except Exception as e:
        msgs.append(str(e))
repr((k, str(v), v.args, repr(ValueError()), str(KeyError('m')), msgs))
'''),
    ("function_attributes_and_wraps", "functions", r'''
import functools
def deco(fn):
    @functools.wraps(fn)
    def wrapper(*a):
        return fn(*a)
    return wrapper
@deco
def named():
    pass
def f():
    pass
f.calls = 3
repr((named.__name__, named.__wrapped__.__name__, f.calls))
'''),
    ("re_compile_and_patterns", "regex", r'''
import re
p = re.compile(r'(\d+)-(\d+)', re.I)
q = re.compile(r'\w+')
repr((p.findall('1-2 33-4'), p.sub(r'\2-\1', '1-2'), p.pattern, p.groups, bool(p.search('x9-9')), q.match('ab cd').group(), q.findall('ab cd', 2), re.search(q, 'zz').group()))
'''),
    ("regex_lookaround", "regex", r'''
import re
repr((re.findall(r'(?<=\$)\d+(?=\.)', '$12. $3.'), re.sub(r'(?<!\d)0', 'o', '10 0'), bool(re.search(r'foo(?!bar)', 'foobar')), re.findall(r'\b\w+(?=ing\b)', 'going doing'), re.findall(r'(?<=ab|cd)x', 'abx cdx efx')))
'''),
    ("regex_backreferences_and_inline_flags", "regex", r'''
import re
repr((re.findall(r'(\w)\1', 'aabbcd'), re.sub(r'(?P<w>\w+) (?P=w)', r'\g<w>', 'the the end'), bool(re.fullmatch(r'(a|b)\1', 'ab')), re.findall(r'(?i)abc', 'ABC abc'), re.sub(r'(?m)^', '> ', 'a\nb')))
'''),
    ("json_options", "json", r'''
import json
repr((json.dumps({'b': 1, 'a': [1, 2]}, sort_keys=True), json.dumps({'a': 1, 'b': 2}, separators=(',', ':')), json.dumps('é😀', ensure_ascii=True), json.dumps([1], indent='\t'), json.dumps({1: True}), json.dumps('\b')))
'''),
    ("int_literals", "numbers", r'''
repr((int('0x1f', 0), int('0b101', 0), int('1_000'), int('-0o17', 8), int('0x10', 16), int('৩২'), int(' 7 ')))
'''),
    ("format_alternate_form", "numbers", r'''
repr((f'{255:#x}', f'{255:#06x}', format(5, '#b'), f'{8:#o}', f'{3:#.0f}', f'{10:#X}'))
'''),
    ("math_additions", "numbers", r'''
import math
repr((math.isclose(0.1 + 0.2, 0.3), math.isqrt(99), math.perm(5, 2), math.fsum([0.1] * 10), math.factorial(25) > 1e25, math.dist((0, 0), (3, 4)), math.isclose(1, 1.1, rel_tol=0.2)))
'''),
    ("str_method_additions", "strings", r'''
t = str.maketrans('abc', 'xyz', 'd')
repr(('abcd'.translate(t), 'abc'.translate({ord('a'): 'AA', ord('b'): None}), 'a\tb'.expandtabs(4), 'x1'.isidentifier(), '1x'.isidentifier(), 'é'.isascii(), '٣'.isdecimal(), 'a\n'.isprintable(), '{a}-{b}'.format_map({'a': 1, 'b': 2}), str.upper('q')))
'''),
    ("string_repr_quotes", "strings", r'''
repr(["it's", 'say "hi"', 'both \' "', ''])
'''),
    ("self_documenting_fstrings", "strings", r'''
x = 5
name = 'Ann'
f'{x=} {x + 1 = } {x=:>3} {name=!s}'
'''),
    ("itertools_infinite_iterators", "stdlib", r'''
import itertools as it
repr((list(it.islice(it.count(5), 3)), list(it.islice(it.cycle('ab'), 5)), list(zip(it.count(1), 'ab')), list(it.takewhile(lambda x: x < 3, it.count())), list(it.repeat('x', 2)), next(it.count(7, 2)), list(it.islice(it.repeat(0), 2))))
'''),
    ("typing_namedtuple", "stdlib", r'''
from typing import NamedTuple
class Pt(NamedTuple):
    x: int
    y: int = 0
p = Pt(1)
a, b = p
repr((p, p.x, a + b, p._replace(y=5), p == (1, 0), p[1], len(p), Pt._fields))
'''),
]


def cpython(src: str) -> str:
    tree = ast.parse(src)
    last = tree.body[-1]
    assert isinstance(last, ast.Expr)
    g: dict = {}
    exec(compile(ast.Module(tree.body[:-1], []), "case", "exec"), g)
    value = eval(compile(ast.Expression(last.value), "case", "eval"), g)
    assert isinstance(value, str), src
    return value


def rust_raw(s: str) -> str:
    hashes = "#"
    while f'"{hashes}' in s:
        hashes += "#"
    return f'r{hashes}"{s}"{hashes}'


out = ['''//! What Python itself does, where PyMini did otherwise or had nothing: each
//! case's expected text is CPython's answer to the same script. Generated by
//! `tests/cpython_parity_gen.py`; edit the cases there, not here. Sorting, dict keys,
//! frozenset and bytearray, keyword arguments of builtins, slice and starred
//! assignment, `raise … from`, `re.compile` and lookaround, json options,
//! int literals, `#` formats, the math, str and itertools additions.

mod common;
use common::big_stack;

use wm_python::interp::{Limits, Runtime, STANDARD_MODULES};

/// The last line's value, with the Python standard library on, under the
/// default limits a plugin runs with.
fn py(src: &'static str) -> String {
    big_stack(move || {
        let mut rt = Runtime::new(Limits::default(), STANDARD_MODULES);
        rt.enable_python_stdlib();
        match rt.run(src) {
            Ok(v) => v.to_str(),
            Err(e) => panic!("{src}\\n{e}"),
        }
    })
}
''']
for name, _group, src in CASES:
    src = src.strip("\n")
    expected = cpython(src)
    out.append(f'''
#[test]
fn {name}() {{
    assert_eq!(
        py({rust_raw(src)}),
        {rust_raw(expected)}
    );
}}
''')

out.append('''
#[test]
fn print_takes_sep_and_end() {
    let lines = big_stack(|| {
        let mut rt = Runtime::default();
        rt.run("print(1, 2, sep='-', end='!\\\\n')\\nprint('a', end='')\\nprint('b')\\nprint('x', 'y', sep='')\\nprint('z', end='')").unwrap();
        rt.output().to_vec()
    });
    assert_eq!(lines, vec!["1-2!", "ab", "xy", "z"]);
}

#[test]
fn lookbehind_needs_a_fixed_width() {
    let message = big_stack(|| Runtime::default().run("import re\\nre.compile(r'(?<=a+)b')").unwrap_err().to_string());
    assert!(message.contains("look-behind requires fixed-width pattern"), "{message}");
}

#[test]
fn unicode_name_escapes_are_refused_by_name() {
    let message = big_stack(|| Runtime::default().run("'\\\\N{BULLET}'").unwrap_err().to_string());
    assert!(message.contains("\\\\N{BULLET}"), "{message}");
}

#[test]
fn in_place_operators_on_the_same_object() {
    // `s |= s` and `d |= d` read and write one container: no double borrow.
    assert_eq!(py("s = {1}\\ns |= s\\ns &= s\\nd = {'a': 1}\\nd |= d\\nl = [1, 2]\\nl[1:] = l\\nrepr((s, d, l))"), "({1}, {'a': 1}, [1, 1, 2])");
}

#[test]
fn sorting_ten_thousand_items_fits_an_action_budget() {
    // An action gets 9 million steps on the phone; the old insertion sort spent ~25 million here.
    let used = big_stack(|| {
        let mut rt = Runtime::new(Limits { steps: 9_000_000, container_items: 10_000, string_length: 100_000 }, STANDARD_MODULES);
        rt.run("import random\\nrandom.seed(2)\\nxs = [random.random() for _ in range(10000)]\\nys = sorted(xs, key=lambda x: -x)\\nlen(ys)").unwrap();
        rt.steps_used()
    });
    assert!(used < 2_000_000, "{used} steps");
}
''')

open(sys.argv[1], "w").write("".join(out))
print(len(CASES), "cases written")
