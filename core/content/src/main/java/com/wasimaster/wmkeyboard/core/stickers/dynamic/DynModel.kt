package com.wasimaster.wmkeyboard.core.stickers.dynamic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Dynamic stickers: a picture made on the spot from a template and the words
 * the user typed. A template is a Lottie animation (After Effects exported as
 * JSON) whose text layers take the typed text, with rules for which messages
 * it suits and how the words are laid into its lines.
 *
 * Templates come in packs: the built-in one, packs the user made in the
 * builder or imported from a file, and packs downloaded from a feed link or
 * the addon repository. This file holds the shapes a pack's manifest is
 * written in; [DynTemplate] is one template as the keyboard uses it.
 */

/** What a template makes. Memes sit apart in the panel and stay out of the tray unless asked for. */
@Serializable
enum class DynKind {
    @SerialName("sticker")
    STICKER,

    @SerialName("meme")
    MEME,
}

/** How typed text is cased before it is laid out. Null on a slot means "as the template's own text says". */
@Serializable
enum class TextCase {
    @SerialName("keep")
    KEEP,

    @SerialName("upper")
    UPPER,

    @SerialName("lower")
    LOWER,

    @SerialName("title")
    TITLE,
}

/** What a text slot is for, which is how a multi-slot meme splits a message and labels its fields. */
@Serializable
enum class SlotRole {
    @SerialName("main")
    MAIN,

    @SerialName("top")
    TOP,

    @SerialName("bottom")
    BOTTOM,

    @SerialName("label")
    LABEL,

    @SerialName("caption")
    CAPTION,

    @SerialName("name")
    NAME,
}

/**
 * Limits for one line of a slot: how many characters it takes and the range
 * its font size may be fitted in, in the template's own pixels. A size of 0
 * means "the template's own size is the bound".
 */
@Serializable
data class LineRule(
    val minChars: Int = 1,
    val maxChars: Int = DEFAULT_MAX_CHARS,
    val minFont: Float = 0f,
    val maxFont: Float = 0f,
) {
    companion object {
        const val DEFAULT_MAX_CHARS = 60
    }
}

/**
 * One place typed text goes.
 *
 * [layers] are the text layers (by their `nm`) that take it. With one layer
 * the whole text goes into it and wraps inside its box. With several, the
 * words are split over them, one line each, in order; [lines] holds one rule
 * per layer.
 */
@Serializable
data class TextSlot(
    val id: String,
    val layers: List<String>,
    val lines: List<LineRule> = emptyList(),
    val case: TextCase? = null,
    val role: SlotRole = SlotRole.MAIN,
    /** Shown above the field in the fill sheet, e.g. "Top text". Blank means the role's own name. */
    val label: String = "",
    /** What the slot says when nothing has been typed; blank means the template's own text. */
    val sample: String = "",
) {
    /** The rule for line [index], falling back to a permissive one. */
    fun rule(index: Int): LineRule = lines.getOrNull(index) ?: lines.lastOrNull() ?: LineRule()
}

/** How a picture fills its photo slot. */
@Serializable
enum class PhotoFit {
    @SerialName("cover")
    COVER,

    @SerialName("contain")
    CONTAIN,
}

/** A place for one of the user's own pictures: an image asset (by its `id`) the picture replaces. */
@Serializable
data class PhotoSlot(
    val id: String,
    val asset: String,
    val label: String = "",
    val fit: PhotoFit = PhotoFit.COVER,
)

/** Which messages a template suits. */
@Serializable
data class DynMatch(
    /** Phrases in any language, matched on the whole message or inside it. */
    val phrases: List<String> = emptyList(),
    /** Feelings and things ("heart", "birthday", "laugh") matched from the emoji and words around them. */
    val concepts: List<String> = emptyList(),
    /** Takes any short text, offered after the templates that matched by meaning. */
    val generic: Boolean = false,
    /** This template's own cap on words, 0 for the user's setting alone. */
    val maxWords: Int = 0,
)

/** Which engine draws a template. AUTO lets the user's "exact rendering" setting decide. */
@Serializable
enum class RendererHint {
    @SerialName("auto")
    AUTO,

    @SerialName("lottie")
    LOTTIE,

    @SerialName("exact")
    EXACT,
}

/** One template in a pack's manifest. */
@Serializable
data class TemplateMeta(
    val id: String,
    val name: String = "",
    /** The Lottie document, a path inside the pack. */
    val file: String = "",
    /** The builder's source for it, when the builder made it; editing reopens this. */
    val scene: String? = null,
    val kind: DynKind = DynKind.STICKER,
    val match: DynMatch = DynMatch(),
    /** Empty means "read them off the document" (see [TemplateProps]). */
    val slots: List<TextSlot> = emptyList(),
    val photos: List<PhotoSlot> = emptyList(),
    val altText: String = "",
    /** Where the still and the tray picture are taken, as progress 0..1; below 0 means the last frame. */
    val poster: Float = -1f,
    val renderer: RendererHint = RendererHint.AUTO,
    /** Sent as a still even when it moves: for templates whose motion is only an entrance. */
    val stillOnly: Boolean = false,
)

/** A font a pack carries, by the family name its documents ask for. */
@Serializable
data class PackFont(
    val family: String,
    val file: String,
    val style: String = "",
)

/** Where a pack came from, which decides what the user may do with it. */
@Serializable
enum class PackSource {
    /** Shipped in the app: read-only, duplicated into a user pack to edit. */
    @SerialName("builtin")
    BUILTIN,

    /** Made in the builder or imported from a file: the user's own. */
    @SerialName("user")
    USER,

    /** Downloaded from a feed link; replaced whenever the feed is refreshed. */
    @SerialName("feed")
    FEED,

    /** Installed from an addon repository. */
    @SerialName("addon")
    ADDON,
}

/** A pack's manifest, `pack.json`'s `pack` object. */
@Serializable
data class DynPackMeta(
    val id: String,
    val name: String = "",
    val author: String = "",
    val version: String = "",
    val license: String = "",
    val description: String = "",
    val templates: List<TemplateMeta> = emptyList(),
    val fonts: List<PackFont> = emptyList(),
    val source: PackSource = PackSource.USER,
    /** The feed this pack was downloaded from, for [PackSource.FEED]. */
    val feedId: String? = null,
    val sourceUrl: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * A template as the keyboard uses it: its manifest entry with the slots
 * resolved (read off the document when the manifest left them out), and which
 * pack it is in.
 */
data class DynTemplate(
    val packId: String,
    val meta: TemplateMeta,
    val slots: List<TextSlot>,
    val source: PackSource,
    /** Phrases normalised once by [DynText.normalize], for matching. */
    val phrases: List<String>,
) {
    /** Unique across packs; what favourites, the disabled list and the render cache key on. */
    val key: String get() = "$packId/${meta.id}"

    val name: String get() = meta.name.ifBlank { meta.id }

    val kind: DynKind get() = meta.kind

    /** The slot typed text goes into first: the main one, else the first. */
    val mainSlot: TextSlot? get() = slots.firstOrNull { it.role == SlotRole.MAIN } ?: slots.firstOrNull()
}

/**
 * What to put into a template: the text for each slot by slot id, pictures
 * for photo slots by slot id (file paths), and the user's overrides.
 */
data class DynFill(
    val texts: Map<String, String>,
    val photos: Map<String, String> = emptyMap(),
    /** A family name to draw every text layer in instead of the template's own. */
    val fontOverride: String? = null,
    /** Replaces every text fill colour (ARGB), for the fill sheet's colour chips. */
    val textColor: Long? = null,
) {
    /** A stable key for caches: the same fill always gives the same string. */
    val cacheKey: String
        get() = buildString {
            texts.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v).append('\u0001') }
            photos.toSortedMap().forEach { (k, v) -> append(k).append('@').append(v).append('\u0001') }
            fontOverride?.let { append("f=").append(it) }
            textColor?.let { append("c=").append(it) }
        }
}
