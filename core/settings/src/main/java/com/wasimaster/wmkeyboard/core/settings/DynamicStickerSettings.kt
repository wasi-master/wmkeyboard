package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Dynamic stickers: the words being typed, drawn into sticker and meme
 * templates. Offered in the sticker row while typing (in fields that take
 * images) and under a Text chip in the stickers panel.
 *
 * Everything about what the row offers is the user's to set: which kinds of
 * match count, how long a message may be, how many show, whether memes and
 * generic templates join in, and where they sit beside the user's own
 * stickers and emoji mixes.
 *
 * Nested in [GifSettings] (whose sticker settings it sits beside) because
 * `KeyboardSettings` is at its field ceiling; the DataStore keys stay flat
 * (`dynsticker_*`). Which packs and templates are on, and the favourites,
 * live with the packs themselves, not here.
 */
data class DynamicStickerSettings(
    val enabled: Boolean = true,
    /** Offer them in the sticker row while typing. Off leaves them in the panel only. */
    val inTray: Boolean = true,
    /** Templates whose phrase is the whole message ("thank you"). */
    val matchPhrase: Boolean = true,
    /** Templates whose phrase is somewhere in the message ("thank you so much"). */
    val matchInside: Boolean = true,
    /** Templates tagged with what the message's emoji and words mean (😂, "lol"). */
    val matchFeelings: Boolean = true,
    /** When templates that take any text join the row. */
    val generic: DynGenericMode = DynGenericMode.AFTER_MATCH,
    /** Longer messages get no offer. See [DynMaxWordsRange]. */
    val maxWords: Int = 6,
    /** How many the row offers at most. See [DynMaxInTrayRange]. */
    val maxInTray: Int = 8,
    /** Text-only memes join the row too (memes with a photo never do: they need a picture first). */
    val memesInTray: Boolean = false,
    /** Moving templates move in the row as well, rather than showing their still. */
    val animateInTray: Boolean = false,
    /** How the row is drawn; the same choices as the user's own stickers. */
    val style: StickerSuggestStyle = StickerSuggestStyle.TRAY,
    /** Where these sit when the user's own stickers or emoji mixes also match. */
    val order: DynOfferOrder = DynOfferOrder.AFTER_OWN,
    /** Send moving templates as animated stickers, or always as their still. */
    val sendAnimated: Boolean = true,
    /** A font every template is drawn in instead of its own, as a font-picker id; blank for each template's own. */
    val fontOverride: String = "",
    /** Which templates the exact engine draws, when it is installed. */
    val exactEngine: DynExactMode = DynExactMode.IMPORTED,
    /** The panel's Text grid starts from the message being typed. */
    val panelUsesTyped: Boolean = true,
    /** Feed links the user added, in the order they were added. */
    val feeds: List<DynStickerFeed> = emptyList(),
    /** Check every feed for a new version once a week, on Wi-Fi only. */
    val autoRefresh: Boolean = true,
)

/** When generic templates (which take any text) are offered. */
enum class DynGenericMode {
    /** Never in the row; still in the panel. */
    OFF,

    /** Only to fill the row after something matched the message by meaning. */
    AFTER_MATCH,

    /** For any short message in a field that takes images. */
    ALWAYS,
}

/** Where dynamic stickers sit in the row beside the user's own stickers and emoji mixes. */
enum class DynOfferOrder {
    /** Own stickers first, then mixes, then these. */
    AFTER_OWN,

    /** These first. */
    FIRST,

    /** Only when neither own stickers nor mixes matched. */
    ONLY_WHEN_NOTHING_ELSE,
}

/** Which templates the downloadable exact engine draws. */
enum class DynExactMode {
    NEVER,

    /** Packs from feeds and the addon repository, made for it; the built-in and the user's own stay on Lottie. */
    IMPORTED,

    ALWAYS,
}

val DynMaxWordsRange = 1..20
val DynMaxInTrayRange = 1..30

/**
 * A feed link the user added: an index that lists packs to download, ours or
 * the zip-set manifest some template sets ship. Everything but [url] is
 * learned when it is refreshed.
 */
@Serializable
data class DynStickerFeed(
    val id: String,
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    /** The feed's own version string when it has one, else a hash of the index. */
    val version: String = "",
    val refreshedAt: Long = 0L,
    /** The last refresh's error, shown under the feed; null when it worked. */
    val error: String? = null,
    /** The packs it installed. */
    @SerialName("packs")
    val packIds: List<String> = emptyList(),
)

private val feedJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}
private val feedListSerializer = ListSerializer(DynStickerFeed.serializer())

fun encodeDynStickerFeeds(feeds: List<DynStickerFeed>): String = feedJson.encodeToString(feedListSerializer, feeds)

fun decodeDynStickerFeeds(raw: String?): List<DynStickerFeed> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { feedJson.decodeFromString(feedListSerializer, raw) }.getOrDefault(emptyList())
}
