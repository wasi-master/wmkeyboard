package com.wasimaster.wmkeyboard.core.emoji

/**
 * Word → emoji prediction for the suggestion strip: typing "birthday"
 * offers 🎂🎉🥳🎁, "coffee" offers ☕, "bangladesh" offers 🇧🇩.
 *
 * Four layers, high precision on purpose (this rides along with word
 * suggestions, so a wrong emoji is worse than no emoji):
 *  1. a small curated trigger map, which is where the ordering judgements and
 *     the Bengali triggers live — gemoji has neither;
 *  2. [EmojiTriggers], GitHub's own word→emoji data, which covers far more
 *     English than a hand-written table can ("hooray", "espresso", "workout");
 *  3. an exact [EmojiShortcodes] hit, so the name someone would type between
 *     colons works as plain text too — mostly the two-word codes ("alarm
 *     clock", "crossed fingers"), which nothing else here can reach;
 *  4. exact keyword hits from the catalog (with a trailing plural 's'
 *     stripped) — no prefix or fuzzy matching.
 *
 * The layers stack rather than replace: gemoji has nothing for "sorry",
 * "work" or "sleep", and the curated map has nothing for the other thousand
 * words gemoji names.
 */
class EmojiSuggester(
    entries: List<EmojiEntry>,
    private val triggers: EmojiTriggers = EmojiTriggers.EMPTY,
    private val shortcodes: EmojiShortcodes = EmojiShortcodes.EMPTY,
) {

    /**
     * The catalog's keywords, packed: every keyword sorted in [keywords], and
     * the emoji filed under keyword `k` at `emoji[start[k] until start[k + 1]]`,
     * in catalog order. This rides along with plain typing, so it is always
     * held; as a `HashMap<String, MutableList<String>>` it was 1.28 MB with
     * the English and Bengali packs merged in and 2.46 MB with Banglish and
     * Hindi too (10,800 and 20,900 keywords), packed 183 and 331 KB (measured
     * on a desktop JVM, 2026-11-26). The keyword and emoji strings are the
     * catalog's own, not copies.
     */
    private val keywords: Array<String>
    private val start: IntArray
    private val emoji: Array<String>

    init {
        val words = ArrayList<String>()
        val faces = ArrayList<String>()
        for (entry in entries) {
            // Gender/role variants would flood matches like "man"/"woman";
            // their base emoji already carries the concept.
            if (entry.parent != null) continue
            for (keyword in entry.keywords) {
                words.add(keyword)
                faces.add(entry.emoji)
            }
        }
        // Stable, so the emoji under one keyword keep the catalog's order.
        val order = words.indices.sortedWith { a, b -> words[a].compareTo(words[b]) }
        val keys = ArrayList<String>()
        val starts = IntArray(order.size + 1)
        val filed = arrayOfNulls<String>(order.size)
        var count = 0
        for (i in order) {
            val word = words[i]
            val face = faces[i]
            if (keys.isEmpty() || keys[keys.size - 1] != word) {
                starts[keys.size] = count
                keys.add(word)
            } else if (filed[count - 1] == face) {
                // The same keyword twice in one entry: one hit is enough.
                continue
            }
            filed[count++] = face
        }
        starts[keys.size] = count
        keywords = keys.toTypedArray()
        start = starts.copyOf(keys.size + 1)
        emoji = Array(count) { filed[it]!! }
    }

    /** Adds the emoji filed under exactly [word], in catalog order. */
    private fun addKeywordHits(word: String, into: MutableSet<String>) {
        val k = keywords.binarySearch(word)
        if (k < 0) return
        for (i in start[k] until start[k + 1]) into.add(emoji[i])
    }

    /**
     * Emoji for [word]. [previous] is the word committed before it, which lets
     * the two together match a two-word shortcode ("alarm clock" → ⏰); pass
     * "" when there is none, or when the two are not adjacent.
     */
    fun suggest(word: String, previous: String = "", limit: Int = 4): List<String> {
        val cleaned = word.trim().lowercase()
        // One letter is no word, but one Han character or kana often is: 笑,
        // 猫 and 雨 each name an emoji in the Chinese and Japanese packs (#633).
        if (cleaned.isEmpty() || cleaned.length < 2 && !isLogogram(cleaned[0])) return emptyList()
        val results = LinkedHashSet<String>()
        // A two-word hit is the more specific reading of what was typed, so it
        // leads: "alarm clock" is a clock, not an alarm.
        phraseHit(previous.trim().lowercase(), cleaned)?.let { results.add(it) }
        addLayers(cleaned, results)
        if (results.size < limit && cleaned.length >= 4 && cleaned.endsWith("s")) {
            addLayers(cleaned.dropLast(1), results)
        }
        return results.take(limit).toList()
    }

    /**
     * Emoji for the words of [text], the text before the caret, for the emoji
     * panel's Suggested tab (#1554): each of the last [maxWords] words through
     * [suggest], the same lookup the strip makes for one word, the last word
     * first, since what was written last is most likely what the emoji is
     * for. "time is ticking" gives the clocks before anything else.
     */
    fun suggestForText(text: String, maxWords: Int = MAX_TEXT_WORDS, limit: Int = MAX_TEXT_EMOJI): List<String> {
        val words = WORD.findAll(text).map { it.value }.toList().takeLast(maxWords)
        val results = LinkedHashSet<String>()
        for (i in words.indices.reversed()) {
            results.addAll(suggest(words[i], words.getOrElse(i - 1) { "" }, limit = PER_WORD))
            if (results.size >= limit) break
        }
        return results.take(limit)
    }

    /**
     * Most of what only the shortcode table names is two words joined by an
     * underscore — `alarm_clock`, `crossed_fingers`, `jack_o_lantern` — which
     * a one-word lookup can never reach.
     */
    private fun phraseHit(previous: String, word: String): String? {
        if (previous.length < 2 || word.length < 2) return null
        return shortcodes.exact("${previous}_$word")
    }

    /** The four layers for one word, in the order they should be offered. */
    private fun addLayers(word: String, into: MutableSet<String>) {
        TRIGGERS[word]?.let { into.addAll(it) }
        into.addAll(triggers.of(word))
        // Exact only. A shortcode prefix is the inline `:query` search's job,
        // where the colon says the user is naming an emoji; here the word is
        // ordinary text and a prefix match would fire on half of what is typed.
        // Two-letter codes are flags and enclosed letters (`:it:`, `:us:`,
        // `:ok:`), which is the wrong-emoji case this whole class avoids.
        if (word.length >= MIN_SHORTCODE_LENGTH) shortcodes.exact(word)?.let { into.add(it) }
        addKeywordHits(word, into)
    }

    /** A character that is a word on its own: a Han ideograph, or Japanese kana. */
    private fun isLogogram(c: Char): Boolean = Character.isIdeographic(c.code) ||
        Character.UnicodeBlock.of(c) == Character.UnicodeBlock.HIRAGANA ||
        Character.UnicodeBlock.of(c) == Character.UnicodeBlock.KATAKANA

    companion object {

        /** Shortest word that may match a shortcode. See [addLayers]. */
        private const val MIN_SHORTCODE_LENGTH = 3

        /** How far back [suggestForText] reads: about a sentence. */
        const val MAX_TEXT_WORDS = 12

        /** The most emoji [suggestForText] gives: a few rows of the panel. */
        const val MAX_TEXT_EMOJI = 48

        /** Emoji per word in [suggestForText]: more than the strip's four, since the panel has room. */
        private const val PER_WORD = 8

        /** A word for [suggestForText]: letters, marks and digits, an apostrophe inside. */
        private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}]+)*")

        /**
         * Curated word → emoji triggers; listed emojis always come first.
         *
         * Deliberately kept alongside [EmojiTriggers] rather than folded into
         * it: this is where the Bengali triggers live, and where an ordering
         * is chosen by hand ("birthday" leading with 🎂 rather than 🥳).
         */
        private val TRIGGERS: Map<String, List<String>> = mapOf(
            "birthday" to listOf("🎂", "🎉", "🥳", "🎁"),
            "coffee" to listOf("☕", "😊"),
            "tea" to listOf("🍵", "☕"),
            "party" to listOf("🥳", "🎉", "🪩"),
            "congrats" to listOf("🎉", "👏", "🥳"),
            "congratulations" to listOf("🎉", "👏", "🥳"),
            "thanks" to listOf("🙏", "😊", "❤️"),
            "thank" to listOf("🙏", "😊"),
            "love" to listOf("❤️", "😍", "🥰"),
            "haha" to listOf("😂", "🤣"),
            "lol" to listOf("😂", "🤣"),
            "wow" to listOf("😮", "🤩"),
            "sad" to listOf("😢", "😞", "💔"),
            "cry" to listOf("😭", "😢"),
            "angry" to listOf("😠", "😡"),
            "sorry" to listOf("😔", "🙏"),
            "please" to listOf("🙏", "🥺"),
            "good" to listOf("👍", "😊"),
            "great" to listOf("👏", "🔥", "💯"),
            "night" to listOf("🌙", "😴", "✨"),
            "morning" to listOf("🌅", "☀️", "☕"),
            "sleep" to listOf("😴", "💤", "🛌"),
            "fire" to listOf("🔥"),
            "hot" to listOf("🥵", "🔥", "☀️"),
            "cold" to listOf("🥶", "❄️"),
            "rain" to listOf("🌧️", "☔", "⛈️"),
            "sun" to listOf("☀️", "😎"),
            "food" to listOf("🍽️", "😋", "🍚"),
            "hungry" to listOf("😋", "🍽️"),
            "pizza" to listOf("🍕"),
            "cake" to listOf("🍰", "🎂"),
            "music" to listOf("🎵", "🎶", "🎧"),
            "cricket" to listOf("🏏"),
            "football" to listOf("⚽", "🏈"),
            "win" to listOf("🏆", "🥇", "🎉"),
            "money" to listOf("💰", "💸"),
            "work" to listOf("💼", "💻"),
            "home" to listOf("🏠", "🏡"),
            "car" to listOf("🚗"),
            "flight" to listOf("✈️", "🛫"),
            "beach" to listOf("🏖️", "🌊"),
            "book" to listOf("📚", "📖"),
            "idea" to listOf("💡", "🤔"),
            "ok" to listOf("👌", "👍"),
            "yes" to listOf("✅", "👍"),
            "no" to listOf("❌", "🙅"),
            "wedding" to listOf("💍", "👰", "🤵", "💒"),
            "baby" to listOf("👶", "🍼"),
            "eid" to listOf("🌙", "🕌", "✨"),
            "ramadan" to listOf("🌙", "🕌", "🤲"),
            "iftar" to listOf("🌙", "🍽️", "🕌"),
            "prayer" to listOf("🤲", "🙏", "🕌"),
            "puja" to listOf("🙏", "🪔", "🌺"),
            // Bengali triggers
            "জন্মদিন" to listOf("🎂", "🎉", "🥳", "🎁"),
            "শুভ" to listOf("✨", "🎉", "😊"),
            "ভালোবাসা" to listOf("❤️", "😍", "🥰"),
            "ভালোবাসি" to listOf("❤️", "😍", "🥰"),
            "হাসি" to listOf("😂", "😄"),
            "কান্না" to listOf("😭", "😢"),
            "ধন্যবাদ" to listOf("🙏", "😊", "❤️"),
            "অভিনন্দন" to listOf("🎉", "👏", "🥳"),
            "চা" to listOf("🍵", "☕"),
            "কফি" to listOf("☕", "😊"),
            "বাংলাদেশ" to listOf("🇧🇩"),
            "ঈদ" to listOf("🌙", "🕌", "✨"),
            "রমজান" to listOf("🌙", "🕌", "🤲"),
            "ইফতার" to listOf("🌙", "🍽️", "🕌"),
            "নামাজ" to listOf("🤲", "🕌"),
            "দোয়া" to listOf("🤲", "🙏"),
            "পূজা" to listOf("🙏", "🪔", "🌺"),
            "বৃষ্টি" to listOf("🌧️", "☔"),
            "ঘুম" to listOf("😴", "💤"),
            "খাবার" to listOf("🍽️", "😋", "🍚"),
            "ক্রিকেট" to listOf("🏏"),
            "ফুটবল" to listOf("⚽"),
            "আগুন" to listOf("🔥"),
            "মন" to listOf("💙", "🥰"),
            "মিষ্টি" to listOf("🍬", "🍮", "😋"),
        )
    }
}
