//! `wmpy push`, `wmpy dev`, `wmpy logs` and `wmpy device`: a plugin on a
//! phone over adb. Not built yet.

macro_rules! stub_args {
    ($name:ident) => {
        #[derive(clap::Args, Debug)]
        pub struct $name {
            /// Arguments, until this command is built
            #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
            pub rest: Vec<String>,
        }
    };
}
stub_args!(PushArgs);
stub_args!(DevArgs);
stub_args!(LogsArgs);
stub_args!(DeviceArgs);

pub fn push(_a: PushArgs) -> u8 {
    eprintln!("wmpy push: not built yet");
    2
}
pub fn dev(_a: DevArgs) -> u8 {
    eprintln!("wmpy dev: not built yet");
    2
}
pub fn logs(_a: LogsArgs) -> u8 {
    eprintln!("wmpy logs: not built yet");
    2
}
pub fn devices(_a: DeviceArgs) -> u8 {
    eprintln!("wmpy device: not built yet");
    2
}
