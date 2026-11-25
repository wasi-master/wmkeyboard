package com.wasimaster.wmkeyboard.core.stickers.dynamic

/**
 * Which templates suit a message, best first.
 *
 * Four tiers, each of which the user can switch off:
 * 1. [Tier.PHRASE] — the whole message is one of a template's phrases
 *    ("thank you" for a thanks template);
 * 2. [Tier.INSIDE] — one of its phrases appears in the message at word
 *    boundaries ("thank you so much");
 * 3. [Tier.CONCEPT] — the feelings and things the message's emoji and words
 *    point at ("😂" or "lol" for laugh) meet the template's concepts;
 * 4. [Tier.GENERIC] — templates that take any short text.
 *
 * A template is only offered when the message fits it: enough words for its
 * lines and none of them too long or too short (see [TextFit.splitLines]).
 * Within a tier the user's favourites lead; the rest are shuffled by the
 * message, so the same words always get the same row and different words a
 * different one.
 *
 * Built once per change of the templates; a match is a few hash lookups.
 */
class DynMatcher(templates: List<DynTemplate>) {

    enum class Tier { PHRASE, INSIDE, CONCEPT, GENERIC }

    data class Options(
        val tiers: Set<Tier> = Tier.entries.toSet(),
        /** Messages longer than this get nothing (a template's own cap can be lower). */
        val maxWords: Int = DEFAULT_MAX_WORDS,
        val limit: Int = DEFAULT_LIMIT,
        /** Memes too, not only stickers. */
        val memes: Boolean = false,
        /** Templates with photo slots, which need a picture before they can be sent. */
        val photos: Boolean = false,
        val favourites: Set<String> = emptySet(),
        val disabled: Set<String> = emptySet(),
    )

    /** One template the message suits, and the text each of its slots takes. */
    data class Hit(val template: DynTemplate, val tier: Tier, val fill: DynFill)

    private val all = templates
    private val byPhrase = HashMap<String, MutableList<DynTemplate>>()
    private val byConcept = HashMap<String, MutableList<DynTemplate>>()
    private val generic = templates.filter { it.meta.match.generic }
    private val longestPhrase: Int

    init {
        var longest = 0
        for (template in templates) {
            for (phrase in template.phrases) {
                if (phrase.isEmpty()) continue
                byPhrase.getOrPut(phrase) { ArrayList() } += template
                longest = maxOf(longest, phrase.count { it == ' ' } + 1)
            }
            for (concept in template.meta.match.concepts) {
                byConcept.getOrPut(EmojiConcepts.canonical(concept)) { ArrayList() } += template
            }
        }
        longestPhrase = longest
    }

    val isEmpty: Boolean get() = all.isEmpty()

    /**
     * The templates [text] suits under [options], best first. [conceptsOf]
     * adds concepts for a word beyond the built-in cues: the keyboard passes
     * one reading its emoji keyword packs, so "pizza" finds a template tagged
     * with what 🍕 means there.
     */
    fun match(text: String, options: Options, conceptsOf: (String) -> Collection<String> = { emptyList() }): List<Hit> {
        val normalized = DynText.normalize(text)
        val display = DynText.display(text)
        val words = normalized.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > options.maxWords || display.isEmpty()) return emptyList()

        val chosen = LinkedHashMap<String, Hit>()
        val seed = normalized.hashCode()
        fun offer(candidates: Collection<DynTemplate>, tier: Tier) {
            if (tier !in options.tiers || chosen.size >= options.limit) return
            val usable = candidates.filter { usable(it, options, words.size) && it.key !in chosen }
            for (template in order(usable, options.favourites, seed)) {
                if (chosen.size >= options.limit) return
                val fill = fillFor(template, text) ?: continue
                chosen[template.key] = Hit(template, tier, fill)
            }
        }

        offer(byPhrase[normalized].orEmpty(), Tier.PHRASE)
        if (Tier.INSIDE in options.tiers && words.size > 1) {
            val inside = LinkedHashSet<DynTemplate>()
            // Every run of words shorter than the whole message, longest first.
            for (length in minOf(words.size - 1, longestPhrase) downTo 1) {
                for (start in 0..words.size - length) {
                    val key = words.subList(start, start + length).joinToString(" ")
                    byPhrase[key]?.let { inside += it }
                }
            }
            offer(inside, Tier.INSIDE)
        }
        if (Tier.CONCEPT in options.tiers) {
            val concepts = LinkedHashSet<String>()
            DynText.emoji(text).forEach { concepts += EmojiConcepts.of(it) }
            for (word in words) {
                concepts += EmojiConcepts.ofWord(word)
                conceptsOf(word).forEach { concepts += EmojiConcepts.canonical(it) }
            }
            val found = LinkedHashSet<DynTemplate>()
            for (concept in concepts) byConcept[concept]?.let { found += it }
            offer(found, Tier.CONCEPT)
        }
        offer(generic, Tier.GENERIC)
        return chosen.values.toList()
    }

    private fun usable(template: DynTemplate, options: Options, wordCount: Int): Boolean {
        if (template.key in options.disabled) return false
        if (template.kind == DynKind.MEME && !options.memes) return false
        if (template.meta.photos.isNotEmpty() && !options.photos) return false
        val cap = template.meta.match.maxWords
        return cap <= 0 || wordCount <= cap
    }

    companion object {
        const val DEFAULT_MAX_WORDS = 6
        const val DEFAULT_LIMIT = 8

        /**
         * The text each slot of [template] takes from [text], or null when
         * the message does not fit the template's lines.
         *
         * A message with line breaks or `|` in it is split over the slots in
         * order (top text | bottom text); otherwise it all goes into the main
         * slot and the others keep the template's own words.
         */
        fun fillFor(template: DynTemplate, text: String): DynFill? {
            val slots = template.slots
            if (slots.isEmpty()) return null
            val parts = text.split('\n', '|').map { DynText.display(it) }.filter { it.isNotEmpty() }
            val texts = LinkedHashMap<String, String>()
            if (parts.size > 1 && slots.size > 1) {
                slots.forEachIndexed { i, slot -> parts.getOrNull(i)?.let { texts[slot.id] = it } }
            } else {
                val main = template.mainSlot ?: return null
                texts[main.id] = DynText.display(text)
            }
            for ((slotId, value) in texts) {
                val slot = slots.first { it.id == slotId }
                if (!fits(slot, value)) return null
            }
            return DynFill(texts)
        }

        /** Whether [text] can be laid over [slot]'s lines within their character limits. */
        fun fits(slot: TextSlot, text: String): Boolean {
            val words = DynText.words(text)
            if (words.isEmpty()) return false
            val lineCount = slot.layers.size.coerceAtLeast(1)
            return TextFit.splitLines(words, lineCount, slot::rule) != null
        }

        /**
         * [templates] with [favourites] first, each group in an order fixed by
         * [seed]: shuffled, but the same for the same message.
         */
        fun order(templates: List<DynTemplate>, favourites: Set<String>, seed: Int): List<DynTemplate> =
            templates.sortedWith(
                compareBy<DynTemplate> { it.key !in favourites }
                    .thenBy { mix(it.key.hashCode() xor seed) },
            )

        /** A cheap integer mix, so keys that differ by a letter do not sort side by side. */
        private fun mix(value: Int): Int {
            var h = value
            h = h xor (h ushr SHIFT_A)
            h *= MIX_A
            h = h xor (h ushr SHIFT_B)
            h *= MIX_B
            return h xor (h ushr SHIFT_A)
        }

        private const val SHIFT_A = 16
        private const val SHIFT_B = 13
        private const val MIX_A = -0x7a143595
        private const val MIX_B = -0x3d4d51cb
    }
}

/**
 * What an emoji or a word says about a message, in the concept names
 * templates are tagged with. Built-in and small: the common faces, hearts and
 * party things, plus the words people type instead of them.
 */
object EmojiConcepts {

    /** The concept names, as tagged, for [emoji] (selectors and skin tones ignored). */
    fun of(emoji: String): List<String> = BY_EMOJI[strip(emoji)].orEmpty()

    /** The concepts a typed word points at ("lol" → laugh). */
    fun ofWord(word: String): List<String> = BY_WORD[word].orEmpty()

    /**
     * [concept] in its canonical spelling: lower case, spaces as `_`, and the
     * misspellings published template sets carry ("embarassed",
     * "starstuck", "explossion") folded into the right one.
     */
    fun canonical(concept: String): String {
        val clean = concept.trim().lowercase().replace(' ', '_').replace('-', '_')
        return ALIASES[clean] ?: clean
    }

    private fun strip(emoji: String): String = buildString {
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == VS15 || cp == VS16 || cp in SKIN_TONES) continue
            appendCodePoint(cp)
        }
    }

    private const val VS15 = 0xFE0E
    private const val VS16 = 0xFE0F
    private val SKIN_TONES = 0x1F3FB..0x1F3FF

    private val ALIASES = mapOf(
        "embarassed" to "embarrassed",
        "starstuck" to "starstruck",
        "explossion" to "explosion",
        "congreatulations" to "congratulations",
        "heart_heart_eyes" to "heart_eyes",
        "hearty_eyes" to "heart_eyes",
        "flower_gift" to "flower",
    )

    private fun table(vararg rows: Pair<String, String>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for ((keys, concepts) in rows) {
            val list = concepts.split(' ')
            for (key in keys.split(' ')) if (key.isNotEmpty()) out.getOrPut(key) { ArrayList() } += list
        }
        return out
    }

    private val BY_EMOJI: Map<String, List<String>> = table(
        "😀 😃 😄 😁 🙂" to "grinning",
        "😆 😹" to "laugh",
        "😂 🤣" to "laugh laughing_crying",
        "😊 ☺" to "smiling_blush",
        "🥰" to "hearty_blush heart",
        "😍 😻" to "heart_eyes heart",
        "😘 😚 😙 💋" to "blow_kiss",
        "😉 😜" to "wink",
        "😎" to "cool",
        "🤩 ✨ 🌟 ⭐" to "starstruck awesome",
        "😇" to "angel",
        "😌" to "relieved",
        "🤔 🧐" to "thinking",
        "🙄" to "eye_roll whatever",
        "🤦" to "facepalm",
        "😳 😬" to "embarrassed",
        "😢 😭 😿 💔" to "crying",
        "😞 😔 😟 ☹ 🙁 😕" to "displeasure",
        "😠 😡 🤬 👿" to "anger",
        "😤 😩 😫 😒" to "annoyed",
        "😑 😐 🤷" to "whatever",
        "🤐 🤫 😶" to "quiet",
        "😮‍💨 😪" to "sigh",
        "🥺 🙏" to "pleading_face",
        "🤗 🫂" to "hug",
        "😋 🤤 🍕 🍔 🍩" to "yum",
        "👋" to "waving",
        "❤ ♥ 💖 💗 💓 💞 💕 💘 💝 🧡 💛 💚 💙 🤍 ❣" to "heart",
        "💜" to "purple_heart heart",
        "🎂 🍰 🧁" to "cake birthday",
        "🎈" to "balloon birthday",
        "🎉 🎊 🥳" to "celebration congratulations",
        "👏 🏆 🥇" to "congratulations",
        "🎁" to "gift",
        "🌹" to "rose flower",
        "🌸 🌺 🌻 🌷 💐 🌼" to "flower",
        "☀ 🌞 🌅" to "sun",
        "☕ 🍵" to "coffee",
        "👑" to "crown",
        "💥 🤯" to "explosion",
        "🔥 💯 👍 👌 🙌" to "awesome",
        "🍀 🤞" to "good_luck",
    )

    private val BY_WORD: Map<String, List<String>> = table(
        "lol lmao lmfao rofl haha hahaha hehe hihi" to "laugh",
        "love luv lovely" to "heart",
        "congrats congratulations congratz" to "congratulations celebration",
        "birthday bday hbd" to "birthday",
        "party yay woohoo" to "celebration",
        "sorry" to "displeasure",
        "sad" to "crying",
        "angry mad furious" to "anger",
        "ugh annoying" to "annoyed",
        "coffee tea" to "coffee",
        "morning sunny" to "sun",
        "hug hugs" to "hug",
        "kiss kisses" to "blow_kiss",
        "wow awesome amazing great" to "awesome",
        "cool nice" to "cool",
        "yum yummy delicious hungry" to "yum",
        "bye hi hello hey" to "waving",
        "luck" to "good_luck",
        "hmm" to "thinking",
        "please" to "pleading_face",
        "gift present" to "gift",
        "flowers flower rose" to "flower",
    )
}
