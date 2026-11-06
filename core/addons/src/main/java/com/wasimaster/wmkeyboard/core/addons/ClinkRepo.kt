package com.wasimaster.wmkeyboard.core.addons

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reading a Clink repository as an addon repository.
 *
 * Clink is an iOS keyboard whose user repositories are GitHub repositories
 * (`owner/repository`, added under *General → Repositories* there). Each one
 * publishes a `manifest.json` to its `latest` release, listing release assets
 * with their SHA-256 and byte count. The kind of repository is the manifest's
 * list key:
 *
 * | Clink list | Payload | Here |
 * |---|---|---|
 * | `themes` | `.clinktheme`, JSON | [AddonType.Theme], converted by `ClinkTheme` |
 * | `fonts` | `.ttf` / `.otf` | [AddonType.Font], as it is |
 * | `sounds` | `.clinkpack`, JSON with base64 recordings | [AddonType.SoundPack], converted by `ClinkSoundPack` |
 * | `layouts` | `.clinklayout`, JSON letter rows | [AddonType.Layout], converted by `ClinkLayouts` |
 * | `packs` (language packs) | `<code>.clex` and `<code>.emoji.json` among others | [AddonType.Dictionary] and [AddonType.EmojiKeywords] |
 * | `actions` | `.clinkext`, JSON with a Python `transform(text)` | [AddonType.Plugin], converted on install by `ClinkAction` |
 * | `profiles` | Clink's own animation and timing settings | not offered |
 * | `panels` | Python source drawing a panel through Clink's own UI calls | not offered |
 *
 * A language pack's other files are Clink's prediction models (`.cngm`, a
 * Core ML `.mlmodelc`, the vocabularies they read) and its input-method
 * tables, which only Clink can run, so only the word list and the emoji names
 * are offered. Profiles and panels have nothing here to land in: a panel
 * calls Clink's own drawing functions, and the settings are not Clink's.
 * An action is a plain text-in, text-out function, which the plugin
 * system's Python port runs as a selection action. Being code, it lands as a
 * plugin and so needs the plugins switch on and the SHA-256 Clink publishes.
 *
 * The converted manifest is cached in this app's own format (see [encode]), so
 * everything after the fetch, from the catalogue to the install records, reads
 * it like any other repository.
 *
 * ### Versions
 *
 * Every entry in a Clink manifest carries the same `version`, the hash of the
 * whole release, so it changes whenever any file does. Each entry's own file
 * hash is the honest version of that entry instead, written with
 * [Semver.CONTENT_PREFIX] so an update is offered when the file changes.
 */
object ClinkRepo {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val writer = Json { encodeDefaults = false }

    /** A Clink repository on GitHub, and where its two catalogue files are. */
    data class Location(val owner: String, val repo: String, val ref: String = "HEAD") {
        /** The `manifest.json` the release workflow publishes. */
        val releaseManifestUrl: String
            get() = "https://github.com/$owner/$repo/releases/latest/download/manifest.json"

        /** The theme gallery's index of preview pictures, which the theme template keeps. */
        val previewIndexUrl: String
            get() = "$rawBase/README-assets/preview-manifest.json"

        val homepage: String get() = "https://github.com/$owner/$repo"

        internal val rawBase: String get() = "https://raw.githubusercontent.com/$owner/$repo/$ref"
    }

    /**
     * Where to look for a Clink catalogue, for a manifest URL that a GitHub
     * repository address resolved to (see [AddonRepoCodec.resolveManifestUrl]).
     * Null for anything that is not a GitHub repository's raw
     * `wmkeyboard-repo.json`.
     */
    fun locate(manifestUrl: String): Location? {
        val uri = runCatching { URI(manifestUrl) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals("raw.githubusercontent.com", ignoreCase = true)) return null
        val parts = uri.path.orEmpty().trim('/').split('/')
        if (parts.size != 4 || parts[3] != AddonRepoCodec.MANIFEST_NAME) return null
        if (parts.take(3).any { it.isEmpty() }) return null
        return Location(owner = parts[0], repo = parts[1], ref = parts[2])
    }

    /** Whether [text] is a Clink catalogue: an object with a `version` and one of the known lists. */
    fun looksLikeManifest(text: String): Boolean {
        val root = parse(text) ?: return false
        return root["version"] is JsonPrimitive && KINDS.any { root[it] is JsonArray }
    }

    /**
     * The theme ids a preview-picture index lists. The theme template's
     * gallery tool writes one PNG per theme, named by the theme's own `id`.
     */
    fun previewIds(text: String?): Set<String> =
        text?.let(::parse)?.keys.orEmpty()

    /**
     * [text], a Clink `manifest.json`, as this app's repository manifest, or
     * null when it is not one. Entries for iOS alone are left out, and so are
     * the kinds nothing here can install.
     */
    fun convert(text: String, location: Location, previewIds: Set<String> = emptySet()): AddonRepoManifest? {
        val root = parse(text) ?: return null
        if (!looksLikeManifest(text)) return null
        val addons = ArrayList<AddonEntry>()
        val skipped = ArrayList<String>()
        for (kind in KINDS) {
            val list = root[kind] as? JsonArray ?: continue
            val items = list.mapNotNull { it as? JsonObject }.filter { it.string("platform") != PLATFORM_IOS }
            when (kind) {
                KIND_THEMES -> items.mapNotNullTo(addons) { theme(it, location, previewIds) }
                KIND_FONTS -> items.mapNotNullTo(addons) { font(it) }
                KIND_SOUNDS -> items.mapNotNullTo(addons) { single(it, AddonType.SoundPack) }
                KIND_LAYOUTS -> items.mapNotNullTo(addons) { single(it, AddonType.Layout) }
                KIND_LANGUAGE_PACKS -> items.flatMapTo(addons) { languagePack(it) }
                KIND_ACTIONS -> items.mapNotNullTo(addons) { action(it) }
                else -> if (items.isNotEmpty()) skipped += kind
            }
        }
        return AddonRepoManifest(
            format = AddonRepoCodec.FORMAT,
            repo = AddonRepoInfo(
                id = "clink.${location.owner}.${location.repo}".lowercase(),
                name = location.repo,
                description = describe(skipped),
                author = location.owner,
                homepage = location.homepage,
            ),
            addons = addons,
        )
    }

    /** [manifest] as the text [AddonRepoCodec.decode] reads back, for the repository cache. */
    fun encode(manifest: AddonRepoManifest): String = writer.encodeToString(AddonRepoManifest.serializer(), manifest)

    // ---- entries ----------------------------------------------------------

    private fun theme(item: JsonObject, location: Location, previewIds: Set<String>): AddonEntry? {
        val entry = single(item, AddonType.Theme) ?: return null
        val preview = item["preview"] as? JsonObject
        // The picture is named by the id inside the file, which is not always
        // the file's name: Nebula's file is nebula, its id repo-nebula.
        val previewId = preview?.string("id") ?: entry.id
        val material = preview?.string("material")?.takeIf { it.isNotBlank() }
        return entry.copy(
            tags = listOfNotNull(material, if (preview.isDark()) "dark" else "light"),
            previews = if (previewId in previewIds) {
                listOf("${location.rawBase}/README-assets/previews/$previewId.png")
            } else {
                emptyList()
            },
        )
    }

    /**
     * An action, as a plugin entry. The id gains the prefix the converted
     * plugin's id has, so the installed record and the catalogue entry share
     * a name. A Clink entry without a checksum is still listed, and refused at
     * download like any plugin without one.
     */
    private fun action(item: JsonObject): AddonEntry? =
        single(item, AddonType.Plugin)?.let { it.copy(id = "clink." + it.id) }

    private fun font(item: JsonObject): AddonEntry? =
        single(item, AddonType.Font)?.copy(
            license = item.string("license"),
            description = item.string("attribution").orEmpty(),
        )

    /** One entry whose payload is the item's single `asset`. */
    private fun single(item: JsonObject, type: AddonType): AddonEntry? {
        val id = item.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val asset = item["asset"] as? JsonObject ?: return null
        return entry(
            id = id,
            type = type,
            name = item.string("name") ?: id,
            description = item.string("summary").orEmpty(),
            asset = asset,
        )
    }

    /**
     * A language pack as up to two entries, its word list and its emoji names,
     * each in the language this app knows by the nearest id: Clink writes a
     * Latin-script variant `_latn` where this app writes `_rom`, and folds a
     * regional variant (`pt_br`, `es_mx`) into its language.
     */
    private fun languagePack(item: JsonObject): List<AddonEntry> {
        val code = item.string("code")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val assets = (item["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val langId = languageId(code)
        val language = languageName(code)
        fun find(suffix: String) = assets.firstOrNull { it.string("path") == "$code$suffix" }
        return listOfNotNull(
            find(".clex")?.let {
                entry("$code-words", AddonType.Dictionary, language, "", it)?.copy(langId = langId)
            },
            find(".emoji.json")?.let {
                entry("$code-emoji", AddonType.EmojiKeywords, language, "", it)?.copy(langId = langId)
            },
        )
    }

    private fun entry(id: String, type: AddonType, name: String, description: String, asset: JsonObject): AddonEntry? {
        val url = asset.string("url")?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: return null
        val sha = asset.string("sha256")?.lowercase()?.takeIf { SHA256.matches(it) }
        return AddonEntry(
            id = id,
            type = type,
            name = name,
            version = sha?.let { Semver.CONTENT_PREFIX + it.take(SHORT_HASH) } ?: "",
            description = description,
            path = url,
            sha256 = sha,
            sizeBytes = (asset["byteCount"] as? JsonPrimitive)?.longOrNull,
        )
    }

    /** `hi_latn` → `hi_rom`, `pt_br` → `pt`; a plain code stays as it is. */
    fun languageId(code: String): String {
        val base = code.substringBefore('_')
        val variant = code.substringAfter('_', missingDelimiterValue = "")
        return when {
            variant.isEmpty() -> code
            variant == "latn" -> "${base}_rom"
            else -> base
        }
    }

    /** The language's English name, with Clink's variant after it. */
    private fun languageName(code: String): String {
        val base = code.substringBefore('_')
        val name = Locale.forLanguageTag(base).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() && it != base }
            ?: code
        return when (val variant = code.substringAfter('_', missingDelimiterValue = "")) {
            "" -> name
            "latn" -> "$name (Latin)"
            else -> "$name (${variant.uppercase()})"
        }
    }

    /** One line naming what the repository holds that cannot be installed here, or blank. */
    private fun describe(skipped: List<String>): String {
        if (skipped.isEmpty()) return ""
        val names = skipped.map {
            when (it) {
                KIND_PROFILES -> "profiles"
                else -> "panels"
            }
        }
        return "A Clink repository. Its ${names.joinToString(" and ")} are for Clink only and are not listed here."
    }

    private fun parse(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text.trimStart().removePrefix("﻿")) }.getOrNull() as? JsonObject

    private fun JsonObject?.isDark(): Boolean =
        (this?.get("isDark") as? JsonPrimitive)?.content == "true"

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val KIND_THEMES = "themes"
    private const val KIND_FONTS = "fonts"
    private const val KIND_SOUNDS = "sounds"
    private const val KIND_LAYOUTS = "layouts"
    private const val KIND_LANGUAGE_PACKS = "packs"
    private const val KIND_PROFILES = "profiles"
    private const val KIND_PANELS = "panels"
    private const val KIND_ACTIONS = "actions"
    private val KINDS = listOf(
        KIND_THEMES, KIND_FONTS, KIND_SOUNDS, KIND_LAYOUTS, KIND_LANGUAGE_PACKS,
        KIND_PROFILES, KIND_PANELS, KIND_ACTIONS,
    )

    private const val PLATFORM_IOS = "ios"
    private const val SHORT_HASH = 12
    private val SHA256 = Regex("[0-9a-f]{64}")
}
