package com.wasimaster.wmkeyboard.core.input.imported

import com.wasimaster.wmkeyboard.core.layout.InputMethodBinding
import com.wasimaster.wmkeyboard.core.layout.InputMethodKind
import com.wasimaster.wmkeyboard.core.layout.InputMethodLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutMessage
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.input.R
import java.io.File

/**
 * Importing another input method's own files as a keyboard of this app's:
 * a `.cin` table, a Rime schema with its dictionaries, an m17n `.mim`, or a
 * jquery.ime rule file.
 *
 * The screen reads the picked files ([read]), shows what came out and asks
 * for the language, and then [install] writes the method's data where the
 * keyboard loads it from and hands back a layout bound to it. That layout is
 * an ordinary custom layout from then on: it can be renamed, rearranged,
 * switched on and off, and deleting it ([forget]) deletes the data too.
 */
object InputMethodImport {

    /**
     * What the picker offers. None of the three formats has a registered MIME
     * type, and providers call them anything from `text/plain` to
     * `application/octet-stream`, so every file is offered and the contents
     * decide.
     */
    val MIME_TYPES = arrayOf("*/*")

    /** A picked file larger than this is not a table anyone made by hand or by tool. */
    const val MAX_FILE_BYTES: Int = 48 * 1024 * 1024

    /** All the picked files together. */
    const val MAX_TOTAL_BYTES: Long = 128L * 1024 * 1024

    fun read(files: List<SourceFile>): Conversion = InputMethodSources.convert(files)

    /** The language to offer first: the one the file names, else the one its text is in. */
    fun guessLanguage(method: ConvertedMethod): String =
        InputMethodLayouts.guessLanguage(method.languageTag, method.sample)

    /** One line on what was read: the format, and how many entries or rules. */
    fun summary(method: ConvertedMethod): LayoutMessage = when (method.format) {
        MethodFormat.CIN -> LayoutMessage(
            pluralsRes = R.plurals.core_input_im_summary_cin,
            quantity = method.entries,
            args = listOf(method.entries),
        )
        MethodFormat.RIME -> LayoutMessage(
            pluralsRes = if (method.kind == InputMethodKind.READING) {
                R.plurals.core_input_im_summary_rime_reading
            } else {
                R.plurals.core_input_im_summary_rime_table
            },
            quantity = method.entries,
            args = listOf(method.entries),
        )
        MethodFormat.M17N -> LayoutMessage(
            pluralsRes = R.plurals.core_input_im_summary_m17n,
            quantity = method.entries,
            args = listOf(method.entries),
        )
        MethodFormat.JQUERY -> LayoutMessage(
            pluralsRes = R.plurals.core_input_im_summary_jquery,
            quantity = method.entries,
            args = listOf(method.entries),
        )
    }

    fun notes(method: ConvertedMethod): List<LayoutMessage> = method.notes.map { note ->
        when (note) {
            is ImportNote.MissingFiles -> LayoutMessage(
                stringRes = R.string.core_input_im_note_missing_files,
                args = listOf(note.names.joinToString(", ")),
            )
            is ImportNote.UnusedFiles -> LayoutMessage(
                stringRes = R.string.core_input_im_note_unused_files,
                args = listOf(note.names.joinToString(", ")),
            )
            ImportNote.Truncated -> LayoutMessage(stringRes = R.string.core_input_im_note_truncated)
            is ImportNote.SkippedRules -> LayoutMessage(
                pluralsRes = R.plurals.core_input_im_note_skipped_rules,
                quantity = note.count,
                args = listOf(note.count),
            )
            is ImportNote.Unsupported -> LayoutMessage(
                stringRes = when (note.feature) {
                    MimFeature.EXTERNAL_MODULE -> R.string.core_input_im_note_module
                    MimFeature.OTHER_METHOD -> R.string.core_input_im_note_other_method
                    MimFeature.CANDIDATE_LIST -> R.string.core_input_im_note_candidates
                },
            )
            ImportNote.SpaceKey -> LayoutMessage(stringRes = R.string.core_input_im_note_space)
        }
    }

    fun refusal(refused: Conversion.Refused): LayoutMessage = when (refused.reason) {
        ImportRefusal.NOT_A_METHOD -> LayoutMessage(stringRes = R.string.core_input_im_refused_not_method)
        ImportRefusal.BROKEN -> LayoutMessage(stringRes = R.string.core_input_im_refused_broken, args = listOf(refused.detail))
        ImportRefusal.NO_DICTIONARY -> LayoutMessage(
            stringRes = R.string.core_input_im_refused_no_dictionary,
            args = listOf(refused.detail.ifBlank { "*.dict.yaml" }),
        )
        ImportRefusal.EMPTY -> LayoutMessage(stringRes = R.string.core_input_im_refused_empty)
    }

    /**
     * Writes [method]'s data and returns the layout that types it, as
     * [layoutId] in [langId]. Null when the data could not be written. Does
     * disk work: call it off the main thread.
     */
    fun install(filesDir: File, method: ConvertedMethod, langId: String, layoutId: String): LayoutSpec? {
        val id = InputMethodStore.newId()
        if (!InputMethodStore.write(filesDir, id, method)) return null
        return InputMethodLayouts.build(
            id = layoutId,
            name = method.name,
            langId = langId,
            binding = InputMethodBinding(id, method.kind),
            keys = method.keys,
            labels = method.keyNames,
            shifted = method.shifted,
        )
    }

    /**
     * Deletes the data behind [removed] once no layout in [remaining] still
     * names it. A duplicated layout shares its original's data, which is why
     * this asks rather than deleting outright.
     */
    fun forget(filesDir: File, removed: LayoutSpec, remaining: List<LayoutSpec>) {
        val id = removed.inputMethod?.id ?: return
        if (remaining.any { it.id != removed.id && it.inputMethod?.id == id }) return
        InputMethodStore.delete(filesDir, id)
    }
}
