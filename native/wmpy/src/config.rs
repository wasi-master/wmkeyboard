//! A plugin's own settings for wmpy: `wmpy.toml` in the plugin folder, or
//! the `[tool.wmpy]` table of its `pyproject.toml` when there is no
//! wmpy.toml. Flags on the command line win over both.
//!
//! ```toml
//! [check]
//! strict = true                       # wmpy check fails on warnings too
//! ignore = ["unused_local"]           # codes never reported for this plugin
//!
//! [run]                               # test, run, preview, render, budget, profile, repl
//! locale = "bn"
//! theme = "dark"
//! surface = "panel"
//! mock = "mocks/dev.json"             # run, preview, render, budget, profile, repl
//! settings = { units = "imperial" }   # run, preview
//!
//! [test]
//! jobs = 4
//! fail-fast = true
//!
//! [pack]
//! output = "dist"                     # a folder, or a file ending in .wmplugin
//! shake-symbols = true
//!
//! [device]
//! serial = "192.168.1.20:5555"
//! ```
//!
//! Paths are relative to the plugin folder. A key wmpy does not know is a
//! warning, so a typo does not pass unseen; a file that is not TOML is an
//! error.

use std::path::{Path, PathBuf};

use toml_edit::{DocumentMut, Item, TableLike};

pub const FILE: &str = "wmpy.toml";

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Config {
    /// Where it came from, for messages: `wmpy.toml` or `pyproject.toml`.
    pub source: Option<String>,
    pub check: Check,
    pub run: Run,
    pub test: Test,
    pub pack: Pack,
    pub device: Device,
    /// Keys wmpy does not know, as `section.key`.
    pub unknown: Vec<String>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Check {
    pub strict: Option<bool>,
    pub ignore: Vec<String>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Run {
    pub locale: Option<String>,
    pub theme: Option<String>,
    pub surface: Option<String>,
    pub mock: Option<PathBuf>,
    pub settings: Vec<(String, String)>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Test {
    pub jobs: Option<usize>,
    pub fail_fast: Option<bool>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Pack {
    pub output: Option<PathBuf>,
    pub shake_symbols: Option<bool>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct Device {
    pub serial: Option<String>,
}

const KNOWN: &[(&str, &[&str])] = &[
    ("check", &["strict", "ignore"]),
    ("run", &["locale", "theme", "surface", "mock", "settings"]),
    ("test", &["jobs", "fail-fast"]),
    ("pack", &["output", "shake-symbols"]),
    ("device", &["serial"]),
];

/// The settings for the plugin in [dir]: empty when it has none, an error
/// when the file cannot be read as TOML or a value has the wrong type.
pub fn load(dir: &Path) -> Result<Config, String> {
    let own = dir.join(FILE);
    if let Ok(text) = std::fs::read_to_string(&own) {
        return parse(&text, FILE, None);
    }
    let pyproject = dir.join("pyproject.toml");
    if let Ok(text) = std::fs::read_to_string(&pyproject) {
        return parse(&text, "pyproject.toml", Some("tool.wmpy"));
    }
    Ok(Config::default())
}

/// [load], with an error or unknown keys printed to stderr. Err(2) when the
/// command should stop.
pub fn load_or_report(dir: &Path) -> Result<Config, u8> {
    match load(dir) {
        Ok(c) => {
            if let Some(source) = &c.source {
                for k in &c.unknown {
                    eprintln!("warning: {source}: wmpy has no setting {k} (it knows {})", known_list());
                }
            }
            Ok(c)
        }
        Err(e) => {
            eprintln!("error: {e}");
            Err(2)
        }
    }
}

fn known_list() -> String {
    KNOWN.iter().flat_map(|(s, keys)| keys.iter().map(move |k| format!("{s}.{k}"))).collect::<Vec<_>>().join(", ")
}

/// [text] as settings; [under] is the dotted table they sit in, for pyproject.toml.
pub fn parse(text: &str, source: &str, under: Option<&str>) -> Result<Config, String> {
    let doc: DocumentMut = text.parse().map_err(|e: toml_edit::TomlError| format!("{source}: {}", e.to_string().trim()))?;
    let mut root: &dyn TableLike = doc.as_table();
    if let Some(path) = under {
        for part in path.split('.') {
            match root.get(part).and_then(Item::as_table_like) {
                Some(t) => root = t,
                None => return Ok(Config::default()),
            }
        }
    }
    let prefix = under.map(|u| format!("{u}.")).unwrap_or_default();
    let mut c = Config { source: Some(source.to_string()), ..Config::default() };
    for (section, item) in root.iter() {
        let Some(known) = KNOWN.iter().find(|(s, _)| *s == section).map(|(_, k)| *k) else {
            c.unknown.push(format!("{prefix}{section}"));
            continue;
        };
        let Some(table) = item.as_table_like() else {
            return Err(format!("{source}: {prefix}{section} is a table, such as [{prefix}{section}]"));
        };
        for (key, value) in table.iter() {
            let at = format!("{prefix}{section}.{key}");
            if !known.contains(&key) {
                c.unknown.push(at);
                continue;
            }
            let text = || value.as_str().map(String::from).ok_or_else(|| format!("{source}: {at} is text, in quotes"));
            let flag = || value.as_bool().ok_or_else(|| format!("{source}: {at} is true or false"));
            match (section, key) {
                ("check", "strict") => c.check.strict = Some(flag()?),
                ("check", "ignore") => {
                    let list = value.as_array().ok_or_else(|| format!("{source}: {at} is a list of codes, such as [\"unused_local\"]"))?;
                    for v in list.iter() {
                        c.check.ignore.push(v.as_str().ok_or_else(|| format!("{source}: {at} holds codes in quotes"))?.to_string());
                    }
                }
                ("run", "locale") => {
                    let tag = text()?;
                    if !crate::project::is_locale_tag(&tag) {
                        return Err(format!("{source}: {at} is a language tag such as \"bn\" or \"pt-BR\", not \"{tag}\""));
                    }
                    c.run.locale = Some(tag);
                }
                ("run", "theme") => c.run.theme = Some(one_of(&text()?, &["light", "dark"], source, &at)?),
                ("run", "surface") => c.run.surface = Some(one_of(&text()?, &["panel", "float"], source, &at)?),
                ("run", "mock") => c.run.mock = Some(PathBuf::from(text()?)),
                ("run", "settings") => {
                    let t = value.as_table_like().ok_or_else(|| format!("{source}: {at} is a table of setting values, such as {{ units = \"metric\" }}"))?;
                    for (k, v) in t.iter() {
                        let shown = match v.as_value() {
                            Some(toml_edit::Value::String(s)) => s.value().clone(),
                            Some(toml_edit::Value::Boolean(b)) => b.value().to_string(),
                            Some(toml_edit::Value::Integer(i)) => i.value().to_string(),
                            Some(toml_edit::Value::Float(f)) => f.value().to_string(),
                            _ => return Err(format!("{source}: {at}.{k} is text, a number or true/false")),
                        };
                        c.run.settings.push((k.to_string(), shown));
                    }
                }
                ("test", "jobs") => {
                    let n = value.as_integer().filter(|n| *n >= 1).ok_or_else(|| format!("{source}: {at} is a whole number from 1"))?;
                    c.test.jobs = Some(n as usize);
                }
                ("test", "fail-fast") => c.test.fail_fast = Some(flag()?),
                ("pack", "output") => c.pack.output = Some(PathBuf::from(text()?)),
                ("pack", "shake-symbols") => c.pack.shake_symbols = Some(flag()?),
                ("device", "serial") => c.device.serial = Some(text()?),
                _ => c.unknown.push(at),
            }
        }
    }
    Ok(c)
}

fn one_of(v: &str, allowed: &[&str], source: &str, at: &str) -> Result<String, String> {
    if allowed.contains(&v) {
        Ok(v.to_string())
    } else {
        Err(format!("{source}: {at} is one of {}, not \"{v}\"", allowed.join(", ")))
    }
}

/// [path] from the config, read from the plugin folder [dir].
pub fn in_dir(dir: &Path, path: &Path) -> PathBuf {
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        dir.join(path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_every_section() {
        let c = parse(
            "[check]\nstrict = true\nignore = [\"unused_local\"]\n[run]\nlocale = \"bn\"\ntheme = \"dark\"\nmock = \"m.json\"\nsettings = { units = \"imperial\", count = 3 }\n[test]\njobs = 4\nfail-fast = true\n[pack]\noutput = \"dist\"\n[device]\nserial = \"x\"\n[extra]\n",
            FILE,
            None,
        )
        .unwrap();
        assert_eq!(c.check.strict, Some(true));
        assert_eq!(c.check.ignore, ["unused_local"]);
        assert_eq!(c.run.locale.as_deref(), Some("bn"));
        assert_eq!(c.run.theme.as_deref(), Some("dark"));
        assert_eq!(c.run.settings, [("units".to_string(), "imperial".to_string()), ("count".to_string(), "3".to_string())]);
        assert_eq!((c.test.jobs, c.test.fail_fast), (Some(4), Some(true)));
        assert_eq!(c.pack.output.as_deref(), Some(Path::new("dist")));
        assert_eq!(c.device.serial.as_deref(), Some("x"));
        assert_eq!(c.unknown, ["extra"]);
    }

    #[test]
    fn pyproject_reads_tool_wmpy_and_wrong_types_are_errors() {
        let c = parse("[project]\nname = \"x\"\n[tool.wmpy.check]\nstrict = true\n", "pyproject.toml", Some("tool.wmpy")).unwrap();
        assert_eq!(c.check.strict, Some(true));
        assert!(c.unknown.is_empty());
        assert_eq!(parse("[project]\n", "pyproject.toml", Some("tool.wmpy")).unwrap(), Config::default());
        assert!(parse("[check]\nstrict = \"yes\"\n", FILE, None).unwrap_err().contains("check.strict is true or false"));
        assert!(parse("[run]\ntheme = \"blue\"\n", FILE, None).unwrap_err().contains("one of light, dark"));
        assert!(parse("[check\n", FILE, None).is_err());
    }
}
