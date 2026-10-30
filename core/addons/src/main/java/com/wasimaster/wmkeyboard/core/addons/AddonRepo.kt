package com.wasimaster.wmkeyboard.core.addons

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.addons.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The `wmkeyboard-repo.json` manifest: an index over addon files served from a
 * URL the user pasted in.
 *
 * A repository is deliberately *only* an index. Every payload it points at is
 * already one of the app's own import formats — a `.wmtheme.json` is the theme
 * export, a `.wmicons` is the icon-pack export — so installing an addon is
 * "download the file and hand it to the importer that already reads it". There
 * is no packaging step for a publisher and no new parser here.
 *
 * The spec these types mirror is `docs/addons/REPO_FORMAT.md`.
 *
 * Every field past the required ones has a default, and decoding is tolerant
 * (see [AddonRepoCodec]), so a manifest written against a later version of the
 * format still loads on an older build instead of failing wholesale.
 */
@Serializable
data class AddonRepoManifest(
    /** Magic tag; anything but [AddonRepoCodec.FORMAT] is rejected outright. */
    val format: String = "",
    val version: Int = 1,
    val repo: AddonRepoInfo = AddonRepoInfo(),
    val addons: List<AddonEntry> = emptyList(),
)

@Serializable
data class AddonRepoInfo(
    /** Stable id, reverse-DNS by convention. Namespaces the installed addons. */
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
    /** Relative to the manifest, or an absolute https URL. */
    val icon: String? = null,
    /** ISO-8601 date the publisher last touched the manifest. */
    val updatedAt: String = "",
)

@Serializable
data class AddonEntry(
    /** Unique within its repository. Half of the install key; never recycled. */
    val id: String = "",
    val type: AddonType = AddonType.Unknown,
    val name: String = "",
    /** Semver. Bumping it is how a publisher offers an update. */
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    /** Payload location: relative to the manifest, or an absolute https URL. */
    val path: String = "",
    /**
     * Lowercase hex SHA-256 of the payload, **optional**.
     *
     * Verified before install when present. When absent the addon still
     * installs and is simply shown as unverified — requiring it would put a
     * hashing step between a beginner and their first published pack, and the
     * transport is https either way.
     */
    val sha256: String? = null,
    /** Payload size for the UI and a pre-download guard; also optional. */
    val sizeBytes: Long? = null,
    /** Screenshots, relative or absolute. */
    val previews: List<String> = emptyList(),
    /** App `versionCode` floor. Older builds show the addon but can't install it. */
    val minAppVersion: Int? = null,
    /**
     * Required for [AddonType.Dictionary] and [AddonType.EmojiKeywords] —
     * both install into a per-language folder, so an entry that doesn't name
     * its language has nowhere to go. A grouping hint for layouts.
     */
    val langId: String? = null,
    /**
     * Languages this addon is for, when one id isn't enough.
     *
     * Mostly a font thing: plenty of faces carry Latin and nothing else, and a
     * picker that offers them for Bengali is offering something that will draw
     * every key blank. Empty means "no claim" — the addon is offered everywhere,
     * which is the right default for a face with broad coverage.
     */
    val langIds: List<String> = emptyList(),
    /**
     * Licence identifier — SPDX where one fits (`MIT`, `OFL-1.1`, `CC0-1.0`),
     * otherwise any short name. Shown as-is.
     */
    val license: String? = null,
    /** Full licence text inline, for a licence with no identifier worth quoting. */
    val licenseText: String? = null,
    /** Licence text as a file in the repository, relative or absolute; fetched on demand. */
    val licenseFile: String? = null,
    /**
     * Ids of other entries **in this same manifest** the addon depends on — a
     * theme naming the font and sound it was designed around. Soft by design:
     * the install screen offers to download them alongside, and skipping them
     * still installs a working addon (a theme without its font falls back to
     * the global one). Keeping them separate entries is what lets the user
     * repurpose a theme's font anywhere else. An id this manifest doesn't
     * carry is ignored.
     */
    val requires: List<String> = emptyList(),
    /**
     * The same addon in other languages, for the types that install into one
     * language's folder (see [AddonType.perLanguage]).
     *
     * A medical dictionary is one idea with a word list per language, and
     * publishing it as `en-medical`, `es-medical`, `pt-medical`… fills the
     * catalogue with near-identical cards and repeats every field but four. So
     * the entry itself is the base list (its own [path] and [langId]) and each
     * localization adds another, optionally narrowed to a region (`es-419`,
     * `pt-BR`). Each one installs, updates and uninstalls on its own, tracked
     * under [AddonLocalization.key]; an older build ignores the field and sees
     * the base list alone, which is exactly what it offered before.
     */
    val localizations: List<AddonLocalization> = emptyList(),
    /**
     * The addon this one remixes, as its install key `"<repoId>/<addonId>"`.
     * Set by the community repository's bot from what the app sent with the
     * submission; the detail page credits the original. Informational only.
     */
    val basedOn: String? = null,
) {
    /** `"<repoId>/<addonId>"` — how an install is tracked in [AddonStore]. */
    fun key(repoId: String): String = "$repoId/$id"

    /** True when this entry offers its payload in more than one language. */
    val isLocalized: Boolean
        get() = type.perLanguage && localizations.isNotEmpty()

    /**
     * The base list followed by every localization, each as an installable
     * entry of its own. Just `[this]` for an entry without localizations.
     */
    val variants: List<AddonEntry>
        get() = if (isLocalized) listOf(this) + localizations.map(::localized) else listOf(this)

    /**
     * [localization] as a standalone entry: its own payload and language, the
     * rest inherited. The id carries the locale after [AddonLocalization.SEPARATOR]
     * so the install key differs per language and the pipeline that installs
     * a plain dictionary needs no second path.
     */
    fun localized(localization: AddonLocalization): AddonEntry = copy(
        id = "$id${AddonLocalization.SEPARATOR}${localization.locale}",
        version = localization.version?.takeIf { it.isNotBlank() } ?: version,
        name = localization.name?.takeIf { it.isNotBlank() } ?: name,
        description = localization.description?.takeIf { it.isNotBlank() } ?: description,
        path = localization.path,
        sha256 = localization.sha256,
        sizeBytes = localization.sizeBytes,
        langId = localization.langId,
        langIds = emptyList(),
        localizations = emptyList(),
    )

    /** The locale this entry stands for: a localization's tag, or the base [langId]. */
    val locale: String
        get() = id.substringAfter(AddonLocalization.SEPARATOR, missingDelimiterValue = "")
            .ifEmpty { langId.orEmpty() }

    /** [langId] and [langIds] as one list, deduplicated and blank-free. */
    val languages: List<String>
        get() = (listOfNotNull(langId) + langIds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** True when there is a licence worth putting on the detail page. */
    val hasLicense: Boolean
        get() = !license.isNullOrBlank() ||
            !licenseText.isNullOrBlank() ||
            !licenseFile.isNullOrBlank()
}

/**
 * One language of a localized addon; see [AddonEntry.localizations].
 *
 * Carries only what differs between languages. Everything else (type, author,
 * licence, tags, previews) is the parent entry's.
 */
@Serializable
data class AddonLocalization(
    /**
     * A registered language id (§5 of the format), optionally followed by a
     * region after a hyphen: `es`, `es-419`, `pt-BR`. The language part picks
     * the folder the payload installs into; the region only tells two lists
     * for the same language apart.
     */
    val locale: String = "",
    /** Payload location, resolved like [AddonEntry.path]. */
    val path: String = "",
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    /** The addon's name in this language. Falls back to the parent's. */
    val name: String? = null,
    val description: String? = null,
    /** Own semver, for a translation updated on its own schedule. Falls back to the parent's. */
    val version: String? = null,
) {
    /** The registered language id: [locale] up to its region. */
    val langId: String
        get() = locale.substringBefore('-')

    /** Upper-cased region (`419`, `BR`), or blank for a language-wide list. */
    val region: String
        get() = locale.substringAfter('-', missingDelimiterValue = "").uppercase()

    companion object {
        /**
         * Joins a parent id to a locale in a variant's id and install key. Not
         * a character a publisher puts in an id: ids are file-name-like.
         */
        const val SEPARATOR = "@"
    }
}

/**
 * The entry [id] names: a top-level entry, or one localization of one, as
 * [AddonEntry.localized] spells it. Null when neither exists.
 */
fun List<AddonEntry>.findVariant(id: String): AddonEntry? {
    firstOrNull { it.id == id }?.let { return it }
    val parent = firstOrNull { it.id == id.substringBefore(AddonLocalization.SEPARATOR) } ?: return null
    return parent.variants.firstOrNull { it.id == id }
}

/**
 * The addon kinds the format defines.
 *
 * [Unknown] is the forward-compatibility hatch: `type` defaults to it and the
 * decoder coerces invalid values to the default, so a manifest listing a type
 * this build has never heard of loads with that one entry unusable rather than
 * failing the whole repository.
 */
@Serializable
enum class AddonType {
    @SerialName("theme") Theme,
    @SerialName("layout") Layout,
    @SerialName("dictionary") Dictionary,
    @SerialName("emoji_keywords") EmojiKeywords,
    @SerialName("snippets") Snippets,
    @SerialName("espanso") Espanso,
    @SerialName("stickers") Stickers,
    @SerialName("icon_pack") IconPack,
    @SerialName("font") Font,
    @SerialName("emoji_font") EmojiFont,
    @SerialName("sound") Sound,
    @SerialName("sound_pack") SoundPack,
    @SerialName("plugin") Plugin,
    @SerialName("vocabulary") Vocabulary,
    @SerialName("unknown") Unknown,
    ;

    /** Plural heading for the type filter and section headers. */
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            Theme -> R.string.core_addons_type_theme_label
            Layout -> R.string.core_addons_type_layout_label
            Dictionary -> R.string.core_addons_type_dictionary_label
            EmojiKeywords -> R.string.core_addons_type_emoji_keywords_label
            Snippets -> R.string.core_addons_type_snippets_label
            Espanso -> R.string.core_addons_type_espanso_label
            Stickers -> R.string.core_addons_type_stickers_label
            IconPack -> R.string.core_addons_type_icon_pack_label
            Font -> R.string.core_addons_type_font_label
            EmojiFont -> R.string.core_addons_type_emoji_font_label
            Sound -> R.string.core_addons_type_sound_label
            SoundPack -> R.string.core_addons_type_sound_pack_label
            Plugin -> R.string.core_addons_type_plugin_label
            Vocabulary -> R.string.core_addons_type_vocabulary_label
            Unknown -> R.string.core_addons_type_unknown_label
        }

    /**
     * Singular form, for naming one addon.
     *
     * Spelled out rather than derived from [labelRes], because dropping a
     * trailing "s" turns "Dictionaries" into "Dictionarie", and because the
     * two forms diverge outright in other languages.
     */
    @get:StringRes
    val singularLabelRes: Int
        get() = when (this) {
            Theme -> R.string.core_addons_type_theme_singular_label
            Layout -> R.string.core_addons_type_layout_singular_label
            Dictionary -> R.string.core_addons_type_dictionary_singular_label
            EmojiKeywords -> R.string.core_addons_type_emoji_keywords_singular_label
            Snippets -> R.string.core_addons_type_snippets_singular_label
            Espanso -> R.string.core_addons_type_espanso_singular_label
            Stickers -> R.string.core_addons_type_stickers_singular_label
            IconPack -> R.string.core_addons_type_icon_pack_singular_label
            Font -> R.string.core_addons_type_font_singular_label
            EmojiFont -> R.string.core_addons_type_emoji_font_singular_label
            Sound -> R.string.core_addons_type_sound_singular_label
            SoundPack -> R.string.core_addons_type_sound_pack_singular_label
            Plugin -> R.string.core_addons_type_plugin_singular_label
            Vocabulary -> R.string.core_addons_type_vocabulary_singular_label
            Unknown -> R.string.core_addons_type_unknown_singular_label
        }

    /**
     * Largest payload worth downloading, matched to whatever the importer on
     * the other end will actually accept — there is no point streaming 60 MB
     * of dictionary only for the importer to refuse it at 32.
     */
    val maxBytes: Long
        get() = when (this) {
            Layout, Snippets, Espanso -> 4L * 1024 * 1024
            // A theme can carry base64 background images — an animated GIF
            // pair plus key textures runs well past the old 4 MB.
            Theme -> 16L * 1024 * 1024
            Dictionary -> 32L * 1024 * 1024
            // A keyword pack is one row per emoji: a few thousand short
            // lines, even for a language that names every one of them.
            EmojiKeywords -> 8L * 1024 * 1024
            IconPack -> 8L * 1024 * 1024
            Stickers -> 64L * 1024 * 1024
            // A colour emoji font carries thousands of bitmap or COLR glyphs,
            // so it runs several times the size of a text face.
            Font, EmojiFont -> 64L * 1024 * 1024
            Sound -> 4L * 1024 * 1024
            // A pack is many recordings of one keyboard, each of which
            // the importer caps at the same 4 MB a lone sound gets.
            SoundPack -> 16L * 1024 * 1024
            // A plugin is a manifest and a Lua file. Anything approaching this
            // is not a keyboard panel tool.
            Plugin -> 1L * 1024 * 1024
            // A pack is a few thousand dictionary records; the importer caps it at 8 MB too.
            Vocabulary -> 8L * 1024 * 1024
            Unknown -> 0L
        }

    /**
     * Whether the payload can be previewed without installing it.
     *
     * The ones that can are the ones whose content is *the* thing being chosen
     * — the words in a dictionary, the actual sticker images, the sound itself.
     * A theme or a font is judged by looking at the keyboard wearing it, which
     * a preview panel can't honestly reproduce, so those don't offer one.
     *
     * A plugin previews for a different reason than the rest: not so the user
     * can judge the content, but so they can read what the thing is allowed to
     * do before they let any of its code near their keyboard.
     */
    val previewable: Boolean
        get() = when (this) {
            Snippets, Espanso, Dictionary, EmojiKeywords, Sound, SoundPack, Stickers, Plugin, Vocabulary -> true
            else -> false
        }

    /**
     * Whether a payload of this type is executable code.
     *
     * Exactly one type is, and it changes two rules: the checksum stops being
     * optional (see `AddonDownloadManager`), and the detail page shows what the
     * plugin may do before offering to install it.
     */
    val isExecutable: Boolean
        get() = this == Plugin

    /**
     * Whether a payload of this type belongs to exactly one language, which is
     * what makes [AddonEntry.localizations] meaningful for it: each localized
     * payload installs into its own language's folder by the same importer.
     */
    val perLanguage: Boolean
        get() = this == Dictionary || this == EmojiKeywords || this == Vocabulary

    /**
     * The type this one is browsed under.
     *
     * The split between [Snippets] and [Espanso] is a contract with whoever
     * writes a repository manifest: the entry says which format its payload is
     * in, so a mistake is a clear rejection rather than a guess. It is not a
     * second thing for a user to go looking for. An Espanso package *is* a pack
     * of snippets, so the filter chips, the type listing, the routes and the
     * store group all work in categories, and only the entry card and the
     * install dialog name the format.
     */
    val storeCategory: AddonType
        get() = if (this == Espanso) Snippets else this
}
