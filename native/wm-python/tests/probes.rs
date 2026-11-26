//! The differential corpus: each `tests/fixtures/probes/NNN-name.py` was run
//! through a real PyMini 2.0.1 build (`pymini_oracle`, built from the Swift
//! sources with a main that does `PyRuntime().run(src)`, prints the result's
//! repr or `ERROR: <description>`, then each printed line as `OUT: <line>`),
//! and its output saved beside it as `.out`. This port, run the same way
//! under PyMini's own limits (`Limits::PYMINI`), must print the same.
//!
//! Left out on purpose: scripts on which PyMini itself crashes (runaway
//! recursion, `int(1e19)`), and `random.seed(str)`, which PyMini seeds from
//! Swift's per-process `hashValue`.
//!
//! [DIVERGES] lists the probes where this port parts from PyMini on purpose,
//! with what it prints instead. Each one is CPython's behaviour: classes and
//! `**kwargs` exist here, a keyword that repeats a positional argument is
//! an error, not a silent overwrite, and a default is evaluated once, when
//! the `def` or `lambda` runs, so `lambda i=i: ...` in a loop keeps each
//! turn's value. The oracle's own `.out` stays as it was.

use wm_python::interp::{Limits, Runtime, STANDARD_MODULES};

const DIVERGES: &[(&str, &str)] = &[
    ("120-syntax-class.py", "None\n"),
    ("126-syntax-kwargs.py", "None\n"),
    ("154-lambda-default-closure.py", "[10, 11, 12]\n"),
    ("192-edge-default-eval-time.py", "1\n"),
    ("193-edge-variadic-kw-collision.py", "ERROR: Line 3: TypeError: f() got multiple values for argument 'a'\n"),
    // 2026-11-26: integers are exact 64-bit (PyMini computed them in doubles
    // and lost precision past 2^53), `1 is 1.0` is False, `split(None, n)`
    // keeps the rest whole, splitlines() splits "\r\n" and drops the empty
    // line after a final line end, and string literals decode \x, \u, \U and
    // octal escapes. With escapes decoded, the grapheme probes now show the
    // port's grapheme model (len("\r\n") == 1, "e\u0301" == "é") instead of
    // an undecoded backslash.
    ("022-ints-via-double.py", concat!(r##"[9007199254740992, 9007199254740993, 4611686018427387904, 1e+20, 3, -4, -2, 2, 0.25, 9007199254740993, 2, 2]"##, "\n")),
    ("027-bitwise.py", "[1, 7, 6, 1, 0]\n"),
    ("034-strings-split.py", concat!(r##"[['a', 'b', 'c'], ['a', 'b', '', 'c'], ['a', 'b,c'], ['a,b', 'c'], ['', 'a', '', 'b', ''], ['a', 'b', 'c'], ['a', 'b c']]"##, "\n")),
    ("045-strings-graphemes.py", concat!(r##"[2, ['é', 'x'], True, 1, '🇫🇷', 1, ['e', 'f', 'é']]"##, "\n")),
    ("049-escapes.py", concat!(r##"['a\tb', '\\', 'A', 'é', '\\q', '\\n', "it's", 'say "hi"', True]"##, "\n")),
    ("081-recursion.py", concat!(r##"[610, 2432902008176640000, 1.5511210043330986e+25]"##, "\n")),
    ("113-re-unicode.py", concat!(r##"[['café', 'naïve', '中文'], '--', (3, 4)]"##, "\n")),
    // 2026-11-26: call and division errors in CPython's words, which count
    // and name every missing argument and say which division it was.
    ("026-zero-mod.py", "ERROR: Line 1: ZeroDivisionError: integer modulo by zero\n"),
    ("078-functions-errors.py", "ERROR: Line 3: TypeError: f() takes 1 positional argument but 2 were given\n"),
    ("080-functions-missing.py", "ERROR: Line 3: TypeError: f() missing 1 required positional argument: 'b'\n"),
    // 2026-11-26: `type()` answers with the type (`<class 'int'>`), map and
    // filter are lazy iterators, `max([])` says what CPython says, and a
    // bare `yield` in a lambda is a syntax error.
    ("010-builtins-minmax-err.py", "ERROR: Line 1: ValueError: max() iterable argument is empty\n"),
    ("015-builtins-map-filter.py", "[<map object>, <map object>, <filter object>, False, True, False]\n"),
    ("019-builtins-type-isinstance.py", concat!(r##"[<class 'int'>, <class 'float'>, <class 'str'>, <class 'list'>, <class 'tuple'>, <class 'dict'>, <class 'set'>, <class 'NoneType'>, <class 'builtin_function_or_method'>, <class 'function'>, True, False, True, True, False]"##, "\n")),
    ("084-try-except.py", concat!(r##"['list index out of range', "KeyError('k')", <class 'ZeroDivisionError'>, 'fin', ('v',), 'plain']"##, "\n")),
    ("132-syntax-lambda-kw.py", "ERROR: Line 1: unexpected token in expression\n"),
    // `from math import *` binds every public name of the module.
    ("127-syntax-star-import.py", "None\n"),
    // str.format names the index CPython names, items() is a live view, and
    // ints have their `real` part.
    ("054-format-method-err.py", "ERROR: Line 1: IndexError: Replacement index 2 out of range for positional args tuple\n"),
    ("068-dicts.py", concat!(r##"[{'a': 20, 'c': 3, 'z': 0, 'q': 7}, 1, 7, None, 1, ['a', 'c', 'z', 'q'], [20, 3, 0, 7], dict_items([('a', 20), ('c', 3), ('z', 0), ('q', 7)]), True, 4]"##, "\n")),
    ("092-attribute-error2.py", "5\n"),
    // A bare `raise` reports the line the exception was first raised on, as
    // CPython's traceback does.
    ("085-try-reraise.py", "ERROR: Line 3: ValueError: invalid literal for int() with base 10: 'x'\n"),
    // Arguments `%` formatting leaves unused are an error, as in CPython.
    ("217-edge-string-mod-tuple-many.py", "ERROR: Line 1: TypeError: not all arguments converted during string formatting\n"),
    ("148-exception-values.py", concat!(r##"[ValueError('bad', 1), "('bad', 1)", "ValueError('bad', 1)", <class 'ValueError'>, ('bad', 1), "('bad', 1)", Exception(), "'k'"]"##, "\n")),
    ("188-edge-except-order.py", concat!(r##"[<class 'ValueError'>, "lookup:'k'", 'arith', 'lookup:i']"##, "\n")),
    ("137-is-identity.py", concat!(r##"[True, True, False, False, True, False]"##, "\n")),
    ("163-edge-predicates-unicode.py", concat!(r##"[True, True, False, True, True, True, False, True, True]"##, "\n")),
    ("164-edge-strip-graphemes.py", concat!(r##"['éxé', 'x', 'a', 'x']"##, "\n")),
    ("165-edge-find-graphemes.py", concat!(r##"[1, 2, 1, 3, -1, 1]"##, "\n")),
    ("166-edge-split-unicode.py", concat!(r##"[['a', 'b', 'c'], ['a​b'], ['x\r', 'y'], ['', '', 'a']]"##, "\n")),
    ("182-edge-json-unicode.py", concat!(r##"['"😀é\\u0001\\b"', 0, 'é\n', '{"a": 1.0, "b": 1e+20, "c": -0.0}', 100.0, 0, 0]"##, "\n")),
    ("202-edge-large-ints.py", concat!(r##"[9007199254740992, 18014398509481982, 121932631112635269, 1.8446744073709552e+19, -9.223372036854776e+18, 1000000000000001, 1.2157665459056929e+19]"##, "\n")),
    ("205-edge-tuple-key-nfc.py", concat!(r##"['x', True, True]"##, "\n")),
    // 2026-11-26, CPython's behaviour where PyMini parted from it: equal
    // numbers are one dict key ({1: 1, True: 2} has one entry), strings repr
    // with double quotes around a single quote, IndexError names the type,
    // int() reads `_` and prefixes and says which base, KeyError's str() is
    // the key's repr and an exception keeps its args, factorial past 20 goes
    // on as a float, the '#' format option, starred and slice assignment,
    // regex lookaround and backreferences, and json writes \b as \b.
    ("003-builtins-int-err.py", concat!(r##"ERROR: Line 1: ValueError: invalid literal for int() with base 10: 'abc'"##, "\n")),
    ("004-builtins-int-base-err.py", concat!(r##"ERROR: Line 1: ValueError: invalid literal for int() with base 10: 'zz'"##, "\n")),
    ("007-builtins-str-repr.py", concat!(r##"['1', '1.0', 'None', 'True', '"a\'b"', "'tab\\tnl\\n'", "[1, 'a']", '(1,)', "{'k': (1, 2)}"]"##, "\n")),
    ("032-strings-case.py", concat!(r##"['HELLO WORLD', 'école', 'STRASSE', 'Hello World', 'Hello world', 'hEllO', 'aoøø ss', 'οδοσ', "It's"]"##, "\n")),
    ("044-strings-index-err2.py", concat!(r##"ERROR: Line 1: IndexError: string index out of range"##, "\n")),
    ("051-fstring-basic.py", concat!(r##"['5', '6', "'Bob'", '   Bob', '{x}', '005', '3.14', 0, '[1, 2]', 'q']"##, "\n")),
    ("055-format-errors.py", concat!(r##"'0x1'"##, "\n")),
    ("066-tuples.py", concat!(r##"[1, 2, (2, 3), (1, 2, 3, 4), 0, (1, 1, 1)]"##, "\n")),
    ("069-dicts-popitem-copy.py", concat!(r##"[{}, {1: 'a', 2: 'b'}, (2, 'b'), {1: 3}, {'a': 1, 'b': 2}]"##, "\n")),
    ("084-try-except.py", concat!(r##"['list index out of range', "KeyError('k')", 'ZeroDivisionError', 'fin', ('v',), 'plain']"##, "\n")),
    ("085-try-reraise.py", concat!(r##"ERROR: Line 5: ValueError: invalid literal for int() with base 10: 'x'"##, "\n")),
    ("087-raise-custom.py", concat!(r##"ERROR: Line 1: KeyError: 'missing thing'"##, "\n")),
    ("100-math-factorial-big.py", concat!(r##"5.109094217170944e+19"##, "\n")),
    ("115-re-backref-err.py", concat!(r##"<Match 'aa'>"##, "\n")),
    ("146-set-order.py", concat!(r##"[{3, 1, 2}, {'h', 'e', 'l', 'o'}, {(1, 2), (0,)}, {1}]"##, "\n")),
    ("148-exception-values.py", concat!(r##"[ValueError('bad', 1), "('bad', 1)", "ValueError('bad', 1)", 'ValueError', ('bad', 1), "('bad', 1)", Exception(), "'k'"]"##, "\n")),
    ("161-edge-title.py", concat!(r##"["Hello-World 3Rd X_Y O'neil", 'ǅemal', 'Fish', 'άσοσ', 'ǆ', 'İ', 'ʼN']"##, "\n")),
    ("167-edge-fstring-nesting.py", concat!(r##"['2', '{', '     6', '    3', "  'a'  ", '26', '2', '8']"##, "\n")),
    ("180-edge-regex-lookahead.py", concat!(r##"<Match 'a'>"##, "\n")),
    ("188-edge-except-order.py", concat!(r##"['ValueError', "lookup:'k'", 'arith', 'lookup:i']"##, "\n")),
    ("190-edge-exception-in-handler.py", concat!(r##""KeyError('b')""##, "\n")),
    ("196-edge-slice-assign-err.py", concat!(r##"None"##, "\n")),
    ("198-edge-negative-index-err.py", concat!(r##"ERROR: Line 1: IndexError: list index out of range"##, "\n")),
    ("201-edge-int-parse-err.py", concat!(r##"1000"##, "\n")),
    ("204-edge-dict-equality.py", concat!(r##"[True, True, True, True, True]"##, "\n")),
];

fn run_like_the_oracle(src: String) -> String {
    std::thread::Builder::new()
        .stack_size(64 << 20)
        .spawn(move || {
            let mut py = Runtime::new(Limits::PYMINI, STANDARD_MODULES);
            let mut out = match py.run(&src) {
                Ok(v) => format!("{}\n", v.repr()),
                Err(e) => format!("ERROR: {}\n", e.description()),
            };
            for l in py.output() {
                out.push_str(&format!("OUT: {l}\n"));
            }
            out
        })
        .unwrap()
        .join()
        .unwrap()
}

#[test]
fn probes_match_pymini() {
    let dir = format!("{}/tests/fixtures/probes", env!("CARGO_MANIFEST_DIR"));
    let mut names: Vec<_> = std::fs::read_dir(&dir)
        .unwrap()
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "py"))
        .collect();
    names.sort();
    assert!(names.len() >= 80, "only {} probes", names.len());
    let mut failures = Vec::new();
    for py in &names {
        let src = std::fs::read_to_string(py).unwrap();
        let file = py.file_name().unwrap().to_string_lossy().to_string();
        let expected = match DIVERGES.iter().find(|(n, _)| *n == file) {
            Some((_, ours)) => ours.to_string(),
            None => std::fs::read_to_string(py.with_extension("out")).unwrap(),
        };
        let actual = run_like_the_oracle(src);
        if actual != expected {
            failures.push(format!("{}\n--- expected\n{expected}--- actual\n{actual}", py.display()));
        }
    }
    assert!(failures.is_empty(), "{} of {} probes differ:\n{}", failures.len(), names.len(), failures.join("\n"));
}
