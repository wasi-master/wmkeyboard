//! `wmpy lsp` spoken to over stdio, as an editor would: initialize, open a
//! plugin's files, ask for completion, hover, a definition across modules,
//! code lenses and a quick fix that edits plugin.json, then shut down.

use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::{channel, Receiver};
use std::time::Duration;

use serde_json::{json, Value};

const MANIFEST: &str = "{\n  \"format\": \"wmkeyboard-plugin\",\n  \"id\": \"com.example.flip\",\n  \"name\": \"Flip\",\n  \"pluginVersion\": \"1.0.0\",\n  \"apiVersion\": 5,\n  \"permissions\": []\n}\n";

const MAIN: &str = "import random\nfrom wm import plugin, ui, http\nfrom helpers import double\n\nstate = plugin.state(result=\"Press Flip\")\n\n\ndef flip():\n    state.result = random.choice([\"Heads\", \"Tails\"])\n    http.get(\"https://api.example.com/coin\")\n\n\ndef render():\n    with ui.column():\n        ui.title(state.result)\n        ui.button(\"Flip\", on_click=flip, primary=True)\n        ui.caption(str(double(2)))\n";

const HELPERS: &str = "def double(x):\n    return x * 2\n";

fn plugin(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-lsp-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("plugin.json"), MANIFEST).unwrap();
    std::fs::write(dir.join("main.py"), MAIN).unwrap();
    std::fs::write(dir.join("helpers.py"), HELPERS).unwrap();
    std::fs::write(dir.join("test.py"), "s = plugin_test.session()\n\ndef test_loads():\n    assert s[\"load\"]()[\"ok\"]\n\ntest_loads()\n").unwrap();
    dir.canonicalize().unwrap()
}

fn uri(path: &Path) -> String {
    let mut out = String::from("file://");
    for b in path.to_string_lossy().bytes() {
        if b.is_ascii_alphanumeric() || b"-._~/".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// Line and UTF-16 character of the end of the first [needle] in [text].
fn pos_after(text: &str, needle: &str) -> Value {
    let at = text.find(needle).expect("needle in text") + needle.len();
    let line = text[..at].matches('\n').count();
    let line_start = text[..at].rfind('\n').map_or(0, |p| p + 1);
    json!({"line": line, "character": text[line_start..at].encode_utf16().count()})
}

struct Lsp {
    child: Child,
    stdin: ChildStdin,
    from: Receiver<Value>,
    next: i64,
    /// Notifications seen while waiting for responses.
    seen: Vec<Value>,
}

impl Lsp {
    fn start() -> Lsp {
        let mut child = Command::new(env!("CARGO_BIN_EXE_wmpy"))
            .args(["lsp", "--stdio"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .unwrap();
        let stdin = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();
        let (tx, from) = channel();
        std::thread::spawn(move || {
            let mut r = BufReader::new(stdout);
            loop {
                let mut length = 0usize;
                loop {
                    let mut line = String::new();
                    if r.read_line(&mut line).unwrap_or(0) == 0 {
                        return;
                    }
                    let line = line.trim();
                    if line.is_empty() {
                        break;
                    }
                    if let Some(n) = line.strip_prefix("Content-Length: ") {
                        length = n.parse().unwrap();
                    }
                }
                let mut body = vec![0; length];
                if r.read_exact(&mut body).is_err() {
                    return;
                }
                if tx.send(serde_json::from_slice::<Value>(&body).unwrap()).is_err() {
                    return;
                }
            }
        });
        Lsp { child, stdin, from, next: 1, seen: Vec::new() }
    }

    fn send(&mut self, msg: Value) {
        let body = msg.to_string();
        write!(self.stdin, "Content-Length: {}\r\n\r\n{body}", body.len()).unwrap();
        self.stdin.flush().unwrap();
    }

    fn notify(&mut self, method: &str, params: Value) {
        self.send(json!({"jsonrpc": "2.0", "method": method, "params": params}));
    }

    fn next_message(&mut self) -> Value {
        self.from.recv_timeout(Duration::from_secs(30)).expect("a message from the server within 30 s")
    }

    fn request(&mut self, method: &str, params: Value) -> Value {
        let m = self.raw(method, params);
        assert!(m.get("error").is_none(), "{method} failed: {m}");
        m["result"].clone()
    }

    /// The whole response to a request, an error included.
    fn raw(&mut self, method: &str, params: Value) -> Value {
        let id = self.next;
        self.next += 1;
        self.send(json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params}));
        loop {
            let m = self.next_message();
            if m.get("id") == Some(&json!(id)) && m.get("method").is_none() {
                return m;
            }
            if m.get("method").is_some() && m.get("id").is_some() {
                // A request from the server (registerCapability): answer it.
                let reply = json!({"jsonrpc": "2.0", "id": m["id"], "result": null});
                self.send(reply);
            }
            self.seen.push(m);
        }
    }

    /// The first diagnostics published for [uri] with a problem coded [code].
    fn diagnostics_with(&mut self, uri: &str, code: &str) -> Value {
        let matches = |m: &Value| {
            m["method"] == "textDocument/publishDiagnostics"
                && m["params"]["uri"] == uri
                && m["params"]["diagnostics"].as_array().is_some_and(|d| d.iter().any(|d| d["code"] == code))
        };
        if let Some(found) = self.seen.iter().find(|m| matches(m)) {
            return found["params"]["diagnostics"].clone();
        }
        loop {
            let m = self.next_message();
            if matches(&m) {
                return m["params"]["diagnostics"].clone();
            }
            self.seen.push(m);
        }
    }
}

#[test]
fn an_editor_session_over_stdio() {
    let dir = plugin("session");
    let main_uri = uri(&dir.join("main.py"));
    let manifest_uri = uri(&dir.join("plugin.json"));
    let mut lsp = Lsp::start();

    let init = lsp.request(
        "initialize",
        json!({
            "processId": null,
            "rootUri": uri(&dir),
            "workspaceFolders": [{"uri": uri(&dir), "name": "flip"}],
            "capabilities": {"general": {"positionEncodings": ["utf-16"]}, "textDocument": {"completion": {"completionItem": {"snippetSupport": true}}}},
            "initializationOptions": {"preview": "none"},
        }),
    );
    assert_eq!(init["capabilities"]["positionEncoding"], "utf-16");
    assert!(init["capabilities"]["executeCommandProvider"]["commands"].as_array().unwrap().iter().any(|c| c == "wmpy.preview"));
    lsp.notify("initialized", json!({}));
    lsp.notify("textDocument/didOpen", json!({"textDocument": {"uri": main_uri, "languageId": "python", "version": 1, "text": MAIN}}));

    // http.get without the network permission: an error (LSP severity 1) with a fix that edits plugin.json.
    let diagnostics = lsp.diagnostics_with(&main_uri, "permission_missing");
    let problem = diagnostics.as_array().unwrap().iter().find(|d| d["code"] == "permission_missing").unwrap().clone();
    assert_eq!(problem["source"], "wmpy");
    assert_eq!(problem["severity"], 1, "{problem}");
    let actions = lsp.request(
        "textDocument/codeAction",
        json!({"textDocument": {"uri": main_uri}, "range": problem["range"], "context": {"diagnostics": [problem]}}),
    );
    let fix = actions.as_array().unwrap().iter().find(|a| a["edit"]["changes"].get(&manifest_uri).is_some()).unwrap_or_else(|| panic!("no plugin.json fix in {actions}"));
    let edits = fix["edit"]["changes"][&manifest_uri].as_array().unwrap();
    assert!(edits.iter().any(|e| e["newText"].as_str().unwrap().contains("\"network\"")), "{edits:?}");

    // Completion after `ui.` lists the widgets.
    let at = pos_after(MAIN, "        ui.");
    let list = lsp.request("textDocument/completion", json!({"textDocument": {"uri": main_uri}, "position": at, "context": {"triggerKind": 2, "triggerCharacter": "."}}));
    let items = list["items"].as_array().unwrap();
    let button = items.iter().find(|i| i["label"] == "button").unwrap_or_else(|| panic!("no button in {list}"));
    let resolved = lsp.request("completionItem/resolve", button.clone());
    assert!(resolved["documentation"]["value"].as_str().is_some_and(|d| !d.is_empty()), "{resolved}");

    // Hover over ui.button explains it.
    let at = pos_after(MAIN, "ui.butt");
    let hover = lsp.request("textDocument/hover", json!({"textDocument": {"uri": main_uri}, "position": at}));
    assert!(hover["contents"]["value"].as_str().unwrap().contains("button"), "{hover}");

    // Go to definition of `double` lands in helpers.py.
    let at = pos_after(MAIN, "str(doub");
    let def = lsp.request("textDocument/definition", json!({"textDocument": {"uri": main_uri}, "position": at}));
    assert_eq!(def["uri"], uri(&dir.join("helpers.py")), "{def}");
    assert_eq!(def["range"]["start"], json!({"line": 0, "character": 4}));

    // References of `double` reach both files.
    let refs = lsp.request("textDocument/references", json!({"textDocument": {"uri": main_uri}, "position": at, "context": {"includeDeclaration": true}}));
    let files: std::collections::HashSet<&str> = refs.as_array().unwrap().iter().map(|r| r["uri"].as_str().unwrap()).collect();
    assert_eq!(files.len(), 2, "{refs}");

    // A preview lens over render().
    let lenses = lsp.request("textDocument/codeLens", json!({"textDocument": {"uri": main_uri}}));
    let preview = lenses.as_array().unwrap().iter().find(|l| l["command"]["command"] == "wmpy.preview").unwrap_or_else(|| panic!("{lenses}"));
    assert_eq!(preview["command"]["arguments"][0], uri(&dir));

    // plugin.json completes permission names.
    let typing = MANIFEST.replace("\"permissions\": []", "\"permissions\": [\"net");
    lsp.notify("textDocument/didOpen", json!({"textDocument": {"uri": manifest_uri, "languageId": "json", "version": 1, "text": typing}}));
    let at = pos_after(&typing, "[\"net");
    let list = lsp.request("textDocument/completion", json!({"textDocument": {"uri": manifest_uri}, "position": at}));
    assert!(list["items"].as_array().unwrap().iter().any(|i| i["label"] == "network"), "{list}");

    // Symbols, folding and formatting answer.
    let symbols = lsp.request("textDocument/documentSymbol", json!({"textDocument": {"uri": main_uri}}));
    assert!(symbols.as_array().unwrap().iter().any(|s| s["name"] == "render"), "{symbols}");

    // While plugin.json is half typed, the checks read it as broken; closed, they read the file.
    let check = lsp.request("workspace/executeCommand", json!({"command": "wmpy.check", "arguments": [uri(&dir)]}));
    assert_eq!(check["errors"], 1, "{check}");
    lsp.notify("textDocument/didClose", json!({"textDocument": {"uri": manifest_uri}}));
    let check = lsp.request("workspace/executeCommand", json!({"command": "wmpy.check", "arguments": [uri(&dir)]}));
    // http with no "network" permission and a host not in "hosts": errors, as the request would be refused.
    assert_eq!(check["errors"], 2, "{check}");

    // Rename across modules: `double` in main.py renames the def in helpers.py too.
    let at = pos_after(MAIN, "str(doub");
    let edit = lsp.request("textDocument/rename", json!({"textDocument": {"uri": main_uri}, "position": at, "newName": "twice"}));
    let changes = edit["changes"].as_object().unwrap();
    assert_eq!(changes.len(), 2, "{edit}");
    assert!(changes[&uri(&dir.join("helpers.py"))].as_array().unwrap().iter().all(|e| e["newText"] == "twice"));
    // render keeps its name: the keyboard calls it.
    let at = pos_after(MAIN, "def rend");
    let refused = lsp.raw("textDocument/prepareRename", json!({"textDocument": {"uri": main_uri}, "position": at}));
    assert!(refused.get("error").is_some(), "{refused}");

    assert_eq!(lsp.request("shutdown", Value::Null), Value::Null);
    lsp.notify("exit", Value::Null);
    let status = lsp.child.wait().unwrap();
    assert!(status.success(), "{status}");
    let _ = std::fs::remove_dir_all(&dir);
}
