package com.wasimaster.wmkeyboard.core.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException

/**
 * The plugins that answer for provider lists, as this module sees them.
 *
 * Plugins live in `:core:plugins`, which this module cannot see, so the
 * keyboard installs a [Directory] at start-up the way
 * [ProviderEnvironment.install] hands down what the build can run. Until it
 * does, there are no plugin rows and a call fails.
 *
 * A plugin row is a [ProviderTypes.PLUGIN] entry whose credential id is the
 * provider's key, `plugin:<pluginId>/<tool>/<providerId>`: the row stays in
 * the list when the plugin is switched off or removed, and is simply not
 * asked until it is back.
 */
object PluginProviders {

    /** One provider a plugin offers. */
    data class Row(
        /** `plugin:<pluginId>/<tool>/<providerId>`: the list entry's credential id. */
        val key: String,
        val tool: ProviderTool,
        /** The provider's name, else its plugin's. */
        val name: String,
        val summary: String,
        val pluginName: String,
        /** The plugin is installed, on, and the plugin subsystem is on. */
        val runnable: Boolean,
        /** A search provider that answers the Images tab too. */
        val images: Boolean = false,
    )

    interface Directory {
        /** Every plugin provider, the ones that cannot run now too. */
        fun rows(): List<Row>

        /**
         * Asks the provider [key] with [request] and returns its reply.
         * Blocking; throws an [IOException] whose message is for the user.
         */
        fun call(key: String, request: JsonObject, isActive: () -> Boolean): JsonElement
    }

    @Volatile
    private var directory: Directory? = null

    fun install(directory: Directory) {
        this.directory = directory
    }

    /** The plugin providers for [tool]. */
    fun rows(tool: ProviderTool): List<Row> = directory?.rows().orEmpty().filter { it.tool == tool }

    /** The provider [key] names, or null when no installed plugin offers it. */
    fun row(key: String): Row? = directory?.rows()?.firstOrNull { it.key == key }

    /** See [Directory.call]. */
    fun call(key: String, request: JsonObject, isActive: () -> Boolean = { true }): JsonElement =
        (directory ?: throw IOException("Plugins are not available")).call(key, request, isActive)

    /** A list entry for [key]. */
    fun entry(key: String): ProviderEntry = ProviderEntry(ProviderTypes.PLUGIN.id, credentialId = key)
}

/*
 * Reading a plugin's reply. A plugin is someone else's code, so its JSON is
 * read forgivingly: a field of the wrong type is a missing field, and an
 * item without what makes it an item is left out, never an error.
 */

/** [this] as an object, or null. */
fun JsonElement?.obj(): JsonObject? = this as? JsonObject

/** [this] as a list: an array's items, or the array under [key] of an object. */
fun JsonElement?.items(key: String): List<JsonElement> = when (this) {
    is JsonArray -> this
    is JsonObject -> (this[key] as? JsonArray).orEmpty()
    else -> emptyList()
}

/** The text at [key], trimmed; numbers and booleans as written; "" otherwise. */
fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim().orEmpty()

/** The first of [keys] with text. */
fun JsonObject.text(vararg keys: String): String = keys.firstNotNullOfOrNull { k -> text(k).ifEmpty { null } }.orEmpty()

fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** The strings of the array at [key]: each item's text, blank ones dropped. */
fun JsonObject.texts(key: String): List<String> = (this[key] as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull?.trim()?.ifEmpty { null } }

/** An address a plugin returned, kept only when it is http or https. */
fun webUrl(raw: String): String? {
    val url = raw.trim()
    return url.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
}
