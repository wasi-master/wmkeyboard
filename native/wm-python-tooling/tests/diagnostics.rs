//! For every code, the smallest script that earns it and the nearest one that must not.

mod common;

use common::*;
use wm_python_tooling::diagnostics::{Code, Severity, MAX_DIAGNOSTICS};
use wm_python_tooling::document::{Host, Verdict};

fn host() -> Host {
    Host::default()
}

fn panel() -> Host {
    Host { panel: Some(true), ..Host::default() }
}

fn with(f: impl FnOnce(&mut Host)) -> Host {
    let mut h = Host::default();
    f(&mut h);
    h
}

const ON_EVENT: &str = "def render():\n    return ui.column(ui.button(\"ok\", \"OK\"))\n\n\ndef on_event(e):\n";

/// (code, earns it, must not earn it, host)
fn cases() -> Vec<(&'static str, String, String, Host)> {
    let s = |x: &str| x.to_string();
    let actions = || with(|h| h.actions = Some(vec!["rot13".into()]));
    vec![
        ("unknown_character", s("x = 1 $ 2\n"), s("x = '$'\n"), host()),
        ("unclosed_string", s("x = 'abc\n"), s("x = 'abc'\n"), host()),
        ("unclosed_triple_string", s("x = '''abc\n"), s("x = '''abc'''\n"), host()),
        ("bad_number", s("x = 1e\n"), s("x = 1e5\n"), host()),
        ("unclosed_bracket", s("x = (1, 2\n"), s("x = (1, 2)\n"), host()),
        ("stray_closer", s("x = 1)\n"), s("x = (1)\n"), host()),
        ("wrong_closer", s("x = (1]\n"), s("x = [1]\n"), host()),
        ("unexpected_indent", s("x = 1\n    y = 2\n"), s("x = 1\ny = 2\n"), host()),
        ("inconsistent_dedent", s("if True:\n        x = 1\n    y = 2\n"), s("if True:\n    x = 1\ny = 2\n"), host()),
        ("empty_block", s("if True:\nx = 1\n"), s("if True:\n    x = 1\n"), host()),
        ("mixed_indentation", s("if True:\n \tx = 1\n"), s("if True:\n\tx = 1\n"), host()),
        ("syntax_expected", s("x = = 1\n"), s("x = 1\n"), host()),
        ("syntax_at_end", s("x = 1 +\n"), s("x = 1 + 2\n"), host()),
        ("unsupported", s("async def a():\n    pass\n"), s("def a():\n    pass\n"), host()),
        ("undefined_name", s("print(zzz)\n"), s("zzz = 1\nprint(zzz)\n"), host()),
        ("name_typo", s("count = 1\nprint(cuont)\n"), s("count = 1\nprint(count)\n"), host()),
        ("module_not_imported", s("x = math.pi\n"), s("import math\nx = math.pi\n"), host()),
        ("removed_builtin", s("open('f')\n"), s("len('f')\n"), host()),
        ("missing_builtin", s("x = frozenset\n"), s("x = len\n"), host()),
        ("import_not_available", s("import os\n"), s("import json\n"), host()),
        ("unused_local", s("def f():\n    x = 1\n    return 2\n"), s("def f():\n    x = 1\n    return x\n"), host()),
        ("unused_parameter", s("def f(a):\n    return 1\n"), s("def f(a):\n    return a\n"), host()),
        ("accidental_global", s("count = 0\n\n\ndef bump():\n    count += 1\n"), s("count = 0\n\n\ndef bump():\n    global count\n    count += 1\n"), host()),
        ("replaces_api", s("ui = 1\n"), s("view = 1\n"), host()),
        ("shadows_builtin", s("list = [1]\n"), s("items = [1]\n"), host()),
        ("unknown_member", s("wm.zzzzzzzz()\n"), s("wm.log()\n"), host()),
        ("member_typo", s("wm.lgo('x')\n"), s("wm.log('x')\n"), host()),
        ("use_instead", s("wm.json.decode('1')\n"), s("import json\njson.loads('1')\n"), host()),
        ("never_in_api", s("x = wm.keys\n"), s("x = wm.plugin_id\n"), host()),
        ("storage_not_declared", s("wm.storage.get('a')\n"), s("wm.plugin_id\n"), with(|h| h.storage = Some(false))),
        ("reminders_not_declared", s("wm.reminders.list()\n"), s("wm.log()\n"), with(|h| h.reminders = Some(false))),
        ("selection_not_declared", s("def transform(text, action):\n    return text\n"), s("def change(text, action):\n    return text + action\n"), with(|h| h.selection = Some(false))),
        ("panel_only", s("wm.ui.set_input('a', 'b')\n"), s("wm.log('a', 'b')\n"), with(|h| h.panel = Some(false))),
        ("missing_render", s("x = 1\n"), s("def render():\n    return ui.column()\n"), panel()),
        ("missing_transform", s("x = 1\n"), s("def transform(text, action):\n    return text\n"), actions()),
        ("render_parameters", s("def render(x):\n    return ui.column(x)\n"), s("def render():\n    return ui.column()\n"), host()),
        ("render_returns_nothing", s("def render():\n    ui.column()\n"), s("def render():\n    return ui.column()\n"), host()),
        ("on_event_signature", s("def on_event():\n    pass\n"), s("def on_event(e):\n    pass\n"), host()),
        ("transform_signature", s("def transform(a, b, c):\n    return a + b + c\n"), s("def transform(text):\n    return text\n"), host()),
        ("unknown_event_type", format!("{ON_EVENT}    if e.type == \"tapped\":\n        pass\n"), format!("{ON_EVENT}    if e.type == \"click\":\n        pass\n"), host()),
        ("event_type_typo", format!("{ON_EVENT}    if e[\"type\"] == \"clik\":\n        pass\n"), format!("{ON_EVENT}    if e[\"type\"] == \"click\":\n        pass\n"), host()),
        ("unknown_event_id", format!("{ON_EVENT}    if e.id == \"cancel\":\n        pass\n"), format!("{ON_EVENT}    if e.id == \"ok\":\n        pass\n"), host()),
        ("unknown_action", s("def transform(text, action):\n    if action == \"upper\":\n        return text\n"), s("def transform(text, action):\n    if action == \"rot13\":\n        return text\n"), actions()),
        ("action_typo", s("def transform(text, action):\n    if action == \"rot31\":\n        return text\n"), s("def transform(text, action):\n    if action == \"rot13\":\n        return text\n"), actions()),
        ("unknown_ui_argument", s("x = ui.label('a', colour='red')\n"), s("x = ui.label('a', style='title')\n"), host()),
        ("ui_argument_typo", s("x = ui.label('a', styl='title')\n"), s("x = ui.label('a', style='title')\n"), host()),
        ("ui_missing_argument", s("x = ui.button('ok')\n"), s("x = ui.button('ok', 'OK')\n"), host()),
        ("too_many_ui_arguments", s("x = ui.divider(1)\n"), s("x = ui.divider()\n"), host()),
        ("unknown_style", s("x = ui.label('a', style='huge')\n"), s("x = ui.label('a', style='body')\n"), host()),
        ("style_typo", s("x = ui.label('a', style='titel')\n"), s("x = ui.label('a', style='title')\n"), host()),
        ("duplicate_id", s("x = ui.row(ui.button('a', 'A'), ui.button('a', 'B'))\n"), s("x = ui.row(ui.button('a', 'A'), ui.button('b', 'B'))\n"), host()),
        ("too_many_tabs", format!("x = ui.tabs('t', {})\n", vec!["ui.page('p')"; 9].join(", ")), format!("x = ui.tabs('t', {})\n", vec!["ui.page('p')"; 8].join(", ")), host()),
        ("loop_never_ends", s("while True:\n    x = 1\n"), s("while True:\n    x = 1\n    break\n"), host()),
        ("unreachable", s("def f():\n    return 1\n    x = 2\n"), s("def f():\n    x = 2\n    return x\n"), host()),
    ]
}

#[test]
fn every_code_has_a_case_that_earns_it_and_a_near_miss_that_does_not() {
    let cases = cases();
    for (code, earns, misses, host) in &cases {
        let found = codes(earns, host);
        assert!(found.contains(code), "{code}: {earns:?} gave {found:?}");
        let near = codes(misses, host);
        assert!(!near.contains(code), "{code}: near miss {misses:?} gave {near:?}");
    }
    // will_not_run is the verdict, tested below; every other code is covered here.
    for code in Code::ALL {
        if *code == Code::WillNotRun {
            continue;
        }
        assert!(cases.iter().any(|(c, ..)| c == &code.name()), "no case for {}", code.name());
    }
}

#[test]
fn messages_have_a_slot_for_every_argument_a_code_is_given() {
    for (code, earns, _, host) in cases() {
        for d in diagnostics(&earns, &host).iter().filter(|d| d.code.name() == code) {
            for (i, _) in d.args.iter().enumerate() {
                assert!(d.code.message().contains(&format!("{{{i}}}")), "{code} has arg {i} but no slot: {}", d.code.message());
            }
            assert!(!d.text().contains("{0}") || d.args.is_empty(), "{code}: {}", d.text());
        }
    }
}

#[test]
fn the_demo_plugins_earn_no_errors_and_no_warnings() {
    for name in FIXTURES {
        let source = fixture(name);
        let found = serious(&source, &fixture_host(name));
        assert!(found.is_empty(), "{name}: {found:#?}");
    }
}

#[test]
fn the_verdict_lands_on_its_line_unless_the_tooling_already_marked_an_error_there() {
    let verdict = |line| Host { verdict: Some(Verdict { message: "boom".into(), kind: "RuntimeError".into(), line }), ..Host::default() };
    let source = "x = 1\n  \ty = 2   \nz = 3\n";
    let d = diagnostics("x = 1\ny = 2\n", &verdict(Some(2)));
    let v = d.iter().find(|d| d.code == Code::WillNotRun).expect("verdict shown");
    assert_eq!((v.span.start, v.span.end), (6, 11));
    assert_eq!(v.args, vec!["boom".to_string(), "RuntimeError".to_string()]);
    // An error already on that line: the verdict is not repeated.
    let doc = wm_python_tooling::Document::new("x = = 1\n");
    let report = wm_python_tooling::diagnostics::diagnose(&doc, &verdict(Some(1)));
    assert!(!report.verdict_used);
    assert_eq!(report.diagnostics.iter().filter(|d| d.severity() == Severity::Error).count(), 1);
    // No line, or one past the end: the first line.
    let d = diagnostics(source, &verdict(None));
    assert!(d.iter().any(|d| d.code == Code::WillNotRun && d.span.start == 0));
    let d = diagnostics(source, &verdict(Some(99)));
    assert!(d.iter().any(|d| d.code == Code::WillNotRun && d.span.start == 0));
    // At most one.
    assert_eq!(diagnostics("a = 1\nb = 2\n", &verdict(Some(1))).iter().filter(|d| d.code == Code::WillNotRun).count(), 1);
}

#[test]
fn name_checks_wait_while_the_file_has_a_syntax_error() {
    let h = host();
    let broken = "def f():\n    x = 1\n    y = (x +\n";
    let found = codes(broken, &h);
    assert!(!found.contains(&"unused_local") && !found.contains(&"undefined_name"), "{found:?}");
    // A name assigned on a line that does not parse is still defined.
    let found = codes("x = 1 +\nprint(x)\n", &h);
    assert!(!found.contains(&"undefined_name"));
}

#[test]
fn what_the_interpreter_runs_is_not_flagged() {
    let sources = [
        "class A:\n    pass\n",
        "class B(Exception):\n    def __init__(self, x):\n        super().__init__(x)\n",
        "class M:\n    def __enter__(self):\n        return self\n    def __exit__(self, *a):\n        return False\nwith M() as m:\n    pass\n",
        "def dec(f):\n    return f\n@dec\ndef f():\n    pass\n",
        "def outer():\n    n = 0\n    def inner():\n        nonlocal n\n        n += 1\n    inner()\n",
        "assert 1 == 1, 'ok'\n",
        "def f(a, *, b=1, **kw):\n    return a\n",
        "def f(x: int) -> int:\n    return x\n",
        "count: int = 1\n",
        "d = {'a': 1}\nprint(dict(**d))\n",
        "x = [*range(3)]\n",
        "try:\n    pass\nexcept ValueError as e:\n    raise TypeError('x') from e\n",
        "import json, time\n",
        "x = b'ab'\n",
        "from dataclasses import dataclass\nimport urllib.parse\n",
    ];
    for source in sources {
        let d = diagnostics(source, &host());
        let bad: Vec<_> = d.iter().filter(|d| d.code == Code::Unsupported || d.code == Code::ImportNotAvailable).map(|d| (d.code.name(), d.args.clone())).collect();
        assert!(bad.is_empty(), "{source:?}: {bad:?}");
    }
}

#[test]
fn every_unsupported_construct_is_named() {
    let cases = [
        ("def f():\n    yield 1\n", "yield"),
        ("async def f():\n    pass\n", "async"),
        ("async def f():\n    await g()\n", "await"),
        ("match x:\n    case 1:\n        pass\n", "match"),
        ("from math import *\n", "import *"),
        ("if (n := 3):\n    pass\n", ":="),
        ("x = 1 << 2\n", "bit shifts"),
        ("x = a @ b\n", "@"),
        ("x = ~1\n", "~"),
        ("a, *b = [1, 2]\n", "starred assignment"),
        ("x = ...\n", "..."),
        ("x = 2j\n", "complex numbers"),
        ("x |= 1\n", "|="),
    ];
    for (source, what) in cases {
        let d = diagnostics(source, &host());
        assert!(
            d.iter().any(|d| d.code == Code::Unsupported && d.args.first().is_some_and(|a| a == what)),
            "{source:?} should name {what}: {:?}",
            d.iter().map(|d| (d.code.name(), d.args.clone())).collect::<Vec<_>>()
        );
    }
}

#[test]
fn diagnostics_are_sorted_and_capped() {
    let source: String = (0..400).map(|i| format!("print(undefined_{i})\n")).collect();
    let d = diagnostics(&source, &host());
    assert_eq!(d.len(), MAX_DIAGNOSTICS);
    assert!(d.windows(2).all(|w| (w[0].span.start, w[0].severity()) <= (w[1].span.start, w[1].severity())));
}

#[test]
fn an_unclosed_string_marks_its_opening_quotes_only() {
    let d = diagnostics("x = f'''abc\nmore\n", &host());
    let u = d.iter().find(|d| d.code == Code::UnclosedTripleString).unwrap();
    assert_eq!((u.span.start, u.span.end), (4, 8));
}

#[test]
fn event_checks_follow_a_local_holding_the_event_type() {
    let source = format!("{ON_EVENT}    kind = e[\"type\"]\n    if kind == \"clik\":\n        pass\n    elif kind in (\"toggle\", \"tapped\"):\n        pass\n");
    let d = diagnostics(&source, &host());
    let args: Vec<_> = d.iter().filter(|d| d.code.name().contains("event_type")).map(|d| d.args.clone()).collect();
    assert_eq!(args, vec![vec!["clik".to_string(), "click".to_string()], vec!["tapped".to_string()]]);
}

#[test]
fn widget_id_checks_stay_quiet_when_an_id_is_built_at_run_time() {
    let source = format!("{ON_EVENT}    if e.id == \"done:1\":\n        pass\n\n\ndef rows(n):\n    return ui.toggle(\"done:\" + str(n), \"x\")\n");
    assert!(!codes(&source, &host()).contains(&"unknown_event_id"));
}

#[test]
fn an_assignment_inside_a_function_to_a_module_variable_it_also_reads_is_flagged_once() {
    let source = "total = 0\n\n\ndef add(n):\n    total = total + n\n    return total\n";
    let found = codes(source, &host());
    assert_eq!(found.iter().filter(|c| **c == "accidental_global").count(), 1, "{found:?}");
    // A local that shadows on purpose, and is read, is left alone.
    let source = "result = 0\n\n\ndef f():\n    result = 2\n    return result\n";
    assert!(!codes(source, &host()).contains(&"accidental_global"));
}

#[test]
fn an_api_5_plugin_importing_the_wm_package_earns_nothing() {
    let source = "from wm import plugin, ui, http, storage\n\nstate = plugin.state(city=\"Dhaka\")\n\n@plugin.every(minutes=30)\ndef refresh():\n    r = http.get(\"https://api.example.com/\", params={\"q\": state.city})\n    storage[\"last\"] = r.text\n\ndef render():\n    with ui.card(title=\"Today\"):\n        ui.label(state.city)\n";
    let found = serious(source, &panel());
    assert!(found.is_empty(), "{found:#?}");
}

#[test]
fn the_wm_package_is_checked_name_by_name() {
    let typo = "from wm import plugin\n\nstate = plugin.stat(n=1)\n";
    let found = serious(typo, &panel());
    assert!(found.iter().any(|d| d.starts_with("member_typo")), "{found:#?}");
    let bad_import = "from wm.ui import colum\n";
    let found = serious(bad_import, &panel());
    assert!(found.iter().any(|d| d.starts_with("member_typo")), "{found:#?}");
    let fine = "from wm.ui import column, button\nfrom wm import _, ngettext, i18n\n\ndef render():\n    with column():\n        button(_(\"Go\"))\n        button(ngettext(\"{n} day\", \"{n} days\", 2) + i18n.locale)\n";
    let found = serious(fine, &panel());
    assert!(found.is_empty(), "{found:#?}");
}
