//! What the development host needs from plugin.json beyond what
//! [crate::project::Manifest] reads: the name, the hosts `wm.http` may
//! reach, and the settings rows with the values a run starts from.
//!
//! A setting's value is, in order of strength: the manifest's default, the
//! text saved in `.wmpy/settings.json` (what `plugin.settings.set` wrote),
//! then `--set key=value` from the command line. The session reads them
//! typed as the keyboard types them (PluginStore.settingsJson).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde_json::{json, Map, Value as Json};

/// One row of the manifest's `settings`.
#[derive(Clone, Debug, PartialEq)]
pub struct SettingSpec {
    pub key: String,
    /// switch, text, number, choice, secret or host.
    pub kind: String,
    pub default: String,
    pub options: Vec<String>,
}

/// PluginHost.MAX_SETTING_TEXT and PluginSecrets.MAX_VALUE.
pub const MAX_SETTING_TEXT: usize = 1024;
pub const MAX_SECRET: usize = 4096;
pub const MAX_SECRETS: usize = 32;
pub const MAX_SECRET_NAME: usize = 64;

#[derive(Clone, Debug)]
pub struct PluginFacts {
    pub id: String,
    pub name: String,
    pub version: String,
    pub api: i64,
    pub panel: bool,
    pub permissions: Vec<String>,
    /// The manifest's `hosts`, lowercased, without `https://`.
    pub hosts: Vec<String>,
    pub settings: Vec<SettingSpec>,
}

impl PluginFacts {
    pub fn read(dir: &Path) -> Result<PluginFacts, String> {
        let m = crate::project::manifest(dir)?;
        let path = dir.join("plugin.json");
        let text = std::fs::read_to_string(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        let v: Json = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))?;
        let hosts = v
            .get("hosts")
            .and_then(Json::as_array)
            .map(|a| a.iter().filter_map(Json::as_str).map(normalise_host).filter(|h| !h.is_empty()).collect())
            .unwrap_or_default();
        let settings = v
            .get("settings")
            .and_then(Json::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(|s| {
                        let key = s.get("key")?.as_str()?.trim().to_string();
                        if key.is_empty() {
                            return None;
                        }
                        let kind = s.get("type").and_then(Json::as_str).unwrap_or("text").trim().to_string();
                        let default = match s.get("default") {
                            Some(Json::String(t)) => t.clone(),
                            Some(Json::Null) | None => String::new(),
                            Some(other) => other.to_string(),
                        };
                        let options = s
                            .get("options")
                            .and_then(Json::as_array)
                            .map(|o| o.iter().filter_map(|x| x.as_str().map(String::from)).collect())
                            .unwrap_or_default();
                        Some(SettingSpec { key, kind: if kind.is_empty() { "text".into() } else { kind }, default, options })
                    })
                    .collect()
            })
            .unwrap_or_default();
        let name = v.get("name").and_then(Json::as_str).map(str::trim).filter(|n| !n.is_empty()).unwrap_or(&m.id).to_string();
        Ok(PluginFacts { id: m.id, name, version: m.version, api: m.api, panel: m.panel, permissions: m.permissions, hosts, settings })
    }

    pub fn has(&self, permission: &str) -> bool {
        self.permissions.iter().any(|p| p.trim() == permission)
    }

    pub fn spec(&self, key: &str) -> Option<&SettingSpec> {
        self.settings.iter().find(|s| s.key == key)
    }
}

/// `https://Api.Example.com/` as `api.example.com`.
pub fn normalise_host(raw: &str) -> String {
    let h = raw.trim().to_ascii_lowercase();
    let h = h.strip_prefix("https://").unwrap_or(&h);
    h.trim_end_matches(['/', '.']).to_string()
}

/// The value a session reads for [spec] holding [raw]: PluginStore.settingsJson.
pub fn typed(spec: &SettingSpec, raw: &str) -> Json {
    match spec.kind.as_str() {
        "switch" => Json::Bool(raw.trim() == "true"),
        "number" => match raw.trim().parse::<f64>() {
            Ok(n) if n.is_finite() && n == n.floor() && n.abs() < 1e15 => json!(n as i64),
            Ok(n) if n.is_finite() => json!(n),
            _ => Json::Null,
        },
        "secret" => json!({"$secret": true}),
        _ => Json::String(raw.to_string()),
    }
}

/// PluginSettingSpec.storedText: the text kept for [value], or None when it does not fit the row.
pub fn stored_text(spec: &SettingSpec, value: &Json) -> Option<String> {
    match (spec.kind.as_str(), value) {
        ("switch", Json::Bool(b)) => Some(b.to_string()),
        ("number", Json::Number(n)) => {
            let f = n.as_f64()?;
            if !f.is_finite() {
                return None;
            }
            Some(if f == f.floor() && f.abs() < 1e15 { (f as i64).to_string() } else { f.to_string() })
        }
        ("choice", Json::String(s)) if spec.options.contains(s) => Some(s.clone()),
        ("text" | "secret", Json::String(s)) => Some(s.clone()),
        _ => None,
    }
}

/// The text values of every setting the user (or `--set`, or the plugin) changed.
pub struct SettingValues {
    path: PathBuf,
    /// Saved in `.wmpy/settings.json`.
    saved: BTreeMap<String, String>,
    /// From `--set`, which win over the file on every reload.
    overrides: BTreeMap<String, String>,
}

impl SettingValues {
    pub fn load(path: PathBuf, overrides: &[(String, String)]) -> SettingValues {
        let mut saved = BTreeMap::new();
        if let Ok(text) = std::fs::read_to_string(&path) {
            if let Ok(Json::Object(o)) = serde_json::from_str::<Json>(&text) {
                for (k, v) in o {
                    let text = match v {
                        Json::String(s) => s,
                        Json::Null => continue,
                        other => other.to_string(),
                    };
                    saved.insert(k, text);
                }
            }
        }
        SettingValues { path, saved, overrides: overrides.iter().cloned().collect() }
    }

    pub fn raw(&self, spec: &SettingSpec) -> String {
        self.overrides.get(&spec.key).or_else(|| self.saved.get(&spec.key)).cloned().unwrap_or_else(|| spec.default.clone())
    }

    /// The `settings` object for the session config.
    pub fn json(&self, facts: &PluginFacts) -> Json {
        let mut out = Map::new();
        for spec in &facts.settings {
            out.insert(spec.key.clone(), typed(spec, &self.raw(spec)));
        }
        Json::Object(out)
    }

    /// The hosts typed into `host` settings, as PluginStore.typedHosts reads them.
    pub fn typed_hosts(&self, facts: &PluginFacts) -> Vec<String> {
        facts.settings.iter().filter(|s| s.kind == "host").filter_map(|s| server_host(&self.raw(s))).collect()
    }

    /// The plugin's own write (`plugin.settings.set`); None resets to the default.
    pub fn set(&mut self, key: &str, text: Option<String>) {
        self.overrides.remove(key);
        match text {
            Some(t) => {
                self.saved.insert(key.to_string(), t);
            }
            None => {
                self.saved.remove(key);
            }
        }
        let body: Map<String, Json> = self.saved.iter().map(|(k, v)| (k.clone(), Json::String(v.clone()))).collect();
        if let Some(parent) = self.path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(&self.path, serde_json::to_string_pretty(&Json::Object(body)).unwrap_or_default() + "\n");
    }
}

/// PluginManifestCodec.serverHost: the host of a typed server address.
pub fn server_host(raw: &str) -> Option<String> {
    let text = raw.trim();
    if text.is_empty() {
        return None;
    }
    let with_scheme = if text.contains("://") { text.to_string() } else { format!("https://{text}") };
    let (scheme, rest) = with_scheme.split_once("://")?;
    let scheme = scheme.to_ascii_lowercase();
    if scheme != "https" && scheme != "http" {
        return None;
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    if authority.contains('@') {
        return None;
    }
    let host = match authority.rsplit_once(':') {
        Some((h, port)) if port.chars().all(|c| c.is_ascii_digit()) => h,
        _ => authority,
    };
    let host = host.to_ascii_lowercase().trim_end_matches('.').to_string();
    (is_local_host(&host) || is_valid_host(&host)).then_some(host)
}

/// PluginManifestCodec.isValidHost: dotted labels, a letter-led last label.
pub fn is_valid_host(host: &str) -> bool {
    if host.len() > 253 || host.is_empty() {
        return false;
    }
    let parts: Vec<&str> = host.split('.').collect();
    if parts.len() < 2 {
        return false;
    }
    let label_ok = |p: &str| {
        !p.is_empty()
            && p.len() <= 63
            && p.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
            && !p.starts_with('-')
            && !p.ends_with('-')
    };
    parts.iter().all(|p| label_ok(p)) && parts.last().is_some_and(|l| l.chars().next().is_some_and(|c| c.is_ascii_alphabetic()))
}

/// PluginManifestCodec.isLocalHost: a server on the local network, which
/// only `network.local` reaches, and only when typed into a host setting.
pub fn is_local_host(host: &str) -> bool {
    let h = host.to_ascii_lowercase();
    let h = h.trim_end_matches('.');
    if h.is_empty() || h.starts_with('[') || h.contains(':') || h == "localhost" || h.ends_with(".localhost") {
        return false;
    }
    let parts: Vec<&str> = h.split('.').collect();
    if parts.len() == 4 && parts.iter().all(|p| !p.is_empty() && p.len() <= 3 && p.chars().all(|c| c.is_ascii_digit())) {
        let n: Vec<u32> = parts.iter().map(|p| p.parse().unwrap_or(999)).collect();
        if n.iter().any(|x| *x > 255) {
            return false;
        }
        return n[0] == 10
            || (n[0] == 172 && (16..=31).contains(&n[1]))
            || (n[0] == 192 && n[1] == 168)
            || (n[0] == 100 && (64..=127).contains(&n[1]))
            || (n[0] == 169 && n[1] == 254);
    }
    let label = |p: &str| {
        !p.is_empty() && p.len() <= 63 && p.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') && !p.starts_with('-') && !p.ends_with('-')
    };
    if !parts.iter().all(|p| label(p)) {
        return false;
    }
    if parts.len() == 1 {
        return !h.chars().all(|c| c.is_ascii_digit());
    }
    ["local", "lan", "home", "home.arpa", "internal", "localdomain"].iter().any(|s| h.ends_with(&format!(".{s}")))
}

/// PluginManifestCodec.hostAllowed: exactly, or under a `*.` entry.
pub fn host_allowed(host: &str, allowed: &[String]) -> bool {
    let h = host.to_ascii_lowercase();
    let h = h.trim_end_matches('.');
    allowed.iter().any(|entry| match entry.strip_prefix("*.") {
        Some(base) => h == base || h.ends_with(&format!(".{base}")),
        None => h == entry,
    })
}

/// A plugin's secrets, kept in `.wmpy/secrets.json` as `{"name": "value"}`,
/// with PluginSecrets' limits and refusals. `--set` on a secret row puts a
/// value in memory only.
pub struct Secrets {
    path: PathBuf,
    memory: BTreeMap<String, String>,
}

impl Secrets {
    pub fn new(path: PathBuf, overrides: BTreeMap<String, String>) -> Secrets {
        Secrets { path, memory: overrides }
    }

    fn read(&self) -> BTreeMap<String, String> {
        let mut out = BTreeMap::new();
        if let Ok(text) = std::fs::read_to_string(&self.path) {
            if let Ok(Json::Object(o)) = serde_json::from_str::<Json>(&text) {
                for (k, v) in o {
                    if let Json::String(s) = v {
                        out.insert(k, s);
                    }
                }
            }
        }
        out
    }

    pub fn get(&self, name: &str) -> Option<String> {
        self.memory.get(name).cloned().or_else(|| self.read().get(name).cloned())
    }

    pub fn set(&mut self, name: &str, value: &str) -> Result<(), String> {
        if name.is_empty() || name.chars().count() > MAX_SECRET_NAME {
            return Err(format!("secret names are 1 to {MAX_SECRET_NAME} characters"));
        }
        if value.encode_utf16().count() > MAX_SECRET {
            return Err(format!("a secret can't be longer than {MAX_SECRET} characters"));
        }
        let mut all = self.read();
        if !all.contains_key(name) && all.len() >= MAX_SECRETS {
            return Err(format!("this plugin already keeps {MAX_SECRETS} secrets"));
        }
        self.memory.remove(name);
        all.insert(name.to_string(), value.to_string());
        self.write(&all);
        Ok(())
    }

    pub fn remove(&mut self, name: &str) {
        self.memory.remove(name);
        let mut all = self.read();
        if all.remove(name).is_some() {
            self.write(&all);
        }
    }

    /// The names kept, for the storage pane (values stay hidden).
    pub fn names(&self) -> Vec<String> {
        let mut names: Vec<String> = self.read().into_keys().collect();
        for k in self.memory.keys() {
            if !names.contains(k) {
                names.push(k.clone());
            }
        }
        names.sort();
        names
    }

    fn write(&self, all: &BTreeMap<String, String>) {
        if let Some(parent) = self.path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let body: Map<String, Json> = all.iter().map(|(k, v)| (k.clone(), Json::String(v.clone()))).collect();
        let _ = std::fs::write(&self.path, serde_json::to_string_pretty(&Json::Object(body)).unwrap_or_default() + "\n");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hosts_are_matched_as_the_keyboard_matches_them() {
        let allowed = vec!["api.example.com".to_string(), "*.shop.example".to_string()];
        assert!(host_allowed("API.example.com", &allowed));
        assert!(host_allowed("eu.shop.example", &allowed));
        assert!(host_allowed("shop.example", &allowed));
        assert!(!host_allowed("example.com", &allowed));
        assert!(is_local_host("192.168.1.20"));
        assert!(is_local_host("nas.local"));
        assert!(is_local_host("homeassistant"));
        assert!(!is_local_host("8.8.8.8"));
        assert!(!is_local_host("localhost"));
        assert_eq!(server_host("https://Money.Example.com:8443/firefly").as_deref(), Some("money.example.com"));
        assert_eq!(server_host("192.168.1.5:8123").as_deref(), Some("192.168.1.5"));
        assert_eq!(server_host("ftp://x.example.com"), None);
    }

    #[test]
    fn settings_are_typed_like_the_store_types_them() {
        let spec = |kind: &str| SettingSpec { key: "k".into(), kind: kind.into(), default: String::new(), options: vec!["metric".into()] };
        assert_eq!(typed(&spec("switch"), "true"), json!(true));
        assert_eq!(typed(&spec("number"), "12"), json!(12));
        assert_eq!(typed(&spec("number"), "1.5"), json!(1.5));
        assert_eq!(typed(&spec("number"), "x"), Json::Null);
        assert_eq!(typed(&spec("secret"), "s"), json!({"$secret": true}));
        assert_eq!(stored_text(&spec("choice"), &json!("metric")).as_deref(), Some("metric"));
        assert_eq!(stored_text(&spec("choice"), &json!("imperial")), None);
        assert_eq!(stored_text(&spec("switch"), &json!("true")), None);
    }
}
