//! `wmpy`: the command-line tool for WM Keyboard's Python plugins.
//!
//! Each subcommand lives in its own module with its own `Args` and a
//! `run(args) -> u8` returning the exit code: 0 for success, 1 when the
//! plugin or check failed, 2 when wmpy itself could not do the work (bad
//! arguments, unreadable files, no network).

mod check;
mod completions;
mod devhost;
mod diag;
mod editor;
mod lsp;
mod mcp;
mod mock;
mod new;
mod pack;
mod pip;
mod preview;
mod project;
mod publish;
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
        Command::Completions(a) => completions::run(a),
    });
    ExitCode::from(worker.expect("spawn").join().unwrap_or(1))
}
