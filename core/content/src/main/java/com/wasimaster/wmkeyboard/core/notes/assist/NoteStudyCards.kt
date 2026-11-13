package com.wasimaster.wmkeyboard.core.notes.assist

import com.wasimaster.wmkeyboard.core.vocab.FlashcardImport
import com.wasimaster.wmkeyboard.core.vocab.VocabPack
import com.wasimaster.wmkeyboard.core.vocab.VocabPacks

/**
 * "Make a study deck from this note" (#1293): the note's terms and what they
 * mean, as cards for the Vocabulary tool's reviews.
 *
 * Study notes mostly already say it in a shape rules can read: "term: what
 * it means", "term - meaning", "**term** meaning", a two-column table, a
 * "Q: … / A: …" pair. [fromText] reads those, so a deck needs no model. With
 * a model on the phone, the assistant can also be asked to write cards out
 * of prose; it answers one "front | back" per line, which [fromModel] reads
 * with the same rules.
 */
object NoteStudyCards {

    /** The most cards one deck takes from a note. */
    const val MAX_CARDS = 300

    /** A front longer than this is a sentence, not a term to learn. */
    private const val MAX_TERM_CHARS = 64
    private const val MAX_TERM_WORDS = 8
    private const val MAX_BACK_CHARS = 400

    private val LIST_MARKER = Regex("""^\s*(?:[-*+•]\s+(?:\[[ xX]\]\s*)?|\d{1,3}[.)]\s+|>\s*)""")
    private val HEADING = Regex("""^\s*#{1,6}\s""")
    private val BOLD_TERM = Regex("""^\*\*(.+?)\*\*\s*[:\-–—=]?\s*(.+)$""")
    private val SEPARATORS = listOf(" :: ", " | ", " — ", " – ", " - ", " = ", ": ", " : ", "\t")
    private val QUESTION = Regex("""^(?:q|question|প্রশ্ন)\s*[:.)]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val ANSWER = Regex("""^(?:a|answer|উত্তর)\s*[:.)]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val TABLE_RULE = Regex("""^\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)*\|?$""")
    private val URL_START = Regex("""^(?:https?://|www\.)""", RegexOption.IGNORE_CASE)

    /** The cards [body] spells out, in order, a repeated front kept once. */
    fun fromText(body: String): List<FlashcardImport.Card> {
        val out = ArrayList<FlashcardImport.Card>()
        val lines = body.replace("\r\n", "\n").split('\n')
        var pendingQuestion: String? = null
        var tableHeader = true
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) {
                pendingQuestion = null
                tableHeader = true
                continue
            }
            if (HEADING.containsMatchIn(line)) continue
            val asked = QUESTION.matchEntire(line)
            if (asked != null) {
                pendingQuestion = asked.groupValues[1].trim()
                continue
            }
            val answered = ANSWER.matchEntire(line)
            val question = pendingQuestion
            pendingQuestion = null
            if (question != null || answered != null) {
                // An answer with no question before it is half a card.
                if (question != null && answered != null) card(question, answered.groupValues[1], question = true)?.let(out::add)
                continue
            }
            if (line.startsWith("|")) {
                if (TABLE_RULE.matches(line)) continue
                val cells = line.trim('|').split('|').map { it.trim() }
                // The first row of a table is its column names.
                if (tableHeader) {
                    tableHeader = false
                    continue
                }
                if (cells.size >= 2) card(cells[0], cells.drop(1).filter { it.isNotEmpty() }.joinToString("; "))?.let(out::add)
                continue
            }
            tableHeader = true
            val content = line.replaceFirst(LIST_MARKER, "").trim()
            pair(content)?.let(out::add)
            if (out.size >= MAX_CARDS) break
        }
        return out.distinctBy { it.term.lowercase() }.take(MAX_CARDS)
    }

    /**
     * Cards out of a model's answer: one "front | back" per line, the shapes
     * [fromText] reads as well, since a small model drifts between them. Its
     * thinking, if it showed any, is the caller's to strip first.
     */
    fun fromModel(answer: String): List<FlashcardImport.Card> =
        fromText(answer.replace("```", "\n"))

    /** A deck named [name] holding [cards], as a pack the Vocabulary tool keeps as the user's own list. */
    fun toPack(cards: List<FlashcardImport.Card>, name: String, nowMillis: Long = System.currentTimeMillis()): VocabPack {
        val built = FlashcardImport.toPack(cards, name, LANGUAGE)
        return built.copy(meta = VocabPacks.newUserPack(built.meta.name, LANGUAGE, nowMillis))
    }

    /** Vocabulary lists live under a language; the tool's own lists are English. */
    const val LANGUAGE = "en"

    private fun pair(line: String): FlashcardImport.Card? {
        BOLD_TERM.matchEntire(line)?.let { m -> return card(m.groupValues[1], m.groupValues[2]) }
        if (URL_START.containsMatchIn(line)) return null
        // The first separator in the line splits it: "term: meaning - more" keeps its dash in the meaning.
        val (at, separator) = SEPARATORS
            .mapNotNull { sep -> line.indexOf(sep).takeIf { it > 0 }?.let { it to sep } }
            .minByOrNull { it.first } ?: return null
        return card(line.substring(0, at), line.substring(at + separator.length))
    }

    /**
     * A card, or null when [front] is no term: too long, a sentence, a time
     * of day ("10:30"), or [back] is empty. A Q/A pair keeps its question
     * however long it is: that is what the card asks.
     */
    private fun card(front: String, back: String, question: Boolean = false): FlashcardImport.Card? {
        val term = strip(front)
        val meaning = strip(back).take(MAX_BACK_CHARS)
        if (term.isEmpty() || meaning.isEmpty()) return null
        if (term.all { it.isDigit() || it == ':' || it == '.' }) return null
        if (!question) {
            if (term.length > MAX_TERM_CHARS) return null
            if (term.split(' ').count { it.isNotEmpty() } > MAX_TERM_WORDS) return null
            if (term.endsWith('.') || term.endsWith('?')) return null
        }
        return FlashcardImport.Card(term = term, definition = meaning)
    }

    /** Inline Markdown emphasis taken off. */
    private fun strip(text: String): String =
        text.trim().removePrefix("**").removeSuffix("**").removePrefix("__").removeSuffix("__")
            .trim('*', '_', '`', ' ').trim()
}
