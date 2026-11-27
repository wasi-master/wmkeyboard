//! `analyse` on small packages shaped like real ones.

use std::collections::BTreeMap;

use wm_python_pip::{analyse, AnalyseOptions, Report, Severity, Verdict};

fn files(list: &[(&str, &str)]) -> BTreeMap<String, String> {
    list.iter().map(|(p, s)| (p.to_string(), s.to_string())).collect()
}

/// The interpreter recurses on the native stack: give it room, as the CLI does.
fn run(list: &[(&str, &str)], available: &[&str]) -> Report {
    let f = files(list);
    let opts = AnalyseOptions { package: "test".into(), available: available.iter().map(|s| s.to_string()).collect(), ..Default::default() };
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
fn a_network_module_at_import_time_is_incompatible() {
    let r = run(&[("net.py", "import json\nimport smtplib\n\ndef get(host):\n    return smtplib.SMTP(host)\n")], &[]);
    assert_eq!(r.verdict, Verdict::Incompatible);
    let p = r.problems().find(|p| p.code == "sandbox-import").expect("smtplib flagged");
    assert_eq!((p.severity, p.line, p.col), (Severity::Error, Some(2), Some(8)));
    assert!(p.help.as_deref().unwrap_or("").contains("wm.http"), "{p:?}");
    assert!(r.imports.missing.contains("smtplib"));
}

#[test]
fn where_an_import_sits_decides_how_bad_it_is() {
    let r = run(
        &[(
            "fast.py",
            "try:\n    import simplejson as json\nexcept ImportError:\n    import json\n\ndef save(x):\n    import smtplib\n    return smtplib.SMTP('x')\n\nif False:\n    pass\n",
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
            "import sys\nfrom math import floor, not_a_function\nPY3 = sys._current_frames() is None\n\ndef read(path):\n    with open(path) as f:\n        return f.read()\n\ndef ok():\n    return sys.version\n",
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
    // Both checks ask the interpreter, so what counts as a gap follows it:
    // a line is either fine or flagged with the matching code, once.
    let at = |line: u32| c.iter().filter(|x| x.3 == Some(line)).collect::<Vec<_>>();
    assert!(at(8).iter().all(|x| x.1 == "multiple-inheritance"), "{c:?}");
    // The parser may refuse the literal outright; otherwise it is a warning.
    assert!(at(12).iter().all(|x| x.1 == "big-int" || x.1 == "syntax"), "{c:?}");
    assert!(at(8).len() <= 1 && at(12).len() <= 1, "{c:?}");
}

#[test]
fn the_json_has_the_shape_the_web_editor_reads() {
    let r = run(&[("m.py", "import smtplib\n")], &[]);
    let j = r.to_json();
    assert_eq!(j["verdict"], "incompatible");
    let p = &j["problems"][0];
    for k in ["severity", "file", "line", "col", "code", "message"] {
        assert!(p.get(k).is_some(), "{k} missing in {p}");
    }
    assert_eq!(j["imports"]["missing"][0], "smtplib");
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

#[test]
fn future_imports_are_asked_as_statements() {
    // The interpreter may run `from __future__ import x` as a statement of
    // its own while `import __future__` fails; only the first matters here.
    let src = "from __future__ import annotations\n\ndef f(x: int) -> int:\n    return x\n";
    let r = run(&[("fut.py", src)], &[]);
    if wm_python::interp::check(src).is_ok() {
        assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
    } else {
        assert!(r.problems().any(|p| p.code == "missing-import" || p.code == "syntax"), "{:?}", codes(&r));
    }
}

#[test]
fn branches_for_other_versions_and_systems_never_run() {
    let r = run(
        &[
            (
                "compat/__init__.py",
                "import os\nimport sys\nfrom ._flags import WIN\n\nPY2 = sys.version_info[0] == 2\nPY3 = not PY2\nNEW = sys.version_info[:2] >= (3, 99)\n\nif PY2:\n    import StringIO\n    text = unicode\nelse:\n    text = str\n\nif NEW:\n    import annotationlib\n\nif sys.version_info < (3,):\n    import cPickle\nelif sys.platform == 'win32' or os.name == 'nt':\n    import winreg\n\nif WIN:\n    import msvcrt\n    from . import _console\n\nif sys.platform.startswith(('win', 'cygwin')):\n    import _winapi\n",
            ),
            ("compat/_flags.py", "import sys\nWIN = sys.platform.startswith('win')\n"),
            ("compat/_console.py", "import ctypes\nimport msvcrt\n"),
        ],
        &[],
    );
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
    // The Windows console module only the dead branch imports never loads.
    assert!(r.problems().filter(|p| p.file == "compat/_console.py").all(|p| p.severity == Severity::Info), "{:?}", codes(&r));
}

#[test]
fn a_branch_that_cannot_be_worked_out_is_still_checked() {
    let r = run(&[("maybe.py", "import os\n\nif os.environ.get('X'):\n    import smtplib\n")], &[]);
    assert_eq!(r.verdict, Verdict::Incompatible, "{:?}", codes(&r));
}

#[test]
fn the_fallback_of_an_import_that_works_never_runs() {
    let r = run(
        &[(
            "lazy.py",
            "try:\n    from collections.abc import Mapping\nexcept ImportError:\n    try:\n        from UserDict import DictMixin\n    except ImportError:\n        from collections import Mapping\n\ntry:\n    set\nexcept NameError:\n    from sets import Set as set\n",
        )],
        &[],
    );
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
    // A fallback for something the keyboard lacks is still read.
    let r = run(&[("fast.py", "try:\n    import simplejson as json\nexcept ImportError:\n    import smtplib\n")], &[]);
    assert_eq!(r.verdict, Verdict::Incompatible, "{:?}", codes(&r));
}

#[test]
fn a_module_loaded_only_by_some_code_fails_that_code_not_the_package() {
    let r = run(
        &[
            ("tool/__init__.py", "from .core import run\n"),
            ("tool/core.py", "def run():\n    return 1\n\ndef pager():\n    from . import _pager\n    return _pager.show()\n"),
            ("tool/_pager.py", "import smtplib\n\ndef show():\n    return smtplib.SMTP('x')\n"),
            ("tool/testing.py", "import smtplib\n"),
            ("tool/__main__.py", "import smtplib\nprint(input())\n"),
        ],
        &[],
    );
    assert_eq!(r.verdict, Verdict::Warnings, "{:?}", codes(&r));
    let c = codes(&r);
    assert!(c.contains(&(Severity::Warning, "sandbox-import", "tool/_pager.py".into(), Some(1))), "{c:?}");
    assert!(c.contains(&(Severity::Warning, "sandbox-import", "tool/testing.py".into(), Some(1))), "{c:?}");
    assert!(c.contains(&(Severity::Info, "sandbox-import", "tool/__main__.py".into(), Some(1))), "{c:?}");
    assert!(r.problems().any(|p| p.code == "loaded-later" && p.file == "tool/_pager.py"), "{c:?}");
    // A public module the package does not import is the package's to offer: still checked in full.
    let r = run(&[("tool/__init__.py", ""), ("tool/net.py", "import smtplib\n")], &[]);
    assert_eq!(r.verdict, Verdict::Incompatible, "{:?}", codes(&r));
}

#[test]
fn empty_package_files_too_deep_are_left_out_not_refused() {
    let r = run(
        &[
            ("zones/__init__.py", ""),
            ("zones/data/America/Argentina/__init__.py", ""),
            ("zones/data/America/Argentina/extra.py", "x = 1\n"),
        ],
        &[],
    );
    let c = codes(&r);
    assert!(c.contains(&(Severity::Info, "too-deep", "zones/data/America/Argentina/__init__.py".into(), None)), "{c:?}");
    assert!(c.contains(&(Severity::Error, "too-deep", "zones/data/America/Argentina/extra.py".into(), None)), "{c:?}");
}

#[test]
fn a_module_docstring_can_be_imported_and_annotations_are_not_read() {
    let r = run(
        &[
            ("doc/__init__.py", "from ._impl import __doc__, parse\n"),
            ("doc/_impl.py", "\"\"\"Parse things.\"\"\"\nprompt: input = None\n\ndef parse(x):\n    return x\n"),
        ],
        &[],
    );
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
}

#[test]
fn a_module_for_another_system_never_loads() {
    let r = run(
        &[
            ("zones/__init__.py", "from .tz import local\n"),
            ("zones/tz.py", "try:\n    from .win import tzwin\nexcept ImportError:\n    tzwin = None\n\ndef local():\n    return 0\n"),
            ("zones/win.py", "import ctypes\nimport winreg\n\ndef tzwin():\n    return winreg.HKEY_LOCAL_MACHINE\n"),
            ("zones/tzwin.py", "from .win import *\n"),
        ],
        &[],
    );
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
}

#[test]
fn a_switch_turned_off_when_the_speedups_are_missing_is_followed() {
    let r = run(
        &[
            ("md/__init__.py", "from ._compat import USE_EXTENSIONS\n\nif not USE_EXTENSIONS:\n    from ._md_py import MultiDict\nelse:\n    from ._md import MultiDict\n"),
            (
                "md/_compat.py",
                "import os\n\nNO_EXTENSIONS = bool(os.environ.get('MD_NO_EXTENSIONS'))\nUSE_EXTENSIONS = not NO_EXTENSIONS\n\nif USE_EXTENSIONS:\n    try:\n        from . import _md\n    except ImportError:\n        USE_EXTENSIONS = False\n",
            ),
            ("md/_md_py.py", "class MultiDict(dict):\n    pass\n"),
        ],
        &[],
    );
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
}

#[test]
fn a_private_shim_beside_the_package_is_optional() {
    let r = run(&[("yaml/__init__.py", "X = 1\n"), ("_yaml/__init__.py", "from yaml._yaml import *\n")], &[]);
    assert_eq!(r.verdict, Verdict::Warnings, "{:?}", codes(&r));
}

#[test]
fn names_a_standard_module_makes_on_demand_are_found() {
    // CPython 3.13's typing makes Pattern and Match in its module __getattr__.
    let r = run(&[("rx.py", "import typing\nfrom typing import Pattern, Match\n\ndef f(p: Pattern):\n    return typing.Match\n")], &[]);
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
    let r = run(&[("rx.py", "from typing import NoSuchThing\n")], &[]);
    assert_eq!(r.verdict, Verdict::Incompatible, "{:?}", codes(&r));
}
