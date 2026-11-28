//! `wmpy pack`: the `.wmplugin` file, as the app's `PluginFile.write` lays
//! it out: plugin.json, the entry script, and for API 5 the other modules,
//! `assets/` and `locales/`; and `licenses/`, the vendored packages' licence
//! texts, which the app accepts and leaves out of the install. Tests,
//! dotfiles, `__pycache__`, the editor's
//! stubs and settings, `wmpy.lock` and built archives stay out
//! ([wm_python_tooling::manifest::packed_role]).
//!
//! The archive is deterministic: plugin.json first, the entry second, the
//! rest sorted, every entry dated 1980-01-01, so packing the same files gives
//! the same bytes and the same sha256 an addon repository lists.
//!
//! Before packing it checks the folder as `wmpy check` does and refuses on an
//! error (`--no-check` skips that), then tree-shakes the packages `wmpy pip`
//! vendored (`--no-shake` skips that).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde_json::{json, Value as Json};
use sha2::{Digest, Sha256};
use wm_python_tooling::manifest as codec;

use crate::diag::{color_stderr, Diag};
use crate::project;

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// Where to write the archive [default: <Name>-<version>.wmplugin in the plugin folder, or in wmpy.toml's pack.output]
    #[arg(short, long)]
    pub output: Option<PathBuf>,
    /// Pack without checking the plugin first
    #[arg(long)]
    pub no_check: bool,
    /// Keep vendored packages whole
    #[arg(long)]
    pub no_shake: bool,
    /// Also drop unused functions and classes inside the vendored modules that stay
    #[arg(long)]
    pub shake_symbols: bool,
    /// Print {"path", "size", "sha256", "entry", "shake"} as JSON on stdout
    #[arg(long)]
    pub json: bool,
    /// Print only the addon repository entry for the archive, as JSON
    #[arg(long)]
    pub entry_json: bool,
}

pub fn run(args: Args) -> u8 {
    let dir = match project::plugin_dir(&args.dir) {
        Ok(d) => d,
        Err(d) => return fail(&d, 2),
    };
    let config = match crate::config::load_or_report(&dir) {
        Ok(c) => c,
        Err(code) => return code,
    };
    let mut args = args;
    args.shake_symbols |= config.pack.shake_symbols.unwrap_or(false);
    if !args.no_check {
        let outcome = crate::check::check_dir(&dir);
        if outcome.errors() > 0 {
            let source = crate::diag::folder_source(&dir);
            let color = color_stderr();
            for d in outcome.problems.iter().filter(|d| d.severity == crate::diag::Severity::Error) {
                eprint!("{}", d.render(&source, color));
                eprintln!();
            }
            let n = outcome.errors();
            eprintln!("not packed: {n} {} (run `wmpy check` for all of it, or pack anyway with --no-check)", if n == 1 { "error" } else { "errors" });
            return 1;
        }
    }
    let text = match std::fs::read_to_string(dir.join(codec::MANIFEST)) {
        Ok(t) => t,
        Err(e) => return fail(&Diag::error(format!("cannot read plugin.json: {e}")), 2),
    };
    let Some(m) = codec::check(&text).manifest else {
        return fail(&Diag::error("plugin.json is not a JSON object, so there is nothing to pack"), 1);
    };
    let packed = match pack(&dir, &m, !args.no_shake, args.shake_symbols) {
        Ok(p) => p,
        Err(e) => return fail(&e, 1),
    };
    // -o, else wmpy.toml's pack.output (a folder, or a .wmplugin file), else beside plugin.json.
    let out = match (&args.output, &config.pack.output) {
        (Some(o), _) => o.clone(),
        (None, Some(o)) => {
            let o = crate::config::in_dir(&dir, o);
            if o.extension().is_some_and(|e| e == "wmplugin") {
                o
            } else {
                if let Err(e) = std::fs::create_dir_all(&o) {
                    return fail(&Diag::error(format!("cannot make {}: {e}", o.display())), 2);
                }
                o.join(codec::file_name(&m))
            }
        }
        (None, None) => dir.join(codec::file_name(&m)),
    };
    // `./Demo-1.0.wmplugin` reads better without its `./`.
    let out = out.strip_prefix(".").map(Path::to_path_buf).unwrap_or(out);
    if let Err(e) = std::fs::write(&out, &packed.bytes) {
        return fail(&Diag::error(format!("cannot write {}: {e}", out.display())), 2);
    }
    let sha = hex(&Sha256::digest(&packed.bytes));
    let file_name = out.file_name().map(|f| f.to_string_lossy().into_owned()).unwrap_or_default();
    let entry = repo_entry(&dir, &m, &file_name, packed.bytes.len(), &sha);
    if args.entry_json {
        println!("{}", serde_json::to_string_pretty(&entry).unwrap_or_default());
        return 0;
    }
    if args.json {
        let s = &packed.shake;
        println!(
            "{}",
            json!({
                "path": out.display().to_string(), "size": packed.bytes.len(), "sha256": sha, "entry": entry,
                "shake": {
                    "removedFiles": s.removed_files, "removedSymbols": s.removed_symbols.iter().map(|(f, n)| json!([f, n])).collect::<Vec<_>>(),
                    "bytesBefore": s.bytes_before, "bytesAfter": s.bytes_after, "notes": s.notes,
                },
            })
        );
        return 0;
    }
    crate::note!("packed {} ({} files, {})", out.display(), packed.names.len(), size(packed.bytes.len()));
    crate::note!("  sha256 {sha}");
    let s = &packed.shake;
    if !s.removed_files.is_empty() || !s.removed_symbols.is_empty() {
        crate::note!(
            "  tree shaking left out {} vendored files and {} definitions: {} of Python became {}",
            s.removed_files.len(),
            s.removed_symbols.len(),
            size(s.bytes_before),
            size(s.bytes_after)
        );
    }
    for note in &s.notes {
        crate::note!("  note: {note}");
    }
    if !packed.left_out.is_empty() {
        let shown: Vec<&str> = packed.left_out.iter().take(6).map(String::as_str).collect();
        let more = if packed.left_out.len() > 6 { format!(" and {} more", packed.left_out.len() - 6) } else { String::new() };
        crate::note!("  not in the archive (the keyboard does not read them): {}{more}", shown.join(", "));
    }
    // The path alone on stdout, for scripts.
    println!("{}", out.display());
    0
}

fn fail(d: &Diag, code: u8) -> u8 {
    eprint!("{}", d.render(&|_| None, color_stderr()));
    code
}

/// A packed plugin.
pub struct Packed {
    pub bytes: Vec<u8>,
    /// The archive's entries, in order.
    pub names: Vec<String>,
    /// Files of the folder the archive leaves out that a person might expect in it.
    pub left_out: Vec<String>,
    pub shake: wm_python_pip::ShakeReport,
}

/// The archive of the plugin in [dir], whose manifest is [m].
pub fn pack(dir: &Path, m: &codec::Manifest, shake: bool, symbols: bool) -> Result<Packed, Diag> {
    let mut files: BTreeMap<String, Vec<u8>> = BTreeMap::new();
    let mut left_out = Vec::new();
    for f in project::files(dir) {
        match codec::packed_role(&f, m) {
            Some(_) => {
                let bytes = std::fs::read(dir.join(&f)).map_err(|e| Diag::error(format!("cannot read {f}: {e}")))?;
                files.insert(f, bytes);
            }
            None if !codec::is_dev_file(&f) => left_out.push(f),
            None => {}
        }
    }
    let entry = m.entry();
    if !files.contains_key(&entry) {
        let mut d = Diag::error(format!("there is no {entry}, the script plugin.json names"));
        d.file = Some(entry.clone());
        return Err(d);
    }
    // Shake the vendored packages: every .py file goes in, the shaken set comes out.
    let python: BTreeMap<String, String> =
        files.iter().filter(|(p, _)| p.ends_with(".py")).filter_map(|(p, b)| std::str::from_utf8(b).ok().map(|t| (p.clone(), t.to_string()))).collect();
    let roots = crate::pip::lock::vendored_roots(dir);
    let report = if shake && !roots.is_empty() {
        wm_python_pip::shake(&python, &entry, &roots, &wm_python_pip::ShakeOptions { symbols })
    } else {
        let bytes: usize = python.values().map(String::len).sum();
        wm_python_pip::ShakeReport { files: python.clone(), bytes_before: bytes, bytes_after: bytes, ..Default::default() }
    };
    for removed in &report.removed_files {
        if *removed != entry {
            files.remove(removed);
        }
    }
    for (path, source) in &report.files {
        if files.contains_key(path) {
            files.insert(path.clone(), source.clone().into_bytes());
        }
    }
    // plugin.json, the entry, then the rest in order.
    let mut names = vec![codec::MANIFEST.to_string(), entry.clone()];
    names.extend(files.keys().filter(|k| **k != codec::MANIFEST && **k != entry).cloned());
    let entries: Vec<(&str, &[u8])> = names.iter().filter_map(|n| files.get(n).map(|b| (n.as_str(), b.as_slice()))).collect();
    Ok(Packed { bytes: zip(&entries), names, left_out, shake: report })
}

/// The addon repository's entry for the archive (the Publishing section of
/// plugins/getting-started.mdx and development/addon-repos/repo-format.mdx).
pub fn repo_entry(dir: &Path, m: &codec::Manifest, file_name: &str, size: usize, sha: &str) -> Json {
    let folder = dir.canonicalize().ok().and_then(|d| d.file_name().map(|f| f.to_string_lossy().into_owned())).unwrap_or_default();
    let mut id = slug(&folder);
    if id.is_empty() {
        id = slug(m.clean_id().rsplit('.').next().unwrap_or("plugin"));
    }
    let mut entry = serde_json::Map::new();
    entry.insert("id".into(), json!(id));
    entry.insert("type".into(), json!("plugin"));
    entry.insert("name".into(), json!(m.name()));
    entry.insert("version".into(), json!(codec::display_text(&m.plugin_version, 32)));
    for (key, value) in [("author", codec::display_text(&m.author, 64)), ("description", codec::display_text(&m.description, 280))] {
        if !value.is_empty() {
            entry.insert(key.into(), json!(value));
        }
    }
    entry.insert("path".into(), json!(format!("plugins/{file_name}")));
    entry.insert("sha256".into(), json!(sha));
    entry.insert("sizeBytes".into(), json!(size));
    let (types, names) = m.file_types();
    if !types.is_empty() {
        entry.insert("fileTypes".into(), json!(types));
    }
    if !names.is_empty() {
        entry.insert("fileNames".into(), json!(names));
    }
    Json::Object(entry)
}

fn slug(s: &str) -> String {
    let mut out = String::new();
    for c in s.to_lowercase().chars() {
        if c.is_ascii_alphanumeric() {
            out.push(c);
        } else if !out.ends_with('-') {
            out.push('-');
        }
    }
    out.trim_matches('-').to_string()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn size(n: usize) -> String {
    if n < 1024 {
        format!("{n} bytes")
    } else {
        format!("{:.1} KB", n as f64 / 1024.0)
    }
}

// ---- the zip ------------------------------------------------------------------

/// MS-DOS date of 1980-01-01, the earliest a zip can hold (`(0 << 9) | (1 << 5) | 1`).
const DOS_DATE: u16 = 0x21;

/// A zip of [entries], in that order, deflated where it helps, with fixed
/// dates: the same entries give the same bytes. Read by Java's
/// `ZipInputStream` (the app) as much as by any unzip: sizes and CRCs are in
/// each local header, no data descriptors, no zip64.
pub fn zip(entries: &[(&str, &[u8])]) -> Vec<u8> {
    let mut out: Vec<u8> = Vec::new();
    let mut central: Vec<u8> = Vec::new();
    for (name, data) in entries {
        let crc = crc32(data);
        let deflated = miniz_oxide::deflate::compress_to_vec(data, 9);
        let (method, body): (u16, &[u8]) = if deflated.len() < data.len() { (8, &deflated) } else { (0, data) };
        let flags: u16 = if name.is_ascii() { 0 } else { 0x0800 };
        let offset = out.len() as u32;
        let name = name.as_bytes();
        // Local file header.
        out.extend_from_slice(&0x0403_4b50u32.to_le_bytes());
        out.extend_from_slice(&20u16.to_le_bytes());
        out.extend_from_slice(&flags.to_le_bytes());
        out.extend_from_slice(&method.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(&DOS_DATE.to_le_bytes());
        out.extend_from_slice(&crc.to_le_bytes());
        out.extend_from_slice(&(body.len() as u32).to_le_bytes());
        out.extend_from_slice(&(data.len() as u32).to_le_bytes());
        out.extend_from_slice(&(name.len() as u16).to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
        out.extend_from_slice(name);
        out.extend_from_slice(body);
        // Its central directory record: made by Unix, a 0644 regular file.
        central.extend_from_slice(&0x0201_4b50u32.to_le_bytes());
        central.extend_from_slice(&0x0314u16.to_le_bytes());
        central.extend_from_slice(&20u16.to_le_bytes());
        central.extend_from_slice(&flags.to_le_bytes());
        central.extend_from_slice(&method.to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&DOS_DATE.to_le_bytes());
        central.extend_from_slice(&crc.to_le_bytes());
        central.extend_from_slice(&(body.len() as u32).to_le_bytes());
        central.extend_from_slice(&(data.len() as u32).to_le_bytes());
        central.extend_from_slice(&(name.len() as u16).to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&(0o100644u32 << 16).to_le_bytes());
        central.extend_from_slice(&offset.to_le_bytes());
        central.extend_from_slice(name);
    }
    let start = out.len() as u32;
    out.extend_from_slice(&central);
    // End of central directory.
    out.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
    out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
    out.extend_from_slice(&(central.len() as u32).to_le_bytes());
    out.extend_from_slice(&start.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out
}

/// CRC-32 (IEEE), as zip uses it.
fn crc32(data: &[u8]) -> u32 {
    static TABLE: std::sync::OnceLock<[u32; 256]> = std::sync::OnceLock::new();
    let table = TABLE.get_or_init(|| {
        let mut t = [0u32; 256];
        for (i, slot) in t.iter_mut().enumerate() {
            let mut c = i as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
            }
            *slot = c;
        }
        t
    });
    let mut crc = 0xFFFF_FFFFu32;
    for b in data {
        crc = table[((crc ^ u32::from(*b)) & 0xFF) as usize] ^ (crc >> 8);
    }
    !crc
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc_of_a_known_string() {
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    }

    #[test]
    fn a_zip_reads_back_and_is_the_same_each_time() {
        let long = "print('hello')\n".repeat(50);
        let entries: Vec<(&str, &[u8])> = vec![("plugin.json", b"{}"), ("main.py", long.as_bytes()), ("assets/a.txt", b"x")];
        let a = zip(&entries);
        assert_eq!(a, zip(&entries));
        let listed = wm_python::host::formats::zip_entries(&a).unwrap();
        let names: Vec<String> = listed.iter().map(|e| e.name.clone()).collect();
        assert_eq!(names, vec!["plugin.json", "main.py", "assets/a.txt"]);
        assert_eq!(wm_python::host::formats::zip_read(&a, "main.py", 1 << 20).unwrap(), long.as_bytes());
    }
}
