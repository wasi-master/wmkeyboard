//! `wmpy i18n extract`, `check` and `stats` against plugin folders made on the fly.

use std::path::{Path, PathBuf};
use std::process::Command;

fn plugin(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-static-i18n-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
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

fn read(dir: &Path, f: &str) -> String {
    std::fs::read_to_string(dir.join(f)).unwrap()
}

fn s(p: &Path) -> &str {
    p.to_str().unwrap()
}

const MANIFEST: &str = r#"{
  "format": "wmkeyboard-plugin",
  "id": "com.example.words",
  "name": "Words",
  "pluginVersion": "1",
  "apiVersion": 5,
  "settings": [{"key": "size", "type": "choice", "label": "Size", "summary": "How big", "options": ["small", "large"]}]
}
"#;

const MAIN: &str = "from wm import ui, _, ngettext\nimport helpers\n\n\ndef render():\n    ui.button(_(\"Look up\"))\n    ui.label(_(\"Hello, {name}\", name=\"x\"))\n    ui.label(ngettext(\"{n} word\", \"{n} words\", 2))\n    ui.label(_(helpers.title()))\n";

const HELPERS: &str = "from wm.i18n import gettext\n\n\ndef title():\n    return gettext(\"Words of the day\")\n";

const BN: &str = "{\n    \"@name\": \"শব্দ\",\n    \"Look up\": \"খুঁজুন\",\n    \"Old text\": \"পুরোনো\"\n}\n";

fn folder(name: &str) -> PathBuf {
    plugin(name, &[("plugin.json", MANIFEST), ("main.py", MAIN), ("helpers.py", HELPERS), ("locales/bn.json", BN)])
}

#[test]
fn extract_adds_empty_keys_keeps_translations_and_prunes_only_when_asked() {
    let dir = folder("extract");
    let (code, out, err) = cli(&["i18n", "extract", s(&dir)]);
    assert_eq!(code, 0, "{out}{err}");
    let bn = json(&read(&dir, "locales/bn.json"));
    let keys: Vec<&str> = bn.as_object().unwrap().keys().map(String::as_str).collect();
    assert_eq!(
        keys,
        ["@name", "Look up", "Old text", "@setting.size", "@setting.size.summary", "@option.size.small", "@option.size.large", "Hello, {name}", "{n} word", "Words of the day"]
    );
    assert_eq!(bn["Look up"], "খুঁজুন");
    assert_eq!(bn["Old text"], "পুরোনো", "kept without --prune");
    assert_eq!(bn["{n} word"], serde_json::json!({"one": "", "other": ""}));
    assert!(read(&dir, "locales/bn.json").starts_with("{\n    \"@name\": \"শব্দ\",\n"), "the file's indent is kept");
    assert!(err.contains("found 9 texts to translate: 5 in plugin.json, 4 in the code (1 of them plural)"), "{err}");
    assert!(err.contains("locales/bn.json: added 7 keys, 7 of 9 still to translate"), "{err}");
    assert!(err.contains("1 key nothing uses, kept: \"Old text\""), "{err}");
    assert!(err.contains("warning[not_extractable]: _() is given text that is not a literal") && err.contains("--> main.py:9:16"), "{err}");

    // A new language, then --prune.
    let (code, out, _) = cli(&["i18n", "extract", s(&dir), "--lang", "ru", "--prune", "--json"]);
    assert_eq!(code, 0);
    let v = json(&out);
    assert_eq!(v["locales"][0]["created"], true);
    let ru = json(&read(&dir, "locales/ru.json"));
    assert_eq!(ru["{n} word"], serde_json::json!({"one": "", "few": "", "many": ""}));
    assert_eq!(json(&read(&dir, "locales/bn.json"))["Old text"], "পুরোনো", "--lang ru leaves bn alone");
    let (_, out, _) = cli(&["i18n", "extract", s(&dir), "--prune", "--dry-run", "--json"]);
    let v = json(&out);
    let bn_result = v["locales"].as_array().unwrap().iter().find(|l| l["tag"] == "bn").unwrap().clone();
    assert_eq!(bn_result["pruned"], serde_json::json!(["Old text"]));
    assert_eq!(bn_result["written"], false);
    assert!(json(&read(&dir, "locales/bn.json")).get("Old text").is_some(), "a dry run writes nothing");
    cli(&["i18n", "extract", s(&dir), "--prune"]);
    assert!(json(&read(&dir, "locales/bn.json")).get("Old text").is_none());
}

#[test]
fn check_fails_on_missing_empty_and_wrong_translations() {
    let dir = folder("check");
    let (code, _, err) = cli(&["i18n", "check", s(&dir)]);
    assert_eq!(code, 1);
    assert!(err.contains("error[missing_translation]: 7 texts have no translation"), "{err}");
    assert!(err.contains("wmpy i18n extract --lang bn"), "{err}");

    let done = r#"{
  "@name": "শব্দ", "@setting.size": "আকার", "@setting.size.summary": "কত বড়", "@option.size.small": "ছোট", "@option.size.large": "বড়",
  "Look up": "খুঁজুন", "Hello, {name}": "নমস্কার, {name}", "{n} word": {"one": "{n}টি শব্দ", "other": "{n}টি শব্দ"},
  "Words of the day": "আজকের শব্দ"
}
"#;
    std::fs::write(dir.join("locales/bn.json"), done).unwrap();
    let (code, _, err) = cli(&["i18n", "check", s(&dir)]);
    assert_eq!(code, 0, "{err}");
    assert!(err.contains("checked 1 language against 9 texts: 1 complete"), "{err}");

    let wrong = done.replace("নমস্কার, {name}", "নমস্কার, {nam}").replace(", \"other\": \"{n}টি শব্দ\"", "");
    std::fs::write(dir.join("locales/bn.json"), &wrong).unwrap();
    let (code, out, _) = cli(&["i18n", "check", s(&dir), "--json"]);
    assert_eq!(code, 1);
    let v = json(&out);
    let problems: Vec<String> = v["locales"][0]["problems"].as_array().unwrap().iter().map(|p| format!("{}: {}", p["key"].as_str().unwrap(), p["message"].as_str().unwrap())).collect();
    assert!(problems.iter().any(|p| p.starts_with("Hello, {name}: uses {nam}")), "{problems:?}");
    assert!(problems.iter().any(|p| p == "{n} word: has no other form, which bn needs"), "{problems:?}");
    assert_eq!(v["locales"][0]["translated"], 7);

    // --lang checks a language that has no file yet: all of it is missing.
    let (code, out, _) = cli(&["i18n", "check", s(&dir), "--lang", "de", "--json"]);
    assert_eq!(code, 1);
    let v = json(&out);
    assert_eq!(v["locales"].as_array().unwrap().len(), 1);
    assert_eq!(v["locales"][0]["missing"].as_array().unwrap().len(), 9);
}

#[test]
fn stats_count_each_language() {
    let dir = folder("stats");
    std::fs::write(dir.join("locales/ru.json"), "{\"Look up\": \"Найти\", \"{n} word\": {\"one\": \"{n} слово\", \"few\": \"{n} слова\", \"many\": \"{n} слов\"}}").unwrap();
    let (code, out, _) = cli(&["i18n", "stats", s(&dir)]);
    assert_eq!(code, 0);
    let rows: Vec<Vec<&str>> = out.lines().map(|l| l.split_whitespace().collect()).collect();
    assert_eq!(rows[0], ["language", "translated", "%", "missing", "empty", "unused", "problems"]);
    assert_eq!(rows[1], ["bn", "2/9", "22%", "7", "0", "1", "0"]);
    assert_eq!(rows[2], ["ru", "2/9", "22%", "7", "0", "0", "0"]);
    let (_, out, _) = cli(&["i18n", "stats", s(&dir), "--json"]);
    let v = json(&out);
    assert_eq!(v["total"], 9);
    assert_eq!(v["locales"][1]["tag"], "ru");
}

#[test]
fn bad_tags_and_bad_files() {
    let dir = folder("bad");
    let (code, _, err) = cli(&["i18n", "extract", s(&dir), "--lang", "english"]);
    assert_eq!(code, 2);
    assert!(err.contains("not a language tag"), "{err}");
    std::fs::write(dir.join("locales/bn.json"), "[1, 2]").unwrap();
    let (code, _, err) = cli(&["i18n", "extract", s(&dir)]);
    assert_eq!(code, 2);
    assert!(err.contains("locales/bn.json is not a JSON object; left as it is"), "{err}");
    assert_eq!(read(&dir, "locales/bn.json"), "[1, 2]");
    assert_eq!(cli(&["i18n", "check", s(&dir)]).0, 1);
}
