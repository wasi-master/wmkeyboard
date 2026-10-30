package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Phonetic typing for the languages outside the Indic engine that are only a
 * table: Arabic-script languages, Yiddish, Mongolian, Santali in Ol Chiki and
 * Manipuri in Meetei Mayek. The tables live in the Rust engine
 * (`native/wm-engine/src/minor_phonetic.rs`, which has to list the same ids);
 * this is the call into it.
 */
object MinorPhonetic {

    /** Each language with the code points its letters fall in. */
    val languages: Map<String, IntRange> = buildMap {
        val arabic = 0x0620..0x06FF
        for (id in ARABIC_IDS) put(id, arabic)
        put("yi", 0x05D0..0x05F4)
        put("mn", 0x0400..0x052F)
        put("sat", 0x1C50..0x1C7F)
        put("mni", 0xABC0..0xABFF)
    }

    fun transliterate(languageId: String, input: String): String = EngineNative.minorPhonetic(languageId, input)

    private val ARABIC_IDS = listOf(
        "bcc", "bft", "bgp", "bgq", "brh", "dcc", "doi-Arab", "gju", "hnd", "hno", "ks", "mve", "phr", "ps", "scl",
        "sd", "skr",
    )
}
