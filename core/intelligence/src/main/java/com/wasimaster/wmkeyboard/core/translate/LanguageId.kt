package com.wasimaster.wmkeyboard.core.translate

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of `libwmcld3.so` (native/cld3-jni): CLD3, Google's neural
 * language identifier, model compiled in. In the `langid` gate.
 */
internal object Cld3Native {
    private val library = GatedLibrary({ FeatureModules.langid }) {
        runCatching { FeatureModules.loadLibrary("wmcld3") }.isSuccess
    }

    val available: Boolean get() = library.available

    /**
     * Up to [languages] languages in [utf8] text, one line each:
     * `code\tprobability\treliable\tproportion`, most of the text first.
     */
    @JvmStatic external fun nativeIdentify(utf8: ByteArray, languages: Int): ByteArray?
}

/**
 * JNI surface of `libwmfasttext.so` (native/fasttext-jni): fastText's lid.176
 * identifier, 176 languages, model compiled in. In the `langid` gate with
 * CLD3.
 */
internal object FastTextNative {
    private val library = GatedLibrary({ FeatureModules.langid }) {
        runCatching { FeatureModules.loadLibrary("wmfasttext") }.isSuccess
    }

    val available: Boolean get() = library.available

    /** Up to [languages] languages for [utf8] text, one line each: `code\tprobability`, likeliest first. */
    @JvmStatic external fun nativeIdentify(utf8: ByteArray, languages: Int): ByteArray?
}

/**
 * Language identification for the builds without ML Kit's (F-Droid, the
 * offline build): what the translate tool's Bergamot and NLLB engines read
 * the source language from when the user leaves it on Detect, and what the
 * sticker prompt asks before translating a prompt to English.
 *
 * fastText's lid.176 answers first: it knows 176 languages and holds up on
 * a few words. When it is unsure ([FASTTEXT_SURE]) CLD3 answers instead,
 * which splits a text by script and names a language per span, so a mixed
 * text comes back as several candidates, each weighted by the share of the
 * text it covers, and which knows a few romanised languages (`hi-Latn`).
 * Both come in the `langid` gate.
 */
object LanguageId {

    /** The longest text worth reading: CLD3 itself looks at 1000 bytes per span. */
    private const val MAX_CHARS = 2000

    /** fastText's top probability at and above which CLD3 is not asked. */
    internal const val FASTTEXT_SURE = 0.5f

    /** Whether the libraries are on the device, without loading them. */
    val moduleReady: Boolean get() = FeatureModules.langid.installed

    fun requestModule() = FeatureModules.langid.requestInstall()

    /**
     * The languages [text] is in, likeliest first, or none when the libraries
     * are not here (asked for then, for next time) or nothing was recognised.
     * A few milliseconds; any thread.
     */
    fun identify(text: String, languages: Int = 3): List<OfflineTranslatePlan.Candidate> {
        if (text.isBlank()) return emptyList()
        if (!moduleReady) {
            requestModule()
            return emptyList()
        }
        val bytes = text.take(MAX_CHARS).toByteArray(Charsets.UTF_8)
        val fast = if (FastTextNative.available) {
            runCatching { FastTextNative.nativeIdentify(bytes, languages) }.getOrNull()
                ?.let { parseFastText(String(it, Charsets.UTF_8)) }.orEmpty()
        } else {
            emptyList()
        }
        if ((fast.firstOrNull()?.confidence ?: 0f) >= FASTTEXT_SURE) return fast
        val cld3 = if (Cld3Native.available) {
            runCatching { Cld3Native.nativeIdentify(bytes, languages) }.getOrNull()
                ?.let { parseCld3(String(it, Charsets.UTF_8)) }.orEmpty()
        } else {
            emptyList()
        }
        return pick(fast, cld3)
    }

    /** fastText's unsure answer against CLD3's: whichever is surer leads. */
    internal fun pick(
        fast: List<OfflineTranslatePlan.Candidate>,
        cld3: List<OfflineTranslatePlan.Candidate>,
    ): List<OfflineTranslatePlan.Candidate> = when {
        cld3.isEmpty() -> fast
        fast.isEmpty() -> cld3
        cld3.first().confidence > fast.first().confidence -> cld3
        else -> fast
    }

    internal fun parseFastText(lines: String): List<OfflineTranslatePlan.Candidate> = lines.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 2 || parts[0].isBlank()) return@mapNotNull null
            val probability = parts[1].toFloatOrNull() ?: return@mapNotNull null
            OfflineTranslatePlan.Candidate(parts[0], probability.coerceIn(0f, 1f))
        }
        .sortedByDescending { it.confidence }
        .toList()

    internal fun parseCld3(lines: String): List<OfflineTranslatePlan.Candidate> = lines.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            val probability = parts[1].toFloatOrNull() ?: return@mapNotNull null
            val proportion = parts[3].toFloatOrNull() ?: return@mapNotNull null
            OfflineTranslatePlan.Candidate(parts[0], (probability * proportion).coerceIn(0f, 1f))
        }
        .sortedByDescending { it.confidence }
        .toList()
}
