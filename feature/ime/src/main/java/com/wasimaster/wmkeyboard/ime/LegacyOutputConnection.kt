package com.wasimaster.wmkeyboard.ime

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.wasimaster.wmkeyboard.core.transliteration.BijoyAnsi
import com.wasimaster.wmkeyboard.core.transliteration.Zawgyi

/**
 * A pre-Unicode encoding a layout can write instead of Unicode: what the
 * field is meant to receive, while the keyboard itself goes on typing,
 * predicting and correcting in Unicode.
 */
internal sealed interface OutputEncoding {
    /** Whether [text] holds anything this encoding writes differently. */
    fun needsConversion(text: CharSequence): Boolean

    /** A character a word of this script is made of, for the letter-by-letter run. */
    fun isRunChar(c: Char): Boolean

    /**
     * Unicode [text] in this encoding. [before] is the character in front of
     * it in the field, or empty at the start; only an encoding that
     * [readsBefore] is ever given one.
     */
    fun convert(text: String, before: String): String

    /** Whether [convert] depends on the character in front of the text. */
    val readsBefore: Boolean

    /**
     * The longest run rewritten whole. Past it, the next letter goes in on its
     * own, which only matters inside a word that long.
     */
    val maxRun: Int

    /** Bengali as Bijoy-era ANSI (আ as `Av`); see [BijoyAnsi]. */
    data class Ansi(val version: BijoyAnsi.Version) : OutputEncoding {
        override fun needsConversion(text: CharSequence) = BijoyAnsi.needsConversion(text)

        /** A letter, sign or digit of the Bengali block, or a joiner. */
        override fun isRunChar(c: Char) = c in 'ঀ'..'৿' || c == '\u200C' || c == '\u200D'

        override fun convert(text: String, before: String) = BijoyAnsi.convert(text, version, before)

        // A word's first glyph depends on what is in front of it: after a
        // space, ে is `†`, after a letter it is `‡`.
        override val readsBefore = true
        override val maxRun = 48
    }

    /**
     * Burmese as Zawgyi; see [Zawgyi]. A run is longer than a Bengali word
     * because Burmese puts no spaces between words, and a syllable's signs
     * are only in the right order once they are converted together.
     */
    data object ZawgyiOutput : OutputEncoding {
        override fun needsConversion(text: CharSequence) = Zawgyi.hasMyanmar(text)

        override fun isRunChar(c: Char) = Zawgyi.isMyanmar(c) || c == '\u200C' || c == '\u200D'

        /**
         * Text that is Zawgyi already, a clip copied from a Zawgyi chat, goes
         * in as it is: converted again it would be garbage in both encodings.
         * A typed word is never long enough Zawgyi to be mistaken for it.
         */
        override fun convert(text: String, before: String) =
            if (text.length >= ALREADY_ZAWGYI_MIN && Zawgyi.isConfidentlyZawgyi(text)) text else Zawgyi.toZawgyi(text)

        private const val ALREADY_ZAWGYI_MIN = 8

        override val readsBefore = false
        override val maxRun = 160
    }
}

/**
 * The field's connection while a layout writes a legacy [encoding]: Bengali
 * as ANSI (Bijoy-era text, আ as `Av`), or Burmese as Zawgyi. Every piece of
 * that script the keyboard commits is converted on its way into the field,
 * whichever path sent it: a word off the space bar, a strip pick, a glide, a
 * dictated phrase, a paste from the clipboard panel.
 *
 * The word being typed stays Unicode while it is composing and is converted
 * when it commits. Both encodings put glyphs in visual order (ি and ေ go in
 * front of their consonant), so a word is only right once it is whole, and
 * the keyboard's composing machinery reads its own region back and has to
 * find the text it put there.
 *
 * A layout that commits letter by letter, with nothing composing, is handled
 * by keeping the Unicode of the run just written ([run]): each new letter
 * rewrites that run whole, so কি comes out `wK` and not `Kw`, and နေ comes
 * out with ေ in front. The run is only trusted while the field still ends with
 * what was written for it.
 */
internal class LegacyOutputConnection(
    val base: InputConnection,
    val encoding: OutputEncoding,
) : InputConnectionWrapper(base, true) {

    /** Length of the live composing region the keyboard set, or -1 for none. */
    private var composingLength = -1

    /** The run last committed outside a composing region, in Unicode. */
    private var run = ""

    /** What [run] was written as. */
    private var runConverted = ""

    /** The character in front of [run] when it started, for the encodings that read it. */
    private var runBefore = ""

    /**
     * Set around a commit whose text is already in the encoding it should be:
     * a selection action converting between the two writes its own result.
     */
    var passThrough = false

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (passThrough || text == null || !encoding.needsConversion(text)) {
            forgetRun()
            composingLength = -1
            return super.commitText(text, newCursorPosition)
        }
        val unicode = text.toString()
        val replacing = composingLength
        if (replacing < 0 && newCursorPosition == 1 && extendRun(unicode)) return true
        val before = if (encoding.readsBefore) textBefore(replacing) else ""
        val converted = encoding.convert(unicode, before)
        composingLength = -1
        val done = super.commitText(converted, newCursorPosition)
        if (newCursorPosition == 1 && unicode.all(encoding::isRunChar)) {
            run = unicode
            runConverted = converted
            runBefore = before
        } else {
            forgetRun()
        }
        return done
    }

    /**
     * Adds [unicode] to the run just written, rewriting the part of it that
     * changes. False when there is no run to add to, or the field no longer
     * ends with it (the caret moved, the app edited the text), and the text
     * then goes in on its own.
     */
    private fun extendRun(unicode: String): Boolean {
        if (run.isEmpty() || !unicode.all(encoding::isRunChar) || run.length + unicode.length > encoding.maxRun) {
            return false
        }
        if (super.getTextBeforeCursor(runConverted.length, 0)?.toString() != runConverted) {
            forgetRun()
            return false
        }
        val merged = run + unicode
        val converted = encoding.convert(merged, runBefore)
        val kept = runConverted.commonPrefixWith(converted).length
        super.beginBatchEdit()
        if (runConverted.length > kept) super.deleteSurroundingText(runConverted.length - kept, 0)
        super.commitText(converted.substring(kept), 1)
        super.endBatchEdit()
        run = merged
        runConverted = converted
        return true
    }

    /**
     * The character in front of the text about to be committed: in front of
     * the composing region the commit replaces, when there is one. Empty at
     * the start of the field, and when the field will not say.
     */
    private fun textBefore(replacing: Int): String {
        val want = if (replacing >= 0) replacing + 1 else 1
        val read = super.getTextBeforeCursor(want, 0) ?: return ""
        return if (read.length == want) read.subSequence(0, 1).toString() else ""
    }

    private fun forgetRun() {
        run = ""
        runConverted = ""
        runBefore = ""
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        forgetRun()
        composingLength = text?.length ?: -1
        return super.setComposingText(text, newCursorPosition)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        forgetRun()
        composingLength = (end - start).takeIf { it >= 0 } ?: -1
        return super.setComposingRegion(start, end)
    }

    /**
     * Leaves the region as the Unicode it holds. The keyboard finishes a
     * composition when the caret has already gone elsewhere, and ahead of a
     * delete counted in the characters it composed, and a conversion here
     * would land in the wrong place in the first case and break the count in
     * the second.
     */
    override fun finishComposingText(): Boolean {
        forgetRun()
        composingLength = -1
        return super.finishComposingText()
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        forgetRun()
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        forgetRun()
        return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        forgetRun()
        return super.setSelection(start, end)
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        forgetRun()
        return super.sendKeyEvent(event)
    }

    override fun performContextMenuAction(id: Int): Boolean {
        forgetRun()
        return super.performContextMenuAction(id)
    }
}
