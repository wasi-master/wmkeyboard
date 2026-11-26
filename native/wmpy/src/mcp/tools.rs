//! The MCP server's tools: what an agent needs to write a plugin, run it,
//! test it, fix it and pack it, each with a JSON Schema for its arguments.

use std::path::{Path, PathBuf};

use serde_json::{json, Value};

use super::docs;
use super::permissions;
use super::sessions::{Live, Sessions};
use crate::lsp::commands;

/// What a tool call gave: the structured result, and whether the tool could
/// not do the work (bad arguments, a missing folder).
pub struct Outcome {
    pub value: Value,
    pub error: bool,
}

fn ok(value: Value) -> Outcome {
    Outcome { value, error: false }
}

fn fail(message: impl Into<String>) -> Outcome {
    Outcome { value: json!({"error": message.into()}), error: true }
}

const DIR: &str = "The plugin folder: the folder that holds plugin.json. Absolute, or relative to the folder the MCP server runs in.";
const SESSION: &str = "The id session_start returned.";
const EVENT: &str = "One event, as the keyboard sends it. A widget's id is its type and text, such as \"button:Look up\" or \"input:Word\", unless the code gave it key=. Shapes: {\"type\": \"click\", \"id\": \"button:Flip\"}, {\"type\": \"change\", \"id\": \"input:Word\", \"value\": \"rain\"}, {\"type\": \"submit\", \"id\": \"input:Word\", \"value\": \"rain\"}, {\"type\": \"keyword\", \"id\": \"define\", \"value\": \"rain\"}, {\"type\": \"command\", \"id\": \"clear\"}, and {\"type\": \"result\", \"token\": \"http:1\", \"value\": ...} to answer a then= call by hand.";
const MOCK: &str = "Answers to the plugin's host calls, by op name: {\"http.request\": {\"status\": 200, \"text\": \"...\", \"headers\": {}}, \"ai.ask\": {\"text\": \"...\"}, \"ui.toast\": null}. A value that is only {\"error\": {\"kind\": \"TimeoutError\", \"message\": \"...\"}} raises that error. An op's value may be a list of routes [{\"when\": {\"method\": \"GET\", \"url\": \"https://example.com/api/*\"}, \"reply\": ...}], the first whose `when` fits the call answering. A call with no answer raises NotImplementedError in the plugin; `calls` in the result shows every call made, to see what to add.";

fn dir_prop() -> Value {
    json!({"type": "string", "description": DIR})
}

fn diag_schema() -> Value {
    json!({
        "type": "object",
        "properties": {
            "severity": {"type": "string", "enum": ["error", "warning", "info"]},
            "code": {"type": ["string", "null"]},
            "message": {"type": "string"},
            "file": {"type": ["string", "null"]},
            "line": {"type": ["integer", "null"], "description": "1-based"},
            "col": {"type": ["integer", "null"], "description": "1-based, in characters"},
            "endCol": {"type": ["integer", "null"]},
            "help": {"type": ["string", "null"], "description": "The fix, when there is one obvious one"},
        },
        "required": ["severity", "message"],
    })
}

/// Every tool, in a fixed order.
pub fn list() -> Vec<Value> {
    let read_only = json!({"readOnlyHint": true, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false});
    let runs = json!({"readOnlyHint": true, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false});
    let writes = json!({"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false});
    let session_ref = json!({"type": "object", "properties": {"session": {"type": "string", "description": SESSION}}, "required": ["session"], "additionalProperties": false});
    vec![
        json!({
            "name": "new_plugin",
            "title": "Make a plugin",
            "description": "Make a new plugin folder from a template with `wmpy new`: plugin.json and main.py ready to run. Start here for a new plugin, then edit main.py and plugin.json, and run check_plugin. The folder must not exist yet, or be empty. Returns the folder, the id and the files written.",
            "inputSchema": {"type": "object", "properties": {
                "dir": {"type": "string", "description": "The new plugin's folder."},
                "name": {"type": "string", "description": "The plugin's name as users see it, up to 40 characters."},
                "template": {"type": "string", "description": "What to start from: blank (a button that counts presses); weather (Open-Meteo: hosts, a setting, a timer, a header button); todo (a list kept with the storage permission); text-tools (changes the text typed into a box); text-actions (selection actions over selected text); cipher (Caesar and Vigenère); text-generator (placeholder text to insert); kitchen-sink (every widget and event).", "enum": ["blank", "weather", "todo", "text-tools", "text-actions", "cipher", "text-generator", "kitchen-sink"], "default": "blank"},
                "id": {"type": "string", "description": "The plugin's id, such as com.yourname.weather: 3 to 64 characters of a-z 0-9 . _ -. Made from the name when left out."},
            }, "required": ["dir", "name"], "additionalProperties": false},
            "annotations": writes,
        }),
        json!({
            "name": "check_plugin",
            "title": "Check a plugin",
            "description": "Check a plugin without running it, as the editors do: Python the keyboard's interpreter refuses, undefined or misspelt names, names the wm package does not have, a module used without its permission in plugin.json, an address on a host plugin.json does not list, a provider or action declared but not answered, widget arguments and values that do not exist, and plugin.json itself (unknown permissions, missing fields, API 5 permissions on API 4). Each problem has file, 1-based line and column, a code, and `help` naming the fix when there is one. Run it after every edit; ok is true when there are no errors.",
            "inputSchema": {"type": "object", "properties": {"dir": dir_prop()}, "required": ["dir"], "additionalProperties": false},
            "outputSchema": {"type": "object", "properties": {
                "ok": {"type": "boolean"}, "errors": {"type": "integer"}, "warnings": {"type": "integer"},
                "problems": {"type": "array", "items": diag_schema()},
            }, "required": ["ok", "errors", "warnings", "problems"]},
            "annotations": read_only,
        }),
        json!({
            "name": "run_tests",
            "title": "Run a plugin's tests",
            "description": "Run the plugin's tests (test.py, tests/test_*.py) with `wmpy test`, on the interpreter the keyboard uses, host calls answered by the mocks the tests pass to plugin_test.session(mock=...). Returns ok, passed, failed and each test with its printed output and, for a failure, an error with file, line and traceback. A test fails by raising, so plain assert works; read the wmpy://docs/testing resource (or read_docs topic testing) before writing tests.",
            "inputSchema": {"type": "object", "properties": {
                "dir": dir_prop(),
                "filter": {"type": "string", "description": "Run only the tests whose name contains this."},
            }, "required": ["dir"], "additionalProperties": false},
            "annotations": runs,
        }),
        json!({
            "name": "render_plugin",
            "title": "Draw a plugin's panel",
            "description": "Load the plugin as the keyboard does and return what its panel draws (`ui`, the widget tree), after sending `events` in order. Use it to see a panel once, or to try a sequence of taps; use session_start to go back and forth. The result has ok, ui, error (with line and traceback) and host (timers, header buttons, waiting then= calls) for the last step, a line per event, every host call made, and what the plugin saved.",
            "inputSchema": {"type": "object", "properties": {
                "dir": dir_prop(),
                "events": {"type": "array", "items": {"type": "object"}, "description": EVENT},
                "mock": {"type": "object", "description": MOCK},
                "mock_file": {"type": "string", "description": "A JSON file inside the plugin folder to read the mock from, such as tests/mock.json, instead of `mock`."},
                "settings": {"type": "object", "description": "Values of the plugin's settings, by key, as the user set them."},
                "settle": {"type": "boolean", "description": "After loading and after each event, answer every waiting then= call from the mock, and the calls those answers start.", "default": true},
            }, "required": ["dir"], "additionalProperties": false},
            "annotations": runs,
        }),
        json!({
            "name": "session_start",
            "title": "Start a plugin session",
            "description": "Load the plugin and keep it running, so you can send events one at a time and look at the panel, storage and host calls between them. Returns `session`, an id for the other session_* tools, and the first draw. A session keeps the code it loaded: start a new one after editing. At most 16 live at once (the oldest stops first); all stop when the MCP server exits.",
            "inputSchema": {"type": "object", "properties": {
                "dir": dir_prop(),
                "mock": {"type": "object", "description": MOCK},
                "mock_file": {"type": "string", "description": "A JSON file inside the plugin folder to read the mock from, instead of `mock`."},
                "settings": {"type": "object", "description": "Values of the plugin's settings, by key."},
            }, "required": ["dir"], "additionalProperties": false},
            "outputSchema": {"type": "object", "properties": {"session": {"type": "string"}, "result": {"type": "object"}}, "required": ["session", "result"]},
            "annotations": runs,
        }),
        json!({
            "name": "session_event",
            "title": "Send an event",
            "description": "Send one event to a session's plugin, as a tap or a typed value in its panel, and get the new draw: ok, ui, error, host. `host.pending` counts then= calls waiting; settle answers them from the mock.",
            "inputSchema": {"type": "object", "properties": {
                "session": {"type": "string", "description": SESSION},
                "event": {"type": "object", "description": EVENT},
                "settle": {"type": "boolean", "description": "Then answer every waiting then= call from the mock.", "default": false},
            }, "required": ["session", "event"], "additionalProperties": false},
            "annotations": runs,
        }),
        json!({
            "name": "session_render",
            "title": "Draw again",
            "description": "Call the session's render() again and return the panel: ok, ui, error, host.",
            "inputSchema": session_ref, "annotations": runs,
        }),
        json!({
            "name": "session_settle",
            "title": "Answer waiting calls",
            "description": "Answer every then= call the session's plugin is waiting on, from its mock, round after round as answers start more calls. Returns the last result, or the first that failed.",
            "inputSchema": session_ref, "annotations": runs,
        }),
        json!({
            "name": "session_transform",
            "title": "Run a selection action",
            "description": "Run one of the plugin's selection actions (plugin.json \"actions\", answered by @plugin.action) on `text`, as when the user selects text and taps its chip. Returns the result with `value`, the text that would replace the selection.",
            "inputSchema": {"type": "object", "properties": {
                "session": {"type": "string", "description": SESSION},
                "text": {"type": "string", "description": "The selected text."},
                "action": {"type": "string", "description": "The action's id in plugin.json."},
            }, "required": ["session", "text", "action"], "additionalProperties": false},
            "annotations": runs,
        }),
        json!({
            "name": "session_storage",
            "title": "Read saved data",
            "description": "What the session's plugin has saved with wm.storage (and plugin.state(persist=True)), as a dict.",
            "inputSchema": session_ref, "annotations": read_only,
        }),
        json!({
            "name": "session_calls",
            "title": "List host calls",
            "description": "Every call the session's plugin made to the keyboard (http.request, ai.ask, ui.toast, ...), as {\"op\", \"args\"} in order, from index `since` on. For a request, args holds method, url, params, headers and the body as text.",
            "inputSchema": {"type": "object", "properties": {
                "session": {"type": "string", "description": SESSION},
                "since": {"type": "integer", "minimum": 0, "description": "Skip this many calls from the start.", "default": 0},
            }, "required": ["session"], "additionalProperties": false},
            "annotations": read_only,
        }),
        json!({
            "name": "session_stop",
            "title": "Stop a session",
            "description": "Stop a session session_start made and free what it holds. All sessions also stop when the MCP server exits.",
            "inputSchema": session_ref,
            "annotations": {"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false},
        }),
        json!({
            "name": "pack_plugin",
            "title": "Pack a plugin",
            "description": "Build the .wmplugin file the keyboard installs, with `wmpy pack`. Returns its path, size and sha256. Check and test first: pack refuses a plugin whose plugin.json the keyboard would refuse.",
            "inputSchema": {"type": "object", "properties": {"dir": dir_prop()}, "required": ["dir"], "additionalProperties": false},
            "annotations": writes,
        }),
        json!({
            "name": "pip_check",
            "title": "Check a PyPI package",
            "description": "Check whether a package from PyPI can run inside a plugin with `wmpy pip check`: pure Python, only modules plugins have, nothing the interpreter refuses. `spec` is a requirement such as \"python-slugify\" or \"toml==0.10.2\". Returns a verdict per package with its problems. Downloads from PyPI; changes nothing in the plugin.",
            "inputSchema": {"type": "object", "properties": {
                "spec": {"type": "string", "description": "A requirement: a name, optionally with a version."},
            }, "required": ["spec"], "additionalProperties": false},
            "annotations": {"readOnlyHint": true, "destructiveHint": false, "idempotentHint": true, "openWorldHint": true},
        }),
        json!({
            "name": "pip_install",
            "title": "Vendor a PyPI package",
            "description": "Copy a pure-Python package from PyPI into the plugin with `wmpy pip install`, recorded in wmpy.lock, so `import` finds it on the phone. Run pip_check first. Downloads from PyPI.",
            "inputSchema": {"type": "object", "properties": {
                "dir": dir_prop(),
                "spec": {"type": "string", "description": "A requirement: a name, optionally with a version."},
            }, "required": ["dir", "spec"], "additionalProperties": false},
            "annotations": {"readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": true},
        }),
        json!({
            "name": "api_lookup",
            "title": "Look up a wm API name",
            "description": "The signature, doc and permission of one name plugins can use: wm.http.get (or http.get), ui.button, plugin.state, storage.get, a builtin such as len, a method such as str.upper. For a module it also lists its members. When nothing has that name, `suggestions` lists near ones. This is the model the editors complete from, so it is exactly what the keyboard has.",
            "inputSchema": {"type": "object", "properties": {"name": {"type": "string", "description": "The name, with or without wm."}}, "required": ["name"], "additionalProperties": false},
            "outputSchema": {"type": "object", "properties": {
                "found": {"type": "boolean"}, "entry": {"type": ["object", "null"]},
                "children": {"type": "array", "items": {"type": "object"}},
                "suggestions": {"type": "array", "items": {"type": "string"}},
            }, "required": ["found", "entry", "children"]},
            "annotations": read_only,
        }),
        json!({
            "name": "api_search",
            "title": "Search the wm API",
            "description": "Search every name plugins can use by words, such as \"vibrate\", \"clipboard\", \"date picker\" or \"timer\". Returns entries best first, each with path, kind, signature, doc and permission. Use it to find out whether the keyboard can do something before writing code for it.",
            "inputSchema": {"type": "object", "properties": {
                "query": {"type": "string"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 100, "default": 20},
            }, "required": ["query"], "additionalProperties": false},
            "outputSchema": {"type": "object", "properties": {"entries": {"type": "array", "items": {"type": "object"}}}, "required": ["entries"]},
            "annotations": read_only,
        }),
        json!({
            "name": "list_permissions",
            "title": "List permissions",
            "description": "Every permission plugin.json may declare, with its tier and what it gives. Listed ones apply on install. Risky ones (network, network.local, ai, field.read, clipboard.read, location, files, calendar, todo) stay off until the user turns them on, so a call before that raises PermissionError: catch it and tell the user. All but storage, reminders and selection need \"apiVersion\": 5. Declare only what the plugin uses; a permission the keyboard does not know refuses the install.",
            "inputSchema": {"type": "object", "properties": {"name": {"type": "string", "description": "Only this permission."}}, "additionalProperties": false},
            "outputSchema": {"type": "object", "properties": {"permissions": {"type": "array", "items": {"type": "object"}}}, "required": ["permissions"]},
            "annotations": read_only,
        }),
        json!({
            "name": "read_docs",
            "title": "Read the plugin docs",
            "description": "Read a page of the plugin docs as Markdown (the same pages as the server's resources): api (the whole wm package and plugin.json), api-index (every name, one line each), plugin-json, permissions, testing (test.py, mocks, events), help (error messages), diagnostics (every check code). Read api or api-index before writing a plugin, and testing before writing tests.",
            "inputSchema": {"type": "object", "properties": {
                "topic": {"type": "string", "enum": ["api", "api-index", "plugin-json", "permissions", "testing", "help", "diagnostics"]},
            }, "required": ["topic"], "additionalProperties": false},
            "annotations": read_only,
        }),
        json!({
            "name": "format_file",
            "title": "Format a Python file",
            "description": "Format one .py file of the plugin the way the editors do: four-space indentation, spacing around operators and after commas, blank lines between functions. Writes the file unless write is false, and returns whether it changed and the formatted text when not written.",
            "inputSchema": {"type": "object", "properties": {
                "dir": dir_prop(),
                "file": {"type": "string", "description": "The file inside the plugin folder, such as main.py."},
                "write": {"type": "boolean", "default": true},
            }, "required": ["dir", "file"], "additionalProperties": false},
            "annotations": writes,
        }),
        json!({
            "name": "write_stubs",
            "title": "Write wm type stubs",
            "description": "Write type stubs of the wm package with `wmpy stubs`, into .wmpy/stubs in the plugin folder, so other editors and type checkers (Pyright, mypy) know its names. Not needed for this server or the wmpy language server.",
            "inputSchema": {"type": "object", "properties": {"dir": dir_prop()}, "required": ["dir"], "additionalProperties": false},
            "annotations": writes,
        }),
    ]
}

/// Server state the tools share.
pub struct Tools {
    pub exe: PathBuf,
    pub sessions: Sessions,
}

fn str_arg<'a>(args: &'a Value, key: &str) -> Option<&'a str> {
    args.get(key).and_then(Value::as_str).filter(|s| !s.is_empty())
}

/// A plugin folder argument, checked: it must hold plugin.json.
fn plugin_dir(args: &Value) -> Result<PathBuf, String> {
    let given = str_arg(args, "dir").ok_or("`dir` is required: the plugin folder")?;
    let dir = PathBuf::from(given);
    if !dir.is_dir() {
        return Err(format!("{} is not a folder", dir.display()));
    }
    if !dir.join("plugin.json").is_file() {
        return Err(format!("{} has no plugin.json: is it the plugin folder? (new_plugin makes one)", dir.display()));
    }
    Ok(dir)
}

fn mock_of(args: &Value, dir: &Path) -> Result<Value, String> {
    if let Some(file) = str_arg(args, "mock_file") {
        let path = crate::project::inside(dir, file)?;
        let text = std::fs::read_to_string(&path).map_err(|e| format!("{file}: {e}"))?;
        return serde_json::from_str(&text).map_err(|e| format!("{file}: {e}"));
    }
    Ok(args.get("mock").cloned().unwrap_or(Value::Null))
}

/// A child command's result: its JSON, or what it printed.
fn child(out: Result<commands::Output, String>, what: &str) -> Outcome {
    match out {
        Err(e) => fail(e),
        Ok(out) => match commands::json_of(&out) {
            Some(v) => Outcome { error: out.code == 2, value: v },
            None if out.code == 0 => ok(json!({"ok": true, "output": format!("{}{}", out.stdout, out.stderr).trim()})),
            None => fail(format!("{what} failed: {}", commands::first_words(&out))),
        },
    }
}

impl Tools {
    /// The outcome of tool [name], or None when there is no such tool.
    pub fn call(&mut self, name: &str, args: &Value) -> Option<Outcome> {
        if !list().iter().any(|t| t["name"] == name) {
            return None;
        }
        Some(self.run(name, args).unwrap_or_else(fail))
    }

    fn run(&mut self, name: &str, args: &Value) -> Result<Outcome, String> {
        let exe = self.exe.clone();
        {
            Ok(match name {
                "new_plugin" => {
                    let dir = str_arg(args, "dir").ok_or("`dir` is required")?;
                    let name = str_arg(args, "name").ok_or("`name` is required")?;
                    let template = str_arg(args, "template").unwrap_or("blank");
                    let mut argv = vec!["new", dir, "--template", template, "--name", name, "--json"];
                    if let Some(id) = str_arg(args, "id") {
                        argv.extend(["--id", id]);
                    }
                    child(commands::run(&exe, &argv, None), "wmpy new")
                }
                "check_plugin" => ok(check(&exe, &plugin_dir(args)?)),
                "run_tests" => {
                    let dir = plugin_dir(args)?;
                    ok(commands::test(&exe, &dir, str_arg(args, "filter"))?)
                }
                "render_plugin" => {
                    let dir = plugin_dir(args)?;
                    ok(render(&dir, mock_of(args, &dir)?, args.get("settings").cloned().unwrap_or(Value::Null), args.get("events"), args.get("settle").and_then(Value::as_bool).unwrap_or(true))?)
                }
                "session_start" => {
                    let dir = plugin_dir(args)?;
                    let (live, first) = Live::start(&dir, mock_of(args, &dir)?, args.get("settings").cloned().unwrap_or(Value::Null))?;
                    let id = self.sessions.add(live);
                    ok(json!({"session": id, "result": first}))
                }
                "session_event" => {
                    let event = args.get("event").filter(|e| e.is_object()).ok_or("`event` is required: an object such as {\"type\": \"click\", \"id\": \"button:Flip\"}")?;
                    let live = self.sessions.get(str_arg(args, "session").unwrap_or(""))?;
                    let mut result = live.event(event);
                    if args.get("settle").and_then(Value::as_bool).unwrap_or(false) {
                        if let Some(r) = live.settle()? {
                            result = r;
                        }
                    }
                    ok(result)
                }
                "session_render" => ok(self.sessions.get(str_arg(args, "session").unwrap_or(""))?.render()),
                "session_settle" => {
                    let live = self.sessions.get(str_arg(args, "session").unwrap_or(""))?;
                    match live.settle()? {
                        Some(r) => ok(r),
                        None => ok(live.render()),
                    }
                }
                "session_transform" => {
                    let text = args.get("text").and_then(Value::as_str).ok_or("`text` is required")?;
                    let action = str_arg(args, "action").ok_or("`action` is required: the action's id")?;
                    ok(self.sessions.get(str_arg(args, "session").unwrap_or(""))?.transform(text, action))
                }
                "session_storage" => ok(json!({"storage": self.sessions.get(str_arg(args, "session").unwrap_or(""))?.storage()})),
                "session_calls" => {
                    let since = args.get("since").and_then(Value::as_u64).unwrap_or(0) as usize;
                    let live = self.sessions.get(str_arg(args, "session").unwrap_or(""))?;
                    ok(json!({"calls": live.calls(since), "total": live.call_count(), "waiting": live.waiting()}))
                }
                "session_stop" => {
                    let id = str_arg(args, "session").unwrap_or("");
                    if self.sessions.stop(id) {
                        ok(json!({"stopped": id}))
                    } else {
                        fail(format!("no session '{id}'"))
                    }
                }
                "pack_plugin" => {
                    let dir = plugin_dir(args)?;
                    child(commands::run(&exe, &["pack", &dir.to_string_lossy(), "--json"], None), "wmpy pack")
                }
                "pip_check" => {
                    let spec = str_arg(args, "spec").ok_or("`spec` is required")?;
                    child(commands::run(&exe, &["pip", "check", spec, "--json"], None), "wmpy pip check")
                }
                "pip_install" => {
                    let dir = plugin_dir(args)?;
                    let spec = str_arg(args, "spec").ok_or("`spec` is required")?;
                    child(commands::run(&exe, &["pip", "install", spec, "--dir", &dir.to_string_lossy(), "--json"], None), "wmpy pip install")
                }
                "api_lookup" => ok(docs::lookup(str_arg(args, "name").ok_or("`name` is required")?)),
                "api_search" => {
                    let query = str_arg(args, "query").ok_or("`query` is required")?;
                    let limit = args.get("limit").and_then(Value::as_u64).unwrap_or(20).clamp(1, 100) as usize;
                    ok(json!({"entries": docs::search(query, limit)}))
                }
                "list_permissions" => {
                    let list: Vec<Value> = match str_arg(args, "name") {
                        Some(n) => match permissions::find(n) {
                            Some(p) => vec![permissions::json(&p)],
                            None => return Ok(fail(format!("no permission '{n}'; list_permissions with no name lists them all"))),
                        },
                        None => permissions::all().iter().map(permissions::json).collect(),
                    };
                    ok(json!({"permissions": list}))
                }
                "read_docs" => {
                    let topic = str_arg(args, "topic").ok_or("`topic` is required")?;
                    match docs::read(&format!("wmpy://docs/{topic}")) {
                        Some(text) => ok(json!({"topic": topic, "markdown": text})),
                        None => fail(format!("no docs topic '{topic}'")),
                    }
                }
                "format_file" => {
                    let dir = plugin_dir(args)?;
                    let file = str_arg(args, "file").ok_or("`file` is required")?;
                    let path = crate::project::inside(&dir, file)?;
                    let text = std::fs::read_to_string(&path).map_err(|e| format!("{file}: {e}"))?;
                    let formatted = wm_python_tooling::format::format(&text);
                    let changed = formatted != text;
                    let write = args.get("write").and_then(Value::as_bool).unwrap_or(true);
                    if write && changed {
                        std::fs::write(&path, &formatted).map_err(|e| format!("{file}: {e}"))?;
                        ok(json!({"file": file, "changed": true, "written": true}))
                    } else if write {
                        ok(json!({"file": file, "changed": false, "written": false}))
                    } else {
                        ok(json!({"file": file, "changed": changed, "written": false, "text": formatted}))
                    }
                }
                "write_stubs" => {
                    let dir = plugin_dir(args)?;
                    child(commands::run(&exe, &["stubs", &dir.to_string_lossy()], None), "wmpy stubs")
                }
                other => return Err(format!("no tool {other}")),
            })
        }
    }
}

/// `wmpy check --json`, or the language server's own checks when that
/// command gives no JSON. Always `{"ok", "errors", "warnings", "problems"}`.
pub fn check(exe: &Path, dir: &Path) -> Value {
    let from_cli = commands::run(exe, &["check", &dir.to_string_lossy(), "--json"], None).ok().and_then(|o| commands::json_of(&o));
    let own = crate::lsp::plugin::check_folder(dir);
    let problems: Vec<Value> = match from_cli.as_ref().and_then(|v| v.get("problems")).and_then(Value::as_array) {
        Some(found) => {
            // The language server knows each problem's fix; say it where the command did not.
            let key = |p: &Value| format!("{}|{}|{}|{}", p["file"], p["line"], p["col"], p["code"]);
            let helps: std::collections::HashMap<_, _> = own.iter().filter(|p| p["help"].is_string()).map(|p| (key(p), p["help"].clone())).collect();
            found
                .iter()
                .map(|p| {
                    let mut p = p.clone();
                    if !p["help"].is_string() {
                        if let Some(h) = helps.get(&key(&p)) {
                            p["help"] = h.clone();
                        }
                    }
                    p
                })
                .collect()
        }
        None => own,
    };
    let count = |s: &str| problems.iter().filter(|p| p["severity"] == s).count();
    let (errors, warnings) = (count("error"), count("warning"));
    json!({"ok": errors == 0, "errors": errors, "warnings": warnings, "problems": problems})
}

/// Load, send [events], and gather what happened.
fn render(dir: &Path, mock: Value, settings: Value, events: Option<&Value>, settle: bool) -> Result<Value, String> {
    let (mut live, first) = Live::start(dir, mock, settings)?;
    let mut last = first;
    let mut steps = vec![json!({"step": "load", "ok": last["ok"], "error": last.get("error")})];
    if settle {
        if let Some(r) = live.settle()? {
            last = r;
        }
    }
    for event in events.and_then(Value::as_array).into_iter().flatten() {
        if !event.is_object() {
            return Err(format!("an event is an object such as {{\"type\": \"click\", \"id\": \"button:Flip\"}}, not {event}"));
        }
        last = live.event(event);
        if settle {
            if let Some(r) = live.settle()? {
                last = r;
            }
        }
        steps.push(json!({"step": event, "ok": last["ok"], "error": last.get("error")}));
    }
    Ok(json!({"result": last, "steps": steps, "calls": live.calls(0), "storage": live.storage()}))
}
