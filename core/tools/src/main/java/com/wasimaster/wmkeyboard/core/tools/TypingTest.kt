package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import com.wasimaster.wmkeyboard.tools.R
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The typing-speed tool's engine: prompt generation and scoring, with no
 * Compose types so the maths can be reasoned about (and tested) on its own.
 * The IME owns the clock and the typed buffer; everything here is a pure
 * function of those. Only [typingConfigLabel] reaches for a [Context], because
 * the words it puts on screen have to be translated.
 */

/** What decides the test is over. */
enum class TypingTestMode {
    /** Type for a fixed number of seconds. */
    TIME,

    /** Type a fixed number of words. */
    WORDS,

    /** Type one quotation, however long it runs. */
    QUOTE,
}

/** Seconds offered for [TypingTestMode.TIME]. */
val TypingTestDurations: List<Int> = listOf(15, 30, 60, 120)

/** Word counts offered for [TypingTestMode.WORDS]. */
val TypingTestWordCounts: List<Int> = listOf(10, 25, 50, 100)

/** How each character the user produced compares to the prompt. */
enum class CharState {
    /** Not reached yet. */
    PENDING,
    CORRECT,
    /** Typed, but the wrong letter. */
    WRONG,
    /** Typed past the end of the word. */
    EXTRA,
    /** Skipped — the user hit space before finishing the word. */
    MISSING,
}

/** One prompt word paired with what the user actually typed for it. */
data class TypedWord(val expected: String, val typed: String)

/** A once-a-second reading, for the result graph. */
data class WpmSample(val second: Int, val wpm: Double, val raw: Double, val errors: Int)

/** Everything the results screen shows. */
data class TypingResult(
    val wpm: Double,
    /** Speed ignoring mistakes — what the fingers did before correction. */
    val raw: Double,
    /** Share of keystrokes that were right the first time, 0..100. */
    val accuracy: Double,
    /** How even the per-second speed was, 0..100. */
    val consistency: Double,
    val correctChars: Int,
    val incorrectChars: Int,
    val extraChars: Int,
    val missedChars: Int,
    val seconds: Double,
    val samples: List<WpmSample>,
    val mode: TypingTestMode,
    /** Human label for the settings this run used, e.g. "time 30" — also the PB key. */
    val configKey: String,
)

/**
 * What a language's prompts are dealt from: its common words, and the
 * quotations Quote mode draws on when it has any. A language can have words
 * without quotes (a pool built from a dictionary), in which case Quote mode
 * falls back to a plain word run — see [buildTypingPrompt].
 */
data class TypingWordPool(
    val words: List<String>,
    val quotes: List<String> = emptyList(),
) {
    val hasQuotes: Boolean get() = quotes.isNotEmpty()
}

/**
 * The prompt material that ships in the app, keyed by language id, and the
 * rules for building a pool out of a dictionary for every other language.
 */
object TypingWordPools {

    /** How many words a pool built out of a dictionary holds. */
    const val DICTIONARY_POOL_SIZE = 250

    /**
     * The fewest dictionary words that make a usable pool. Below this the
     * prompt repeats itself so much that it measures memory, not typing.
     */
    const val DICTIONARY_POOL_MIN = 40

    val english: TypingWordPool by lazy { TypingWordPool(CommonWords, Quotes) }

    val bengali: TypingWordPool by lazy {
        TypingWordPool(
            BengaliCommonWords.map(::precomposeBengaliNukta),
            BengaliQuotes.map(::precomposeBengaliNukta),
        )
    }

    /** The pool that ships for [languageId], or null when it has to come from a dictionary. */
    fun bundled(languageId: String): TypingWordPool? = when (languageId) {
        "en" -> english
        "bn" -> bengali
        else -> null
    }

    /**
     * Whether a dictionary word belongs in a prompt. Letters only — with the
     * combining marks that spell vowels in the Indic scripts, which
     * [Char.isLetter] does not count — and short, lowercase, no proper nouns
     * or abbreviations: the same "measure typing, not vocabulary" rule the
     * English list follows.
     */
    fun acceptsPromptWord(word: String): Boolean {
        if (word.length !in 2..9) return false
        var letters = 0
        for (c in word) {
            if (c.isUpperCase()) return false
            val type = Character.getType(c)
            when {
                c.isLetter() -> letters++
                type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() -> Unit
                else -> return false
            }
        }
        return letters >= 2
    }

    /**
     * Bengali keeps য়, ড় and ঢ় as single precomposed characters (the layouts
     * and the transliterator both produce those), but NFC normalisation and
     * some editors write them as base letter plus nukta. A prompt spelt the
     * decomposed way would score every keystroke on those letters as a miss,
     * so the shipped lists are folded to the precomposed form on load.
     */
    fun precomposeBengaliNukta(text: String): String =
        text.replace("\u09AF\u09BC", "\u09DF")
            .replace("\u09A1\u09BC", "\u09DC")
            .replace("\u09A2\u09BC", "\u09DD")
}

/**
 * The 200 most common English words. Short and high-frequency on purpose:
 * a speed test should measure typing, not vocabulary recall.
 */
private val CommonWords: List<String> = listOf(
    "the", "be", "of", "and", "a", "to", "in", "he", "have", "it",
    "that", "for", "they", "with", "as", "not", "on", "she", "at", "by",
    "this", "we", "you", "do", "but", "from", "or", "which", "one", "would",
    "all", "will", "there", "say", "who", "make", "when", "can", "more", "if",
    "no", "man", "out", "other", "so", "what", "time", "up", "go", "about",
    "than", "into", "could", "state", "only", "new", "year", "some", "take", "come",
    "these", "know", "see", "use", "get", "like", "then", "first", "any", "work",
    "now", "may", "such", "give", "over", "think", "most", "even", "find", "day",
    "also", "after", "way", "many", "must", "look", "before", "great", "back", "through",
    "long", "where", "much", "should", "well", "people", "down", "own", "just", "because",
    "good", "each", "those", "feel", "seem", "how", "high", "too", "place", "little",
    "world", "very", "still", "nation", "hand", "old", "life", "tell", "write", "become",
    "here", "show", "house", "both", "between", "need", "mean", "call", "develop", "under",
    "last", "right", "move", "thing", "general", "school", "never", "same", "another", "begin",
    "while", "number", "part", "turn", "real", "leave", "might", "want", "point", "form",
    "off", "child", "few", "small", "since", "against", "ask", "late", "home", "interest",
    "large", "person", "end", "open", "public", "follow", "during", "present", "without", "again",
    "hold", "govern", "around", "possible", "head", "consider", "word", "program", "problem", "however",
    "lead", "system", "set", "order", "eye", "plan", "run", "keep", "face", "fact",
    "group", "play", "stand", "increase", "early", "course", "change", "help", "line", "night",
)

/**
 * Public-domain lines and original sentences. Nothing under copyright — the
 * app ships these, so they have to be free to redistribute.
 */
private val Quotes: List<String> = listOf(
    "It is a truth universally acknowledged that a single man in possession of a good fortune must be in want of a wife.",
    "We hold these truths to be self evident, that all men are created equal.",
    "The only thing we have to fear is fear itself.",
    "All the world is a stage, and all the men and women merely players.",
    "It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness.",
    "Call me Ishmael. Some years ago, never mind how long precisely, I thought I would sail about a little.",
    "In the beginning the universe was created. This has made a lot of people very angry and been widely regarded as a bad move.",
    "The quick brown fox jumps over the lazy dog while the whole town sleeps through the quiet afternoon.",
    "A good keyboard disappears under the hands. You stop thinking about the letters and start thinking about the sentence.",
    "Speed comes from rhythm, not from hurry. The steady typist beats the frantic one over any distance worth measuring.",
    "Two roads diverged in a wood, and I took the one less travelled by, and that has made all the difference.",
    "Ask not what your country can do for you, ask what you can do for your country.",
    "Somewhere, something incredible is waiting to be known, and the only way to find it is to keep looking.",
    "The journey of a thousand miles begins with a single step, and every step after that is a choice to continue.",
)

/**
 * Common Bengali words, chosen the same way as the English list: short,
 * everyday, high-frequency. Hand-picked rather than counted, because the
 * bundled Bengali dictionary carries no frequencies to rank by.
 */
private val BengaliCommonWords: List<String> = listOf(
    "আমি", "তুমি", "সে", "আমরা", "তোমরা", "তারা", "আপনি", "এই", "ওই", "সেই",
    "কি", "কে", "কেন", "কোথায়", "কখন", "কত", "এবং", "আর", "বা", "কিন্তু",
    "তবে", "যদি", "তাহলে", "না", "হ্যাঁ", "হয়", "হবে", "ছিল", "আছে", "নেই",
    "করে", "করি", "করা", "করব", "করছি", "করেছি", "যাই", "যাব", "যাও", "যায়",
    "গিয়ে", "আসে", "আসি", "এসে", "এসো", "দেখি", "দেখা", "দেখে", "বলি", "বলে",
    "বলা", "বলল", "খাই", "খাও", "খেয়ে", "নিয়ে", "দিয়ে", "দেয়", "দাও", "নাও",
    "পারি", "পারে", "চাই", "চায়", "হয়ে", "থেকে", "জন্য", "সাথে", "সঙ্গে", "মধ্যে",
    "পরে", "আগে", "উপর", "নিচে", "কাছে", "দূরে", "ভেতরে", "বাইরে", "এখন", "তখন",
    "আজ", "কাল", "সকাল", "বিকাল", "রাত", "দিন", "সময়", "বছর", "মাস", "সপ্তাহ",
    "ঘর", "বাড়ি", "দেশ", "শহর", "গ্রাম", "রাস্তা", "মানুষ", "লোক", "ছেলে", "মেয়ে",
    "মা", "বাবা", "ভাই", "বোন", "বন্ধু", "নাম", "কাজ", "কথা", "মন", "হাত",
    "চোখ", "মুখ", "মাথা", "পানি", "জল", "ভাত", "খাবার", "চা", "টাকা", "বই",
    "কলম", "স্কুল", "ভালো", "খারাপ", "বড়", "ছোট", "নতুন", "পুরানো", "সুন্দর", "অনেক",
    "কম", "বেশি", "সব", "কিছু", "কেউ", "একটা", "একটি", "দুই", "তিন", "এক",
    "প্রথম", "শেষ", "ঠিক", "খুব", "একটু", "আবার", "শুধু", "সবাই", "নিজে", "তাই",
    "যে", "যা", "যার", "যেমন", "তেমন", "এখানে", "ওখানে", "সেখানে", "মতো", "ভালোবাসা",
    "জীবন", "পৃথিবী", "আকাশ", "সূর্য", "চাঁদ", "ফুল", "গাছ", "নদী", "বৃষ্টি", "রোদ",
    "আলো", "অন্ধকার", "গান", "ছবি", "খেলা", "পড়া", "লেখা", "শোনা", "ঘুম", "স্বপ্ন",
    "আশা", "কষ্ট", "সুখ", "হাসি", "কান্না", "মনে", "জানি", "জানে", "বুঝি", "চলো",
    "থাকে", "থাকি", "রাখে", "পাই", "পায়", "শুনি", "লিখি", "পড়ি", "ভাবি", "চলে",
)

/**
 * Bengali quotations: Tagore, whose work is long out of copyright, and
 * original sentences. Same rule as the English list — nothing the app cannot
 * freely redistribute.
 */
private val BengaliQuotes: List<String> = listOf(
    "আমার সোনার বাংলা, আমি তোমায় ভালোবাসি।",
    "যদি তোর ডাক শুনে কেউ না আসে তবে একলা চলো রে।",
    "আকাশ ভরা সূর্য তারা, বিশ্ব ভরা প্রাণ, তাহারি মাঝখানে আমি পেয়েছি মোর স্থান।",
    "ভালোবেসে সখী নিভৃতে যতনে আমার নামটি লিখো তোমার মনের মন্দিরে।",
    "একটা ভালো কীবোর্ড হাতের নিচে হারিয়ে যায়, তখন অক্ষর নয়, বাক্য নিয়ে ভাবা যায়।",
    "ধীরে কিন্তু নিয়মিত লিখলে গতি নিজে থেকেই আসে, তাড়াহুড়ো করে কিছু হয় না।",
    "প্রতিদিন একটু একটু করে লিখলে হাত নিজেই পথ চিনে নেয়।",
)

/** Punctuation marks sprinkled in when the punctuation option is on. */
private val Punctuation: List<String> = listOf(".", ",", ".", ",", "!", "?", ";", ":")

/**
 * Builds the word list for a run.
 *
 * [punctuation] adds sentence shape — capitals after a full stop, the odd
 * comma or quoted word — and [numbers] mixes in short numerals, both the
 * way the usual online tests do it.
 *
 * [pool] is the language's material. [digits] are the ten numerals the
 * keyboard types for that language (null for `0-9`), so a numeral in a
 * Bengali prompt is spelt the way the Bengali number row types it; and
 * [fullStop] is the script's sentence terminator (`।` for Bengali), which
 * is the mark the period key types there.
 */
fun buildTypingPrompt(
    mode: TypingTestMode,
    duration: Int,
    wordCount: Int,
    punctuation: Boolean,
    numbers: Boolean,
    random: Random = Random.Default,
    pool: TypingWordPool = TypingWordPools.english,
    digits: String? = null,
    fullStop: String = ".",
): List<String> {
    if (mode == TypingTestMode.QUOTE && pool.hasQuotes) {
        return pool.quotes[random.nextInt(pool.quotes.size)].split(" ")
    }
    if (pool.words.isEmpty()) return emptyList()
    // Time runs have no natural length, so generate a comfortable surplus:
    // nobody types 200 words in two minutes, and running dry mid-test would
    // end the run early for the wrong reason. A quote run for a language
    // with no quotes falls through to here as a word run of the set count:
    // it still ends on its last word, so it must not be dealt a time run's
    // surplus.
    val count = if (mode == TypingTestMode.TIME) max(60, duration * 4) else wordCount
    val words = MutableList(count) { pool.words[random.nextInt(pool.words.size)] }

    if (numbers) {
        // Roughly one word in eight becomes a numeral.
        for (i in words.indices) {
            if (random.nextInt(8) == 0) {
                val numeral = random.nextInt(1, if (random.nextInt(3) == 0) 10000 else 100).toString()
                words[i] = localizeDigits(numeral, digits)
            }
        }
    }

    if (punctuation) {
        var startOfSentence = true
        for (i in words.indices) {
            if (startOfSentence) {
                words[i] = words[i].replaceFirstChar { it.uppercase() }
                startOfSentence = false
            }
            // Never punctuate the last word into a sentence that never ends.
            if (i == words.lastIndex) break
            if (random.nextInt(6) == 0) {
                val mark = Punctuation[random.nextInt(Punctuation.size)].let {
                    if (it == ".") fullStop else it
                }
                words[i] = words[i] + mark
                if (mark == fullStop || mark == "!" || mark == "?") startOfSentence = true
            } else if (random.nextInt(20) == 0) {
                words[i] = "\"" + words[i] + "\""
            }
        }
        words[words.lastIndex] = words[words.lastIndex].trimEnd(',', ';', ':') + fullStop
    }
    return words
}

/** `0-9` respelt in [digits] (ten glyphs, `0` first); unchanged when null. */
private fun localizeDigits(numeral: String, digits: String?): String {
    if (digits == null || digits.length != 10) return numeral
    return buildString(numeral.length) {
        for (c in numeral) append(if (c in '0'..'9') digits[c - '0'] else c)
    }
}

/**
 * Per-character verdicts for one word: [typed] laid over [expected], with
 * anything typed past the end marked [CharState.EXTRA] and anything the
 * user skipped marked [CharState.MISSING].
 *
 * [live] is the word the caret is in — its untyped tail is still
 * [CharState.PENDING] rather than a mistake the user has not made yet.
 */
fun compareWord(expected: String, typed: String, live: Boolean): List<CharState> {
    val states = ArrayList<CharState>(max(expected.length, typed.length))
    for (i in expected.indices) {
        states += when {
            i < typed.length && typed[i] == expected[i] -> CharState.CORRECT
            i < typed.length -> CharState.WRONG
            live -> CharState.PENDING
            else -> CharState.MISSING
        }
    }
    for (i in expected.length until typed.length) states += CharState.EXTRA
    return states
}

/**
 * Scores a finished (or abandoned) run.
 *
 * Speed follows the standard definition — a "word" is five characters, and
 * only characters that ended up matching the prompt count towards [
 * TypingResult.wpm], while [TypingResult.raw] counts everything typed.
 * Accuracy is keystroke-level and comes from the caller ([totalKeystrokes]
 * / [correctKeystrokes]) because corrected mistakes leave no trace in the
 * final text but should still cost the user.
 */
fun scoreTypingTest(
    words: List<TypedWord>,
    elapsedMs: Long,
    totalKeystrokes: Int,
    correctKeystrokes: Int,
    samples: List<WpmSample>,
    mode: TypingTestMode,
    configKey: String,
): TypingResult {
    var correct = 0
    var incorrect = 0
    var extra = 0
    var missed = 0
    for ((index, word) in words.withIndex()) {
        for (state in compareWord(word.expected, word.typed, live = false)) {
            when (state) {
                CharState.CORRECT -> correct++
                CharState.WRONG -> incorrect++
                CharState.EXTRA -> extra++
                CharState.MISSING -> missed++
                CharState.PENDING -> Unit
            }
        }
        // The space that ended the word is a keystroke too, and it only
        // counts if the word it closed was right.
        if (index < words.lastIndex && word.typed == word.expected) correct++
    }

    val minutes = elapsedMs / 60_000.0
    val wpm = if (minutes > 0) (correct / 5.0) / minutes else 0.0
    val typedChars = correct + incorrect + extra
    val raw = if (minutes > 0) (typedChars / 5.0) / minutes else 0.0
    val accuracy =
        if (totalKeystrokes > 0) correctKeystrokes * 100.0 / totalKeystrokes else 100.0

    return TypingResult(
        wpm = wpm,
        raw = raw,
        accuracy = accuracy,
        consistency = consistencyOf(samples),
        correctChars = correct,
        incorrectChars = incorrect,
        extraChars = extra,
        missedChars = missed,
        seconds = elapsedMs / 1000.0,
        samples = samples,
        mode = mode,
        configKey = configKey,
    )
}

/**
 * How even the pace was, as a percentage. This is the usual
 * coefficient-of-variation measure: 100 means every second ran at the same
 * speed, and the number falls as the run gets spikier.
 */
private fun consistencyOf(samples: List<WpmSample>): Double {
    val values = samples.map { it.raw }.filter { it > 0 }
    if (values.size < 2) return 100.0
    val mean = values.average()
    if (mean <= 0) return 100.0
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    val cv = sqrt(variance) / mean
    return ((1 - cv) * 100).coerceIn(0.0, 100.0)
}

/**
 * Stable identity for a run's settings — the key personal bests hang off.
 *
 * The language rides along as an `@id` suffix for every language but
 * English, whose keys stay as they were so records set before the test
 * spoke other languages are still found. A 30-second Bengali run and a
 * 30-second English one are different achievements: the layouts, the
 * word lengths and the transliteration are all different.
 */
fun typingConfigKey(
    mode: TypingTestMode,
    duration: Int,
    wordCount: Int,
    languageId: String = "en",
): String {
    val base = when (mode) {
        TypingTestMode.TIME -> "time$duration"
        TypingTestMode.WORDS -> "words$wordCount"
        TypingTestMode.QUOTE -> "quote"
    }
    return if (languageId == "en" || languageId.isEmpty()) base else "$base@$languageId"
}

/** The `@id` language suffix of a config key — see [typingConfigKey]. */
fun typingConfigLanguage(key: String): String = key.substringAfter('@', "")

/** The config key with its language suffix removed. */
fun typingConfigBase(key: String): String = key.substringBefore('@')

/** The same settings, spelled for a human. */
fun typingConfigLabel(
    context: Context,
    mode: TypingTestMode,
    duration: Int,
    wordCount: Int,
): String = when (mode) {
    TypingTestMode.TIME -> context.getString(R.string.core_tools_typing_config_seconds, duration)
    TypingTestMode.WORDS -> context.resources.getQuantityString(
        R.plurals.core_tools_typing_config_words,
        wordCount,
        wordCount,
    )
    TypingTestMode.QUOTE -> context.getString(R.string.core_tools_typing_config_quote)
}

/**
 * Personal bests, stored as one preference string ("time30=81.4;quote=76").
 * A map per config beats a single all-time number: a 15-second sprint and a
 * two-minute run are not the same achievement.
 */
object TypingBests {
    fun decode(raw: String): Map<String, Double> =
        raw.split(';')
            .mapNotNull { entry ->
                val key = entry.substringBefore('=', "")
                val value = entry.substringAfter('=', "").toDoubleOrNull()
                if (key.isEmpty() || value == null) null else key to value
            }
            .toMap()

    fun encode(bests: Map<String, Double>): String =
        bests.entries.joinToString(";") { "${it.key}=${(it.value * 10).roundToInt() / 10.0}" }

    /** Returns the updated map, or null when [wpm] did not beat the record. */
    fun improve(raw: String, key: String, wpm: Double): Map<String, Double>? {
        val bests = decode(raw)
        val current = bests[key]
        if (current != null && wpm <= current) return null
        return bests + (key to wpm)
    }
}

/**
 * The typing test's achievement badges, stored as one comma-list preference
 * ("wpm100,perfect"). Unlocks only ever accumulate — a badge once earned
 * stays earned, so the set only grows and needs no per-run bookkeeping.
 */
object TypingAchievements {

    const val WPM_100 = "wpm100"
    const val PERFECT = "perfect"
    const val PANGRAM = "pangram"
    const val TESTS_50 = "tests50"

    /** Every badge there is, in display order. */
    val ALL = listOf(WPM_100, PERFECT, PANGRAM, TESTS_50)

    /** A flawless run shorter than this is luck, not typing. */
    const val PERFECT_MIN_CHARS = 30

    /** How many finished runs the persistence badge asks for. */
    const val TESTS_GOAL = 50

    fun decode(raw: String): Set<String> =
        raw.split(',').filter { it in ALL }.toSet()

    fun encode(ids: Set<String>): String =
        ALL.filter { it in ids }.joinToString(",")

    /**
     * The badges [result] earns. [testsCompleted] counts this run;
     * [isPangram] says whether the run's prompt covered the whole alphabet —
     * the caller knows the prompt, this object only knows the score.
     */
    fun evaluate(result: TypingResult, testsCompleted: Int, isPangram: Boolean): Set<String> {
        val earned = mutableSetOf<String>()
        if (result.wpm >= 100.0) earned += WPM_100
        val flawless = result.incorrectChars == 0 && result.extraChars == 0 &&
            result.missedChars == 0 && result.correctChars >= PERFECT_MIN_CHARS
        if (flawless) earned += PERFECT
        if (isPangram && result.mode == TypingTestMode.QUOTE) earned += PANGRAM
        if (testsCompleted >= TESTS_GOAL) earned += TESTS_50
        return earned
    }

    /** True when [text] uses every letter a to z — the pangram badge's gate. */
    fun isPangram(text: String): Boolean {
        val letters = text.lowercase()
        return ('a'..'z').all { it in letters }
    }
}

/**
 * The last few results, newest last, as a comma list of WPM values. Feeds
 * the little trend bar on the results screen.
 */
object TypingHistory {
    const val LIMIT = 24

    fun decode(raw: String): List<Double> =
        raw.split(',').mapNotNull { it.toDoubleOrNull() }

    fun append(raw: String, wpm: Double): String =
        (decode(raw) + wpm).takeLast(LIMIT)
            .joinToString(",") { ((it * 10).roundToInt() / 10.0).toString() }
}
