//! JNI bridge over vidyut-lipi, the transliterator between the Brahmic
//! scripts and the romanisations Sanskrit and Indic text is written in.
//!
//! Kotlin side: `com.wasimaster.wmkeyboard.core.transliteration.LipiNative`.
//! One entry point: text, the scheme it is in and the scheme to write it in,
//! both by vidyut's own variant name (`Devanagari`, `Iast`, `HarvardKyoto`),
//! which is what `LipiScheme.native` holds on the Kotlin side.

use std::str::FromStr;
use std::sync::{Mutex, OnceLock};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use vidyut_lipi::{Lipika, Scheme};

/// One `Lipika` for the process: it caches the mappings it has built, which
/// is most of the cost of a call, and needs `&mut` to do it.
fn lipika() -> &'static Mutex<Lipika> {
    static LIPIKA: OnceLock<Mutex<Lipika>> = OnceLock::new();
    LIPIKA.get_or_init(|| Mutex::new(Lipika::new()))
}

/// [text] from [from] to [to], or `None` when either name is not a scheme.
pub fn transliterate(text: &str, from: &str, to: &str) -> Option<String> {
    let from = Scheme::from_str(from).ok()?;
    let to = Scheme::from_str(to).ok()?;
    if from == to {
        return Some(text.to_owned());
    }
    // A poisoned lock only means an earlier call panicked, and with
    // panic = "abort" there is no earlier call left to have done that.
    let mut lipika = lipika().lock().unwrap_or_else(|e| e.into_inner());
    Some(lipika.transliterate(text, from, to))
}

/// `String? nativeTransliterate(String text, String from, String to)`: null
/// for an unknown scheme name or a string the VM could not hand over.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_transliteration_LipiNative_nativeTransliterate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
    from: JString<'local>,
    to: JString<'local>,
) -> jstring {
    let read = |env: &mut JNIEnv<'local>, s: &JString<'local>| -> Option<String> {
        env.get_string(s).ok().map(String::from)
    };
    let (Some(text), Some(from), Some(to)) = (read(&mut env, &text), read(&mut env, &from), read(&mut env, &to)) else {
        return std::ptr::null_mut();
    };
    match transliterate(&text, &from, &to) {
        Some(out) => env.new_string(out).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::transliterate;

    #[test]
    fn devanagari_to_romanisations() {
        assert_eq!(transliterate("संस्कृतम्", "Devanagari", "Iast").as_deref(), Some("saṃskṛtam"));
        assert_eq!(transliterate("संस्कृतम्", "Devanagari", "HarvardKyoto").as_deref(), Some("saMskRtam"));
        assert_eq!(transliterate("संस्कृतम्", "Devanagari", "Slp1").as_deref(), Some("saMskftam"));
    }

    #[test]
    fn between_scripts() {
        assert_eq!(transliterate("कन्नड", "Devanagari", "Kannada").as_deref(), Some("ಕನ್ನಡ"));
        assert_eq!(transliterate("saMskRtam", "HarvardKyoto", "Devanagari").as_deref(), Some("संस्कृतम्"));
        assert_eq!(transliterate("kR^iShNa", "Itrans", "Devanagari").as_deref(), Some("कृष्ण"));
    }

    #[test]
    fn unknown_scheme_is_none() {
        assert_eq!(transliterate("a", "Klingon", "Iast"), None);
        assert_eq!(transliterate("a", "Iast", "iast"), None);
    }

    /// Every name LipiScheme in :core:language passes, so a rename in a
    /// vidyut bump fails here and not on a phone. Keep in step with it.
    #[test]
    fn kotlin_names_are_schemes() {
        for name in KOTLIN_NAMES {
            assert!(transliterate("a", "Iast", name).is_some(), "{name}");
        }
    }

    const KOTLIN_NAMES: &[&str] = &[
        "Iast", "Iso15919", "Itrans", "HarvardKyoto", "Slp1", "Velthuis", "Wx", "BarahaSouth",
        "Devanagari", "Bengali", "Assamese", "Gurmukhi", "Gujarati", "Odia", "Tamil", "Telugu",
        "Kannada", "Malayalam", "Sinhala", "Grantha", "OlChiki", "MeeteiMayek", "Limbu",
        "Burmese", "Thai", "Khmer", "Tibetan", "Javanese", "Balinese", "Brahmi", "Sharada",
        "Siddham", "Modi", "Newa", "Tirhuta", "Takri", "Dogra", "Kaithi", "Nandinagari",
        "Bhaiksuki", "Saurashtra", "Soyombo", "ZanabazarSquare", "MasaramGondi", "GunjalaGondi",
        "Khudawadi", "Kharoshthi", "Cham", "TaiTham",
    ];
}
