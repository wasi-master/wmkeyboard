//! File previews and thumbnails (API 5): `@plugin.preview`, `@plugin.thumbnail`,
//! `wm.preview` and `wm.formats`, the preview-only widgets and the
//! `preview.read` op.

use std::cell::RefCell;
use std::rc::Rc;

use serde_json::{json, Value as Json};
use wm_python::host::Session;

fn session(permissions: &[&str], source: &str) -> Session {
    let config = json!({"id": "com.example.previews", "version": "1.0.0", "permissions": permissions, "mode": "action", "apiVersion": 5});
    let mut s = Session::new(&config.to_string()).unwrap();
    let loaded: Json = serde_json::from_str(&s.load(source)).unwrap();
    assert_eq!(loaded["ok"], true, "{loaded:#}");
    s
}

fn big<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    std::thread::Builder::new().stack_size(64 << 20).spawn(f).unwrap().join().unwrap()
}

fn request(id: &str, name: &str, size: usize, truncated: bool) -> String {
    json!({"id": id, "name": name, "mime": "application/octet-stream", "size": size, "modified": 1_700_000_000, "truncated": truncated}).to_string()
}

fn first(r: &Json) -> Json {
    r["ui"]["root"][0].clone()
}

/// A zip holding [entries], the first stored and the rest deflated.
fn zip(entries: &[(&str, &[u8])]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut central = Vec::new();
    for (i, (name, body)) in entries.iter().enumerate() {
        let (method, data) = if i == 0 { (0u16, body.to_vec()) } else { (8u16, miniz_oxide::deflate::compress_to_vec(body, 6)) };
        let offset = out.len() as u32;
        let header = |sig: u32, central_dir: bool| {
            let mut h = Vec::new();
            h.extend(sig.to_le_bytes());
            if central_dir {
                h.extend(20u16.to_le_bytes());
            }
            h.extend(20u16.to_le_bytes());
            h.extend(0x0800u16.to_le_bytes());
            h.extend(method.to_le_bytes());
            h.extend(0u16.to_le_bytes());
            h.extend(0x5821u16.to_le_bytes());
            h.extend(0u32.to_le_bytes());
            h.extend((data.len() as u32).to_le_bytes());
            h.extend((body.len() as u32).to_le_bytes());
            h.extend((name.len() as u16).to_le_bytes());
            h.extend(0u16.to_le_bytes());
            if central_dir {
                h.extend([0u8; 6]);
                h.extend(0u32.to_le_bytes());
                h.extend(offset.to_le_bytes());
            }
            h.extend(name.as_bytes());
            h
        };
        out.extend(header(0x0403_4b50, false));
        out.extend(&data);
        central.extend(header(0x0201_4b50, true));
    }
    let start = out.len() as u32;
    out.extend(&central);
    out.extend(0x0605_4b50u32.to_le_bytes());
    out.extend([0u8; 4]);
    out.extend((entries.len() as u16).to_le_bytes());
    out.extend((entries.len() as u16).to_le_bytes());
    out.extend((central.len() as u32).to_le_bytes());
    out.extend(start.to_le_bytes());
    out.extend(0u16.to_le_bytes());
    out
}

#[test]
fn a_preview_draws_a_table_from_csv() {
    big(|| {
        let src = r#"
from wm import plugin, ui, formats

@plugin.preview("csv")
def show(file):
    rows = formats.csv(file.text())
    ui.label(file.name + " " + file.extension)
    ui.table(rows[1:], header=rows[0], align=["start", "end"])
"#;
        let mut s = session(&["previews"], src);
        let r: Json = serde_json::from_str(&s.preview(&request("csv", "Sales.CSV", 20, false), b"name;sum\n\"a; b\";3\nc;4\n")).unwrap();
        assert_eq!(r["ok"], true, "{r:#}");
        assert_eq!(first(&r)["text"], "Sales.CSV csv");
        let table = &r["ui"]["root"][1];
        assert_eq!(table["type"], "table");
        assert_eq!(table["header"], json!(["name", "sum"]));
        assert_eq!(table["rows"], json!([["a; b", "3"], ["c", "4"]]));
        assert_eq!(table["align"], json!(["start", "end"]));
        assert_eq!(r["phases"][0]["phase"], "preview");
    });
}

#[test]
fn a_missing_preview_is_no_preview_and_errors_are_runtime() {
    big(|| {
        let src = "from wm import plugin\n\n@plugin.preview('bad')\ndef bad(file):\n    raise ValueError('nope')\n";
        let mut s = session(&["previews"], src);
        let r: Json = serde_json::from_str(&s.preview(&request("other", "x.bin", 1, false), b"x")).unwrap();
        assert_eq!(r["error"]["kind"], "no_preview");
        let r: Json = serde_json::from_str(&s.preview(&request("bad", "x.bin", 1, false), b"x")).unwrap();
        assert_eq!(r["error"]["kind"], "runtime");
        assert_eq!(r["error"]["message"], "ValueError: nope");
    });
}

#[test]
fn zip_and_xml_read_an_office_document() {
    big(|| {
        let doc = br#"<?xml version="1.0"?><w:document xmlns:w="x"><w:body><w:p><w:r><w:t>Hello &amp; </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p><w:p><w:r><w:t>Two</w:t></w:r></w:p></w:body></w:document>"#;
        let data = zip(&[("mimetype", b"application/x"), ("word/document.xml", doc)]);
        let src = r#"
from wm import plugin, ui
from wm.preview import xml

@plugin.preview("docx")
def show(file):
    names = [e["name"] for e in file.zip()]
    ui.label(",".join(names))
    root = xml(file.zip_read("word/document.xml"))
    for p in root.find("body").findall("w:p"):
        ui.label(p.all_text())
    ui.label(root.first("t").text + "|" + root.tag + "|" + root.name)
"#;
        let mut s = session(&["previews"], src);
        let r: Json = serde_json::from_str(&s.preview(&request("docx", "a.docx", data.len(), false), &data)).unwrap();
        assert_eq!(r["ok"], true, "{r:#}");
        let texts: Vec<&str> = r["ui"]["root"].as_array().unwrap().iter().map(|w| w["text"].as_str().unwrap()).collect();
        assert_eq!(texts, vec!["mimetype,word/document.xml", "Hello & world", "Two", "Hello & |w:document|document"]);
    });
}

#[test]
fn a_thumbnail_answers_base64_bytes_or_null() {
    big(|| {
        let src = r#"
from wm import plugin

@plugin.thumbnail("img")
def thumb(file):
    if file.size == 0:
        return None
    return file.data[:3]
"#;
        let mut s = session(&["previews"], src);
        let r: Json = serde_json::from_str(&s.thumbnail(&request("img", "a.x", 4, false), b"\x89PNG")).unwrap();
        assert_eq!(r["ok"], true, "{r:#}");
        assert_eq!(r["value"], "iVBO");
        let r: Json = serde_json::from_str(&s.thumbnail(&request("img", "a.x", 0, false), b"")).unwrap();
        assert_eq!(r["value"], Json::Null);
        assert_eq!(r["phases"][0]["phase"], "thumbnail");
    });
}

#[test]
fn read_past_the_head_asks_the_keyboard_only_in_a_preview() {
    big(|| {
        let src = r#"
from wm import plugin, ui, _core

@plugin.preview("tail")
def show(file):
    ui.label(file.read(0, 2).decode())
    ui.label(file.read(10, 3).decode())

@plugin.action("sneak")
def sneak(text):
    return _core.call("preview.read", {"offset": 0, "length": 4})
"#;
        let mut s = session(&["previews"], src);
        let seen = Rc::new(RefCell::new(Vec::new()));
        let seen2 = seen.clone();
        s.set_bridge(Some(Box::new(move |op: &str, args: &str| {
            seen2.borrow_mut().push((op.to_string(), args.to_string()));
            json!({"value": "eHl6"}).to_string()
        })));
        let r: Json = serde_json::from_str(&s.preview(&request("tail", "big.log", 100, true), b"abcd")).unwrap();
        assert_eq!(r["ok"], true, "{r:#}");
        assert_eq!(r["ui"]["root"][0]["text"], "ab");
        assert_eq!(r["ui"]["root"][1]["text"], "xyz");
        assert_eq!(seen.borrow()[0].0, "preview.read");
        let t: Json = serde_json::from_str(&s.transform("", "sneak")).unwrap();
        assert_eq!(t["error"]["exception"], "PermissionError", "{t:#}");
    });
}

#[test]
fn preview_read_needs_the_permission() {
    big(|| {
        let src = "from wm import plugin, ui\n\n@plugin.preview('t')\ndef show(file):\n    ui.label(file.read(10, 1))\n";
        let mut s = session(&[], src);
        let r: Json = serde_json::from_str(&s.preview(&request("t", "x", 100, true), b"ab")).unwrap();
        assert_eq!(r["error"]["exception"], "PermissionError");
    });
}

#[test]
fn code_and_font_samples_and_big_images() {
    big(|| {
        let src = r#"
from wm import plugin, ui

@plugin.preview("f")
def show(file):
    ui.code("x = 1\n" * 3, language="PY")
    ui.font_sample("Quick fox", sizes=[2, 200, 20])
    ui.image(bytes(200000))

def render():
    ui.font_sample("x")
    ui.image(bytes(90000))
"#;
        let mut s = session(&["previews"], src);
        let r: Json = serde_json::from_str(&s.preview(&request("f", "a.ttf", 4, false), b"0000")).unwrap();
        assert_eq!(r["ok"], true, "{r:#}");
        let root = &r["ui"]["root"];
        assert_eq!(root[0]["type"], "code");
        assert_eq!(root[0]["language"], "py");
        assert_eq!(root[1], json!({"type": "font_sample", "text": "Quick fox", "sizes": [6, 96, 20]}));
        assert_eq!(root[2]["type"], "image");
        assert!(root[2]["src"].as_str().unwrap().len() > 260_000);

        // A panel may not draw a font sample, but its big images work now too.
        let config = json!({"id": "p", "version": "1", "permissions": [], "mode": "panel", "apiVersion": 5});
        let mut panel = Session::new(&config.to_string()).unwrap();
        let r: Json = serde_json::from_str(&panel.load(src)).unwrap();
        assert_eq!(r["ok"], true, "{r:#}");
        assert_eq!(r["ui"]["root"].as_array().unwrap().len(), 1);
        assert_eq!(r["ui"]["repairs"][0]["code"], "PREVIEW_ONLY");
    });
}

#[test]
fn formats_read_struct_ini_bencode_tar_and_gzip() {
    big(|| {
        let mut tar = vec![0u8; 512];
        tar[..5].copy_from_slice(b"a.txt");
        tar[124..135].copy_from_slice(b"00000000005");
        tar[156] = b'0';
        tar.extend(b"hello");
        tar.extend(vec![0u8; 507 + 1024]);
        let gz = {
            let mut g = vec![0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 3];
            g.extend(miniz_oxide::deflate::compress_to_vec(b"gzipped", 6));
            g.extend([0u8; 8]);
            g
        };
        let mut data = tar.clone();
        data.extend(&gz);
        let gz_at = tar.len();
        let src = format!(
            r#"
from wm import plugin, ui, formats
from wm.preview import inflate

@plugin.preview("t")
def show(file):
    d = file.data
    ui.label(str(formats.unpack(">HB", b"\x01\x02\x03")) + str(formats.calcsize("<I2h")))
    ui.label(str(formats.ini("top=1\n[s]\nk: v\n; note")))
    t = formats.bencode(b"d4:infod4:name3:abc6:lengthi42eee")
    ui.label(t["info"]["name"].decode() + str(t["info"]["length"]))
    e = formats.tar(d[:{gz_at}])[0]
    ui.label(e["name"] + ":" + d[e["offset"]:e["offset"] + e["size"]].decode())
    ui.label(inflate(d[{gz_at}:]).decode())
"#
        );
        let mut s = session(&["previews"], &src);
        let r: Json = serde_json::from_str(&s.preview(&request("t", "x", data.len(), false), &data)).unwrap();
        assert_eq!(r["ok"], true, "{r:#}");
        let texts: Vec<&str> = r["ui"]["root"].as_array().unwrap().iter().map(|w| w["text"].as_str().unwrap()).collect();
        assert_eq!(texts, vec!["(258, 3)8", "{'': {'top': '1'}, 's': {'k': 'v'}}", "abc42", "a.txt:hello", "gzipped"]);
    });
}

#[test]
fn meta_lists_previews_and_thumbnails() {
    big(|| {
        let src = r#"
from wm import plugin, ui

@plugin.preview("b")
def b(file):
    pass

@plugin.thumbnail("b")
def bt(file):
    pass

def render():
    ui.label("x")
"#;
        let config = json!({"id": "p", "version": "1", "permissions": ["previews"], "mode": "panel", "apiVersion": 5});
        let mut panel = Session::new(&config.to_string()).unwrap();
        let r: Json = serde_json::from_str(&panel.load(src)).unwrap();
        assert_eq!(r["host"]["previews"], json!(["b"]));
        assert_eq!(r["host"]["thumbnails"], json!(["b"]));
    });
}

#[test]
fn lines_split_windows_and_old_mac_line_ends() {
    big(|| {
        // The interpreter's splitlines() keeps a "\r\n" cluster whole, so
        // lines() splits on the line ends itself.
        let src = "from wm import plugin, ui\n\n@plugin.preview('txt')\ndef show(file):\n    ui.label('|'.join(file.lines()))\n    ui.label(str(len(file.lines(2))))\n";
        let mut s = session(&["previews"], src);
        let r: Json = serde_json::from_str(&s.preview(&request("txt", "a.txt", 14, false), b"a\r\nb\rc\n\nd\r\n")).unwrap();
        assert_eq!(r["ok"], true, "{r:#}");
        assert_eq!(first(&r)["text"], "a|b|c||d");
        assert_eq!(r["ui"]["root"][1]["text"], "2");
    });
}
