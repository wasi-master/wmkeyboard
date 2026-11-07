package com.wasimaster.wmkeyboard.core.grammar

/**
 * One GiellaLT grammar checker the keyboard can fetch: the `.drb` bundle of a
 * versioned `grammar-<code>/v<version>` release on the language's own
 * repository (github.com/giellalt/lang-<code>), pinned by URL and the SHA-256
 * GitHub publishes for it. The bundle holds the whole pipeline (tokeniser,
 * VISL CG-3 rules, speller, message files) and is read where it lies, mapped
 * rather than unpacked, so its size is what the phone keeps.
 */
data class DivvunGrammarEntry(
    /** The keyboard's language id. */
    val languageId: String,
    /** GiellaLT's ISO 639-3 code, the `lang-<code>` repository. */
    val giellaCode: String,
    val version: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    /** SPDX id of the language repository's licence, which the bundle is built from. */
    val licence: String,
) {
    val sourceUrl: String get() = "https://github.com/giellalt/lang-$giellaCode"
}

/**
 * The GiellaLT grammar checkers the keyboard offers, one per language: every
 * language with a versioned grammar release (checked 2026-11-07), at its
 * newest. GiellaLT's `dev-latest` releases are rebuilt on every commit and
 * drop the previous file, so they are not pinned.
 */
object DivvunGrammarCatalog {
    private const val BASE = "https://github.com/giellalt"

    private fun entry(languageId: String, code: String, version: String, build: Int, sha256: String, bytes: Long) =
        DivvunGrammarEntry(
            languageId = languageId,
            giellaCode = code,
            version = version,
            url = "$BASE/lang-$code/releases/download/grammar-$code/v$version/" +
                "grammar-${code}_$version%2Bbuild.${build}_noarch-all.drb",
            sha256 = sha256,
            bytes = bytes,
            licence = "GPL-3.0-only",
        )

    val entries: List<DivvunGrammarEntry> = listOf(
        entry("se", "sme", "1.3.6", 1856, "21a1b5140fde75a1fa0ac14d3d3f21e87e0a222e0cd54c66c34d6f41a8a2175e", 1_518_227_965),
        entry("smj", "smj", "1.0.6", 286, "512eb7ad78261255fdb809a9c0019d4c20bdc2bb03a07177a12dd40c0096b5f6", 375_249_153),
        entry("sma", "sma", "1.0.0", 232, "ccd35583d3d7c56a7977efca19d25767f2ab68dea8cc63b451a8095984ec2854", 290_308_175),
        entry("smn", "smn", "1.0.1", 45, "27d33e2304426c313b1948e9ea6d95f3c9e4b5b407b444f85fe2375d05b6402e", 186_094_328),
        entry("fo", "fao", "1.0.0", 48, "c16f17a0d08021627830fd3f32674f5adbaf08a5415a66884799268d97754052", 353_241_913),
    )

    private val byLanguage = entries.associateBy { it.languageId }

    fun forLanguage(languageId: String): DivvunGrammarEntry? = byLanguage[languageId]

    /**
     * The catalog entry for a keyboard [localeTag] ("se", "se-NO", "fo_FO"):
     * its language part, which is the catalog's keyboard id for every entry.
     */
    fun forLocale(localeTag: String): DivvunGrammarEntry? =
        forLanguage(localeTag.trim().lowercase().substringBefore('-').substringBefore('_'))
}
