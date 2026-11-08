package com.wasimaster.wmkeyboard.core.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * This key's style with one edit made to another key's style carried over:
 * every field that differs between [before] and [after] takes its value from
 * [after], and every other field stays this key's own.
 *
 * The theme editor styles several keys from one sheet (a whole number row, say)
 * by editing the first and handing each change to the rest this way, so a key
 * that already had a border of its own keeps it when only the fill is changed
 * for all of them. Read field by field through the serialized form, so a field
 * [KeyOverride] gains later is carried without anyone listing it here.
 */
fun KeyOverride.withChangesOf(before: KeyOverride, after: KeyOverride): KeyOverride {
    if (before == after) return this
    val old = fields(before)
    val new = fields(after)
    val mine = fields(this).toMutableMap()
    for (name in old.keys + new.keys) {
        if (old[name] == new[name]) continue
        val value = new[name]
        if (value == null) mine.remove(name) else mine[name] = value
    }
    return mergeJson.decodeFromJsonElement(KeyOverride.serializer(), JsonObject(mine))
}

private fun fields(override: KeyOverride): Map<String, kotlinx.serialization.json.JsonElement> =
    mergeJson.encodeToJsonElement(KeyOverride.serializer(), override).jsonObject

private val mergeJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
