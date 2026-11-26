//! `wmpy new`: a plugin folder to start from, the same plugins the app's and
//! the web's editors start from (PluginTemplates.kt,
//! docs/src/store/ui/creators/ide/templates.ts), with a test, editor
//! settings and the `wm` stubs beside them.
//!
//! ```text
//! my-tool/
//! ├── plugin.json
//! ├── main.py
//! ├── test.py
//! ├── .gitignore              .wmpy/ and built *.wmplugin files
//! ├── pyrightconfig.json      stubPath: .wmpy/stubs
//! ├── .vscode/extensions.json recommends the wmpy extension
//! └── .wmpy/stubs/            the wm package for Pylance, pyright, mypy, PyCharm
//! ```

use std::io::{BufRead, IsTerminal, Write};
use std::path::{Path, PathBuf};

use serde_json::{json, Value as Json};
use wm_python_tooling::manifest as codec;

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The folder to make (it may exist, if it is empty)
    pub dir: PathBuf,
    /// What to start from
    #[arg(long, value_enum)]
    pub template: Option<TemplateId>,
    /// The plugin's name, shown in the keyboard [default: from the folder's name]
    #[arg(long)]
    pub name: Option<String>,
    /// The plugin's id [default: my.<name>]
    #[arg(long)]
    pub id: Option<String>,
    /// Who wrote it, shown on the install screen
    #[arg(long)]
    pub author: Option<String>,
    /// Write over files of the same names in a folder that is not empty
    #[arg(long)]
    pub force: bool,
    /// Print {"dir", "id", "name", "template", "files"} as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

#[derive(clap::ValueEnum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum TemplateId {
    Blank,
    TextTools,
    Cipher,
    Todo,
    KitchenSink,
    TextGenerator,
    Weather,
    TextActions,
}

/// One plugin to start from, as PluginTemplates.kt declares it.
pub struct Template {
    pub id: TemplateId,
    pub slug: &'static str,
    pub title: &'static str,
    pub description: &'static str,
    pub script: &'static str,
    pub permissions: &'static [&'static str],
    pub api: i64,
    pub hosts: &'static [&'static str],
    /// The manifest's `actions` and `settings`, as JSON.
    pub actions: &'static str,
    pub settings: &'static str,
}

/// PluginIdeState.BLANK_PLUGIN_SCRIPT.
pub const BLANK_PLUGIN_SCRIPT: &str = r#"# A plugin draws a panel in the keyboard. render() says what the panel shows,
# and a button names the function that runs when it is pressed.

from wm import plugin, ui

state = plugin.state(count=0)


def add():
    state.count += 1


def render():
    with ui.column():
        ui.label(f"Pressed {state.count} times", style="title")
        ui.button("Press me", on_click=add, primary=True, id="add")
"#;

pub const TEMPLATES: &[Template] = &[
    Template {
        id: TemplateId::Blank,
        slug: "blank",
        title: "A blank plugin",
        description: "A button that counts presses. The smallest plugin that works.",
        script: BLANK_PLUGIN_SCRIPT,
        permissions: &[],
        api: 5,
        hosts: &[],
        actions: "",
        settings: "",
    },
    Template {
        id: TemplateId::TextTools,
        slug: "text-tools",
        title: "Text tools",
        description: "Changes the case of the text typed into its box, encodes it and counts it.",
        script: include_str!("../../../app/src/main/assets/plugin-templates/text-tools.py"),
        permissions: &[],
        api: 4,
        hosts: &[],
        actions: "",
        settings: "",
    },
    Template {
        id: TemplateId::Cipher,
        slug: "cipher",
        title: "Cipher tool",
        description: "Caesar and Vigenère ciphers. The plugin the getting started guide builds.",
        script: include_str!("../../../app/src/main/assets/plugin-templates/cipher-tool.py"),
        permissions: &[],
        api: 4,
        hosts: &[],
        actions: "",
        settings: "",
    },
    Template {
        id: TemplateId::Todo,
        slug: "todo",
        title: "Todo list",
        description: "A list the plugin keeps on this device with the storage permission.",
        script: include_str!("../../../app/src/main/assets/plugin-templates/todo-list.py"),
        permissions: &["storage", "reminders"],
        api: 4,
        hosts: &[],
        actions: "",
        settings: "",
    },
    Template {
        id: TemplateId::KitchenSink,
        slug: "kitchen-sink",
        title: "Every widget",
        description: "Each widget and each event in one panel, to copy from.",
        script: include_str!("../../../app/src/main/assets/plugin-templates/ui-kitchen-sink.py"),
        permissions: &[],
        api: 4,
        hosts: &[],
        actions: "",
        settings: "",
    },
    Template {
        id: TemplateId::TextGenerator,
        slug: "text-generator",
        title: "Text generator",
        description: "Placeholder words, sentences and paragraphs to insert, written the API 5 way.",
        script: include_str!("../../../app/src/main/assets/plugin-templates/text-generator.py"),
        permissions: &[],
        api: 5,
        hosts: &[],
        actions: "",
        settings: "",
    },
    Template {
        id: TemplateId::Weather,
        slug: "weather",
        title: "Weather",
        description: "Today's forecast from Open-Meteo: two listed websites, a setting, a timer and a header button.",
        script: include_str!("../../../app/src/main/assets/plugin-templates/weather.py"),
        permissions: &["network", "storage"],
        api: 5,
        hosts: &["api.open-meteo.com", "geocoding-api.open-meteo.com"],
        actions: "",
        settings: r#"[{"key": "units", "type": "choice", "label": "Units", "default": "metric", "options": ["metric", "imperial"]}]"#,
    },
    Template {
        id: TemplateId::TextActions,
        slug: "text-actions",
        title: "Selection actions",
        description: "ROT13 and Wrap in quotes on the bar over selected text, with a panel that picks the quotes.",
        script: include_str!("../../../app/src/main/assets/plugin-templates/text-actions.py"),
        permissions: &["storage", "selection"],
        api: 4,
        hosts: &[],
        actions: r#"[{"id": "rot13", "name": "ROT13", "summary": "Shift each letter 13 places", "icon": "lock"}, {"id": "quote", "name": "Wrap in quotes", "summary": "Put quotes around the selection"}]"#,
        settings: "",
    },
];

pub fn template(id: TemplateId) -> &'static Template {
    TEMPLATES.iter().find(|t| t.id == id).unwrap_or(&TEMPLATES[0])
}

pub fn run(args: Args) -> u8 {
    let interactive = !args.json && std::io::stdin().is_terminal() && std::io::stderr().is_terminal();
    let folder = args.dir.file_name().map(|f| f.to_string_lossy().into_owned()).unwrap_or_default();
    let mut name = args.name.clone().unwrap_or_default();
    if name.trim().is_empty() {
        let default = title_of(&folder);
        name = if interactive { ask("Name", &default) } else { default };
    }
    let id_template = match args.template {
        Some(t) => t,
        None if interactive => {
            eprintln!("Templates:");
            for t in TEMPLATES {
                eprintln!("  {:<15} {}", t.slug, t.description);
            }
            loop {
                let answer = ask("Template", "blank");
                match TEMPLATES.iter().find(|t| t.slug == answer.trim()) {
                    Some(t) => break t.id,
                    None => eprintln!("no template \"{answer}\""),
                }
            }
        }
        None => TemplateId::Blank,
    };
    let author = match &args.author {
        Some(a) => a.clone(),
        None if interactive => ask("Author (shown on the install screen)", ""),
        None => String::new(),
    };
    let t = template(id_template);
    let id = args.id.clone().unwrap_or_else(|| codec::unique_id(&name, &[]));
    if !codec::is_valid_id(&id) {
        eprintln!("error: \"{id}\" is not a plugin id: 3 to 64 of a-z, 0-9, dots, dashes and underscores, starting with a letter or digit");
        return 2;
    }
    let name = codec::display_text(&name, codec::MAX_NAME);
    if name.is_empty() {
        eprintln!("error: the plugin needs a name: pass --name");
        return 2;
    }
    match make(&args.dir, t, &name, &id, &author, args.force) {
        Ok(files) => {
            if args.json {
                println!("{}", json!({"dir": args.dir.display().to_string(), "id": id, "name": name, "template": t.slug, "files": files}));
            } else {
                eprintln!("made {} ({}, {}) in {}", name, t.title.to_lowercase(), id, args.dir.display());
                let cd = args.dir.display().to_string();
                eprintln!("next:\n  cd {}\n  wmpy test      run test.py\n  wmpy preview   see the panel in the browser\n  wmpy pack      build the .wmplugin", shell_quote(&cd));
            }
            0
        }
        Err(e) => {
            eprintln!("error: {e}");
            2
        }
    }
}

/// Writes the plugin folder; the files written, relative to [dir].
pub fn make(dir: &Path, t: &Template, name: &str, id: &str, author: &str, force: bool) -> Result<Vec<String>, String> {
    let files = contents(t, name, id, author);
    if dir.exists() {
        if !dir.is_dir() {
            return Err(format!("{} is a file, not a folder", dir.display()));
        }
        let busy = std::fs::read_dir(dir).map_err(|e| e.to_string())?.next().is_some();
        if busy && !force {
            let clashes: Vec<&str> = files.iter().map(|(p, _)| p.as_str()).filter(|p| dir.join(p).exists()).collect();
            let what = if clashes.is_empty() { "it is not empty".to_string() } else { format!("it already has {}", clashes.join(", ")) };
            return Err(format!("{} not made: {what}; pass --force to write the plugin's files into it", dir.display()));
        }
    }
    let mut written = Vec::new();
    for (path, text) in &files {
        let target = dir.join(path);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent).map_err(|e| format!("cannot make {}: {e}", parent.display()))?;
        }
        std::fs::write(&target, text).map_err(|e| format!("cannot write {}: {e}", target.display()))?;
        written.push(path.clone());
    }
    let stubs = crate::stubs::write(&dir.join(".wmpy/stubs"), t.api).map_err(|e| format!("cannot write the stubs: {e}"))?;
    written.extend(stubs.iter().filter_map(|p| p.strip_prefix(dir).ok()).map(|p| p.to_string_lossy().replace('\\', "/")));
    Ok(written)
}

/// Every file of a new plugin but the stubs, `(path, text)`.
pub fn contents(t: &Template, name: &str, id: &str, author: &str) -> Vec<(String, String)> {
    let mut manifest = serde_json::Map::new();
    manifest.insert("format".into(), json!(codec::FORMAT));
    manifest.insert("id".into(), json!(id));
    manifest.insert("name".into(), json!(name));
    manifest.insert("pluginVersion".into(), json!("0.1.0"));
    if !author.trim().is_empty() {
        manifest.insert("author".into(), json!(author.trim()));
    }
    manifest.insert("description".into(), json!(""));
    manifest.insert("apiVersion".into(), json!(t.api));
    manifest.insert("permissions".into(), json!(t.permissions));
    if !t.hosts.is_empty() {
        manifest.insert("hosts".into(), json!(t.hosts));
    }
    for (key, raw) in [("actions", t.actions), ("settings", t.settings)] {
        if let Ok(v) = serde_json::from_str::<Json>(raw) {
            manifest.insert(key.into(), v);
        }
    }
    let manifest = serde_json::to_string_pretty(&Json::Object(manifest)).unwrap_or_default() + "\n";
    let pyright = serde_json::to_string_pretty(&json!({
        "stubPath": ".wmpy/stubs",
        "exclude": [".wmpy"],
        "typeCheckingMode": "basic",
    }))
    .unwrap_or_default()
        + "\n";
    let extensions = serde_json::to_string_pretty(&json!({"recommendations": ["wasimaster.wmpy"]})).unwrap_or_default() + "\n";
    vec![
        ("plugin.json".into(), manifest),
        ("main.py".into(), t.script.to_string()),
        ("test.py".into(), test_py(t, name)),
        (".gitignore".into(), ".wmpy/\n*.wmplugin\n".into()),
        ("pyrightconfig.json".into(), pyright),
        (".vscode/extensions.json".into(), extensions),
    ]
}

fn test_py(t: &Template, name: &str) -> String {
    let header = format!(
        r#""""Tests for {name}. Run them with `wmpy test`.

Each def test_...() runs on its own, and fails by raising, so a plain
assert works. plugin_test.session() runs the plugin the way the keyboard
does: https://wmkeyboard.pages.dev/plugins/guides/testing/
"""

import json
"#
    );
    if t.id == TemplateId::Weather {
        // The panel looks the weather up when it opens: answer both websites.
        return header
            + r#"
GEOCODE = {"results": [{"name": "Dhaka", "country": "Bangladesh", "latitude": 23.8, "longitude": 90.4}]}
FORECAST = {
    "current": {"temperature_2m": 30.4, "weather_code": 0},
    "daily": {"time": ["2026-11-26"], "temperature_2m_max": [31.0], "temperature_2m_min": [21.5], "weather_code": [3]},
}
MOCK = {
    "http.request": [
        {"when": {"url": "https://geocoding-api.open-meteo.com/*"}, "reply": {"status": 200, "text": json.dumps(GEOCODE), "headers": {}}},
        {"when": {"url": "https://api.open-meteo.com/*"}, "reply": {"status": 200, "text": json.dumps(FORECAST), "headers": {}}},
    ],
}


def test_it_loads_and_draws():
    s = plugin_test.session(mock=MOCK, settings={"units": "metric"})
    r = s["load"]()
    assert r["ok"], r["error"]
    assert r["ui"], "the panel drew nothing"


def test_it_shows_the_forecast():
    s = plugin_test.session(mock=MOCK, settings={"units": "metric"})
    r = s["load"]()
    text = json.dumps(r["ui"])
    assert "Dhaka" in text, text
    assert "30°" in text, text
"#;
    }
    let mut s = header
        + r#"

def test_it_loads_and_draws():
    s = plugin_test.session()
    r = s["load"]()
    assert r["ok"], r["error"]
    assert r["ui"], "the panel drew nothing"
"#;
    match t.id {
        TemplateId::Blank => s.push_str(
            r#"

def test_the_button_counts():
    s = plugin_test.session()
    s["load"]()
    r = s["event"]({"type": "click", "id": "add"})
    assert r["ok"], r["error"]
    assert "Pressed 1 times" in json.dumps(r["ui"]), r["ui"]
"#,
        ),
        TemplateId::TextActions => s.push_str(
            r#"

def test_rot13():
    s = plugin_test.session()
    s["load"]()
    r = s["transform"]("Hello", "rot13")
    assert r["ok"], r["error"]
    assert r["value"] == "Uryyb", r
"#,
        ),
        _ => s.push_str(
            r#"

def test_it_draws_again():
    s = plugin_test.session()
    s["load"]()
    r = s["render"]()
    assert r["ok"], r["error"]
    assert json.dumps(r["ui"]), r
"#,
        ),
    }
    s
}

/// "my-tool" as "My tool".
fn title_of(folder: &str) -> String {
    let words = folder.replace(['-', '_', '.'], " ");
    let words = words.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut chars = words.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().chain(chars).collect(),
        None => "My plugin".into(),
    }
}

fn ask(question: &str, default: &str) -> String {
    if default.is_empty() {
        eprint!("{question}: ");
    } else {
        eprint!("{question} [{default}]: ");
    }
    let _ = std::io::stderr().flush();
    let mut line = String::new();
    let _ = std::io::stdin().lock().read_line(&mut line);
    let line = line.trim();
    if line.is_empty() { default.to_string() } else { line.to_string() }
}

fn shell_quote(s: &str) -> String {
    if s.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '.' | '-' | '_')) { s.to_string() } else { format!("'{}'", s.replace('\'', "'\\''")) }
}
