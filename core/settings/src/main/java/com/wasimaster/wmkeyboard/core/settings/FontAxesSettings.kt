package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.theme.FontAxesSpec
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The user's variable-font tuning, one [FontAxesSpec] per font id
 * (`installed:<id>`, `google:<Name>`, `default`…), stored as one JSON string.
 *
 * Keyed by font rather than by script or by place on the board because the
 * axes belong to a font: Roboto Flex's `wdth` runs 25..151 and Inter's does
 * not exist, so a value only means something next to the font it was set
 * for. Picking another font and coming back finds the old values waiting.
 */
private val fontAxesSerializer = MapSerializer(String.serializer(), FontAxesSpec.serializer())

private val fontAxesJson = Json { ignoreUnknownKeys = true }

internal fun encodeFontAxes(map: Map<String, FontAxesSpec>): String =
    fontAxesJson.encodeToString(fontAxesSerializer, map.filterValues { !it.isEmpty })

internal fun decodeFontAxes(raw: String): Map<String, FontAxesSpec> =
    runCatching { fontAxesJson.decodeFromString(fontAxesSerializer, raw) }.getOrDefault(emptyMap())

/** The tuning for [fontId], or null when the user has set none for it. */
fun KeyboardSettings.axesFor(fontId: String?): FontAxesSpec? =
    fontId?.let { fontAxes[it] }?.takeIf { !it.isEmpty }
