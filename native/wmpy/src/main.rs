//! `wmpy`: the command-line tool for WM Keyboard's Python plugins.
//!
//! Each subcommand lives in its own module with its own `Args` and a
//! `run(args) -> u8` returning the exit code: 0 for success, 1 when the
//! plugin or check failed, 2 when wmpy itself could not do the work (bad
//! arguments, unreadable files, no network).
//!
//! The global flags come before or after the command: `-C <dir>` runs in
//! another folder, `--color` and `-q` decide the output ([output]).

mod budget;
mod check;
mod completions;
mod config;
mod device;
mod devhost;
mod diag;
mod editor;
mod explain;
mod fmt;
mod i18n;
mod look;
mod lsp;
mod mcp;
mod mock;
mod mockrec;
mod new;
mod output;
mod pack;
mod permissions;
mod pip;
mod preview;
mod profile;
mod project;
mod publish;
mod repl;
mod stubs;
mod testing;
mod tui;
mod upgrade;

use std::path::PathBuf;
use std::process::ExitCode;

use clap::{CommandFactory, FromArgMatches, Parser, Subcommand};

/// The tool for WM Keyboard's Python plugins.
#[derive(Parser, Debug)]
#[command(name = "wmpy", version = version(), about, propagate_version = true, after_help = AFTER_HELP)]
pub struct Cli {
    /// Run as if wmpy had been started in this folder
    #[arg(short = 'C', value_name = "DIR", global = true)]
    pub chdir: Option<PathBuf>,
    /// When to colour the output
    #[arg(long, value_enum, value_name = "WHEN", default_value_t = output::ColorChoice::Auto, global = true)]
    pub color: output::ColorChoice,
    /// Print only problems and results: no progress, hints or summaries of success
    #[arg(short, long, global = true)]
    pub quiet: bool,
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand, Debug)]
enum Command {
    /// Make a new plugin folder from a template
    New(new::Args),
    /// Check a plugin's code and plugin.json without running it
    Check(check::Args),
    /// Run a plugin's tests
    Test(testing::TestArgs),
    /// Run a plugin interactively in the terminal
    Run(tui::Args),
    /// Preview a plugin's panel in the browser, live as you edit
    Preview(preview::Args),
    /// Print a plugin's first draw as JSON
    Render(testing::RenderArgs),
    /// Build the .wmplugin file
    Pack(pack::Args),
    /// Add, check and remove pure-Python packages from PyPI
    Pip(pip::Args),
    /// Send a plugin to the community repository, or to your own
    Publish(publish::Args),
    /// Write type stubs of the wm package for other editors and checkers
    Stubs(stubs::Args),
    /// Install and manage the wmpy editor extensions
    Editor(editor::Args),
    /// Run the language server (for editors)
    Lsp(lsp::Args),
    /// Run the MCP server (for AI agents)
    Mcp(mcp::Args),
    /// Format a plugin's Python files
    Fmt(fmt::Args),
    /// Show which code needs each permission and host, and what nothing uses
    Permissions(permissions::Args),
    /// Extract, check and count a plugin's translations
    I18n(i18n::Args),
    /// Try Python and the wm package line by line
    Repl(repl::Args),
    /// How close each part of a run comes to the keyboard's limits
    Budget(budget::Args),
    /// Which functions take the time
    Profile(profile::Args),
    /// Record real replies into a mock file for tests
    Mock(mockrec::Args),
    /// Install a plugin on a phone over adb
    Push(device::PushArgs),
    /// Push to the phone on every save and stream its log
    Dev(device::DevArgs),
    /// Stream a plugin's log from a phone
    Logs(device::LogsArgs),
    /// List phones over adb and whether plugin developer mode is on
    Device(device::DeviceArgs),
    /// What a problem code from `wmpy check` means, and what to do about it
    Explain(explain::Args),
    /// Update wmpy to its newest release
    Upgrade(upgrade::Args),
    /// Print shell completions
    Completions(completions::Args),
}

/// The commands as `wmpy --help` lists them, in groups.
const GROUPS: &[(&str, &[&str])] = &[
    ("Start", &["new"]),
    ("Write and check", &["check", "fmt", "explain", "permissions", "i18n"]),
    ("Run", &["test", "run", "preview", "render", "repl"]),
    ("Measure", &["budget", "profile", "mock"]),
    ("Packages", &["pip"]),
    ("Ship", &["pack", "publish"]),
    ("Phone", &["push", "dev", "logs", "device"]),
    ("Editors and AI agents", &["editor", "lsp", "mcp", "stubs"]),
    ("wmpy itself", &["upgrade", "completions", "help"]),
];

const AFTER_HELP: &str = "\
Exit codes: 0 when it worked, 1 when the plugin or a check failed, 2 when wmpy could not do the work (a bad argument, an unreadable file, no network).

Examples:
  wmpy new weather --template weather    start a plugin
  wmpy check --strict && wmpy test       what CI runs
  wmpy preview --locale bn --theme dark  the panel in Bengali, dark
  wmpy -C plugins/weather pack           pack the plugin in another folder

Guide: https://wmkeyboard.pages.dev/plugins/wmpy/";

/// `wmpy 0.1.0 (wm-python <its version>, plugin API 5)`.
fn version() -> &'static str {
    Box::leak(format!("{} (wm-python {}, plugin API 5)", env!("CARGO_PKG_VERSION"), wm_python::interp::VERSION).into_boxed_str())
}

/// The top-level help, with the commands in [GROUPS].
fn grouped_help(cmd: &clap::Command) -> String {
    let subs: Vec<(String, String)> = cmd
        .get_subcommands()
        .filter(|s| !s.is_hide_set())
        .map(|s| (s.get_name().to_string(), s.get_about().map(|a| a.to_string()).unwrap_or_default()))
        .chain(std::iter::once(("help".to_string(), "Print this message or the help of the given command".to_string())))
        .collect();
    let width = subs.iter().map(|(n, _)| n.len()).max().unwrap_or(10);
    let mut out = String::from("{about-with-newline}\n{usage-heading} {usage}\n");
    for (title, names) in GROUPS {
        out.push_str(&format!("\n{title}:\n"));
        for name in *names {
            if let Some((n, about)) = subs.iter().find(|(n, _)| n == name) {
                out.push_str(&format!("  {n:<width$}  {about}\n"));
            }
        }
    }
    out.push_str("\nOptions:\n{options}{after-help}");
    out
}

/// The command line as clap reads it, with the grouped top-level help.
pub fn command() -> clap::Command {
    let cmd = Cli::command();
    let template = grouped_help(&cmd);
    cmd.help_template(template)
}

fn main() -> ExitCode {
    let matches = command().get_matches();
    let cli = match Cli::from_arg_matches(&matches) {
        Ok(c) => c,
        Err(e) => e.exit(),
    };
    output::set(cli.color, cli.quiet);
    if dies_quietly_on_a_closed_pipe(&cli.command) {
        // `wmpy explain | head`: stop, as other tools do, instead of panicking
        // on the next print. Commands that talk to sockets keep Rust's default.
        #[cfg(unix)]
        unsafe {
            libc::signal(libc::SIGPIPE, libc::SIG_DFL);
        }
    }
    if let Some(dir) = &cli.chdir {
        if let Err(e) = std::env::set_current_dir(dir) {
            eprintln!("error: -C {}: {e}", dir.display());
            return ExitCode::from(2);
        }
    }
    let notice = upgrade::Notice::start(notice_wanted(&cli.command));
    // Scripts recurse on the native stack; give every command room.
    let worker = std::thread::Builder::new().stack_size(256 << 20).spawn(move || match cli.command {
        Command::New(a) => new::run(a),
        Command::Check(a) => check::run(a),
        Command::Test(a) => testing::run_test(a),
        Command::Run(a) => tui::run(a),
        Command::Preview(a) => preview::run(a),
        Command::Render(a) => testing::run_render(a),
        Command::Pack(a) => pack::run(a),
        Command::Pip(a) => pip::run(a),
        Command::Publish(a) => publish::run(a),
        Command::Stubs(a) => stubs::run(a),
        Command::Editor(a) => editor::run(a),
        Command::Lsp(a) => lsp::run(a),
        Command::Mcp(a) => mcp::run(a),
        Command::Fmt(a) => fmt::run(a),
        Command::Permissions(a) => permissions::run(a),
        Command::I18n(a) => i18n::run(a),
        Command::Repl(a) => repl::run(a),
        Command::Budget(a) => budget::run(a),
        Command::Profile(a) => profile::run(a),
        Command::Mock(a) => mockrec::run(a),
        Command::Push(a) => device::push(a),
        Command::Dev(a) => device::dev(a),
        Command::Logs(a) => device::logs(a),
        Command::Device(a) => device::devices(a),
        Command::Explain(a) => explain::run(a),
        Command::Upgrade(a) => upgrade::run(a),
        Command::Completions(a) => completions::run(a),
    });
    let code = worker.expect("spawn").join().unwrap_or(1);
    notice.finish();
    ExitCode::from(code)
}

/// Commands that only read files and print: a closed stdout ends them.
fn dies_quietly_on_a_closed_pipe(command: &Command) -> bool {
    matches!(
        command,
        Command::Explain(_) | Command::Completions(_) | Command::Check(_) | Command::Fmt(_) | Command::Permissions(_) | Command::I18n(_) | Command::Test(_) | Command::Render(_) | Command::Pack(_) | Command::Stubs(_) | Command::New(_) | Command::Budget(_) | Command::Profile(_)
    )
}

/// Whether a run of [command] may say a newer wmpy is out: not for the
/// servers editors and agents start, not for output scripts read, and not
/// for the commands that run until stopped.
fn notice_wanted(command: &Command) -> bool {
    match command {
        Command::Lsp(_) | Command::Mcp(_) | Command::Completions(_) | Command::Upgrade(_) | Command::Render(_) => false,
        Command::Run(_) | Command::Preview(_) | Command::Dev(_) | Command::Logs(_) | Command::Repl(_) => false,
        Command::Check(a) => !a.json,
        Command::Test(a) => !a.json && !a.watch,
        Command::Pack(a) => !a.json && !a.entry_json,
        Command::New(a) => !a.json,
        _ => true,
    }
}
