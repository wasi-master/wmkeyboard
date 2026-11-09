package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.runtime.Immutable
import com.wasimaster.wmkeyboard.core.input.composer.CandidateAdjustment
import com.wasimaster.wmkeyboard.ime.SynonymAction
import com.wasimaster.wmkeyboard.ime.WordCardAction
import com.wasimaster.wmkeyboard.ime.WordMenuAction
import com.wasimaster.wmkeyboard.ime.WordMenuFacts

/**
 * Everything pressing and holding a word on the suggestion strip can ask of
 * the service (#99): the facts the menu needs to pick its items, the menu's
 * actions, and the word card's.
 *
 * One bundle rather than three callbacks because `ServiceKeyboardContent`
 * sits against the JVM's 64K method ceiling and cannot afford another
 * [KeyboardScreen] parameter — see [DeleteSwipeCallbacks] for the same
 * trick. It replaces the single `onSuggestionHold` lambda that was the
 * gesture's whole API when the menu had one item.
 */
@Immutable
class SuggestionHoldCallbacks(
    /**
     * Answers, on the main thread and at once, what the menu for a held word
     * may offer. Map and set lookups only; the walk that describes the word
     * in full happens later, for the card.
     */
    val facts: (word: String) -> WordMenuFacts = { WordMenuFacts() },
    /** A menu item was pressed. */
    val onMenu: (WordMenuAction) -> Unit = {},
    /** Something on the open word card was pressed. */
    val onCard: (WordCardAction) -> Unit = {},
    /** Something on the open synonyms sheet was pressed (#321). */
    val onSynonyms: (SynonymAction) -> Unit = {},
    /** A swipe on the word strip: true opens the pages of suggestions, false closes them (#385). */
    val onPagesToggle: (Boolean) -> Unit = {},
    /** A swipe up on the word strip: true opens the popup of more words over it, false closes it. */
    val onMorePopup: (Boolean) -> Unit = {},
    /** A word picked from that popup, by lifting the swiping finger on it. */
    val onMorePick: (String) -> Unit = {},
    /**
     * A held conversion candidate's menu was answered (#580): the candidate,
     * its strip position, and earlier, later or never.
     */
    val onCandidateAdjust: (candidate: String, index: Int, adjustment: CandidateAdjustment) -> Unit = { _, _, _ -> },
    /**
     * A held word was slid and let go (#790): set its rank to [steps], or,
     * when [block], put it on the never-suggest list.
     */
    val onRankSlide: (word: String, steps: Int, block: Boolean) -> Unit = { _, _, _ -> },
    /**
     * A recent-bar word's alternative was picked (#787, #842): put
     * [replacement] where [word] stands, the occurrence that starts at [start].
     */
    val onRecentPick: (start: Int, word: String, replacement: String) -> Unit = { _, _, _ -> },
    /** The Retype button on the strip or the recent words' row was pressed (#1189). */
    val onRetype: () -> Unit = {},
)
