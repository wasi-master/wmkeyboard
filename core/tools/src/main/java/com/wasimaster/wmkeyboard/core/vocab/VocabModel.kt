package com.wasimaster.wmkeyboard.core.vocab

import java.io.File
import kotlinx.serialization.Serializable

/** One of the word lists a pack's words are drawn from ("Word Smart 1"). */
@Serializable
data class VocabSource(
    val id: String,
    val name: String = "",
    /** The badge text: "WS1", "333". */
    val short: String = "",
)

/** Who the definitions and recordings come from, shown on the pack's page. */
@Serializable
data class VocabAttribution(
    val name: String,
    val license: String = "",
    val url: String = "",
)

/** A dated use of the word in print, with a short reference. */
@Serializable
data class VocabQuotation(
    val text: String,
    val ref: String = "",
) {
    /** The year the reference opens with ("1975", "c. 1350"), or null when it has none. */
    val year: String?
        get() = YEAR.find(ref)?.groupValues?.get(1)?.trim()

    /**
     * The reference as a reader wants it: without the date (shown on its
     * own), Wiktionary's catalogue links (→ISSN, →OCLC), the archive note,
     * the elisions and the trailing colon. "Judy Klemesrud, “Vegetarianism…”,
     * in The New York Times".
     */
    val citation: String
        get() {
            var text = ref.replace(DATE_PREFIX, "")
            for (noise in NOISE) text = text.replace(noise, "")
            return text
                .replace(Regex("""\s*,(\s*,)+"""), ",")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trim(',', ':', ';', ' ')
        }

    private companion object {
        // Built on first use: each compiled Regex holds native ICU sets for the
        // life of the process, about 28 KB across this file's patterns.
        val YEAR by lazy { Regex("""^\s*((?:c\.\s*)?\d{4}(?:[–-]\d{2,4})?)""") }
        val DATE_PREFIX by lazy { Regex("""^\s*(?:c\.\s*)?\d{4}(?:[–-]\d{2,4})?(?:\s+[A-Z][a-z]+(?:\s+\d{1,2})?)?,?\s*""") }
        val NOISE by lazy {
            listOf(
                Regex("""\s*→\S+"""),
                Regex("""\s*,?\s*archived from the original on [^,:;]+"""),
                Regex("""\s*\[…\]"""),
                Regex("""\s*\[\.\.\.\]"""),
                Regex("""\s*\(\s*\)"""),
            )
        }
    }
}

/** One meaning of a word under one part of speech. */
@Serializable
data class VocabSense(
    val pos: String = "",
    val definition: String = "",
    val example: String? = null,
    val quotations: List<VocabQuotation> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    /** Register and grammar notes: "transitive", "formal", "informal". */
    val tags: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
)

/** One step of the word's history: the language and the word it came from. */
@Serializable
data class VocabOrigin(
    val lang: String,
    val word: String,
)

/** Words built on this one, and its relatives. */
@Serializable
data class VocabFamily(
    val derived: List<String> = emptyList(),
    val related: List<String> = emptyList(),
)

/**
 * A plainer word that should make the keyboard offer this one. [forms] are
 * its inflections ("hated", "hates"), matched the same way; [gap] is how much
 * more frequent it is, on the Zipf scale, which the nudge-sensitivity setting
 * thresholds.
 */
@Serializable
data class VocabTrigger(
    val w: String,
    val forms: List<String> = emptyList(),
    val gap: Double = 0.0,
)

/** One gloss with the romanisation that belongs to it, when the script needs one. */
data class VocabGloss(val word: String, val roman: String?)

/** Glosses in one language, with romanisations when the script needs them. */
@Serializable
data class VocabTranslation(
    val w: List<String> = emptyList(),
    val r: List<String> = emptyList(),
) {
    /**
     * The glosses paired with their romanisations, in order. A Latin-script
     * entry that only repeats another entry's romanisation (Wiktionary lists
     * Serbo-Croatian in both alphabets) is folded into that entry, and a
     * romanisation identical to its word is dropped.
     */
    fun glosses(): List<VocabGloss> {
        val romans = w.indices.map { i -> r.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } }
        val romanKeys = romans.filterNotNull().map { it.lowercase() }.toSet()
        val hasScript = w.any { !isLatin(it) }
        val out = ArrayList<VocabGloss>(w.size)
        for ((i, raw) in w.withIndex()) {
            val word = raw.trim()
            if (word.isEmpty()) continue
            if (hasScript && isLatin(word) && word.lowercase() in romanKeys) continue
            out += VocabGloss(word, romans[i]?.takeIf { !it.equals(word, ignoreCase = true) })
        }
        return out
    }

    private companion object {
        fun isLatin(text: String): Boolean = text.all { !Character.isLetter(it) || it.code <= 0x24F || it.code in 0x1E00..0x1EFF }
    }
}

/**
 * One word of a pack. Every field but [word] is optional: a pack built from
 * Wiktionary fills most of them, a list the user typed in may carry only a
 * definition, and the card draws what it finds.
 *
 * [ipa] and [audio] are keyed by [VocabAccent.key] (`us`, `uk`). Translations
 * are keyed by language code; a hosted pack keeps them in sidecar files that
 * [VocabPacks.load] folds in, a user-made pack carries them inline.
 */
@Serializable
data class VocabWord(
    val word: String,
    val pos: List<String> = emptyList(),
    val ipa: Map<String, String> = emptyMap(),
    val respelling: String? = null,
    val audio: Map<String, String> = emptyMap(),
    val senses: List<VocabSense> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val family: VocabFamily? = null,
    val hypernyms: List<String> = emptyList(),
    val hyponyms: List<String> = emptyList(),
    val forms: List<String> = emptyList(),
    val hyphenation: List<String> = emptyList(),
    val rhymes: String? = null,
    val etymology: String? = null,
    val origin: List<VocabOrigin> = emptyList(),
    val root: String? = null,
    val attested: String? = null,
    val wikipedia: String? = null,
    val mnemonic: String? = null,
    val translations: Map<String, VocabTranslation> = emptyMap(),
    val sources: List<String> = emptyList(),
    val triggers: List<VocabTrigger> = emptyList(),
) {
    /** The first definition, for rows and chips that have room for one line. */
    val definition: String
        get() = senses.firstOrNull { it.definition.isNotBlank() }?.definition.orEmpty()

    /** The transcription for [accent], falling back to whichever accent the record has. */
    fun ipaFor(accent: VocabAccent): String? =
        ipa[accent.key] ?: ipa.values.firstOrNull()

    /** The recording for [accent], falling back to the other accent's. */
    fun audioFor(accent: VocabAccent): String? =
        audio[accent.key] ?: audio.values.firstOrNull()

    /** The relatives worth a chip row, derived words first. */
    val familyWords: List<String>
        get() = family?.let { it.derived + it.related }.orEmpty()
}

/** Everything about a pack except its words. */
@Serializable
data class VocabPackMeta(
    val id: String = "",
    val name: String = "",
    val langId: String = "en",
    val description: String = "",
    /** Made in the app rather than downloaded; its words are editable. */
    val userCreated: Boolean = false,
    /** The list id this pack was built from, for hosted packs. */
    val sourceId: String? = null,
    /** ISO date the hosted pack was built, for hosted packs. */
    val built: String? = null,
    /** Every list a word's [VocabWord.sources] may name, for the badges. */
    val sources: List<VocabSource> = emptyList(),
    val attribution: List<VocabAttribution> = emptyList(),
)

/**
 * A pack as loaded from disk. [file] is where it lives (null for one built
 * in memory), [enabled] whether its words take part in the index — a
 * disabled pack sits on disk under a `.off` name and is skipped.
 */
data class VocabPack(
    val meta: VocabPackMeta,
    val words: List<VocabWord>,
    val file: File? = null,
    val enabled: Boolean = true,
) {
    val id: String get() = meta.id
    val name: String get() = meta.name
    val langId: String get() = meta.langId
}
