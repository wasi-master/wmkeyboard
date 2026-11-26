//! `wmpy new`, `check`, `pack`, `stubs` and the `test` runner's test
//! functions, against plugin folders made on the fly.

use std::path::{Path, PathBuf};
use std::process::Command;

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-core-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn plugin(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = scratch(name);
    for (f, text) in files {
        let path = dir.join(f);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }
    dir
}

fn cli(args: &[&str]) -> (i32, String, String) {
    let out = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(args).output().unwrap();
    (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into(), String::from_utf8_lossy(&out.stderr).into())
}

fn json(text: &str) -> serde_json::Value {
    serde_json::from_str(text.trim()).unwrap_or_else(|e| panic!("not JSON ({e}): {text}"))
}

fn s(p: &Path) -> &str {
    p.to_str().unwrap()
}

const MANIFEST: &str = r#"{
  "format": "wmkeyboard-plugin",
  "id": "com.example.shop",
  "name": "Shop",
  "pluginVersion": "1.2.0",
  "author": "A. Person",
  "apiVersion": 5,
  "permissions": ["storage"],
  "providers": [{"tool": "search", "id": "shop"}]
}
"#;

const MAIN: &str = "from wm import plugin, ui\nimport helpers\n\nstate = plugin.state(count=0)\n\n\ndef render():\n    ui.label(helpers.title(state.count))\n";

const HELPERS: &str = "from wm import plugin\n\n\ndef title(n):\n    return 'count ' + str(n)\n\n\n@plugin.provider('search', 'shop')\ndef shop(request):\n    return {'results': []}\n";

#[test]
fn every_template_makes_a_plugin_that_checks_tests_and_packs() {
    let root = scratch("templates");
    for slug in ["blank", "text-tools", "cipher", "todo", "kitchen-sink", "text-generator", "weather", "text-actions"] {
        let dir = root.join(slug);
        let (code, out, err) = cli(&["new", s(&dir), "--template", slug, "--name", &format!("T {slug}"), "--author", "Me", "--json"]);
        assert_eq!(code, 0, "{slug}: {err}");
        let made = json(&out);
        assert_eq!(made["id"], format!("my.t-{slug}"));
        for f in ["plugin.json", "main.py", "test.py", ".gitignore", "pyrightconfig.json", ".vscode/extensions.json", ".wmpy/stubs/wm/ui.pyi", ".wmpy/stubs/__builtins__.pyi", ".wmpy/stubs/plugin_test.pyi"] {
            assert!(dir.join(f).is_file(), "{slug}: no {f}");
        }
        let (code, out, err) = cli(&["check", s(&dir), "--json"]);
        let report = json(&out);
        assert_eq!((code, report["errors"].as_u64()), (0, Some(0)), "{slug}: {report:#}\n{err}");
        let (code, out, err) = cli(&["test", s(&dir), "--json"]);
        let tests = json(&out);
        assert_eq!(code, 0, "{slug}: {tests:#}\n{err}");
        assert!(tests["passed"].as_u64().unwrap() >= 2, "{slug}: {tests:#}");
        let (code, out, err) = cli(&["pack", s(&dir), "--json"]);
        assert_eq!(code, 0, "{slug}: {err}");
        let packed = json(&out);
        assert!(Path::new(packed["path"].as_str().unwrap()).is_file());
        assert_eq!(packed["entry"]["name"], format!("T {slug}"));
    }
}

#[test]
fn new_refuses_a_folder_with_files_in_it() {
    let dir = scratch("busy-root").join("busy");
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("main.py"), "x = 1\n").unwrap();
    let (code, _, err) = cli(&["new", s(&dir)]);
    assert_eq!(code, 2);
    assert!(err.contains("already has main.py") && err.contains("--force"), "{err}");
    assert_eq!(cli(&["new", s(&dir), "--force"]).0, 0);
    let manifest = json(&std::fs::read_to_string(dir.join("plugin.json")).unwrap());
    assert_eq!(manifest["apiVersion"], 5);
    assert_eq!(manifest["name"], "Busy");
}

#[test]
fn check_places_each_manifest_problem_in_plugin_json() {
    let manifest = "{\n  \"format\": \"wmkeyboard-plugin\", \"id\": \"com.example.x\", \"name\": \"X\", \"pluginVersion\": \"1\", \"apiVersion\": 5,\n  \"permissions\": [\"storage\", \"netwrok\"],\n  \"hosts\": [\"http://api.example.com\"]\n}\n";
    let dir = plugin("manifest", &[("plugin.json", manifest), ("main.py", "from wm import ui\n\ndef render():\n    ui.label('x')\n")]);
    let (code, out, _) = cli(&["check", s(&dir), "--json"]);
    assert_eq!(code, 1);
    let report = json(&out);
    let problems = report["problems"].as_array().unwrap();
    let unknown = problems.iter().find(|p| p["code"] == "unknown_permission").expect("unknown_permission");
    assert_eq!((unknown["file"].as_str(), unknown["line"].as_u64(), unknown["col"].as_u64()), (Some("plugin.json"), Some(3), Some(30)), "{unknown:#}");
    assert!(unknown["help"].as_str().unwrap().contains("\"network\""));
    assert!(unknown["notes"][0].as_str().unwrap().contains("does not know the permission"));
    let host = problems.iter().find(|p| p["code"] == "bad_host").expect("bad_host");
    assert_eq!(host["line"], 4);
    assert!(host["help"].as_str().unwrap().contains("without http://"));
    // The human output: a code frame under the place.
    let (_, _, err) = cli(&["check", s(&dir)]);
    assert!(err.contains("error[unknown_permission]") && err.contains("--> plugin.json:3:30") && err.contains("^^^^^^^^^"), "{err}");
    assert!(err.contains("checked plugin.json and 1 Python file: 2 errors"), "{err}");
}

#[test]
fn check_reads_bad_json_and_wrong_folders() {
    let dir = plugin("badjson", &[("plugin.json", "{\n  \"id\": \"a.b.c\"\n  \"name\": \"x\"\n}\n"), ("main.py", "")]);
    let (code, out, _) = cli(&["check", s(&dir), "--json"]);
    assert_eq!(code, 1);
    let bad = &json(&out)["problems"][0];
    assert_eq!((bad["code"].as_str(), bad["line"].as_u64()), (Some("bad_json"), Some(3)), "{bad:#}");

    let outer = plugin("outer", &[("inner/plugin.json", MANIFEST)]);
    let (code, _, err) = cli(&["check", s(&outer)]);
    assert_eq!(code, 2);
    assert!(err.contains("has no plugin.json") && err.contains("one folder down") && err.contains("inner"), "{err}");
    // A file in the folder names the folder.
    let (code, _, _) = cli(&["check", s(&dir.join("plugin.json"))]);
    assert_eq!(code, 1);
}

#[test]
fn check_runs_the_language_service_across_modules() {
    // The provider is answered in helpers.py, so main.py is not blamed for it.
    let dir = plugin("modules", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("helpers.py", HELPERS), ("test.py", "assert True\n")]);
    let (code, out, err) = cli(&["check", s(&dir), "--json"]);
    let report = json(&out);
    assert_eq!(code, 0, "{report:#}\n{err}");
    assert!(!report["problems"].as_array().unwrap().iter().any(|p| p["code"] == "provider_unanswered"), "{report:#}");

    let main = "from wm import ui, http\n\ndef render():\n    ui.lable('x')\n    http.get('https://api.example.com/')\n";
    let dir = plugin("python", &[("plugin.json", MANIFEST), ("main.py", main), ("helpers.py", HELPERS), ("broken.py", "def f(:\n    pass\n")]);
    let (code, out, _) = cli(&["check", s(&dir), "--json"]);
    let report = json(&out);
    let codes: Vec<(&str, &str)> = report["problems"].as_array().unwrap().iter().map(|p| (p["file"].as_str().unwrap(), p["code"].as_str().unwrap())).collect();
    assert_eq!(code, 1, "{codes:?}");
    assert!(codes.contains(&("main.py", "member_typo")), "{codes:?}");
    assert!(codes.contains(&("main.py", "permission_missing")), "{codes:?}");
    assert!(codes.iter().any(|(f, c)| *f == "broken.py" && (c.starts_with("syntax") || *c == "unclosed_bracket")), "{codes:?}");
}

#[test]
fn check_strict_fails_on_warnings() {
    let main = "from wm import ui, http\n\ndef render():\n    ui.label('x')\n    http.get('https://api.example.com/')\n";
    let dir = plugin("strict", &[("plugin.json", MANIFEST.replace(", \"providers\": [{\"tool\": \"search\", \"id\": \"shop\"}]", "").as_str()), ("main.py", main)]);
    assert_eq!(cli(&["check", s(&dir)]).0, 0);
    assert_eq!(cli(&["check", s(&dir), "--strict"]).0, 1);
}

#[test]
fn pack_writes_the_archive_the_app_reads_the_same_each_time() {
    let dir = plugin(
        "pack",
        &[
            ("plugin.json", MANIFEST),
            ("main.py", MAIN),
            ("helpers.py", HELPERS),
            ("assets/logo.svg", "<svg/>"),
            ("locales/bn.json", "{\"Shop\": \"দোকান\"}"),
            ("test.py", "assert True\n"),
            ("tests/test_more.py", "def test_x():\n    pass\n"),
            ("README.md", "# Shop\n"),
            ("licenses/shop-lib/LICENSE", "MIT License\n"),
            ("wmpy.lock", "{}"),
            (".wmpy/stubs/wm/ui.pyi", ""),
            ("pyrightconfig.json", "{}"),
        ],
    );
    let (code, out, err) = cli(&["pack", s(&dir), "--json"]);
    assert_eq!(code, 0, "{err}");
    let first = json(&out);
    let path = PathBuf::from(first["path"].as_str().unwrap());
    assert_eq!(path.file_name().unwrap(), "Shop-1.2.0.wmplugin");
    let bytes = std::fs::read(&path).unwrap();
    assert_eq!(first["size"].as_u64(), Some(bytes.len() as u64));
    let names: Vec<String> = wm_python::host::formats::zip_entries(&bytes).unwrap().into_iter().map(|e| e.name).collect();
    assert_eq!(names, vec!["plugin.json", "main.py", "assets/logo.svg", "helpers.py", "licenses/shop-lib/LICENSE", "locales/bn.json"]);
    assert_eq!(wm_python::host::formats::zip_read(&bytes, "plugin.json", 1 << 20).unwrap(), MANIFEST.as_bytes());
    // Same files, same bytes; the old archive in the folder is not packed into the new one.
    let (_, again, _) = cli(&["pack", s(&dir), "--json"]);
    assert_eq!(json(&again)["sha256"], first["sha256"]);
    let entry = &first["entry"];
    assert_eq!((entry["id"].as_str(), entry["type"].as_str(), entry["version"].as_str()), (Some(dir.file_name().unwrap().to_str().unwrap()), Some("plugin"), Some("1.2.0")));
    assert_eq!(entry["path"], "plugins/Shop-1.2.0.wmplugin");
    assert_eq!(entry["sha256"], first["sha256"]);
    assert_eq!(entry["author"], "A. Person");
    let (code, out, _) = cli(&["pack", s(&dir), "--entry-json", "-o", s(&dir.join("shop.wmplugin"))]);
    assert_eq!(code, 0);
    assert_eq!(json(&out)["path"], "plugins/shop.wmplugin");
    assert!(dir.join("shop.wmplugin").is_file());
}

#[test]
fn pack_refuses_a_plugin_the_app_would_refuse() {
    let dir = plugin("refuse", &[("plugin.json", &MANIFEST.replace("\"storage\"", "\"bogus\"")), ("main.py", MAIN), ("helpers.py", HELPERS)]);
    let (code, _, err) = cli(&["pack", s(&dir)]);
    assert_eq!(code, 1);
    assert!(err.contains("unknown_permission") && err.contains("not packed: 1 error"), "{err}");
    assert_eq!(cli(&["pack", s(&dir), "--no-check"]).0, 0);
}

#[test]
fn an_api_4_archive_holds_only_the_manifest_and_the_script() {
    let manifest = r#"{"format": "wmkeyboard-plugin", "id": "com.example.old", "name": "Old", "pluginVersion": "1", "apiVersion": 4}"#;
    let dir = plugin("api4", &[("plugin.json", manifest), ("main.py", "def render():\n    return ui.label('x')\n"), ("notes.py", "x = 1\n")]);
    let out = dir.join("old.wmplugin");
    let (code, _, err) = cli(&["pack", s(&dir), "-o", s(&out)]);
    assert_eq!(code, 0, "{err}");
    let names: Vec<String> = wm_python::host::formats::zip_entries(&std::fs::read(&out).unwrap()).unwrap().into_iter().map(|e| e.name).collect();
    assert_eq!(names, vec!["plugin.json", "main.py"]);
}

const TESTS: &str = "import json\n\
print('top level')\n\
\n\
def test_loads():\n    s = plugin_test.session()\n    assert s['load']()['ok']\n\
\n\
def test_counts():\n    print('counting')\n    assert 1 + 1 == 2\n\
\n\
def test_broken():\n    x = 1\n    assert x == 2, 'x is ' + str(x)\n\
\n\
def helper():\n    raise ValueError('not a test')\n";

#[test]
fn each_test_function_runs_on_its_own() {
    let dir = plugin("functions", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("helpers.py", HELPERS), ("test.py", TESTS), ("tests/test_more.py", "def test_more():\n    import helpers\n    assert helpers.title(2) == 'count 2'\n")]);
    let (code, out, _) = cli(&["test", s(&dir), "--json"]);
    assert_eq!(code, 1);
    let r = json(&out);
    assert_eq!((r["passed"].as_u64(), r["failed"].as_u64(), r["ok"].as_bool()), (Some(3), Some(1), Some(false)), "{r:#}");
    let tests = r["tests"].as_array().unwrap();
    let names: Vec<String> = tests.iter().map(|t| format!("{}::{}", t["file"].as_str().unwrap(), t["name"].as_str().unwrap())).collect();
    assert_eq!(names, vec!["test.py::test_loads", "test.py::test_counts", "test.py::test_broken", "tests/test_more.py::test_more"]);
    assert_eq!(tests[0]["output"], "top level\n");
    assert_eq!(tests[1]["output"], "counting\n");
    let error = &tests[2]["error"];
    assert_eq!((error["code"].as_str(), error["message"].as_str(), error["file"].as_str(), error["line"].as_u64()), (Some("AssertionError"), Some("x is 1"), Some("test.py"), Some(14)), "{error:#}");

    // -k picks by file::name.
    let (code, out, _) = cli(&["test", s(&dir), "--json", "-k", "count"]);
    assert_eq!(code, 0);
    assert_eq!(json(&out)["passed"], 1);
    let (_, out, _) = cli(&["test", s(&dir), "--json", "-k", "tests/"]);
    assert_eq!(json(&out)["tests"][0]["name"], "test_more");

    // Human output: each result, the failure's code frame, the count; prints on stdout.
    let (code, out, err) = cli(&["test", s(&dir)]);
    assert_eq!(code, 1);
    assert_eq!(out, "top level\ncounting\n");
    assert!(err.contains("ok   test.py::test_loads") && err.contains("FAIL test.py::test_broken"), "{err}");
    assert!(err.contains("--> test.py:14") && err.contains("assert x == 2"), "{err}");
    assert!(err.contains("3 passed, 1 failed in "), "{err}");

    // JUnit for CI.
    let xml = dir.join("results.xml");
    assert_eq!(cli(&["test", s(&dir), "--junit", s(&xml)]).0, 1);
    let xml = std::fs::read_to_string(xml).unwrap();
    assert!(xml.contains("<testsuites name=\"wmpy test\" tests=\"4\" failures=\"1\""), "{xml}");
    assert!(xml.contains("<testcase classname=\"test.py\" name=\"test_broken\"") && xml.contains("<failure type=\"AssertionError\" message=\"x is 1\">"), "{xml}");
}

#[test]
fn a_failure_in_a_helper_module_points_into_it_with_the_calls_that_led_there() {
    let fares = "def fare(n):\n    if n < 0:\n        raise ValueError('negative fare')\n    return n\n\n\ndef total(ns):\n    return sum(fare(n) for n in ns)\n";
    let tests = "import json\nimport statistics\nimport fares\n\n\ndef test_total():\n    assert fares.total([1, -1]) == 0\n\n\ndef test_mean():\n    statistics.mean([])\n\n\ndef test_parse():\n    json.loads('{')\n";
    let dir = plugin("traceback", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("helpers.py", HELPERS), ("fares.py", fares), ("test.py", tests)]);
    let (code, out, _) = cli(&["test", s(&dir), "--json"]);
    assert_eq!(code, 1);
    let r = json(&out);
    let error = &r["tests"][0]["error"];
    assert_eq!((error["file"].as_str(), error["line"].as_u64()), (Some("fares.py"), Some(3)), "{error:#}");
    let own: Vec<String> = error["traceback"].as_array().unwrap().iter().filter(|f| f["file"].is_string()).map(|f| format!("{}:{} {}", f["file"].as_str().unwrap(), f["line"], f["function"].as_str().unwrap())).collect();
    assert_eq!(own.first().map(String::as_str), Some("test.py:7 test_total"), "{own:?}");
    assert_eq!(own.last().map(String::as_str), Some("fares.py:3 fare"), "{own:?}");
    assert!(own.iter().any(|f| f.starts_with("fares.py:8 ")), "{own:?}");

    // Raised inside statistics, a Python module that comes with the keyboard:
    // the headline is the test's call, and a note says where it came out.
    let error = &r["tests"][1]["error"];
    assert_eq!((error["file"].as_str(), error["line"].as_u64()), (Some("test.py"), Some(11)), "{error:#}");
    assert!(error["notes"].as_array().unwrap().iter().any(|n| n.as_str().unwrap().contains("comes with the keyboard")), "{error:#}");
    // Raised by a native function (json's): no frame of its own, the call's line.
    let error = &r["tests"][2]["error"];
    assert_eq!((error["file"].as_str(), error["line"].as_u64()), (Some("test.py"), Some(15)), "{error:#}");

    let (_, _, err) = cli(&["test", s(&dir), "-k", "total"]);
    assert!(err.contains("--> fares.py:3") && err.contains("raise ValueError('negative fare')"), "{err}");
    assert!(err.contains("traceback (most recent call last):\n  test.py:7 in test_total\n      assert fares.total([1, -1]) == 0\n"), "{err}");
}

#[test]
fn a_failing_top_level_fails_the_file_and_hints_at_the_fix() {
    let dir = plugin("toplevel", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("helpers.py", HELPERS), ("test.py", "s = plugin_test.session()\nraise PermissionError(\"http.request needs the 'network' permission, which this plugin's manifest does not list\")\n\ndef test_never():\n    pass\n")]);
    let (code, out, _) = cli(&["test", s(&dir), "--json"]);
    assert_eq!(code, 1);
    let r = json(&out);
    assert_eq!((r["passed"].as_u64(), r["failed"].as_u64()), (Some(0), Some(1)));
    assert_eq!(r["tests"][0]["name"], "<module>");
    assert!(r["tests"][0]["error"]["help"].as_str().unwrap().contains("add \"network\" to the permissions"), "{r:#}");
}

#[test]
fn no_tests_and_no_matches_are_failures() {
    let dir = plugin("notests", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    assert_eq!(cli(&["test", s(&dir)]).0, 2);
    let dir = plugin("nomatch", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("helpers.py", HELPERS), ("test.py", "def test_a():\n    pass\n")]);
    let (code, _, err) = cli(&["test", s(&dir), "-k", "zzz"]);
    assert_eq!(code, 1);
    assert!(err.contains("no test matches -k zzz"), "{err}");
}

#[test]
fn watch_runs_again_when_a_file_changes() {
    use std::io::Read;
    let dir = plugin("watch", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("helpers.py", HELPERS), ("test.py", "def test_a():\n    print('run')\n")]);
    let mut child = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(["test", s(&dir), "--watch"]).stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::null()).spawn().unwrap();
    std::thread::sleep(std::time::Duration::from_millis(1500));
    std::fs::write(dir.join("test.py"), "def test_a():\n    print('run again')\n").unwrap();
    std::thread::sleep(std::time::Duration::from_millis(2500));
    let _ = child.kill();
    let mut out = String::new();
    child.stdout.take().unwrap().read_to_string(&mut out).unwrap();
    let _ = child.wait();
    assert_eq!(out, "run\nrun again\n");
}

#[test]
fn stubs_cover_the_package_and_the_test_module() {
    let manifest = r#"{"format": "wmkeyboard-plugin", "id": "com.example.old", "name": "Old", "pluginVersion": "1", "apiVersion": 4}"#;
    let dir = plugin("stubs", &[("plugin.json", manifest), ("main.py", "def render():\n    return ui.label('x')\n")]);
    let (code, _, err) = cli(&["stubs", s(&dir)]);
    assert_eq!(code, 0, "{err}");
    let stubs = dir.join(".wmpy/stubs");
    let http = std::fs::read_to_string(stubs.join("wm/http.pyi")).unwrap();
    assert!(http.contains("def get(url, params=None, **options) -> Response:"), "{http}");
    let builtins = std::fs::read_to_string(stubs.join("__builtins__.pyi")).unwrap();
    assert!(builtins.contains("plugin_test: _PluginTest") && builtins.contains("wm: _Api4_wm") && builtins.contains("ui: _Api4_ui"), "{builtins}");
    let module = std::fs::read_to_string(stubs.join("plugin_test.pyi")).unwrap();
    assert!(module.contains("def session(") && module.contains("class Session(TypedDict):"));
    // A stub of ours that the model no longer has goes; anything else stays.
    std::fs::write(stubs.join("wm/gone.pyi"), format!("{}\n", wm_python_tooling::stubs::HEADER)).unwrap();
    std::fs::write(stubs.join("wm/mine.pyi"), "x: int\n").unwrap();
    assert_eq!(cli(&["stubs", s(&dir)]).0, 0);
    assert!(!stubs.join("wm/gone.pyi").exists() && stubs.join("wm/mine.pyi").exists());
}
