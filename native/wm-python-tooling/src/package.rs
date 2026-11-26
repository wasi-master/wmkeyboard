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
    ("calendar", include_str!("../../wm-python/src/host/prelude/calendar.py")),
    ("clipboard", include_str!("../../wm-python/src/host/prelude/clipboard.py")),
    ("clock", include_str!("../../wm-python/src/host/prelude/clock.py")),
    ("device", include_str!("../../wm-python/src/host/prelude/device.py")),
    ("field", include_str!("../../wm-python/src/host/prelude/field.py")),
    ("files", include_str!("../../wm-python/src/host/prelude/files.py")),
    ("formats", include_str!("../../wm-python/src/host/prelude/formats.py")),
    ("http", include_str!("../../wm-python/src/host/prelude/http.py")),
    ("i18n", include_str!("../../wm-python/src/host/prelude/i18n.py")),
    ("keyboard", include_str!("../../wm-python/src/host/prelude/keyboard.py")),
    ("location", include_str!("../../wm-python/src/host/prelude/location.py")),
    ("notify", include_str!("../../wm-python/src/host/prelude/notify.py")),
    ("plugin", include_str!("../../wm-python/src/host/prelude/plugin.py")),
    ("preview", include_str!("../../wm-python/src/host/prelude/preview.py")),
    ("reminders", include_str!("../../wm-python/src/host/prelude/reminders.py")),
    ("scan", include_str!("../../wm-python/src/host/prelude/scan.py")),
    ("secrets", include_str!("../../wm-python/src/host/prelude/secrets.py")),
    ("sensors", include_str!("../../wm-python/src/host/prelude/sensors.py")),
    ("share", include_str!("../../wm-python/src/host/prelude/share.py")),
    ("todo", include_str!("../../wm-python/src/host/prelude/todo.py")),
    ("ui", include_str!("../../wm-python/src/host/prelude/ui.py")),
];

/// The permission each module's calls need, as the manifest names it.
fn permission(module: &str) -> Option<&'static str> {
    Some(match module {
        "http" => "network",
        "ai" => "ai",
        "location" => "location",
        "calendar" => "calendar",
        "clock" => "clock",
        "todo" => "todo",
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
    ("http.post", "A POST request: data= sends a form or bytes, json= sends JSON, files= sends a multipart upload form. Returns a Response."),
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
    ("secrets.get", "A secret your plugin saved, such as an API key, or default. Kept apart from storage and never backed up. Needs storage."),
    ("secrets.set", "Saves a secret under name, apart from storage and out of backups. Needs storage."),
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
    // Methods of objects the scan does not look inside.
    for (path, signature, doc) in MEMBERS {
        out.push(ApiEntry {
            path: leak(format!("{ROOT}.{path}")),
            // An attribute has no call shape.
            kind: if signature.is_empty() { ApiKind::Constant } else { ApiKind::Function },
            signature: (!signature.is_empty()).then_some(*signature),
            permission: None,
            doc,
        });
    }
    out
}

/// `(path under wm, signature, doc)` for the members of module-level objects
/// and of the classes the package returns; an empty signature is an attribute.
const MEMBERS: &[(&str, &str, &str)] = &[
    ("http.Response.status_code", "", "The HTTP status: 200 for a page that came back, 404 for one that does not exist."),
    ("http.Response.ok", "", "True when the status is under 400."),
    ("http.Response.headers", "", "The reply's headers, by lowercase name."),
    ("http.Response.text", "", "The reply's body as text."),
    ("http.Response.content", "", "The reply's body as bytes."),
    ("http.Response.url", "", "The address that answered."),
    ("http.Response.reason", "", "The status in words, such as \"Not Found\"."),
    ("http.Response.json", "json()", "The body read as JSON: a dict, a list, a string or a number."),
    ("http.Response.raise_for_status", "raise_for_status()", "Raises http.HTTPError when the status is 400 or more; returns the response otherwise."),
    ("http.HTTPError.response", "", "The Response that failed, or None."),
    ("plugin.State.update", "update(**fields)", "Sets several fields at once: state.update(count=0, name=\"\")."),
    ("plugin.State.as_dict", "as_dict()", "Every field and its value, as a dict."),
    ("plugin.State.save", "save()", "With persist=True, saves now instead of after the event."),
    (
        "plugin.settings.get",
        "get(name, default=None)",
        "The value of setting name, or default when it is not declared or a secret is not set.",
    ),
    (
        "plugin.settings.set",
        "set(key, value)",
        "Changes your own setting key: True/False for a switch, a number, one of a choice's options, or text. Saved at once and shown on the settings page. A host setting raises PermissionError.",
    ),
    (
        "plugin.settings.reset",
        "reset(key)",
        "Puts setting key back to the manifest's default. A secret setting is forgotten.",
    ),
];

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

// ---- the libraries beside the package --------------------------------------------

/// The libraries the host gives plugins under their usual names, modelled
/// from their own Python as the package is: `(import name, source)`.
const LIBRARIES: &[(&str, &str)] = &[
    ("requests", include_str!("../../wm-python/src/host/prelude/requests/__init__.py")),
    ("requests.adapters", include_str!("../../wm-python/src/host/prelude/requests/adapters.py")),
    ("requests.api", include_str!("../../wm-python/src/host/prelude/requests/api.py")),
    ("requests.auth", include_str!("../../wm-python/src/host/prelude/requests/auth.py")),
    ("requests.cookies", include_str!("../../wm-python/src/host/prelude/requests/cookies.py")),
    ("requests.exceptions", include_str!("../../wm-python/src/host/prelude/requests/exceptions.py")),
    ("requests.models", include_str!("../../wm-python/src/host/prelude/requests/models.py")),
    ("requests.sessions", include_str!("../../wm-python/src/host/prelude/requests/sessions.py")),
    ("requests.status_codes", include_str!("../../wm-python/src/host/prelude/requests/status_codes.py")),
    ("requests.structures", include_str!("../../wm-python/src/host/prelude/requests/structures.py")),
    ("requests.utils", include_str!("../../wm-python/src/host/prelude/requests/utils.py")),
    ("httpx", include_str!("../../wm-python/src/host/prelude/httpx.py")),
    ("urllib.error", include_str!("../../wm-python/src/host/prelude/urllib/error.py")),
    ("urllib.request", include_str!("../../wm-python/src/host/prelude/urllib/request.py")),
];

/// Modules the libraries import names from, read for those names only.
const LIBRARY_HELPERS: &[(&str, &str)] = &[("wm._net", include_str!("../../wm-python/src/host/prelude/_net.py"))];

/// The library modules whose calls go to the network.
const NETWORK_LIBRARIES: &[&str] = &["requests", "requests.api", "requests.sessions", "httpx", "urllib.request"];

/// Every library module, by the name it is imported as.
pub fn library_modules() -> impl Iterator<Item = &'static str> {
    LIBRARIES.iter().map(|(name, _)| *name)
}

/// Whether [module] is one of the libraries: `requests`, `requests.exceptions`, `httpx`, `urllib.request`.
pub fn is_library(module: &str) -> bool {
    LIBRARIES.iter().any(|(name, _)| *name == module)
}

/// The permission importing [module] means a plugin needs: `network` for
/// requests, httpx and urllib.request; nothing for their errors and helpers.
pub fn library_permission(module: &str) -> Option<&'static str> {
    NETWORK_LIBRARIES.contains(&module).then_some("network")
}

/// What their source leaves undocumented, by model path.
const LIBRARY_DOCS: &[(&str, &str)] = &[
    ("requests.Response.status_code", "The HTTP status as a number: 200 for a page that came back, 404 for one that does not exist."),
    ("requests.Response.headers", "The reply's headers, looked up in any case: r.headers[\"Content-Type\"]."),
    ("requests.Response.url", "The address that answered, after any redirects."),
    ("requests.Response.encoding", "The charset r.text is read in: the reply's charset, or None to guess it. Set it to read the text another way."),
    ("requests.Response.history", "Always empty: the keyboard follows redirects itself."),
    ("requests.Response.reason", "The status in words, such as \"Not Found\"."),
    ("requests.Response.cookies", "The cookies the reply set, by name: r.cookies[\"session\"]."),
    ("requests.Response.elapsed", "How long the reply took, as a datetime.timedelta: r.elapsed.total_seconds()."),
    ("requests.Response.request", "The PreparedRequest this answers: method, url, headers, body."),
    ("requests.Response.raw", "The body as a file to read() from."),
    ("requests.Session.headers", "Headers sent with every request of the session: s.headers[\"Authorization\"] = \"Bearer \" + token."),
    ("requests.Session.auth", "Sent with every request: (\"user\", \"password\") for HTTP Basic, or an auth object."),
    ("requests.Session.params", "Query parameters added to every request of the session."),
    ("requests.Session.cookies", "The cookies replies set, kept for reading. The keyboard does not send them back."),
    ("requests.Session.proxies", "Not used: plugins connect only through the keyboard."),
    ("requests.Session.hooks", "{\"response\": [fn]}: fn(response) runs on every reply, and may return a replacement."),
    ("requests.Session.verify", "Not used: the keyboard always checks certificates."),
    ("requests.Session.cert", "Not used: plugins cannot send a client certificate."),
    ("requests.Session.stream", "Not used: the keyboard reads the whole reply."),
    ("requests.Session.max_redirects", "Not used: the keyboard follows 5 redirects at most."),
    ("requests.Session.trust_env", "Not used: plugins read no environment."),
    ("requests.Session.adapters", "The adapters mount() was given. The keyboard sends every request itself."),
    ("requests.exceptions.RequestException.response", "The Response that failed, or None."),
    ("requests.exceptions.RequestException.request", "The request that failed, or None."),
    ("requests.exceptions.JSONDecodeError.msg", "What was wrong, without the position: \"Expecting value\"."),
    ("requests.exceptions.JSONDecodeError.doc", "The text that was read."),
    ("requests.exceptions.JSONDecodeError.pos", "Where in the text it went wrong."),
    ("requests.exceptions.JSONDecodeError.lineno", "The line where it went wrong."),
    ("requests.exceptions.JSONDecodeError.colno", "The column where it went wrong."),
    ("httpx.Response.status_code", "The HTTP status as a number."),
    ("httpx.Response.headers", "The reply's headers, looked up in any case."),
    ("httpx.Response.reason_phrase", "The status in words, such as \"Not Found\"."),
    ("httpx.Response.url", "The address that answered, as an httpx.URL."),
    ("httpx.Response.elapsed", "How long the reply took, as a datetime.timedelta."),
    ("httpx.Response.cookies", "The cookies the reply set, by name."),
    ("httpx.Client.headers", "Headers sent with every request of the client."),
    ("httpx.Client.params", "Query parameters added to every request of the client."),
    ("httpx.Client.base_url", "The address relative addresses are joined to."),
    ("httpx.Client.timeout", "Seconds to wait for each reply, 15 at most."),
    ("httpx.Client.auth", "Sent with every request: (\"user\", \"password\") for HTTP Basic."),
    ("httpx.Client.cookies", "The cookies replies set. The keyboard does not send them back."),
    ("httpx.HTTPError.request", "The request that failed."),
    ("httpx.HTTPStatusError.response", "The Response whose status was not 2xx."),
    ("urllib.request.HTTPResponse.status", "The HTTP status as a number."),
    ("urllib.request.HTTPResponse.reason", "The status in words, such as \"Not Found\"."),
    ("urllib.request.HTTPResponse.headers", "The reply's headers: reply.headers[\"Content-Type\"], reply.headers.get_content_charset()."),
    ("urllib.request.HTTPResponse.url", "The address that answered, after any redirects."),
    ("urllib.error.HTTPError.code", "The HTTP status as a number."),
    ("urllib.error.URLError.reason", "Why the request got no answer, or the status in words for an HTTPError."),
];

#[derive(Clone, Debug)]
struct LibItem {
    name: String,
    kind: ApiKind,
    signature: Option<String>,
    doc: String,
    /// The class it derives from, as written.
    base: Option<String>,
    members: Vec<LibItem>,
    /// `from module import original`, for a name the module takes from another.
    from: Option<(String, String)>,
}

impl LibItem {
    fn new(name: &str, kind: ApiKind, signature: Option<String>, doc: String) -> LibItem {
        LibItem { name: name.to_string(), kind, signature, doc, base: None, members: Vec::new(), from: None }
    }
}

/// `def name(...)` starting at line [i], up to its colon, without `-> type`,
/// and the line it ends on.
fn def_at(lines: &[&str], i: usize, prefix: &str) -> (String, usize) {
    let mut sig = lines[i].trim_start().strip_prefix(prefix.trim_start()).unwrap_or("").to_string();
    let mut j = i;
    while !sig.trim_end().ends_with(':') && j + 1 < lines.len() {
        j += 1;
        sig.push(' ');
        sig.push_str(lines[j].trim());
    }
    let sig = sig.trim_end().trim_end_matches(':').trim().to_string();
    let sig = sig.split(" -> ").next().unwrap_or(&sig).replace("( ", "(").replace(", )", ")");
    (sig, j)
}

/// [sig] without its first parameter, `self` or `cls`.
fn without_first(sig: &str) -> String {
    let Some(open) = sig.find('(') else { return sig.to_string() };
    let inner = &sig[open + 1..sig.len().saturating_sub(1)];
    let rest = match inner.split_once(',') {
        Some((_, rest)) => rest.trim().to_string(),
        None => String::new(),
    };
    format!("{}({rest})", &sig[..open])
}

fn ident(text: &str) -> String {
    text.chars().take_while(|c| c.is_alphanumeric() || *c == '_').collect()
}

/// A class's body: its methods and properties, the attributes its
/// `__init__` sets, and the `__init__` signature without `self`.
fn scan_class(body: &[&str]) -> (Vec<LibItem>, Option<String>) {
    let mut members: Vec<LibItem> = Vec::new();
    let mut init = None;
    let mut decorators: Vec<String> = Vec::new();
    let add = |members: &mut Vec<LibItem>, item: LibItem| match members.iter_mut().find(|m| m.name == item.name) {
        Some(m) if m.doc.len() < 8 && item.doc.len() >= 8 => {
            m.doc = item.doc;
            m.kind = item.kind;
            m.signature = item.signature;
        }
        Some(_) => {}
        None => members.push(item),
    };
    let mut k = 0;
    while k < body.len() {
        let line = body[k];
        let Some(rest) = line.strip_prefix("    ") else {
            k += 1;
            continue;
        };
        if rest.starts_with(' ') || rest.starts_with('#') {
            k += 1;
            continue;
        }
        if let Some(d) = rest.strip_prefix('@') {
            decorators.push(d.trim().to_string());
            k += 1;
            continue;
        }
        if rest.starts_with("def ") {
            let (sig, j) = def_at(body, k, "def ");
            let name = ident(&sig);
            let doc = docstring(&body[j + 1..]);
            let property = decorators.iter().any(|d| d == "property" || d.ends_with(".setter") || d.ends_with(".getter"));
            let params = if decorators.iter().any(|d| d == "staticmethod") { sig.clone() } else { without_first(&sig) };
            if name == "__init__" {
                init = Some(params.trim_start_matches("__init__").to_string());
                // The attributes it sets.
                let mut m = j + 1;
                while m < body.len() && (body[m].trim().is_empty() || body[m].starts_with("        ")) {
                    if let Some(attr) = body[m].trim().strip_prefix("self.") {
                        let attr = ident(attr);
                        if public(&attr) && !attr.starts_with('_') && body[m].trim()[5 + attr.len()..].trim_start().starts_with('=') {
                            add(&mut members, LibItem::new(&attr, ApiKind::Constant, None, String::new()));
                        }
                    }
                    m += 1;
                }
            } else if !name.starts_with('_') {
                let item = if property { LibItem::new(&name, ApiKind::Constant, None, doc) } else { LibItem::new(&name, ApiKind::Function, Some(params), doc) };
                add(&mut members, item);
            }
            decorators.clear();
            k = j + 1;
            continue;
        }
        decorators.clear();
        if let Some((left, right)) = rest.split_once('=') {
            let name = left.trim();
            if !right.starts_with('=') && !name.is_empty() && name.chars().all(|c| c.is_alphanumeric() || c == '_') && !name.starts_with('_') {
                add(&mut members, LibItem::new(name, ApiKind::Constant, None, String::new()));
            }
        }
        k += 1;
    }
    (members, init)
}

/// A library module's top level, read from its source as [scan] reads the
/// package's, classes with their bodies.
fn scan_library(source: &str) -> Vec<LibItem> {
    let lines: Vec<&str> = source.lines().collect();
    let mut out: Vec<LibItem> = Vec::new();
    let mut seen = std::collections::HashSet::new();
    let mut i = 0;
    while i < lines.len() {
        let line = lines[i];
        if line.starts_with("def ") {
            let (sig, j) = def_at(&lines, i, "def ");
            let name = ident(&sig);
            if !name.starts_with('_') && seen.insert(name.clone()) {
                out.push(LibItem::new(&name, ApiKind::Function, Some(sig), docstring(&lines[j + 1..])));
            }
            i = j + 1;
            continue;
        }
        if let Some(rest) = line.strip_prefix("class ") {
            let name = ident(rest);
            let base = rest.split_once('(').map(|(_, b)| b.split([',', ')']).next().unwrap_or("").trim().to_string()).filter(|b| !b.is_empty());
            let mut j = i + 1;
            while j < lines.len() && (lines[j].trim().is_empty() || lines[j].starts_with(' ')) {
                j += 1;
            }
            let (members, init) = scan_class(&lines[i + 1..j]);
            if !name.starts_with('_') && seen.insert(name.clone()) {
                let mut item = LibItem::new(&name, ApiKind::Type, init.map(|p| format!("{name}{p}")), docstring(&lines[i + 1..]));
                item.base = base;
                item.members = members;
                out.push(item);
            }
            i = j;
            continue;
        }
        if let Some(rest) = line.strip_prefix("from ") {
            if let Some((module, names)) = rest.split_once(" import ") {
                let mut names = names.to_string();
                let mut j = i;
                if names.trim_start().starts_with('(') {
                    while !names.contains(')') && j + 1 < lines.len() {
                        j += 1;
                        names.push(' ');
                        names.push_str(lines[j].trim());
                    }
                    names = names.replace(['(', ')'], "");
                }
                for part in names.split(',') {
                    let part = part.trim();
                    let (original, alias) = match part.split_once(" as ") {
                        Some((o, a)) => (o.trim(), a.trim()),
                        None => (part, part),
                    };
                    if !alias.is_empty() && !alias.starts_with('_') && seen.insert(alias.to_string()) {
                        let mut item = LibItem::new(alias, ApiKind::Constant, None, String::new());
                        item.from = Some((module.trim().to_string(), original.to_string()));
                        out.push(item);
                    }
                }
                i = j + 1;
                continue;
            }
        }
        if line.starts_with(|c: char| c.is_ascii_alphabetic()) && !line.starts_with("import ") && !line.starts_with("for ") && !line.starts_with("if ") {
            if let Some((left, right)) = line.split_once('=') {
                let name = left.trim();
                if !right.starts_with('=') && !name.is_empty() && name.chars().all(|c| c.is_alphanumeric() || c == '_') && seen.insert(name.to_string()) {
                    let doc = i.checked_sub(1).and_then(|p| lines[p].trim().strip_prefix("# ")).unwrap_or("").to_string();
                    out.push(LibItem::new(name, ApiKind::Constant, None, doc));
                }
            }
        }
        i += 1;
    }
    out
}

/// Every library module's items, re-exports resolved to what they name.
fn library_items() -> &'static Vec<(&'static str, Vec<LibItem>)> {
    static ITEMS: std::sync::OnceLock<Vec<(&'static str, Vec<LibItem>)>> = std::sync::OnceLock::new();
    ITEMS.get_or_init(|| {
        let raw: Vec<(&str, Vec<LibItem>)> = LIBRARIES.iter().chain(LIBRARY_HELPERS).map(|(m, s)| (*m, scan_library(s))).collect();
        let find = |module: &str, name: &str| raw.iter().find(|(m, _)| *m == module).and_then(|(_, items)| items.iter().find(|i| i.name == name)).cloned();
        let mut resolved = Vec::new();
        for (module, items) in &raw {
            if !is_library(module) {
                continue;
            }
            let mut out = Vec::new();
            for item in items {
                match &item.from {
                    // `from requests import utils`: the submodule, which has its own entry.
                    Some((from, original)) if is_library(&format!("{from}.{original}")) => {}
                    Some((from, original)) => {
                        // Followed through each module that passes it on.
                        let mut target = find(from, original);
                        let mut hops = 0;
                        while let Some(Some((f, o))) = target.as_ref().map(|t| t.from.clone()) {
                            hops += 1;
                            if hops > 4 {
                                break;
                            }
                            target = find(&f, &o);
                        }
                        if let Some(mut t) = target.filter(|t| t.from.is_none()) {
                            t.name = item.name.clone();
                            out.push(t);
                        } else {
                            out.push(LibItem { from: None, ..item.clone() });
                        }
                    }
                    None => out.push(item.clone()),
                }
            }
            resolved.push((*module, out));
        }
        // A class is an exception when its base is one, here or where it came from.
        let mut exceptions: std::collections::HashSet<String> = crate::api::EXCEPTIONS.iter().map(|s| s.to_string()).collect();
        exceptions.insert("BaseException".into());
        loop {
            let mut changed = false;
            for (_, items) in &resolved {
                for i in items {
                    if i.kind == ApiKind::Type && i.base.as_ref().is_some_and(|b| exceptions.contains(b.rsplit('.').next().unwrap_or(b))) && exceptions.insert(i.name.clone()) {
                        changed = true;
                    }
                }
            }
            if !changed {
                break;
            }
        }
        for (_, items) in resolved.iter_mut() {
            for i in items.iter_mut() {
                if i.kind == ApiKind::Type && exceptions.contains(&i.name) && i.base.is_some() {
                    i.kind = ApiKind::Exception;
                }
            }
        }
        resolved
    })
}

/// The class [path] (`requests.exceptions.HTTPError`) derives from, as its source writes it.
pub fn library_base(path: &str) -> Option<&'static str> {
    let (module, name) = path.rsplit_once('.')?;
    library_items().iter().find(|(m, _)| *m == module)?.1.iter().find(|i| i.name == name)?.base.as_deref()
}

/// The entries of every library module, their classes' members included.
pub fn library_entries(leak: &dyn Fn(String) -> &'static str) -> Vec<ApiEntry> {
    let mut out = Vec::new();
    for (module, items) in library_items() {
        let lib = if module.starts_with("urllib.") { *module } else { module.split('.').next().unwrap_or(module) };
        let permission = library_permission(module);
        let source = LIBRARIES.iter().find(|(m, _)| m == module).map(|(_, s)| *s).unwrap_or("");
        let doc = module_doc(source);
        out.push(ApiEntry {
            path: module,
            kind: ApiKind::Module,
            signature: None,
            permission,
            doc: leak(if doc.len() >= 8 { doc } else { format!("The {module} module, as in {lib}.") }),
        });
        for item in items {
            push_library_item(&mut out, module, item, lib, permission, leak);
        }
    }
    out
}

fn push_library_item(out: &mut Vec<ApiEntry>, parent: &str, item: &LibItem, lib: &str, permission: Option<&'static str>, leak: &dyn Fn(String) -> &'static str) {
    let path = format!("{parent}.{}", item.name);
    let doc = if item.doc.len() >= 8 {
        item.doc.clone()
    } else {
        LIBRARY_DOCS.iter().find(|(p, _)| *p == path).map(|(_, d)| d.to_string()).unwrap_or_else(|| format!("As in {lib}."))
    };
    // A name bound to another function (`Headers = CaseInsensitiveDict`) stays a name.
    let kind = if item.kind == ApiKind::Function && item.signature.is_none() { ApiKind::Constant } else { item.kind };
    out.push(ApiEntry { path: leak(path.clone()), kind, signature: item.signature.clone().map(leak), permission, doc: leak(doc) });
    for m in &item.members {
        push_library_item(out, &path, m, lib, permission, leak);
    }
}
