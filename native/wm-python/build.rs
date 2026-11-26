//! Deflates the standard library modules written in Python into `OUT_DIR`.
//! They are about 850 KB of source and a fifth of that deflated, and every
//! APK carries the library twice (two ABIs). `stdlib::packed!` names the
//! deflated copy; a module inflates the first time a script imports it.

use std::path::{Path, PathBuf};
use std::{env, fs};

fn main() {
    let out = PathBuf::from(env::var_os("OUT_DIR").expect("OUT_DIR")).join("stdlib");
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=src/stdlib");
    pack(Path::new("src/stdlib"), &out);
}

fn pack(dir: &Path, out: &Path) {
    fs::create_dir_all(out).expect("make the packed stdlib folder");
    let mut entries: Vec<_> = fs::read_dir(dir).unwrap_or_else(|e| panic!("{}: {e}", dir.display())).flatten().collect();
    entries.sort_by_key(|e| e.file_name());
    for entry in entries {
        let path = entry.path();
        let name = entry.file_name();
        if path.is_dir() {
            pack(&path, &out.join(&name));
        } else if path.extension().is_some_and(|x| x == "py") {
            println!("cargo:rerun-if-changed={}", path.display());
            let text = fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
            let packed = miniz_oxide::deflate::compress_to_vec(&text, 10);
            let mut file = name.into_string().expect("a UTF-8 file name");
            file.push_str(".deflate");
            fs::write(out.join(file), packed).expect("write a deflated module");
        }
    }
}
