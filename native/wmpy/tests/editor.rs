//! `wmpy editor` against fake editors: a temporary home folder with stand-in
//! `code` and `cursor` commands, a Zed data folder, a JetBrains IDE folder,
//! and a folder of release files in place of GitHub.
#![cfg(unix)]

use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Command;

use sha2::{Digest, Sha256};

struct World {
    home: PathBuf,
    bin: PathBuf,
    releases: PathBuf,
}

fn script(path: &Path, body: &str) {
    std::fs::write(path, format!("#!/bin/sh\n{body}")).unwrap();
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)).unwrap();
}

fn zip(entries: &[(&str, &[u8])]) -> Vec<u8> {
    let mut out = std::io::Cursor::new(Vec::new());
    {
        let mut z = zip::ZipWriter::new(&mut out);
        for (name, data) in entries {
            z.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            z.write_all(data).unwrap();
        }
        z.finish().unwrap();
    }
    out.into_inner()
}

fn publish(dir: &Path, name: &str, data: &[u8]) {
    std::fs::write(dir.join(name), data).unwrap();
    let hex: String = Sha256::digest(data).iter().map(|b| format!("{b:02x}")).collect();
    std::fs::write(dir.join(format!("{name}.sha256")), format!("{hex}  {name}\n")).unwrap();
}

fn plugins_dir(home: &Path) -> PathBuf {
    let ide = home.join("jb").join("PyCharm2025.2");
    if cfg!(target_os = "macos") {
        ide.join("plugins")
    } else {
        ide
    }
}

fn world(tag: &str) -> World {
    let home = std::env::temp_dir().join(format!("wmpy-editor-{}-{tag}", std::process::id()));
    let _ = std::fs::remove_dir_all(&home);
    let bin = home.join("bin");
    let releases = home.join("releases");
    for d in [&bin, &releases, &home.join("zed")] {
        std::fs::create_dir_all(d).unwrap();
    }
    std::fs::create_dir_all(plugins_dir(&home)).unwrap();
    // VS Code: the marketplace install works.
    script(
        &bin.join("code"),
        r#"echo "code $*" >> "$HOME/editors.log"
case "$1" in
  --install-extension)
    case "$2" in *.vsix) v=0.1.1 ;; *) v=0.1.0 ;; esac
    mkdir -p "$HOME/.vscode/extensions/wasimaster.wmpy-$v" && echo '{}' > "$HOME/.vscode/extensions/wasimaster.wmpy-$v/package.json" ;;
  --uninstall-extension) rm -rf "$HOME"/.vscode/extensions/wasimaster.wmpy-* ;;
esac
"#,
    );
    // Cursor: the marketplace has no wmpy, the .vsix works.
    script(
        &bin.join("cursor"),
        r#"echo "cursor $*" >> "$HOME/editors.log"
case "$2" in
  *.vsix) mkdir -p "$HOME/.cursor/extensions/wasimaster.wmpy-0.1.1" && echo '{}' > "$HOME/.cursor/extensions/wasimaster.wmpy-0.1.1/package.json" ;;
  *) echo "Extension 'wasimaster.wmpy' not found." >&2; exit 1 ;;
esac
"#,
    );
    publish(&releases, "wmpy.vsix", &zip(&[("extension/package.json", b"{}")]));
    publish(
        &releases,
        "wmpy-zed.zip",
        &zip(&[("extension.toml", b"id = \"wmpy\"\nversion = \"0.1.0\"\n\n[lib]\nkind = \"Rust\"\n"), ("extension.wasm", b"\0asm"), ("LICENSE", b"MIT")]),
    );
    publish(&releases, "wmpy-jetbrains.zip", &zip(&[("wmpy/lib/wmpy-0.1.0.jar", b"jar")]));
    World { home, bin, releases }
}

impl World {
    fn wmpy(&self, args: &[&str]) -> (i32, String, String) {
        let out = Command::new(env!("CARGO_BIN_EXE_wmpy"))
            .args(args)
            .env("HOME", &self.home)
            .env("PATH", format!("{}:/usr/bin:/bin", self.bin.display()))
            .env("WMPY_RELEASE_BASE", &self.releases)
            .env("WMPY_ZED_DATA_DIR", self.home.join("zed"))
            .env("WMPY_JETBRAINS_DIR", self.home.join("jb"))
            .env("WMPY_EDITOR_NO_SYSTEM", "1")
            .env_remove("XDG_DATA_HOME")
            .output()
            .unwrap();
        (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into(), String::from_utf8_lossy(&out.stderr).into())
    }

    fn log(&self) -> String {
        std::fs::read_to_string(self.home.join("editors.log")).unwrap_or_default()
    }

    fn listed(&self) -> serde_json::Value {
        let (code, out, err) = self.wmpy(&["editor", "list", "--json"]);
        assert_eq!(code, 0, "{err}");
        serde_json::from_str(&out).unwrap()
    }
}

fn row<'a>(list: &'a serde_json::Value, editor: &str) -> &'a serde_json::Value {
    list.as_array().unwrap().iter().find(|r| r["editor"] == editor).unwrap_or_else(|| panic!("no {editor} in {list}"))
}

#[test]
fn list_finds_the_fake_editors() {
    let w = world("list");
    let list = w.listed();
    assert!(row(&list, "vscode")["program"].as_str().unwrap().ends_with("/bin/code"));
    assert!(row(&list, "vscode")["extension"].is_null());
    assert!(row(&list, "cursor")["program"].is_string());
    assert!(row(&list, "zed")["extension"].is_null());
    assert_eq!(row(&list, "jetbrains")["name"], "PyCharm 2025.2");
    assert!(list.as_array().unwrap().iter().all(|r| r["editor"] != "vscodium"), "{list}");
    let (code, out, _) = w.wmpy(&["editor", "list"]);
    assert_eq!(code, 0);
    assert!(out.contains("VS Code") && out.contains("PyCharm 2025.2"), "{out}");
}

#[test]
fn install_everywhere_then_remove() {
    let w = world("install");
    let (code, out, err) = w.wmpy(&["editor", "install"]);
    assert_eq!(code, 0, "{out}\n{err}");
    let log = w.log();
    assert!(log.contains("code --install-extension wasimaster.wmpy --force"), "{log}");
    assert!(log.contains("cursor --install-extension wasimaster.wmpy --force"), "{log}");
    assert!(log.lines().any(|l| l.starts_with("cursor --install-extension") && l.contains("wmpy.vsix")), "cursor falls back to the vsix: {log}");
    assert!(err.contains("trying the release's wmpy.vsix"), "{err}");
    let zed = w.home.join("zed/extensions/installed/wmpy");
    assert!(zed.join("extension.toml").is_file() && zed.join("extension.wasm").is_file());
    assert!(plugins_dir(&w.home).join("wmpy/lib/wmpy-0.1.0.jar").is_file());

    let list = w.listed();
    assert_eq!(row(&list, "vscode")["extension"], "0.1.0");
    assert_eq!(row(&list, "cursor")["extension"], "0.1.1");
    assert_eq!(row(&list, "zed")["extension"], "0.1.0");
    assert_eq!(row(&list, "jetbrains")["extension"], "0.1.0");
    assert_eq!(row(&list, "jetbrains")["lsp4ij"], false);

    let (code, _, err) = w.wmpy(&["editor", "remove", "vscode", "zed", "jetbrains"]);
    assert_eq!(code, 0, "{err}");
    assert!(w.log().contains("code --uninstall-extension wasimaster.wmpy"));
    assert!(!zed.exists());
    assert!(!plugins_dir(&w.home).join("wmpy").exists());
    assert!(w.home.join(".cursor/extensions/wasimaster.wmpy-0.1.1").exists(), "cursor was not named");
}

#[test]
fn a_wrong_sha256_installs_nothing() {
    let w = world("sha");
    std::fs::write(w.releases.join("wmpy-zed.zip.sha256"), format!("{}  wmpy-zed.zip\n", "0".repeat(64))).unwrap();
    let (code, _, err) = w.wmpy(&["editor", "install", "zed"]);
    assert_eq!(code, 1);
    assert!(err.contains("does not match its SHA-256"), "{err}");
    assert!(!w.home.join("zed/extensions/installed/wmpy").exists());
}

#[test]
fn dry_run_changes_nothing() {
    let w = world("dry");
    let (code, out, err) = w.wmpy(&["editor", "install", "--dry-run", "vscode", "zed"]);
    assert_eq!(code, 0, "{err}");
    assert!(out.contains("--install-extension wasimaster.wmpy --force"), "{out}");
    assert!(w.log().is_empty());
    assert!(!w.home.join("zed/extensions/installed/wmpy").exists());
}

#[test]
fn naming_a_missing_editor_fails() {
    let w = world("missing");
    let (code, _, err) = w.wmpy(&["editor", "install", "windsurf"]);
    assert_eq!(code, 1);
    assert!(err.contains("Windsurf: not found"), "{err}");
}

#[test]
fn doctor_reports_the_language_server_and_the_editors() {
    let w = world("doctor");
    let (code, out, _) = w.wmpy(&["editor", "doctor"]);
    assert!(code == 0 || code == 1, "{out}");
    assert!(out.contains("wmpy lsp"), "{out}");
    assert!(out.contains("on PATH      no"), "this wmpy is not on the fake PATH: {out}");
    assert!(out.contains("VS Code") && out.contains("Zed") && out.contains("PyCharm 2025.2"), "{out}");
}
