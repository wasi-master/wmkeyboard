package com.wasimaster.wmkeyboard.ime.emojimix

/** One mix as the keyboard draws it: the two emoji in the image's order, and where its image is. */
data class EmojiMixCell(
    val id: String,
    val left: String,
    val right: String,
    /** The image to draw: its address, or the file it was fetched into. */
    val url: String,
    /** Whether a source vouches the pair has a mix; an unverified one is checked when fetched. */
    val verified: Boolean,
) {
    /** How a favourite is stored: `leftKey_rightKey`. */
    val pairKey: String
        get() = com.wasimaster.wmkeyboard.core.emoji.mix.MixSeq.key(left) + "_" +
            com.wasimaster.wmkeyboard.core.emoji.mix.MixSeq.key(right)
}

/**
 * The emoji mixes panel: the two emoji picked, their mix, every mix of the
 * first, and the emoji there are to pick from. Lives on the keyboard's state
 * so the hardware focus ring and the panel read one copy.
 */
data class EmojiMixUi(
    /** Some source is on and loaded. */
    val usable: Boolean = false,
    /** A source can list an emoji's mixes; without one, the panel can only try pairs. */
    val canList: Boolean = false,
    /** The first emoji, or empty before one is picked. */
    val left: String = "",
    /** The second emoji, or empty. */
    val right: String = "",
    /** Which emoji the next pick replaces: 0 the first, 1 the second. */
    val slot: Int = 0,
    /** The mix of [left] and [right]; null while one is missing or the pair has none. */
    val result: EmojiMixCell? = null,
    /** Both emoji are picked and no source has a mix of them. */
    val noMix: Boolean = false,
    /** Every mix of [left], in the order the settings ask for. */
    val mixes: List<EmojiMixCell> = emptyList(),
    /** The emoji there are to pick from, in the emoji catalog's order. */
    val emoji: List<String> = emptyList(),
    /**
     * The picker's tabs: recent emoji when any of them mix, then each emoji
     * category that has an emoji here, in the emoji panel's order.
     */
    val tabs: List<String> = emptyList(),
    /** The tab the picker shows. */
    val tab: String = "",
    /** Each tab's emoji. */
    val byTab: Map<String, List<String>> = emptyMap(),
    /** The emoji there are to pick from that match the panel's search box. */
    val searched: List<String> = emptyList(),
    /** The mixes the user starred, newest first. */
    val favourites: List<EmojiMixCell> = emptyList(),
    /** Previews wait: data saving holds them on this connection. */
    val previewsHeld: Boolean = false,
    /** Mixes are being looked for (a mixer with no index checks each one). */
    val loading: Boolean = false,
)
