package com.wasimaster.wmkeyboard.app

import android.content.Context
import com.wasimaster.wmkeyboard.app.updates.AppVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Which release first had each settings screen and row, so a link copied from
 * a row says when that row arrived rather than which version the sender runs.
 *
 * The table is the documentation site's `docs/src/data/settings-since.json`,
 * written from git tags by `docs/scripts/extract-settings-since.mjs` and
 * bundled as the `settings-since.json` asset at build time. [versionFor] reads
 * it the way the site's `sinceOf` does, so the app and the /open/ page agree.
 */
internal class SettingsSinceTable(
    /** The first release that opens settings links at all; nothing is dated before it. */
    val floor: String,
    private val routes: Map<String, String>,
    private val settings: Map<String, String>,
    private val modes: Map<String, String>,
    /** The first release that finds a row on one keyboard mode's editor. */
    private val modeRows: String,
) {
    /**
     * The `since=` for the row [name] on [route], or `""` when the link needs
     * no version: everything it opens is as old as [floor].
     *
     * [own] is the version making the copy. It is the answer for a screen,
     * row or mode the table does not list, which is newer than the table, and
     * a cap on every other answer: the row is on the sender's screen, so it is
     * not newer than the sender. A table dates a row still on main to the
     * next release, which a build of that release already is.
     */
    fun versionFor(route: String?, name: String, own: String): String {
        val pattern = route?.let { SettingsRoutes.patternOf(it) }
        val mode = if (pattern == MODE_EDIT) route?.split('/')?.getOrNull(1) else null
        val known = (pattern == null || pattern in routes) && name in settings && (mode == null || mode in modes)
        if (!known) return own
        val dated = listOfNotNull(
            pattern?.let(routes::get),
            settings[name],
            mode?.let(modes::get),
            modeRows.takeIf { mode != null },
        ).fold(floor) { a, b -> if (AppVersion.compare(b, a) > 0) b else a }
        val capped = if (own.matches(PLAIN) && AppVersion.compare(dated, own) > 0) own else dated
        return if (AppVersion.compare(capped, floor) > 0) capped else ""
    }

    companion object {
        private const val MODE_EDIT = "mode_edit/{modeId}"
        private val PLAIN = Regex("[0-9]+(\\.[0-9]+)*")

        /** The table in [json], or null when it is not one. */
        fun parse(json: String): SettingsSinceTable? = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            fun text(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun map(key: String): Map<String, String> = (root[key] as? JsonObject).orEmpty()
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }
                .toMap()
            val floor = text("floor") ?: return null
            SettingsSinceTable(floor, map("routes"), map("settings"), map("modes"), text("modeRows") ?: floor)
        }.getOrNull()
    }
}

/** The bundled [SettingsSinceTable], read once, the first time a link is copied. */
internal object SettingsSince {
    private const val ASSET = "settings-since.json"

    @Volatile private var table: SettingsSinceTable? = null

    @Volatile private var tried = false

    fun table(context: Context): SettingsSinceTable? {
        if (tried) return table
        synchronized(this) {
            if (!tried) {
                table = runCatching {
                    context.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                }.getOrNull()?.let(SettingsSinceTable::parse)
                tried = true
            }
        }
        return table
    }

    /**
     * The `since=` a link copied on [own] gives the row [name] on [route]:
     * the table's date, or [own] itself where the build carries no table.
     */
    fun versionFor(context: Context, route: String?, name: String, own: String): String =
        table(context)?.versionFor(route, name, own) ?: own
}
