//! Pretend answers for what only a phone has, the same shapes and the same
//! made-up data as the web editor's (docs/src/store/lib/plugin-host.ts), so
//! a plugin's code paths run on a computer too. Times are in UTC: wmpy has
//! no time zone of its own.

use base64::Engine;
use serde_json::{json, Value as Json};

/// Where `location.get` says the phone is: Dhaka, as in the web editor.
pub const PLACE: (f64, f64) = (23.8103, 90.4125);

pub const SENSORS: &[&str] = &["accelerometer", "gyroscope", "magnetometer", "light", "steps", "proximity", "pressure"];

pub fn ai_reply(prompt: &str) -> String {
    let asked: String = prompt.split_whitespace().collect::<Vec<_>>().join(" ");
    let quoted = if asked.chars().count() > 80 { format!("{}…", asked.chars().take(80).collect::<String>()) } else { asked };
    format!("This is a pretend reply from wmpy, which has no AI model. You asked: “{quoted}”. On the phone, the model the user chose in the keyboard's settings answers instead.")
}

pub fn b64(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

/// A reading for [kind] at [t] seconds: the phone lying on a table, gently nudged.
pub fn reading(kind: &str, t: f64, n: u64) -> Json {
    let wobble = |f: f64, a: f64| ((t * f).sin() * a * 1000.0).round() / 1000.0;
    match kind {
        "accelerometer" => json!({"x": wobble(1.3, 0.4), "y": wobble(0.9, 0.3), "z": ((9.81 + wobble(2.1, 0.1)) * 1000.0).round() / 1000.0}),
        "gyroscope" => json!({"x": wobble(1.1, 0.05), "y": wobble(0.7, 0.05), "z": wobble(0.5, 0.02)}),
        "magnetometer" => json!({"x": 21.5 + wobble(0.3, 1.0), "y": -4.2 + wobble(0.4, 1.0), "z": -39.8 + wobble(0.2, 1.0)}),
        "light" => json!({"value": (320.0 + (t * 0.5).sin() * 60.0).round()}),
        "steps" => json!({"value": n / 2}),
        "proximity" => json!({"value": 5}),
        _ => json!({"value": ((1013.25 + wobble(0.1, 0.4)) * 100.0).round() / 100.0}),
    }
}

/// A stand-up each weekday and lunch on Wednesdays, in UTC.
pub fn events(start: f64, end: f64) -> Vec<Json> {
    let mut out = Vec::new();
    let first = (start / 86400.0).floor() as i64;
    for (n, day) in (first..).take(62).enumerate() {
        let base = day * 86400;
        if base as f64 >= end {
            break;
        }
        // 1970-01-01 was a Thursday: 0 is Sunday.
        let weekday = (day + 4).rem_euclid(7);
        let at = |h: i64, m: i64| base + h * 3600 + m * 60;
        if (1..=5).contains(&weekday) {
            out.push(json!({"id": 1000 + n * 2, "title": "Stand-up", "start": at(9, 30), "end": at(9, 45), "all_day": false, "location": null, "calendar": 1, "recurring": true}));
        }
        if weekday == 3 {
            out.push(json!({"id": 1001 + n * 2, "title": "Lunch with Sam", "start": at(13, 0), "end": at(14, 0), "all_day": false, "location": "Cafe", "calendar": 1, "recurring": false}));
        }
    }
    out.retain(|e| e["end"].as_f64().unwrap_or(0.0) > start && e["start"].as_f64().unwrap_or(0.0) < end);
    out
}

#[derive(Clone, Debug)]
pub struct Task {
    pub id: i64,
    pub text: String,
    pub done: bool,
    pub due: Option<f64>,
    pub all_day: bool,
    pub parent: Option<i64>,
}

pub fn tasks() -> Vec<Task> {
    let t = |id, text: &str, done, parent| Task { id, text: text.into(), done, due: None, all_day: false, parent };
    vec![t(1, "Reply to the landlord", false, None), t(2, "Plan the trip", false, None), t(3, "Book the train", true, Some(2)), t(4, "Find a hotel", false, Some(2))]
}

fn task_shape(t: &Task) -> Json {
    json!({"id": t.id, "text": t.text, "done": t.done, "due": t.due, "all_day": t.all_day})
}

/// The pretend To-do list's top-level tasks with their steps, as `todo.list` answers.
pub fn todo_tree(tasks: &[Task], with_done: bool) -> Vec<Json> {
    tasks
        .iter()
        .filter(|t| t.parent.is_none() && (with_done || !t.done))
        .map(|t| {
            let mut v = task_shape(t);
            v["steps"] = Json::Array(tasks.iter().filter(|c| c.parent == Some(t.id)).map(task_shape).collect());
            v
        })
        .collect()
}

const TEXT_FILE: &str = "Shopping\n- rice\n- lentils\n- green chillies\n";

/// What `files.pick(kind)` hands back, or None for a video (wmpy has none).
pub fn file(kind: &str) -> Option<Json> {
    let k = kind.to_ascii_lowercase();
    if k == "image" || k.starts_with("image/") {
        return Some(json!({"name": "sample.png", "mime": "image/png", "data": b64(&png(160, 120))}));
    }
    if k == "audio" || k.starts_with("audio/") {
        return Some(json!({"name": "sample.wav", "mime": "audio/wav", "data": b64(&wav())}));
    }
    if k == "video" || k.starts_with("video/") {
        return None;
    }
    if k == "application/json" {
        return Some(json!({"name": "sample.json", "mime": "application/json", "data": b64(b"{\"items\": [\"rice\", \"lentils\", \"green chillies\"]}\n")}));
    }
    Some(json!({"name": "sample.txt", "mime": "text/plain", "data": b64(TEXT_FILE.as_bytes())}))
}

pub fn photo() -> Json {
    json!({"name": "photo.png", "mime": "image/png", "data": b64(&png(160, 120))})
}

/// A [w]x[h] PNG in the web editor's colours: a gradient from #6750a4 to #7d5260.
pub fn png(w: u32, h: u32) -> Vec<u8> {
    let (a, b) = ([0x67u8, 0x50, 0xa4], [0x7du8, 0x52, 0x60]);
    let mut raw = Vec::with_capacity(((w * 3 + 1) * h) as usize);
    for y in 0..h {
        raw.push(0);
        for x in 0..w {
            let t = (x + y) as f32 / (w + h).max(1) as f32;
            for c in 0..3 {
                raw.push((a[c] as f32 + (b[c] as f32 - a[c] as f32) * t) as u8);
            }
        }
    }
    // zlib with stored (uncompressed) blocks: no compressor needed.
    let mut z = vec![0x78, 0x01];
    let mut chunks = raw.chunks(65535).peekable();
    while let Some(chunk) = chunks.next() {
        z.push(if chunks.peek().is_none() { 1 } else { 0 });
        let len = chunk.len() as u16;
        z.extend_from_slice(&len.to_le_bytes());
        z.extend_from_slice(&(!len).to_le_bytes());
        z.extend_from_slice(chunk);
    }
    z.extend_from_slice(&adler32(&raw).to_be_bytes());
    let mut out = b"\x89PNG\r\n\x1a\n".to_vec();
    let mut ihdr = Vec::new();
    ihdr.extend_from_slice(&w.to_be_bytes());
    ihdr.extend_from_slice(&h.to_be_bytes());
    ihdr.extend_from_slice(&[8, 2, 0, 0, 0]);
    for (kind, data) in [(&b"IHDR"[..], ihdr), (&b"IDAT"[..], z), (&b"IEND"[..], Vec::new())] {
        out.extend_from_slice(&(data.len() as u32).to_be_bytes());
        let mut crc_input = kind.to_vec();
        crc_input.extend_from_slice(&data);
        out.extend_from_slice(&crc_input);
        out.extend_from_slice(&crc32(&crc_input).to_be_bytes());
    }
    out
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for &x in data {
        a = (a + x as u32) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

fn crc32(data: &[u8]) -> u32 {
    let mut crc = 0xffff_ffffu32;
    for &x in data {
        crc ^= x as u32;
        for _ in 0..8 {
            crc = if crc & 1 != 0 { (crc >> 1) ^ 0xedb8_8320 } else { crc >> 1 };
        }
    }
    !crc
}

/// One second of silence, as an 8 kHz 8-bit WAV file.
pub fn wav() -> Vec<u8> {
    let rate: u32 = 8000;
    let mut out = Vec::with_capacity(44 + rate as usize);
    out.extend_from_slice(b"RIFF");
    out.extend_from_slice(&(36 + rate).to_le_bytes());
    out.extend_from_slice(b"WAVEfmt ");
    out.extend_from_slice(&16u32.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&rate.to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&8u16.to_le_bytes());
    out.extend_from_slice(b"data");
    out.extend_from_slice(&rate.to_le_bytes());
    out.resize(44 + rate as usize, 128);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_pretend_picture_is_a_png() {
        let p = png(4, 3);
        assert_eq!(&p[..8], b"\x89PNG\r\n\x1a\n");
        assert_eq!(&p[12..16], b"IHDR");
        assert_eq!(crc32(b"IEND"), 0xae42_6082);
        assert_eq!(wav().len(), 8044);
    }

    #[test]
    fn pretend_events_fall_on_weekdays() {
        // 2026-11-23 is a Monday.
        let monday = 1_795_392_000.0;
        assert_eq!(events(monday, monday + 86400.0)[0]["start"], json!(1_795_392_000i64 + 9 * 3600 + 30 * 60));
        let week = events(monday, monday + 7.0 * 86400.0);
        assert_eq!(week.iter().filter(|e| e["title"] == "Stand-up").count(), 5);
        assert_eq!(week.iter().filter(|e| e["title"] == "Lunch with Sam").count(), 1);
    }
}
