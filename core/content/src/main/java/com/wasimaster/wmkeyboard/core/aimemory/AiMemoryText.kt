package com.wasimaster.wmkeyboard.core.aimemory

import com.wasimaster.wmkeyboard.core.clipboard.ClipSensitivity

/**
 * The text side of AI memory, with no storage and no Android in it: what
 * counts as a secret, when two facts are the same fact, which memories a run
 * is handed, and how they read in a system prompt.
 *
 * Kept apart from [AiMemoryStore] so every rule here can be tested on plain
 * strings.
 */
object AiMemoryText {

    /** Longest fact one memory may hold. A memory is a line, not a note. */
    const val MAX_CHARS = 300

    /**
     * While there are at most this many memories, and they fit in
     * [ALL_CHARS], a run is handed every one of them.
     */
    const val ALL_COUNT = 30

    /** See [ALL_COUNT]. Roughly 750 tokens, which every run pays. */
    const val ALL_CHARS = 3_000

    /** Past [ALL_COUNT], the matches a run is handed, best first. */
    const val MATCHES = 12

    /** Past [ALL_COUNT], everything a run is handed: the matches, then the newest. */
    const val PICKED = 20

    /**
     * Why [text] must not be kept, or null when it may.
     *
     * The model is told never to save a secret; this is what holds when it
     * does anyway, and when the user types one into "Add memory". It looks
     * for shapes, not meanings: a card number that passes the Luhn check, a
     * named secret with its value ("my PIN is 4821"), a key in a provider's
     * own format, a one-time code, and a token mixed enough to have been
     * generated. A sentence that only mentions a password ("uses a password
     * manager") passes.
     */
    fun secretReason(text: String): SecretKind? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (ClipSensitivity.isBareCode(trimmed)) return SecretKind.CODE
        if (NAMED_SECRET.containsMatchIn(trimmed)) return SecretKind.NAMED
        if (CODE_IN_WORDS.containsMatchIn(trimmed)) return SecretKind.CODE
        if (KEY_SHAPES.containsMatchIn(trimmed)) return SecretKind.KEY
        if (hasCardNumber(trimmed)) return SecretKind.CARD
        if (trimmed.split(WHITESPACE).any(::looksGenerated)) return SecretKind.KEY
        return null
    }

    /** What kind of secret [secretReason] found, for the words that refuse it. */
    enum class SecretKind { NAMED, CODE, CARD, KEY }

    /**
     * Whether [text] holds a run of 13 to 19 digits, spaces and dashes
     * allowed between them, that passes the Luhn check: what every payment
     * card number does. A run after a `+` is a phone number and is left alone.
     */
    fun hasCardNumber(text: String): Boolean = DIGIT_RUN.findAll(text).any { match ->
        val before = text.getOrNull(match.range.first - 1)
        val digits = match.value.filter { it.isDigit() }
        before != '+' && digits.length in CARD_DIGITS && luhn(digits)
    }

    /** The Luhn checksum over [digits], which must be digits only. */
    fun luhn(digits: String): Boolean {
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return digits.isNotEmpty() && sum % 10 == 0
    }

    /**
     * A single word long and mixed enough to be machine-made: three of the
     * four character classes and at least two digits. A link or an address
     * is not one, however mixed.
     */
    private fun looksGenerated(word: String): Boolean {
        val token = word.trim { it in EDGE_PUNCTUATION }
        if (token.length < GENERATED_MIN) return false
        if ("://" in token || token.startsWith("www.")) return false
        if (token.count { it == '@' } == 1 && token.substringAfterLast('@').contains('.')) return false
        if (token.count { it.isDigit() } < 2) return false
        val classes = listOf(
            token.any { it.isLowerCase() },
            token.any { it.isUpperCase() },
            token.any { it.isDigit() },
            token.any { !it.isLetterOrDigit() },
        ).count { it }
        return classes >= 3
    }

    /**
     * [text] as two facts are compared: lower case, punctuation gone, one
     * space between words. Letters of every script count as letters, and so
     * do the marks written on them: a Bengali or Hindi vowel sign is a mark,
     * not a letter, and dropping it would split every word.
     */
    fun normalize(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit() || isMark(it)) it else ' ' }.joinToString("")
            .split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")

    private fun isMark(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** The words of [text] that carry meaning: [normalize]d, minus the small words. */
    fun words(text: String): Set<String> =
        normalize(text).split(' ').filterTo(LinkedHashSet()) { it.isNotEmpty() && it !in STOP_WORDS }

    /** How a new fact relates to one already kept. */
    enum class Match {
        /** Nothing alike. */
        NONE,

        /** The same fact, or one that says less than the kept one: nothing to save. */
        SAME,

        /** The same fact said differently, or with more in it: the new wording replaces the old. */
        NEWER,
    }

    /**
     * Whether [fresh] repeats [kept]. Equal once normalised, or every word of
     * [fresh] already in [kept], is [Match.SAME]. Every word of [kept] in
     * [fresh], or four in five words shared, is [Match.NEWER]: "likes tea"
     * becomes "likes green tea". "Has a dog named Max" and "has a cat named
     * Max" share three words in five, and stay two facts.
     */
    fun compare(kept: String, fresh: String): Match {
        if (normalize(kept) == normalize(fresh)) return Match.SAME
        val a = words(kept)
        val b = words(fresh)
        if (a.isEmpty() || b.isEmpty()) return Match.NONE
        if (b.size >= 2 && a.containsAll(b)) return Match.SAME
        if (a.size >= 2 && b.containsAll(a)) return Match.NEWER
        val shared = a.count { it in b }
        val union = a.size + b.size - shared
        return if (shared.toDouble() / union >= SAME_FACT) Match.NEWER else Match.NONE
    }

    /**
     * [memories] ranked by the words they share with [query], best first,
     * those sharing none left out. A word counts when it is equal, or when
     * one starts the other and both are four letters or more ("travel",
     * "travelling"). The fallback when search by meaning is off.
     */
    fun rankByWords(memories: List<AiMemory>, query: String): List<AiMemory> {
        val wanted = words(query)
        if (wanted.isEmpty()) return emptyList()
        return memories.map { memory ->
            val have = words(memory.text) + words(memory.category)
            memory to wanted.count { w -> have.any { h -> sameWord(w, h) } }
        }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AiMemory, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .map { it.first }
    }

    private fun sameWord(a: String, b: String): Boolean =
        a == b || (a.length >= PREFIX_MIN && b.length >= PREFIX_MIN && (a.startsWith(b) || b.startsWith(a)))

    /** Whether [memories] are few and short enough that a run is handed all of them. */
    fun fitsWhole(memories: List<AiMemory>): Boolean =
        memories.size <= ALL_COUNT && memories.sumOf { it.text.length } <= ALL_CHARS

    /**
     * The memories one run is handed. All of them while they [fitsWhole];
     * past that, the [MATCHES] best [matches] (by meaning when the caller
     * could rank by it, by [rankByWords] otherwise), then the most recently
     * changed, up to [PICKED]. The newest are there because a fact saved a
     * minute ago is the likeliest to be what the user is still talking about.
     */
    fun pick(memories: List<AiMemory>, matches: List<AiMemory>): List<AiMemory> {
        if (fitsWhole(memories)) return memories.sortedBy { it.createdAt }
        val out = LinkedHashMap<Long, AiMemory>()
        matches.asSequence().take(MATCHES).forEach { out[it.id] = it }
        memories.sortedByDescending { it.updatedAt }.forEach { if (out.size < PICKED) out.putIfAbsent(it.id, it) }
        return out.values.toList()
    }

    /**
     * The memories as a system prompt reads them, to be appended to it.
     * Empty when there are none.
     *
     * [forChat] numbers each one, since `forget` and an update name a memory
     * by its number; the AI actions, which cannot change memory, get the
     * facts alone and are told to leave them out of the text unless the task
     * needs them, so "fix the grammar" does not sign the user's name.
     * [total] is how many there are in all, for the line that says these are
     * only the closest when they are fewer.
     */
    fun promptBlock(picked: List<AiMemory>, total: Int, forChat: Boolean): String {
        if (picked.isEmpty()) return ""
        return buildString {
            append("\n\n")
            append(if (forChat) CHAT_HEADER else ACTION_HEADER)
            if (picked.size < total) append(" These are the ${picked.size} closest to this request, of $total.")
            append('\n')
            for (memory in picked) {
                append("- ")
                if (forChat) append('[').append(memory.id).append("] ")
                append(memory.text.replace('\n', ' ').trim())
                if (memory.category.isNotBlank()) append(" (").append(memory.category.trim()).append(')')
                append('\n')
            }
        }.trimEnd()
    }

    /**
     * What a chat with the memory tools is told about using them. Appended
     * after [promptBlock], or alone when nothing is saved yet.
     */
    const val CHAT_RULES =
        "\n\nYou have a memory that lasts between conversations, and the remember and forget tools to keep it. " +
            "When the user tells you something about themselves that will still be true and useful later " +
            "(their name, where they live, their work, their preferences, people and projects they mention often), " +
            "save it with remember: one short fact per call, written about the user in the third person. " +
            "Save what the user asks you to remember. If a saved fact has changed, call remember with its number. " +
            "Do not save one-off requests, guesses, or what other people said. " +
            "Never save a secret: no passwords, PINs, one-time codes, card or account numbers, keys or tokens. " +
            "When the user asks you to forget something, use forget. " +
            "Do not announce each save; the app shows the user what was saved."

    private const val CHAT_HEADER =
        "What you remember about the user, saved from earlier conversations. " +
            "The user can see and edit this list. Each line starts with its number."

    private const val ACTION_HEADER =
        "Background about the user, saved by them. Use it only where the task calls for it, " +
            "and do not add it to the text otherwise."

    /** Words that say nothing on their own about which fact this is. */
    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "of", "in", "on", "at", "to", "for", "with", "by", "from",
        "is", "are", "was", "were", "be", "been", "has", "have", "had", "do", "does",
        "user", "users", "s", "their", "they", "them", "he", "she", "his", "her", "i", "my", "me", "mine",
        "that", "this", "it", "its", "as", "who", "which",
    )

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 30 KB across this file's patterns.
    private val WHITESPACE by lazy { Regex("""\s+""") }
    private val DIGIT_RUN by lazy { Regex("""\d(?:[ -]?\d){12,22}""") }
    private val CARD_DIGITS = 13..19
    private const val GENERATED_MIN = 12
    private const val PREFIX_MIN = 4
    private const val SAME_FACT = 0.8
    private const val EDGE_PUNCTUATION = ".,;:!?\"'()[]{}<>"

    /** A secret named and then given: "password: hunter22", "PIN is 4821". */
    private val NAMED_SECRET by lazy {
        Regex(
            """\b(?:password|passcode|passphrase|passwd|pin(?: code| number)?|cvv|cvc|otp|one[- ]time (?:code|password)|""" +
                """verification code|security code|recovery code|backup code|2fa code|seed phrase|recovery phrase|""" +
                """private key|api key|secret key|access token|auth token|bearer token)""" +
                """\s*(?:is|was|=|:)\s*\S{3,}""",
            RegexOption.IGNORE_CASE,
        )
    }

    /**
     * "the code 482913", "OTP 4821": a short number right after the word for
     * one. Not an area, zip, postal or dialling code, which are addresses.
     */
    private val CODE_IN_WORDS by lazy {
        Regex(
            """(?<!\b(?:area|zip|postal|post|country|dial|dialing|dialling|promo|discount|airport)\s)""" +
                """\b(?:code|otp|pin)\b\D{0,12}\b\d{4,8}\b""",
            RegexOption.IGNORE_CASE,
        )
    }

    /** Keys in their providers' own formats, and the start of a PEM block. */
    private val KEY_SHAPES by lazy {
        Regex(
            """(?:\bsk-[A-Za-z0-9_-]{16,}|\bAIza[0-9A-Za-z_-]{30,}|\bghp_[A-Za-z0-9]{30,}|\bgithub_pat_\w{20,}|""" +
                """\bxox[abpr]-[A-Za-z0-9-]{10,}|\bAKIA[0-9A-Z]{16}\b|-----BEGIN [A-Z ]*PRIVATE KEY-----)""",
        )
    }
}
