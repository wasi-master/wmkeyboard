package com.wasimaster.wmkeyboard.core.translate

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of `libwmcld3.so` (native/cld3-jni): CLD3, Google's neural
 * language identifier, model compiled in. Its own small gate, `langid`.
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
 * Language identification for the builds without ML Kit's (F-Droid, the
 * offline build): what the translate tool's Bergamot and NLLB engines read
 * the source language from when the user leaves it on Detect, and what the
 * sticker prompt asks before translating a prompt to English.
 *
 * CLD3 splits the text by script and names a language per span, so a mixed
 * text comes back as several candidates; each candidate's confidence is its
 * probability weighted by the share of the text it covers. Codes are BCP-47
 * like ML Kit's, `-Latn` for a language typed in Latin letters that is
 * usually not (`hi-Latn`).
 */
object Cld3 {

    /** The longest text worth reading: CLD3 itself looks at 1000 bytes per span. */
    private const val MAX_CHARS = 2000

    /** Whether the library is on the device, without loading it. */
    val moduleReady: Boolean get() = FeatureModules.langid.installed

    fun requestModule() = FeatureModules.langid.requestInstall()

    /**
     * The languages [text] is in, likeliest first, or none when the library
     * is not here (asked for then, for next time) or nothing was recognised.
     * A few milliseconds; any thread.
     */
    fun identify(text: String, languages: Int = 3): List<OfflineTranslatePlan.Candidate> {
        if (text.isBlank()) return emptyList()
        if (!moduleReady) {
            requestModule()
            return emptyList()
        }
        if (!Cld3Native.available) return emptyList()
        val raw = runCatching {
            Cld3Native.nativeIdentify(text.take(MAX_CHARS).toByteArray(Charsets.UTF_8), languages)
        }.getOrNull() ?: return emptyList()
        return parse(String(raw, Charsets.UTF_8))
    }

    internal fun parse(lines: String): List<OfflineTranslatePlan.Candidate> = lines.lineSequence()
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
