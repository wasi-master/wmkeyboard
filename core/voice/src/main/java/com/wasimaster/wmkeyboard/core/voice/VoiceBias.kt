package com.wasimaster.wmkeyboard.core.voice

/**
 * The words dictation is told to listen for (#305): names, jargon and anything
 * else a recognizer trained on everyone's speech would spell its own way.
 *
 * Two engines take a hint, in two shapes. The system recognizer takes a list
 * (`RecognizerIntent.EXTRA_BIASING_STRINGS`, Android 13+), with no documented
 * limit. A transcription server takes one `prompt` string; Whisper models read
 * it as the text that came before the clip and keep only its **last** 224
 * tokens, while OpenAI's newer transcription models read far more. So the
 * prompt is built most important last, and when a Whisper server has to cut,
 * it cuts the least important words first.
 *
 * Offline, Whistle takes the list as keywords, a whisper.cpp model as its
 * prompt and a Sherpa transducer as hotwords
 * ([com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaHotwords]). The LiteRT
 * Whisper graphs take no hint at all: they have no prompt input.
 */
object VoiceBias {

    /** How many words the system recognizer is handed. */
    const val RECOGNIZER_LIMIT = 200

    /** How many words go into a server prompt, ahead of the user's own text. */
    const val PROMPT_WORD_LIMIT = 150

    /**
     * The longest prompt sent. A Whisper server keeps the last 224 tokens of
     * it anyway; this only stops a runaway list from making every request big.
     */
    const val PROMPT_MAX_CHARS = 4000

    /** The words in a user's list: split on commas and line breaks, trimmed, each once. */
    fun parseList(raw: String): List<String> =
        raw.split(',', '\n', '，', '、', '،')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

    /**
     * The words to send, most important first: the user's own list, then
     * [personal] (already ranked by the caller), each word once whatever its
     * case, at most [limit].
     */
    fun select(custom: List<String>, personal: List<String>, limit: Int): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(limit)
        for (word in custom.asSequence() + personal.asSequence()) {
            if (out.size >= limit) break
            val trimmed = word.trim()
            if (trimmed.isEmpty() || !seen.add(trimmed.lowercase())) continue
            out += trimmed
        }
        return out
    }

    /**
     * The server `prompt` for [words] (most important first) and the user's
     * own [prompt] text, or null when there is nothing to send.
     *
     * The words go first, reversed so the most important sits next to the
     * prompt text, and the prompt text goes last, where a Whisper server keeps
     * it. Past [PROMPT_MAX_CHARS] the front is dropped, a whole word at a time.
     */
    fun serverPrompt(words: List<String>, prompt: String, maxChars: Int = PROMPT_MAX_CHARS): String? {
        val tail = prompt.trim()
        val list = words.take(PROMPT_WORD_LIMIT).asReversed().joinToString(", ")
        val text = when {
            list.isEmpty() -> tail
            tail.isEmpty() -> list
            // A full stop between them, so the list reads as its own sentence
            // and the model does not run it into the user's first word.
            else -> "$list. $tail"
        }
        if (text.isEmpty()) return null
        val limit = minOf(maxChars, PROMPT_MAX_CHARS)
        if (text.length <= limit) return text
        // The prompt text alone fills the limit: it goes without the words.
        if (tail.length + 2 >= limit) return tail.takeLast(limit).takeIf { it.isNotBlank() }
        val cut = text.substring(text.length - limit)
        return cut.substringAfter(", ", cut).trimStart()
    }

    /**
     * Where the characters of a server prompt come from, for the error line
     * a server's own prompt limit earns (Groq's is 896 characters): the
     * user's own [prompt] text, the words from **More words to listen for**
     * ([custom]), and the learned words from **Listen for my words**. Each
     * list part counts its ", " separators; [total] is the whole prompt.
     */
    data class PromptBreakdown(val total: Int, val prompt: Int, val custom: Int, val personal: Int)

    fun breakdown(words: List<String>, custom: List<String>, prompt: String): PromptBreakdown {
        val sent = words.take(PROMPT_WORD_LIMIT)
        val own = custom.mapTo(HashSet()) { it.trim().lowercase() }
        var customChars = 0
        var personalChars = 0
        for (word in sent) {
            val chars = word.length + 2
            if (word.lowercase() in own) customChars += chars else personalChars += chars
        }
        val tail = prompt.trim().length
        val total = serverPrompt(words, prompt)?.length ?: 0
        return PromptBreakdown(total, tail, customChars, personalChars)
    }
}
