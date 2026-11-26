//! `wmpy`: the command-line tool for WM Keyboard's Python plugins.
//!
//! Each subcommand lives in its own module with its own `Args` and a
//! `run(args) -> u8` returning the exit code: 0 for success, 1 when the
//! plugin or check failed, 2 when wmpy itself could not do the work (bad
//! arguments, unreadable files, no network).

mod budget;
mod check;
mod completions;
mod device;
mod devhost;
mod diag;
mod editor;
mod fmt;
mod i18n;
mod lsp;
mod mcp;
mod mock;
mod mockrec;
mod new;
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

use std::process::ExitCode;

use clap::{Parser, Subcommand};

/// The tool for WM Keyboard's Python plugins.
#[derive(Parser, Debug)]
#[command(name = "wmpy", version = version(), about, propagate_version = true)]
pub struct Cli {
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
    /// Print shell completions
    Completions(completions::Args),
}

/// `wmpy 0.1.0 (wm-python <its version>, plugin API 5)`.
fn version() -> &'static str {
    Box::leak(format!("{} (wm-python {}, plugin API 5)", env!("CARGO_PKG_VERSION"), wm_python::interp::VERSION).into_boxed_str())
}

fn main() -> ExitCode {
    let cli = Cli::parse();
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
        Command::Completions(a) => completions::run(a),
    });
    ExitCode::from(worker.expect("spawn").join().unwrap_or(1))
}
