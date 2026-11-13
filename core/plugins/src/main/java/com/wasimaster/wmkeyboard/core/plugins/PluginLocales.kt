package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * A plugin's translations (API 5): `locales/<tag>.json` in its archive, one
 * file a language.
 *
 * Each file maps the English text the plugin wrote to the same text in that
 * language, so the script keeps writing English and wraps it, gettext style:
 * `_("Look up")`. A value is a string, or `{"one": …, "other": …}` for
 * `ngettext`. Keys that start with `@` translate what the app itself reads
 * from the manifest, before any script runs: `@name`, `@description`,
 * `@action.<id>`, `@setting.<key>` and `@option.<key>.<value>`.
 *
 * Text only: a translation is shown as written, never run, and a file that is
 * anything but an object of strings and plural objects refuses the install.
 */
object PluginLocales {
    const val PREFIX = "locales/"
    const val SUFFIX = ".json"

    /** One language's file. Far more than a tool's worth of strings. */
    const val MAX_BYTES = 64 * 1024

    /** Stored beside the script, one file a tag. */
    const val DIR = "locales"

    /** `bn`, `pt-BR`, `es-419`, `zh-Hant`. */
    private val TAG = Regex("^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$")

    private val json = Json { ignoreUnknownKeys = true }

    /** The language tag an archive entry is the translation for, or null when it is not one. */
    fun tagOf(entry: String): String? {
        if (!entry.startsWith(PREFIX) || !entry.endsWith(SUFFIX)) return null
        val tag = entry.removePrefix(PREFIX).removeSuffix(SUFFIX)
        return tag.takeIf { TAG.matches(it) }
    }

    fun isTag(tag: String): Boolean = TAG.matches(tag)

    /**
     * [text] as a translation file, or null when it is not one: a JSON object
     * whose values are strings, or objects of plural forms whose values are
     * strings.
     */
    fun parse(text: String): JsonObject? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        for ((_, value) in root) {
            val ok = when (value) {
                is JsonPrimitive -> value.isString
                is JsonObject -> value.isNotEmpty() && value.keys.all { it in PLURAL_FORMS } &&
                    value.values.all { it is JsonPrimitive && it.isString }
                else -> false
            }
            if (!ok) return null
        }
        return root
    }

    /** CLDR's plural categories; `ngettext` reads `one` and `other`, the rest are kept for later. */
    private val PLURAL_FORMS = setOf("zero", "one", "two", "few", "many", "other")

    /** The `@` keys of [file] with string values: what the app shows from the manifest. */
    fun labels(file: JsonObject): Map<String, String> =
        file.filterKeys { it.startsWith("@") }
            .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
            .toMap()

    /**
     * The tag in [available] that best fits [preferred], in order: the whole
     * tag (`pt-BR`), then the language (`pt`), then any tag of that language.
     * Null when none fits, and the plugin's English stands.
     */
    fun pick(available: Collection<String>, preferred: List<Locale>): String? {
        if (available.isEmpty()) return null
        for (locale in preferred) {
            val full = locale.toLanguageTag()
            available.firstOrNull { it.equals(full, ignoreCase = true) }?.let { return it }
            val language = locale.language
            available.firstOrNull { it.equals(language, ignoreCase = true) }?.let { return it }
            available.firstOrNull { it.substringBefore('-').equals(language, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    /** The languages the user reads, in order: the app's own choice first, as Android reports it. */
    fun preferred(): List<Locale> {
        val list = android.os.LocaleList.getAdjustedDefault()
        return (0 until list.size()).map { list[it] }
    }
}

/**
 * [english], or the plugin's translation of [key] for the user's language.
 * [key] is one of the `@` keys [PluginLocales] describes.
 */
fun InstalledPlugin.label(key: String, english: String, preferred: List<Locale> = PluginLocales.preferred()): String {
    val tag = PluginLocales.pick(labels.keys, preferred) ?: return english
    return labels[tag]?.get(key)?.takeIf { it.isNotBlank() } ?: english
}

/** The plugin's name in the user's language. */
val InstalledPlugin.displayName: String
    get() = label("@name", name)

/** The plugin's description in the user's language. */
val InstalledPlugin.displayDescription: String
    get() = if (description.isBlank()) description else label("@description", description)
