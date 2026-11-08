package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.script.FancyStyle
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One zalgo style on the Fancy Text strip: how tall it stacks its marks
 * ([level], [FancyStyles.ZALGO_LEVELS]) and an optional [name]. Empty names
 * itself after its level ("Zalgo 3"), so the default entries need no
 * translation and an entry's name follows its level until the user gives it
 * one of their own.
 *
 * The user keeps a list of these, so a light and a heavy zalgo (or five) can
 * sit on the strip side by side, each one tap away.
 */
@Serializable
data class ZalgoStyle(
    val id: String,
    val level: Int,
    val name: String = "",
)

/** The style the keyboard types for this entry. */
fun ZalgoStyle.toFancyStyle(): FancyStyle = FancyStyles.zalgo(id, level, name)

/** What an install starts with, and what "Reset" goes back to. */
val DefaultZalgoStyles: List<ZalgoStyle> = FancyStyles.DEFAULT_ZALGO.map { (id, level) -> ZalgoStyle(id, level) }

/** The most zalgo entries kept: ten levels, one each. */
const val MAX_ZALGO_STYLES = 10

private const val MAX_ZALGO_NAME_LENGTH = 40

private val zalgoStyleSerializer = ListSerializer(ZalgoStyle.serializer())

private val zalgoStyleJson = Json { ignoreUnknownKeys = true }

internal fun encodeZalgoStyles(list: List<ZalgoStyle>): String =
    zalgoStyleJson.encodeToString(zalgoStyleSerializer, list)

/**
 * The stored list, cleaned like [cleanZalgoStyles]. Nothing stored is the
 * default pair; a stored empty list is a user who deleted them all, and stays
 * empty. A malformed blob reads as the defaults.
 */
internal fun decodeZalgoStyles(raw: String?): List<ZalgoStyle> {
    if (raw == null) return DefaultZalgoStyles
    return runCatching { zalgoStyleJson.decodeFromString(zalgoStyleSerializer, raw) }
        .map(::cleanZalgoStyles)
        .getOrDefault(DefaultZalgoStyles)
}

/**
 * Entries with an id that is not a zalgo id dropped, levels pulled into
 * range, names trimmed and cut, one entry per id (the first wins), capped at
 * [MAX_ZALGO_STYLES].
 */
internal fun cleanZalgoStyles(list: List<ZalgoStyle>): List<ZalgoStyle> =
    list.asSequence()
        .filter { it.id.startsWith(FancyStyles.ZALGO_PREFIX) }
        .map {
            it.copy(
                level = it.level.coerceIn(FancyStyles.ZALGO_LEVELS),
                name = it.name.trim().take(MAX_ZALGO_NAME_LENGTH),
            )
        }
        .distinctBy { it.id }
        .take(MAX_ZALGO_STYLES)
        .toList()
