package com.wasimaster.wmkeyboard.core.notes

import com.wasimaster.wmkeyboard.core.tools.AiMarkdownBlocks

/**
 * The task boxes in a note's body: how many are ticked, and ticking one by
 * rewriting its line.
 *
 * Both go through [AiMarkdownBlocks], so a `- [ ]` inside a fenced code block
 * is code and not a task, exactly as the reading view draws it. Lines are
 * `split('\n')`; the store normalises carriage returns away so this agrees
 * with the parser's `lines()`.
 */
object NoteChecklist {

    /** Ticked boxes and boxes in all. */
    data class Progress(val done: Int, val total: Int) {
        val isEmpty: Boolean get() = total == 0
    }

    fun progress(body: String): Progress {
        if ('[' !in body) return Progress(0, 0)
        // The whole parse only for a body with a line that reads as a task
        // (#1466). Every card on the Notes shelf asks this as it is drawn, and
        // a note holding a pasted file's bytes has a `[` somewhere in it: the
        // full Markdown pass over hundreds of kilobytes of that, card after
        // card, held the panel for seconds on the way back to the list.
        if (body.lineSequence().none { AiMarkdownBlocks.task(it) != null }) return Progress(0, 0)
        var done = 0
        var total = 0
        for (block in AiMarkdownBlocks.parse(body)) {
            if (block !is AiMarkdownBlocks.Block.Task) continue
            total++
            if (block.checked) done++
        }
        return Progress(done, total)
    }

    /**
     * [body] with the task on line [lineIndex] set to [checked], or null when
     * that line is not a task any more — the reading view parsed an older body
     * than the one the store holds now, and a blind rewrite would damage a
     * line of prose.
     *
     * Idempotent, so a double tap cannot flip the box twice. The indent and
     * the marker are kept as they were; the box is written as a lowercase `x`.
     */
    fun setTask(body: String, lineIndex: Int, checked: Boolean): String? {
        if (lineIndex < 0) return null
        val lines = body.split('\n')
        val line = lines.getOrNull(lineIndex) ?: return null
        val task = AiMarkdownBlocks.task(line) ?: return null
        // A box inside a fence parses as a task line on its own but is code in
        // context; only a line the block parser calls a task may be rewritten.
        if (AiMarkdownBlocks.parse(body).none { it is AiMarkdownBlocks.Block.Task && it.line == lineIndex }) return null
        if (task.checked == checked) return body
        val box = if (checked) "[x]" else "[ ]"
        val rewritten = buildString {
            append(task.indent).append(task.marker).append(' ').append(box)
            if (task.text.isNotEmpty()) append(' ').append(task.text)
        }
        return lines.mapIndexed { i, l -> if (i == lineIndex) rewritten else l }.joinToString("\n")
    }

    /** The task on line [lineIndex] flipped; null for the reason [setTask] gives. */
    fun toggleTask(body: String, lineIndex: Int): String? {
        val line = body.split('\n').getOrNull(lineIndex) ?: return null
        val task = AiMarkdownBlocks.task(line) ?: return null
        return setTask(body, lineIndex, !task.checked)
    }
}
