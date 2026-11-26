//! The work behind `workspace/executeCommand`: other wmpy commands run as
//! child processes of this binary, so the language server shares their
//! output shapes without sharing their code.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{json, Value};

/// How `wmpy.preview` shows the page.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PreviewMode {
    /// Open the system browser too.
    Browser,
    /// Only return the URL: the client shows it.
    Client,
    /// Only return the URL.
    None,
}

impl PreviewMode {
    pub fn parse(v: Option<&str>) -> PreviewMode {
        match v {
            Some("client") => PreviewMode::Client,
            Some("none") => PreviewMode::None,
            _ => PreviewMode::Browser,
        }
    }
}

pub struct Preview {
    child: Child,
    url: String,
}

/// The preview servers this language server started, one per plugin folder.
#[derive(Clone, Default)]
pub struct Previews(Arc<Mutex<HashMap<PathBuf, Preview>>>);

/// How long a preview server has to print its URL.
const PREVIEW_START: Duration = Duration::from_secs(20);

impl Previews {
    /// The URL of [dir]'s preview, starting `wmpy preview` for it when none runs.
    pub fn start(&self, exe: &Path, dir: &Path) -> Result<String, String> {
        {
            let mut map = self.0.lock().map_err(|_| "preview lock poisoned")?;
            if let Some(p) = map.get_mut(dir) {
                if matches!(p.child.try_wait(), Ok(None)) {
                    return Ok(p.url.clone());
                }
                map.remove(dir);
            }
        }
        let mut child = Command::new(exe)
            .arg("preview")
            .arg(dir)
            .args(["--port", "0", "--no-open", "--print-url"])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| format!("could not start wmpy preview: {e}"))?;
        let stdout = child.stdout.take().ok_or("no stdout")?;
        let stderr = child.stderr.take().ok_or("no stderr")?;
        let (tx, rx) = std::sync::mpsc::channel::<Option<String>>();
        std::thread::spawn(move || {
            let mut lines = BufReader::new(stdout).lines();
            let first = lines.next().and_then(Result::ok);
            let _ = tx.send(first);
            // Keep reading so the server never blocks on a full pipe.
            for _ in lines {}
        });
        let errors = Arc::new(Mutex::new(String::new()));
        let sink = errors.clone();
        std::thread::spawn(move || {
            let mut text = String::new();
            let _ = BufReader::new(stderr).read_to_string(&mut text);
            if let Ok(mut e) = sink.lock() {
                *e = text;
            }
        });
        let line = match rx.recv_timeout(PREVIEW_START) {
            Ok(Some(line)) => line,
            other => {
                let _ = child.kill();
                let _ = child.wait();
                std::thread::sleep(Duration::from_millis(50));
                let said = errors.lock().map(|e| e.trim().to_string()).unwrap_or_default();
                let why = if other.is_err() { "it printed no URL in time".to_string() } else { "it stopped".to_string() };
                return Err(if said.is_empty() { format!("wmpy preview did not start: {why}") } else { format!("wmpy preview did not start: {said}") });
            }
        };
        let parsed: Value = serde_json::from_str(line.trim()).map_err(|_| format!("wmpy preview printed {line:?}, not its URL"))?;
        let url = parsed.get("url").and_then(Value::as_str).ok_or("wmpy preview printed no url")?.to_string();
        self.0.lock().map_err(|_| "preview lock poisoned")?.insert(dir.to_path_buf(), Preview { child, url: url.clone() });
        Ok(url)
    }

    /// Stop every preview server.
    pub fn stop_all(&self) {
        if let Ok(mut map) = self.0.lock() {
            for (_, mut p) in map.drain() {
                let _ = p.child.kill();
                let _ = p.child.wait();
            }
        }
    }
}

/// Open [url] in the system browser.
pub fn open_browser(url: &str) -> Result<(), String> {
    let mut cmd = if cfg!(target_os = "macos") {
        let mut c = Command::new("open");
        c.arg(url);
        c
    } else if cfg!(windows) {
        let mut c = Command::new("rundll32");
        c.args(["url.dll,FileProtocolHandler", url]);
        c
    } else {
        let mut c = Command::new("xdg-open");
        c.arg(url);
        c
    };
    cmd.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
    cmd.spawn().map(|_| ()).map_err(|e| format!("could not open the browser: {e}"))
}

/// What a wmpy child command gave back.
pub struct Output {
    pub code: i32,
    pub stdout: String,
    pub stderr: String,
}

/// Run `<exe> <args>` in [cwd] (the current folder when None) and wait for it.
pub fn run(exe: &Path, args: &[&str], cwd: Option<&Path>) -> Result<Output, String> {
    let mut cmd = Command::new(exe);
    cmd.args(args).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped()).env("NO_COLOR", "1");
    if let Some(dir) = cwd {
        cmd.current_dir(dir);
    }
    let out = cmd.output().map_err(|e| format!("could not run wmpy {}: {e}", args.first().unwrap_or(&"")))?;
    Ok(Output {
        code: out.status.code().unwrap_or(-1),
        stdout: String::from_utf8_lossy(&out.stdout).to_string(),
        stderr: String::from_utf8_lossy(&out.stderr).to_string(),
    })
}

/// The JSON a `--json` command printed: its last line that parses, or the whole output.
pub fn json_of(out: &Output) -> Option<Value> {
    if let Ok(v) = serde_json::from_str::<Value>(out.stdout.trim()) {
        return Some(v);
    }
    out.stdout.lines().rev().find_map(|l| serde_json::from_str::<Value>(l.trim()).ok().filter(|v| v.is_object()))
}

/// `wmpy test <dir> --json [-k filter]`, as JSON. A run that printed no JSON
/// becomes `{"ok", "output"}`.
pub fn test(exe: &Path, dir: &Path, filter: Option<&str>) -> Result<Value, String> {
    let dir_text = dir.to_string_lossy().to_string();
    let mut args = vec!["test", dir_text.as_str(), "--json"];
    if let Some(k) = filter {
        args.extend(["-k", k]);
    }
    let out = run(exe, &args, None)?;
    match json_of(&out) {
        Some(v) => Ok(v),
        None if out.code == 2 => Err(format!("wmpy test could not run: {}", out.stderr.trim())),
        None => Ok(json!({"ok": out.code == 0, "output": format!("{}{}", out.stdout, out.stderr)})),
    }
}

/// One line saying what failed in a `wmpy test --json` result.
pub fn test_summary(v: &Value) -> Option<String> {
    if v.get("ok").and_then(Value::as_bool) != Some(false) {
        return None;
    }
    let failed: Vec<String> = v
        .get("tests")
        .and_then(Value::as_array)
        .map(|t| t.iter().filter(|t| t.get("ok").and_then(Value::as_bool) == Some(false)).filter_map(|t| t.get("name").and_then(Value::as_str).map(str::to_string)).collect())
        .unwrap_or_default();
    Some(match (v.get("failed").and_then(Value::as_u64), failed.is_empty()) {
        (Some(n), false) => format!("{n} test(s) failed: {}", failed.join(", ")),
        (Some(n), true) => format!("{n} test(s) failed"),
        _ => "The plugin's tests failed.".to_string(),
    })
}

/// `wmpy pack <dir> --json`: `{"path", "sha256", "size"}`.
pub fn pack(exe: &Path, dir: &Path) -> Result<Value, String> {
    let dir_text = dir.to_string_lossy().to_string();
    let out = run(exe, &["pack", dir_text.as_str(), "--json"], None)?;
    match json_of(&out) {
        Some(v) if out.code == 0 => Ok(json!({"path": v.get("path"), "sha256": v.get("sha256"), "size": v.get("size")})),
        _ => Err(format!("wmpy pack failed: {}", first_words(&out))),
    }
}

/// What a failed child said, in a line or two.
pub fn first_words(out: &Output) -> String {
    let text = if out.stderr.trim().is_empty() { out.stdout.trim() } else { out.stderr.trim() };
    text.lines().take(6).collect::<Vec<_>>().join("\n")
}
