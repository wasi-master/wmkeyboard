//! Calendar arithmetic and Unicode date patterns (`time.format`), with English
//! names so the output is the same on every device and in the browser.
//!
//! The calendar is proleptic Gregorian. The pattern letters are
//! SimpleDateFormat's: G y Y M L w W D d F E u a H k K h m s S z Z X, with
//! `'quoted text'` and `''` for a quote.

use super::modules::Clock;

const MONTHS: [&str; 12] =
    ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const WEEKDAYS: [&str; 7] = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

/// Days since 1970-01-01 of a proleptic Gregorian date (month 1-12).
pub(crate) fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// The date of a day number: (year, month 1-12, day 1-31).
pub(crate) fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

fn is_leap(y: i64) -> bool {
    (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}

/// One instant broken into local fields.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Civil {
    pub year: i64,
    pub month: u32,
    pub day: u32,
    pub hour: u32,
    pub minute: u32,
    pub second: u32,
    pub millis: u32,
    /// Monday is 0.
    pub weekday: u32,
    /// 1-based.
    pub yearday: u32,
    pub offset: i32,
}

/// [ms] since the epoch, in the clock's local zone.
pub(crate) fn civil(ms: i64, clock: &dyn Clock) -> Civil {
    let utc_seconds = ms.div_euclid(1000);
    let offset = clock.utc_offset(utc_seconds);
    let local_ms = ms.saturating_add(offset as i64 * 1000);
    let days = local_ms.div_euclid(86_400_000);
    let in_day = local_ms.rem_euclid(86_400_000);
    let (year, month, day) = civil_from_days(days);
    Civil {
        year,
        month,
        day,
        hour: (in_day / 3_600_000) as u32,
        minute: (in_day / 60_000 % 60) as u32,
        second: (in_day / 1000 % 60) as u32,
        millis: (in_day % 1000) as u32,
        // 1970-01-01 was a Thursday.
        weekday: (days + 3).rem_euclid(7) as u32,
        yearday: (days - days_from_civil(year, 1, 1) + 1) as u32,
        offset,
    }
}

/// Local wall-clock fields, rolled over leniently (month 13 is next January),
/// to seconds since the epoch in the clock's zone.
pub(crate) fn local_to_unix(year: i64, month: i64, day: i64, hour: i64, minute: i64, second: i64, clock: &dyn Clock) -> i64 {
    let m0 = month - 1;
    let y = year + m0.div_euclid(12);
    let m = m0.rem_euclid(12) + 1;
    let days = days_from_civil(y, m, 1) + (day - 1);
    let local = days * 86_400 + hour * 3_600 + minute * 60 + second;
    // The offset is a property of the instant; two passes settle it either side of a change.
    let guess = local - clock.utc_offset(local) as i64;
    local - clock.utc_offset(guess) as i64
}

fn pad(out: &mut String, n: i64, width: usize) {
    let digits = n.unsigned_abs().to_string();
    if n < 0 {
        out.push('-');
    }
    for _ in digits.len()..width {
        out.push('0');
    }
    out.push_str(&digits);
}

fn offset_text(offset: i32, colon: bool) -> String {
    let sign = if offset < 0 { '-' } else { '+' };
    let a = offset.unsigned_abs() / 60;
    if colon {
        format!("{sign}{:02}:{:02}", a / 60, a % 60)
    } else {
        format!("{sign}{:02}{:02}", a / 60, a % 60)
    }
}

/// Format [c] with a Unicode date pattern. Errors read like SimpleDateFormat's.
pub(crate) fn format_pattern(pattern: &str, c: &Civil) -> Result<String, String> {
    let chars: Vec<char> = pattern.chars().collect();
    let mut out = String::new();
    let mut i = 0;
    while i < chars.len() {
        let ch = chars[i];
        if ch == '\'' {
            if i + 1 < chars.len() && chars[i + 1] == '\'' {
                out.push('\'');
                i += 2;
                continue;
            }
            let mut j = i + 1;
            loop {
                if j >= chars.len() {
                    return Err("Unterminated quote".into());
                }
                if chars[j] == '\'' {
                    if j + 1 < chars.len() && chars[j + 1] == '\'' {
                        out.push('\'');
                        j += 2;
                        continue;
                    }
                    break;
                }
                out.push(chars[j]);
                j += 1;
            }
            i = j + 1;
            continue;
        }
        if !ch.is_ascii_alphabetic() {
            out.push(ch);
            i += 1;
            continue;
        }
        let mut n = 1;
        while i + n < chars.len() && chars[i + n] == ch {
            n += 1;
        }
        i += n;
        let era_year = if c.year <= 0 { 1 - c.year } else { c.year };
        match ch {
            'G' => out.push_str(if c.year <= 0 { "BC" } else { "AD" }),
            'y' | 'Y' => {
                if n == 2 {
                    pad(&mut out, era_year % 100, 2)
                } else {
                    pad(&mut out, era_year, n)
                }
            }
            'M' | 'L' => match n {
                1 | 2 => pad(&mut out, c.month as i64, n),
                3 => out.push_str(&MONTHS[c.month as usize - 1][..3]),
                _ => out.push_str(MONTHS[c.month as usize - 1]),
            },
            'd' => pad(&mut out, c.day as i64, n),
            'D' => pad(&mut out, c.yearday as i64, n),
            'F' => pad(&mut out, ((c.day - 1) / 7 + 1) as i64, n),
            'w' => {
                // Weeks start on Sunday and week 1 holds January 1st (US rules).
                let jan1 = (c.weekday + 7 - (c.yearday - 1) % 7) % 7;
                let jan1_sun = (jan1 + 1) % 7;
                let mut w = (c.yearday - 1 + jan1_sun) / 7 + 1;
                let days_in_year = if is_leap(c.year) { 366 } else { 365 };
                // The days after the last Sunday of December belong to week 1 when that week holds next January 1st.
                let dow_sun = (c.weekday + 1) % 7;
                if c.yearday + (6 - dow_sun) > days_in_year {
                    w = 1;
                }
                pad(&mut out, w as i64, n)
            }
            'W' => {
                let first = (c.weekday + 7 - (c.day - 1) % 7) % 7;
                let first_sun = (first + 1) % 7;
                pad(&mut out, ((c.day - 1 + first_sun) / 7 + 1) as i64, n)
            }
            'E' => {
                let name = WEEKDAYS[c.weekday as usize];
                out.push_str(if n >= 4 { name } else { &name[..3] })
            }
            'u' => pad(&mut out, c.weekday as i64 + 1, n),
            'a' => out.push_str(if c.hour < 12 { "AM" } else { "PM" }),
            'H' => pad(&mut out, c.hour as i64, n),
            'k' => pad(&mut out, if c.hour == 0 { 24 } else { c.hour as i64 }, n),
            'K' => pad(&mut out, (c.hour % 12) as i64, n),
            'h' => pad(&mut out, if c.hour % 12 == 0 { 12 } else { (c.hour % 12) as i64 }, n),
            'm' => pad(&mut out, c.minute as i64, n),
            's' => pad(&mut out, c.second as i64, n),
            'S' => pad(&mut out, c.millis as i64, n),
            'z' => {
                if c.offset == 0 {
                    out.push_str("GMT")
                } else {
                    out.push_str(&format!("GMT{}", offset_text(c.offset, true)))
                }
            }
            'Z' => out.push_str(&offset_text(c.offset, false)),
            'X' => {
                if n > 3 {
                    return Err("invalid ISO 8601 format: length=".to_string() + &n.to_string());
                }
                if c.offset == 0 {
                    out.push('Z');
                } else {
                    let t = offset_text(c.offset, n == 3);
                    out.push_str(if n == 1 { &t[..3] } else { &t });
                }
            }
            other => return Err(format!("Illegal pattern character '{other}'")),
        }
    }
    Ok(out)
}
