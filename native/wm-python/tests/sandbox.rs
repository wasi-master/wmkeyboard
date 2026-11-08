//! Port of SandboxTest.kt: aborts, cancellation, module switches, seeded
//! randomness and the Swift string model. Values marked "PyMini:" were read
//! from PyMini 2.0.1 built from source.

mod common;
use common::*;

use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};

use wm_python::interp::{AbortKind, Limits, Runtime, STANDARD_MODULES};

fn steps(n: u64) -> Limits {
    Limits { steps: n, ..Limits::default() }
}

#[test]
fn an_endless_loop_aborts() {
    let error = py_error(Runtime::new(steps(10_000), STANDARD_MODULES).load("while True:\n    pass"));
    assert_eq!(Some(AbortKind::Steps), error.abort);
    assert!(error.to_string().contains("step budget"), "{error}");
}

#[test]
fn a_bare_except_cannot_swallow_the_budget() {
    let error = py_error(Runtime::new(steps(10_000), STANDARD_MODULES).load("try:\n    while True:\n        pass\nexcept:\n    pass"));
    assert!(error.is_abort());
}

#[test]
fn cancellation_aborts_and_cannot_be_caught() {
    let mut py = Runtime::new(steps(u64::MAX), STANDARD_MODULES);
    let flag = py.cancel_handle();
    let canceller = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(50));
        flag.store(true, Ordering::Relaxed);
    });
    let started = Instant::now();
    let error = py_error(py.load("try:\n    while True:\n        pass\nexcept Exception:\n    pass"));
    canceller.join().unwrap();
    assert_eq!(Some(AbortKind::Cancelled), error.abort);
    assert!(error.to_string().contains("cancelled"), "{error}");
    assert!(started.elapsed() < Duration::from_secs(5));
}

#[test]
fn catastrophic_regex_spends_the_budget_instead_of_hanging() {
    let error = big_stack(|| {
        let mut py = Runtime::new(steps(50_000), STANDARD_MODULES);
        py.run("import re\nre.match(r\"(a+)+b\", \"a\" * 30)").unwrap_err()
    });
    assert!(error.is_abort());
    assert!(error.to_string().contains("step budget"), "{error}");
}

#[test]
fn size_caps_are_aborts() {
    let small = Limits { container_items: 100, string_length: 50, ..Limits::default() };
    let e = py_error(Runtime::new(small, STANDARD_MODULES).run("x = []\nwhile True:\n    x.append(1)"));
    assert_eq!(Some(AbortKind::ContainerCap), e.abort);
    let e = py_error(Runtime::new(small, STANDARD_MODULES).run("s = 'ab' * 30"));
    assert_eq!(Some(AbortKind::StringCap), e.abort);
    assert!(py_error(Runtime::new(small, STANDARD_MODULES).run("try:\n    s = 'ab' * 30\nexcept:\n    pass")).is_abort());
}

#[test]
fn runaway_recursion_aborts_instead_of_overflowing_the_stack() {
    let error = big_stack(|| Runtime::default().load("def f(n):\n    return f(n + 1)\nf(0)").unwrap_err());
    assert_eq!(Some(AbortKind::Recursion), error.abort);
}

#[test]
fn an_excluded_module_is_a_catchable_import_error() {
    let mut py = Runtime::new(Limits::default(), &["math", "json", "re", "sys"]);
    assert_eq!(
        "caught",
        py.run("try:\n    import random\n    r = 'imported'\nexcept ImportError:\n    r = 'caught'\nr").unwrap().to_str()
    );
    let error = py_error(py.run("import time"));
    assert_eq!("ImportError", error.kind);
    assert!(!error.is_abort());
}

#[test]
fn every_standard_module_is_on_by_default() {
    assert_eq!(&["math", "random", "time", "json", "re", "sys"], STANDARD_MODULES);
    Runtime::default().run("import math\nimport random\nimport time\nimport json\nimport re\nimport sys").unwrap();
}

#[test]
fn seeded_random_matches_pymini() {
    let result = Runtime::default()
        .run(
            "import random\nrandom.seed(7)\na = [random.randint(1, 100) for _ in range(5)]\nrandom.seed(42)\nb = random.random()\n\
             xs = [1, 2, 3, 4, 5, 6]\nrandom.shuffle(xs)\nc = random.choice(\"abcdef\")\nd = random.sample([10, 20, 30, 40], 2)\n\
             e = random.randrange(0, 100, 7)\nf = random.uniform(1.0, 2.0)\ng = random.choices([1, 2, 3], 4)\n[a, b, xs, c, d, e, f, g]",
        )
        .unwrap();
    // PyMini: the same script, same seeds.
    assert_eq!(
        "[[88, 5, 47, 4, 75], 0.7281787732893573, [6, 5, 1, 4, 3, 2], 'a', [30, 10], 49, 1.7460986192516328, [2, 1, 3, 1]]",
        result.repr()
    );
}

#[test]
fn random_stays_in_range() {
    let result = Runtime::default()
        .run(
            "import random\nok = True\nfor _ in range(200):\n    n = random.randint(3, 5)\n    u = random.random()\n    \
             ok = ok and 3 <= n <= 5 and 0 <= u < 1 and random.randrange(10) < 10\nok",
        )
        .unwrap();
    assert_eq!("True", result.to_str());
}

#[test]
fn time_parts_and_format() {
    // 2023-11-14 12:00 UTC: the same year and month in every time zone.
    let mut py = Runtime::default();
    assert_eq!("[2023, 11]", py.run("import time\np = time.parts(1699963200)\n[p['year'], p['month']]").unwrap().repr());
    assert_eq!("2023", py.run("import time\ntime.format('yyyy', 1699963200)").unwrap().to_str());
    assert!(py.run("import time\ntime.time() > 1699963200").unwrap().truthy());
}

#[test]
fn strings_are_grapheme_clusters_as_in_swift() {
    // PyMini: 7, 'é', True, 1, False, ['a', 'b', 'z', 'é']
    let s = "\"héllo 👍🏽\"";
    assert_eq!("7", evaluate(&format!("len({s})")));
    assert_eq!("é", evaluate(&format!("{s}[1]")));
    assert_eq!("True", evaluate("\"é\" == \"e\u{301}\""));
    assert_eq!("1", evaluate("{\"é\": 1}.get(\"e\u{301}\")"));
    assert_eq!("False", evaluate("\"é\" < \"f\""));
    assert_eq!("['a', 'b', 'z', 'é']", evaluate("sorted([\"b\", \"é\", \"a\", \"z\"])"));
    assert_eq!("1", evaluate("len(\"👍🏽\")"));
}

#[test]
fn case_mapping_follows_swift_and_foundation() {
    // PyMini: no final sigma, Foundation's capitalized for title().
    assert_eq!("οδοσ σα", evaluate("\"ΟΔΟΣ ΣΑ\".lower()"));
    assert_eq!("Don’t Stop", evaluate("\"don’t stop\".title()"));
    assert_eq!("It's O'neil", evaluate("\"it's o'neil\".title()"));
    assert_eq!("X1Y Z.W", evaluate("\"x1y z.w\".title()"));
    assert_eq!(
        "Hello-World Word.Word A:B X_Y Abc1Def École Élève",
        evaluate("\"hello-world word.word a:b x_y abc1def ÉCOLE élève\".title()")
    );
    assert_eq!("Ssa", evaluate("\"ßa\".title()"));
    assert_eq!("aoøø fi ss", evaluate("\"ÄÖøØ ﬁ ß\".casefold()"));
    assert_eq!("['a', 'b', 'c']", evaluate("\"a\\tb c\".split()"));
}

#[test]
fn numbers_print_like_pymini() {
    // PyMini: ints run through Double, so 10**20 is a float.
    assert_eq!("1e+20", evaluate("10**20"));
    assert_eq!("0.30000000000000004", evaluate("0.1+0.2"));
    assert_eq!("1e+16", evaluate("1e16"));
    assert_eq!("1e-05", evaluate("1e-5"));
    assert_eq!("-0.0", evaluate("-0.0"));
    assert_eq!("9007199254740992.0", evaluate("2**53+1"));
    assert_eq!("3", evaluate("int(\"3.5\")"));
    assert_eq!("100,000,000,000,000,000,000.00", evaluate("f\"{1e20:,.2f}\""));
    assert_eq!("1.2e+04", evaluate("\"%5.1e\" % 12345.678"));
    assert_eq!("str", evaluate("type('x')"));
    assert_eq!("{3, 1, 2}", evaluate("{3, 1, 2, 1}"));
}
