package com.wasimaster.wmkeyboard.core.vocab

import java.util.Locale

/**
 * The choices the Vocabulary tool offers, kept here rather than beside the
 * settings model because [com.wasimaster.wmkeyboard.core.tools.SmartSuggest]
 * (this module) reads them and `:core:settings` depends on this module, not
 * the other way round.
 */

/** Which words the "hate → abhor" nudge may name. */
enum class VocabNudgeScope {
    /** Words not yet marked learnt — the default; a learnt word needs no reminder. */
    UNLEARNT,
    ALL,

    /** Practice mode: only words already studied, as prompts to use them. */
    LEARNT_ONLY,
}

/** How often the same nudge may come back. */
enum class VocabCooldown {
    EVERY_TIME,

    /** Shown and typed past once, it stays down until the field changes. */
    ONCE_PER_FIELD,

    /** Once a day per word, across every app. */
    ONCE_PER_DAY,
}

/**
 * How far apart in frequency the typed word and the offered word must be.
 * [minGap] is a Zipf-scale gap compared against the `gap` each pack trigger
 * carries, so a pack never needs rebuilding for the setting to change.
 */
enum class VocabNudgeLevel(val minGap: Double) {
    /** Only clearly plain words nudge: "hate", not "detest". */
    LOW(2.0),
    MEDIUM(1.0),

    /** Every trigger the pack lists. */
    HIGH(0.0),
}

/** What a tap on the nudge chip does; a long press does the other. */
enum class VocabChipTap { OPEN, REPLACE }

/** What a tap on a synonym or antonym chip inside a word card does. */
enum class VocabRelatedTap {
    /** Its own card when the word is in a pack, else typed into the field. */
    OPEN_CARD_ELSE_INSERT,
    INSERT,
    DICTIONARY_LOOKUP,
}

/** Which spaced-repetition rule schedules flashcards. */
enum class VocabScheduler { LEITNER, SM2 }

/** Where the speaker button gets its voice. */
enum class VocabAudioSource {
    /** Wiktionary's recording when there is one and the network allows, else speech synthesis. */
    AUTO,
    WIKTIONARY,
    TTS,
}

/**
 * The accent the card shows and speaks. [key] is the accent's key inside a
 * pack record's `ipa` and `audio` maps; [languageTag] is the voice's locale.
 */
enum class VocabAccent(val key: String, val languageTag: String) {
    US("us", "en-US"),
    UK("uk", "en-GB"),
    ;

    val locale: Locale get() = Locale.forLanguageTag(languageTag)
}

/** Where one section of a word card is drawn. */
enum class FieldVisibility(val code: String) {
    OFF("off"),

    /** In the settings app's word screen only. */
    SETTINGS("settings"),

    /** In the keyboard panel and the settings app. */
    KEYBOARD("kb"),
    ;

    companion object {
        fun fromCode(code: String): FieldVisibility? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Every optional section a word card can show, with where it shows unless
 * the user says otherwise. The definition itself and the headword are not
 * here: a card without them is not a card.
 */
enum class VocabCardField(val key: String, val defaultVisibility: FieldVisibility) {
    IPA("ipa", FieldVisibility.KEYBOARD),
    RESPELLING("respelling", FieldVisibility.KEYBOARD),
    EXAMPLES("examples", FieldVisibility.KEYBOARD),
    QUOTATIONS("quotations", FieldVisibility.SETTINGS),
    SYNONYMS("synonyms", FieldVisibility.KEYBOARD),
    ANTONYMS("antonyms", FieldVisibility.KEYBOARD),
    FAMILY("family", FieldVisibility.KEYBOARD),
    HYPERNYMS("hypernyms", FieldVisibility.SETTINGS),
    TAGS("tags", FieldVisibility.KEYBOARD),
    TOPICS("topics", FieldVisibility.SETTINGS),
    ETYMOLOGY("etymology", FieldVisibility.KEYBOARD),
    ORIGIN("origin", FieldVisibility.KEYBOARD),
    ROOT("root", FieldVisibility.SETTINGS),
    ATTESTED("attested", FieldVisibility.SETTINGS),
    MNEMONIC("mnemonic", FieldVisibility.KEYBOARD),
    TRANSLATIONS("translations", FieldVisibility.KEYBOARD),
    SOURCES("sources", FieldVisibility.KEYBOARD),
    HYPHENATION("hyphenation", FieldVisibility.SETTINGS),
    RHYMES("rhymes", FieldVisibility.SETTINGS),
    FORMS("forms", FieldVisibility.SETTINGS),
    WIKIPEDIA("wikipedia", FieldVisibility.SETTINGS),
    ;

    companion object {
        fun fromKey(key: String): VocabCardField? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The one settings string that records which card sections the user moved
 * away from their defaults: `"examples=kb,quotations=settings,rhymes=off"`.
 * Only departures from the default are written, so a pack of new sections in
 * a later build inherits its defaults without touching anyone's settings.
 */
object VocabCardFields {

    fun decode(encoded: String): Map<VocabCardField, FieldVisibility> {
        val out = HashMap<VocabCardField, FieldVisibility>()
        for (item in encoded.split(',')) {
            val eq = item.indexOf('=')
            if (eq <= 0) continue
            val field = VocabCardField.fromKey(item.substring(0, eq).trim()) ?: continue
            val visibility = FieldVisibility.fromCode(item.substring(eq + 1).trim()) ?: continue
            out[field] = visibility
        }
        return out
    }

    fun encode(overrides: Map<VocabCardField, FieldVisibility>): String =
        VocabCardField.entries
            .mapNotNull { field ->
                val visibility = overrides[field] ?: return@mapNotNull null
                if (visibility == field.defaultVisibility) null else "${field.key}=${visibility.code}"
            }
            .joinToString(",")

    fun with(encoded: String, field: VocabCardField, visibility: FieldVisibility): String =
        encode(decode(encoded) + (field to visibility))

    fun visibility(encoded: String, field: VocabCardField): FieldVisibility =
        decode(encoded)[field] ?: field.defaultVisibility

    /** Resolved once per card rather than once per section. */
    fun resolve(encoded: String): Map<VocabCardField, FieldVisibility> {
        val overrides = decode(encoded)
        return VocabCardField.entries.associateWith { overrides[it] ?: it.defaultVisibility }
    }

    fun inKeyboard(resolved: Map<VocabCardField, FieldVisibility>, field: VocabCardField): Boolean =
        resolved[field] == FieldVisibility.KEYBOARD

    fun inApp(resolved: Map<VocabCardField, FieldVisibility>, field: VocabCardField): Boolean =
        resolved[field] != FieldVisibility.OFF
}
