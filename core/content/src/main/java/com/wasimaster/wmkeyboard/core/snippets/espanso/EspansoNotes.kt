package com.wasimaster.wmkeyboard.core.snippets.espanso

import androidx.annotation.PluralsRes
import com.wasimaster.wmkeyboard.content.R
import com.wasimaster.wmkeyboard.core.content.ContentText

/**
 * The ways an Espanso file can lose something on the way in or out.
 *
 * One line per kind with a count, rather than one line per match. A Hub package
 * can hold four hundred matches and half of them may share a fault; "12 matches
 * used a shell command" is something a person can read and decide about, and
 * twelve near-identical lines is not.
 */
enum class EspansoNote(@get:PluralsRes val pluralsRes: Int) {

    /**
     * The match ran a shell command or a script. Always dropped, never run:
     * an imported file is somebody else's text, and nothing in this app builds
     * a command out of it.
     */
    SHELL(R.plurals.core_content_espanso_note_shell),

    /** The match asked for a fill-in form, which this app has no panel for. */
    FORM(R.plurals.core_content_espanso_note_form),

    /** The match was rich text (Markdown or HTML), flattened to plain. */
    RICH_TEXT(R.plurals.core_content_espanso_note_rich_text),

    /** The match inserted an image file, which cannot travel in a text file. */
    IMAGE(R.plurals.core_content_espanso_note_image),

    /** The match was limited to certain desktop apps, which means nothing here. */
    APPS(R.plurals.core_content_espanso_note_apps),

    /** A pick-one-from-a-list became a random pick. */
    CHOICE(R.plurals.core_content_espanso_note_choice),

    /** A variable of a kind this app has no equivalent for. */
    VARIABLE(R.plurals.core_content_espanso_note_variable),

    /** Part of a date format had no equivalent and was left out. */
    DATE(R.plurals.core_content_espanso_note_date),

    /** The regular expression was one this app will not run. */
    REGEX(R.plurals.core_content_espanso_note_regex),

    /** The trigger was all punctuation, which cannot be matched here. */
    SYMBOL_TRIGGER(R.plurals.core_content_espanso_note_symbol_trigger),

    /** The trigger was meant to fire in the middle of a word. */
    MID_WORD(R.plurals.core_content_espanso_note_mid_word),

    /** The match had nothing to insert. */
    EMPTY(R.plurals.core_content_espanso_note_empty),

    /** The file pulled in other files, which a single file cannot carry. */
    IMPORTS(R.plurals.core_content_espanso_note_imports),

    /**
     * The text already contained something this app reads as a variable, so it
     * will expand rather than appear as written.
     */
    TOKEN(R.plurals.core_content_espanso_note_token),

    /** The snippet asked first rather than expanding, which Espanso cannot say. */
    CONFIRM(R.plurals.core_content_espanso_note_confirm),

    /** The snippet used a variable Espanso has no equivalent for. */
    NO_ESPANSO_VARIABLE(R.plurals.core_content_espanso_note_no_variable),

    /** The snippet reshaped a capture with upper/lower/title/trim. */
    TRANSFORM(R.plurals.core_content_espanso_note_transform),

    /** The snippet was in a folder that is switched off. */
    DISABLED_FOLDER(R.plurals.core_content_espanso_note_disabled_folder),

    /**
     * The snippet had more than one expansion and only the default could go
     * out. Espanso can carry a plain list as a `choice`, but not one whose
     * entries hold dates, the clipboard or a capture reference.
     */
    ALTERNATES(R.plurals.core_content_espanso_note_alternates),

    /** The snippet linked to other snippets, which Espanso has no word for. */
    LINKS(R.plurals.core_content_espanso_note_links),
}

/**
 * Counts what a conversion lost, and turns it into lines at the end.
 *
 * Not thread-safe and not meant to be: one conversion, one collector, on one
 * thread.
 */
internal class EspansoNotes {

    private val counts = LinkedHashMap<EspansoNote, Int>()

    fun add(note: EspansoNote) {
        counts[note] = (counts[note] ?: 0) + 1
    }

    /** Adds [note] only when [condition] holds, which most call sites want. */
    fun addIf(condition: Boolean, note: EspansoNote) {
        if (condition) add(note)
    }

    fun isEmpty(): Boolean = counts.isEmpty()

    /** One line per kind, in the order the kinds were first hit. */
    fun build(): List<ContentText> = counts.map { (note, count) ->
        ContentText(pluralsRes = note.pluralsRes, quantity = count, args = listOf(count))
    }
}
