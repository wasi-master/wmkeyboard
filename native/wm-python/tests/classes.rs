//! What this crate adds to PyMini's language: classes, decorators, `with`,
//! `nonlocal`, `assert`, annotations, keyword-only parameters, `**kwargs`,
//! spreads, modules written in Python, and the Python standard library
//! modules behind `Runtime::enable_python_stdlib`.

mod common;
use common::*;

use wm_python::interp::{Runtime, Value};

/// The value of the script's last line, through `str()` so `__str__` counts.
fn run(source: &str) -> String {
    let source = source.to_string();
    big_stack(move || {
        let mut py = Runtime::default();
        py.enable_python_stdlib();
        let lines: Vec<&str> = source.trim_end().lines().collect();
        let (body, last) = lines.split_at(lines.len() - 1);
        let wrapped = format!("{}\n__result__ = str({})\n__result__", body.join("\n"), last[0].trim());
        match py.run(&wrapped) {
            Ok(v) => v.as_str().unwrap_or("").to_string(),
            Err(e) => format!("ERROR: {e}"),
        }
    })
}

fn error(source: &str) -> String {
    let r = run(&format!("{source}\n0"));
    assert!(r.starts_with("ERROR: "), "expected an error, got {r}");
    r
}

// MARK: - classes

#[test]
fn a_class_with_init_methods_and_attributes() {
    assert_eq!(
        "Rex says woof, 3",
        run("class Dog:\n    sound = 'woof'\n    def __init__(self, name, age=3):\n        self.name = name\n        self.age = age\n    def speak(self):\n        return f'{self.name} says {self.sound}, {self.age}'\nDog('Rex').speak()")
    );
    assert_eq!("2", run("class C:\n    pass\nc = C()\nc.x = 1\nc.x += 1\nc.x"));
    assert_eq!("True", run("class C:\n    pass\nisinstance(C(), C)"));
    assert_eq!("C", run("class C:\n    pass\ntype(C()).__name__"));
}

#[test]
fn inheritance_super_and_overrides() {
    let src = "class Base:\n    def __init__(self, n):\n        self.n = n\n    def describe(self):\n        return 'base ' + str(self.n)\nclass Child(Base):\n    def __init__(self, n):\n        super().__init__(n * 2)\n    def describe(self):\n        return 'child, ' + super().describe()\nChild(4).describe()";
    assert_eq!("child, base 8", run(src));
    assert_eq!("True", run("class A:\n    pass\nclass B(A):\n    pass\nisinstance(B(), A) and issubclass(B, A) and not issubclass(A, B)"));
}

#[test]
fn class_body_names_are_not_seen_from_methods() {
    assert_eq!(
        "ERROR: Line 4: NameError: name 'x' is not defined",
        run("class C:\n    x = 1\n    def f(self):\n        return x\nC().f()")
    );
    assert_eq!("1", run("class C:\n    x = 1\n    def f(self):\n        return self.x\nC().f()"));
}

#[test]
fn properties_static_and_class_methods() {
    let src = "class Temp:\n    def __init__(self, c):\n        self._c = c\n    @property\n    def f(self):\n        return self._c * 9 / 5 + 32\n    @f.setter\n    def f(self, value):\n        self._c = (value - 32) * 5 / 9\n    @staticmethod\n    def unit():\n        return 'C'\n    @classmethod\n    def freezing(cls):\n        return cls(0)\nt = Temp.freezing()\nt.f = 212\nf'{t._c} {t.f} {Temp.unit()} {t.unit()}'";
    assert_eq!("100.0 212.0 C C", run(src));
    assert!(error("class P:\n    @property\n    def x(self):\n        return 1\nP().x = 2").contains("has no setter"));
}

#[test]
fn operator_methods() {
    let src = "class V:\n    def __init__(self, x, y):\n        self.x, self.y = x, y\n    def __add__(self, o):\n        return V(self.x + o.x, self.y + o.y)\n    def __mul__(self, k):\n        return V(self.x * k, self.y * k)\n    __rmul__ = __mul__\n    def __eq__(self, o):\n        return isinstance(o, V) and (self.x, self.y) == (o.x, o.y)\n    def __lt__(self, o):\n        return self.x < o.x\n    def __len__(self):\n        return 2\n    def __getitem__(self, i):\n        return [self.x, self.y][i]\n    def __repr__(self):\n        return f'V({self.x}, {self.y})'\n    def __hash__(self):\n        return hash((self.x, self.y))\n    def __contains__(self, v):\n        return v in (self.x, self.y)\n";
    assert_eq!("V(4, 6)", run(&format!("{src}V(1, 2) + V(3, 4)")));
    assert_eq!("V(2, 4)", run(&format!("{src}2 * V(1, 2)")));
    assert_eq!("True", run(&format!("{src}V(1, 2) == V(1, 2) and V(1, 2) != V(2, 2)")));
    assert_eq!("[V(1, 0), V(2, 0), V(3, 0)]", run(&format!("{src}sorted([V(3, 0), V(1, 0), V(2, 0)])")));
    assert_eq!("2 [1, 2] True True", run(&format!("{src}v = V(1, 2)\nf'{{len(v)}} {{list(v)}} {{2 in v}} {{V(1, 2) in [V(0, 0), V(1, 2)]}}'")));
    assert_eq!("V(9, 9)", run(&format!("{src}max([V(1, 1), V(9, 9), V(3, 3)])")));
}

#[test]
fn iter_next_call_and_bool() {
    let src = "class Count:\n    def __init__(self, n):\n        self.i = 0\n        self.n = n\n    def __iter__(self):\n        return self\n    def __next__(self):\n        if self.i >= self.n:\n            raise StopIteration\n        self.i += 1\n        return self.i\n";
    assert_eq!("[1, 2, 3]", run(&format!("{src}[x for x in Count(3)]")));
    assert_eq!("6", run(&format!("{src}sum(Count(3))")));
    assert_eq!("1 2 done", run("it = iter([1, 2])\nf'{next(it)} {next(it)} {next(it, \"done\")}'"));
    assert_eq!("10", run("class Adder:\n    def __init__(self, n):\n        self.n = n\n    def __call__(self, x):\n        return self.n + x\nAdder(3)(7)"));
    assert_eq!("no", run("class Empty:\n    def __len__(self):\n        return 0\n'yes' if Empty() else 'no'"));
}

#[test]
fn str_repr_and_format_hooks() {
    assert_eq!("<P 1>|P(1)|[P(1)]", run("class P:\n    def __init__(self, n):\n        self.n = n\n    def __str__(self):\n        return f'<P {self.n}>'\n    def __repr__(self):\n        return f'P({self.n})'\np = P(1)\nf'{p}|{p!r}|{[p]}'"));
    assert_eq!("<Plain object>", run("class Plain:\n    pass\nPlain()"));
}

#[test]
fn getattr_setattr_hasattr_vars() {
    assert_eq!(
        "1 dflt True False {'a': 2}",
        run("class C:\n    pass\nc = C()\nsetattr(c, 'a', 1)\nx = getattr(c, 'a')\nc.a = 2\nf\"{x} {getattr(c, 'b', 'dflt')} {hasattr(c, 'a')} {hasattr(c, 'b')} {vars(c)}\"")
    );
    assert_eq!("dynamic:z", run("class D:\n    def __getattr__(self, name):\n        return 'dynamic:' + name\nD().z"));
}

#[test]
fn exceptions_as_classes() {
    let src = "class AppError(Exception):\n    pass\nclass NotFound(AppError):\n    def __init__(self, what):\n        super().__init__(f'{what} is missing')\n        self.what = what\n";
    assert_eq!("caught: key is missing (key)", run(&format!("{src}try:\n    raise NotFound('key')\nexcept AppError as e:\n    r = f'caught: {{e}} ({{e.what}})'\nr")));
    assert_eq!("ERROR: Line 7: NotFound: x is missing", run(&format!("{src}raise NotFound('x')\n0")));
    assert_eq!("True", run(&format!("{src}try:\n    raise NotFound('a')\nexcept Exception as e:\n    r = isinstance(e, NotFound) and isinstance(e, AppError)\nr")));
    assert_eq!("ok", run("class E(ValueError):\n    pass\ntry:\n    raise E('bad')\nexcept ValueError:\n    r = 'ok'\nr"));
    assert_eq!("('a', 1)", run("class E(Exception):\n    pass\ntry:\n    raise E('a', 1)\nexcept E as e:\n    r = e.args\nr"));
    assert_eq!("re-raised", run("class E(Exception):\n    pass\ntry:\n    try:\n        raise E('x')\n    except E:\n        raise\nexcept E as e:\n    r = 're-raised'\nr"));
    assert_eq!("True", run("try:\n    raise PermissionError('no')\nexcept OSError as e:\n    r = isinstance(e, PermissionError)\nr"));
    assert_eq!("ok", run("try:\n    raise ValueError\nexcept ValueError:\n    r = 'ok'\nr"));
}

#[test]
fn decorators_on_functions_and_classes() {
    assert_eq!("<<hi>>", run("def wrap(f):\n    def inner(*a, **k):\n        return '<<' + f(*a, **k) + '>>'\n    return inner\n@wrap\ndef hi():\n    return 'hi'\nhi()"));
    assert_eq!(
        "['b', 'a']",
        run("registry = []\ndef register(name):\n    def deco(f):\n        registry.append(name)\n        return f\n    return deco\n@register('a')\n@register('b')\ndef f():\n    pass\nregistry")
    );
    assert_eq!("tagged", run("def tag(cls):\n    cls.label = 'tagged'\n    return cls\n@tag\nclass C:\n    pass\nC.label"));
}

#[test]
fn keyword_only_parameters_and_kwargs() {
    assert_eq!("1 2 {'c': 3}", run("def f(a, *, b, **rest):\n    return f'{a} {b} {rest}'\nf(1, b=2, c=3)"));
    assert!(error("def f(a, *, b):\n    pass\nf(1, 2)").contains("takes 1 arguments but 2 were given"));
    assert!(error("def f(a, *, b):\n    pass\nf(1)").contains("missing required argument 'b'"));
    assert_eq!("3", run("def f(a, b):\n    return a + b\nf(**{'a': 1, 'b': 2})"));
    assert_eq!("[1, 2, 3, 4]", run("a = [1, 2]\n[*a, *[3, 4]]"));
    assert_eq!("{'x': 1, 'y': 2}", run("a = {'x': 1}\n{**a, 'y': 2}"));
    assert_eq!("(0, 1, 2)", run("t = (1, 2)\n(0, *t)"));
}

#[test]
fn annotations_are_read_and_ignored() {
    assert_eq!("hi", run("def greet(name: str, times: int = 1) -> str:\n    return name * times\ngreet('hi')"));
    assert_eq!("{'x': 'int', 'y': 'list'}", run("class C:\n    x: int = 1\n    y: list[str]\nC.__annotations__"));
    assert_eq!("5", run("count: int = 5\ncount"));
}

#[test]
fn with_blocks_enter_and_exit() {
    let src = "log = []\nclass M:\n    def __init__(self, name, swallow=False):\n        self.name = name\n        self.swallow = swallow\n    def __enter__(self):\n        log.append('enter ' + self.name)\n        return self.name.upper()\n    def __exit__(self, kind, value, tb):\n        log.append(f'exit {self.name} {kind}')\n        return self.swallow\n";
    assert_eq!("['enter a', 'enter b', 'body A B', 'exit b None', 'exit a None']", run(&format!("{src}with M('a') as x, M('b') as y:\n    log.append(f'body {{x}} {{y}}')\nlog")));
    assert_eq!("['enter a', 'exit a ValueError']", run(&format!("{src}with M('a', True):\n    raise ValueError('x')\nlog")));
    assert!(error(&format!("{src}with M('a'):\n    raise ValueError('kept')")).contains("ValueError: kept"));
}

#[test]
fn nonlocal_and_assert() {
    assert_eq!("3", run("def counter():\n    n = 0\n    def inc():\n        nonlocal n\n        n += 1\n        return n\n    inc()\n    inc()\n    return inc()\ncounter()"));
    assert!(error("assert 1 == 2, 'math broke'").contains("AssertionError: math broke"));
    assert_eq!("fine", run("assert True\n'fine'"));
}

#[test]
fn modules_written_in_python() {
    let r = big_stack(|| {
        let mut py = Runtime::default();
        py.add_source_module("helpers", "PI = 3\ndef area(r):\n    return PI * r * r\n", false);
        py.add_source_module("pkg", "from .inner import twice\n", true);
        py.add_source_module("pkg.inner", "def twice(x):\n    return x * 2\n", false);
        py.run("import helpers\nfrom pkg import twice\nimport pkg.inner as inner\nf'{helpers.area(2)} {twice(5)} {inner.twice(1)}'").map(|v| v.to_str())
    });
    assert_eq!("12 10 2", r.unwrap());
    let r = big_stack(|| {
        let mut py = Runtime::default();
        py.run("import nothere").unwrap_err().to_string()
    });
    assert!(r.contains("no module named 'nothere'"), "{r}");
}

#[test]
fn instances_count_against_the_container_cap() {
    let r = error("class C:\n    pass\nc = C()\nfor i in range(20000):\n    setattr(c, 'a' + str(i), i)\n0");
    assert!(r.contains("too large"), "{r}");
}

#[test]
fn objects_make_dict_keys_by_identity() {
    assert_eq!("2", run("class K:\n    pass\na, b = K(), K()\nd = {a: 1, b: 2}\nd[b]"));
}

// MARK: - standard library

#[test]
fn dataclasses_write_init_repr_and_eq() {
    let src = "from dataclasses import dataclass, field, asdict, replace\n@dataclass\nclass Item:\n    name: str\n    done: bool = False\n    tags: list = field(default_factory=list)\n";
    assert_eq!("Item(name='milk', done=False, tags=[])", run(&format!("{src}Item('milk')")));
    assert_eq!("True", run(&format!("{src}Item('a', True) == Item('a', done=True)")));
    assert_eq!("{'name': 'a', 'done': True, 'tags': ['x']}", run(&format!("{src}asdict(Item('a', True, ['x']))")));
    assert_eq!("Item(name='b', done=False, tags=[])", run(&format!("{src}replace(Item('a'), name='b')")));
    assert!(error(&format!("{src}Item()")).contains("missing required argument 'name'"));
    assert!(error("from dataclasses import dataclass\n@dataclass(frozen=True)\nclass P:\n    x: int\np = P(1)\np.x = 2").contains("cannot assign to field 'x'"));
    assert_eq!("[P(x=1), P(x=2)]", run("from dataclasses import dataclass\n@dataclass(order=True)\nclass P:\n    x: int\nsorted([P(2), P(1)])"));
}

#[test]
fn collections() {
    assert_eq!("[('a', 3), ('b', 2)]", run("from collections import Counter\nCounter('abacab').most_common(2)"));
    assert_eq!("3 0", run("from collections import Counter\nc = Counter(['x', 'x', 'x'])\nf\"{c['x']} {c['y']}\""));
    assert_eq!("{'a': [1, 2], 'b': [3]}", run("from collections import defaultdict\nd = defaultdict(list)\nd['a'].append(1)\nd['a'].append(2)\nd['b'].append(3)\ndict(d)"));
    assert_eq!("deque([2, 3, 4], maxlen=3)", run("from collections import deque\nq = deque(maxlen=3)\nfor i in range(5):\n    q.append(i)\nq"));
    assert_eq!("Point(x=1, y=2) 3", run("from collections import namedtuple\nPoint = namedtuple('Point', 'x y')\np = Point(1, y=2)\nf'{p} {p.x + p[1]}'"));
}

#[test]
fn functools_and_itertools() {
    assert_eq!("10", run("from functools import reduce\nreduce(lambda a, b: a + b, [1, 2, 3, 4])"));
    assert_eq!("8", run("from functools import partial\npow2 = partial(pow, 2)\npow2(3)"));
    assert_eq!("55 9", run("from functools import lru_cache\ncalls = 0\n@lru_cache(maxsize=None)\ndef fib(n):\n    global calls\n    calls += 1\n    return n if n < 2 else fib(n - 1) + fib(n - 2)\nf'{fib(10)} {calls - 2}'"));
    assert_eq!("[(1, 'a'), (1, 'b'), (2, 'a'), (2, 'b')]", run("from itertools import product\nproduct([1, 2], 'ab')"));
    assert_eq!("[(1, 2), (1, 3), (2, 3)]", run("import itertools\nitertools.combinations([1, 2, 3], 2)"));
    assert_eq!("[1, 2, 3, 4]", run("from itertools import chain\nlist(chain([1, 2], [3, 4]))"));
    assert_eq!("[('a', ['ant', 'ape']), ('b', ['bee'])]", run("from itertools import groupby\n[(k, g) for k, g in groupby(['ant', 'ape', 'bee'], key=lambda w: w[0])]"));
}

#[test]
fn enums() {
    let src = "from enum import Enum, auto\nclass Color(Enum):\n    RED = 1\n    GREEN = auto()\n";
    assert_eq!("Color.RED RED 1 2", run(&format!("{src}f'{{Color.RED}} {{Color.RED.name}} {{Color.RED.value}} {{Color.GREEN.value}}'")));
    assert_eq!("True True", run(&format!("{src}f'{{Color(1) is Color.RED}} {{Color[\"GREEN\"] is Color.GREEN}}'")));
    assert_eq!("['RED', 'GREEN'] 2", run(&format!("{src}f'{{[c.name for c in Color]}} {{len(Color)}}'")));
    assert!(error(&format!("{src}Color(9)")).contains("9 is not a valid Color"));
}

#[test]
fn datetime_arithmetic_and_formatting() {
    assert_eq!("2024-03-01", run("from datetime import date, timedelta\ndate(2024, 2, 28) + timedelta(days=2)"));
    assert_eq!("366", run("from datetime import date\n(date(2025, 1, 1) - date(2024, 1, 1)).days"));
    assert_eq!("Thursday 01 Feb 2024", run("from datetime import date\ndate(2024, 2, 1).strftime('%A %d %b %Y')"));
    assert_eq!("2024-02-01 13:05:00", run("from datetime import datetime\ndatetime(2024, 2, 1, 13, 5)"));
    assert_eq!("1 day, 2:00:00", run("from datetime import timedelta\ntimedelta(hours=26)"));
    assert_eq!("2024-12-25 09:30:00", run("from datetime import datetime\ndatetime.strptime('25/12/2024 9:30', '%d/%m/%Y %H:%M')"));
    assert_eq!("True", run("from datetime import datetime\nd = datetime(2024, 5, 6, 7, 8, 9)\ndatetime.fromtimestamp(d.timestamp()) == d"));
    assert_eq!("2", run("from datetime import date\ndate(2024, 1, 1).toordinal() - date(2023, 12, 30).toordinal()"));
}

#[test]
fn hashing_and_encoding() {
    assert_eq!(
        "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
        run("import hashlib\nhashlib.sha256(b'hello').hexdigest()")
    );
    assert_eq!("5d41402abc4b2a76b9719d911017c592", run("import hashlib\nhashlib.md5('hello'.encode()).hexdigest()"));
    assert_eq!(
        "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
        run("import hmac\nhmac.new(b'key', b'The quick brown fox jumps over the lazy dog', 'sha256').hexdigest()")
    );
    assert_eq!("b'aGk/Pz8+Pg==' b'aGk_Pz8-Pg==' b'hi??>>'", run("import base64\nf'{base64.b64encode(b\"hi???>>\")} {base64.urlsafe_b64encode(b\"hi???>>\")} {base64.b64decode(\"aGk/Pz4+\")}'"));
    assert_eq!("a%20b%2Fc a+b%2Fc a b/c", run("from urllib.parse import quote, quote_plus, unquote\nf\"{quote('a b/c', safe='')} {quote_plus('a b/c')} {unquote('a%20b%2Fc')}\""));
    assert_eq!("q=a+b&n=1 {'q': ['a b'], 'n': ['1']}", run("from urllib.parse import urlencode, parse_qs\nf\"{urlencode({'q': 'a b', 'n': 1})} {parse_qs('q=a+b&n=1')}\""));
    assert_eq!("https api.x.org 8080 /v1 k=1", run("from urllib.parse import urlparse\nu = urlparse('https://api.x.org:8080/v1?k=1#top')\nf'{u.scheme} {u.hostname} {u.port} {u.path} {u.query}'"));
    assert_eq!("https://a.org/b/d", run("from urllib.parse import urljoin\nurljoin('https://a.org/b/c', 'd')"));
}

#[test]
fn the_smaller_modules() {
    assert_eq!("Hello World", run("import string\nstring.capwords('hello world')"));
    assert_eq!("Hi Ann", run("from string import Template\nTemplate('Hi $name').substitute(name='Ann')"));
    assert_eq!("['one two', 'three']", run("import textwrap\ntextwrap.wrap('one two three', 8)"));
    assert_eq!("&lt;b&gt; <b> é", run("import html\nf\"{html.escape('<b>')} {html.unescape('&lt;b&gt;')} {html.unescape('&#233;')}\""));
    assert_eq!("2.5 2.5", run("import statistics\nf'{statistics.mean([1, 2, 3, 4])} {statistics.median([1, 2, 3, 4])}'"));
    assert_eq!("36 4", run("import uuid\nu = str(uuid.uuid4())\nf'{len(u)} {u[14]}'"));
    assert_eq!("[1, [2]] False", run("import copy\na = [1, [2]]\nb = copy.deepcopy(a)\nb[1].append(3)\nf'{a} {a[1] is b[1]}'"));
    assert!(error("from abc import ABC, abstractmethod\nclass Shape(ABC):\n    @abstractmethod\n    def area(self):\n        pass\nShape()").contains("abstract methods area"));
    assert_eq!("4", run("from abc import ABC, abstractmethod\nclass Shape(ABC):\n    @abstractmethod\n    def area(self):\n        pass\nclass Sq(Shape):\n    def area(self):\n        return 4\nSq().area()"));
    assert_eq!("None", run("from typing import Optional, List\ndef f(x: Optional[List[int]] = None) -> None:\n    return x\nf()"));
}

#[test]
fn stdlib_modules_are_off_unless_asked_for() {
    let e = big_stack(|| Runtime::default().run("import dataclasses").unwrap_err().to_string());
    assert!(e.contains("no module named 'dataclasses'"), "{e}");
    let names = Runtime::python_stdlib_names();
    assert!(names.contains(&"datetime") && names.contains(&"urllib.parse"));
    let listed = big_stack(|| {
        let mut py = Runtime::default();
        py.enable_python_stdlib();
        py.importable_modules()
    });
    assert!(listed.contains(&"collections".to_string()) && !listed.iter().any(|m| m.starts_with('_')));
    // A value made in a stdlib module crosses back as a plain Value.
    let v = big_stack(|| {
        let mut py = Runtime::default();
        py.enable_python_stdlib();
        py.run("import json\nfrom dataclasses import dataclass, asdict\n@dataclass\nclass A:\n    x: int\njson.dumps(asdict(A(1)))").map(|v| v.to_str())
    });
    assert_eq!("{\"x\": 1}", v.unwrap());
    let _ = Value::None;
}
