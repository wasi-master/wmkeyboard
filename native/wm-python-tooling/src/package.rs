//! The API 5 `wm` package (`from wm import plugin, ui, http`), modelled from
//! the package's own Python, which this crate compiles in. Reading the
//! source keeps completion, hover and the member checks in step with what
//! the interpreter actually runs: a function added to `wm/ui.py` is offered
//! the day it exists, and one taken out stops being offered.
//!
//! The package lives under the root `wm5` so it never meets the API 4 `wm`
//! global (whose `wm.ui` is a different thing); [import_path] maps an import
//! from `wm` onto it.

use crate::api::{ApiEntry, ApiKind};

/// The package's modules, `(name under wm, source)`; `""` is `wm/__init__.py`.
const SOURCES: &[(&str, &str)] = &[
    ("", include_str!("../../wm-python/src/host/prelude/__init__.py")),
    ("ai", include_str!("../../wm-python/src/host/prelude/ai.py")),
    ("clipboard", include_str!("../../wm-python/src/host/prelude/clipboard.py")),
    ("device", include_str!("../../wm-python/src/host/prelude/device.py")),
    ("field", include_str!("../../wm-python/src/host/prelude/field.py")),
    ("files", include_str!("../../wm-python/src/host/prelude/files.py")),
    ("http", include_str!("../../wm-python/src/host/prelude/http.py")),
    ("i18n", include_str!("../../wm-python/src/host/prelude/i18n.py")),
    ("location", include_str!("../../wm-python/src/host/prelude/location.py")),
    ("notify", include_str!("../../wm-python/src/host/prelude/notify.py")),
    ("plugin", include_str!("../../wm-python/src/host/prelude/plugin.py")),
    ("reminders", include_str!("../../wm-python/src/host/prelude/reminders.py")),
    ("scan", include_str!("../../wm-python/src/host/prelude/scan.py")),
    ("secrets", include_str!("../../wm-python/src/host/prelude/secrets.py")),
    ("sensors", include_str!("../../wm-python/src/host/prelude/sensors.py")),
    ("share", include_str!("../../wm-python/src/host/prelude/share.py")),
    ("ui", include_str!("../../wm-python/src/host/prelude/ui.py")),
];

/// The permission each module's calls need, as the manifest names it.
fn permission(module: &str) -> Option<&'static str> {
    Some(match module {
        "http" => "network",
        "ai" => "ai",
        "location" => "location",
        "files" => "files",
        "sensors" => "sensors",
        "scan" => "scan",
        "device" => "device",
        "share" => "share",
        "notify" => "notifications",
        "secrets" => "storage",
        "reminders" => "reminders",
        _ => return None,
    })
}

pub const ROOT: &str = "wm5";

/// What the package's own source leaves undocumented, by path under `wm5`.
/// Kept here rather than as docstrings, which every session would parse.
const DOCS: &[(&str, &str)] = &[
    ("api_version", "The plugin API level this keyboard runs: 5."),
    ("plugin_id", "The id from your plugin.json."),
    ("plugin_version", "The pluginVersion from your plugin.json."),
    ("storage", "A dict of JSON values kept on this device for your plugin: storage[\"items\"] = items. Needs the storage permission."),
    ("_", "Your English text in the user's language, from locales/<tag>.json; {name} values are filled after translating."),
    ("gettext", "The same as _(): your English text in the user's language."),
    ("ngettext", "ngettext(singular, plural, n): the form for the count n in the user's language, with {n} filled in."),
    ("ai.ask", "Sends prompt to the AI model the user set up and returns its reply; with then=, calls then(reply) instead of waiting. Needs the risky ai permission."),
    ("clipboard.get", "The text the user copied last, or None. Only while handling something the user did in your panel. Needs the risky clipboard.read permission."),
    ("clipboard.set", "Copies text. Needs the clipboard.write permission."),
    ("clipboard.copy", "The same as clipboard.set(text)."),
    ("clipboard.paste", "The same as clipboard.get()."),
    ("device.vibrate", "A short vibration, ms milliseconds long. Needs the device permission."),
    ("device.flashlight", "Turns the flashlight on or off. Needs the device permission."),
    ("field.text_before", "Up to n characters before the cursor in the field the user is writing in. Needs the risky field.read permission, and works only while handling something the user did in your panel."),
    ("field.text_after", "Up to n characters after the cursor. Needs the risky field.read permission, and works only while handling something the user did in your panel."),
    ("field.replace_selection", "Replaces the selected text, or types at the cursor when nothing is selected. Needs field.write."),
    ("field.delete", "Deletes before characters before the cursor and after characters after it. Needs field.write."),
    ("files.save", "Asks the user where to save data (bytes or text) as name; then(path) hears where it went. Needs the risky files permission."),
    ("http.Response", "What a request returns: status_code, ok, headers, text, content, json() and raise_for_status()."),
    ("http.get", "A GET request to one of your hosts; params= adds a query string. Returns a Response, or with then= calls then(response)."),
    ("http.post", "A POST request: data= sends a form or bytes, json= sends JSON. Returns a Response."),
    ("http.put", "A PUT request, with data= or json= as for post."),
    ("http.patch", "A PATCH request, with data= or json= as for post."),
    ("http.delete", "A DELETE request. Returns a Response."),
    ("i18n._", "The same as wm._(): your English text in the user's language."),
    ("location.get", "Where the phone is, as {\"lat\", \"lon\", \"accuracy\"}; coarse unless accuracy=\"fine\". Needs the risky location permission."),
    ("notify.show", "Shows a notification titled with your plugin's name; reuse id to replace one. Needs the notifications permission."),
    ("notify.cancel", "Takes down the notification id."),
    ("plugin.id", "Your plugin's id, from plugin.json."),
    ("plugin.version", "Your plugin's version, from plugin.json."),
    ("plugin.api_version", "The plugin API level this keyboard runs: 5."),
    ("plugin.permissions", "The permissions your manifest declares, as a list of strings."),
    ("plugin.locale", "The user's first language as a tag, such as \"bn-BD\"."),
    ("plugin.theme", "\"dark\" or \"light\": the keyboard's theme right now."),
    ("plugin.surface", "Where this run of your code draws: \"panel\", \"float\" or \"action\"."),
    ("plugin.dark", "True while the keyboard's theme is dark."),
    ("plugin.settings", "The values from your settings page, by key: plugin.settings.units."),
    ("reminders.set", "Shows text in a notification at seconds (as time.time() gives), setting or replacing the reminder id. Needs the reminders permission."),
    ("reminders.cancel", "Cancels the reminder id. Returns True when there was one."),
    ("reminders.list", "Every reminder not shown yet, soonest first, as dicts with id, time and text."),
    ("scan.qr", "Opens the keyboard's QR scanner; then(text) hears what it read. Needs the scan permission."),
    ("scan.barcode", "The same as scan.qr: the scanner reads barcodes too."),
    ("scan.text", "Opens the keyboard's text scanner; then(text) hears the text it read. Needs the scan permission."),
    ("secrets.get", "A secret your plugin saved, such as an API key, or default. Kept encrypted and never backed up. Needs storage."),
    ("secrets.set", "Saves a secret, encrypted, under name. Needs storage."),
    ("secrets.remove", "Forgets the secret name."),
    ("sensors.subscribe", "Calls on_reading(values) hz times a second with readings of kind (\"accelerometer\", \"light\", \"pressure\", \"steps\"...) while your panel is open. Returns a token."),
    ("sensors.unsubscribe", "Stops the readings for token."),
    ("sensors.available", "The sensor kinds this phone has."),
    ("share.text", "Hands text to another app the user picks. Needs the share permission."),
    ("ui.COLOR_ROLES", "The colours a widget may take: primary, secondary, muted, danger, success, warning, accent."),
    ("ui.column", "Widgets stacked top to bottom. Use it as a with block, or pass the widgets."),
    ("ui.row", "Widgets side by side; weights= shares the width unevenly."),
    ("ui.grid", "Widgets in columns columns, filled row by row."),
    ("ui.card", "A raised box with an optional title; on_click makes the whole card a button."),
    ("ui.section", "A titled group; collapsible= lets the user fold it."),
    ("ui.scroll", "A box that scrolls its widgets inside height."),
    ("ui.spacer", "Empty space, height high."),
    ("ui.divider", "A line across the panel."),
    ("ui.page", "One page of ui.tabs: its title, then its widgets."),
    ("ui.label", "Text. style is \"title\", \"body\" or \"caption\"; color is a role such as \"danger\"."),
    ("ui.text", "The same as ui.label."),
    ("ui.title", "Text in the title style."),
    ("ui.caption", "Small text in the caption style."),
    ("ui.markdown", "Text with **bold**, *italic*, `code`, links and lists, drawn by the keyboard."),
    ("ui.output", "A result with the keyboard's Insert and Copy buttons under it."),
    ("ui.empty", "What an empty panel says, with an optional icon and action button."),
    ("ui.icon_button", "A button that is only an icon; label is what TalkBack reads."),
    ("ui.checkbox", "A checkbox; on_change(value) hears True or False."),
    ("ui.search", "A search box; on_change hears each change, on_submit the text when Enter is pressed."),
    ("ui.slider", "A slider from min to max; on_change(value) hears the number."),
    ("ui.stepper", "A number with minus and plus buttons; on_change(value) hears it."),
    ("ui.chips", "A row of chips to pick one of options, or several with multi=True."),
    ("ui.date", "A date picked with the keyboard's buttons; on_change hears seconds since 1970, or None."),
    ("ui.icon", "A Material Symbols icon by name, such as \"cloud\"."),
    ("ui.qr", "A QR code of text."),
    ("ui.stat", "A big value with a label above and an optional caption."),
    ("ui.ring", "A ring filled to value, from 0 to 1, with a label in the middle."),
    ("ui.rect", "A rectangle for ui.canvas, at x, y, w wide and h high."),
    ("ui.circle", "A circle for ui.canvas, centred at x, y with radius r."),
    ("ui.line", "A line for ui.canvas from x1, y1 to x2, y2."),
    ("ui.text_at", "Text for ui.canvas at x, y."),
];

/// Signatures for names the source binds by assignment or re-export.
const DOCS_SIGNATURES: &[(&str, &str)] = &[
    ("_", "_(text, **values)"),
    ("gettext", "gettext(text, **values)"),
    ("ngettext", "ngettext(singular, plural, n, **values)"),
    ("i18n._", "_(text, **values)"),
    ("clipboard.copy", "copy(text)"),
    ("clipboard.paste", "paste()"),
    ("scan.barcode", "barcode(then, on_error=None)"),
];

fn doc_for(path: &str, found: String) -> String {
    if found.len() >= 8 {
        return found;
    }
    DOCS.iter().find(|(p, _)| *p == path).map(|(_, d)| d.to_string()).unwrap_or(found)
}

/// The model path an import names: `wm5.plugin` for `from wm import plugin`,
/// `wm5.ui.column` for `from wm.ui import column`, otherwise `module.member`.
pub fn import_path(module: &str, member: Option<&str>, known: impl Fn(&str) -> bool) -> String {
    let plain = match member {
        Some(m) => format!("{module}.{m}"),
        None => module.to_string(),
    };
    let mapped = if module == "wm" {
        member.map(|m| format!("{ROOT}.{m}"))
    } else {
        module.strip_prefix("wm.").map(|rest| match member {
            Some(m) => format!("{ROOT}.{rest}.{m}"),
            None => format!("{ROOT}.{rest}"),
        })
    };
    match mapped {
        Some(p) if known(&p) => p,
        _ => plain,
    }
}

pub fn entries(leak: &dyn Fn(String) -> &'static str) -> Vec<ApiEntry> {
    let mut out = vec![ApiEntry {
        path: ROOT,
        kind: ApiKind::Module,
        signature: None,
        permission: None,
        doc: "WM Keyboard's plugin package (API 5): from wm import plugin, ui, http.",
    }];
    for (module, source) in SOURCES {
        let parent = if module.is_empty() { ROOT.to_string() } else { format!("{ROOT}.{module}") };
        if !module.is_empty() {
            out.push(ApiEntry {
                path: leak(parent.clone()),
                kind: ApiKind::Module,
                signature: None,
                permission: permission(module),
                doc: leak(module_doc(source)),
            });
        }
        for item in scan(source) {
            if module.is_empty() && SOURCES.iter().any(|(m, _)| *m == item.name) {
                continue;
            }
            let key = if module.is_empty() { item.name.clone() } else { format!("{module}.{}", item.name) };
            // A name that is another function (`_ = gettext`) is called like one.
            let signature = item.signature.or_else(|| DOCS_SIGNATURES.iter().find(|(p, _)| *p == key).map(|(_, s)| s.to_string()));
            let kind = if signature.is_some() && item.kind == ApiKind::Constant { ApiKind::Function } else { item.kind };
            out.push(ApiEntry {
                path: leak(format!("{parent}.{}", item.name)),
                kind,
                signature: signature.map(leak),
                permission: permission(module),
                doc: leak(doc_for(&key, item.doc)),
            });
        }
    }
    out
}

struct Item {
    name: String,
    kind: ApiKind,
    signature: Option<String>,
    doc: String,
}

/// A public name: not `_private`, except `_` itself, gettext's.
fn public(name: &str) -> bool {
    name == "_" || !name.starts_with('_')
}

/// The first paragraph of the docstring that opens [lines], collapsed to one line.
fn docstring(lines: &[&str]) -> String {
    let Some(first) = lines.iter().map(|l| l.trim()).find(|l| !l.is_empty()) else { return String::new() };
    let Some(rest) = first.strip_prefix("\"\"\"") else { return String::new() };
    let mut text = String::new();
    if let Some(end) = rest.find("\"\"\"") {
        return rest[..end].trim().to_string();
    }
    text.push_str(rest.trim());
    for line in lines.iter().skip_while(|l| l.trim() != first).skip(1) {
        let line = line.trim();
        if let Some(end) = line.find("\"\"\"") {
            text.push(' ');
            text.push_str(line[..end].trim());
            break;
        }
        if line.is_empty() {
            break;
        }
        text.push(' ');
        text.push_str(line);
    }
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn module_doc(source: &str) -> String {
    let lines: Vec<&str> = source.lines().collect();
    docstring(&lines)
}

/// The module's top-level functions, classes and names, read from the
/// source text: the package is ours and written plainly, so its top level
/// is lines that start in column 0.
fn scan(source: &str) -> Vec<Item> {
    let lines: Vec<&str> = source.lines().collect();
    let mut out: Vec<Item> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    let mut i = 0;
    while i < lines.len() {
        let line = lines[i];
        if let Some(rest) = line.strip_prefix("def ") {
            // The signature may run over several lines, up to the colon.
            let mut sig = rest.to_string();
            let mut j = i;
            while !sig.trim_end().ends_with(':') && j + 1 < lines.len() {
                j += 1;
                sig.push(' ');
                sig.push_str(lines[j].trim());
            }
            let sig = sig.trim_end().trim_end_matches(':').trim().to_string();
            let sig = sig.split(" -> ").next().unwrap_or(&sig).to_string();
            let name = sig.split('(').next().unwrap_or("").trim().to_string();
            if public(&name) && seen.insert(name.clone()) {
                out.push(Item { doc: docstring(&lines[j + 1..]), name, kind: ApiKind::Function, signature: Some(sig) });
            }
            i = j + 1;
            continue;
        }
        if let Some(rest) = line.strip_prefix("class ") {
            let name: String = rest.chars().take_while(|c| c.is_alphanumeric() || *c == '_').collect();
            if public(&name) && seen.insert(name.clone()) {
                let kind = if rest.contains("Error") || rest.contains("Exception") { ApiKind::Exception } else { ApiKind::Type };
                out.push(Item { doc: docstring(&lines[i + 1..]), name, kind, signature: None });
            }
        } else if let Some(rest) = line.strip_prefix("from ") {
            // A re-export: `from wm.i18n import _, gettext, ngettext`.
            if let Some((_, names)) = rest.split_once(" import ") {
                for part in names.split(',') {
                    let part = part.trim();
                    let name = part.rsplit(" as ").next().unwrap_or(part).trim().to_string();
                    if public(&name) && seen.insert(name.clone()) {
                        out.push(Item { name, kind: ApiKind::Function, signature: None, doc: String::new() });
                    }
                }
            }
        } else if line.starts_with(|c: char| c.is_ascii_alphabetic() || c == '_') && !line.starts_with("import ") {
            // `name = value` at the top level (one name, not a tuple or an attribute).
            if let Some((left, _)) = line.split_once('=') {
                let name = left.trim().split(':').next().unwrap_or("").trim();
                let simple = !name.is_empty() && name.chars().all(|c| c.is_alphanumeric() || c == '_') && !line[left.len()..].starts_with("==");
                if simple && public(name) && seen.insert(name.to_string()) {
                    // A comment on the line before says what it is.
                    let doc = i.checked_sub(1).and_then(|p| lines[p].trim().strip_prefix("# ")).unwrap_or("").to_string();
                    out.push(Item { name: name.to_string(), kind: ApiKind::Constant, signature: None, doc });
                }
            }
        }
        i += 1;
    }
    out
}
