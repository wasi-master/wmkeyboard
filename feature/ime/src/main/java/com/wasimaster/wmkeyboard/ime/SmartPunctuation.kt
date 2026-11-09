package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.prediction.PunctuationModel
import com.wasimaster.wmkeyboard.core.script.ScriptDef

/**
 * The smart punctuation key's marks in a script's own writing.
 *
 * The model answers in classes (`PunctuationModel.MARKS`: a full stop, a
 * question, an exclamation, a comma), never in glyphs: a Bengali sentence
 * ends in a danda and an Arabic question in a reversed mark, and which is
 * which is the script's business, the same lever the period key's own swap
 * reads (`ScriptDef.fullStop`, `ScriptDef.questionMark`). The exclamation
 * mark is "!" everywhere the keyboard writes one; the comma follows the
 * question mark into the Arabic script, where it is reversed too.
 */
object SmartPunctuation {

    /** The Arabic-script comma, where the script asks with the reversed question mark. */
    private const val ARABIC_COMMA = "،"
    private const val ARABIC_QUESTION = "؟"

    /** The glyph to show on the key for [mark], one of `PunctuationModel.MARKS`'s indices. */
    fun glyph(mark: Int, script: ScriptDef): String = when (mark) {
        PunctuationModel.PERIOD -> script.fullStop
        PunctuationModel.QUESTION -> script.questionMark
        PunctuationModel.EXCLAIM -> "!"
        PunctuationModel.COMMA -> if (script.questionMark == ARABIC_QUESTION) ARABIC_COMMA else ","
        else -> script.fullStop
    }
}
