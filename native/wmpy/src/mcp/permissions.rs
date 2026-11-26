//! Every permission a plugin.json may declare: its tier and API level from
//! the importer's own table (`wm_python_tooling::manifest::PERMISSIONS`, a
//! port of PluginPermission.kt), and what it gives and the warning the user
//! reads, from docs/src/content/docs/plugins/permissions.mdx. The language
//! server completes and explains these names, and the MCP server lists them.

use wm_python_tooling::manifest as codec;

/// How a permission reaches the plugin once it is installed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tier {
    /// Applies on install, shown in the list before install.
    Listed,
    /// Off after install until the user turns it on, behind a warning.
    Risky,
}

impl Tier {
    pub fn name(self) -> &'static str {
        match self {
            Tier::Listed => "listed",
            Tier::Risky => "risky",
        }
    }
}

pub struct Permission {
    pub name: &'static str,
    pub tier: Tier,
    /// The API level that brought it.
    pub since_api: i64,
    /// What it gives the plugin.
    pub gives: &'static str,
    /// The warning the user reads before turning a risky one on.
    pub warning: Option<&'static str>,
}

/// What each permission gives, and the warning a risky one shows.
const WORDS: &[(&str, &str, Option<&str>)] = &[
    ("storage", "wm.storage and wm.secrets: up to 512 keys, 64 KB a value, 1 MB in all, on the device only. Also plugin.state(persist=True).", None),
    ("reminders", "wm.reminders: a line of text the app shows in a notification at a set time (64 pending, 200 characters). No plugin code runs when it fires.", None),
    ("selection", "Buttons over selected text, listed under \"actions\"; the plugin sees the selection only when the user taps one.", None),
    ("field.write", "wm.field writes: type, replace the selection, delete, move the cursor, press the action key. Refused in password fields and incognito.", None),
    ("clipboard.write", "clipboard.set(text).", None),
    ("sensors", "wm.sensors: motion, light, pressure and step readings while the panel is open.", None),
    ("scan", "wm.scan: the keyboard's QR and text scanners; the plugin gets the result, never the camera.", None),
    ("device", "wm.device: vibrate, flashlight, read text aloud, battery level.", None),
    ("share", "wm.share: hand text to another app or open a link; the keyboard shows what goes where first.", None),
    ("notifications", "wm.notify: a notification titled with the plugin's name.", None),
    ("clock", "wm.clock: set an alarm or start a timer in the keyboard's Clock.", None),
    ("previews", "Show files in the keyboard's file manager; needed exactly when the manifest lists \"previews\".", None),
    ("network", "wm.http: https requests to the hosts in \"hosts\" (or the server a host setting names). 1 MB replies, 15 s, 60 requests a minute.", Some("Data you give the plugin can go to those websites, which see your IP address.")),
    ("network.local", "With network and a host setting: the server the user typed may be on the local network, over http or https.", Some("The plugin can connect to the server you type into its settings on your local network, and use plain http there, where anyone else on that network can read what is sent, including passwords and tokens.")),
    ("ai", "wm.ai: the AI model the user set up.", Some("Text goes to that model, online or on the phone.")),
    ("field.read", "field.selection(), field.text_before(), field.text_after(), field.info(), only while answering something the user did in the panel.", Some("The plugin can read what you are writing, each time you tap something in its panel.")),
    ("clipboard.read", "clipboard.get(), only while answering something the user did in the panel.", Some("The plugin can read what you copied, each time you tap something in its panel.")),
    ("location", "location.get() while the panel is open.", Some("The plugin can find out where your phone is while its panel is open.")),
    ("files", "files.pick(), files.photo(), files.save().", Some("The plugin can read every file you pick for it, and every photo you take for it.")),
    ("calendar", "wm.calendar: events(), calendars(), add().", Some("The plugin can read the events in your phone's calendars while its panel is open, and add new events to them.")),
    ("todo", "wm.todo: the keyboard's To-do list.", Some("The plugin can read every task in the keyboard's To-do list, and add, tick, change and delete tasks.")),
];

fn of(p: &codec::Permission) -> Permission {
    let (gives, warning) = WORDS.iter().find(|w| w.0 == p.wire).map_or(("", None), |w| (w.1, w.2));
    Permission { name: p.wire, tier: if p.risky { Tier::Risky } else { Tier::Listed }, since_api: p.since_api, gives, warning }
}

/// Every permission, in the order the manifest form lists them.
pub fn all() -> Vec<Permission> {
    codec::PERMISSIONS.iter().map(of).collect()
}

pub fn find(name: &str) -> Option<Permission> {
    codec::permission(name).map(of)
}

/// The permission as JSON, for tools and completion docs.
pub fn json(p: &Permission) -> serde_json::Value {
    serde_json::json!({
        "name": p.name, "tier": p.tier.name(), "sinceApi": p.since_api,
        "gives": p.gives, "warning": p.warning,
    })
}

/// A paragraph of Markdown about [p], for hovers and completion docs.
pub fn markdown(p: &Permission) -> String {
    let tier = match p.tier {
        Tier::Listed => "Listed: applies as soon as the plugin is installed.",
        Tier::Risky => "Risky: off after install until the user turns it on for this plugin. A call before that raises PermissionError.",
    };
    let api = if p.since_api >= 5 { " Needs `\"apiVersion\": 5`." } else { "" };
    let mut out = format!("`{}`: {}\n\n{tier}{api}", p.name, p.gives);
    if let Some(w) = p.warning {
        out.push_str(&format!("\n\nThe user reads: \"{w}\""));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_permission_has_words() {
        for p in all() {
            assert!(!p.gives.is_empty(), "{} has no words", p.name);
            assert_eq!(p.warning.is_some(), p.tier == Tier::Risky, "{}", p.name);
        }
        assert_eq!(WORDS.len(), codec::PERMISSIONS.len());
    }
}
