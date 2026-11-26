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
    ("whitespace_split_and_strip", "strings", r'''
s = '  a  b c  '
repr((s.split(), s.split(None, 1), s.split(None, 0), s.rsplit(None, 1), s.rsplit(None, 0), 'a\tb\nc'.split(), 'héllo  wörld '.split(), ''.split(), 'a,,b'.split(','), 'a,b,c'.split(',', 1), '  hi \n'.strip(), 'xxhixx'.strip('x'), 'ümlaut  '.rstrip(), '  hi'.lstrip(), 'abc'[1], 'abc'[-1], 'abcdef'[1:5:2], list('héé')))
'''),
    ("integer_comparisons_are_exact", "numbers", r'''
nan = float('nan')
repr((2**53 + 1 == 2**53, 2**62 + 1 > 2**62, sorted([2**60, 2**60 - 1, 3]), max(2**60 - 1, 2**60), nan <= 1, 1 >= nan, nan == nan, 1 < 2.5, True < 2, 3 == 3.0))
'''),
    ("call_and_division_errors", "errors", r'''
from dataclasses import dataclass
from collections import namedtuple
from typing import NamedTuple
def g(x, y):
    pass
def f(a, b=2):
    pass
def k(a, *, c, d):
    pass
class C:
    def m(self, x):
        pass
@dataclass
class Item:
    name: str
    done: bool = False
Q = namedtuple('Q', 'a b')
class P(NamedTuple):
    x: int
    y: int = 0
calls = [lambda: g(1), lambda: g(), lambda: g(1, 2, 3), lambda: f(1, 2, 3), lambda: f(), lambda: k(1), lambda: k(1, c=2),
         lambda: C().m(), lambda: C().m(1, 2), lambda: Item(), lambda: Item(1, 2, 3), lambda: Item(1, name=2), lambda: Q(),
         lambda: Q(1, 2, 3), lambda: Q(1, a=2), lambda: P(), lambda: P(1, 2, 3), lambda: 1 / 0, lambda: 1.0 / 0,
         lambda: 1 // 0, lambda: 1.0 // 0, lambda: 1 % 0, lambda: 1.0 % 0, lambda: divmod(1, 0), lambda: divmod(1.0, 0),
         lambda: 0 ** -1]
out = []
for call in calls:
    try:
        call()
        out.append('no error')
    except (TypeError, ZeroDivisionError) as e:
        out.append(str(e))
'\n'.join(out)
'''),
    ("range_loops_count_past_the_container_cap", "loops", r'''
t = 0
for i in range(25000):
    t += i
k = 0
for j in range(30, 0, -7):
    k += j
n = len([i for i in range(12000) if i % 2])
repr((t, k, n, [i for i in range(-3, 9, 4)]))
'''),
    ("generators_send_throw_close", "generators", r'''
def acc():
    total = 0
    try:
        while True:
            x = yield total
            if x is None:
                break
            total += x
    finally:
        log.append('closed')
    return total
log = []
g = acc()
next(g)
a = g.send(5)
b = g.send(10)
try:
    g.send(None)
except StopIteration as e:
    done = e.value
def inner():
    yield 1
    r = yield 2
    return r
def outer():
    got = yield from inner()
    yield ('got', got)
o = outer()
seq = [next(o), next(o), o.send('x')]
def catcher():
    while True:
        try:
            yield 'ok'
        except ValueError as e:
            yield 'caught ' + str(e)
c = catcher()
next(c)
repr((a, b, done, log, seq, c.throw(ValueError('v')), list(x * x for x in range(4)), sum(i for i in range(100000))))
'''),
    ("walrus_and_scoping", "scoping", r'''
data = [3, 1, 4, 1, 5]
evens = [y for x in data if (y := x * 2) > 4]
def counter():
    n = 0
    def inc():
        nonlocal n
        n += 1
        return n
    return inc
c = counter()
c(); c()
x = 'global'
def shadow():
    return x
def unbound():
    try:
        print(z)
    except UnboundLocalError as e:
        return type(e).__name__, isinstance(e, NameError)
    z = 1
fs = [lambda i=i: i for i in range(3)]
repr((evens, y, c(), shadow(), unbound(), [f() for f in fs]))
'''),
    ("match_statements", "match", r'''
from dataclasses import dataclass
@dataclass
class Point:
    x: int
    y: int
def where(p):
    match p:
        case Point(x=0, y=0):
            return 'origin'
        case Point(0, y):
            return f'y-axis {y}'
        case [a, b, *rest] if rest:
            return f'list {a} {b} +{len(rest)}'
        case {'kind': 'circle', 'r': r}:
            return f'circle {r}'
        case int() | float() as n:
            return f'number {n}'
        case str(s):
            return 'text ' + s
        case _:
            return 'other'
repr([where(v) for v in (Point(0, 0), Point(0, 3), [1, 2, 3, 4], {'kind': 'circle', 'r': 2}, 2.5, 'hi', None)])
'''),
    ("multiple_inheritance_and_super", "classes", r'''
class A:
    def who(self):
        return ['A']
class B(A):
    def who(self):
        return ['B'] + super().who()
class C(A):
    def who(self):
        return ['C'] + super().who()
class D(B, C):
    def who(self):
        return ['D'] + super().who()
try:
    class E(A, B):
        pass
    bad = 'made'
except TypeError:
    bad = 'TypeError'
repr((D().who(), [k.__name__ for k in D.__mro__], bad, issubclass(D, C), D.__bases__ == (B, C)))
'''),
    ("metaclasses", "classes", r'''
class Registry(type):
    classes = []
    def __new__(mcs, name, bases, ns, **kw):
        cls = super().__new__(mcs, name, bases, ns)
        cls.tag = kw.get('tag', name.lower())
        Registry.classes.append(name)
        return cls
    def __init__(cls, name, bases, ns, **kw):
        super().__init__(name, bases, ns)
    def __iter__(cls):
        return iter(cls.items)
    def __len__(cls):
        return len(cls.items)
class Base(metaclass=Registry):
    items = [1, 2]
class Child(Base, tag='kid'):
    items = [3]
class Singleton(type):
    made = {}
    def __call__(cls, *a):
        if cls not in cls.made:
            cls.made[cls] = super().__call__(*a)
        return cls.made[cls]
class Config(metaclass=Singleton):
    def __init__(self, v=0):
        self.v = v
repr((Registry.classes, Child.tag, type(Child).__name__, isinstance(Child, Registry), list(Child), len(Base), Config(1) is Config(2), Config().v))
'''),
    ("init_subclass_keywords", "classes", r'''
class Plugin:
    registry = {}
    def __init_subclass__(cls, name=None, **kw):
        super().__init_subclass__(**kw)
        Plugin.registry[name or cls.__name__] = cls.__name__
class A(Plugin, name='alpha'):
    pass
class B(Plugin):
    pass
try:
    class C(foo=1):
        pass
except TypeError as e:
    err = str(e)
repr((Plugin.registry, err))
'''),
    ("type_returns_types", "builtins", r'''
X = type('X', (object,), {'v': 3, 'twice': lambda self: self.v * 2})
repr((type(1), type('a') is str, type(X()).__name__, X().twice(), type(len).__name__, type(...).__name__, ..., issubclass(bool, int), issubclass(KeyError, LookupError)))
'''),
    ("lazy_builtins_and_range", "builtins", r'''
r = range(0, 20, 3)
m = map(str, [1, 2])
z = zip('ab', [1, 2, 3])
e = enumerate('xy', 1)
repr((r, len(r), r[2], r[-1], 9 in r, 10 in r, list(r[1:3]), next(m), list(m), list(z), list(e), list(reversed(range(3))), list(filter(None, [0, 1, 2])), 10**12 in range(10**15)))
'''),
    ("eval_and_exec", "builtins", r'''
ns = {}
exec('a = 2\ndef f(x):\n    return x * a', ns)
def local():
    q = 5
    return eval('q * 2')
repr((eval('1 + 2'), ns['f'](4), local(), eval('x + y', {'x': 1, 'y': 2})))
'''),
    ("deep_recursion", "calls", r'''
def depth(n):
    return 0 if n == 0 else 1 + depth(n - 1)
repr(depth(900))
'''),
    ("star_import_future_and_name", "modules", r'''
from __future__ import annotations
from math import *
def f(x: Undefined) -> AlsoUndefined:
    return x
repr((floor(2.7), sqrt(16), f(3)))
'''),
    ("sys_members", "modules", r'''
import sys
repr((sys.version_info >= (3, 8), sys.version_info.major, sys.maxsize == 2**63 - 1, sys.byteorder, sys.getrecursionlimit() >= 1000, isinstance(sys.argv, list)))
'''),
    ("collections_abc_checks", "stdlib", r'''
from collections.abc import Mapping, MutableMapping, Sequence, MutableSequence, Iterable, Iterator, Hashable, Callable, Set
class MyMap(Mapping):
    def __init__(self):
        self.d = {'a': 1}
    def __getitem__(self, k):
        return self.d[k]
    def __iter__(self):
        return iter(self.d)
    def __len__(self):
        return len(self.d)
m = MyMap()
repr((isinstance({}, MutableMapping), isinstance([], Sequence), isinstance((), MutableSequence), isinstance('a', Iterable), isinstance(iter([]), Iterator), isinstance([], Hashable), isinstance(len, Callable), isinstance({}.keys(), Set), isinstance(m, Mapping), m.get('z', 0), 'a' in m, dict(m.items())))
'''),
    ("contextlib_and_types", "stdlib", r'''
from contextlib import contextmanager, suppress, ExitStack, redirect_stdout
import io, types
log = []
@contextmanager
def tag(name):
    log.append('<' + name)
    try:
        yield name.upper()
    except ValueError:
        log.append('caught')
    log.append(name + '>')
with tag('a') as v:
    log.append(v)
with tag('b'):
    raise ValueError()
with suppress(KeyError):
    {}['x']
with ExitStack() as stack:
    stack.callback(log.append, 'stack')
buf = io.StringIO()
with redirect_stdout(buf):
    print('captured', end='!')
ns = types.SimpleNamespace(x=1, y=2)
repr((log, buf.getvalue(), ns, isinstance(tag, types.FunctionType)))
'''),
    ("logging_and_warnings", "stdlib", r'''
import io, logging, warnings
stream = io.StringIO()
log = logging.getLogger('app.part')
handler = logging.StreamHandler(stream)
handler.setFormatter(logging.Formatter('%(levelname)s %(name)s: %(message)s'))
log.addHandler(handler)
log.setLevel(logging.INFO)
log.debug('hidden')
log.info('n=%d', 3)
log.warning('careful')
with warnings.catch_warnings(record=True) as caught:
    warnings.simplefilter('always')
    warnings.warn('old', DeprecationWarning)
repr((stream.getvalue(), [str(w.message) for w in caught], caught[0].category.__name__, logging.getLevelName(logging.ERROR)))
'''),
    ("small_stdlib_modules", "stdlib", r'''
import heapq, bisect, operator, keyword, os.path, fnmatch, unicodedata
h = [5, 1, 4, 2]
heapq.heapify(h)
first = [heapq.heappop(h), heapq.heappop(h)]
xs = [1, 3, 5]
bisect.insort(xs, 4)
repr((first, heapq.nlargest(2, [3, 9, 1]), bisect.bisect_left(xs, 4), xs, operator.itemgetter(1, 0)('ab'), operator.attrgetter('real')(3), keyword.iskeyword('yield'), os.path.join('a', 'b', '../c'), os.path.normpath('a/b/../c'), os.path.splitext('f.tar.gz'), fnmatch.fnmatch('data.csv', '*.csv'), unicodedata.normalize('NFD', 'é') == 'é', unicodedata.category('Ä'), unicodedata.decimal('٣')))
'''),
    ("regex_verbose_and_ascii", "regex", r'''
import re
p = re.compile(r"""
    (\d+)    # digits
    \s*
    ([a-z]+) # word
""", re.VERBOSE)
repr((p.findall('12 ab 3cd'), re.findall(r'\w+', 'naïve café', re.ASCII), re.findall(r'(?x) a b # spaced', 'ab'), re.X == re.VERBOSE))
'''),
    ("str_format_fields", "strings", r'''
class P:
    name = 'pt'
    def __format__(self, spec):
        return 'P:' + spec
d = {'k': [10, 20]}
try:
    '{} {0}'.format(1)
except ValueError as e:
    err = str(e)
try:
    '%s' % (1, 2)
except TypeError as e:
    extra = str(e)
repr(('{0.name}|{1[k][1]}|{2!r}|{3:>{4}}|{p:x}'.format(P, d, 'q', 'ab', 5, p=P()), '{:,}'.format(1234567), err, '%(a)s-%(b)03d' % {'a': 'x', 'b': 7}, '%s and %r' % ('a', 'b'), extra))
'''),
    ("dict_views_are_live", "builtins", r'''
d = {'a': 1, 'b': 2}
k = d.keys()
v = d.values()
i = d.items()
d['c'] = 3
repr((k, list(v), ('a', 1) in i, k & {'a', 'z'}, k | {'q'} == {'a', 'b', 'c', 'q'}, k - {'a', 'b'}, len(i), list(reversed(k)), k == {'a', 'b', 'c'}))
'''),
    ("numbers_parts_and_float_sums", "numbers", r'''
repr(((5).real, (5).imag, (5).numerator, (5).denominator, (2.5).real, (255).bit_count(), (0.75).as_integer_ratio(), (3.0).is_integer(), sum([0.1] * 10), sum([1e100, 1.0, -1e100])))
'''),
    ("exception_context_and_reraise", "errors", r'''
def chained():
    try:
        {}['k']
    except KeyError:
        raise ValueError('wrapped')
try:
    chained()
except ValueError as e:
    ctx = (type(e.__context__).__name__, e.__cause__)
try:
    try:
        raise TypeError('a')
    except TypeError as t:
        raise ValueError('b') from t
except ValueError as e:
    same = e.__cause__ is e.__context__
class AppError(Exception):
    def __init__(self, code):
        super().__init__(f'code {code}')
        self.code = code
try:
    raise AppError(7)
except Exception as e:
    app = (str(e), e.code, e.args)
import sys
try:
    sys.exit(3)
except SystemExit as e:
    code = e.args
repr((ctx, same, app, code))
'''),
    ("time_struct_and_strftime", "stdlib", r'''
import time
t = time.gmtime(1700000000)
y, m, d, *_ = t
repr((t.tm_year, t[1], (y, m, d), time.strftime('%Y-%m-%d %H:%M:%S', t), time.asctime(t), type(time.time_ns()).__name__, time.strptime('2024-03-05', '%Y-%m-%d')[:3], len(t)))
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
//! int literals, `#` formats, the math, str and itertools additions, whitespace
//! splits, exact integer comparisons, `range` loops past the container cap,
//! the wording of call and division errors, generators, the walrus, scoping,
//! `match`, multiple inheritance, metaclasses and class keywords, `type()`,
//! lazy builtins and `range`, `eval` and `exec`, deep recursion, star and
//! `__future__` imports, `sys`, `collections.abc`, `contextlib`, `types`,
//! `logging`, `warnings`, the small modules, verbose regexes, `str.format`
//! fields, dict views, number parts and float sums, exception context, and `time`.

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
