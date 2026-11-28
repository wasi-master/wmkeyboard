//! How every command writes to the terminal: colour and `--quiet`, decided
//! once from the global flags and the environment.
//!
//! Colour (`--color auto|always|never`, auto by default): auto paints a
//! stream that is a terminal, and also CI logs that show colour
//! (`GITHUB_ACTIONS`), and follows the usual variables: `NO_COLOR` (any
//! value but empty) turns it off; `CLICOLOR_FORCE` or `FORCE_COLOR` (any
//! value but empty or `0`) turns it on; `CLICOLOR=0` and `TERM=dumb` turn it
//! off. `--color always` and `never` win over all of them.

use std::io::IsTerminal;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};

#[derive(clap::ValueEnum, Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ColorChoice {
    /// Colour on a terminal, and in CI logs that show it
    #[default]
    Auto,
    /// Always colour, even into a file or a pipe
    Always,
    /// Never colour
    Never,
}

static COLOR: AtomicU8 = AtomicU8::new(0);
static QUIET: AtomicBool = AtomicBool::new(false);

/// Called once, from main, with the global flags.
pub fn set(color: ColorChoice, quiet: bool) {
    COLOR.store(
        match color {
            ColorChoice::Auto => 0,
            ColorChoice::Always => 1,
            ColorChoice::Never => 2,
        },
        Ordering::Relaxed,
    );
    QUIET.store(quiet, Ordering::Relaxed);
}

/// `--quiet`: only problems and results, none of the progress and hints.
pub fn quiet() -> bool {
    QUIET.load(Ordering::Relaxed)
}

fn set_and_not(name: &str, off: &[&str]) -> bool {
    std::env::var(name).is_ok_and(|v| !v.is_empty() && !off.contains(&v.as_str()))
}

/// What auto decides for a stream that [is_terminal] or not.
fn auto(is_terminal: bool) -> bool {
    if std::env::var_os("NO_COLOR").is_some_and(|v| !v.is_empty()) {
        return false;
    }
    if set_and_not("CLICOLOR_FORCE", &["0"]) || set_and_not("FORCE_COLOR", &["0", "false"]) {
        return true;
    }
    if std::env::var("CLICOLOR").is_ok_and(|v| v == "0") {
        return false;
    }
    if std::env::var("GITHUB_ACTIONS").is_ok_and(|v| v == "true") {
        return true;
    }
    is_terminal && std::env::var("TERM").map_or(true, |t| t != "dumb")
}

fn decide(is_terminal: bool) -> bool {
    match COLOR.load(Ordering::Relaxed) {
        1 => true,
        2 => false,
        _ => auto(is_terminal),
    }
}

/// Whether to colour what goes to stderr.
pub fn stderr() -> bool {
    decide(std::io::stderr().is_terminal())
}

/// Whether to colour what goes to stdout.
pub fn stdout() -> bool {
    decide(std::io::stdout().is_terminal())
}

/// [text] in the ANSI style [code] when [color].
pub fn paint(color: bool, code: &str, text: &str) -> String {
    if color {
        format!("\x1b[{code}m{text}\x1b[0m")
    } else {
        text.to_string()
    }
}

/// A line of progress or a hint on stderr, left out with `--quiet`.
#[macro_export]
macro_rules! note {
    ($($t:tt)*) => {
        if !$crate::output::quiet() {
            eprintln!($($t)*);
        }
    };
}
