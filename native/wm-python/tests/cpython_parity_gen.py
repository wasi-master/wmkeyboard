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
    ("string_concat_with_fstrings", "strings", r'''
v = 'x'
msg = (f"the version is {v!r}. "
       "next line "
       f"and {1 + 1}")
repr((msg, "a" "b" f"{v}" "c", u'old' + U"style", rf"\{v}", fr"\{v}\x"))
'''),
    ("subscripts_trailing_comma_and_slices", "syntax", r'''
class Grid:
    def __getitem__(self, k):
        return k
g = Grid()
X = dict[
    str,
    int,
]
repr((g[1:2, ::3], g[..., 1:], g[1, 2], g[1,], X == dict[str, int], X, list[int]("ab")))
'''),
    ("yield_inside_expressions", "generators", r'''
def gen():
    got = yield 1
    r = (yield got).upper()
    if (yield r):
        return (yield "last")
    return "no"
it = gen()
out = [next(it), it.send("hi"), it.send("x"), it.send(True)]
try:
    it.send("done")
except StopIteration as e:
    out.append(e.value)
repr(out)
'''),
    ("except_any_expression", "errors", r'''
import re
def errors():
    return (KeyError, ValueError)
out = []
try:
    {}["k"]
except errors() as e:
    out.append(type(e).__name__)
try:
    int("x")
except (TypeError,) + (ValueError,):
    out.append("tuple sum")
try:
    re.compile("(")
except re.error:
    out.append("re.error")
RETRY = (KeyError, IndexError)
try:
    [][1]
except RETRY:
    out.append("tuple name")
repr(out)
'''),
    ("matmul_and_type_params", "classes", r'''
class M:
    def __init__(self, v):
        self.v = v
    def __matmul__(self, o):
        return M(self.v * o.v)
    def __rmatmul__(self, o):
        return M(o * self.v)
m = M(2) @ M(5)
m @= M(3)
def first[T](xs: list[T]) -> T:
    return xs[0]
class Box[T]:
    def __init__(self, v):
        self.v = v
type Pair = tuple[int, Later]
class Later:
    pass
try:
    1 @ 2
except TypeError as e:
    err = str(e)
repr((m.v, (4 @ M(2)).v, err, first([9]), Box(1).v, Pair.__value__ == tuple[int, Later], repr(Pair)))
'''),
    ("async_await_with_for", "async", r'''
import asyncio
from collections.abc import Awaitable, Coroutine, AsyncIterator
log = []
async def add(a, b):
    await asyncio.sleep(0)
    return a + b
class Ctx:
    async def __aenter__(self):
        log.append("enter")
        return "res"
    async def __aexit__(self, t, v, tb):
        log.append(("exit", t.__name__ if t else None))
        return t is KeyError
async def ticker(n):
    for i in range(n):
        yield i * 10
async def main():
    x = await add(1, 2)
    y = await asyncio.gather(add(1, 1), add(2, 2))
    t = asyncio.create_task(add(5, 5))
    async with Ctx() as r:
        log.append(r)
    async with Ctx():
        raise KeyError("x")
    out = []
    async for v in ticker(4):
        if v == 20:
            continue
        out.append(v)
    else:
        out.append("else")
    agen = ticker(1)
    first = await anext(agen)
    last = await anext(agen, "dflt")
    return x, y, await t, out, first, last
c = add(1, 2)
kinds = (type(c).__name__, asyncio.iscoroutine(c), asyncio.iscoroutinefunction(add), isinstance(c, Coroutine), isinstance(c, Awaitable), isinstance(ticker(1), AsyncIterator))
c.close()
repr((asyncio.run(main()), log, kinds))
'''),
    ("datetime_time_zones", "stdlib", r'''
from datetime import datetime, timezone, timedelta, time, UTC
utc = datetime(2024, 3, 5, 12, 30, tzinfo=timezone.utc)
ist = timezone(timedelta(hours=5, minutes=30), "IST")
a = datetime(2024, 1, 1, tzinfo=timezone.utc)
b = datetime(2024, 1, 1, 5, 30, tzinfo=ist)
try:
    a < datetime(2024, 1, 1)
except TypeError as e:
    err = str(e)
repr((str(utc), utc, utc.astimezone(ist).isoformat(timespec="minutes"), utc.strftime("%Y-%m-%d %H:%M %z %Z"), utc.timestamp(),
      str(datetime.fromisoformat("2024-03-05T12:30:00+05:30")), datetime.fromisoformat("2024-03-05T12:30:00Z").tzinfo,
      str(datetime.fromtimestamp(0, timezone.utc)), a == b, a - b, ist, timezone(timedelta(hours=-8)), timezone.utc is UTC, err,
      str(datetime.strptime("2024-03-05 10:00 +0100", "%Y-%m-%d %H:%M %z")), time(9, 5, tzinfo=ist)))
'''),
    ("typing_unions_and_aliases", "typing", r'''
import typing, types
from typing import get_args, get_origin, Optional, Union, List, Callable, Generator, TypeAlias
T = typing.TypeVar("T")
class Box(typing.Generic[T]):
    def __init__(self, v):
        self.v = v
class Lookup(typing.Mapping[str, int]):
    def __getitem__(self, k):
        return 1
    def __iter__(self):
        return iter(["a"])
    def __len__(self):
        return 1
X: TypeAlias = dict[str, int]
repr((int | str, isinstance(3, int | str), isinstance(3.5, int | str), get_args(int | None), Optional[int], get_args(Optional[int]),
      get_origin(List[int]), get_args(Callable[[int], str]), get_args(dict[str, int]), get_origin(list[int]), X,
      get_origin(Union[int, str]) is Union, Box[int](3).v, T, dict(Lookup()), isinstance(Lookup(), typing.Mapping),
      types.GenericAlias, types.UnionType))
'''),
    ("unicode_names_and_code_points", "strings", r'''
import unicodedata
emoji = {"light": "🏻", "acc": "̀"}
नमस्ते = 3
repr(("\N{EM DASH}", "\N{no-break space}" == "\xa0", "\N{GREEK SMALL LETTER MU}", unicodedata.name("é"), unicodedata.lookup("SNOWMAN"),
      unicodedata.name("한"), unicodedata.name("\x1b", "none"), len(emoji["light"]), [hex(ord(c)) for c in emoji["acc"]], नमस्ते))
'''),
    ("class_module_names", "classes", r'''
import re, collections, datetime
repr((type(re.match("a", "a")), datetime.datetime, collections.Counter, datetime.timezone))
'''),
    ("mock_records_and_asserts", "mock", r"""
from unittest.mock import Mock, MagicMock, call, ANY, DEFAULT, sentinel
m = Mock(name="svc")
m(1, k=2)
m.child.go(3)
msgs = []
try:
    m.assert_called_with(9)
except AssertionError as e:
    msgs.append(str(e))
try:
    m.child.go.assert_called_once_with(4)
except AssertionError as e:
    msgs.append(str(e))
try:
    Mock(name="never").assert_called()
except AssertionError as e:
    msgs.append(str(e))
mm = MagicMock()
defaults = (len(mm), bool(mm), list(mm), 3 in mm, int(mm), float(mm))
mm.__len__.return_value = 4
se = Mock(side_effect=[1, DEFAULT], return_value="d")
repr((m.call_args, m.mock_calls, m.method_calls, m.mock_calls == [call(1, k=2), call.child.go(3)], call(1, ANY) == call(1, "x"), [ANY] == [5], msgs, defaults, len(mm), se(), se(), sentinel.x is sentinel.x, repr(call.a(1).b(k=2))))
"""),
    ("mock_patch_restores", "mock", r"""
import textwrap, json
from unittest.mock import patch, PropertyMock
with patch("textwrap.dedent", return_value="P") as d:
    inside = textwrap.dedent("  x")
    d.assert_called_once_with("  x")
@patch("json.dumps", return_value="J")
def f(md):
    return (json.dumps(1), md.call_count)
class K:
    def m(self):
        return "real"
    @property
    def p(self):
        return 1
with patch.object(K, "m", return_value="fake"):
    faked = K().m()
with patch.object(K, "p", new_callable=PropertyMock) as pm:
    pm.return_value = 7
    prop = K().p
cfg = {"a": 1}
with patch.dict(cfg, {"b": 2}):
    patched = dict(cfg)
repr((inside, textwrap.dedent("  y"), f(), json.dumps([1]), faked, K().m(), prop, K().p, patched, cfg))
"""),
    ("mock_spec_and_autospec", "mock", r"""
from unittest.mock import Mock, create_autospec, seal
class Foo:
    x = 1
    def bar(self, a, b=2):
        return a
s = Mock(spec=Foo)
errs = []
try:
    s.nope
except AttributeError as e:
    errs.append(str(e))
af = create_autospec(Foo)
inst = af()
try:
    inst.bar()
except TypeError as e:
    errs.append(str(e))
inst.bar(1)
sealed = Mock()
sealed.a.b = 1
seal(sealed)
try:
    sealed.c
except AttributeError as e:
    errs.append(str(e))
repr((isinstance(s, Foo), errs, inst.bar.call_args, sealed.a.b))
"""),
    ("unittest_runs_cases", "unittest", r"""
import io, unittest
class T(unittest.TestCase):
    def test_ok(self):
        self.assertEqual([1, 2], [1, 2])
    def test_fail(self):
        self.assertIn(3, [1, 2])
    def test_err(self):
        {}["k"]
    @unittest.skip("later")
    def test_skip(self):
        pass
stream = io.StringIO()
r = unittest.TextTestRunner(stream=stream).run(unittest.defaultTestLoader.loadTestsFromTestCase(T))
repr((r.testsRun, len(r.failures), len(r.errors), len(r.skipped), r.wasSuccessful(), str(r.failures[0][1]).strip().splitlines()[-1]))
"""),
    ("struct_layouts", "stdlib", r"""
import struct
errs = []
for fmt, args in [("<b", (200,)), ("<H", (1, 2)), ("<4s", (3,))]:
    try:
        struct.pack(fmt, *args)
    except struct.error as e:
        errs.append(str(e))
repr((struct.pack("<Ihq?", 7, -2, 2 ** 40, True), struct.unpack(">Hd", struct.pack(">Hd", 513, 0.5)), struct.calcsize("<iqs"), struct.calcsize("!3sH"), struct.unpack_from("<H", b"xx\x01\x02", 2), list(struct.iter_unpack("<B", b"ab")), errs))
"""),
    ("zlib_checksums_and_round_trips", "stdlib", r"""
import zlib, gzip
data = b"hello hello hello" * 20
c = zlib.compressobj()
streamed = c.compress(data[:100]) + c.compress(data[100:]) + c.flush()
d = zlib.decompressobj()
back = d.decompress(streamed[:10]) + d.decompress(streamed[10:])
try:
    zlib.decompress(b"nope")
    err = None
except zlib.error as e:
    err = str(e)
repr((zlib.crc32(b"abc"), zlib.adler32(b"abc"), zlib.decompress(zlib.compress(data, 9)) == data, back == data, gzip.decompress(gzip.compress(b"xyz")), err))
"""),
    ("binary_text_codings", "stdlib", r"""
import base64, binascii
errs = []
try:
    base64.b64decode("abc")
except binascii.Error as e:
    errs.append(str(e))
repr((base64.b64encode(b"hi!"), base64.urlsafe_b64encode(b"\xfb\xff"), base64.b32encode(b"ab"), base64.b16decode("6869"), base64.a85encode(b"hello"), base64.b85encode(b"hello"), binascii.hexlify(b"\x01\xab", "-"), binascii.unhexlify("ff00"), binascii.crc32(b"x"), errs))
"""),
    ("encodings_and_error_handlers", "strings", r"""
errs = []
try:
    "é".encode("ascii")
except UnicodeEncodeError as e:
    errs.append(str(e))
try:
    b"a\xffb".decode("utf-8")
except UnicodeDecodeError as e:
    errs.append(str(e))
repr(("aé€".encode("ascii", "replace"), "aé".encode("ascii", "xmlcharrefreplace"), "é".encode("ascii", "backslashreplace"), b"a\xffb".decode("utf-8", "replace"), b"a\xffb".decode("utf-8", "backslashreplace"), "é".encode("latin-1"), "€".encode("cp1252"), "hi".encode("utf-16"), b"\xff\xfeh\x00".decode("utf-16"), "x".encode("utf-8-sig"), "a\n".encode("unicode_escape"), errs))
"""),
    ("text_helpers_shlex_csv_difflib", "stdlib", r'''
import shlex, csv, io, difflib
rows = list(csv.reader(io.StringIO('a,"b,c",d\n1,"x ""q""",3\n')))
out = io.StringIO()
w = csv.writer(out)
w.writerow(["a", "b,c", 'q"'])
dw = io.StringIO()
dictw = csv.DictWriter(dw, fieldnames=["k", "v"])
dictw.writeheader()
dictw.writerow({"k": 1, "v": "two"})
diff = list(difflib.unified_diff(["a\n", "b\n"], ["a\n", "c\n"], "x", "y"))
repr((shlex.split("a 'b c' \"d e\" f\\ g"), shlex.quote("it's"), shlex.join(["a", "b c"]), rows, out.getvalue(), dw.getvalue(), diff, round(difflib.SequenceMatcher(None, "abcd", "abxd").ratio(), 3), difflib.get_close_matches("appel", ["ape", "apple", "peach"])))
'''),
    ("configparser_and_argparse", "stdlib", r"""
import configparser, argparse
cp = configparser.ConfigParser()
cp.read_string("[main]\nport = 8080\ndebug = yes\nname = %(port)s-x\n[other]\nk=v\n")
p = argparse.ArgumentParser(prog="tool")
p.add_argument("path")
p.add_argument("-n", "--count", type=int, default=1)
p.add_argument("--flag", action="store_true")
p.add_argument("--mode", choices=["a", "b"])
ns = p.parse_args(["file.txt", "-n", "3", "--flag"])
repr((cp.sections(), cp.getint("main", "port"), cp.getboolean("main", "debug"), cp.get("main", "name"), dict(cp["other"]), ns.path, ns.count, ns.flag, ns.mode, p.format_usage()))
"""),
    ("calendar_and_pprint", "stdlib", r"""
import calendar, pprint
repr((calendar.isleap(2024), tuple(map(int, calendar.monthrange(2024, 2))), int(calendar.weekday(2024, 3, 1)), calendar.leapdays(2000, 2025), calendar.month(2024, 2), pprint.pformat({"b": [1, 2, 3] * 8, "a": {"x": "y" * 30}}, width=40)))
"""),
    ("inspect_signatures", "stdlib", r"""
import inspect, binascii
def f(a, b=2, /, c=3, *args, d, e=5, **kw):
    pass
class C:
    def m(self, x, y=1):
        pass
async def co():
    pass
sig = inspect.signature(f)
bound = sig.bind(1, d=4)
bound.apply_defaults()
try:
    f(1, b=2, d=4)
    posonly = None
except TypeError as e:
    posonly = str(e)
repr((str(sig), [p.kind.name for p in sig.parameters.values()], posonly, binascii.hexlify(b"\x01\x02\x03", b":", 2), str(inspect.signature(C.m)), str(inspect.signature(C().m)), dict(bound.arguments), inspect.isfunction(f), inspect.ismethod(C().m), inspect.isclass(C), inspect.iscoroutinefunction(co), inspect.getfullargspec(C.m).args))
"""),
    ("literal_eval_and_import_module", "stdlib", r"""
import ast, importlib
errs = []
for src in ["x", "f()", "1 +"]:
    try:
        ast.literal_eval(src)
    except (ValueError, SyntaxError) as e:
        errs.append(type(e).__name__)
repr((ast.literal_eval("{'a': [1, 2.5, (3, None)], 'b': -4, 'c': 1 + 2j == 0 or True}") if False else ast.literal_eval("{'a': [1, 2.5, (3, None)], 'b': -4, 's': {1}}"), errs, importlib.import_module("json").dumps([1]), importlib.import_module("email.utils").__name__))
"""),
    ("email_addresses_and_messages", "stdlib", r"""
from email.utils import parseaddr, formataddr, getaddresses
from email.message import EmailMessage
msg = EmailMessage()
msg["Subject"] = "Hi"
msg["To"] = "a@example.com"
msg.set_content("body text")
repr((parseaddr("Ann <ann@example.com>"), formataddr(("Bob", "bob@example.com")), getaddresses(["a@x.com, B <b@y.com>"]), msg["subject"], msg.get_content_type(), msg.get_content().strip()))
"""),
    ("array_typed_items", "stdlib", r"""
import array
a = array.array("h", [1, -2, 3])
b = a
a += array.array("h", [4])
errs = []
try:
    array.array("b", [200])
except OverflowError as e:
    errs.append(str(e))
repr((a, a is b, a.tobytes(), array.array("h", a.tobytes()) == a, a.itemsize, a.typecode, list(reversed(a)), a[1:3], errs))
"""),
    ("containers_ask_eq_of_their_items", "classes", r"""
class AnyThing:
    def __eq__(self, other):
        return True
class Key:
    def __init__(self, v):
        self.v = v
    def __eq__(self, other):
        return isinstance(other, Key) and other.v == self.v
    __hash__ = None
any_ = AnyThing()
items = [Key(1), Key(2)]
repr(((1, any_) == (1, "z"), [any_] == [3], {"k": any_} == {"k": 9}, Key(2) in items, items.index(Key(2)), items.count(Key(1)), (Key(1),).index(Key(1)), [1, any_] != [1, 2]))
"""),
    ("number_hooks_of_objects", "classes", r"""
import math
class N:
    def __index__(self):
        return 3
    def __int__(self):
        return 7
    def __float__(self):
        return 2.5
    def __abs__(self):
        return "abs"
    def __round__(self, n=None):
        return ("round", n)
    def __floor__(self):
        return "floor"
    def __divmod__(self, o):
        return ("divmod", o)
    def __bool__(self):
        return False
n = N()
repr((int(n), float(n), [0, 1, 2, 3, 4][n], [0, 1, 2, 3, 4][n:], list(range(n)), hex(n), "ab" * n, abs(n), round(n), round(n, 2), math.floor(n), divmod(n, 2), bool(n), math.sqrt(n), chr(65 + n.__index__())))
"""),
    ("try_else_and_in_place_operators", "syntax", r"""
def f():
    try:
        pass
    except KeyError:
        return "caught"
    else:
        raise KeyError("from else")
try:
    f()
    r = "none"
except KeyError as e:
    r = str(e)
class Acc:
    def __init__(self):
        self.items = []
    def __iadd__(self, x):
        self.items.append(x)
        return self
    def __add__(self, x):
        return "copy"
a = Acc()
same = a
a += 1
a += 2
repr((r, a is same, a.items))
"""),
    ("descriptors_and_live_dict", "classes", r"""
class Lazy:
    def __init__(self, f):
        self.f = f
    def __set_name__(self, owner, name):
        self.name = name
    def __get__(self, obj, owner=None):
        if obj is None:
            return self
        v = obj.__dict__[self.name] = self.f(obj)
        return v
class Doubled:
    def __get__(self, obj, owner=None):
        return obj.__dict__.get("_v") if obj is not None else "class"
    def __set__(self, obj, value):
        obj.__dict__["_v"] = value * 2
class Logged:
    def __setattr__(self, name, value):
        object.__setattr__(self, name, ("set", value))
    @property
    def p(self):
        return self.__dict__.get("_p")
    @p.setter
    def p(self, v):
        self.__dict__["_p"] = v
class C:
    @Lazy
    def big(self):
        return 42
    d = Doubled()
c = C()
c.d = 5
vars(c)["extra"] = 1
lg = Logged()
lg.p = 3
lg.q = 4
repr((c.big, c.big, "big" in vars(c), c.d, C.d, c.extra, sorted(vars(c)), lg.p, lg.q, C.__init__ is object.__init__ or callable(C.__init__)))
"""),
    ('builtin_subclasses_behave_as_their_base', "classes", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import json
class D(dict):
    def __missing__(self, k):
        return k * 2
d = D(a=1)
print(d['a'], d['zz'], isinstance(d, dict), len(d), type(d).__name__, d, repr(d), str(d))
print(d.get('q'), 'a' in d, list(d), list(d.items()), d.keys(), dict(d), json.dumps(d))
d['b'] = 2; d.update(c=3); del d['a']
print(d, d.pop('b'), d, D.fromkeys('xy', 0), type(D.fromkeys('x')).__name__)
class L(list):
    def total(self):
        return sum(self)
l = L([1, 2, 3])
l.append(4)
print(l.total(), l, len(l), l[1:], isinstance(l, list), l[0], l[-1], sorted(l, reverse=True))
l += [5]
print(l, type(l).__name__, l + [6], type(l + [6]).__name__, 2 * l[:2], l == [1, 2, 3, 4, 5], l != [1])
l[0] = 10; del l[1]
print(l, l.index(3), l.count(4), [x * 2 for x in l], max(l), min(l), sum(l), list(reversed(l)))
class S(str):
    def shout(self):
        return self.upper() + '!'
s = S('hi')
print(s.shout(), s + 'x', 'x' + s, len(s), s == 'hi', {s: 1}['hi'], {'hi': 2}[s], repr(s), str(s), f"<{s}>", f"{s:>5}")
print(s * 2, s.replace('h', 'H'), 'h' in s, s in 'ohio', '-'.join([s, s]), 'hi'.startswith(s), s.split('i'), s[0], s[::-1], s.upper(), type(s.upper()).__name__)
print(hash(s) == hash('hi'), sorted([S('b'), S('a')]), s < 'z', '%s and %r' % (s, s), int(S('42')) + 1, float(S('1.5')))
class I(int):
    def double(self):
        return self * 2
i = I(21)
print(i.double(), i + 1, 1 + i, isinstance(i, int), repr(i), str(i), i * 2, -i, abs(I(-3)), i // 2, i % 5, i ** 2, i > 20, i == 21, hex(i), '%d' % i, f"{i:04d}")
print([0, 1, 2][I(1)], range(I(3)), list(range(I(2))), round(I(5)), int(i), float(i), bool(I(0)), {i: 'x'}[21], I('ff', 16), I(), i.bit_length())
class F(float):
    pass
f = F(2.5)
print(f + 1, f * 2, f, repr(f), isinstance(f, float), int(f), round(f), f.is_integer(), F('3.25'))
class T(tuple):
    def first(self):
        return self[0]
t = T([1, 2, 3])
print(t, t.first(), len(t), t + (4,), t[1:], isinstance(t, tuple), hash(t) == hash((1, 2, 3)), t == (1, 2, 3), {t: 1}[(1, 2, 3)])
a, b, c = t
print(a, b, c, *t)
class Pt(tuple):
    def __new__(cls, x, y):
        return super().__new__(cls, (x, y))
    @property
    def x(self):
        return self[0]
p = Pt(3, 4)
print(p, p.x, p[1], type(p).__name__, len(p))
class Up(str):
    def __new__(cls, value):
        return super().__new__(cls, value.upper())
u = Up('abc')
print(u, len(u), u.lower(), type(u).__name__, u + '!', isinstance(u, Up))
class St(set):
    pass
st = St([1, 2])
st.add(3)
print(sorted(st), len(st), 2 in st, isinstance(st, set), sorted(st | {9}), st == {1, 2, 3})
st |= {4}
print(sorted(st), type(st).__name__)
class Counter2(dict):
    def __init__(self, items=()):
        super().__init__()
        for x in items:
            self[x] = self.get(x, 0) + 1
c = Counter2('abca')
print(c, c['a'], len(c))
class AttrDict(dict):
    __getattr__ = dict.__getitem__
    __setattr__ = dict.__setitem__
ad = AttrDict(x=1)
ad.y = 2
print(ad.x, ad.y, ad, sorted(ad.keys()))
class Logged(dict):
    def __setitem__(self, k, v):
        print('set', k, v)
        super().__setitem__(k, v)
lg = Logged()
lg['k'] = 1
print(lg, dict.__getitem__(lg, 'k'), list.__len__([1, 2]), str.upper('x'), int.__add__(2, 3), (5).__add__(1), 'ab'.__len__(), [1].__contains__(1))
print(issubclass(D, dict), issubclass(D, object), D.__bases__, D.__mro__, type(d) is D, type(d) is dict)
print(json.dumps({'d': D(x=L([1, S('s')])), 'i': I(3)}))
import copy
print(copy.copy(l), type(copy.copy(l)).__name__, copy.deepcopy(d), type(copy.deepcopy(d)).__name__, copy.copy(s), type(copy.copy(i)).__name__)
'\n'.join(_out)
'''),
    ('builtin_subclasses_with_overrides', "classes", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
class Ci(str):
    def __eq__(self, other):
        return self.lower() == str(other).lower()
    def __hash__(self):
        return hash(self.lower())
print(Ci('A') == 'a', Ci('A') != 'b', {Ci('X'): 1}.get(Ci('x')), 'a' == Ci('A'))
class Rev(list):
    def __iter__(self):
        return iter(sorted(super().__iter__(), reverse=True))
r = Rev([1, 3, 2])
print(list(r), [x for x in r], r, len(r))
class Lim(list):
    def __len__(self):
        return 99
print(len(Lim([1])), bool(Lim()), Lim([1]))
class Pretty(list):
    def __repr__(self):
        return 'Pretty' + super().__repr__()
print(Pretty([1]), str(Pretty([2])), [Pretty([3])], f"{Pretty([4])}")
class Named(str):
    def __repr__(self):
        return 'Named(' + super().__repr__() + ')'
n = Named('x')
print(n, repr(n), [n], f"{n}", str(n), '%s|%r' % (n, n))
class Tag(str):
    def __init__(self, value):
        self.tag = 't'
t = Tag('v')
print(t, t.tag, t + 'w', vars(t))
class Pos(int):
    def __new__(cls, v):
        if v < 0:
            raise ValueError('negative')
        return super().__new__(cls, v)
print(Pos(3) + Pos(4), type(Pos(3)).__name__)
try:
    Pos(-1)
except ValueError as e:
    print('caught', e)
class Mix:
    def hello(self):
        return 'hello ' + str(len(self))
class MD(Mix, dict):
    pass
md = MD(a=1, b=2)
print(md.hello(), isinstance(md, Mix), isinstance(md, dict), MD.__mro__)
class OD(dict):
    def keys(self):
        return sorted(super().keys())
od = OD(b=1, a=2)
print(od.keys(), dict(od), {**od})
class Guard(dict):
    def __contains__(self, k):
        return k == 'magic'
print('magic' in Guard(), 'x' in Guard(x=1))
class DD(dict):
    def __init__(self, factory, *a, **kw):
        super().__init__(*a, **kw)
        self.factory = factory
    def __missing__(self, k):
        v = self[k] = self.factory()
        return v
dd = DD(list)
dd['x'].append(1)
dd['y'].append(2)
print(dd, dd.factory, len(dd))
try:
    {Rev(): 1}
except TypeError as e:
    print('TypeError', e)
for bad in (bool, type(None)):
    try:
        class X(bad):
            pass
    except TypeError as e:
        print('TypeError', e)
try:
    class Y(dict, list):
        pass
except TypeError as e:
    print('TypeError', e)
print(L() == L() if (L := type('L', (list,), {})) else 0, type('S2', (str,), {})('q').upper())
class Money(float):
    def __str__(self):
        return f'${self:.2f}'
m = Money(3.5)
print(m, str(m), repr(m), f"{m}", m + 1, [m])
import functools
@functools.total_ordering
class Ver(tuple):
    pass
print(Ver((1, 2)) < Ver((1, 3)), max([Ver((1,)), Ver((2,))]))
class Stack(list):
    push = list.append
    def peek(self):
        return self[-1]
s = Stack()
s.push(1); s.push(2)
print(s.peek(), s.pop(), s, Stack.push)
class Bits(int):
    def __repr__(self):
        return f'Bits({int(self)})'
b = Bits(5)
print(b, repr(b), b | 2, b & 4, b << 1, ~b, [b], str(b))
class Lst(list):
    pass
x = Lst([3, 1, 2]); x.sort(); x.reverse(); x.extend([0]); x.insert(0, 9); x.remove(1)
print(x, x * 2, x[::2], x.copy(), type(x.copy()).__name__, 9 in x, x.index(2))
y = Lst([1]); y *= 3
print(y, type(y).__name__, y.__class__.__name__)
print(sorted({Ci('b'), Ci('a')}), 'append' in dir(Lst()), hasattr(Lst(), 'append'), callable(Lst().append), Lst[int], type(Lst[int]).__name__)
print(type(iter([])).__name__, iter([]).__class__.__name__, (1).__class__, "".__class__.__name__, len.__class__.__name__)
'\n'.join(_out)
'''),
    ('enums_counters_and_named_tuples', "classes", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import json, copy
from enum import Enum, IntEnum, StrEnum, Flag, IntFlag, auto, unique
from collections import Counter, defaultdict, OrderedDict, namedtuple
from typing import NamedTuple
class C(str, Enum):
    R = 'r'
    G = 'g'
print(C.R, repr(C.R), f"{C.R}", C.R == 'r', C.R.value, C('r'), isinstance(C.R, str), C.R.upper(), hash(C.R) == hash('r'), json.dumps(C.R), {C.R: 1}['r'], list(C), len(C), C['G'], C.R in C)
class P(IntEnum):
    A = 1
    B = 2
print(P.A, repr(P.A), f"{P.A}", f"{P.A:03d}", P.A + 1, P.A == 1, P(2), json.dumps([P.A, P.B]), sorted([P.B, P.A]), isinstance(P.A, int), hash(P.A) == hash(1), {P.A: 'x'}[1], P.A < P.B, int(P.B), P.B.name, P(P.A))
class S(StrEnum):
    X = auto()
    Y = 'why'
print(S.X, repr(S.X), S.Y, S('why'), S.X == 'x', '-'.join(S), S.X.value)
class Color(Enum):
    RED = 1
    GREEN = 2
    CRIMSON = 1
print(Color.CRIMSON is Color.RED, list(Color), len(Color), list(Color.__members__), Color(1), Color.RED == 1, repr(Color.GREEN), str(Color.GREEN), f"{Color.GREEN}", Color.RED.name, copy.deepcopy(Color.RED) is Color.RED)
try:
    Color(9)
except ValueError as e:
    print('ValueError', e)
class Perm(IntFlag):
    R = 4
    W = 2
    X = 1
rw = Perm.R | Perm.W
print(rw, repr(rw), Perm.R in rw, Perm.X in rw, int(rw), rw & Perm.R, repr(Perm(7)), bool(Perm(0)), Perm.R | 1, list(rw))
class Fl(Flag):
    A = auto()
    B = auto()
    C = auto()
print(Fl.A, Fl.C.value, repr(Fl.A | Fl.B), list(Fl.A | Fl.C), repr(~Fl.A), Fl(0), bool(Fl(0)))
Animal = Enum('Animal', 'ANT BEE CAT')
print(list(Animal), Animal.BEE.value, Animal(3))
class Planet(Enum):
    EARTH = (5.97e24, 6.37e6)
    def __init__(self, mass, radius):
        self.mass = mass
        self.radius = radius
print(Planet.EARTH.mass, Planet.EARTH.value)
cnt = Counter('abracadabra')
print(cnt, cnt.most_common(2), cnt['z'], isinstance(cnt, dict), json.dumps(cnt, sort_keys=True), cnt + Counter('zz'), cnt - Counter('aaaaa'), sum(cnt.values()), cnt.total(), sorted(cnt.elements())[:3])
cnt.update(['a', 'z']); cnt.subtract({'b': 1}); del cnt['nothing']
print(cnt['a'], cnt['b'], cnt['z'], Counter(a=1) == Counter(a=1, b=0), dict(Counter('aab')), copy.copy(Counter('ab')), type(copy.deepcopy(cnt)).__name__)
dd = defaultdict(list)
dd['x'].append(1)
dd['y']
print(dd, dict(dd), isinstance(dd, dict), json.dumps(dd), 'z' in dd, len(dd), copy.copy(dd), dd.default_factory)
try:
    defaultdict(None)['q']
except KeyError as e:
    print('KeyError', e)
od = OrderedDict(a=1, b=2, c=3)
od.move_to_end('a'); od.move_to_end('c', last=False)
print(od, list(od), od.popitem(), od.popitem(last=False), od, OrderedDict(a=1, b=2) == OrderedDict(b=2, a=1), OrderedDict(a=1, b=2) == dict(b=2, a=1), isinstance(od, dict))
Point = namedtuple('Point', 'x y', defaults=[0])
p = Point(1)
print(p, p.x, p.y, p[0], isinstance(p, tuple), p == (1, 0), p._replace(y=5), p._asdict(), Point._fields, Point._make([3, 4]), json.dumps(p), len(p), hash(p) == hash((1, 0)), p + (9,), sorted([Point(2), Point(1)]), copy.copy(p), Point._field_defaults)
x, y = p
print(x, y, Point.__name__, type(p).__name__, repr(Point(y=2, x=1)))
try:
    p.x = 3
except AttributeError as e:
    print('AttributeError', e)
class Emp(NamedTuple):
    name: str
    dept: str = 'eng'
    def label(self):
        return f'{self.name}@{self.dept}'
e = Emp('ann')
print(e, e.label(), e.dept, isinstance(e, tuple), e._asdict(), Emp._fields, e == ('ann', 'eng'), json.dumps(e), Emp('bob', 'ops')[1])
Pair = NamedTuple('Pair', [('a', int), ('b', int)])
print(Pair(1, 2), Pair(1, 2).b)
'\n'.join(_out)
'''),
    ('integers_of_any_size', "numbers", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import math, json, struct, sys
a = 2 ** 64
print(a, type(a).__name__, a + 1, a - 1, a * a, -a, abs(-a), a // 3, a % 7, a ** 2, 2 ** 100, 10 ** 30)
print(a == 2.0 ** 64, a + 1 == 2.0 ** 64, a > 2.0 ** 63, 2 ** 64 > 1e300, a < float('inf'), a == a, hash(a) == hash(2.0 ** 64), {a: 1}[2.0 ** 64])
print(1 << 70, (1 << 70) >> 69, -(1 << 70) >> 3, (2 ** 70) & 0xFF, (2 ** 70) | 1, (2 ** 70) ^ (2 ** 70), ~(2 ** 70), 9223372036854775807 + 1, -9223372036854775808 - 1)
print(math.factorial(30), math.comb(100, 50), math.perm(30, 20), math.gcd(2 ** 80, 6 ** 40), math.lcm(2 ** 40, 3 ** 30), math.isqrt(10 ** 40), math.prod(range(1, 30)))
print(int('123456789012345678901234567890'), int('ff' * 20, 16), int(1e30), int(-2.5e20), float(2 ** 80), str(2 ** 100), repr(-(10 ** 25)), hex(2 ** 70), bin(2 ** 65), oct(8 ** 30))
print(f"{2 ** 70:,}", f"{2 ** 70:x}", f"{-(2 ** 70):>30}", "%d" % (3 ** 50), "{:_}".format(10 ** 20), format(2 ** 64, '#X'), f"{2**70:e}")
print(json.dumps([2 ** 70, -(3 ** 45)]), json.loads('[123456789012345678901234567890]'), (2 ** 64 - 1).to_bytes(8, 'big'), int.from_bytes(b'\xff' * 16, 'little'), int.from_bytes(b'\x80' + b'\x00' * 15, 'big', signed=True))
print((2 ** 100).bit_length(), (2 ** 100 - 1).bit_count(), pow(3, 200, 10 ** 9 + 7), pow(2, -1, 7), divmod(10 ** 20, 7), round(123456789012345678901, -5), round(2 ** 70), 7 ** 30 / 7 ** 28)
print(struct.unpack('<Q', b'\xff' * 8), struct.pack('>Q', 2 ** 64 - 1), sorted([2 ** 70, 5, -2 ** 65, 3.5]), max(2 ** 63, 2 ** 64), sum([2 ** 62] * 8), 0.5 * 2 ** 64, 2 ** 64 / 2)
print(isinstance(2 ** 70, int), (2 ** 70).__add__(1), int.__mul__(2 ** 40, 2 ** 40), (10 ** 20).real, (2 ** 70).as_integer_ratio(), (0.1).as_integer_ratio(), 12345678901234567890 % 1000, -12345678901234567890 // 1000)
x = 1
for i in range(200):
    x *= 3
print(x, len(str(x)), x % 1000003, x.bit_length())
try:
    int('9' * 5000)
except ValueError as e:
    print('ValueError', str(e)[:40])
try:
    float(10 ** 400)
except OverflowError as e:
    print('OverflowError', e)
print(10 ** 400 > 1e308, math.log(10 ** 400) > 920, round(math.log10(10 ** 500)), 1 / (10 ** 400), (10 ** 400) // (10 ** 399))
import array, struct
for c,v in [('q',2**63),('q',-2**63-1),('Q',2**64),('Q',-1),('l',2**63),('L',2**64),('L',-1)]:
    try: array.array(c,[v])
    except Exception as e: print(c, type(e).__name__, e)
print(array.array('Q',[2**64-1]), array.array('q',[-2**63]))
a = array.array('Q', [2**64-1, 5]); print(a.tobytes(), array.array('Q', a.tobytes()), sum(a))
print(struct.unpack('<Q', b'\xff'*8), struct.pack('<d', 2**70), struct.pack('<Q', 2**64-1), struct.unpack('>q', b'\x80'+b'\0'*7))
for f,v in [('<Q',2**64),('<q',2**63),('<Q',-1), ('<Q', 2**200), ('<d', 2**2000)]:
    try: struct.pack(f,v)
    except Exception as e: print(f, type(e).__name__, e)
class I:
    def __index__(self): return 2**70
print(hex(I()), bin(I())[:10], float(I()), int.__index__(5))
import operator; print(operator.index(I()))
try: [1,2][I()]
except Exception as e: print(type(e).__name__, e)
import uuid
u = uuid.UUID('{12345678-1234-5678-1234-567812345678}')
print(u, repr(u), u.int, u.hex, u.urn, u.version, u.variant)
print(u.fields, u.time, u.clock_seq, u.node)
print(u.bytes, u.bytes_le)
print(uuid.UUID(bytes_le=u.bytes_le) == u, uuid.UUID(int=u.int) == u, uuid.UUID(fields=u.fields) == u)
print(uuid.uuid3(uuid.NAMESPACE_DNS, 'python.org'), uuid.uuid5(uuid.NAMESPACE_DNS, 'python.org'), uuid.uuid5(uuid.NAMESPACE_URL, b'https://example.com'))
r = uuid.uuid4()
print(r.version, r.variant, len(str(r)), uuid.UUID(str(r)) == r)
t = uuid.uuid1()
print(t.version, t.variant, t.node == uuid.getnode(), uuid.uuid1().time > t.time)
print(sorted([uuid.UUID(int=5), uuid.UUID(int=2**127), uuid.UUID(int=1)]), int(uuid.UUID(int=2**100)), hash(uuid.UUID(int=7)) == hash(7))
try:
    u.int = 3
except TypeError as e:
    print('TypeError', e)
for bad in [dict(hex='xyz'), dict(int=-1), dict(int=2**128), dict(fields=(2**32,0,0,0,0,0)), dict(bytes=b'abc'), {}]:
    try:
        uuid.UUID(**bad)
    except (ValueError, TypeError) as e:
        print(type(e).__name__, e)
print(uuid.UUID(int=0, version=4), uuid.SafeUUID.unknown, u.is_safe, u.__getstate__())
'\n'.join(_out)
'''),
    ('semicolons_in_one_line_blocks', "syntax", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
try: a = 1; print(a)
except Exception as e: print(e)
if True: x = 1; print(x);
else: print(2)
for i in []: pass; print(i)
else: print("done")
def f(): return 3;
print(f()); y = 2;
while y: y -= 1; print("w", y)
class K: a = 1; b = 2
print(K.b)
if 0: pass
elif 1: print("e"); print("e2")
with open if False else __import__("contextlib").nullcontext(): print("in"); print("in2")
import math; print(math.pi > 3)
x = [1,
 2]; print(x)
'\n'.join(_out)
'''),
    ('the_int_digit_limit', "numbers", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import sys
print(sys.get_int_max_str_digits())
for v in [100, -1, 0, 640]:
    try: sys.set_int_max_str_digits(v); print('ok', v, sys.get_int_max_str_digits())
    except Exception as e: print(type(e).__name__, e)
sys.set_int_max_str_digits(640)
try: str(10**700)
except ValueError as e: print(e)
try: int('1'*700)
except ValueError as e: print(e)
sys.set_int_max_str_digits(maxdigits=5000); print(sys.get_int_max_str_digits(), len(str(10**4500)))
try: sys.set_int_max_str_digits('a')
except Exception as e: print(type(e).__name__, e)
sys.set_int_max_str_digits(0); print(len(str(7**20000)))
'\n'.join(_out)
'''),
    ('random_ranges_of_any_size', "modules", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import random
for a in [(0,), (5,2), (10,0,-1), (0,10,0), (0,10,-1), (10.5,), (-3,), (1,2,3,4)]:
    try:
        r = random.randrange(*a)
        print(a, 'ok', r in range(*a))
    except Exception as e: print(a, type(e).__name__, e)
for a in [(5,2), (0, 2**100), (-2**63, 2**63-1), (3, 3)]:
    try:
        r = random.randint(*a); print(a, 'ok', a[0] <= r <= a[1])
    except Exception as e: print(a, type(e).__name__, e)
xs = [random.randrange(2**70, 2**70 + 10) for _ in range(200)]
print(min(xs) >= 2**70, max(xs) < 2**70 + 10, len(set(xs)) > 5)
ys = [random.randrange(100, 0, -7) for _ in range(300)]
print(set(ys) <= set(range(100, 0, -7)), len(set(ys)))
big = [random.randrange(10**30) for _ in range(50)]
print(all(0 <= b < 10**30 for b in big), max(big) > 10**28)
print(random.getrandbits(100) < 2**100, random.getrandbits(0))
'\n'.join(_out)
'''),
    ('ipaddress_module', "modules", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import ipaddress
a = ipaddress.ip_address('192.168.1.10')
print(repr(a), a.version, a.is_private, a.is_global, int(a), a + 5, a.packed, a.reverse_pointer)
n = ipaddress.ip_network('10.0.0.0/30')
print(n, list(n), n.num_addresses, n.netmask, n.hostmask, n.broadcast_address, list(n.hosts()), a in n)
i = ipaddress.ip_interface('192.168.1.10/24')
print(i, i.network, i.ip, i.with_netmask, i.with_prefixlen)
six = ipaddress.ip_address('2001:db8::1')
print(repr(six), six.exploded, six.compressed, six.is_private, int(six), six.reverse_pointer[:20], six.version)
print(ipaddress.ip_address('::ffff:1.2.3.4').ipv4_mapped, ipaddress.IPv6Network('2001:db8::/126').num_addresses, list(ipaddress.ip_network('2001:db8::/126')))
print(list(ipaddress.summarize_address_range(ipaddress.IPv4Address('10.0.0.0'), ipaddress.IPv4Address('10.0.0.10'))))
print(list(ipaddress.collapse_addresses([ipaddress.ip_network('10.0.0.0/25'), ipaddress.ip_network('10.0.0.128/25')])))
print(list(ipaddress.ip_network('10.0.0.0/24').subnets(new_prefix=26)), ipaddress.ip_network('10.0.0.0/24').supernet())
print(sorted([ipaddress.ip_address('10.0.0.2'), ipaddress.ip_address('10.0.0.1')]), ipaddress.ip_address('1.2.3.4') < ipaddress.ip_address('1.2.3.5'))
for bad in ['300.1.1.1', '1.2.3', 'zz::1', '10.0.0.1/24']:
    try:
        print(ipaddress.ip_network(bad) if '/' in bad else ipaddress.ip_address(bad))
    except ValueError as e:
        print(type(e).__name__, e)
print(ipaddress.ip_address('fe80::1%eth0'), ipaddress.ip_address('fe80::1%eth0').scope_id, hash(a) == hash(ipaddress.ip_address('192.168.1.10')))
print(format(a, 'b')[:12], format(six, 'X')[:8], f"{a:#x}", ipaddress.ip_network('192.168.0.0/16').overlaps(n), n.subnet_of(ipaddress.ip_network('10.0.0.0/8')))
print(list(ipaddress.ip_network('10.0.0.0/24').address_exclude(ipaddress.ip_network('10.0.0.0/26'))))
'\n'.join(_out)
'''),
    ('decimal_module', "modules", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import decimal
from decimal import Decimal, getcontext, localcontext, ROUND_HALF_UP, InvalidOperation
print(Decimal('0.1') + Decimal('0.2'), Decimal(1) / Decimal(7), Decimal('1.10') * 3, Decimal(0.1), Decimal('3.14159').quantize(Decimal('0.01')))
print(Decimal('2').sqrt(), Decimal('1e100') + 1, Decimal('-0'), Decimal('NaN'), Decimal('Infinity') > 10**100, Decimal('1.5').to_integral_value(), round(Decimal('2.675'), 2))
getcontext().prec = 50
print(Decimal(1) / Decimal(3), Decimal(2).ln(), Decimal(10).log10(), Decimal(1).exp())
with localcontext() as ctx:
    ctx.prec = 5
    ctx.rounding = ROUND_HALF_UP
try:
    Decimal('abc')
except InvalidOperation as e:
    print('InvalidOperation', type(e).__name__)
print(repr(Decimal('12.50')), str(Decimal('1E+2')), f"{Decimal('1234.5678'):,.2f}", Decimal('7.00').normalize(), Decimal(5).as_tuple(), int(Decimal('42.9')), float(Decimal('0.5')), Decimal('1.23') == 1.23, hash(Decimal(2)) == hash(2), Decimal(3) % 2, divmod(Decimal(7), 2))
print(sorted([Decimal('1.1'), Decimal('0.9'), 1]), sum([Decimal('0.1')] * 10), Decimal('100').as_integer_ratio(), Decimal.from_float(0.25), Decimal(2) ** 100)
'\n'.join(_out)
'''),
    ('pickle_module', "modules", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import pickle, collections, datetime
from dataclasses import dataclass
data = {'a': [1, 2.5, None, True, 'x' * 3, b'\x00\xff', (1, 2), {3, 4}, frozenset([5])], 'big': 2**100, 'neg': -7, 'nested': [[[]]], 'uni': 'héllo ✓'}
for proto in range(0, pickle.HIGHEST_PROTOCOL + 1):
    s = pickle.dumps(data, protocol=proto)
    print(proto, pickle.loads(s) == data, len(s))
print(pickle.dumps([1, 'a', None], protocol=4))
print(pickle.loads(b'\x80\x04\x95\x0b\x00\x00\x00\x00\x00\x00\x00]\x94(K\x01\x8c\x01a\x94Ne.'))
import collections
print(pickle.loads(pickle.dumps(collections.OrderedDict(a=1))), pickle.loads(pickle.dumps(collections.Counter('aab'))))
lst = [1]; lst.append(lst); r = pickle.loads(pickle.dumps(lst)); print(r[1] is r)
print(pickle.loads(pickle.dumps(datetime.date(2024, 2, 29))), pickle.loads(pickle.dumps(datetime.timedelta(days=1, seconds=5))))
try:
    pickle.dumps(lambda: 1)
except (pickle.PicklingError, AttributeError) as e:
    print(type(e).__name__)
'\n'.join(_out)
'''),
    ('tokenize_module', "modules", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import io, tokenize, token
src = 'def f(a, b=2):\n    # a comment\n    return a + b  # trailing\n\nx = [1,\n     2]\nif x:\n    print("hi", \'there\', 3.5e-2, 0x1F)\n'
for t in tokenize.generate_tokens(io.StringIO(src).readline):
    print(tokenize.tok_name[t.type], repr(t.string), t.start, t.end)
toks = list(tokenize.tokenize(io.BytesIO(b"a = 1\n").readline))
print([(tokenize.tok_name[t.type], t.string) for t in toks])
print(tokenize.untokenize([(t.type, t.string) for t in tokenize.generate_tokens(io.StringIO("a=1+2\n").readline)]))
print(token.NAME, token.OP, token.EXACT_TOKEN_TYPES['+'], token.ISTERMINAL(token.NAME), tokenize.detect_encoding(io.BytesIO(b"# -*- coding: latin-1 -*-\nx").readline))
try:
    list(tokenize.generate_tokens(io.StringIO("(1,\n").readline))
except tokenize.TokenError as e:
    print('TokenError', e.args)
for src in ['x = 1', '# only\n', 's = """a\nb"""\n', 'a = 1 \\\n  + 2\n', 'x = 0xFF_FF + 1_000j + 1e10\n', 'λ = 1\n', '@decorator\ndef f(): ...\n', 'x: int = 3 ** 2 // 4 != 5 -> 6\n', 'async def f():\n    await x\n']:
    try:
        print(repr(src), [(tokenize.tok_name[t.type], t.string, t.start, t.end) for t in tokenize.generate_tokens(io.StringIO(src).readline)])
    except Exception as e:
        print(repr(src), type(e).__name__, e)
'\n'.join(_out)
'''),
    ('lazy_itertools', "modules", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import itertools as it
from itertools import *
print(list(chain([1, 2], (3,), 'ab')), list(chain.from_iterable([[1], [2, 3]])), next(chain(repeat(7))))
print(list(islice(count(), 5)), list(islice(count(10, 2), 2, 8, 2)), list(islice('abcdefg', 2, None)), list(islice(cycle('ab'), 5)))
print(list(accumulate([1, 2, 3, 4])), list(accumulate([1, 2, 3], _mul := (lambda a, b: a * b))), list(accumulate([1, 2], initial=100)), list(accumulate([])))
print(list(batched('abcdefg', 3)), list(compress('abcdef', [1, 0, 1, 0, 1, 1])), list(dropwhile(lambda x: x < 5, [1, 4, 6, 4, 1])), list(filterfalse(None, [0, 1, '', 'a'])))
print([(k, list(g)) for k, g in groupby('AAAABBBCCDAABBB')], [k for k, g in groupby([1, 1, 2])])
g = groupby('aabb'); k1, g1 = next(g); k2, g2 = next(g); print(k1, list(g1), k2, list(g2))
print(list(pairwise('ABCD')), list(starmap(pow, [(2, 5), (3, 2)])), list(takewhile(lambda x: x < 5, [1, 4, 6, 1])))
a, b = tee(iter([1, 2, 3])); print(next(a), list(a), list(b), len(tee('x', 3)))
print(list(zip_longest('ABCD', 'xy', fillvalue='-')), list(zip_longest()), list(product('ab', range(2))), list(product([0, 1], repeat=2)), list(product()))
print(list(permutations('ABC', 2)), list(permutations(range(3))), list(combinations('ABCD', 2)), list(combinations_with_replacement('ABC', 2)), list(combinations('AB', 3)))
print(repr(count(3)), repr(count(1, 0.5)), repr(repeat('x', 2)), list(repeat(1, 3)), list(repeat(1, -1)), list(product('a', [])))
for f in [lambda: islice('a', -1), lambda: batched('a', 0), lambda: combinations('a', -1), lambda: permutations('a', -1), lambda: list(batched('abc', 2, strict=True))]:
    try:
        f()
    except ValueError as e:
        print('ValueError', e)
seen = []
def src():
    for i in range(3):
        seen.append(i)
        yield i
c = chain(src()); print(seen, next(c), seen)
print(sum(1 for _ in islice(count(), 1000)), list(map(lambda t: t[0], zip(count(), 'ab'))))
'\n'.join(_out)
'''),
    ('bytes_methods', "builtins", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
b = b'  Hello World\tx\r\ny\nz  '
print(b.partition(b'o'), b.rpartition(b'o'), b.partition(b'#'), b.rpartition(b'#'), b.removeprefix(b'  He'), b.removesuffix(b'  '), b.splitlines(), b.splitlines(True))
print(b.split(), b.split(None, 1), b.rsplit(None, 1), b.rsplit(b'o', 1), b.split(b'o', 1), b'a,b,,c'.rsplit(b','), b'  a  b  '.rsplit(maxsplit=1), b''.split(), b'x'.split(b'x'))
print(b'ab'.center(7, b'*'), b'abc'.center(6), b'ab'.ljust(5, b'.'), b'ab'.rjust(5), b'-42'.zfill(6), b'42'.zfill(1), b'a\tbc\td'.expandtabs(4), b'hELLO wORLD'.capitalize(), b'Hi There'.swapcase(), b"they're bill's".title())
print(b'abc1'.isalnum(), b'ab'.isalpha(), b'\xe9'.isalpha(), b'\x80'.isascii(), b'123'.isdigit(), b'abc'.islower(), b'ABC1'.isupper(), b' \t'.isspace(), b'Hello World'.istitle(), b''.isalpha(), b''.isascii())
t = bytes.maketrans(b'abc', b'xyz'); print(b'aabbcc'.translate(t), b'hello'.translate(None, b'l'), len(t))
print(b'abcabc'.count(b'a', 1), b'abcabc'.startswith((b'x', b'ab')), b'abcabc'.endswith(b'b', 0, 5), b'aaa'.replace(b'a', b'b', 2), b'ab'.replace(b'', b'-'), b''.replace(b'', b'x'))
print(float.fromhex('0x1.8p1'), float.fromhex('-0x1p-2'), float.fromhex('inf'), float.fromhex('0x.8'), float.fromhex(' 0X10 '), float.fromhex((1.1).hex()) == 1.1, float.fromhex('0x1p-1074'))
ba = bytearray(b'a,b'); print(ba.partition(b','), ba.split(b','), ba.upper(), ba.find(b'b'))
print(float.fromhex('0x1.fffffffffffff8p1023') if False else 0, float.fromhex('0x1.000000000000081p0'), float.fromhex('0x1.0000000000000800p0'), float.fromhex('0x1.0000000000001800p0'), float.fromhex('0x0.0000000000001p-1022'), float.fromhex('0x1.8p-1074'), float.fromhex('0x1p-1076'), float.fromhex('0x1.fffffffffffffp1023'))
try: float.fromhex('0x1p1024')
except OverflowError as e: print(e)
try: float.fromhex('zz')
except ValueError as e: print(e)
'\n'.join(_out)
'''),
    ('regex_bytes_and_escapes', "regex", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import re
p = re.compile(br'^[ \t\f]*(?:[#\r\n]|$)', re.ASCII)
print(p.match(b'  # x'), bool(p.match(b'\r\n')), p.match(b'x = 1'), p.pattern, p.flags)
print(re.findall(rb'\d+', b'a1 b22 c333'), re.sub(rb'\s+', b'-', b'a  b\tc'), re.split(rb',', b'a,b,,c'), re.subn(b'a', b'X', b'banana'))
m = re.search(rb'(?P<k>\w+)=(\w+)', b'key=val')
print(m.group(), m.group(1), m['k'], m.groups(), m.groupdict(), m.span(2), m.string, m)
print(re.escape(b'a.b'), re.sub(rb'(\w)', lambda m: m.group(1).upper(), b'ab'), re.compile(b'\xe9').search(b'caf\xe9'))
for bad in [lambda: re.search(b'a', 'a'), lambda: re.search('a', b'a'), lambda: re.sub(b'a', 'x', b'a'), lambda: re.compile(b'a', re.U)]:
    try:
        bad()
    except (TypeError, ValueError) as e:
        print(type(e).__name__, e)
print(re.compile('a').flags, re.compile('a', re.I).flags, re.compile('a', re.A).flags, re.compile(b'a').flags, re.compile(b'a', re.I).flags, re.compile('(?i)a').flags)
print(repr(re.compile(b'a\\d')), repr(re.compile(b'a', re.I)), re.match('ab', 'abc'))
print(re.fullmatch(rb'[\x00-\xff]+', bytes(range(256))) is not None, re.findall(rb'\w', b'a\xe9_1'))
print(re.findall(r'[\x41-\x43]', 'ABCD'), re.findall(r'\x41', 'AB'), re.findall(r'[é]', 'café'), re.findall(r'é', 'café'), re.findall(r'[\101]', 'AB'), re.findall(r'\0', 'a\0'), re.findall(r'[\t\n]', 'a\tb\n'), re.findall(r'\N{BULLET}', '•'))
bad = [i for i in range(256) if not re.fullmatch(rb'[\x00-\xff]', bytes([i]))]
print(bad, re.findall(r'\x41B\U00000043', 'ABC'), re.findall(r'[\0-\x08]', '\x00\x05\x09'), re.findall(r'\101(.)\1', 'Abb'), re.findall(r'(a)\1', 'aa'))
'\n'.join(_out)
'''),
    ('numeric_and_tuple_hashes', "builtins", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
print(hash(2), hash(-1), hash(-2), hash(2**61 - 1), hash(2**61), hash(2**64), hash(-(2**64)), hash(10**30), hash(1.5), hash(0.1), hash(-0.5), hash(1e300), hash(float('inf')), hash(float('-inf')), hash(2.0), hash(True), hash(2**100) == hash(float(2**100)))
print(hash(None), hash((1, 2)), hash(()), hash((1, (2, 3.5))), hash(-1.0), hash(5e-324), hash(-2.5e-310), hash(123.456), hash(2**1000), hash(-(2**61)))
import sys
print(sys.hash_info.modulus, sys.hash_info.inf, sys.int_info.default_max_str_digits, sys.float_info.epsilon, sys.float_info.max, sys.float_info.dig)
'\n'.join(_out)
'''),
    ('set_updates_take_many_iterables', "builtins", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
s = {1, 2, 3}
s.intersection_update({1, 2}, [2]); print(s)
s = {1, 2, 3}; s.difference_update([1], (2,)); print(s)
s = {1, 2}; s.symmetric_difference_update([2, 3]); print(s)
print({1}.union([2], (3,)), {1, 2, 3}.intersection([1, 2], {2}), {1, 2, 3}.difference([1], [2]), {1}.union(), {1, 2}.issubset(range(5)))
s = {1}; s.update([2], {3}); print(s)
'\n'.join(_out)
'''),
    ('ranges_past_64_bits', "builtins", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
r = range(2**64, 2**64 + 10, 3)
print(r, len(r), list(r), r[0], r[-1], r[1:3], 2**64 + 3 in r, 2**64 + 4 in r, r.index(2**64 + 6), r.count(2**64), r.start, r.stop, r.step, bool(r), list(reversed(r)))
big = range(2**100)
print(big[2**99], 2**99 in big, -1 in big, big[-1] == 2**100 - 1, big[10:20:5], bool(range(2**70, 2**70)), range(2**70, 0, -2**68)[1])
try:
    len(big)
except OverflowError as e:
    print('OverflowError', e)
for i in range(2**64, 2**64 + 2):
    print(i)
print(range(2**64) == range(0, 2**64), range(5, 2**70, 2**70) == range(5, 6), sum(range(2**63 - 2, 2**63 + 2)), list(range(-2**63 - 1, -2**63 + 1)))
class I:
    def __index__(self):
        return 3
print(list(range(I())), [x for x in range(I())])
'\n'.join(_out)
'''),
    ('text_over_bytes_and_crlf_lines', "modules", r'''
__name__ = '__main__'
_out = []
def print(*a, sep=' '):
    _out.append(sep.join(str(x) for x in a))
import io
b = io.BytesIO('héllo\r\nwörld\nend'.encode('utf-8'))
t = io.TextIOWrapper(b, encoding='utf-8')
print(t.readline(), repr(t.readline()), t.read(), t.encoding)
w = io.BytesIO(); t2 = io.TextIOWrapper(w, encoding='utf-16-le', newline='\r\n'); n = t2.write('a\nb'); t2.flush(); print(n, w.getvalue())
t3 = io.TextIOWrapper(io.BytesIO(b'x\ny\n')); print(list(t3), io.TextIOWrapper(io.BytesIO(b'one\ntwo')).readlines())
s = io.StringIO('a\r\nb\r\nc'); print([s.readline(), s.readline(), s.readline(), s.readline()], io.StringIO('p\nq').readlines())
'\n'.join(_out)
'''),
    ("complex_numbers", "numbers", r'''
z = complex(3, -4)
w = 1.5 + 2j
parts = (z.real, z.imag, abs(z), z.conjugate(), -z, +w, z == 3 - 4j, z != w, 2j == complex(0, 2))
arith = (z + w, z - w, z * w, z / w, z ** 2, (1j) ** 2, 2 ** 1j == 2 ** complex(0, 1), 1 + 0j == 1)
texts = (complex("1+2j"), complex(" (3-1j) "), complex("-j"), complex("2.5e1J"), complex(1, 2) * 0, complex(-0.0, -0.0), complex(real=1, imag=2))
fmt = (repr(1j), repr(complex(-0.0, 0)), str(1 + 2j), f"{z:.2f}", format(w, "10.3g"), f"{z:^20}", repr(complex(float("inf"), float("nan"))))
errs = []
for bad in [lambda: 1j < 2j, lambda: complex("1+"), lambda: 1j % 2, lambda: int(1j), lambda: 0j ** -1, lambda: round(1j), lambda: complex("1", 2), lambda: 1 / 0j]:
    try:
        bad()
    except Exception as e:
        errs.append((type(e).__name__, str(e)))
repr((parts, arith, texts, fmt, errs, hash(2 + 0j) == hash(2), {1j: "a"}[complex(0, 1)], isinstance(1j, complex), type(1j).__name__))
'''),
    ("cmath_functions", "stdlib", r'''
import cmath, math
def r(z):
    return complex(round(z.real, 10), round(z.imag, 10))
funcs = ["sqrt", "exp", "log", "log10", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh"]
vals = [complex(0.5, 0.25), complex(-2, 1), complex(3, -0.5), complex(-0.25, -1.5), 4, -3]
table = [[r(getattr(cmath, f)(v)) for v in vals] for f in funcs]
special = (cmath.sqrt(-0.0), cmath.sqrt(complex(-0.0, -0.0)), cmath.sqrt(complex(-math.inf, 1)), cmath.exp(complex(-math.inf, 0)),
           cmath.log(complex(0, math.inf)), cmath.acos(complex(math.inf, math.nan)), cmath.polar(-1), round(cmath.phase(-1j), 12),
           cmath.rect(math.inf, 0), cmath.isinf(complex(1, math.inf)), cmath.isnan(complex(math.nan, 1)), cmath.isclose(1j, 1.0000000001j))
errs = []
for bad in [lambda: cmath.log(0), lambda: cmath.exp(1000), lambda: cmath.atanh(1), lambda: cmath.sqrt("x"), lambda: cmath.log(2, 1), lambda: cmath.rect(1, math.inf), lambda: cmath.sqrt(1, 2)]:
    try:
        bad()
    except Exception as e:
        errs.append((type(e).__name__, str(e)))
repr((table, special, errs, cmath.log(0, 2), cmath.infj, cmath.nanj, r(cmath.log(8, 2)), cmath.pi == math.pi))
'''),
    ("math_parity", "stdlib", r'''
import math
def r(x):
    return round(x, 10) if isinstance(x, float) else x
exact = (math.fsum([1e-16, 1, 1e16]), math.fsum([0.1] * 10), math.hypot(3, 4, 12), math.hypot(5e-324, 5e-324), math.dist((1, 2), (4, 6)),
         math.sumprod([1, 2, 3], [4, 5, 6]), math.sumprod([0.1, 0.2, 0.3], [1, 2, 3]), math.prod([1.5, 2], start=3), math.ldexp(1.5, -1074),
         math.frexp(5e-324), math.modf(-3.0), math.modf(math.inf), math.nextafter(1.0, 2.0), math.nextafter(0.0, -1.0, steps=3), math.ulp(1.0),
         math.fma(2.0, 3.0, 1.0), math.remainder(5, 2), math.remainder(-7, 2), math.fmod(-7, 2), math.copysign(0.0, -1), math.atan2(0.0, -0.0),
         math.pow(-2, 3), math.pow(math.nan, 0), math.comb(10**20, 2), math.perm(5), math.isqrt(10**40 + 1), math.log(2**10000),
         math.log2(2**1100), math.log10(10**400), math.gamma(5), math.lgamma(3), math.degrees(math.pi), math.radians(180))
approx = [r(f(x)) for f in (math.gamma, math.lgamma, math.erf, math.erfc, math.asinh, math.atanh, math.cbrt, math.expm1, math.log1p) for x in (0.5, -0.25, 0.75)]
errs = []
for bad in [lambda: math.sqrt(-1), lambda: math.log(0), lambda: math.exp(1000), lambda: math.gamma(0), lambda: math.gamma(-2), lambda: math.lgamma(-1),
            lambda: math.pow(0, -1), lambda: math.fmod(1, 0), lambda: math.ldexp(1, 5000), lambda: math.fsum([math.inf, -math.inf]),
            lambda: math.fsum([1e308, 1e308]), lambda: math.fma(math.inf, 0, 1), lambda: math.sumprod([1], [1, 2]), lambda: math.comb(-1, 2),
            lambda: math.log(2, 1), lambda: math.sqrt(), lambda: math.atan2(1), lambda: math.sqrt(x=1), lambda: math.isclose(1), lambda: math.trunc("x"),
            lambda: math.nextafter(1, 2, steps=-1), lambda: math.factorial(2.5), lambda: math.erf("x"), lambda: math.sin(math.inf)]:
    try:
        bad()
    except Exception as e:
        errs.append((type(e).__name__, str(e)))
repr((exact, approx, errs, math.sqrt(math.nan) != math.sqrt(math.nan), math.sqrt.__name__, repr(math.sqrt), math.__name__))
'''),
    ("fractions_and_numbers", "stdlib", r'''
from fractions import Fraction
from decimal import Decimal
import numbers, math, pickle
f = Fraction(3, 4)
out = (f + Fraction(1, 4), Fraction("3.1415"), Fraction(0.1).limit_denominator(100), Fraction(7, -21), str(Fraction(22, 7)), f ** 2, f ** -2,
       Fraction(1, 3) < 0.34, hash(Fraction(1, 2)) == hash(0.5), round(Fraction(7, 2)), round(Fraction(22, 7), 2), math.floor(Fraction(-7, 2)),
       Fraction(Decimal("1.25")), f"{Fraction(1, 3):.5f}", f"{Fraction(22, 7):>10}", divmod(Fraction(7, 2), 2), Fraction(1, 2) + 1j,
       pickle.loads(pickle.dumps(Fraction(3, 8))), sum(Fraction(1, n) for n in range(1, 6)), Fraction(" -1.5e3 "), sorted([Fraction(1, 2), 0.3, 1]))
abc = ([isinstance(x, numbers.Complex) for x in (1, 1.5, 1j, True, "x")], [isinstance(x, numbers.Real) for x in (1, 1.5, 1j)],
       issubclass(complex, numbers.Number), issubclass(Fraction, numbers.Rational), isinstance(Fraction(1, 2), numbers.Real))
errs = []
for bad in [lambda: Fraction(1, 0), lambda: Fraction("abc"), lambda: Fraction(1.5, 2)]:
    try:
        bad()
    except Exception as e:
        errs.append((type(e).__name__, str(e)))
repr((out, abc, errs, pickle.loads(pickle.dumps(1 + 2j))))
'''),
    ("match_subscript_skipped_group", "regex", r'''
import re
m = re.fullmatch(r"(?P<a>x)?(?P<b>y)", "y")
out = [m["a"], m["b"], m[0], m[1], m.group("a", "b")]
for bad in [lambda: m["zz"], lambda: m[3], lambda: m.group(1, 5)]:
    try:
        bad()
    except IndexError as e:
        out.append(str(e))
repr(out)
'''),
    ("more_builtin_exceptions", "errors", r'''
import builtins
names = ["ChildProcessError", "InterruptedError", "ProcessLookupError", "BufferError", "MemoryError", "ReferenceError", "SystemError",
         "PythonFinalizationError", "TabError", "EncodingWarning"]
parents = ["Exception", "OSError", "RuntimeError", "SyntaxError", "IndentationError", "ValueError", "UnicodeError", "Warning"]
rows = []
for n in names:
    cls = getattr(builtins, n)
    try:
        raise cls("boom")
    except Exception as e:
        caught = (type(e).__name__, str(e))
    rows.append((n, [p for p in parents if issubclass(cls, getattr(builtins, p))], caught))
try:
    raise TabError("t")
except SyntaxError as e:
    rows.append(type(e).__name__)
repr((rows, issubclass(UnicodeTranslateError, ValueError)))
'''),
    ("re_scanner_lexes", "regex", r'''
import re
def s_ident(scanner, token): return ("id", token)
def s_op(scanner, token): return ("op", token, scanner.match.span())
scanner = re.Scanner([
    (r"[a-zA-Z_]\w*", s_ident),
    (r"\d+\.\d*", lambda s, t: float(t)),
    (r"\d+", lambda s, t: int(t)),
    (r"=|\+|-|\*|/", s_op),
    (r"\s+", None),
    (r"#.*", "comment"),
])
repr((scanner.scan("sum = 3*foo + 312.50 + bar # hi"), scanner.scan("x = 1 $ 2"), re.Scanner.__name__))
'''),
    ("random_is_cpythons_mersenne_twister", "stdlib", r'''
import random
random.seed(5)
a = [random.random() for _ in range(3)]
b = [random.randint(1, 100) for _ in range(5)]
xs = list(range(10))
random.shuffle(xs)
c = (random.choice("abcdef"), random.sample(range(50), 4), random.getrandbits(70), round(random.gauss(0, 1), 12), round(random.uniform(1, 2), 12))
r = random.Random("seed text")
d = [r.randrange(1000) for _ in range(3)]
state = r.getstate()
e = r.random()
r.setstate(state)
repr((a, b, xs, c, d, e == r.random(), random.Random(7).choices("xyz", k=5)))
'''),
    ("json_load_and_dump_take_files", "stdlib", r'''
import io, json
buf = io.StringIO()
json.dump({"a": [1, 2], "b": None}, buf, sort_keys=True)
text = buf.getvalue()
repr((text, json.load(io.StringIO(text)), json.load(io.StringIO('[1.5, "x"]'))))
'''),
    ("exception_groups", "errors", r'''
eg = ExceptionGroup("many", [ValueError(1), TypeError(2), ExceptionGroup("inner", [ValueError(3)])])
match, rest = eg.split(ValueError)
only = eg.subgroup(lambda e: isinstance(e, TypeError))
try:
    ExceptionGroup("x", [KeyboardInterrupt()])
    made = "made"
except TypeError as e:
    made = str(e)
beg = BaseExceptionGroup("b", [KeyboardInterrupt()])
repr((str(eg), repr(match), repr(rest), repr(only), made, type(beg).__name__, type(BaseExceptionGroup("c", [ValueError()])).__name__,
      isinstance(eg, Exception), issubclass(ExceptionGroup, BaseExceptionGroup), eg.derive([OSError()]).message,
      len(eg.exceptions), eg.subgroup(KeyError)))
'''),
    ("oserror_picks_its_subclass", "errors", r'''
e = OSError(2, "No such file", "a.txt")
f = OSError(13, "Denied", "x", None, "y")
g = OSError("plain")
repr((type(e).__name__, e.errno, e.strerror, e.filename, str(e), e.args, type(f).__name__, str(f), f.filename2,
      type(OSError(17, "x")).__name__, g.errno, str(g), isinstance(e, OSError), type(OSError(99, "z")).__name__))
'''),
    ("module_spec_and_meta_path", "imports", r'''
import sys, importlib.util
import email.message
class Finder:
    def find_spec(self, name, path=None, target=None):
        if name == "virtual_mod":
            return importlib.util.spec_from_loader(name, self)
        return None
    def create_module(self, spec):
        return None
    def exec_module(self, module):
        module.answer = 42
sys.meta_path.insert(0, Finder())
import virtual_mod
try:
    import no_such_module_anywhere
    missing = "found"
except ModuleNotFoundError as e:
    missing = (type(e).__name__, e.name)
sys.meta_path.pop(0)
repr((email.message.__spec__.name, email.message.__spec__.parent, email.__spec__.submodule_search_locations is not None,
      virtual_mod.answer, missing, "virtual_mod" in sys.modules, issubclass(ModuleNotFoundError, ImportError)))
'''),
    ("typing_runtime_parity", "typing", r'''
__name__ = '__main__'
from typing import (Generic, TypeVar, List, Dict, Optional, Union, Callable, Literal, Annotated, NamedTuple,
                    TypedDict, Protocol, runtime_checkable, NewType, get_type_hints, get_origin, get_args, ParamSpec,
                    TypeVarTuple, Unpack, Tuple, Any, cast, overload, get_overloads, final)
T = TypeVar("T")
V = TypeVar("V", bound=int)
P = ParamSpec("P")
Ts = TypeVarTuple("Ts")
class Box(Generic[T]):
    def __init__(self, item: T):
        self.item = item
class IntBox(Box[int]):
    pass
@runtime_checkable
class Closable(Protocol):
    def close(self) -> None: ...
class F:
    def close(self): pass
class Movie(TypedDict, total=False):
    name: str
    year: int
class Pt(NamedTuple):
    x: int
    y: int = 0
UserId = NewType("UserId", int)
def f(a: int, b: "List[str]" = None) -> Optional[int]: ...
repr((T, V, V.__bound__, P.args, Ts, Box[int], IntBox.__orig_bases__, IntBox.__mro__, Box.__parameters__,
      Union[int, str], Optional[int], Dict[str, T][int], Callable[[int], str], Callable[P, T][[int], str],
      Literal[1, 2, 1], Annotated[int, "m"], get_args(Annotated[int, "m"]), get_origin(List[int]), Tuple[int, Unpack[Ts]],
      isinstance(F(), Closable), isinstance(1, Closable), sorted(Movie.__optional_keys__), Pt(1), Pt._field_defaults,
      UserId(5), UserId.__supertype__, get_type_hints(f), int | T, T | None, cast(str, 1), Any))
'''),
    ("annotations_are_evaluated", "syntax", r'''
class C:
    x: int = 1
    y: list[str]
    z: "Later"
def g(a: int, *args: str, b: "bytes" = b"", **kw: float) -> dict[str, int]:
    pass
n: int = 5
repr((C.__annotations__, g.__annotations__, __annotations__ if "__annotations__" in dir() else None))
'''),
    ("future_annotations_are_text", "syntax", r'''
from __future__ import annotations
class C:
    x: list[int]
    y: Optional["X"] | None
def g(a: dict[str, int], b: "q" = 1) -> tuple[int, ...]:
    pass
repr((C.__annotations__, g.__annotations__))
'''),
    ("compile_eval_exec_and_syntax_errors", "syntax", r'''
c = compile("1 + 2", "<s>", "eval")
ns = {}
exec(compile("x = 5\ny = x * 2", "<s>", "exec"), ns)
errors = []
for src, mode in [("1 +", "eval"), ("x = 1", "eval"), ("def", "exec")]:
    try:
        compile(src, "<s>", mode)
    except SyntaxError:
        errors.append("SyntaxError")
try:
    eval("(")
except SyntaxError:
    errors.append("eval SyntaxError")
try:
    compile("1", "<s>", "bad")
except ValueError as e:
    errors.append(str(e))
repr((type(c).__name__, c.co_filename, eval(c), ns["y"], eval(compile("z = 1", "<s>", "exec"), {}), errors))
'''),
    ("generic_alias_substitution", "typing", r'''
from typing import TypeVar
T = TypeVar("T")
K = TypeVar("K")
try:
    isinstance([], list[int])
    inst = "checked"
except TypeError as e:
    inst = str(e)
try:
    list[int][str]
    sub = "subscripted"
except TypeError as e:
    sub = str(e)
repr((list[T][int], dict[K, list[T]][str, int], dict[K, list[T]].__parameters__, tuple[int, ...], tuple[()],
      list[int] == list[int], hash(list[int]) == hash(list[int]), list[int].__origin__, list[int].__args__,
      [*tuple[int, str]], inst, sub, type[int], (int | list[T])[str]))
'''),
    ("class_dict_is_a_read_only_view", "classes", r'''
class C:
    def get(self): return "method"
    def items(self): return "method"
    x = 5
d = C.__dict__
try:
    d["y"] = 1
    w = "written"
except TypeError as e:
    w = str(e)
repr((type(d).__name__, d.get("x"), d["x"], sorted(k for k in vars(C) if not k.startswith("__")), w,
      "bit_length" in int.__dict__, "__reduce_ex__" in object.__dict__, "__reduce_ex__" in list.__dict__,
      "__int__" in dir(int), "append" in vars(list), type(vars(int)).__name__))
'''),
    ("functions_are_descriptors", "classes", r'''
class O:
    def m(self):
        return self.v
def free(self, k):
    return k * 2
o = O()
object.__setattr__.__get__(o)("v", 7)
cm = classmethod(free)
sm = staticmethod(free)
repr((o.v, O.m.__get__(o)(), free.__get__(o)(3), free.__get__(None, O) is free, cm.__func__ is free, sm.__wrapped__ is free,
      sm.__func__ is free, property(lambda s: 1).__isabstractmethod__))
'''),
    ("generator_throw_forms", "generators", r'''
class E(Exception):
    def __init__(self, message):
        super().__init__(message)
        self.tag = 1
def g():
    try:
        yield 1
    except E as e:
        e.tag += 1
        raise
out = []
for how in ("inst", "pair", "typeval"):
    x = g(); next(x)
    try:
        if how == "inst": x.throw(E("a"))
        elif how == "pair": x.throw(E, E("b"))
        else: x.throw(E, "c")
    except E as e:
        out.append((how, e.args, e.tag))
repr(out)
'''),
    ("with_keeps_the_raised_object", "errors", r'''
from contextlib import contextmanager
class E(Exception):
    def __init__(self, message, ctx=None):
        super().__init__(message)
        self.message = message
        self.ctx = ctx
    def __str__(self):
        return self.message
@contextmanager
def aug():
    try:
        yield
    except E as e:
        if e.ctx is None:
            e.ctx = "set"
        raise
err = E("boom")
try:
    with aug():
        raise err
except E as e:
    caught = (e is err, e.ctx, str(e))
repr(caught)
'''),
    ("sys_extras_and_small_modules", "stdlib", r'''
import sys, os, shutil, urllib.parse
os.environ["COLUMNS"] = "100"
os.environ["LINES"] = "30"
size = shutil.get_terminal_size()
del os.environ["COLUMNS"], os.environ["LINES"]
repr((sys.flags.optimize, sys.flags.dev_mode, sys.implementation.name, sys.implementation.cache_tag is None or isinstance(sys.implementation.cache_tag, str),
      sys.getsizeof(1), sys.getsizeof(1.0), sys.getsizeof(""), sys.getsizeof("abc"), sys.getsizeof([]), sys.getsizeof(()), sys.getsizeof(b"ab"),
      isinstance(sys.prefix, str), size, size.columns, "https" in urllib.parse.uses_netloc, urllib.parse.scheme_chars[-3:],
      __debug__, bool.__mro__, KeyError.__bases__, ValueError.__base__))
'''),
    ("inspect_refuses_builtins", "stdlib", r'''
import inspect
out = []
for c in (int, str):
    try:
        inspect.signature(c)
        out.append("signature")
    except ValueError:
        out.append("ValueError")
def f(a, b: int = 2, *c, d, **e) -> str: ...
repr((out, str(inspect.signature(f)), inspect.isfunction(list.append), inspect.isbuiltin(len)))
'''),
    ("pep560_bases_and_module_getattr", "classes", r'''
import types
class Alias:
    def __init__(self, *real):
        self.real = real
    def __mro_entries__(self, bases):
        return self.real
class A: pass
class B: pass
class C(Alias(A, B), int.__class__.__mro__[-1]):
    pass
mod = types.ModuleType("lazy")
exec("def __getattr__(name):\n    if name == 'late':\n        return 99\n    raise AttributeError(name)", mod.__dict__)
try:
    mod.nope
    nope = "found"
except AttributeError:
    nope = "AttributeError"
repr(([k.__name__ for k in C.__mro__], [getattr(b, "__name__", type(b).__name__) for b in C.__orig_bases__], mod.late, nope))
'''),
    ("json_errors_say_where", "json", r'''
import json
out = []
for s in ["", "[1,", '{"a" 1}', "01", "[1,]", "{'a':1}", '"a\x01"', '"\\x"', '  [1]  x', "[\n1,\n2,\n]"]:
    try:
        json.loads(s)
        out.append("ok")
    except json.JSONDecodeError as e:
        out.append((str(e), e.pos, e.lineno, e.colno, isinstance(e, ValueError)))
repr(out)
'''),
    ("json_hooks_and_constants", "json", r'''
import json, decimal, collections
repr((
    json.loads('[NaN, Infinity, -Infinity, 1e2, -0]', parse_constant=lambda c: c),
    json.loads('{"a": 1.10}', parse_float=decimal.Decimal),
    json.loads('[1, 2]', parse_int=str),
    json.loads('{"b": 1, "a": 2, "b": 3}', object_pairs_hook=list),
    json.loads('{"b": 1, "c": {"d": 2}}', object_hook=lambda d: sorted(d)),
    type(json.loads('{"x": 1}', object_pairs_hook=collections.OrderedDict)).__name__,
    json.loads(b'{"a": [1, 2]}'),
    json.loads('"\\ud83d\\ude00 \\u00e9"'),
    json.JSONDecoder().raw_decode('[1] xyz'),
    json.dumps([float("inf"), -float("inf")]),
))
'''),
    ("json_encoders", "json", r'''
import json
class P:
    def __init__(self, x):
        self.x = x
class Enc(json.JSONEncoder):
    def default(self, o):
        if isinstance(o, P):
            return {"P": o.x}
        return super().default(o)
errors = []
a = []
a.append(a)
for f in (lambda: json.dumps(a), lambda: json.dumps(float("nan"), allow_nan=False), lambda: json.dumps({(1, 2): 3}), lambda: json.dumps(object(), cls=Enc), lambda: json.dumps({1: 1, "a": 2}, sort_keys=True)):
    try:
        f()
    except (TypeError, ValueError) as e:
        errors.append((type(e).__name__, str(e)))
repr((
    json.dumps([P(1), P(2)], cls=Enc),
    Enc(indent=1).encode({"a": [P(5)]}),
    json.dumps({"p": P(3)}, default=lambda o: o.x),
    json.dumps({1: 2, (1, 2): 3, 2.5: 1, None: 0, False: 4}, skipkeys=True),
    json.dumps({"b": [1, {}]}, indent=0),
    errors,
))
'''),
    ("user_collections_and_chainmap", "stdlib", r'''
from collections import UserDict, UserList, UserString, ChainMap, defaultdict
import collections.abc as cabc
class Lower(UserDict):
    def __setitem__(self, k, v):
        super().__setitem__(k.lower(), v)
d = Lower(); d["X"] = 1
u = UserList([3, 1, 2]); u.sort(); u.append(5)
s = UserString("hello world")
c = ChainMap({"a": 1}, {"a": 2, "b": 3})
c2 = c.new_child({"z": 0})
try:
    del c["b"]
    k = "deleted"
except KeyError as e:
    k = str(e)
repr((dict(d), isinstance(d, cabc.MutableMapping), u.data, u[1:].data, (u + [9]).data, s.upper().data, s.split(), s.count("o"), s[1:4].data,
      dict(c), len(c), list(c2), dict(c.parents), isinstance(c, cabc.Mapping), bool(ChainMap()), k, ChainMap(defaultdict(int))["x"]))
'''),
    ("functools_is_cpythons", "stdlib", r'''
import functools, collections.abc
calls = []
@functools.lru_cache(maxsize=2)
def sq(x):
    calls.append(x)
    return x * x
[sq(n) for n in (2, 3, 2, 4, 3, 2)]
class Cell:
    def __init__(self):
        self.alive = False
    def set_state(self, state):
        self.alive = bool(state)
    set_alive = functools.partialmethod(set_state, True)
cell = Cell(); cell.set_alive()
@functools.singledispatch
def kind(arg):
    return "default"
@kind.register
def _(arg: int):
    return "int"
@kind.register(list)
def _(arg):
    return "list"
@kind.register
def _(arg: collections.abc.Mapping):
    return "mapping"
class N:
    @functools.singledispatchmethod
    def neg(self, arg):
        return "?"
    @neg.register
    def _(self, arg: int):
        return -arg
class Lazy:
    @functools.cached_property
    def v(self):
        calls.append("v")
        return 42
z = Lazy(); z.v; z.v
def orig(x):
    "doc"
orig.extra = 1
@functools.wraps(orig)
def w(*a):
    return orig(*a)
repr((calls, tuple(sq.cache_info()), sq.cache_parameters(), cell.alive, [kind(1), kind([1]), kind({}), kind("s"), kind(True)],
      N().neg(5), N().neg("x"), z.__dict__, w.__name__, w.__doc__, w.extra, w.__wrapped__ is orig, functools.reduce(lambda a, b: a + b, [1, 2, 3])))
'''),
    ("abc_old_decorators", "classes", r'''
from abc import ABC, abstractmethod, abstractproperty, abstractclassmethod, abstractstaticmethod
class Base(ABC):
    @abstractmethod
    def run(self): ...
    @abstractproperty
    def name(self): ...
    @abstractclassmethod
    def make(cls): ...
    @abstractstaticmethod
    def helper(): ...
class Half(Base):
    def run(self):
        return 1
    name = "half"
    @classmethod
    def make(cls):
        return cls
try:
    Half()
    msg = "made"
except TypeError as e:
    msg = str(e)
repr((sorted(Base.__abstractmethods__), sorted(Half.__abstractmethods__), msg, isinstance(Base.__dict__["name"], property), Base.__dict__["make"].__isabstractmethod__))
'''),
    ("descriptor_subclasses", "classes", r'''
class named(property):
    def __init__(self, fget, label=None):
        super().__init__(fget)
        self.label = label
class CM(classmethod):
    pass
class SM(staticmethod):
    pass
class Lazy(property):
    def __get__(self, obj, owner=None):
        if obj is None:
            return self
        return "lazy:" + str(self.fget(obj))
class RW(property):
    pass
class G:
    def __init__(self):
        self._v = 3
    @named
    def v(self):
        return self._v
    @CM
    def who(cls):
        return cls.__name__
    @SM
    def s():
        return "s"
    @Lazy
    def q(self):
        return 7
    def _get(self):
        return self.__dict__.get("_z", 0)
    def _set(self, value):
        self.__dict__["_z"] = value
    z = RW(_get, _set)
class H(G):
    pass
g = H()
g.z = 9
try:
    g.v = 5
    err = "set"
except AttributeError as e:
    err = str(e)
repr((g.v, G.__dict__["v"].label, isinstance(G.__dict__["v"], property), G.v is G.__dict__["v"], H.who(), g.who(), G.s(), g.q, g.z, err))
'''),
    ("comprehension_and_yield_forms", "generators", r'''
import asyncio
async def agen(n):
    for i in range(n):
        yield i * 10
async def main():
    return [x async for x in agen(3)], {x: 1 async for x in agen(2)}
def g():
    n = 0
    while not (yield n):
        n += 1
    else:
        n = -n
    return n
it = g(); seen = [next(it), it.send(False), it.send(0)]
try:
    it.send(True)
except StopIteration as e:
    seen.append(e.value)
def h():
    if False:
        pass
    elif (yield "asked") == "yes":
        r: int = yield "typed"
        return r
bases = [type("M1", (), {"a": 1}), type("M2", (), {"b": 2})]
class Both(*bases):
    pass
hg = h(); steps = [next(hg), hg.send("yes")]
try:
    hg.send(5)
except StopIteration as e:
    steps.append(e.value)
flag = True
repr((asyncio.run(main()), seen, steps, Both.a + Both.b, f"""<{
    'x'
    if flag
    else 'y'
}>"""))
'''),
    ("str_count_and_repr_escapes", "strings", r'''
d = "[\n1,\n2,\n]"
repr((d.count("\n", 0, 6), "aaaa".count("a", 1, 3), "aaaa".count("", 1, 3), "abc".count("b", 5), repr("﻿​\xad \xa0x\x7f\U000e0001")))
'''),
    ("signatures_qualnames_and_type_modules", "classes", r'''
import inspect, types, collections, time
__name__ = "__main__"
class A:
    class B:
        def m(self): pass
sigs = []
for f in (len, print, sorted, zip, open, bool, max):
    try:
        sigs.append(str(inspect.signature(f)))
    except ValueError:
        sigs.append("ValueError")
T = type("T", (), {})
P = collections.namedtuple("P", "x")
repr((sigs, A.B.__qualname__, A.B.m.__qualname__, repr(A.B), T.__module__, P.__module__, types.CapsuleType.__name__, time.sleep(0)))
'''),
    ("bases_can_be_reassigned", "classes", r'''
from typing import NamedTuple, Generic, TypeVar
T = TypeVar("T")
class GPt(NamedTuple, Generic[T]):
    v: T
class A:
    x = "A"
class B:
    x = "B"
class C(A):
    pass
class D(C):
    pass
before = D.x
C.__bases__ = (B,)
errors = []
for bad in ((), (D,), (B, B)):
    try:
        C.__bases__ = bad
    except TypeError as e:
        errors.append(str(e))
repr((GPt(v=3), GPt.__parameters__, str(GPt[int]).rsplit(".", 1)[-1], before, D.x, [k.__name__ for k in D.__mro__], isinstance(D(), B), issubclass(D, A), errors))
'''),
    ("bytes_percent_formatting", "strings", r'''
class B:
    def __bytes__(self):
        return b"BB"
errors = []
for bad in (lambda: b"%s" % "str", lambda: b"%c" % 300, lambda: b"%s %s" % (b"a",), lambda: "%b" % b"x"):
    try:
        bad()
    except (TypeError, ValueError, OverflowError) as e:
        errors.append((type(e).__name__, str(e)))
repr((b"%s-%d" % (b"ab", 5), b"%5.2f|%x|%c|%c" % (3.14159, 255, 65, b"z"), b"%(a)s %(b)r" % {b"a": b"x", b"b": "q"},
      b"%a" % "\xe9", b"[%s]" % B(), bytearray(b"%s") % b"k", b"%-4s|%04d" % (memoryview(b"a"), 7), errors))
'''),
    ("exceptions_take_attributes", "exceptions", r'''
e = ImportError("x", name="m", path="/p")
v = ValueError("v")
v.extra = 5
try:
    raise v
except ValueError as caught:
    seen = (caught.extra, caught is v)
v.args = ["p", "q"]
k = KeyError("k")
k.tag = 1
del k.tag
class Sub(ImportError):
    def __init__(self, m):
        super().__init__(m, name="z")
errors = []
for bad in (lambda: ImportError("a", bogus=1), lambda: ValueError("a", name="m")):
    try:
        bad()
    except TypeError as err:
        errors.append(str(err))
repr(((e.args, e.name, e.path, e.msg), (ImportError("y").name, ImportError("y").path), seen, v.__dict__, (v.args, str(v)),
      hasattr(k, "tag"), (Sub("q").name, Sub("q").msg), ModuleNotFoundError("m", name="mm").name, errors))
'''),
    ("slot_names_are_member_descriptors", "classes", r'''
import types, enum
class S:
    __slots__ = ("a", "b")
class D:
    pass
class Auto(enum.auto):
    pass
class E(enum.Enum):
    A = enum.auto()
    B = enum.auto()
s = S()
s.a = 1
repr((type(S.a) is types.MemberDescriptorType, repr(S.a), S.a.__get__(s, S), "a" in dir(s), "__dict__" in dir(s), "__dict__" in dir(D()),
      issubclass(Auto, enum.auto), repr(enum.auto()), (E.A.value, E.B.value)))
'''),
    ("codec_errors_carry_their_fields", "exceptions", r'''
import json
try:
    b"ab\xff".decode("utf-8")
except UnicodeDecodeError as e:
    dec = (e.encoding, e.object, e.start, e.end, e.reason)
try:
    "a\xe9".encode("ascii")
except UnicodeEncodeError as e:
    enc = (e.encoding, e.object, e.start, e.end, e.reason)
d = {"a": 1, "b": 2}
deep = "[" * 900 + "]" * 900
repr((dec, enc, d.keys() <= {"a", "b", "c"}, d.keys() < {"a"}, d.items() >= {("a", 1)}, json.decoder.JSONDecodeError is json.JSONDecodeError,
      len(json.dumps(json.loads(deep)))))
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
//! fields, dict views, number parts and float sums, exception context, `time`,
//! bytes `%` formatting, exception attributes and slot descriptors.

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
fn unicode_name_escapes() {
    assert_eq!(py("'\\\\N{BULLET}' + '\\\\N{em dash}' + '\\\\N{CJK UNIFIED IDEOGRAPH-4E2D}'"), "•—中");
    // A name not carried is the string's own error, raised only when it runs.
    let message = big_stack(|| Runtime::default().run("def f():\\n    return '\\\\N{NOT A NAME}'\\nf()").unwrap_err().to_string());
    assert!(message.contains("NOT A NAME"), "{message}");
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
