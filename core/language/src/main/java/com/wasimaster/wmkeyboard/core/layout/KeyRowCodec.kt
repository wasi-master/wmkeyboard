package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.util.JsonPretty
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * One row of keys on its own, as JSON: the keyboard-wide number row and arrow
 * row the settings hold, which every layout without a row of its own draws
 * (asked for in the Telegram group: set the row once, not in every language).
 *
 * The text is a bare JSON array of keys, in the same format a layout's
 * `numberRow` and `arrowRow` take, so a row can be copied between a layout and
 * the settings without changing a character.
 */
object KeyRowCodec {

    private val serializer = ListSerializer(Key.serializer())

    /** For storage: every field written, like a stored layout. */
    fun encode(row: List<Key>): String = LayoutCodec.json.encodeToString(serializer, row)

    /** For a person: defaults left out, indented the way the layout editor prints. */
    fun encodeForEditing(row: List<Key>): String =
        JsonPretty.print(layoutEditorJson.encodeToJsonElement(serializer, row))

    /**
     * The keys in [text], each through the repair an imported layout's keys
     * get, or null when [text] is blank, is not a list of keys, or nothing in it
     * survives. Keys copied in the layout editor (a [KeyFile] envelope) are
     * accepted too, so a row can be pasted straight in.
     */
    fun decode(text: String?): List<Key>? {
        if (text.isNullOrBlank()) return null
        val element = runCatching {
            LayoutCodec.json.parseToJsonElement(text.firstJsonDocument())
        }.getOrNull() ?: return null
        val keys = if (element is JsonArray) decodeKeys(element) else KeyFile.decode(text)
        val repairs = mutableListOf<LayoutMessage>()
        return keys
            ?.mapNotNull { it.repairKey("", repairs) }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun decodeKeys(element: JsonElement): List<Key>? =
        runCatching { LayoutCodec.json.decodeFromJsonElement(serializer, element) }.getOrNull()
}
