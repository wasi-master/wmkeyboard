package com.wasimaster.wmkeyboard.core.voice

import com.wasimaster.wmkeyboard.core.script.ScriptRegistry

/**
 * The capital the recognizer puts on every utterance, judged against where
 * the words are going.
 *
 * A recognizer formats each utterance on its own: it cannot see the field, so
 * the first word of every phrase arrives capitalized as if it opened a
 * sentence. Under an open microphone a pause is not a full stop, and the
 * phrase after it lands mid-sentence, after a comma, or after the word the
 * user just typed by hand (issue #182). The keyboard knows what the caret
 * follows, so it takes that capital back when the place does not call for
 * one.
 *
 * "Where the caret sits" is read here from the text in front of it
 * ([startsSentence]) rather than from the shift key's own rule. The two are
 * not the same question. A field that never asks for sentence capitals — a
 * plain `inputType="text"`, a terminal's null field — leaves the shift key
 * alone, and so does the Automatic capitals setting when it is off; none of
 * that makes the empty field the caret is sitting in any less the start of a
 * sentence. Deciding it by the shift rule lowered the first word of a
 * dictation into an empty field in most apps on the device.
 *
 * Only the recognizer's *automatic* capital is in question, which is the one
 * on the first word alone. A word that is a capital in its own right is left
 * as it is: the pronoun I and its contractions, and anything with a second
 * capital inside it (NASA, McKinsey, iPhone is never the case since its first
 * letter is small). A name at the start of a phrase cannot be told from a
 * sentence capital and goes with the rule; the shift key is the fix, as it is
 * for a typed name.
 */
object VoiceCasing {

    /**
     * How much text before the caret [startsSentence] wants to see: a
     * terminator, whatever closed the quote or bracket in front of it, and
     * the space the last utterance left behind — `said."' ` is seven.
     */
    const val CONTEXT_CHARS = 8

    /**
     * [text] with its opening capital lowered unless [sentenceStart] says the
     * caret sits where a sentence begins, and with every *later* sentence in
     * it opened with a capital.
     *
     * With [raise], a phrase that does open a sentence gets the capital the
     * recognizer left off. Whisper and the system recognizer capitalize every
     * utterance, but Vosk, Sherpa and a third-party recognizer that formats
     * nothing hand over lower case, and the phrase after a dictated full stop
     * then went in as "Done. and then".
     */
    fun apply(text: String, sentenceStart: Boolean, raise: Boolean = true, everyWord: Boolean = false): String =
        if (everyWord) raisedWords(text) else capitalizeInnerSentences(openingCase(text, sentenceStart, raise))

    /**
     * [text] with every word opened with a capital, for Capitalize every word
     * (#1534): what the shift key does for each typed word in that mode, so a
     * dictated "new york city" lands as "New York City" too. Only a small first
     * letter is raised, behind any opening quote or bracket, and not in a word
     * spelled with a capital further in (iPhone); the rest of each word is the
     * recognizer's to case.
     */
    private fun raisedWords(text: String): String {
        var out: StringBuilder? = null
        var atStart = true
        for (i in text.indices) {
            val c = text[i]
            when {
                c.isWhitespace() -> atStart = true
                atStart && c in OPENERS -> Unit
                else -> {
                    if (atStart && c.isLowerCase() && !innerCapital(text, i)) {
                        val sb = out ?: StringBuilder(text).also { out = it }
                        sb.setCharAt(i, c.titlecaseChar())
                    }
                    atStart = false
                }
            }
        }
        return out?.toString() ?: text
    }

    /** Whether the word starting at [start] has a capital after its first letter. */
    private fun innerCapital(text: String, start: Int): Boolean {
        var i = start + 1
        while (i < text.length && !text[i].isWhitespace()) {
            if (text[i].isUpperCase()) return true
            i++
        }
        return false
    }

    /** [text] with only its opening capital judged against [sentenceStart]. */
    private fun openingCase(text: String, sentenceStart: Boolean, raise: Boolean): String {
        if (text.isEmpty()) return text
        if (sentenceStart) return if (raise) raisedOpening(text) else text
        val first = text[0]
        if (!first.isUpperCase()) return text
        val end = text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
        val word = text.substring(0, end)
        if (isPronounI(word)) return text
        // A second capital means the word is spelled that way, not opened
        // that way.
        if (word.drop(1).any { it.isUpperCase() }) return text
        return first.lowercaseChar() + text.substring(1)
    }

    /**
     * [text] with its first letter raised, unless the first word is spelled
     * with a capital further in (iPhone, eBay) and so is cased on purpose.
     */
    private fun raisedOpening(text: String): String {
        val first = text[0]
        if (!first.isLowerCase()) return text
        val end = text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
        if (text.substring(1, end).any { it.isUpperCase() }) return text
        return first.titlecaseChar() + text.substring(1)
    }

    /**
     * [text] with the first letter of every sentence after the first raised.
     *
     * The recognizer capitalizes the utterance it was given and nothing
     * inside it, so "hello period how are you" came back as "hello. how are
     * you" — a full stop the user dictated, with the next sentence in lower
     * case. The same holds for any recognizer that punctuates a long phrase
     * by itself. Only the opening letter of each sentence is touched; the
     * rest of the words are the recognizer's to case.
     *
     * A full stop that is part of an abbreviation is not a sentence end:
     * "e.g." and "a.m." would otherwise raise the word after them. The test
     * is the length of the letter run in front of the stop — one letter is an
     * initial, more is a word — which is what separates "e.g. this" from
     * "ready. this". The other terminators (? ! …) need no such guard.
     */
    private fun capitalizeInnerSentences(text: String): String {
        var out: StringBuilder? = null
        var i = 1
        while (i < text.length) {
            val mark = text[i]
            if (mark !in TERMINATORS || (mark == '.' && isAbbreviationDot(text, i))) {
                i++
                continue
            }
            var j = i + 1
            while (j < text.length && text[j] in CLOSERS) j++
            // The gap is what makes it a sentence break rather than a mark
            // sitting inside a word ("3.5", "what?!").
            val gap = j
            while (j < text.length && text[j].isWhitespace()) j++
            if (j == gap || j == text.length) {
                i++
                continue
            }
            val letter = text[j]
            if (letter.isLowerCase()) {
                val sb = out ?: StringBuilder(text).also { out = it }
                sb.setCharAt(j, letter.uppercaseChar())
            }
            i = j
        }
        return out?.toString() ?: text
    }

    /** Whether the full stop at [at] closes an initial rather than a sentence. */
    private fun isAbbreviationDot(text: String, at: Int): Boolean {
        var i = at - 1
        var letters = 0
        while (i >= 0 && text[i].isLetter()) {
            letters++
            i--
        }
        return letters == 1
    }

    /**
     * Whether a phrase landing at the caret opens a sentence, judged from
     * [before] — the last [CONTEXT_CHARS] characters in front of it, or fewer
     * when the field holds fewer.
     *
     * Nothing in front of the caret is the plainest sentence start there is,
     * and so is a line of its own. Otherwise the last thing written has to
     * have ended a sentence: a full stop in whatever script wrote it, behind
     * whatever closed the quote or the bracket.
     */
    fun startsSentence(before: CharSequence?): Boolean {
        if (before.isNullOrEmpty()) return true
        val blanks = before.takeLastWhile { it.isWhitespace() }
        // A phrase on a line of its own starts a sentence whatever ended the
        // line above it.
        if (blanks.any { it == '\n' || it == '\r' }) return true
        var i = before.length - blanks.length - 1
        // Nothing but blanks in front of the caret: the field starts here.
        if (i < 0) return true
        while (i >= 0 && before[i] in CLOSERS) i--
        return i >= 0 && before[i] in TERMINATORS
    }

    /** "I", or a contraction of it: I'm, I'll, I've, I'd, with either apostrophe. */
    private fun isPronounI(word: String): Boolean =
        word == "I" || (word.length > 1 && word[0] == 'I' && (word[1] == '\'' || word[1] == '’'))

    /**
     * What ends a sentence: every script's own full stop — `।` for Bengali,
     * `。` for Japanese, `։` for Armenian — taken from where they are already
     * declared for the key beside the spacebar, plus the marks no script
     * calls its own.
     */
    private val TERMINATORS: Set<Char> = buildSet {
        ScriptRegistry.all.forEach { def -> def.fullStop.lastOrNull()?.let { add(it) } }
        addAll(listOf('!', '?', '…', '！', '？'))
    }

    /** Opens a quote or a bracket, and so can stand in front of a word's first letter. */
    private val OPENERS = setOf('"', '\'', '“', '‘', '«', '‹', '(', '[', '{', '¿', '¡')

    /** Closes a quote or a bracket, and so can stand between the terminator and the caret. */
    private val CLOSERS = setOf('"', '\'', '”', '’', '»', '›', ')', ']', '}')
}
