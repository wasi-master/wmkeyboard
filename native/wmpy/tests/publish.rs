//! `wmpy publish` against a fake of its own steps (WMPY_SELF answers
//! `check`, `test` and `pack` as wmpy does), a fake GitHub API on
//! localhost, and no browser or clipboard. Nothing here reaches GitHub.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::mpsc;

use base64::Engine;
use sha2::{Digest, Sha256};

fn temp(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-publish-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

const MANIFEST: &str = r#"{
  "format": "wmkeyboard-plugin",
  "version": 1,
  "id": "com.example.echo",
  "name": "Echo",
  "pluginVersion": "1.2.0",
  "description": "Types back what you write in its box.",
  "apiVersion": 5,
  "permissions": ["storage", "network", "field.read"],
  "hosts": ["api.example.com"]
}"#;

/// The shared .wmplugin vector's bytes, so what wmpy encodes is checked against the app and the bot.
fn vector_plugin() -> Vec<u8> {
    let path = std::env::var_os("WMKB_SUBMISSION_VECTORS").map(PathBuf::from).unwrap_or_else(|| {
        let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        loop {
            let c = dir.join("app/src/test/resources/addons/community-submission-vectors.json");
            if c.is_file() || !dir.pop() {
                return c;
            }
        }
    });
    let all: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&path).expect("the shared vectors (set WMKB_SUBMISSION_VECTORS)")).unwrap();
    let v = all["vectors"].as_array().unwrap().iter().find(|v| v.get("bytes").is_some()).expect("a .wmplugin vector");
    base64::engine::general_purpose::STANDARD.decode(v["bytes"].as_str().unwrap()).unwrap()
}

struct Plugin {
    dir: PathBuf,
    archive: PathBuf,
    fake: PathBuf,
}

/// A plugin folder, its archive, and a fake wmpy whose `check` gives [check]
/// (exiting [check_code]) and whose `test` gives [test].
fn plugin(name: &str, archive_bytes: &[u8], check: &str, check_code: i32, test: Option<&str>) -> Plugin {
    let dir = temp(name);
    std::fs::write(dir.join("plugin.json"), MANIFEST).unwrap();
    std::fs::write(dir.join("main.py"), "from wm import plugin, ui\n").unwrap();
    if test.is_some() {
        std::fs::write(dir.join("test.py"), "pass\n").unwrap();
    }
    let archive = dir.join("Echo-1.2.0.wmplugin");
    std::fs::write(&archive, archive_bytes).unwrap();
    let entry = format!(
        r#"{{"id": "echo", "type": "plugin", "name": "Echo", "version": "1.2.0", "description": "Types back what you write in its box.", "path": "plugins/Echo-1.2.0.wmplugin", "sha256": "stale", "sizeBytes": 1}}"#
    );
    let pack = format!(r#"{{"path": "{}", "size": {}, "sha256": "x", "entry": {entry}, "shake": {{}}}}"#, archive.display(), archive_bytes.len());
    let fake = dir.join("fake-wmpy");
    // Only shell builtins, and no single quotes in the answers: they are echoed inside them.
    let script = format!(
        "#!/bin/sh\ncase \"$1\" in\n  check) echo '{check}'; exit {check_code} ;;\n  test) echo '{}'; exit 0 ;;\n  pack) echo '{pack}' ;;\n  *) echo \"fake wmpy: $*\" >&2; exit 2 ;;\nesac\n",
        test.unwrap_or("{}")
    );
    std::fs::write(&fake, script).unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&fake, std::fs::Permissions::from_mode(0o755)).unwrap();
    }
    Plugin { dir, archive, fake }
}

const CHECK_OK: &str = r#"{"ok": true, "errors": 0, "warnings": 1, "problems": []}"#;
const TEST_OK: &str = r#"{"ok": true, "passed": 3, "failed": 0, "tests": []}"#;

struct Run {
    code: i32,
    stdout: String,
    stderr: String,
}

fn publish(p: &Plugin, args: &[&str], env: &[(&str, &str)], stdin: &str) -> Run {
    let mut cmd = Command::new(env!("CARGO_BIN_EXE_wmpy"));
    cmd.arg("publish").arg(&p.dir).args(args);
    cmd.env("WMPY_SELF", &p.fake).env("WMPY_BROWSER", "none").env("WMPY_CLIPBOARD", "none").env("WMPY_NO_GH", "1");
    cmd.env_remove("GITHUB_TOKEN").env_remove("GH_TOKEN").env_remove("WMPY_GITHUB_API").env_remove("WMPY_COMMUNITY_REPO");
    for (k, v) in env {
        cmd.env(k, v);
    }
    let mut child = cmd.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().unwrap();
    child.stdin.take().unwrap().write_all(stdin.as_bytes()).unwrap();
    let out = child.wait_with_output().unwrap();
    Run { code: out.status.code().unwrap_or(-1), stdout: String::from_utf8_lossy(&out.stdout).into(), stderr: String::from_utf8_lossy(&out.stderr).into() }
}

fn decode(token: &str) -> Vec<u8> {
    let body = token.strip_prefix("wmkb:1:").expect("wmkb:1: prefix");
    assert!(!body.contains('='), "no padding");
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(body).unwrap();
    miniz_oxide::inflate::decompress_to_vec_zlib(&bytes).unwrap()
}

fn payload_in(text: &str) -> String {
    let start = text.find("wmkb:1:").expect("a wmkb:1: payload");
    text[start..].chars().take_while(|c| c.is_ascii_alphanumeric() || matches!(c, ':' | '-' | '_')).collect()
}

/// Percent-decoding of one query value.
fn unescape(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            out.push(u8::from_str_radix(&s[i + 1..i + 3], 16).unwrap());
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    String::from_utf8(out).unwrap()
}

fn query(url: &str) -> Vec<(String, String)> {
    url.split_once('?').unwrap().1.split('&').map(|kv| {
        let (k, v) = kv.split_once('=').unwrap();
        (k.to_string(), unescape(v))
    }).collect()
}

#[test]
fn a_dry_run_prints_the_issue_the_bot_reads_and_sends_nothing() {
    let bytes = vector_plugin();
    let p = plugin("dry", &bytes, CHECK_OK, 0, Some(TEST_OK));
    let r = publish(&p, &["--dry-run", "--tags", "Text, echo,text", "--licence", "Apache-2.0"], &[], "");
    assert_eq!(r.code, 0, "{}", r.stderr);
    let mut lines = r.stdout.lines();
    assert_eq!(lines.next(), Some("[Plugin] Echo"));
    assert_eq!(lines.next(), Some(""));
    assert_eq!(lines.next(), Some("<!-- wmkb-submit:plugin -->"));
    let headings: Vec<&str> = r.stdout.lines().filter(|l| l.starts_with("### ")).collect();
    assert_eq!(headings, ["### Name", "### Description", "### Tags", "### License", "### Updates", "### Based on", "### Plugin", "### Rights"]);
    assert!(r.stdout.contains("### Tags\n\ntext, echo\n\n### License\n\nApache-2.0\n\n### Updates\n\n_No response_\n"), "{}", r.stdout);
    assert!(r.stdout.contains("- [X] I wrote this code, or I have the right to publish it under the licence above."));
    assert!(r.stdout.contains("its tests pass (3)"));
    // Byte for byte what the app and the bot know as the shared vector.
    assert_eq!(decode(&payload_in(&r.stdout)), bytes);
    // The summary flags the risky permissions and the pair that reads and sends.
    assert!(r.stderr.contains("! network"), "{}", r.stderr);
    assert!(r.stderr.contains("! field.read"));
    assert!(r.stderr.contains("read what the user has and send data off the phone"));
    assert!(r.stderr.contains("dry run: nothing sent"));
    // Small enough for the link: the last line opens the form with every field.
    let url = r.stdout.lines().last().unwrap();
    assert!(url.starts_with("https://github.com/wasi-master/wmkeyboard-community-addons/issues/new?template=submit-plugin.yml&"), "{url}");
    assert!(!url.contains('+'));
    let q = query(url);
    let get = |k: &str| q.iter().find(|(key, _)| key == k).map(|(_, v)| v.clone());
    assert_eq!(get("title").as_deref(), Some("[Plugin] Echo"));
    assert_eq!(get("license").as_deref(), Some("Apache-2.0"));
    assert_eq!(get("tags").as_deref(), Some("text, echo"));
    assert_eq!(decode(&get("payload").unwrap()), bytes);
}

/// Answers one request with [status] and [reply], and hands back what was asked.
fn fake_github(status: u16, reply: &str) -> (String, mpsc::Receiver<(String, Vec<String>, String)>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let reply = reply.to_string();
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let (stream, _) = listener.accept().unwrap();
        let mut reader = BufReader::new(stream.try_clone().unwrap());
        let mut request_line = String::new();
        reader.read_line(&mut request_line).unwrap();
        let mut headers = Vec::new();
        let mut length = 0;
        loop {
            let mut line = String::new();
            reader.read_line(&mut line).unwrap();
            let line = line.trim_end().to_string();
            if line.is_empty() {
                break;
            }
            if let Some(v) = line.to_lowercase().strip_prefix("content-length:") {
                length = v.trim().parse().unwrap();
            }
            headers.push(line);
        }
        let mut body = vec![0; length];
        reader.read_exact(&mut body).unwrap();
        let mut stream = stream;
        let reason = if status == 201 { "Created" } else { "Error" };
        write!(stream, "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{reply}", reply.len()).unwrap();
        tx.send((request_line.trim_end().to_string(), headers, String::from_utf8(body).unwrap())).unwrap();
    });
    (url, rx)
}

#[test]
fn with_a_token_it_files_the_issue_through_the_api() {
    let bytes = vector_plugin();
    let p = plugin("api", &bytes, CHECK_OK, 0, None);
    let (api, asked) = fake_github(201, r#"{"number": 42, "html_url": "https://github.com/o/r/issues/42"}"#);
    let r = publish(&p, &["--yes", "--api-url", &api, "--github-repo", "o/r", "--update", "echo"], &[("GITHUB_TOKEN", "test-token")], "");
    assert_eq!(r.code, 0, "{}", r.stderr);
    assert_eq!(r.stdout.trim(), "https://github.com/o/r/issues/42");
    assert!(r.stderr.contains("filed issue #42"));
    assert!(r.stderr.contains("wmpy publish --update echo"));
    let (line, headers, body) = asked.recv().unwrap();
    assert_eq!(line, "POST /repos/o/r/issues HTTP/1.1");
    assert!(headers.iter().any(|h| h == "authorization: Bearer test-token" || h == "Authorization: Bearer test-token"), "{headers:?}");
    assert!(headers.iter().any(|h| h.to_lowercase() == "x-github-api-version: 2022-11-28"));
    let sent: serde_json::Value = serde_json::from_str(&body).unwrap();
    assert_eq!(sent["title"], "[Plugin] Echo");
    assert_eq!(sent["labels"], serde_json::json!(["submission", "type:plugin"]));
    let text = sent["body"].as_str().unwrap();
    assert!(text.starts_with("<!-- wmkb-submit:plugin -->\n"));
    assert!(text.contains("### Updates\n\necho\n"));
    assert!(text.contains("it has no tests"));
    assert_eq!(decode(&payload_in(text)), bytes);
}

#[test]
fn a_refused_token_falls_back_to_the_form() {
    let p = plugin("refused", &vector_plugin(), CHECK_OK, 0, None);
    let (api, _asked) = fake_github(401, r#"{"message": "Bad credentials"}"#);
    let r = publish(&p, &["--yes", "--api-url", &api], &[("GH_TOKEN", "bad")], "");
    assert_eq!(r.code, 0, "{}", r.stderr);
    assert!(r.stderr.contains("GitHub did not accept the token (HTTP 401: Bad credentials)"), "{}", r.stderr);
    assert!(r.stdout.trim().starts_with("https://github.com/wasi-master/wmkeyboard-community-addons/issues/new?template=submit-plugin.yml"));
}

#[test]
fn without_a_token_it_asks_first_and_opens_the_filled_form() {
    let p = plugin("browser", &vector_plugin(), CHECK_OK, 0, None);
    let declined = publish(&p, &[], &[], "n\n");
    assert_eq!(declined.code, 1);
    assert!(declined.stderr.contains("Send it? [y/N]"));
    assert!(declined.stderr.contains("not sent"));
    assert!(declined.stdout.is_empty());
    let r = publish(&p, &[], &[], "y\n");
    assert_eq!(r.code, 0, "{}", r.stderr);
    assert!(r.stderr.contains("opened in your browser as a filled-in form"));
    assert!(r.stderr.contains("open this link to the form"));
    assert!(query(r.stdout.trim()).iter().any(|(k, _)| k == "payload"));
}

/// Bytes that barely compress, so their length decides how they travel.
fn noise(n: usize) -> Vec<u8> {
    let mut x: u32 = 7;
    (0..n).map(|_| {
        x = x.wrapping_mul(1_103_515_245).wrapping_add(12_345);
        (x >> 16) as u8
    }).collect()
}

#[test]
fn a_plugin_too_long_for_a_link_is_pasted_and_a_big_one_attached() {
    let paste = plugin("paste", &noise(20_000), CHECK_OK, 0, None);
    let r = publish(&paste, &["--yes"], &[], "");
    assert_eq!(r.code, 0, "{}", r.stderr);
    let saved = paste.archive.with_extension("wmkb.txt");
    assert!(r.stderr.contains("No clipboard here"), "{}", r.stderr);
    assert_eq!(decode(&std::fs::read_to_string(&saved).unwrap()), noise(20_000));
    assert!(!query(r.stdout.trim()).iter().any(|(k, _)| k == "payload"));

    let attach = plugin("attach", &noise(100_000), CHECK_OK, 0, None);
    let r = publish(&attach, &["--yes"], &[], "");
    assert_eq!(r.code, 0, "{}", r.stderr);
    let zip = attach.archive.with_extension("zip");
    assert_eq!(std::fs::read(&zip).unwrap(), noise(100_000));
    assert!(r.stderr.contains(&format!("Drag {} into the Plugin box", zip.display())));

    let huge = plugin("huge", &noise(1_100_000), CHECK_OK, 0, None);
    let r = publish(&huge, &["--yes"], &[], "");
    assert_eq!(r.code, 1);
    assert!(r.stderr.contains("at most 1 MB"));
}

#[test]
fn nothing_is_sent_when_the_check_or_the_tests_fail() {
    let failing = r#"{"ok": false, "errors": 2, "warnings": 0, "problems": []}"#;
    let p = plugin("check-fails", &vector_plugin(), failing, 1, None);
    let r = publish(&p, &["--yes", "--api-url", "http://127.0.0.1:9"], &[("GITHUB_TOKEN", "t")], "");
    assert_eq!(r.code, 1);
    assert!(r.stderr.contains("not sent: `wmpy check` found 2 errors"), "{}", r.stderr);
    assert!(r.stdout.is_empty());

    let tests = r#"{"ok": false, "passed": 2, "failed": 1, "tests": [{"file": "test.py", "name": "test_upper", "ok": false, "error": {"message": "AssertionError: A != a", "file": "test.py", "line": 7}}]}"#;
    let p = plugin("tests-fail", &vector_plugin(), CHECK_OK, 0, Some(tests));
    let r = publish(&p, &["--yes"], &[], "");
    assert_eq!(r.code, 1);
    assert!(r.stderr.contains("FAILED test.py::test_upper: AssertionError: A != a (test.py:7)"), "{}", r.stderr);
    assert!(r.stderr.contains("not sent: 1 failed, 2 passed"));
}

#[test]
fn bad_arguments_are_refused_before_any_check() {
    let p = plugin("args", &vector_plugin(), CHECK_OK, 0, None);
    let r = publish(&p, &["--licence", "CC-BY-4.0", "--update", "Echo Tool"], &[], "");
    assert_eq!(r.code, 2);
    assert!(r.stderr.contains("not a licence the plugin form offers"));
    assert!(r.stderr.contains("--update takes the plugin's id"));
    assert!(!r.stderr.contains("checking"));
}

fn sha(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

#[test]
fn into_your_own_repository_it_updates_the_entry_and_keeps_the_rest() {
    let bytes = vector_plugin();
    let p = plugin("repo", &bytes, CHECK_OK, 0, None);
    let root = temp("repo-root");
    let manifest = r#"{
    "format": "wmkeyboard-repo",
    "version": 1,
    "repo": {"id": "com.example.mine", "name": "Mine"},
    "addons": [
        {
            "id": "night",
            "type": "theme",
            "name": "Night",
            "version": "1.0.0",
            "path": "themes/night.wmtheme.json"
        },
        {
            "id": "echo",
            "type": "plugin",
            "name": "Echo",
            "version": "1.1.0",
            "path": "plugins/echo.wmplugin",
            "sha256": "old",
            "sizeBytes": 3,
            "previews": ["previews/echo.png"],
            "minAppVersion": 45,
            "license": "MIT",
            "fileTypes": ["md"]
        }
    ]
}
"#;
    std::fs::write(root.join("wmkeyboard-repo.json"), manifest).unwrap();

    let dry = publish(&p, &["--repo", root.to_str().unwrap(), "--dry-run"], &[], "");
    assert_eq!(dry.code, 0, "{}", dry.stderr);
    assert!(dry.stderr.contains("would update echo from 1.1.0 to 1.2.0"));
    assert_eq!(std::fs::read_to_string(root.join("wmkeyboard-repo.json")).unwrap(), manifest);
    assert!(!root.join("plugins").exists());

    let r = publish(&p, &["--repo", root.to_str().unwrap(), "--tags", "text"], &[], "");
    assert_eq!(r.code, 0, "{}", r.stderr);
    assert_eq!(std::fs::read(root.join("plugins/echo.wmplugin")).unwrap(), bytes);
    let text = std::fs::read_to_string(root.join("wmkeyboard-repo.json")).unwrap();
    // The file keeps its four-space indent and its trailing newline.
    assert!(text.starts_with("{\n    \"format\": \"wmkeyboard-repo\",\n"), "{text}");
    assert!(text.ends_with("}\n"));
    let m: serde_json::Value = serde_json::from_str(&text).unwrap();
    assert_eq!(m["addons"][0]["id"], "night");
    assert_eq!(m["addons"][0].as_object().unwrap().len(), 5);
    let e = &m["addons"][1];
    assert_eq!(e["version"], "1.2.0");
    assert_eq!(e["path"], "plugins/echo.wmplugin");
    assert_eq!(e["sha256"], sha(&bytes));
    assert_eq!(e["sizeBytes"], bytes.len());
    assert_eq!(e["previews"], serde_json::json!(["previews/echo.png"]));
    assert_eq!(e["minAppVersion"], 45);
    assert_eq!(e["license"], "MIT");
    assert_eq!(e["tags"], serde_json::json!(["text"]));
    assert_eq!(e.get("fileTypes"), None, "pack lists none now");
    assert!(r.stderr.contains("Nothing is committed"));
    assert!(r.stderr.contains("git add plugins/echo.wmplugin wmkeyboard-repo.json"));
    assert!(r.stderr.contains("git commit -m 'Publish Echo 1.2.0'"));

    // Again at the same version: written, with a warning that phones will not update.
    let again = publish(&p, &["--repo", root.to_str().unwrap()], &[], "");
    assert_eq!(again.code, 0);
    assert!(again.stderr.contains("phones only offer an update for a higher version"));
}

#[test]
fn into_your_own_repository_a_new_plugin_is_added_at_the_end() {
    let bytes = vector_plugin();
    let p = plugin("repo-new", &bytes, CHECK_OK, 0, None);
    let root = temp("repo-new-root");
    std::fs::write(root.join("wmkeyboard-repo.json"), "{\n  \"format\": \"wmkeyboard-repo\",\n  \"version\": 1,\n  \"repo\": {\"id\": \"r\"},\n  \"addons\": []\n}").unwrap();
    let r = publish(&p, &["--repo", root.to_str().unwrap(), "--licence", "GPL-3.0-or-later"], &[], "");
    assert_eq!(r.code, 0, "{}", r.stderr);
    let text = std::fs::read_to_string(root.join("wmkeyboard-repo.json")).unwrap();
    assert!(!text.ends_with('\n'));
    let m: serde_json::Value = serde_json::from_str(&text).unwrap();
    let e = &m["addons"][0];
    assert_eq!(e["id"], "echo");
    assert_eq!(e["type"], "plugin");
    assert_eq!(e["license"], "GPL-3.0-or-later");
    assert_eq!(e["sha256"], sha(&bytes));
    assert!(Path::new(&root.join("plugins/echo.wmplugin")).is_file());

    let not_a_repo = temp("not-a-repo");
    let r = publish(&p, &["--repo", not_a_repo.to_str().unwrap()], &[], "");
    assert_eq!(r.code, 2);
    assert!(r.stderr.contains("--repo takes the folder of an addon repository"));
}
