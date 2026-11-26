//! `wmpy editor`: install and manage the wmpy editor extensions.
//!
//! ```text
//! wmpy editor list [--json]                 the editors here, and the wmpy extension in each
//! wmpy editor install [EDITOR...]           all the editors found, or the ones named
//! wmpy editor update [EDITOR...]            where the extension is installed
//! wmpy editor remove EDITOR...
//! wmpy editor doctor                        wmpy, its language server and the editors
//! ```
//!
//! EDITOR is vscode, insiders, vscodium, cursor, windsurf, zed, jetbrains or all.
//!
//! VS Code and its forks install from their marketplace (`--install-extension
//! wasimaster.wmpy`), and from the release's `wmpy.vsix` when that fails.
//! JetBrains IDEs install from the JetBrains Marketplace with the launcher's
//! `installPlugins`, which needs the IDE closed, or with `--from-release` from
//! the release's zip into each IDE's plugins folder. Zed has no command for
//! it, so the release's built extension is unpacked into Zed's
//! `extensions/installed/wmpy`, which Zed watches and loads. No editor's
//! settings are changed.

mod doctor;
mod find;
mod release;

use std::path::{Path, PathBuf};
use std::process::Command;

use find::{Code, JetBrains};

#[derive(clap::Args, Debug)]
pub struct Args {
    #[command(subcommand)]
    pub command: Sub,
}

#[derive(clap::Subcommand, Debug)]
pub enum Sub {
    /// Show the editors on this computer and the wmpy extension in each
    List {
        /// Print JSON
        #[arg(long)]
        json: bool,
    },
    /// Install the wmpy extension: in every editor found, or the ones named
    Install(InstallArgs),
    /// Update the wmpy extension where it is installed
    Update(InstallArgs),
    /// Remove the wmpy extension from the editors named
    Remove {
        #[arg(value_enum, required = true)]
        editors: Vec<Which>,
        /// Print what would run, and change nothing
        #[arg(long)]
        dry_run: bool,
    },
    /// Check wmpy, its language server and the editor extensions
    Doctor,
}

#[derive(clap::Args, Debug)]
pub struct InstallArgs {
    #[arg(value_enum)]
    pub editors: Vec<Which>,
    /// Install from the GitHub release instead of the editor's marketplace
    #[arg(long)]
    pub from_release: bool,
    /// A .vsix file to install in the VS Code family
    #[arg(long, value_name = "FILE")]
    pub vsix: Option<PathBuf>,
    /// Print what would run, and change nothing
    #[arg(long)]
    pub dry_run: bool,
}

#[derive(clap::ValueEnum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum Which {
    Vscode,
    Insiders,
    Vscodium,
    Cursor,
    Windsurf,
    Zed,
    Jetbrains,
    All,
}

impl Which {
    fn code(self) -> Option<Code> {
        match self {
            Which::Vscode => Some(Code::Vscode),
            Which::Insiders => Some(Code::Insiders),
            Which::Vscodium => Some(Code::VsCodium),
            Which::Cursor => Some(Code::Cursor),
            Which::Windsurf => Some(Code::Windsurf),
            _ => None,
        }
    }
}

pub fn run(args: Args) -> u8 {
    match args.command {
        Sub::List { json } => list(json),
        Sub::Install(a) => install(a, false),
        Sub::Update(a) => install(a, true),
        Sub::Remove { editors, dry_run } => remove(&editors, dry_run),
        Sub::Doctor => doctor_report(),
    }
}

/// The editors [named] picks; none named is every editor found.
fn wanted(named: &[Which]) -> (Vec<Code>, bool, bool) {
    let all = named.is_empty() || named.contains(&Which::All);
    let codes = Code::ALL.into_iter().filter(|c| if all { true } else { named.iter().any(|w| w.code() == Some(*c)) }).collect();
    (codes, all || named.contains(&Which::Zed), all || named.contains(&Which::Jetbrains))
}

fn list(json: bool) -> u8 {
    let mut rows: Vec<serde_json::Value> = Vec::new();
    for code in Code::ALL {
        let program = code.program();
        let installed = code.installed();
        if program.is_some() || installed.is_some() {
            rows.push(serde_json::json!({
                "editor": code.id(), "name": code.name(),
                "program": program.map(|p| p.display().to_string()), "extension": installed,
            }));
        }
    }
    let zed = find::zed_program();
    let zed_ext = find::zed_installed();
    if zed.is_some() || zed_ext.is_some() || find::zed_data().is_some_and(|d| d.is_dir()) {
        rows.push(serde_json::json!({
            "editor": "zed", "name": "Zed",
            "program": zed.map(|p| p.display().to_string()),
            "extension": zed_ext.as_ref().map(|(v, _)| v.clone()),
            "dev": zed_ext.map(|(_, dev)| dev).unwrap_or(false),
        }));
    }
    let launchers = find::jetbrains_launchers();
    for ide in find::jetbrains_ides() {
        let launcher = launchers.iter().find(|(label, _)| ide.name.starts_with(label.as_str())).map(|(_, p)| p.display().to_string());
        rows.push(serde_json::json!({
            "editor": "jetbrains", "name": ide.name, "program": launcher,
            "extension": find::jetbrains_installed(&ide.plugins), "lsp4ij": find::lsp4ij_installed(&ide.plugins),
            "plugins": ide.plugins.display().to_string(),
        }));
    }
    if json {
        println!("{}", serde_json::Value::Array(rows));
        return 0;
    }
    if rows.is_empty() {
        println!("No editor the wmpy extension supports was found: VS Code, its forks, Zed, PyCharm or IntelliJ IDEA.");
        println!("Other editors can run the language server, wmpy lsp: https://wmkeyboard.pages.dev/plugins/language-server/");
        return 0;
    }
    let width = rows.iter().map(|r| r["name"].as_str().unwrap_or("").len()).max().unwrap_or(0).max(6);
    println!("{:width$}  {:13}  PROGRAM", "EDITOR", "WMPY");
    for r in &rows {
        let ext = match r["extension"].as_str() {
            Some(v) if r["dev"] == true => format!("{v} (dev)"),
            Some(v) => v.to_string(),
            None => "-".into(),
        };
        let program = r["program"].as_str().map(|p| find::tilde(Path::new(p))).unwrap_or_else(|| "(not found)".into());
        println!("{:width$}  {:13}  {program}", r["name"].as_str().unwrap_or(""), ext);
    }
    0
}

/// Runs [cmd], printing it first; with [dry] only prints it.
fn exec(cmd: &mut Command, dry: bool) -> Result<String, String> {
    let shown = format!("{} {}", find::tilde(Path::new(cmd.get_program())), cmd.get_args().map(|a| a.to_string_lossy()).collect::<Vec<_>>().join(" "));
    println!("  $ {shown}");
    if dry {
        return Ok(String::new());
    }
    let out = cmd.output().map_err(|e| format!("could not run {shown}: {e}"))?;
    let text = format!("{}{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
    if out.status.success() {
        Ok(text)
    } else {
        Err(text.trim().to_string())
    }
}

fn install(args: InstallArgs, update: bool) -> u8 {
    let (codes, zed, jetbrains) = wanted(&args.editors);
    let named = !args.editors.is_empty() && !args.editors.contains(&Which::All);
    let mut done = 0;
    let mut failed = 0;
    let mut vsix: Option<PathBuf> = args.vsix.clone();
    let mut vsix_tmp: Option<tempdir::Dir> = None;

    for code in codes {
        let Some(program) = code.program() else {
            if named {
                eprintln!("{}: not found. Install it, or put its command ({}) on PATH.", code.name(), code.id());
                failed += 1;
            }
            continue;
        };
        if update && code.installed().is_none() {
            continue;
        }
        println!("{}:", code.name());
        let mut from_file = vsix.is_some() || args.from_release;
        if !from_file {
            match exec(Command::new(&program).args(["--install-extension", find::VSCODE_ID, "--force"]), args.dry_run) {
                Ok(_) => {
                    done += 1;
                    continue;
                }
                Err(e) => {
                    eprintln!("  the marketplace install failed ({}); trying the release's wmpy.vsix", last_line(&e));
                    from_file = true;
                }
            }
        }
        if from_file {
            if vsix.is_none() {
                match release_vsix(&mut vsix_tmp, args.dry_run) {
                    Ok(p) => vsix = Some(p),
                    Err(e) => {
                        eprintln!("  {e}");
                        failed += 1;
                        continue;
                    }
                }
            }
            let file = vsix.clone().expect("vsix");
            match exec(Command::new(&program).arg("--install-extension").arg(&file).arg("--force"), args.dry_run) {
                Ok(_) => done += 1,
                Err(e) => {
                    eprintln!("  {e}");
                    failed += 1;
                }
            }
        }
    }

    if zed && (find::zed_program().is_some() || find::zed_data().is_some_and(|d| d.is_dir())) {
        if !update || find::zed_installed().is_some() {
            println!("Zed:");
            match install_zed(args.dry_run) {
                Ok(()) => done += 1,
                Err(e) => {
                    eprintln!("  {e}");
                    failed += 1;
                }
            }
        }
    } else if zed && named {
        eprintln!("Zed: not found.");
        failed += 1;
    }

    if jetbrains {
        match install_jetbrains(args.from_release, update, args.dry_run) {
            Some(Ok(n)) => done += n,
            Some(Err(n)) => failed += n,
            None if named => {
                eprintln!("JetBrains: no PyCharm, IntelliJ IDEA or DataSpell found.");
                failed += 1;
            }
            None => {}
        }
    }

    if done == 0 && failed == 0 {
        if update {
            println!("The wmpy extension is not installed in any editor here. Try: wmpy editor install");
        } else {
            eprintln!("No editor the wmpy extension supports was found. See: wmpy editor list");
            return 2;
        }
    }
    if failed > 0 {
        1
    } else {
        0
    }
}

fn last_line(s: &str) -> &str {
    s.lines().rev().find(|l| !l.trim().is_empty()).unwrap_or(s).trim()
}

mod tempdir {
    /// A folder under the system temp folder, deleted when dropped.
    pub struct Dir(pub std::path::PathBuf);

    impl Dir {
        pub fn new(tag: &str) -> std::io::Result<Dir> {
            let p = std::env::temp_dir().join(format!("wmpy-{tag}-{}-{}", std::process::id(), std::time::UNIX_EPOCH.elapsed().map(|d| d.as_nanos()).unwrap_or(0)));
            std::fs::create_dir_all(&p)?;
            Ok(Dir(p))
        }
    }

    impl Drop for Dir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
}

/// The release's wmpy.vsix, downloaded once into a temp folder.
fn release_vsix(tmp: &mut Option<tempdir::Dir>, dry: bool) -> Result<PathBuf, String> {
    if dry {
        println!("  (download wmpy.vsix from the wmpy release)");
        return Ok(PathBuf::from("wmpy.vsix"));
    }
    let bytes = release::fetch("wmpy.vsix")?;
    let dir = tempdir::Dir::new("vsix").map_err(|e| e.to_string())?;
    let path = dir.0.join("wmpy.vsix");
    std::fs::write(&path, bytes).map_err(|e| e.to_string())?;
    *tmp = Some(dir);
    Ok(path)
}

/// Unpacks the release's built Zed extension into Zed's installed extensions.
fn install_zed(dry: bool) -> Result<(), String> {
    let dest = find::zed_extension_dir().ok_or("Zed's data folder is unknown here")?;
    if let Some((_, true)) = find::zed_installed() {
        return Err(format!("{} is a dev extension (a link to its source); remove it in Zed's Extensions page first", find::tilde(&dest)));
    }
    if dry {
        println!("  (download wmpy-zed.zip from the wmpy release and unpack it into {})", find::tilde(&dest));
        return Ok(());
    }
    let bytes = release::fetch("wmpy-zed.zip")?;
    let parent = dest.parent().ok_or("bad Zed folder")?;
    std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let staging = parent.join(".wmpy.partial");
    let _ = std::fs::remove_dir_all(&staging);
    release::unzip(&bytes, &staging)?;
    if !staging.join("extension.toml").is_file() || !staging.join("extension.wasm").is_file() {
        let _ = std::fs::remove_dir_all(&staging);
        return Err("wmpy-zed.zip has no extension.toml and extension.wasm".into());
    }
    let _ = std::fs::remove_dir_all(&dest);
    std::fs::rename(&staging, &dest).map_err(|e| e.to_string())?;
    let version = find::zed_installed().map(|(v, _)| v).unwrap_or_default();
    println!("  installed wmpy {version} in {}", find::tilde(&dest));
    println!("  Zed loads it at once when it is running, or on its next start.");
    Ok(())
}

/// Installs in the JetBrains IDEs. None when there are none.
fn install_jetbrains(from_release: bool, update: bool, dry: bool) -> Option<Result<usize, usize>> {
    let ides: Vec<JetBrains> = find::jetbrains_ides()
        .into_iter()
        .filter(|i| !update || find::jetbrains_installed(&i.plugins).is_some())
        .collect();
    let launchers = find::jetbrains_launchers();
    if ides.is_empty() && launchers.is_empty() {
        return None;
    }
    let mut done = 0;
    let mut failed = 0;
    if !from_release && !launchers.is_empty() {
        for (label, launcher) in &launchers {
            if update && !ides.iter().any(|i| i.name.starts_with(label.as_str())) {
                continue;
            }
            println!("{label}:");
            match exec(Command::new(launcher).args(["installPlugins", find::LSP4IJ_ID, find::JETBRAINS_ID]), dry) {
                Ok(_) => done += 1,
                Err(e) => {
                    eprintln!("  {}", last_line(&e));
                    eprintln!("  installPlugins needs the IDE closed. Close it and run this again, or install wmpy from Settings | Plugins | Marketplace.");
                    failed += 1;
                }
            }
        }
    } else {
        let bytes = if dry {
            println!("  (download wmpy-jetbrains.zip from the wmpy release)");
            Vec::new()
        } else {
            match release::fetch("wmpy-jetbrains.zip") {
                Ok(b) => b,
                Err(e) => {
                    eprintln!("JetBrains: {e}");
                    return Some(Err(1));
                }
            }
        };
        for ide in &ides {
            println!("{}:", ide.name);
            let dest = ide.plugins.join("wmpy");
            if dry {
                println!("  (unpack into {})", find::tilde(&dest));
                done += 1;
                continue;
            }
            let staging = ide.plugins.join(".wmpy.partial");
            let _ = std::fs::remove_dir_all(&staging);
            let result = release::unzip(&bytes, &staging).and_then(|()| {
                // The zip holds one folder, wmpy/, with lib/ inside.
                let inner = staging.join("wmpy");
                let from = if inner.join("lib").is_dir() { inner } else { staging.clone() };
                let _ = std::fs::remove_dir_all(&dest);
                std::fs::rename(&from, &dest).map_err(|e| e.to_string())
            });
            let _ = std::fs::remove_dir_all(&staging);
            match result {
                Ok(()) => {
                    println!("  installed in {}. Restart the IDE to load it.", find::tilde(&dest));
                    if !find::lsp4ij_installed(&ide.plugins) {
                        println!("  wmpy needs LSP4IJ: the IDE offers it when it starts, or get it from Settings | Plugins | Marketplace.");
                    }
                    done += 1;
                }
                Err(e) => {
                    eprintln!("  {e}");
                    failed += 1;
                }
            }
        }
    }
    Some(if failed > 0 { Err(failed) } else { Ok(done) })
}

fn remove(editors: &[Which], dry: bool) -> u8 {
    let (codes, zed, jetbrains) = wanted(editors);
    let mut failed = 0;
    let mut done = 0;
    for code in codes {
        let Some(program) = code.program() else { continue };
        if code.installed().is_none() && editors.contains(&Which::All) {
            continue;
        }
        println!("{}:", code.name());
        match exec(Command::new(&program).args(["--uninstall-extension", find::VSCODE_ID]), dry) {
            Ok(_) => done += 1,
            Err(e) => {
                eprintln!("  {}", last_line(&e));
                failed += 1;
            }
        }
    }
    if zed {
        if let Some(dir) = find::zed_extension_dir().filter(|_| find::zed_installed().is_some()) {
            println!("Zed:");
            println!("  remove {}", find::tilde(&dir));
            if !dry {
                let link = std::fs::symlink_metadata(&dir).is_ok_and(|m| m.file_type().is_symlink());
                let r = if link { std::fs::remove_file(&dir) } else { std::fs::remove_dir_all(&dir) };
                if let Err(e) = r {
                    eprintln!("  {e}");
                    failed += 1;
                } else {
                    done += 1;
                }
            }
        }
    }
    if jetbrains {
        for ide in find::jetbrains_ides() {
            if find::jetbrains_installed(&ide.plugins).is_none() {
                continue;
            }
            let dir = ide.plugins.join("wmpy");
            println!("{}:", ide.name);
            println!("  remove {} (close the IDE first)", find::tilde(&dir));
            if !dry {
                match std::fs::remove_dir_all(&dir) {
                    Ok(()) => done += 1,
                    Err(e) => {
                        eprintln!("  {e}");
                        failed += 1;
                    }
                }
            }
        }
    }
    if done == 0 && failed == 0 && !dry {
        println!("The wmpy extension is not installed in those editors.");
    }
    if failed > 0 {
        1
    } else {
        0
    }
}

fn doctor_report() -> u8 {
    let mut problems = 0;
    let exe = std::env::current_exe().unwrap_or_else(|_| PathBuf::from("wmpy"));
    println!("wmpy {}", env!("CARGO_PKG_VERSION"));
    println!("  program      {}", find::tilde(&exe));
    match find::on_path("wmpy") {
        Some(p) if same_file(&p, &exe) => println!("  on PATH      yes"),
        Some(p) => {
            println!("  on PATH      another wmpy comes first: {}", find::tilde(&p));
            problems += 1;
        }
        None => {
            println!("  on PATH      no: editors that look on PATH will not find this wmpy");
            problems += 1;
        }
    }
    match doctor::probe(&exe) {
        Ok(server) => println!("  wmpy lsp     answers initialize ({server})"),
        Err(e) => {
            println!("  wmpy lsp     {e}");
            problems += 1;
        }
    }
    println!();
    println!("Editors");
    let mut any = false;
    for code in Code::ALL {
        let program = code.program();
        let installed = code.installed();
        if program.is_none() && installed.is_none() {
            continue;
        }
        any = true;
        let ext = installed.as_deref().map(|v| format!("wmpy {v}")).unwrap_or_else(|| "no wmpy extension: wmpy editor install ".to_string() + code.id());
        let p = program.as_deref().map(find::tilde).unwrap_or_else(|| format!("its command ({}) is not on PATH", code.id()));
        println!("  {:17} {ext}; {p}", code.name());
    }
    if let Some(data) = find::zed_data().filter(|d| d.is_dir() || find::zed_program().is_some()) {
        any = true;
        match find::zed_installed() {
            Some((v, dev)) => println!("  {:17} wmpy {v}{}; {}", "Zed", if dev { " (dev extension)" } else { "" }, find::tilde(&data)),
            None => println!("  {:17} no wmpy extension: wmpy editor install zed", "Zed"),
        }
    }
    for ide in find::jetbrains_ides() {
        any = true;
        match find::jetbrains_installed(&ide.plugins) {
            Some(v) => {
                let lsp = if find::lsp4ij_installed(&ide.plugins) { "" } else { "; LSP4IJ missing, install it from Settings | Plugins" };
                if !lsp.is_empty() {
                    problems += 1;
                }
                println!("  {:17} wmpy {v}{lsp}", ide.name);
            }
            None => println!("  {:17} no wmpy plugin: wmpy editor install jetbrains", ide.name),
        }
    }
    if !any {
        println!("  none found. Other editors: https://wmkeyboard.pages.dev/plugins/language-server/");
    }
    if problems > 0 {
        1
    } else {
        0
    }
}

fn same_file(a: &Path, b: &Path) -> bool {
    match (a.canonicalize(), b.canonicalize()) {
        (Ok(x), Ok(y)) => x == y,
        _ => a == b,
    }
}
