package com.wasimaster.wmkeyboard.app

import android.app.assist.AssistStructure
import android.os.Build
import android.view.View

/**
 * The visible text of the app the assistant was opened over (#1143), read
 * from the [AssistStructure] Android hands the assistant when **Use text
 * from screen** is on in its settings. Password fields are left out, and
 * the text is capped, since it goes to the AI as the first message's
 * attachment.
 */
internal object AssistScreenText {

    /** About what the floating AI button sends: a screen, not a book. */
    const val MAX_CHARS = 8_000

    /** The text of [structure], one line per view, or blank when there is none. */
    fun of(structure: AssistStructure): String {
        val lines = ArrayList<String>()
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode, lines)
        }
        return join(lines)
    }

    private fun walk(node: AssistStructure.ViewNode?, out: MutableList<String>) {
        if (node == null || node.visibility != View.VISIBLE) return
        val inputType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.inputType else 0
        if (!isPassword(inputType)) {
            node.text?.toString()?.let(out::add)
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), out)
    }

    /**
     * [lines] as one text: each trimmed, blanks and a line repeating the one
     * before it dropped (a label and its view often carry the same words),
     * cut at [limit] characters on a line boundary where one is near.
     */
    fun join(lines: List<String>, limit: Int = MAX_CHARS): String {
        val out = StringBuilder()
        var last = ""
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line == last) continue
            last = line
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
            if (out.length >= limit) break
        }
        if (out.length <= limit) return out.toString()
        val cut = out.lastIndexOf('\n', limit).takeIf { it > limit / 2 } ?: limit
        return out.substring(0, cut)
    }

    /** Whether a field of [inputType] holds a password, whose text never leaves it. */
    fun isPassword(inputType: Int): Boolean {
        val variation = inputType and VARIATION_MASK
        return when (inputType and CLASS_MASK) {
            CLASS_TEXT -> variation == TEXT_PASSWORD || variation == TEXT_VISIBLE_PASSWORD || variation == TEXT_WEB_PASSWORD
            CLASS_NUMBER -> variation == NUMBER_PASSWORD
            else -> false
        }
    }

    // android.text.InputType, inlined so the test runs on the JVM.
    private const val CLASS_MASK = 0x0f
    private const val VARIATION_MASK = 0xff0
    private const val CLASS_TEXT = 0x01
    private const val CLASS_NUMBER = 0x02
    private const val TEXT_PASSWORD = 0x80
    private const val TEXT_VISIBLE_PASSWORD = 0x90
    private const val TEXT_WEB_PASSWORD = 0xe0
    private const val NUMBER_PASSWORD = 0x10
}
