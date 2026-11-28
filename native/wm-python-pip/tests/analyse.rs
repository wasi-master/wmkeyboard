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
    let big = format!("X = 1\n{}", "# padding\n".repeat(110_000));
    let r = run(
        &[
            ("fastpkg/__init__.py", "from . import core, big\nfrom .a.b.c.d.e.f.g import h\n"),
            ("fastpkg/core.py", "def f():\n    return 1\n"),
            ("fastpkg/_speed.cpython-312-darwin.so", ""),
            ("fastpkg/_speed.c", ""),
            ("fastpkg/big.py", &big),
            ("fastpkg/a/b/c/d/e/f/g/h.py", "x = 1\n"),
            ("fastpkg/data-files/x.py", "y = 2\n"),
        ],
        &[],
    );
    let c: Vec<(Severity, &str, String)> = r.problems().map(|p| (p.severity, p.code, p.file.clone())).collect();
    assert!(c.contains(&(Severity::Error, "c-extension", "fastpkg/_speed.cpython-312-darwin.so".into())), "{c:?}");
    assert!(c.contains(&(Severity::Warning, "c-source", "fastpkg/_speed.c".into())), "{c:?}");
    assert!(c.contains(&(Severity::Error, "file-too-big", "fastpkg/big.py".into())), "{c:?}");
    assert!(c.contains(&(Severity::Error, "too-deep", "fastpkg/a/b/c/d/e/f/g/h.py".into())), "{c:?}");
    assert!(c.contains(&(Severity::Error, "module-name", "fastpkg/data-files/x.py".into())), "{c:?}");
    // Nothing imports them: `wmpy pack` leaves them out unless the plugin's code reaches them.
    let r = run(&[("fastpkg/__init__.py", ""), ("fastpkg/big.py", &big), ("fastpkg/a/b/c/d/e/f/g/h.py", "x = 1\n")], &[]);
    let c: Vec<(Severity, &str, String)> = r.problems().map(|p| (p.severity, p.code, p.file.clone())).collect();
    assert!(c.contains(&(Severity::Warning, "file-too-big", "fastpkg/big.py".into())), "{c:?}");
    assert!(c.contains(&(Severity::Warning, "too-deep", "fastpkg/a/b/c/d/e/f/g/h.py".into())), "{c:?}");
    assert_eq!(r.verdict, Verdict::Warnings);
}

#[test]
fn a_public_module_only_for_a_package_it_does_not_need_is_an_add_on() {
    let r = run(
        &[
            ("net/__init__.py", "from . import core\n"),
            ("net/core.py", "def get():\n    return 1\n"),
            ("net/contrib/__init__.py", ""),
            ("net/contrib/fancy.py", "import OpenSSL\n"),
        ],
        &[],
    );
    assert_eq!(r.verdict, Verdict::Warnings, "{:?}", codes(&r));
    assert!(r.problems().any(|p| p.code == "loaded-later" && p.file == "net/contrib/fancy.py"), "{:?}", codes(&r));
    // One the package loads itself is checked in full.
    let r = run(&[("net/__init__.py", "from .contrib import fancy\n"), ("net/contrib/__init__.py", ""), ("net/contrib/fancy.py", "import OpenSSL\n")], &[]);
    assert_eq!(r.verdict, Verdict::Incompatible, "{:?}", codes(&r));
}

#[test]
fn names_a_star_import_brings_in_are_found() {
    let r = run(
        &[
            ("pkg/__init__.py", "from .base import *\nfrom math import *\n"),
            ("pkg/base.py", "from .deeper import *\n\ndef helper():\n    return 1\n"),
            ("pkg/deeper.py", "LIMIT = 3\n"),
            // Names it makes itself: whatever is asked for may be there.
            ("pkg/flags/__init__.py", "from ._made import *\n"),
            ("pkg/flags/_made.py", "import sys\nfor _n in ('A', 'B'):\n    setattr(sys.modules[__name__], _n, 1)\n"),
            ("pkg/use.py", "from pkg import helper, LIMIT, floor\nfrom pkg.flags import A\nfrom pkg import missing\n"),
        ],
        &[],
    );
    let names: Vec<String> = r.problems().filter(|p| p.code == "missing-name").map(|p| p.message.clone()).collect();
    assert_eq!(names, vec!["'pkg' has no 'missing'".to_string()], "{:?}", codes(&r));
}

#[test]
fn a_namespace_another_package_shares_is_not_missing() {
    let list = [("google/cloud/storage/__init__.py", "from google.auth import credentials\nimport google.auth.transport\n")];
    let r = run(&list, &["google"]);
    assert!(r.problems().all(|p| p.code != "missing-import"), "{:?}", codes(&r));
    let r = run(&list, &[]);
    assert!(r.problems().any(|p| p.code == "missing-import" && p.message.contains("google.auth")), "{:?}", codes(&r));
    assert_eq!(r.verdict, Verdict::Incompatible, "{:?}", codes(&r));
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
    let r = run(&[("maybe.py", "import random\n\nif random.random() > 0.5:\n    import smtplib\n")], &[]);
    assert_eq!(r.verdict, Verdict::Incompatible, "{:?}", codes(&r));
    // A plugin's environment starts empty: what a variable would switch on is off.
    let r = run(&[("maybe.py", "import os\n\nif os.environ.get('X'):\n    import smtplib\nif 'Y' in os.environ:\n    import smtplib\n")], &[]);
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
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
            ("zones/data/tz/v2/zones/tz2026/America/Argentina/__init__.py", ""),
            ("zones/data/tz/v2/zones/tz2026/America/Argentina/extra.py", "x = 1\n"),
        ],
        &[],
    );
    let c = codes(&r);
    assert!(c.contains(&(Severity::Info, "too-deep", "zones/data/tz/v2/zones/tz2026/America/Argentina/__init__.py".into(), None)), "{c:?}");
    // Nothing imports it: an error only for code that does.
    assert!(c.contains(&(Severity::Warning, "too-deep", "zones/data/tz/v2/zones/tz2026/America/Argentina/extra.py".into(), None)), "{c:?}");
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

#[test]
fn the_keyboards_own_modules_beside_wm_are_there_as_attributes() {
    let r = run(&[("net.py", "import urllib.request\n\nPROXIES = urllib.request.getproxies\n")], &[]);
    assert!(r.problems().all(|p| p.code != "missing-attribute"), "{:?}", codes(&r));
}

#[test]
fn hasattr_on_any_standard_module_is_asked_of_the_interpreter() {
    let r = run(
        &[("compat.py", "import typing\n\nif hasattr(typing, \"NoSuchThingAtAll\"):\n    X = typing.NoSuchThingAtAll\nelse:\n    X = None\n")],
        &[],
    );
    assert!(r.problems().all(|p| p.code != "missing-attribute"), "{:?}", codes(&r));
}

#[test]
fn a_switch_read_from_the_environment_and_the_platform_is_followed() {
    // multidict: speedups unless an environment variable or PyPy says no, and off when they fail to import.
    let r = run(
        &[
            ("md/__init__.py", "from ._compat import USE_EXTENSIONS\n\nif not USE_EXTENSIONS:\n    from ._py import MultiDict\nelse:\n    from ._fast import MultiDict\n"),
            (
                "md/_compat.py",
                "import os\nimport platform\n\nNO_EXTENSIONS = bool(os.environ.get(\"MD_NO_EXTENSIONS\"))\nPYPY = platform.python_implementation() == \"PyPy\"\nUSE_EXTENSIONS = not NO_EXTENSIONS and not PYPY\n\nif USE_EXTENSIONS:\n    try:\n        from . import _fast\n    except ImportError:\n        USE_EXTENSIONS = False\n",
            ),
            ("md/_py.py", "class MultiDict(dict):\n    pass\n"),
        ],
        &[],
    );
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
}

#[test]
fn a_windows_module_taken_through_six_moves_is_for_windows() {
    let r = run(
        &[
            ("tzs/__init__.py", ""),
            ("tzs/win.py", "from six.moves import winreg\nimport ctypes\n"),
            ("tzs/tzwin.py", "from .win import *\n"),
        ],
        &["six"],
    );
    assert_ne!(r.verdict, Verdict::Incompatible, "{:?}", codes(&r));
}

#[test]
fn a_getattr_kept_for_old_names_answers_only_those() {
    let getattr = "def __getattr__(name):\n    if name == \"upstr\":\n        return str\n    raise AttributeError(name)\n\ndef __dir__():\n    return [*globals(), \"upstr\"]\n";
    let r = run(&[("md/__init__.py", getattr), ("md/use.py", "from md import upstr\nfrom md import _speedups\n")], &[]);
    let names: Vec<String> = r.problems().filter(|p| p.severity == Severity::Error).map(|p| p.message.clone()).collect();
    assert_eq!(names, vec!["'md' has no '_speedups'".to_string()], "{:?}", codes(&r));
    // One that looks names up some other way may answer anything.
    let r = run(&[("md/__init__.py", "def __getattr__(name):\n    return _table[name]\n"), ("md/use.py", "from md import anything\n")], &[]);
    assert!(r.problems().all(|p| p.code != "missing-name"), "{:?}", codes(&r));
}

#[test]
fn the_dead_arm_of_a_conditional_expression_is_not_checked() {
    let r = run(&[("m.py", "import builtins\nimport sys\n\n_UNSET = object() if sys.version_info < (3, 99) else builtins.no_such_thing(\"x\")\n")], &[]);
    assert!(r.problems().all(|p| p.code != "missing-attribute"), "{:?}", codes(&r));
}

#[test]
fn names_a_module_makes_through_globals_it_hands_on_are_unknowable() {
    // protobuf's generated _pb2 modules.
    let r = run(
        &[
            ("pb/__init__.py", ""),
            ("pb/any_pb2.py", "from pb import builder as _builder\n_globals = globals()\n_builder.build(_globals)\n"),
            ("pb/builder.py", "def build(g):\n    g['Any'] = object\n"),
            ("pb/any.py", "from pb.any_pb2 import Any\n"),
        ],
        &[],
    );
    assert!(r.problems().all(|p| p.code != "missing-name"), "{:?}", codes(&r));
}

#[test]
fn imports_under_with_suppress_import_error_are_guarded() {
    let r = run(
        &[(
            "fmt.py",
            "from contextlib import suppress\nimport contextlib\n\nwith suppress(ImportError):\n    import rfc3339_validator\nwith contextlib.suppress(ImportError, AttributeError):\n    from webcolors import name_to_hex\n",
        )],
        &[],
    );
    assert_eq!(r.verdict, Verdict::Ok, "{:?}", codes(&r));
}

#[test]
fn a_desktop_window_module_in_an_add_on_is_a_warning() {
    let r = run(&[("bar/__init__.py", "from .std import tqdm\n"), ("bar/std.py", "class tqdm:\n    pass\n"), ("bar/tk.py", "import tkinter\nfrom tkinter import ttk\n")], &[]);
    assert_eq!(r.verdict, Verdict::Warnings, "{:?}", codes(&r));
}

#[test]
fn open_at_load_reads_the_package_data_that_came_with_it() {
    let reader = "import os\n\nwith open(os.path.join(os.path.dirname(__file__), 'words.txt')) as f:\n    WORDS = f.read().split()\n";
    // Without its data the read fails when the package loads.
    let bare = run(&[("words/__init__.py", reader)], &[]);
    assert_eq!(bare.verdict, Verdict::Incompatible, "{:?}", codes(&bare));
    // With it (wmpy pip wrote words/_wmdata.py), open() finds the file: a warning that other paths fail.
    let kept = run(&[("words/__init__.py", reader), ("words/_wmdata.py", "TEXT = {\"words.txt\": \"a b c\"}\nDATA = {}\n")], &[]);
    assert_eq!(kept.verdict, Verdict::Warnings, "{:?}", codes(&kept));
    assert!(kept.problems().any(|p| p.message.contains("opens only the data files that came with the package")), "{:?}", codes(&kept));
}
