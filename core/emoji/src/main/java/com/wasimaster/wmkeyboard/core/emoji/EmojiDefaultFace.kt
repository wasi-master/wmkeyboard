package com.wasimaster.wmkeyboard.core.emoji

/**
 * The face a base emoji shows when nothing was picked for it yet: first a
 * gender (the base person, or its woman or man variant), then a skin tone.
 * Either may be random (AnySoftKeyboard's "Emoji default gender" and its
 * random tone, gap K2 of the 2026-11-13 comparison).
 *
 * Pure: the caller decides whether the last pick for this emoji wins first
 * (`toneOverrideByLastUsed`), and passes the catalog's gender children.
 */
object EmojiDefaultFace {

    /** The default the user chose. [PERSON] keeps the gender-neutral base. */
    enum class Gender { PERSON, WOMAN, MAN, RANDOM }

    /** Tone index meaning a random tone per emoji; 0 is neutral, 1..5 🏻..🏿. */
    const val RANDOM_TONE: Int = -1

    /**
     * The seed random picks hash with. Fixed while it stays put, so an emoji
     * drawn in the grid commits as drawn; [reroll] when the emoji panel opens
     * gives a fresh set the next time.
     */
    @Volatile
    var seed: Int = (System.nanoTime() xor 0x5DEECE66DL).toInt()
        private set

    fun reroll() {
        seed = (System.nanoTime() xor seed.toLong() * 0x9E3779B9L).toInt()
    }

    /**
     * [base] with [gender] and [tone] applied. [children] are the catalog
     * entries whose parent is [base] (🏃‍♀️ and 🏃‍♂️ under 🏃); a gender with
     * no child of its own keeps [base], and a tone with no RGI form keeps the
     * untoned face.
     */
    fun resolve(
        base: String,
        children: List<String>,
        variants: EmojiVariantIndex,
        gender: Gender,
        tone: Int,
        seed: Int = this.seed,
    ): String {
        val shape = when (gender) {
            Gender.PERSON -> base
            Gender.WOMAN -> genderedChild(children, Gender.WOMAN) ?: base
            Gender.MAN -> genderedChild(children, Gender.MAN) ?: base
            Gender.RANDOM -> {
                val options = listOfNotNull(
                    base,
                    genderedChild(children, Gender.WOMAN),
                    genderedChild(children, Gender.MAN),
                )
                options[pick(base, seed, 1, options.size)]
            }
        }
        val t = if (tone == RANDOM_TONE) 1 + pick(base, seed, 2, 5) else tone
        if (t <= 0) return shape
        return variants.tonedPair(shape, t) ?: shape
    }

    /** The first of [children] that is one [want] person, or null. */
    fun genderedChild(children: List<String>, want: Gender): String? =
        children.firstOrNull { genderOf(it) == want }

    /**
     * [Gender.WOMAN] or [Gender.MAN] for a single-person gendered emoji
     * (🏃‍♀️, 👨‍⚕️, 🧔‍♂️, 🧜‍♂️), else null. Couples, kisses and families hold
     * more than one person and are never a default gender's pick.
     */
    fun genderOf(emoji: String): Gender? {
        var people = 0
        var first = -1
        var sign: Gender? = null
        var i = 0
        while (i < emoji.length) {
            val cp = emoji.codePointAt(i)
            if (first < 0) first = cp
            when (cp) {
                MAN, WOMAN, PERSON, BOY, GIRL, CHILD -> people++
                MALE_SIGN -> sign = Gender.MAN
                FEMALE_SIGN -> sign = Gender.WOMAN
            }
            i += Character.charCount(cp)
        }
        if (people > 1) return null
        return sign ?: when (first) {
            MAN -> Gender.MAN
            WOMAN -> Gender.WOMAN
            else -> null
        }
    }

    /** Parent → children, built once per catalog list (identity-cached). */
    fun children(catalog: List<EmojiEntry>): Map<String, List<String>> {
        cache?.let { (list, map) -> if (list === catalog) return map }
        val map = catalog
            .mapNotNull { entry -> entry.parent?.let { it to entry.emoji } }
            .groupBy({ it.first }, { it.second })
        cache = catalog to map
        return map
    }

    @Volatile
    private var cache: Pair<List<EmojiEntry>, Map<String, List<String>>>? = null

    /** A stable index below [size] for [base] under [seed]; [salt] keeps gender and tone apart. */
    private fun pick(base: String, seed: Int, salt: Int, size: Int): Int {
        var h = base.hashCode() * 31 + seed
        h = h * 0x2C1B3C6D + salt * 0x297A2D39
        h = h xor (h ushr 15)
        h *= 0x45D9F3B
        h = h xor (h ushr 16)
        return Math.floorMod(h, size)
    }

    private const val MAN = 0x1F468
    private const val WOMAN = 0x1F469
    private const val PERSON = 0x1F9D1
    private const val BOY = 0x1F466
    private const val GIRL = 0x1F467
    private const val CHILD = 0x1F9D2
    private const val MALE_SIGN = 0x2642
    private const val FEMALE_SIGN = 0x2640
}
