package com.wasimaster.wmkeyboard.core.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a Clink layout (`.clinklayout`), the format of the iOS keyboard
 * Clink's layout repositories (`anti-ltd/clink-layouts` and its forks):
 *
 * ```json
 * {"id": "custom-clink-flow", "name": "Clink Flow",
 *  "rows": [["w","c","s"], ["h","t","n"], ["f","r","m"]]}
 * ```
 *
 * Only the letters: Clink draws its own shift, delete, number and bottom keys
 * around any layout, the way this app does round a foreign grid. So the rows
 * become the same one-key-per-line grid [ForeignLayouts.fromSimpleText] reads,
 * which puts this app's frame round them and repairs the result.
 */
object ClinkLayouts {

    const val FILE_EXTENSION = "clinklayout"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** True when [text] is shaped like a Clink layout: an object with a `rows` array of string arrays. */
    fun looksLikeClinkLayout(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") && trimmed.contains("\"rows\"") && rowsOf(text) != null
    }

    /** Converts [text], or null when it is not a Clink layout or holds no key. */
    fun read(text: String): ConvertedLayout? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val rows = rowsOf(root) ?: return null
        val name = (root["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.ifEmpty { null } ?: FALLBACK_NAME
        // A blank line between rows and one label a line is the simple text
        // grid. A label that is blank, or holds a space, has no line to be on.
        val grid = rows.joinToString("\n\n") { row ->
            row.filter { it.isNotBlank() && it.none(Char::isWhitespace) }.joinToString("\n")
        }
        val converted = ForeignLayouts.fromSimpleText(grid, FILE_NAME) ?: return null
        // The name the file gives, rather than one made from a file name.
        return converted.copy(layout = converted.layout.copy(name = name))
    }

    private fun rowsOf(text: String): List<List<String>>? =
        (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.let(::rowsOf)

    private fun rowsOf(root: JsonObject): List<List<String>>? {
        val rows = root["rows"] as? JsonArray ?: return null
        val out = rows.map { row ->
            (row as? JsonArray ?: return null).map { key ->
                (key as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            }
        }
        return out.takeIf { it.any { row -> row.isNotEmpty() } }
    }

    private const val FALLBACK_NAME = "Clink layout"
    private const val FILE_NAME = "clink.clinklayout"
}
