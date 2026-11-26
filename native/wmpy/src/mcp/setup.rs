//! `wmpy mcp setup <client>`: the config that adds this MCP server to an AI
//! client, printed, or merged into the client's own file with `--write`.

use std::path::{Path, PathBuf};

use serde_json::{json, Value};

#[derive(clap::ValueEnum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum Client {
    /// Claude Code: `claude mcp add`, or the project's .mcp.json
    Claude,
    /// Cursor: ~/.cursor/mcp.json
    Cursor,
    /// VS Code: the workspace's .vscode/mcp.json
    Vscode,
    /// Windsurf: ~/.codeium/windsurf/mcp_config.json
    Windsurf,
    /// Zed: context_servers in Zed's settings.json
    Zed,
}

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The AI client to set up
    #[arg(value_enum)]
    pub client: Client,
    /// Merge the server into the client's config file, keeping the rest of it, and back up the old file
    #[arg(long)]
    pub write: bool,
    /// The command the client runs, when not this wmpy
    #[arg(long)]
    pub command: Option<String>,
}

/// What the client should run: `wmpy` when PATH finds this binary, else its full path.
fn command_name(given: Option<String>) -> String {
    if let Some(c) = given {
        return c;
    }
    let exe = std::env::current_exe().ok().and_then(|p| p.canonicalize().ok());
    let name = if cfg!(windows) { "wmpy.exe" } else { "wmpy" };
    let on_path = std::env::var_os("PATH")
        .map(|paths| std::env::split_paths(&paths).filter_map(|d| d.join(name).canonicalize().ok()).any(|p| Some(&p) == exe.as_ref()))
        .unwrap_or(false);
    match (&exe, on_path) {
        (_, true) | (None, _) => "wmpy".to_string(),
        (Some(p), false) => p.to_string_lossy().to_string(),
    }
}

fn home() -> Option<PathBuf> {
    std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" }).map(PathBuf::from)
}

/// The file the client reads, the key its servers sit under, and the entry.
fn target(client: Client, command: &str) -> Result<(PathBuf, &'static str, Value), String> {
    let home = || home().ok_or("cannot find the home folder");
    let stdio = json!({"command": command, "args": ["mcp"]});
    Ok(match client {
        Client::Claude => (PathBuf::from(".mcp.json"), "mcpServers", json!({"type": "stdio", "command": command, "args": ["mcp"]})),
        Client::Cursor => (home()?.join(".cursor").join("mcp.json"), "mcpServers", stdio),
        Client::Vscode => (PathBuf::from(".vscode").join("mcp.json"), "servers", json!({"type": "stdio", "command": command, "args": ["mcp"]})),
        Client::Windsurf => (home()?.join(".codeium").join("windsurf").join("mcp_config.json"), "mcpServers", stdio),
        Client::Zed => {
            let file = if cfg!(windows) {
                PathBuf::from(std::env::var_os("APPDATA").ok_or("cannot find %APPDATA%")?).join("Zed").join("settings.json")
            } else {
                home()?.join(".config").join("zed").join("settings.json")
            };
            (file, "context_servers", json!({"command": command, "args": ["mcp"], "env": {}}))
        }
    })
}

pub fn run(args: Args) -> u8 {
    let command = command_name(args.command);
    let (file, key, entry) = match target(args.client, &command) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("wmpy mcp setup: {e}");
            return 2;
        }
    };
    let snippet = serde_json::to_string_pretty(&json!({key: {"wmpy": entry}})).unwrap_or_default();
    if !args.write {
        if args.client == Client::Claude {
            println!("Run this in your project to add the server for you alone:\n\n    claude mcp add wmpy -- {} mcp\n", shell_word(&command));
            println!("Or share it with the project: `wmpy mcp setup claude --write` merges this into ./.mcp.json:\n");
        } else {
            println!("Merge this into {} (or run again with --write):\n", file.display());
        }
        println!("{snippet}");
        return 0;
    }
    match write(&file, key, entry) {
        Ok(backup) => {
            match backup {
                Some(b) => println!("Added wmpy to {}. The old file is at {}.", file.display(), b.display()),
                None => println!("Wrote {} with the wmpy server.", file.display()),
            }
            println!("Restart {} or reload its MCP servers to use it.", client_name(args.client));
            0
        }
        Err(e) => {
            eprintln!("wmpy mcp setup: {e}\n\nAdd this to {} by hand:\n\n{snippet}", file.display());
            1
        }
    }
}

fn client_name(c: Client) -> &'static str {
    match c {
        Client::Claude => "Claude Code",
        Client::Cursor => "Cursor",
        Client::Vscode => "VS Code",
        Client::Windsurf => "Windsurf",
        Client::Zed => "Zed",
    }
}

fn shell_word(s: &str) -> String {
    if s.chars().all(|c| c.is_ascii_alphanumeric() || "/._-:\\".contains(c)) {
        s.to_string()
    } else {
        format!("'{}'", s.replace('\'', "'\\''"))
    }
}

/// Merge `{key: {"wmpy": entry}}` into [file], keeping the rest. Returns the
/// backup of the old file, when there was one.
pub fn write(file: &Path, key: &str, entry: Value) -> Result<Option<PathBuf>, String> {
    let old = match std::fs::read_to_string(file) {
        Ok(t) => Some(t),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
        Err(e) => return Err(format!("cannot read {}: {e}", file.display())),
    };
    let mut root: Value = match &old {
        None => json!({}),
        Some(t) if t.trim().is_empty() => json!({}),
        Some(t) => {
            let (clean, had_comments) = strip_jsonc(t);
            if had_comments {
                return Err(format!("{} has comments, which rewriting it would lose", file.display()));
            }
            serde_json::from_str(&clean).map_err(|e| format!("{} is not JSON wmpy can read: {e}", file.display()))?
        }
    };
    let Some(obj) = root.as_object_mut() else { return Err(format!("{} does not hold a JSON object", file.display())) };
    let servers = obj.entry(key.to_string()).or_insert_with(|| json!({}));
    let Some(servers) = servers.as_object_mut() else { return Err(format!("\"{key}\" in {} is not an object", file.display())) };
    servers.insert("wmpy".into(), entry);
    if let Some(dir) = file.parent().filter(|d| !d.as_os_str().is_empty()) {
        std::fs::create_dir_all(dir).map_err(|e| format!("cannot make {}: {e}", dir.display()))?;
    }
    let backup = match &old {
        Some(text) => {
            let b = PathBuf::from(format!("{}.wmpy-backup", file.display()));
            std::fs::write(&b, text).map_err(|e| format!("cannot back up to {}: {e}", b.display()))?;
            Some(b)
        }
        None => None,
    };
    let mut text = serde_json::to_string_pretty(&root).map_err(|e| e.to_string())?;
    text.push('\n');
    std::fs::write(file, text).map_err(|e| format!("cannot write {}: {e}", file.display()))?;
    Ok(backup)
}

/// [text] without `//` and `/* */` comments and trailing commas, and
/// whether it had comments.
fn strip_jsonc(text: &str) -> (String, bool) {
    let b = text.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut comments = false;
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'"' => {
                let start = i;
                i += 1;
                while i < b.len() && b[i] != b'"' {
                    i += if b[i] == b'\\' { 2 } else { 1 };
                }
                i = (i + 1).min(b.len());
                out.extend_from_slice(&b[start..i]);
                continue;
            }
            b'/' if b.get(i + 1) == Some(&b'/') => {
                comments = true;
                while i < b.len() && b[i] != b'\n' {
                    i += 1;
                }
                continue;
            }
            b'/' if b.get(i + 1) == Some(&b'*') => {
                comments = true;
                i += 2;
                while i + 1 < b.len() && !(b[i] == b'*' && b[i + 1] == b'/') {
                    i += 1;
                }
                i += 2;
                continue;
            }
            b',' => {
                // A comma before a closing bracket is dropped.
                let next = b[i + 1..].iter().find(|c| !c.is_ascii_whitespace());
                if !matches!(next, Some(b'}') | Some(b']')) {
                    out.push(b',');
                }
            }
            c => out.push(c),
        }
        i += 1;
    }
    (String::from_utf8_lossy(&out).to_string(), comments)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merges_and_backs_up() {
        let dir = std::env::temp_dir().join(format!("wmpy-setup-{}", std::process::id()));
        let file = dir.join("mcp.json");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(&file, "{\"mcpServers\": {\"other\": {\"command\": \"x\"}}, \"keep\": 1}").unwrap();
        let backup = write(&file, "mcpServers", json!({"command": "wmpy", "args": ["mcp"]})).unwrap();
        let v: Value = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
        assert_eq!(v["mcpServers"]["other"]["command"], "x");
        assert_eq!(v["mcpServers"]["wmpy"]["args"], json!(["mcp"]));
        assert_eq!(v["keep"], 1);
        assert!(backup.unwrap().is_file());
        std::fs::write(&file, "{\n  // mine\n  \"a\": 1,\n}").unwrap();
        assert!(write(&file, "context_servers", json!({})).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn strips_trailing_commas() {
        let (t, c) = strip_jsonc("{\"a\": [1, 2,], \"b\": \"//x\",}");
        assert!(!c);
        assert_eq!(serde_json::from_str::<Value>(&t).unwrap(), json!({"a": [1, 2], "b": "//x"}));
    }
}
