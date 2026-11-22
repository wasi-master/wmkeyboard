package com.wasimaster.wmkeyboard.ime

import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * The field's connection while "Curly apostrophes" is on: every commit has
 * the straight ' between two letters turned into the typographic ’, whichever
 * path committed it (a glided word, a strip pick, an apostrophe repair, a
 * word typed with the ' key). A ' with a letter on one side only, or on its
 * own, is a quote mark and stays straight.
 *
 * Only commits change. The composing region keeps the straight form the
 * keyboard reads back and matches against; both are one UTF-16 unit, so no
 * offset moves.
 */
internal class CurlyApostropheConnection(val base: InputConnection) : InputConnectionWrapper(base, true) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text.isNullOrEmpty() || '\'' !in text) return super.commitText(text, newCursorPosition)
        return super.commitText(curl(text.toString()), newCursorPosition)
    }

    companion object {
        const val CURLY = '’'

        /** [text] with each ' between two letters made ’. */
        fun curl(text: String): String {
            if ('\'' !in text) return text
            val out = StringBuilder(text)
            for (i in 1 until text.length - 1) {
                if (text[i] == '\'' && text[i - 1].isLetter() && text[i + 1].isLetter()) out.setCharAt(i, CURLY)
            }
            return out.toString()
        }
    }
}
