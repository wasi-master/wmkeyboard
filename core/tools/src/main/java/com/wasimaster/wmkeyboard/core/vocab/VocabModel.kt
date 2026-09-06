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
)

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

/** Glosses in one language, with romanisations when the script needs them. */
@Serializable
data class VocabTranslation(
    val w: List<String> = emptyList(),
    val r: List<String> = emptyList(),
)

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
