//! `wmpy lsp`: a server over stdin and stdout. Not built yet.

#[derive(clap::Args, Debug)]
pub struct Args {
    /// Accepted for editors that pass it; stdio is the only transport
    #[arg(long)]
    pub stdio: bool,
}

pub fn run(_args: Args) -> u8 {
    eprintln!("wmpy lsp: not built yet");
    2
}
