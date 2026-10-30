package com.wasimaster.wmkeyboard.core.transliteration

import com.wasimaster.wmkeyboard.core.engine.NativeEngine

/**
 * JNI surface of the text engines: libwm_engine_jni, built from
 * `native/wm-engine-jni` over the `native/wm-engine` crate, which is the one
 * implementation of the phonetic engines the Android app and WMK Desktop
 * share. The Kotlin objects in this package ([AvroPhonetic], [Khipro],
 * [BijoyAnsi], [Zawgyi] and the rest) are facades over these calls and keep the
 * signatures the rest of the app always had.
 *
 * [NativeEngine] loads the library, on a device and in a JVM test alike.
 *
 * Strings cross as they are; enums cross as ordinals in declaration order,
 * which the Rust side mirrors, so neither enum may be reordered on its own.
 * A panic inside an engine comes back as a [RuntimeException].
 */
internal object EngineNative {
    init {
        NativeEngine.requireLoaded()
    }

    // Phonetic engines.
    external fun avro(input: String): String
    external fun assamese(input: String): String
    external fun assameseToBengali(text: String): String
    external fun hindi(input: String): String
    external fun hindiVariants(input: String): Array<String>
    external fun urdu(input: String): String
    external fun urduVariants(input: String): Array<String>
    external fun arabic(input: String): String
    external fun arabicVariants(input: String): Array<String>
    external fun persian(input: String): String
    external fun persianVariants(input: String): Array<String>
    external fun indic(languageId: String, input: String): String
    external fun indicVariants(languageId: String, input: String): Array<String>
    external fun minorPhonetic(languageId: String, input: String): String
    external fun minorPhoneticVariants(languageId: String, input: String): Array<String>
    external fun indicRomanPrep(languageId: String, input: String): String
    external fun indicDevanagariPrep(languageId: String, dev: String): String
    external fun indicRerankFlatList(languageId: String): Boolean
    external fun indicToDevanagari(script: Int, text: String): String
    external fun indicFromDevanagari(script: Int, dev: String): String

    // Khipro.
    external fun khipro(roman: String, variant: Int): String
    external fun khiproWarm(variant: Int)
    external fun khiproSpellings(word: String): Array<String>
    external fun khiproRomanizerWarm()
    external fun khiproInstall(variant: Int, spec: String): String
    external fun khiproReset(variant: Int)
    external fun khiproCheck(spec: String, cases: String, skip: String): String
    external fun khiproBundledRevision(): String

    // Bijoy ANSI.
    external fun bijoy(text: String, version: Int, before: String): String
    external fun bijoyNeedsConversion(text: String): Boolean

    // Zawgyi.
    external fun zawgyiToUnicode(text: String): String
    external fun zawgyiFromUnicode(text: String): String
    external fun zawgyiConvertEitherWay(text: String): String
    external fun zawgyiProbability(text: String): Double
    external fun zawgyiConfident(text: String): Boolean

    // Bengali graphemes. Lengths are UTF-16 units; `previous` is a code point or -1.
    external fun clusterDeleteLength(text: String): Int
    external fun vowelFormAfter(previous: Int): Int
    external fun vowelKeyText(kar: Int, form: Int): String?

    // Romanizers.
    external fun bengaliRomanize(text: String): String
    external fun bengaliRomanizeWord(word: String): String
    external fun bengaliNormalize(text: String): String
    external fun devanagariRomanize(text: String): String
    external fun devanagariRomanizeWord(word: String): String
    external fun devanagariNormalize(text: String): String
    external fun urduRomanize(text: String): String
    external fun urduRomanizeWord(word: String): String
    external fun urduNormalize(text: String): String

    // Linker. Null on success, the linker's message otherwise.
    external fun dlopen(path: String): String?
}

/**
 * Opens a downloaded native library with a plain `dlopen` from this always-
 * shipped library, for the native delivery of non-Play builds: see
 * `NativePreload` in :core:common, which is handed this function at startup
 * because it sits below this module.
 */
object NativeLinker {
    /** Null on success, the dynamic linker's message otherwise. */
    fun dlopen(path: String): String? = EngineNative.dlopen(path)
}
