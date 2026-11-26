//! What the MCP server can read out: the plugin docs (built into the
//! binary), an index of the wm API from the editor's own model, the checks'
//! codes, and lookups in that model.

use serde_json::{json, Value};
use wm_python_tooling::api::{self, ApiEntry};
use wm_python_tooling::diagnostics::Code;

const WM_PACKAGE: &str = include_str!("../../../../docs/src/content/docs/plugins/wm-package.mdx");
const PERMISSIONS: &str = include_str!("../../../../docs/src/content/docs/plugins/permissions.mdx");
const HELP: &str = include_str!("../../../../docs/src/content/docs/plugins/help.mdx");
const TESTING: &str = include_str!("../../../../docs/src/content/docs/plugins/guides/testing.mdx");

pub struct Resource {
    pub uri: &'static str,
    pub name: &'static str,
    pub title: &'static str,
    pub description: &'static str,
}

pub const RESOURCES: &[Resource] = &[
    Resource {
        uri: "wmpy://docs/api",
        name: "api",
        title: "wm package reference",
        description: "Everything an API 5 plugin can use: plugin.json, the Python it gets, plugin, ui and every host module with arguments, results, errors and limits.",
    },
    Resource {
        uri: "wmpy://docs/api-index",
        name: "api-index",
        title: "wm API index",
        description: "Every name the editors know (wm modules, ui widgets, builtins, methods), one line each with its signature and permission.",
    },
    Resource {
        uri: "wmpy://docs/plugin-json",
        name: "plugin-json",
        title: "plugin.json format",
        description: "The manifest: every field, the tool block, settings, actions, providers, previews.",
    },
    Resource { uri: "wmpy://docs/permissions", name: "permissions", title: "Permissions", description: "Every permission, which ones are risky, and what no permission allows." },
    Resource {
        uri: "wmpy://docs/testing",
        name: "testing",
        title: "Testing plugins",
        description: "test.py, plugin_test.session(mock=...), events, then= tokens, settle(), mock routes.",
    },
    Resource { uri: "wmpy://docs/help", name: "help", title: "Help and error messages", description: "Common questions and what each runtime and install error means." },
    Resource { uri: "wmpy://docs/diagnostics", name: "diagnostics", title: "Check codes", description: "Every problem code wmpy check and the editors report, with its severity and message." },
];

/// An MDX page as plain Markdown: no imports, components as text.
fn markdown(mdx: &str) -> String {
    let mut out = String::with_capacity(mdx.len());
    for line in mdx.lines() {
        let t = line.trim_start();
        if t.starts_with("import ") && t.contains(" from ") {
            continue;
        }
        if matches!(t, "<Steps>" | "</Steps>" | "</TabItem>" | "</Tabs>" | "<CardGrid>" | "</CardGrid>") || t.starts_with("<Tabs") || t.starts_with("<LinkCard") {
            continue;
        }
        if let Some(rest) = t.strip_prefix("<TabItem label=\"") {
            out.push_str(&format!("**{}**\n", rest.split('"').next().unwrap_or("")));
            continue;
        }
        let mut line = line.to_string();
        // <SettingsPath path="Tools / Plugins" /> is where the setting is in the app.
        while let Some(start) = line.find("<SettingsPath path=\"") {
            let after = &line[start + 20..];
            let Some(q) = after.find('"') else { break };
            let path = after[..q].to_string();
            let end = after.find("/>").map(|e| start + 20 + e + 2).unwrap_or(line.len());
            line.replace_range(start..end, &format!("Settings › {path}"));
        }
        out.push_str(&line);
        out.push('\n');
    }
    // Site links read as links into the docs site.
    out.replace("](/", "](https://wmkeyboard.pages.dev/")
}

/// The section of [md] under the heading [heading], to the next of its level.
fn section(md: &str, heading: &str) -> String {
    let mut out = String::new();
    let mut inside = false;
    for line in md.lines() {
        if line.starts_with("## ") {
            if inside {
                break;
            }
            inside = line.trim() == heading;
        }
        if inside {
            out.push_str(line);
            out.push('\n');
        }
    }
    out
}

fn api_index() -> String {
    let mut out = String::from("# wm API index\n\nEvery name the editors complete, from the same model. `permission` is what plugin.json must declare.\n");
    let mut parent = "";
    for e in &api::model().entries {
        if e.parent() != parent {
            parent = e.parent();
            out.push_str(&format!("\n## {}\n\n", if parent.is_empty() { "builtins and modules" } else { parent }));
        }
        let sig = e.signature.unwrap_or("");
        let doc = e.doc.split(". ").next().unwrap_or("").trim_end_matches('.');
        let perm = e.permission.map(|p| format!(" (needs `{p}`)")).unwrap_or_default();
        out.push_str(&format!("- `{}{sig}` {}{perm}: {doc}.\n", e.path, e.kind.name()));
    }
    out
}

fn plugin_json() -> String {
    let mut out = section(&markdown(WM_PACKAGE), "## The manifest");
    out.push_str("\n## Every field\n");
    let groups: [(&str, &[crate::lsp::manifest::Field]); 7] = [
        ("Top level", crate::lsp::manifest::TOP),
        ("tool", crate::lsp::manifest::TOOL),
        ("settings[]", crate::lsp::manifest::SETTING),
        ("actions[]", crate::lsp::manifest::ACTION),
        ("providers[]", crate::lsp::manifest::PROVIDER),
        ("previews[]", crate::lsp::manifest::PREVIEW),
        ("syntax[]", crate::lsp::manifest::SYNTAX),
    ];
    for (title, fields) in groups {
        out.push_str(&format!("\n### {title}\n\n"));
        for f in fields {
            out.push_str(&format!("- `{}`: {}\n", f.name, f.doc));
        }
    }
    out.push_str("\n### Provider lists\n\n");
    for t in wm_python_tooling::keyboard::PROVIDER_TOOLS {
        let keys: Vec<&str> = t.request.iter().map(|(k, _)| *k).collect();
        out.push_str(&format!("- `{}`: {}. Request keys: {}.\n", t.name, t.label, keys.join(", ")));
    }
    out
}

fn diagnostics() -> String {
    let mut out = String::from("# Check codes\n\n`{0}` and `{1}` stand for the names in each message.\n\n| Code | Severity | Message |\n|---|---|---|\n");
    for c in Code::ALL {
        out.push_str(&format!("| `{}` | {} | {} |\n", c.name(), c.severity().name(), c.message().replace('|', "\\|")));
    }
    out
}

/// The Markdown of resource [uri].
pub fn read(uri: &str) -> Option<String> {
    Some(match uri {
        "wmpy://docs/api" => markdown(WM_PACKAGE),
        "wmpy://docs/api-index" => api_index(),
        "wmpy://docs/plugin-json" => plugin_json(),
        "wmpy://docs/permissions" => markdown(PERMISSIONS),
        "wmpy://docs/testing" => markdown(TESTING),
        "wmpy://docs/help" => markdown(HELP),
        "wmpy://docs/diagnostics" => diagnostics(),
        _ => return None,
    })
}

// ---- lookups in the API model ------------------------------------------------

pub fn entry_json(e: &ApiEntry) -> Value {
    json!({
        "path": e.path, "kind": e.kind.name(), "signature": e.signature,
        "doc": e.doc, "permission": e.permission,
    })
}

/// The entry [name] names: a full path (`wm.http.get`), or one without its
/// `wm.` (`http.get`, `ui.button`), or a builtin or method (`len`, `str.upper`).
pub fn find(name: &str) -> Option<&'static ApiEntry> {
    let m = api::model();
    let name = name.trim().trim_end_matches("()");
    m.find(name)
        .or_else(|| m.find(&format!("wm.{name}")))
        .or_else(|| m.entries.iter().find(|e| e.path.ends_with(&format!(".{name}"))))
}

pub fn lookup(name: &str) -> Value {
    let m = api::model();
    match find(name) {
        Some(e) => {
            let children: Vec<Value> = m
                .children(e.path)
                .into_iter()
                .map(|c| json!({"path": c.path, "kind": c.kind.name(), "signature": c.signature, "permission": c.permission}))
                .collect();
            json!({"found": true, "entry": entry_json(e), "children": children})
        }
        None => {
            let near = search(name, 8);
            json!({"found": false, "entry": null, "children": [], "suggestions": near.iter().map(|e| e["path"].clone()).collect::<Vec<_>>()})
        }
    }
}

/// Entries matching [query] best first: by name, then by path, then by doc.
pub fn search(query: &str, limit: usize) -> Vec<Value> {
    let words: Vec<String> = query.to_lowercase().split_whitespace().map(str::to_string).collect();
    if words.is_empty() {
        return Vec::new();
    }
    let mut scored: Vec<(i32, &ApiEntry)> = api::model()
        .entries
        .iter()
        .filter_map(|e| {
            let name = e.name().to_lowercase();
            let path = e.path.to_lowercase();
            let doc = e.doc.to_lowercase();
            let mut score = 0;
            for w in &words {
                score += if name == *w {
                    100
                } else if name.starts_with(w.as_str()) {
                    40
                } else if path.contains(w.as_str()) {
                    20
                } else if doc.contains(w.as_str()) {
                    5
                } else {
                    return None;
                };
            }
            // The wm package first: it is what plugins are written against.
            if path.starts_with("wm.") || path.starts_with("ui.") {
                score += 3;
            }
            Some((score, e))
        })
        .collect();
    scored.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.path.len().cmp(&b.1.path.len())).then(a.1.path.cmp(b.1.path)));
    scored.into_iter().take(limit).map(|(_, e)| entry_json(e)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_resource_reads() {
        for r in RESOURCES {
            let text = read(r.uri).unwrap();
            assert!(text.len() > 200, "{} is short", r.uri);
            assert!(!text.contains("import { "), "{} keeps an MDX import", r.uri);
        }
        assert!(read("wmpy://docs/plugin-json").unwrap().contains("pluginVersion"));
    }

    #[test]
    fn finds_api_names() {
        assert!(find("http.get").is_some() || find("wm.http.get").is_some());
        assert!(find("len").is_some());
        assert!(!search("storage", 5).is_empty());
    }
}
