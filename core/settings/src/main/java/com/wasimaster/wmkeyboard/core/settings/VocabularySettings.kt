package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.vocab.VocabAccent
import com.wasimaster.wmkeyboard.core.vocab.VocabAudioSource
import com.wasimaster.wmkeyboard.core.vocab.VocabChipTap
import com.wasimaster.wmkeyboard.core.vocab.VocabCooldown
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeLevel
import com.wasimaster.wmkeyboard.core.vocab.VocabNudgeScope
import com.wasimaster.wmkeyboard.core.vocab.VocabRelatedTap
import com.wasimaster.wmkeyboard.core.vocab.VocabScheduler

/**
 * The Vocabulary tool's settings, grouped for the reason [TrackpadSettings]
 * gives: [KeyboardSettings] is close to the argument ceiling and these all
 * belong to one feature. Each field still persists under its own DataStore
 * key via the matching setter.
 *
 * The choice enums live in `core.vocab` because the smart-chip detector in
 * `:core:tools` reads them and this module depends on that one.
 */
data class VocabularySettings(
    /** The strip chip: "hate → abhor" when a plainer word with a stronger alternative is typed. */
    val nudges: Boolean = true,
    /** Also open a card when the vocabulary word itself is typed. */
    val nudgeOnVocabWord: Boolean = true,
    val nudgeScope: VocabNudgeScope = VocabNudgeScope.UNLEARNT,
    val nudgeLevel: VocabNudgeLevel = VocabNudgeLevel.MEDIUM,
    val cooldown: VocabCooldown = VocabCooldown.ONCE_PER_FIELD,
    /** What a tap on the chip does; a long press does the other thing. */
    val chipTapAction: VocabChipTap = VocabChipTap.OPEN,
    val relatedTap: VocabRelatedTap = VocabRelatedTap.OPEN_CARD_ELSE_INSERT,
    val scheduler: VocabScheduler = VocabScheduler.LEITNER,
    /** New words a review session introduces, on top of everything due. */
    val dailyGoal: Int = 10,
    /** The word-of-the-day card on the settings home. */
    val wordOfTheDayCard: Boolean = true,
    /** The word-of-the-day chip on the strip, once per day. */
    val wordOfTheDayChip: Boolean = true,
    val audioSource: VocabAudioSource = VocabAudioSource.AUTO,
    val accent: VocabAccent = VocabAccent.US,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    /** [com.wasimaster.wmkeyboard.core.vocab.VocabCardFields] overrides; "" means every default. */
    val cardFields: String = "",
    /**
     * Comma-separated language codes whose translations the card shows, in
     * order; "" means "the enabled keyboard languages other than the pack's".
     */
    val translationLangs: String = "",
) {
    /** The codes in [translationLangs], or empty when it is unset. */
    val translationLangList: List<String>
        get() = translationLangs.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val MIN_DAILY_GOAL = 5
        const val MAX_DAILY_GOAL = 100
        const val MIN_TTS = 0.5f
        const val MAX_TTS = 2.0f
    }
}
