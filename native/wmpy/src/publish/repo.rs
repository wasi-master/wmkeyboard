//! `wmpy publish --repo <path>`: the plugin in your own addon repository.
//! The archive goes to `<path>/plugins/<id>.wmplugin` and its entry into
//! `<path>/wmkeyboard-repo.json`, with the version, sha256 and size of the
//! file now there. The rest of the manifest is kept as it was, entries in
//! their order and the file's indent, and nothing is committed: wmpy prints
//! the git commands instead.

use std::path::Path;

use serde_json::{Map, Value as Json};
use sha2::{Digest, Sha256};

pub const MANIFEST: &str = "wmkeyboard-repo.json";

pub struct Options<'a> {
    /// The entry `wmpy pack` wrote for the archive.
    pub entry: &'a Json,
    /// The id to publish under instead of the entry's.
    pub id: Option<&'a str>,
    pub licence: Option<&'a str>,
    pub tags: &'a [String],
    pub description: Option<&'a str>,
    pub dry_run: bool,
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// An entry id the app takes: file-name-like, and never the `@` it joins variants with.
fn is_entry_id(id: &str) -> bool {
    let mut chars = id.chars();
    chars.next().is_some_and(|c| c.is_ascii_alphanumeric()) && id.len() <= 128 && chars.all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'))
}

/// The indent of the manifest's first indented line, so a rewrite keeps the file's look.
fn indent_of(text: &str) -> String {
    text.lines().nth(1).map(|l| l.chars().take_while(|c| *c == ' ' || *c == '\t').collect::<String>()).filter(|s| !s.is_empty()).unwrap_or_else(|| "  ".into())
}

/// [value] laid out as serde_json's pretty printer does, with [indent] per level.
fn pretty(value: &Json, indent: &str) -> String {
    fn write(out: &mut String, value: &Json, indent: &str, depth: usize) {
        let pad = |out: &mut String, d: usize| (0..d).for_each(|_| out.push_str(indent));
        match value {
            Json::Array(items) if !items.is_empty() => {
                out.push_str("[\n");
                for (i, item) in items.iter().enumerate() {
                    pad(out, depth + 1);
                    write(out, item, indent, depth + 1);
                    out.push_str(if i + 1 < items.len() { ",\n" } else { "\n" });
                }
                pad(out, depth);
                out.push(']');
            }
            Json::Object(map) if !map.is_empty() => {
                out.push_str("{\n");
                for (i, (k, v)) in map.iter().enumerate() {
                    pad(out, depth + 1);
                    out.push_str(&Json::from(k.as_str()).to_string());
                    out.push_str(": ");
                    write(out, v, indent, depth + 1);
                    out.push_str(if i + 1 < map.len() { ",\n" } else { "\n" });
                }
                pad(out, depth);
                out.push('}');
            }
            other => out.push_str(&other.to_string()),
        }
    }
    let mut out = String::new();
    write(&mut out, value, indent, 0);
    out
}

/// MAJOR.MINOR.PATCH as numbers, missing parts as zero.
fn version_parts(v: &str) -> Vec<u64> {
    v.trim().trim_start_matches(['v', 'V']).split(['.', '-', '+']).take(3).map(|p| p.parse().unwrap_or(0)).collect()
}

/// Copies [archive] into the repository at [root] and writes its entry. The exit code.
pub fn publish(root: &Path, archive: &Path, opts: &Options) -> u8 {
    let manifest_path = root.join(MANIFEST);
    let text = match std::fs::read_to_string(&manifest_path) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("error: cannot read {}: {e}", manifest_path.display());
            eprintln!("  = help: --repo takes the folder of an addon repository, the one holding {MANIFEST}; /development/addon-repos/ in the docs shows how to start one");
            return 2;
        }
    };
    let mut manifest: Json = match serde_json::from_str(&text) {
        Ok(m) => m,
        Err(e) => {
            eprintln!("error: {} is not valid JSON: {e}", manifest_path.display());
            return 2;
        }
    };
    if manifest.get("format").and_then(|f| f.as_str()) != Some("wmkeyboard-repo") || !manifest.get("addons").is_some_and(|a| a.is_array()) {
        eprintln!("error: {} is not an addon repository manifest (it needs \"format\": \"wmkeyboard-repo\" and an \"addons\" list)", manifest_path.display());
        return 2;
    }
    let bytes = match std::fs::read(archive) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("error: cannot read {}: {e}", archive.display());
            return 2;
        }
    };
    let id = opts.id.map(String::from).or_else(|| opts.entry.get("id").and_then(|i| i.as_str()).map(String::from)).unwrap_or_default();
    if !is_entry_id(&id) {
        eprintln!("error: \"{id}\" is not an id an addon repository takes: letters, digits, '.', '_' and '-', starting with a letter or digit");
        return 2;
    }

    let addons = manifest["addons"].as_array_mut().expect("checked above");
    let at = addons.iter().position(|a| a.get("id").and_then(|i| i.as_str()) == Some(id.as_str()));
    if let Some(i) = at {
        if addons[i].get("type").and_then(|t| t.as_str()) != Some("plugin") {
            eprintln!("error: {MANIFEST} already lists \"{id}\" as a {}; pass --update <another-id>", addons[i].get("type").and_then(|t| t.as_str()).unwrap_or("different addon"));
            return 1;
        }
    }
    let previous = at.map(|i| addons[i].clone());

    // The entry as it was, then what pack says now, then the file's own facts.
    let path = format!("plugins/{id}.wmplugin");
    let mut entry: Map<String, Json> = previous.as_ref().and_then(|p| p.as_object().cloned()).unwrap_or_default();
    if let Some(fresh) = opts.entry.as_object() {
        // In pack's order, so a new entry reads as the docs show one; the path is ours.
        for (k, v) in fresh {
            entry.insert(k.clone(), if k == "path" { Json::from(path.clone()) } else { v.clone() });
        }
        for derived in ["fileTypes", "fileNames"] {
            if !fresh.contains_key(derived) {
                entry.remove(derived);
            }
        }
    }
    entry.insert("id".into(), Json::from(id.clone()));
    entry.insert("type".into(), Json::from("plugin"));
    entry.insert("path".into(), Json::from(path.clone()));
    entry.insert("sha256".into(), Json::from(hex(&Sha256::digest(&bytes))));
    entry.insert("sizeBytes".into(), Json::from(bytes.len()));
    if let Some(d) = opts.description.filter(|d| !d.trim().is_empty()) {
        entry.insert("description".into(), Json::from(d.trim()));
    }
    if !opts.tags.is_empty() {
        entry.insert("tags".into(), Json::from(opts.tags.to_vec()));
    }
    if let Some(l) = opts.licence {
        entry.insert("license".into(), Json::from(l));
    }
    let version = entry.get("version").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let name = entry.get("name").and_then(|v| v.as_str()).unwrap_or(&id).to_string();

    let old_version = previous.as_ref().and_then(|p| p.get("version")).and_then(|v| v.as_str()).unwrap_or("").to_string();
    let old_path = previous.as_ref().and_then(|p| p.get("path")).and_then(|v| v.as_str()).unwrap_or("").to_string();
    if previous.is_some() && version_parts(&version) <= version_parts(&old_version) {
        eprintln!("warning: {MANIFEST} already lists {id} {old_version}, and this is {version}: phones only offer an update for a higher version, so raise pluginVersion in plugin.json");
    }
    if !entry.contains_key("license") {
        eprintln!("note: the entry has no licence; pass --licence MIT (or the SPDX id of yours) so the plugin's page shows one");
    }

    let entry = Json::Object(entry);
    match at {
        Some(i) => addons[i] = entry.clone(),
        None => addons.push(entry.clone()),
    }
    let mut out = pretty(&manifest, &indent_of(&text));
    if text.ends_with('\n') {
        out.push('\n');
    }

    let verb = if previous.is_some() { format!("update {id} from {old_version} to {version}") } else { format!("add {id} {version}") };
    if opts.dry_run {
        eprintln!("would {verb} in {}: copy {} to {path} and write this entry:", root.display(), archive.display());
        println!("{}", pretty(&entry, "  "));
        return 0;
    }
    let target = root.join(&path);
    if let Err(e) = std::fs::create_dir_all(root.join("plugins")).and_then(|_| std::fs::write(&target, &bytes)) {
        eprintln!("error: cannot write {}: {e}", target.display());
        return 2;
    }
    if let Err(e) = std::fs::write(&manifest_path, out) {
        eprintln!("error: cannot write {}: {e}", manifest_path.display());
        return 2;
    }
    eprintln!("{}: {verb}, {} bytes, sha256 {}", root.display(), bytes.len(), entry["sha256"].as_str().unwrap_or(""));
    let stale = (!old_path.is_empty() && old_path != path && root.join(&old_path).is_file()).then_some(old_path);
    if let Some(old) = &stale {
        eprintln!("note: the entry used to point at {old}; nothing reads that file now");
    }
    eprintln!();
    eprintln!("Nothing is committed. To publish it:");
    eprintln!("  cd {}", shell_quote(&root.display().to_string()));
    if let Some(old) = &stale {
        eprintln!("  git rm {}", shell_quote(old));
    }
    eprintln!("  git add {path} {MANIFEST}");
    eprintln!("  git commit -m {}", shell_quote(&format!("Publish {name} {version}")));
    eprintln!("  git push");
    eprintln!("Phones that added this repository see it after the push; ones with an older version are offered the update.");
    0
}

fn shell_quote(s: &str) -> String {
    if s.chars().all(|c| c.is_ascii_alphanumeric() || "/._-+:=@".contains(c)) {
        s.to_string()
    } else {
        format!("'{}'", s.replace('\'', "'\\''"))
    }
}
