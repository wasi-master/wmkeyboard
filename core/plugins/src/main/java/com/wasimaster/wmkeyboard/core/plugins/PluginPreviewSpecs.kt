package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One kind of file a plugin draws in the keyboard's file manager (API 5):
 * the file types it reads, by extension, exact name and MIME type, and the
 * `@plugin.preview(id)` function that draws one. With [thumbnails] the
 * plugin's `@plugin.thumbnail(id)` also draws the grid's picture for them.
 *
 * Declaring any preview needs the `previews` permission, which lets the
 * plugin read the one file the user opens and nothing else.
 */
@Serializable
data class PluginPreviewSpec(
    /** Unique within the plugin: lowercase letters, digits, `-` and `_`. */
    val id: String = "",
    /** What the "View with" chip says. Blank is the plugin's name. */
    val name: String = "",
    /** Lower case, no dot: `docx`, `tar.gz`. */
    val extensions: List<String> = emptyList(),
    /** Whole file names, for files known by name: `Dockerfile`, `.gitignore`. Matched ignoring case. */
    val names: List<String> = emptyList(),
    /** `text/csv`, or a family: `application/vnd.ms-*`, `audio/ *` written without the space. */
    val mimes: List<String> = emptyList(),
    /** The most of a file the call is given at once; `file.read(offset, length)` reaches the rest. */
    val maxBytes: Long = DEFAULT_MAX_BYTES,
    val thumbnails: Boolean = false,
) {
    /** Whether a file called [fileName] of type [mime] is one this preview reads. */
    fun matches(fileName: String, mime: String?): Boolean {
        val lower = fileName.lowercase()
        if (names.any { it.equals(fileName, ignoreCase = true) }) return true
        if (extensions.any { lower.endsWith(".$it") }) return true
        val type = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        if (type.isEmpty() || type == "application/octet-stream") return false
        return mimes.any { pattern -> if (pattern.endsWith("*")) type.startsWith(pattern.dropLast(1)) else type == pattern }
    }

    /** How closely it fits: an exact name over an extension over a type family. 0 for no match. */
    fun fit(fileName: String, mime: String?): Int {
        val lower = fileName.lowercase()
        return when {
            names.any { it.equals(fileName, ignoreCase = true) } -> FIT_NAME
            extensions.any { lower.endsWith(".$it") } -> FIT_EXTENSION + (extensions.filter { lower.endsWith(".$it") }.maxOf { it.length })
            matches(fileName, mime) -> FIT_MIME
            else -> 0
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024
        const val MAX_MAX_BYTES = 16L * 1024 * 1024
        const val MAX_EXTENSIONS = 64
        const val MAX_NAMES = 16
        const val MAX_MIMES = 16
        private const val FIT_NAME = 300
        private const val FIT_EXTENSION = 200
        private const val FIT_MIME = 100
    }
}

/**
 * Syntax colouring a plugin adds for some file types: a declarative grammar
 * the keyboard runs ([PluginSyntax]), never Python, so a 256 KB file colours
 * as fast as one the keyboard knows itself. Needs no permission: the plugin
 * never sees the text.
 *
 * [grammar] is the grammar itself, or `"assets/<name>.json"` in the archive,
 * which the store reads in when the plugin is installed.
 */
@Serializable
data class PluginSyntaxSpec(
    val id: String = "",
    val name: String = "",
    val extensions: List<String> = emptyList(),
    val names: List<String> = emptyList(),
    val grammar: JsonElement = JsonNull,
) {
    /** The asset the grammar is in, or null when it is written inline. */
    val grammarAsset: String?
        get() = (grammar as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.startsWith(PluginFile.ASSETS_PREFIX) }
            ?.removePrefix(PluginFile.ASSETS_PREFIX)

    fun matches(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return names.any { it.equals(fileName, ignoreCase = true) } || extensions.any { lower.endsWith(".$it") } ||
            extensions.any { it == lower }
    }

    /** Whether [language] (a code block's language, a file extension) is one of [extensions] or [id]. */
    fun answers(language: String): Boolean {
        val l = language.lowercase().removePrefix(".")
        return l == id || l in extensions
    }

    companion object {
        const val MAX_EXTENSIONS = 32
        const val MAX_NAMES = 16
    }
}

/** One file preview, with the plugin that offers it. */
data class PluginPreviewRef(val plugin: InstalledPlugin, val preview: PluginPreviewSpec) {
    /** `plugin:<pluginId>/<previewId>`: what a remembered "View with" choice keys on. */
    val key: String
        get() = keyOf(plugin.id, preview.id)

    /** What the chip says: the preview's own name, else the plugin's. */
    val name: String
        get() = preview.name.ifBlank { plugin.name }

    companion object {
        fun keyOf(pluginId: String, previewId: String): String = PluginActionRef.keyOf(pluginId, previewId)

        fun parse(key: String): Pair<String, String>? = PluginActionRef.parse(key)
    }
}

/** One grammar, with the plugin that offers it. */
data class PluginSyntaxRef(val plugin: InstalledPlugin, val syntax: PluginSyntaxSpec) {
    val key: String
        get() = "${plugin.id}@${plugin.version}/${syntax.id}"
}

/** The manifest rules for `previews` and `syntax`, in [PluginManifestCodec]'s terms. */
internal object PluginPreviewRules {

    const val MAX_PREVIEWS = 16
    const val MAX_SYNTAX = 16
    const val MAX_NAME = 24

    private val ID = Regex("^[a-z0-9_-]{1,32}$")
    private val EXTENSION = Regex("^[a-z0-9][a-z0-9._+-]{0,15}$")
    private val FILE_NAME = Regex("^[A-Za-z0-9._+-]{1,40}$")
    private val MIME = Regex("^[a-z0-9][a-z0-9.+-]*/([a-z0-9][a-z0-9.+_-]*\\*?|\\*)$")

    fun sanitisePreview(p: PluginPreviewSpec): PluginPreviewSpec = p.copy(
        id = p.id.trim(),
        name = PluginManifestCodec.displayText(p.name, MAX_NAME),
        extensions = p.extensions.map { it.trim().lowercase().removePrefix(".") }.distinct(),
        names = p.names.map { it.trim() }.distinct(),
        mimes = p.mimes.map { it.trim().lowercase() }.distinct(),
        maxBytes = p.maxBytes.coerceIn(1, PluginPreviewSpec.MAX_MAX_BYTES),
    )

    fun sanitiseSyntax(s: PluginSyntaxSpec): PluginSyntaxSpec = s.copy(
        id = s.id.trim(),
        name = PluginManifestCodec.displayText(s.name, MAX_NAME),
        extensions = s.extensions.map { it.trim().lowercase().removePrefix(".") }.distinct(),
        names = s.names.map { it.trim() }.distinct(),
    )

    /** Whether the previews are well formed: ids, at least one file type each, the caps. */
    fun previewsOk(previews: List<PluginPreviewSpec>): Boolean {
        val clean = previews.map(::sanitisePreview)
        return clean.size <= MAX_PREVIEWS &&
            clean.map { it.id }.toSet().size == clean.size &&
            clean.all { p ->
                ID.matches(p.id) &&
                    (p.extensions.isNotEmpty() || p.names.isNotEmpty() || p.mimes.isNotEmpty()) &&
                    p.extensions.size <= PluginPreviewSpec.MAX_EXTENSIONS && p.extensions.all(EXTENSION::matches) &&
                    p.names.size <= PluginPreviewSpec.MAX_NAMES && p.names.all(FILE_NAME::matches) &&
                    p.mimes.size <= PluginPreviewSpec.MAX_MIMES && p.mimes.all(MIME::matches)
            }
    }

    /** Whether the grammars are well formed; an inline grammar's rules are checked too. */
    fun syntaxOk(syntax: List<PluginSyntaxSpec>): Boolean {
        val clean = syntax.map(::sanitiseSyntax)
        return clean.size <= MAX_SYNTAX &&
            clean.map { it.id }.toSet().size == clean.size &&
            clean.all { s ->
                ID.matches(s.id) &&
                    (s.extensions.isNotEmpty() || s.names.isNotEmpty()) &&
                    s.extensions.size <= PluginSyntaxSpec.MAX_EXTENSIONS && s.extensions.all(EXTENSION::matches) &&
                    s.names.size <= PluginSyntaxSpec.MAX_NAMES && s.names.all(FILE_NAME::matches) &&
                    when (val g = s.grammar) {
                        is JsonObject -> PluginSyntax.problem(g) == null
                        else -> s.grammarAsset?.let(PluginFile::isAssetName) == true && s.grammarAsset!!.endsWith(".json")
                    }
            }
    }
}
