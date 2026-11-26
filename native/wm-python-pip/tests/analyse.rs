//! `analyse` on small packages shaped like real ones.

use std::collections::BTreeMap;

use wm_python_pip::{analyse, AnalyseOptions, Report, Severity, Verdict};

fn files(list: &[(&str, &str)]) -> BTreeMap<String, String> {
    list.iter().map(|(p, s)| (p.to_string(), s.to_string())).collect()
}

/// The interpreter recurses on the native stack: give it room, as the CLI does.
fn run(list: &[(&str, &str)], available: &[&str]) -> Report {
    let f = files(list);
    let opts = AnalyseOptions { package: "test".into(), available: available.iter().map(|s| s.to_string()).collect() };
    std::thread::Builder::new().stack_size(64 << 20).spawn(move || analyse(&f, &opts)).unwrap().join().unwrap()
}

fn codes(r: &Report) -> Vec<(Severity, &'static str, String, Option<u32>)> {
    r.problems().map(|p| (p.severity, p.code, p.file.clone(), p.line)).collect()
}

#[test]
fn a_plain_package_is_ok() {
    let r = run(
        &[
            ("tidy/__init__.py", "\"\"\"Tidy text.\"\"\"\nfrom .core import shout\nfrom tidy.extra import counts\n"),
            ("tidy/core.py", "import re\nfrom collections import OrderedDict\n\ndef shout(text):\n    return re.sub(r'\\s+', ' ', text).upper()\n"),
            ("tidy/extra.py", "import math\n\ndef counts(words):\n    return {w: math.floor(len(w)) for w in words}\n"),
            ("LICENSE", "MIT"),
        ],
        &[],
    );
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
    assert_eq!(r.roots, vec!["tidy"]);
    assert!(r.imports.stdlib.contains("re") && r.imports.stdlib.contains("collections") && r.imports.stdlib.contains("math"));
    assert!(r.imports.internal.contains("tidy.core") && r.imports.internal.contains("tidy.extra"));
    assert!(r.imports.missing.is_empty());
}

#[test]
fn a_socket_at_import_time_is_incompatible() {
    let r = run(&[("net.py", "import json\nimport socket\n\ndef get(host):\n    return socket.create_connection((host, 80))\n")], &[]);
    assert_eq!(r.verdict, Verdict::Incompatible);
    let p = r.problems().find(|p| p.code == "sandbox-import").expect("socket flagged");
    assert_eq!((p.severity, p.line, p.col), (Severity::Error, Some(2), Some(8)));
    assert!(p.help.as_deref().unwrap_or("").contains("wm.http"), "{p:?}");
    assert!(r.imports.missing.contains("socket"));
}

#[test]
fn where_an_import_sits_decides_how_bad_it_is() {
    let r = run(
        &[(
            "fast.py",
            "try:\n    import simplejson as json\nexcept ImportError:\n    import json\n\ndef save(x):\n    import os\n    return os.getcwd()\n\nif False:\n    pass\n",
        )],
        &[],
    );
    let c = codes(&r);
    assert!(c.contains(&(Severity::Info, "missing-import", "fast.py".into(), Some(2))), "{c:?}");
    assert!(c.contains(&(Severity::Warning, "sandbox-import", "fast.py".into(), Some(7))), "{c:?}");
    assert_eq!(r.verdict, Verdict::Warnings);
}

#[test]
fn type_checking_imports_are_ignored() {
    let r = run(&[("typed.py", "from typing import TYPE_CHECKING\nif TYPE_CHECKING:\n    import os\n\ndef f(x):\n    return x\n")], &[]);
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
}

#[test]
fn a_missing_dependency_is_named_unless_it_is_available() {
    let src = [("wrap.py", "import six\nfrom dateutil import parser\n")];
    let r = run(&src, &[]);
    assert_eq!(r.verdict, Verdict::Incompatible);
    let p = r.problems().find(|p| p.message.contains("'six'")).unwrap();
    assert_eq!(p.help.as_deref(), Some("wmpy pip install six"));
    let r = run(&src, &["six", "dateutil"]);
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
    assert!(r.imports.available.contains("six"));
}

#[test]
fn syntax_the_interpreter_refuses_is_named() {
    let r = run(&[("gen.py", "def numbers():\n    yield 1\n")], &[]);
    // Generators are arriving in the interpreter: whatever it says is the verdict.
    if wm_python::interp::check("def numbers():\n    yield 1\n").is_err() {
        let p = r.problems().find(|p| p.code == "syntax").expect("syntax problem");
        assert_eq!(p.line, Some(2));
        assert!(p.message.contains("yield"), "{}", p.message);
        assert_eq!(r.verdict, Verdict::Incompatible);
    } else {
        assert!(r.problems().all(|p| p.code != "syntax"));
    }
}

#[test]
fn compiled_files_and_keyboard_limits() {
    let big = format!("X = 1\n{}", "# padding\n".repeat(30_000));
    let r = run(
        &[
            ("fastpkg/__init__.py", "from . import core\n"),
            ("fastpkg/core.py", "def f():\n    return 1\n"),
            ("fastpkg/_speed.cpython-312-darwin.so", ""),
            ("fastpkg/_speed.c", ""),
            ("fastpkg/big.py", &big),
            ("fastpkg/a/b/c/d.py", "x = 1\n"),
            ("fastpkg/data-files/x.py", "y = 2\n"),
        ],
        &[],
    );
    let c: Vec<(Severity, &str, String)> = r.problems().map(|p| (p.severity, p.code, p.file.clone())).collect();
    assert!(c.contains(&(Severity::Error, "c-extension", "fastpkg/_speed.cpython-312-darwin.so".into())), "{c:?}");
    assert!(c.contains(&(Severity::Warning, "c-source", "fastpkg/_speed.c".into())), "{c:?}");
    assert!(c.contains(&(Severity::Error, "file-too-big", "fastpkg/big.py".into())), "{c:?}");
    assert!(c.contains(&(Severity::Error, "too-deep", "fastpkg/a/b/c/d.py".into())), "{c:?}");
    assert!(c.contains(&(Severity::Error, "module-name", "fastpkg/data-files/x.py".into())), "{c:?}");
}

#[test]
fn builtins_and_members_are_checked_against_the_interpreter() {
    let r = run(
        &[(
            "compat.py",
            "import sys\nfrom math import floor, not_a_function\nPY3 = sys.version_info[0] == 3\n\ndef read(path):\n    with open(path) as f:\n        return f.read()\n\ndef ok():\n    return sys.version\n",
        )],
        &[],
    );
    let c = codes(&r);
    assert!(c.contains(&(Severity::Error, "missing-name", "compat.py".into(), Some(2))), "{c:?}");
    assert!(c.contains(&(Severity::Error, "missing-attribute", "compat.py".into(), Some(3))), "{c:?}");
    assert!(c.contains(&(Severity::Warning, "sandbox-builtin", "compat.py".into(), Some(6))), "{c:?}");
    assert!(!c.iter().any(|x| x.3 == Some(10)), "sys.version exists: {c:?}");
}

#[test]
fn runtime_gaps_are_warnings_from_what_the_interpreter_does() {
    let r = run(
        &[("shapes.py", "class A:\n    pass\n\nclass B:\n    pass\n\ndef make():\n    class C(A, B):\n        pass\n    return C\n\nBIG = 0xFFFFFFFFFFFFFFFF\n")],
        &[],
    );
    let c = codes(&r);
    // Both checks ask the interpreter; today it has neither.
    assert!(c.iter().any(|x| x.1 == "multiple-inheritance" && x.3 == Some(8)), "{c:?}");
    // The parser may refuse the literal outright; otherwise it is a warning.
    assert!(c.iter().any(|x| (x.1 == "big-int" || x.1 == "syntax") && x.3 == Some(12)), "{c:?}");
    assert_eq!(c.iter().filter(|x| x.3 == Some(12)).count(), 1, "{c:?}");
}

#[test]
fn the_json_has_the_shape_the_web_editor_reads() {
    let r = run(&[("m.py", "import os\n")], &[]);
    let j = r.to_json();
    assert_eq!(j["verdict"], "incompatible");
    let p = &j["problems"][0];
    for k in ["severity", "file", "line", "col", "code", "message"] {
        assert!(p.get(k).is_some(), "{k} missing in {p}");
    }
    assert_eq!(j["imports"]["missing"][0], "os");
    assert!(j["imports"]["stdlib"].is_array() && j["imports"]["internal"].is_array());
    assert_eq!(j["roots"][0], "m");
}

#[test]
fn reserved_and_shadowed_names() {
    let r = run(&[("typing.py", "X = 1\n"), ("main.py", "Y = 2\n")], &[]);
    let c: Vec<(Severity, &str)> = r.problems().map(|p| (p.severity, p.code)).collect();
    assert!(c.contains(&(Severity::Warning, "shadowed")), "{c:?}");
    assert!(c.contains(&(Severity::Error, "reserved-name")), "{c:?}");
}

#[test]
fn the_main_guard_never_runs_in_a_package() {
    let r = run(&[("tool.py", "import sys\n\ndef main():\n    return 0\n\nif __name__ == \"__main__\":\n    import argparse\n    sys.exit(main())\n")], &[]);
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
}
