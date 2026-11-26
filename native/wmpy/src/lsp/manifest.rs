//! plugin.json in the editor: its problems, completion of its keys and
//! permission names, hovers, and the edits a Python fix makes to it.

use serde_json::Value;
use wm_python_tooling::diagnostics::{Fix, Severity};
use wm_python_tooling::keyboard;
use wm_python_tooling::manifest as codec;

use super::json::{self, Context, Edit, Kind, Node, Seg};
use super::plugin::Plugin;
use super::text::byte_of_utf16;
use crate::mcp::permissions;

/// A field: its name, what it is for, and the value a completion inserts
/// (a snippet, `$1` where the caret goes).
pub struct Field {
    pub name: &'static str,
    pub doc: &'static str,
    pub value: &'static str,
}

const fn f(name: &'static str, doc: &'static str, value: &'static str) -> Field {
    Field { name, doc, value }
}

pub const TOP: &[Field] = &[
    f("format", "Always \"wmkeyboard-plugin\". Required.", "\"wmkeyboard-plugin\""),
    f("id", "Unique. 3 to 64 characters of a-z 0-9 . _ -, starting with a letter or digit. Reverse domain by custom: com.yourname.tool. Required.", "\"$1\""),
    f("name", "Shown everywhere. Up to 40 characters. Required.", "\"$1\""),
    f("pluginVersion", "Your version, such as \"1.2.0\". Required.", "\"${1:1.0.0}\""),
    f("author", "Shown on the install screen. Up to 64 characters.", "\"$1\""),
    f("description", "Shown on the install screen. Up to 280 characters.", "\"$1\""),
    f("apiVersion", "5 for the wm package. Required.", "5"),
    f("language", "\"python\".", "\"python\""),
    f("entry", "The script to run. main.py by default.", "\"${1:main.py}\""),
    f("permissions", "What the plugin may do beyond drawing its panel. Risky ones stay off until the user turns them on.", "[$1]"),
    f("hosts", "Websites http may reach, up to 16. Host names only: no scheme, port or path. \"*.example.org\" covers its subdomains. Required with network, unless a host setting names the server.", "[\"$1\"]"),
    f("tool", "Where the plugin opens from: icon, color, tile, float, keyword, chip, height, fullBleed.", "{\"icon\": \"$1\"}"),
    f("settings", "Rows on the plugin's settings page, up to 24.", "[{\"key\": \"$1\", \"type\": \"${2:text}\", \"label\": \"$3\"}]"),
    f("actions", "Buttons over selected text, up to 16. Needs the selection permission.", "[{\"id\": \"$1\", \"name\": \"$2\"}]"),
    f("providers", "Rows for the keyboard's provider lists, such as web search or translate, up to 8.", "[{\"tool\": \"$1\"}]"),
    f("previews", "File types the plugin shows in the file manager, up to 16. Needs the previews permission.", "[{\"id\": \"$1\", \"name\": \"$2\", \"extensions\": [\"$3\"]}]"),
    f("syntax", "Grammars that colour file types, up to 16: inline, or \"assets/name.json\".", "[{\"id\": \"$1\", \"name\": \"$2\", \"extensions\": [\"$3\"], \"grammar\": \"assets/$4.json\"}]"),
    f("panel", "false for a plugin made only of selection actions, providers, previews or grammars.", "false"),
];

pub const TOOL: &[Field] = &[
    f("icon", "The plugin's icon: a Material Symbols name, or \"assets/icon.svg\".", "\"$1\""),
    f("color", "The icon's colour, as \"#RRGGBB\".", "\"#$1\""),
    f("tile", "The plugin joins the toolbox as a tool when it is installed.", "true"),
    f("float", "The panel may open in the floating box, over the app, with the keyboard closed.", "true"),
    f("keyword", "A word the user types as @word: 1 to 16 lowercase letters or digits. Answer it with @plugin.keyword.", "\"$1\""),
    f("chip", "With a keyword: typing @word offers the plugin on the suggestion strip.", "true"),
    f("height", "The panel's height in dp. Leave it out for the keyboard's own.", "$1"),
    f("fullBleed", "true gives the panel the toolbar's rows too, with a back arrow in its header.", "true"),
];

pub const SETTING: &[Field] = &[
    f("key", "The name the code reads it by: lowercase letters, digits and _, up to 32 characters.", "\"$1\""),
    f("type", "switch, text, number, choice, host or secret.", "\"${1:text}\""),
    f("label", "The row's title.", "\"$1\""),
    f("summary", "One line under the title. Optional.", "\"$1\""),
    f("default", "The starting value, written as text: \"true\", \"5\", \"metric\". A secret has none.", "\"$1\""),
    f("options", "For choice: the values to pick from.", "[\"$1\"]"),
];

pub const ACTION: &[Field] = &[
    f("id", "Unique within the plugin: lowercase letters, digits, - and _. @plugin.action(id) answers it.", "\"$1\""),
    f("name", "The chip's label.", "\"$1\""),
    f("summary", "One line for the settings list.", "\"$1\""),
    f("icon", "A Material Symbols name. Blank for the default.", "\"$1\""),
    f("replaces", "false keeps the selection and adds the result after it.", "false"),
    f("input", "\"selection\" passes the selection; \"none\" passes an empty string.", "\"${1:selection}\""),
];

pub const PROVIDER: &[Field] = &[
    f("tool", "The provider list it answers for, such as search or translate.", "\"$1\""),
    f("id", "Unique for its tool within the plugin; the tool's name by default.", "\"$1\""),
    f("name", "The row's name in the list.", "\"$1\""),
    f("summary", "One line under the name.", "\"$1\""),
    f("images", "For search: the provider also answers image searches.", "true"),
];

pub const PREVIEW: &[Field] = &[
    f("id", "Unique within the plugin: lowercase letters, digits, - and _. @plugin.preview(id) draws it.", "\"$1\""),
    f("name", "What the \"View with\" chip says. Blank is the plugin's name.", "\"$1\""),
    f("extensions", "Lower case, no dot: docx, tar.gz.", "[\"$1\"]"),
    f("names", "Whole file names: Dockerfile, .gitignore. Matched ignoring case.", "[\"$1\"]"),
    f("mimes", "text/csv, or a family such as audio/*.", "[\"$1\"]"),
    f("maxBytes", "The most of a file the call is given at once (4 MB by default); file.read(offset, length) reaches the rest.", "$1"),
    f("thumbnails", "true when @plugin.thumbnail(id) makes pictures for the file list.", "true"),
];

pub const SYNTAX: &[Field] = &[
    f("id", "Unique within the plugin.", "\"$1\""),
    f("name", "The grammar's name.", "\"$1\""),
    f("extensions", "Lower case, no dot.", "[\"$1\"]"),
    f("names", "Whole file names.", "[\"$1\"]"),
    f("grammar", "Inline, or \"assets/name.json\".", "\"assets/$1.json\""),
];


/// The fields of the object at [path], when it is one plugin.json defines.
pub fn fields_at(path: &[Seg]) -> Option<&'static [Field]> {
    let key = |i: usize| match path.get(i) {
        Some(Seg::Key(k)) => Some(k.as_str()),
        _ => None,
    };
    match path.len() {
        0 => Some(TOP),
        1 if key(0) == Some("tool") => Some(TOOL),
        2 if matches!(path[1], Seg::Index(_)) => match key(0)? {
            "settings" => Some(SETTING),
            "actions" => Some(ACTION),
            "providers" => Some(PROVIDER),
            "previews" => Some(PREVIEW),
            "syntax" => Some(SYNTAX),
            _ => None,
        },
        _ => None,
    }
}

/// A value plugin.json takes: what to insert, a detail, and Markdown docs.
pub struct Choice {
    pub label: String,
    pub detail: Option<String>,
    pub doc: Option<String>,
    /// Written as a JSON string (else as is: numbers, true).
    pub string: bool,
}

/// The values that fit where [c] is.
pub fn values_at(c: &Context) -> Vec<Choice> {
    let s = |label: &str, doc: Option<String>| Choice { label: label.to_string(), detail: None, doc, string: true };
    let raw = |label: &str| Choice { label: label.to_string(), detail: None, doc: None, string: false };
    let path: Vec<&str> = c.path.iter().map(|p| if let Seg::Key(k) = p { k.as_str() } else { "#" }).collect();
    match (path.as_slice(), c.value_of.as_deref()) {
        (["permissions"], None) => permissions::all()
            .iter()
            .map(|p| Choice { label: p.name.to_string(), detail: Some(format!("{} permission", p.tier.name())), doc: Some(permissions::markdown(p)), string: true })
            .collect(),
        ([], Some("format")) => vec![s("wmkeyboard-plugin", None)],
        ([], Some("language")) => vec![s("python", None)],
        ([], Some("apiVersion")) => vec![raw("5")],
        ([], Some("panel")) | (["tool"], Some("tile" | "float" | "chip" | "fullBleed")) => vec![raw("true"), raw("false")],
        (["tool"], Some("icon")) => keyboard::ICONS.iter().map(|i| s(i, None)).collect(),
        (["settings", "#"], Some("type")) => codec::SETTING_TYPES.iter().map(|t| s(t, None)).collect(),
        (["actions", "#"], Some("input")) => vec![s("selection", None), s("none", None)],
        (["actions", "#"], Some("replaces")) | (["previews", "#"], Some("thumbnails")) | (["providers", "#"], Some("images")) => vec![raw("true"), raw("false")],
        (["providers", "#"], Some("tool")) => keyboard::PROVIDER_TOOLS
            .iter()
            .map(|t| Choice { label: t.name.to_string(), detail: Some(t.label.to_string()), doc: Some(provider_doc(t)), string: true })
            .collect(),
        _ => Vec::new(),
    }
}

fn provider_doc(t: &keyboard::ProviderTool) -> String {
    let keys: Vec<String> = t.request.iter().map(|(k, d)| format!("- `{k}`: {d}")).collect();
    format!("{}\n\nThe request holds:\n{}\n\nReply: {}", t.label, keys.join("\n"), t.reply)
}

// ---- problems ----------------------------------------------------------------

/// One problem with plugin.json, in bytes, from the importer's rules
/// (`wm_python_tooling::manifest::check`, a port of PluginManifestCodec).
pub struct Problem {
    pub severity: Severity,
    pub code: &'static str,
    pub message: String,
    pub help: Option<String>,
    pub start: usize,
    pub end: usize,
}

/// What is wrong with plugin.json [text] as the keyboard would read it.
pub fn problems(text: &str) -> Vec<Problem> {
    codec::check(text)
        .problems
        .into_iter()
        .filter(|p| p.file == codec::MANIFEST)
        .map(|p| {
            // A problem with no place in the text is about the whole file: its first character.
            let (start, end) = match p.span {
                Some(s) => (byte_of_utf16(text, s.start), byte_of_utf16(text, s.end.max(s.start))),
                None => (0, text.chars().next().map_or(0, char::len_utf8)),
            };
            Problem { severity: p.severity, code: p.code, message: p.message, help: p.help, start, end }
        })
        .collect()
}

/// The problems of the plugin's plugin.json as Diag JSON.
pub fn diagnostics_json(plugin: &Plugin) -> Vec<Value> {
    let Some(text) = &plugin.manifest_text else {
        return vec![serde_json::json!({"severity": "error", "code": "no_manifest", "message": "There is no readable plugin.json in this folder.",
            "file": "plugin.json", "line": null, "col": null, "endCol": null, "traceback": [], "notes": [], "help": null})];
    };
    problems(text)
        .into_iter()
        .map(|p| {
            let line_start = text[..p.start].rfind('\n').map_or(0, |i| i + 1);
            let line = text[..p.start].matches('\n').count() + 1;
            let col = text[line_start..p.start].chars().count() + 1;
            let end_col = if text[p.start..p.end].contains('\n') { col + 1 } else { col + text[p.start..p.end].chars().count().max(1) };
            serde_json::json!({"severity": p.severity.name(), "code": p.code, "message": p.message,
                "file": "plugin.json", "line": line, "col": col, "endCol": end_col, "traceback": [], "notes": [], "help": p.help})
        })
        .collect()
}

// ---- hover -------------------------------------------------------------------

/// Markdown about what is at byte [at] of plugin.json [text].
pub fn hover(text: &str, at: usize) -> Option<(String, usize, usize)> {
    let tree = json::parse(text)?;
    let mut path = Vec::new();
    let mut node = &tree;
    loop {
        match &node.kind {
            Kind::Object(members) => {
                let m = members.iter().find(|m| (m.key_start..m.value.end).contains(&at))?;
                if at < m.key_start + m.key.len() + 2 {
                    let field = fields_at(&path)?.iter().find(|f| f.name == m.key)?;
                    return Some((format!("`{}`: {}", field.name, field.doc), m.key_start, m.key_start + m.key.len() + 2));
                }
                path.push(Seg::Key(m.key.clone()));
                node = &m.value;
            }
            Kind::Array(items) => {
                let (i, item) = items.iter().enumerate().find(|(_, n)| (n.start..n.end).contains(&at))?;
                path.push(Seg::Index(i));
                node = item;
            }
            Kind::Str(s) => {
                let is_permission = path.len() == 2 && path[0] == Seg::Key("permissions".into());
                if is_permission {
                    return permissions::find(s).map(|p| (permissions::markdown(&p), node.start, node.end));
                }
                let is_provider_tool = path.len() == 3 && path[0] == Seg::Key("providers".into()) && path[2] == Seg::Key("tool".into());
                if is_provider_tool {
                    return keyboard::provider_tool(s).map(|t| (provider_doc(t), node.start, node.end));
                }
                return None;
            }
            Kind::Other => return None,
        }
    }
}

// ---- a Python fix, made to plugin.json ---------------------------------------

fn titled(key: &str) -> String {
    let words = key.replace(['_', '-'], " ");
    let mut c = words.chars();
    match c.next() {
        Some(first) => first.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

fn quote(s: &str) -> String {
    serde_json::to_string(s).unwrap_or_default()
}

/// The edits that make [fix] in plugin.json [text], in the file's own
/// layout. None when the fix is not a manifest change, when it is already
/// made, or when plugin.json cannot be read.
pub fn fix_edits(text: &str, fix: &Fix) -> Option<Vec<Edit>> {
    let tree = json::parse(text)?;
    let array_add = |key: &str, item: String, same: &dyn Fn(&Node) -> bool| -> Option<Edit> {
        match tree.get(key) {
            Some(m) => {
                if let Kind::Array(items) = &m.value.kind {
                    if items.iter().any(same) {
                        return None;
                    }
                }
                Some(json::append(text, &m.value, &item))
            }
            None => Some(json::add_member(text, &tree, key, &format!("[{item}]"))),
        }
    };
    let is_str = |want: &str| {
        let want = want.to_string();
        move |n: &Node| matches!(&n.kind, Kind::Str(s) if *s == want)
    };
    let has_key = |key: &'static str, want: String| move |n: &Node| matches!(n.get(key).map(|m| &m.value.kind), Some(Kind::Str(s)) if *s == want);
    let edits = match fix {
        Fix::Permission(p) => {
            let mut edits = vec![array_add("permissions", quote(p), &is_str(p))?];
            let needs5 = permissions::find(p).is_some_and(|x| x.since_api >= 5);
            let api = tree.get("apiVersion").and_then(|m| text[m.value.start..m.value.end].parse::<i64>().ok());
            if needs5 && api.is_none_or(|a| a < 5) {
                edits.push(json::set_member(text, &tree, "apiVersion", "5"));
            }
            edits
        }
        Fix::Host(h) => vec![array_add("hosts", quote(h), &is_str(h))?],
        Fix::Provider(tool, id) => {
            let item = if tool == id { format!("{{\"tool\": {}}}", quote(tool)) } else { format!("{{\"tool\": {}, \"id\": {}}}", quote(tool), quote(id)) };
            vec![array_add("providers", item, &has_key("id", id.clone()))?]
        }
        Fix::Action(a) => vec![array_add("actions", format!("{{\"id\": {}, \"name\": {}}}", quote(a), quote(&titled(a))), &has_key("id", a.clone()))?],
        Fix::Setting(s) => {
            let item = format!("{{\"key\": {}, \"type\": \"text\", \"label\": {}}}", quote(s), quote(&titled(s)));
            vec![array_add("settings", item, &has_key("key", s.clone()))?]
        }
        Fix::Keyword(k) => match tree.get("tool") {
            Some(m) if matches!(m.value.kind, Kind::Object(_)) => {
                let edits = vec![json::set_member(text, &m.value, "keyword", &quote(k))];
                if m.value.get("chip").is_none() {
                    // Added after the keyword, in the same object: one edit at its end.
                    let with_keyword = json::apply(text, edits.clone());
                    let tree2 = json::parse(&with_keyword)?;
                    let tool2 = &tree2.get("tool")?.value;
                    let chip = json::add_member(&with_keyword, tool2, "chip", "true");
                    return Some(vec![Edit { start: 0, end: text.len(), text: json::apply(&with_keyword, vec![chip]) }]);
                }
                edits
            }
            _ => vec![json::add_member(text, &tree, "tool", &format!("{{\"keyword\": {}, \"chip\": true}}", quote(k)))],
        },
        Fix::Replace(_) | Fix::Insert(..) => return None,
    };
    Some(edits)
}

#[cfg(test)]
mod tests {
    use super::*;

    const MANIFEST: &str = "{\n  \"format\": \"wmkeyboard-plugin\",\n  \"id\": \"com.example.t\",\n  \"name\": \"T\",\n  \"pluginVersion\": \"1.0\",\n  \"apiVersion\": 4,\n  \"permissions\": []\n}\n";

    #[test]
    fn permission_fix_bumps_the_api() {
        let edits = fix_edits(MANIFEST, &Fix::Permission("network".into())).unwrap();
        let out = json::apply(MANIFEST, edits);
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["permissions"], serde_json::json!(["network"]));
        assert_eq!(v["apiVersion"], 5);
        assert!(fix_edits(&out, &Fix::Permission("network".into())).is_none(), "already there");
    }

    #[test]
    fn keyword_fix_adds_the_tool_block() {
        let out = json::apply(MANIFEST, fix_edits(MANIFEST, &Fix::Keyword("define".into())).unwrap());
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["tool"], serde_json::json!({"keyword": "define", "chip": true}));
        let with_tool = out.replace("\"chip\": true", "\"icon\": \"book\"");
        let out = json::apply(&with_tool, fix_edits(&with_tool, &Fix::Keyword("look".into())).unwrap());
        let v: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["tool"], serde_json::json!({"keyword": "look", "icon": "book", "chip": true}));
    }

    #[test]
    fn problems_name_unknown_permissions() {
        let text = MANIFEST.replace("[]", "[\"netwrok\"]");
        let p = problems(&text);
        let bad = p.iter().find(|p| p.message.contains("netwrok")).unwrap_or_else(|| panic!("{:?}", p.iter().map(|p| &p.message).collect::<Vec<_>>()));
        assert_eq!(bad.severity, Severity::Error);
        assert!(text[bad.start..bad.end].contains("netwrok"), "{}", &text[bad.start..bad.end]);
    }

}
