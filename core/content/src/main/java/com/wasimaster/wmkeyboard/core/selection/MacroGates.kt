package com.wasimaster.wmkeyboard.core.selection

import java.util.Locale
import java.util.TimeZone

/** The settings screen's groups. Headings are the screen's words, not the engine's. */
enum class MacroCategory { EDITING, LINES, FORMAT, CONVERT, LANGUAGE, LOOKUP, OPEN_IN }

/**
 * What the selected text turned out to hold, read once per selection change
 * by [SelectionMacros.detectContent]. Pure text facts only; what the device
 * can do with them is [MacroGates]' business.
 */
data class ContentFlags(
    /** Two or more lines with something on them. */
    val multiLine: Boolean = false,
    val hasLatin: Boolean = false,
    val hasBengali: Boolean = false,
    /** Devanagari letters or signs; its digits and the danda do not count. */
    val hasDevanagari: Boolean = false,
    /** Myanmar letters or signs, the script Zawgyi re-encodes; its digits do not count. */
    val hasMyanmar: Boolean = false,
    /** Hangul syllables, the text Fix spacing reads. */
    val hasHangul: Boolean = false,
    /** Hebrew letters, the text Add niqqud reads. */
    val hasHebrew: Boolean = false,
    val hasForeignDigits: Boolean = false,
    val colour: Colour? = null,
    val dateTime: DateTimeHit? = null,
    val place: Place? = null,
    val jsonShape: JsonReformat.Shape = JsonReformat.Shape.NONE,
    val base64: Boolean = false,
    val urlEncoded: Boolean = false,
    /** A query parameter [SelectionMacros.stripTrackers] would take away. */
    val hasTrackers: Boolean = false,
) {
    companion object {
        val NONE = ContentFlags()
    }
}

/**
 * Which of the costlier detectors to run. Dates and places are only looked
 * for when a macro that needs them is switched on, because both are several
 * regexes over text that changes on every caret drag.
 */
data class DetectOptions(
    val dateTime: Boolean = false,
    val place: Boolean = false,
    val nowMillis: Long = 0L,
    val zone: TimeZone = TimeZone.getDefault(),
    val locale: Locale = Locale.getDefault(),
    /** The user's own tracking parameters; see [SelectionMacros.stripTrackers]. */
    val extraTrackers: Collection<String> = emptyList(),
)

/**
 * Everything [SelectionMacros.offer] needs to know beyond the user's switches:
 * what the device has (an app, a tool, a clipboard, a loaded dictionary) and
 * what the text holds ([content]). Facts the engine cannot read itself are
 * asked rather than assumed, because a chip that opens nothing is worse than
 * no chip.
 */
data class MacroGates(
    val whatsAppInstalled: Boolean = true,
    val qrAvailable: Boolean = true,
    /** Whether [SelectionMacro.FORMAT] would change anything. */
    val formattable: Boolean = true,
    /** The selection already spans the field, so Select all has nothing left to take. */
    val wholeField: Boolean = false,
    val clipboardHasText: Boolean = false,
    val undoAvailable: Boolean = false,
    val grammarAvailable: Boolean = false,
    /** DeepL Write is set up and switched on, and the selection fits one request. */
    val deeplWriteAvailable: Boolean = false,
    val aiAvailable: Boolean = false,
    /** The Bengali dictionary and spelling map are loaded. */
    val bengaliLoaded: Boolean = false,
    /** The Hindi phonetic backend is loaded, which it is while that layout is enabled. */
    val hindiLoaded: Boolean = false,
    /** Kiwi's library and model are on the phone. */
    val koreanSpacing: Boolean = false,
    /** Phonikud's runtime and model are on the phone. */
    val hebrewNikud: Boolean = false,
    /** The host app is in [ChatSyntax]'s table. */
    val chatSyntax: ChatMarkup? = null,
    val ttsAvailable: Boolean = true,
    /**
     * The selection is one word, punctuation around it aside, so the word
     * card has a word to open on. Answered by the service, which owns the
     * keyboard's own rule for what a word is.
     */
    val oneWord: Boolean = false,
    val content: ContentFlags = ContentFlags.NONE,
)
