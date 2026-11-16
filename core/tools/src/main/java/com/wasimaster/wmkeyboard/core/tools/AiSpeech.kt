package com.wasimaster.wmkeyboard.core.tools

/**
 * When the AI chat reads its answers out (#1402), in the phone's voice or a
 * downloaded one, like a voice assistant.
 */
enum class AiSpeakReplies(val id: String) {
    /** Never: answers are only written. */
    OFF("off"),

    /**
     * After a question asked by voice: the chat's microphone, or the
     * assistant's Ask by voice. A typed question gets a written answer only.
     */
    VOICE("voice"),

    /** Every answer, typed question or spoken. */
    ALWAYS("always"),
    ;

    companion object {
        fun of(id: String?): AiSpeakReplies = entries.firstOrNull { it.id == id } ?: VOICE
    }
}

/**
 * An answer as it is read out: the words of its Markdown without the marks
 * a voice would spell ("asterisk asterisk"), code blocks and links' addresses
 * left out, tables and lists read as lines. What the screen shows stays as it
 * was; this is only what goes to the speech engine.
 */
object AiSpokenText {

    /** The longest answer read in full; past it, the reading stops at a sentence. */
    const val MAX_CHARS = 3_000

    fun of(markdown: String): String {
        val text = AiThinking.stripped(markdown)
        val lines = ArrayList<String>()
        var inCode = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("```") || line.startsWith("~~~")) {
                inCode = !inCode
                continue
            }
            if (inCode || line.isEmpty()) continue
            // A table's rule line says nothing; its cells read in a row.
            if (TABLE_RULE.matches(line)) continue
            var spoken = line
                .replace(HEADING, "")
                .replace(BULLET, "")
                .replace(QUOTE, "")
                .replace(IMAGE, "$1")
                .replace(LINK, "$1")
                .replace(BARE_URL, "")
                .replace(INLINE_CODE, "$1")
                .replace(EMPHASIS, "$2")
                .replace(CITATION, "")
            if (spoken.startsWith("|")) spoken = spoken.trim('|').split('|').joinToString(", ") { it.trim() }
            spoken = spoken.replace(SPACES, " ").trim()
            if (spoken.isNotEmpty()) lines += spoken
        }
        // A line without its own stop would run into the next.
        val joined = lines.joinToString(" ") { if (it.last() in ENDINGS) it else "$it." }
        if (joined.length <= MAX_CHARS) return joined
        val cut = joined.substring(0, MAX_CHARS)
        val end = cut.indexOfLast { it in ENDINGS }
        return if (end > MAX_CHARS / 2) cut.substring(0, end + 1) else cut
    }

    private const val ENDINGS = ".!?。！？।:;…"
    private val HEADING = Regex("^#{1,6}\\s+")
    private val BULLET = Regex("^([-*+•]|\\d+[.)])\\s+")
    private val QUOTE = Regex("^>+\\s?")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val BARE_URL = Regex("https?://\\S+")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val EMPHASIS = Regex("(\\*\\*|__|\\*|_|~~)(\\S(?:.*?\\S)?)\\1")
    private val CITATION = Regex("\\[\\d+(?:,\\s*\\d+)*]")
    private val TABLE_RULE = Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$")
    private val SPACES = Regex("\\s+")
}
