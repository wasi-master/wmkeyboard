//! `wmpy run`: a plugin's panel in the terminal, live as you edit.
//!
//! ```text
//! wmpy run [dir] [--mock file.json] [--set key=value]... [--theme dark|light] [--width N] [--no-watch]
//! ```
//!
//! The panel is drawn at a phone's width (52 columns unless `--width`
//! says), with a pane beside it (Console, Events, Storage, Field, Calls).
//! Host calls go to [crate::devhost]: real https to the manifest's hosts, a
//! pretend field and clipboard, pretend answers for the rest. Saving any
//! file in the folder reloads the plugin; what it stored stays.

pub mod app;
pub mod layout;

use std::io::IsTerminal;
use std::path::PathBuf;
use std::time::Duration;

use ratatui::crossterm::event::{self, DisableBracketedPaste, DisableMouseCapture, EnableBracketedPaste, EnableMouseCapture};
use ratatui::crossterm::execute;

use crate::devhost::{parse_set, DevHost, Options};

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// A JSON file answering host calls by op name, as `wmpy test` reads it
    #[arg(long)]
    pub mock: Option<PathBuf>,
    /// A setting's value for this run, over plugin.json's default (repeatable)
    #[arg(long = "set", value_name = "KEY=VALUE", value_parser = parse_set)]
    pub sets: Vec<(String, String)>,
    /// The theme the plugin sees (plugin.dark)
    #[arg(long, value_parser = ["dark", "light"], default_value = "light")]
    pub theme: String,
    /// The panel's width in columns (a phone is about 48 to 60)
    #[arg(long, default_value_t = 52)]
    pub width: u16,
    /// Do not reload when a file in the folder changes
    #[arg(long)]
    pub no_watch: bool,
}

pub fn run(args: Args) -> u8 {
    if !std::io::stdout().is_terminal() || !std::io::stdin().is_terminal() {
        eprintln!("wmpy run needs a terminal; use `wmpy render` or `wmpy preview --print-url` from scripts");
        return 2;
    }
    let opts = Options { dir: args.dir.clone(), mock: args.mock.clone(), sets: args.sets.clone(), dark: args.theme == "dark", watch: !args.no_watch };
    let host = match DevHost::open(opts) {
        Ok(h) => h,
        Err(e) => {
            eprintln!("error: {e}");
            return 2;
        }
    };
    let mut app = app::App::new(host, args.width);
    let mut terminal = match ratatui::try_init() {
        Ok(t) => t,
        Err(e) => {
            eprintln!("error: cannot take over the terminal: {e}");
            return 2;
        }
    };
    let _ = execute!(std::io::stdout(), EnableMouseCapture, EnableBracketedPaste);
    let result = event_loop(&mut terminal, &mut app);
    let _ = execute!(std::io::stdout(), DisableMouseCapture, DisableBracketedPaste);
    ratatui::restore();
    match result {
        Ok(()) => {
            if let Some(e) = &app.host.error {
                // The last error stays readable after the screen is gone.
                eprint!("{}", e.render(&crate::diag::folder_source(app.host.dir()), crate::diag::color_stderr()));
            }
            0
        }
        Err(e) => {
            eprintln!("error: {e}");
            2
        }
    }
}

fn event_loop(terminal: &mut ratatui::DefaultTerminal, app: &mut app::App) -> std::io::Result<()> {
    let mut drawn = None;
    loop {
        let version = (app.host.version(), app.focus, app.editing, app.in_pane, app.help, app.scroll);
        if drawn != Some(version) {
            terminal.draw(|f| app.draw(f))?;
            drawn = Some((app.host.version(), app.focus, app.editing, app.in_pane, app.help, app.scroll));
        }
        let wait = app.host.next_wake().min(Duration::from_millis(250));
        if event::poll(wait)? {
            // Take every waiting event before drawing again.
            loop {
                app.handle(event::read()?);
                if app.quit || !event::poll(Duration::ZERO)? {
                    break;
                }
            }
            drawn = None;
        }
        if app.quit {
            return Ok(());
        }
        if app.host.tick() {
            drawn = None;
        }
    }
}

#[cfg(test)]
mod tests;
