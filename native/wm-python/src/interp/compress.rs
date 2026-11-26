//! Deflate for the `zlib` and `gzip` modules, over miniz_oxide. The zlib
//! and gzip wrappers and their checksums are read here; `src/stdlib/zlib.py`
//! writes them.

use miniz_oxide::deflate::core::{create_comp_flags_from_zip_params, CompressorOxide};
use miniz_oxide::inflate::stream::{inflate, InflateState};
use miniz_oxide::{DataFormat, MZError, MZFlush, MZStatus};

fn crc_table() -> &'static [u32; 256] {
    static TABLE: std::sync::OnceLock<[u32; 256]> = std::sync::OnceLock::new();
    TABLE.get_or_init(|| {
        let mut t = [0u32; 256];
        for (i, slot) in t.iter_mut().enumerate() {
            let mut c = i as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
            }
            *slot = c;
        }
        t
    })
}

/// CRC-32 as zlib's, continuing from [start].
pub(crate) fn crc32(data: &[u8], start: u32) -> u32 {
    let t = crc_table();
    let mut c = !start;
    for &b in data {
        c = t[((c ^ u32::from(b)) & 0xFF) as usize] ^ (c >> 8);
    }
    !c
}

/// Adler-32, continuing from [start].
pub(crate) fn adler32(data: &[u8], start: u32) -> u32 {
    let (mut a, mut b) = (start & 0xFFFF, start >> 16);
    for chunk in data.chunks(5552) {
        for &x in chunk {
            a += u32::from(x);
            b += a;
        }
        a %= 65521;
        b %= 65521;
    }
    (b << 16) | a
}

/// Raw deflate blocks for [data]: the stream's last block when [last],
/// else blocks closed by a sync flush, so more can follow.
pub(crate) fn deflate_raw(data: &[u8], level: i32, last: bool) -> Vec<u8> {
    let flags = create_comp_flags_from_zip_params(level, -15, 0);
    let mut c = Box::new(CompressorOxide::new(flags));
    let mut out = Vec::with_capacity(data.len() / 2 + 64);
    let mut buf = vec![0u8; 64 * 1024];
    let mut input = data;
    let flush = if last { MZFlush::Finish } else { MZFlush::Sync };
    loop {
        let r = miniz_oxide::deflate::stream::deflate(&mut c, input, &mut buf, flush);
        input = &input[r.bytes_consumed..];
        out.extend_from_slice(&buf[..r.bytes_written]);
        match r.status {
            Ok(MZStatus::StreamEnd) => break,
            Ok(_) if input.is_empty() && r.bytes_written < buf.len() => break,
            Ok(_) => {}
            Err(_) => break,
        }
    }
    out
}

/// What [inflate_wrapped] read: the bytes out, how much input it took, and
/// whether the stream (its trailer too) came to its end.
pub(crate) struct Inflated {
    pub data: Vec<u8>,
    pub consumed: usize,
    pub finished: bool,
}

fn data_error(what: &str) -> String {
    format!("Error -3 while decompressing data: {what}")
}

/// Where a gzip member's deflate data starts, or None when the header is
/// not all there yet.
fn gzip_header(d: &[u8]) -> Result<Option<usize>, String> {
    if d.len() < 10 {
        return if d.iter().zip([0x1f, 0x8b, 8]).all(|(a, b)| *a == b) { Ok(None) } else { Err(data_error("incorrect header check")) };
    }
    if d[0] != 0x1f || d[1] != 0x8b {
        return Err(data_error("incorrect header check"));
    }
    if d[2] != 8 {
        return Err(data_error("unknown compression method"));
    }
    let flags = d[3];
    let mut at = 10;
    if flags & 4 != 0 {
        if d.len() < at + 2 {
            return Ok(None);
        }
        at += 2 + usize::from(u16::from_le_bytes([d[at], d[at + 1]]));
    }
    for bit in [8, 16] {
        if flags & bit != 0 {
            match d.get(at..).and_then(|rest| rest.iter().position(|&b| b == 0)) {
                Some(n) => at += n + 1,
                None => return Ok(None),
            }
        }
    }
    if flags & 2 != 0 {
        at += 2;
    }
    Ok((at <= d.len()).then_some(at))
}

/// Inflates one zlib, gzip or raw stream from the start of [d], as zlib's
/// `wbits` picks: 8..=15 zlib, -15..=-8 raw, 24..=31 gzip, 40..=47 either
/// (and 0, zlib). More than [limit] bytes out is an error.
pub(crate) fn inflate_wrapped(d: &[u8], wbits: i64, limit: usize) -> Result<Inflated, String> {
    enum Wrap {
        Zlib,
        Gzip,
        Raw,
    }
    let wrap = match wbits {
        -15..=-8 => Wrap::Raw,
        0 | 8..=15 => Wrap::Zlib,
        24..=31 => Wrap::Gzip,
        40..=47 if d.starts_with(&[0x1f, 0x8b]) => Wrap::Gzip,
        40..=47 => Wrap::Zlib,
        _ => return Err("Error -2 while preparing to decompress data: inconsistent stream state".into()),
    };
    let unfinished = |data: Vec<u8>| Ok(Inflated { data, consumed: d.len(), finished: false });
    let start = match wrap {
        Wrap::Raw => 0,
        Wrap::Gzip => match gzip_header(d)? {
            Some(at) => at,
            None => return unfinished(Vec::new()),
        },
        Wrap::Zlib => {
            if d.len() < 2 {
                return unfinished(Vec::new());
            }
            if (u16::from(d[0]) * 256 + u16::from(d[1])) % 31 != 0 {
                return Err(data_error("incorrect header check"));
            }
            if d[0] & 0x0F != 8 {
                return Err(data_error("unknown compression method"));
            }
            if d[1] & 0x20 != 0 {
                return Err("Error 2 while decompressing data".into());
            }
            2
        }
    };
    let mut state = InflateState::new_boxed(DataFormat::Raw);
    let mut out = Vec::new();
    let mut buf = vec![0u8; 64 * 1024];
    let mut at = start;
    let mut ended = false;
    loop {
        let r = inflate(&mut state, &d[at..], &mut buf, MZFlush::None);
        at += r.bytes_consumed;
        out.extend_from_slice(&buf[..r.bytes_written]);
        if out.len() > limit {
            return Err(format!("decompressed data is longer than the {limit} bytes a string may hold here"));
        }
        match r.status {
            Ok(MZStatus::StreamEnd) => {
                ended = true;
                break;
            }
            Ok(_) if r.bytes_consumed == 0 && r.bytes_written == 0 => break,
            Ok(_) => {}
            Err(MZError::Buf) => break,
            Err(_) => return Err(data_error("invalid deflate data")),
        }
    }
    if !ended {
        return unfinished(out);
    }
    let trailer = match wrap {
        Wrap::Raw => 0,
        Wrap::Zlib => 4,
        Wrap::Gzip => 8,
    };
    let Some(t) = d.get(at..at + trailer) else { return unfinished(out) };
    match wrap {
        Wrap::Zlib if u32::from_be_bytes([t[0], t[1], t[2], t[3]]) != adler32(&out, 1) => return Err(data_error("incorrect data check")),
        Wrap::Gzip if u32::from_le_bytes([t[0], t[1], t[2], t[3]]) != crc32(&out, 0) => return Err(data_error("incorrect data check")),
        Wrap::Gzip if u32::from_le_bytes([t[4], t[5], t[6], t[7]]) != out.len() as u32 => return Err(data_error("incorrect length check")),
        _ => {}
    }
    Ok(Inflated { data: out, consumed: at + trailer, finished: true })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checksums_and_round_trip() {
        assert_eq!(crc32(b"hello", 0), 907_060_870);
        assert_eq!(crc32(b"lo", crc32(b"hel", 0)), 907_060_870);
        assert_eq!(adler32(b"hello", 1), 103_547_413);
        let text = b"hello hello hello hello".repeat(50);
        let mut z = vec![0x78, 0x9c];
        z.extend(deflate_raw(&text, 6, true));
        z.extend(adler32(&text, 1).to_be_bytes());
        z.extend(b"junk");
        let r = inflate_wrapped(&z, 15, 1 << 20).unwrap();
        assert!(r.finished);
        assert_eq!(r.data, text);
        assert_eq!(&z[r.consumed..], b"junk");
        let half = inflate_wrapped(&z[..z.len() / 2], 15, 1 << 20).unwrap();
        assert!(!half.finished);
        // Sync-flushed pieces and a last block make one stream.
        let mut raw = deflate_raw(b"abc", 6, false);
        raw.extend(deflate_raw(b"def", 6, true));
        assert_eq!(inflate_wrapped(&raw, -15, 100).unwrap().data, b"abcdef");
    }
}
