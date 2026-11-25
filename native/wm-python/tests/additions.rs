//! What this crate adds to PyMini: dict attribute reads, `bytes`,
//! `time.format` with English names in the host's zone, and `check`.

mod common;
use common::*;

use std::rc::Rc;

use wm_python::interp::{check, Clock, Limits, Runtime, STANDARD_MODULES};

struct FixedClock {
    now: f64,
    offset: i32,
}

impl Clock for FixedClock {
    fn now(&self) -> f64 {
        self.now
    }
    fn monotonic(&self) -> f64 {
        0.0
    }
    fn utc_offset(&self, _: i64) -> i32 {
        self.offset
    }
}

fn at(now: f64, offset: i32) -> Runtime {
    Runtime::with_clock(Limits::default(), STANDARD_MODULES, Rc::new(FixedClock { now, offset }))
}

// MARK: - dict attributes

#[test]
fn dicts_answer_their_string_keys_as_attributes() {
    assert_eq!("click|go|None", evaluate("e = {'type': 'click', 'id': 'go', 'value': None}\nf'{e.type}|{e.id}|{e.value}'"));
    assert_eq!("2", evaluate("{'a': {'b': 2}}.a.b"));
    // Methods still work when no key shadows them.
    assert_eq!("['a']", evaluate("{'a': 1}.keys()"));
    assert_eq!("0", evaluate("{'a': 1}.get('z', 0)"));
}

#[test]
fn a_key_holding_a_callable_wins_over_the_method_of_that_name() {
    assert_eq!("stub", evaluate("d = {'get': lambda k, default=None: 'stub'}\nd.get('x')"));
    assert_eq!(
        "v|['k']",
        evaluate("wm = {'storage': {'get': lambda k: 'v', 'keys': lambda: ['k']}}\nwm.storage.get('k') + '|' + str(wm.storage.keys())")
    );
}

#[test]
fn a_missing_key_is_an_attribute_error() {
    assert_eq!(
        "AttributeError: 'dict' object has no attribute 'nope'",
        evaluate("try:\n    {'a': 1}.nope\nexcept AttributeError as e:\n    r = 'AttributeError: ' + str(e)\nr")
    );
    assert!(failure("{1: 2}.missing()").contains("'dict' object has no method 'missing'"));
}

// MARK: - bytes

#[test]
fn bytes_encode_and_decode() {
    assert_eq!("b'h\\xc3\\xa9'", evaluate("'hé'.encode()"));
    assert_eq!("b'h\\x00\\xe9\\x00'", evaluate("'hé'.encode('utf-16-le')"));
    assert_eq!("b'\\x00h\\x00\\xe9'", evaluate("'hé'.encode(encoding='UTF-16-BE')"));
    assert_eq!("hé", evaluate("'hé'.encode('utf-16-le').decode('utf-16-le')"));
    assert_eq!("😀", evaluate("'😀'.encode('utf-16-be').decode(encoding='utf-16-be')"));
    assert_eq!("a\u{FFFD}b", evaluate("bytes([97, 255, 98]).decode('utf-8', errors='replace')"));
    assert_eq!("a\u{FFFD}", evaluate("b = 'a'.encode('utf-16-le') + bytes([1])\nb.decode('utf-16-le', 'replace')"));
}

#[test]
fn bad_bytes_raise_catchable_decode_errors() {
    assert_eq!(
        "UnicodeDecodeError|ValueError|caught",
        evaluate(
            "out = []\ntry:\n    bytes([255]).decode()\nexcept UnicodeDecodeError as e:\n    out.append(type(e))\ntry:\n    bytes([0, 216]).decode('utf-16-le')\nexcept ValueError:\n    out.append('ValueError')\ntry:\n    bytes([1]).decode('utf-16-be')\nexcept Exception:\n    out.append('caught')\n'|'.join(out)"
        )
    );
    assert!(failure("bytes([255]).decode()").contains("UnicodeDecodeError: 'utf-8' codec can't decode byte 0xff in position 0"));
    assert!(failure("'x'.encode('latin-1')").contains("LookupError: unknown encoding: latin-1"));
    assert!(failure("bytes([256])").contains("ValueError: bytes must be in range(0, 256)"));
    assert!(failure("bytes('x')").contains("TypeError"));
}

#[test]
fn bytes_behave_like_a_sequence_of_ints() {
    assert_eq!(
        "[3, 104, [104, 105, 33], b'i!', True, False, True, b'hi!!', True, 'bytes', '686921', b'', 3]",
        evaluate(
            "b = bytes([104, 105, 33])\n[len(b), b[0], list(b), b[1:], b == 'hi!'.encode(), b == 'hi', 105 in b, b + bytes([33]), isinstance(b, bytes), type(b), b.hex(), bytes(), len(bytes.fromhex('de ad be'))]"
        )
    );
    assert_eq!("[104, 105]", evaluate("[x for x in 'hi'.encode()]"));
    assert_eq!("b'\\xde\\xad'", evaluate("bytes.fromhex('dead')"));
    assert_eq!("b\"it's\"", evaluate("\"it's\".encode()"));
    assert_eq!("b'a\\\\b\\n'", evaluate("'a\\\\b\\n'.encode()"));
    assert!(failure("bytes.fromhex('zz')").contains("ValueError: non-hexadecimal number"));
    assert_eq!("2", evaluate("len({'a'.encode(): 1, bytes([97]): 2, bytes(): 3})"));
}

#[test]
fn bytes_methods_match_python() {
    assert_eq!(
        "[[b'a', b'b', b'', b'c'], b'x-y', 3, -1, 3, b'a;b;;c', b'hi', b'hi', [b'a', b'b'], True, True, b'AB', b'ab']",
        evaluate(
            "b = b'a,b,,c'\n[b.split(b','), b'-'.join([b'x', b'y']), b.find(b',,'), b.find(b'z'), b.count(b','), b.replace(b',', b';'), b'  hi \\n'.strip(), b'xxhixx'.strip(b'x'), b' a  b '.split(), b.startswith(b'a,'), b.endswith(b'c'), b'Ab'.upper(), b'Ab'.lower()]"
        )
    );
    assert!(failure("b'ab'.index(b'z')").contains("ValueError"));
    assert!(failure("b''.join(['x'])").contains("TypeError"));
}

// MARK: - time

#[test]
fn time_format_uses_english_names_and_the_hosts_zone() {
    // 2024-02-29 23:30:05.250 UTC, a Thursday.
    let ts = 1709249405.25;
    let mut utc = at(ts, 0);
    assert_eq!(
        "Thursday, 29 February 2024 23:30:05 | Thu Feb 29 | 02/29/24 | 11:30 PM | 250 | +0000 Z GMT",
        utc.run("import time\ntime.format(\"EEEE, d MMMM yyyy HH:mm:ss | EEE MMM d | MM/dd/yy | hh:mm a | SSS | Z X z\")").unwrap().to_str()
    );
    // Five and a half hours east it is already March 1st, a Friday.
    let mut east = at(ts, 19_800);
    assert_eq!(
        "Fri 1 Mar 2024 05:00 +0530 +05:30 GMT+05:30 D61 u5",
        east.run("import time\ntime.format(\"EEE d MMM yyyy HH:mm Z XXX z 'D'D 'u'u\")").unwrap().to_str()
    );
    assert_eq!(
        "[2024, 3, 1, 5, 0, 5, 4, 61]",
        east.run("import time\np = time.parts()\n[p['year'], p['month'], p['day'], p['hour'], p['minute'], p['second'], p['weekday'], p['yearday']]").unwrap().repr()
    );
    assert_eq!("o'clock 1970", utc.run("import time\ntime.format(\"'o''clock' yyyy\", 0)").unwrap().to_str());
    assert_eq!("1709249405.25", utc.run("import time\ntime.time()").unwrap().to_str());
}

#[test]
fn bad_date_patterns_are_value_errors() {
    let mut py = at(0.0, 0);
    let e = py.run("import time\ntime.format('yyyy-qq')").unwrap_err();
    assert_eq!("ValueError", e.kind);
    assert_eq!("invalid date pattern: Illegal pattern character 'q'", e.message);
    assert_eq!("ValueError", py.run("import time\ntime.format(\"'open\")").unwrap_err().kind);
}

// MARK: - check

#[test]
fn check_reports_what_load_would() {
    assert!(check("def f(x):\n    return x\n").is_ok());
    for source in ["x = (1,\ny = 2", "def g():\n    yield 1", "if True:\nx = 1", "x = 1 $ 2", "f\"{}\""] {
        let c = check(source).unwrap_err();
        let l = Runtime::default().load(source).unwrap_err();
        assert_eq!((c.message.clone(), c.kind.clone(), c.line), (l.message, l.kind, l.line), "{source}");
    }
    let c = check("a = 1\nb = (\n").unwrap_err();
    assert_eq!("RuntimeError", c.kind);
}

#[test]
fn deep_nesting_is_refused_by_the_parser_not_the_stack() {
    let source = format!("x = {}1{}", "(".repeat(500), ")".repeat(500));
    let e = check(&source).unwrap_err();
    assert_eq!("expression nested too deeply", e.message);
    let ok = format!("x = {}1{}", "(".repeat(30), ")".repeat(30));
    assert!(check(&ok).is_ok());
}

#[test]
fn a_list_that_holds_itself_still_prints() {
    assert_eq!("True", big_stack(|| evaluate("a = [1]\na.append(a)\ns = repr(a)\ns.startswith('[1, [1, [1,')")));
}

// MARK: - methods as values

#[test]
fn built_in_methods_can_be_passed_around() {
    assert_eq!("[1, 2]", evaluate("xs = []\nadd = xs.append\nadd(1)\nlist(map(add, [2]))\nxs"));
    assert_eq!("['A', 'B']", evaluate("list(map(str.upper, ['a', 'b'])) if False else list(map('{}'.format, ['A', 'B']))"));
    assert_eq!("True", evaluate("callable([].append)"));
    assert_eq!("a-b", evaluate("j = '-'.join\nj(['a', 'b'])"));
}

#[test]
fn a_data_key_does_not_hide_a_dict_method() {
    assert_eq!("[('items', [1])]", evaluate("d = {'items': [1]}\nd.items()"));
    assert_eq!("[1]", evaluate("d = {'items': [1]}\nd.items if False else d['items']"));
    // A key holding a function still answers first, as wm.storage stand-ins need.
    assert_eq!("7", evaluate("d = {'get': lambda k: 7}\nd.get('x')"));
}

#[test]
fn type_names_read_back_through_dunder_name() {
    assert_eq!("int", evaluate("type(3).__name__"));
}

#[test]
fn shifts_and_bitwise_assignment() {
    assert_eq!("1024", evaluate("1 << 10"));
    assert_eq!("-4", evaluate("-16 >> 2"));
    assert_eq!("0", evaluate("5 >> 70"));
    assert_eq!("-1", evaluate("-5 >> 70"));
    // Shifts bind looser than + and tighter than &, as in Python.
    assert_eq!("16", evaluate("1 << 2 + 2"));
    assert_eq!("4", evaluate("4 & 1 << 2"));
    assert_eq!("[12, 3, 1, 7, 48]", output("a = [3]\nfor x in range(4):\n    a.append(a[-1])\na[0] <<= 2\na[1] >>= 0\na[2] &= 1\na[3] |= 4 + 2\na[4] ^= 51\nresult = a"));
    assert!(failure("1 << -1").contains("negative shift count"));
}

#[test]
fn integers_stay_exact_across_64_bits() {
    assert_eq!("72057594037927935", evaluate("2 ** 56 - 1"));
    assert_eq!("int", evaluate("type(2 ** 60)"));
    assert_eq!("9223372036854775807", evaluate("2 ** 62 - 1 + 2 ** 62"));
    assert_eq!("-9223372036854775808", evaluate("-(2 ** 62) * 2"));
    assert_eq!("12345678901234567", evaluate("12345678901234566 + 1"));
    assert_eq!("-4", evaluate("-7 // 2"));
    assert_eq!("3", evaluate("-7 % 5"));
    assert_eq!("-2", evaluate("7 % -3"));
    assert_eq!("0.25", evaluate("2 ** -2"));
    // Past 64 bits there is no big integer: the answer goes on as a float.
    assert_eq!("float", evaluate("type(2 ** 64)"));
}

#[test]
fn identity_keeps_bool_int_and_float_apart() {
    assert_eq!("False", evaluate("1 is True"));
    assert_eq!("False", evaluate("0 is False"));
    assert_eq!("True", evaluate("True is True"));
    assert_eq!("False", evaluate("1 is 1.0"));
    assert_eq!("True", evaluate("None is None"));
}

#[test]
fn splitlines_knows_every_line_end() {
    assert_eq!("['a', 'b', 'c', '', 'd']", evaluate(r#""a\r\nb\rc\n\nd\r\n".splitlines()"#));
    assert_eq!("['a\\r\\n', 'b\\n']", evaluate(r#""a\r\nb\n".splitlines(True)"#));
    assert_eq!("[]", evaluate(r#""".splitlines()"#));
    assert_eq!("['x']", evaluate(r#""x".splitlines()"#));
}

#[test]
fn bytes_rfind_and_one_sided_strip() {
    assert_eq!("4", evaluate(r#"b"abcabc".rfind(b"bc")"#));
    assert_eq!("-1", evaluate(r#"b"abc".rfind(b"z")"#));
    assert_eq!("1", evaluate(r#"b"abcabc".rindex(b"bc", 0, 4)"#));
    assert_eq!("b'ab  '", evaluate(r#"b"  ab  ".lstrip()"#));
    assert_eq!("b'\\x00ab'", evaluate(r#"b"\x00ab\x00\x00".rstrip(b"\x00")"#));
}

#[test]
fn int_bytes_round_trip() {
    assert_eq!("258", evaluate(r#"int.from_bytes(b"\x01\x02", "big")"#));
    assert_eq!("513", evaluate(r#"int.from_bytes(b"\x01\x02", byteorder="little")"#));
    assert_eq!("-2", evaluate(r#"int.from_bytes(b"\xff\xfe", "big", signed=True)"#));
    assert_eq!("-1", evaluate(r#"int.from_bytes(b"\xff" * 8, "big", signed=True)"#));
    assert_eq!("b'\\x01\\x02'", evaluate("(258).to_bytes(2, 'big')"));
    assert_eq!("b'\\xfe\\xff'", evaluate("(-2).to_bytes(2, 'little', signed=True)"));
    assert_eq!("9", evaluate("(256).bit_length()"));
    assert!(failure("(256).to_bytes(1, 'big')").contains("too big"));
}

#[test]
fn whitespace_split_keeps_the_rest_whole() {
    assert_eq!("['a', 'b  c']", evaluate(r#""a b  c".split(None, 1)"#));
    assert_eq!("['a', 'b c ']", evaluate(r#"" a b c ".split(None, 1)"#));
    assert_eq!("[' a b', 'c']", evaluate(r#"" a b c ".rsplit(None, 1)"#));
    assert_eq!("['a', 'b', 'c']", evaluate(r#""a b c".rsplit()"#));
    assert_eq!("[]", evaluate(r#""   ".split(None, 1)"#));
}

#[test]
fn string_escapes() {
    assert_eq!("['A', 'é', '😀', '\\x07\\x08\\x0c\\x0b', 'A', '\\x00', '\\\\q', '\\\\xZZ']",
        evaluate(r#"["\x41", "é", "\U0001F600", "\a\b\f\v", "\101", "\0", "\q", "\xZZ"]"#));
}

#[test]
fn bitwise_not() {
    assert_eq!("-6", evaluate("~5"));
    assert_eq!("0", evaluate("~-1"));
    assert_eq!("255", evaluate("~0 & 0xFF"));
}
