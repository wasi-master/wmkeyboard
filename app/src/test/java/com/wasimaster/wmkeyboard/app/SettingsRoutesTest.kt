package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A settings route is a plain string read by nine independent tables — the
 * nav graph, the search index, the route icons and colours, the fingerprint
 * lock, the launcher shortcuts, the add-on store, the storage screen, and
 * hard-coded literals in the keyboard itself — and until this test nothing
 * checked that the string one table names is a screen another table draws.
 * A route that drifts fails only under a user's finger: a search result that
 * throws, a Storage "Manage" button that opens nothing.
 *
 * Read off the sources, so it stays a plain JVM test: the NavHost is
 * `composable("…")` calls in `MainActivity.kt`, and a route is either a
 * literal there or a `const val` it resolves to.
 */
class SettingsRoutesTest {

    private val appDir = File("src/main/java/com/wasimaster/wmkeyboard/app")

    private val appSources: List<File> by lazy {
        appDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val mainActivity: String by lazy { File(appDir, "MainActivity.kt").readText() }

    /**
     * `const val X_ROUTE = "…"` anywhere in the module, plus each object's
     * own `ROUTE` under the object's name, so that `MusicApps.ROUTE` and
     * `AppLockTargets.ROUTE` both resolve.
     */
    private val constants: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        val const = Regex("""const val ([A-Za-z_]+) = "([^"]+)"""")
        val holder = Regex("""object ([A-Za-z_]+)""")
        for (file in appSources) {
            val text = file.readText()
            val owners = holder.findAll(text).map { it.groupValues[1] }.toList()
            for (m in const.findAll(text)) {
                val (name, value) = m.destructured
                if (name != "ROUTE") map[name] = value
                for (owner in owners) map["$owner.$name"] = value
            }
        }
        map
    }

    /**
     * Every destination the NavHost declares, as a matcher: `{arg}` matches
     * one path segment, and a trailing `?query` is dropped, because a route
     * is addressed by its path alone.
     */
    private val destinations: List<Pair<String, Regex>> by lazy {
        val call = Regex("""composable\(\s*(?:route\s*=\s*)?("[^"]+"|[A-Za-z_.]+)""")
        val found = call.findAll(mainActivity).map { it.groupValues[1] }.map { raw ->
            val text = if (raw.startsWith("\"")) {
                raw.trim('"').replace(Regex("""\$\{?([A-Za-z_.]+)}?""")) { m ->
                    constants[m.groupValues[1]] ?: error("unknown route constant ${m.groupValues[1]}")
                }
            } else {
                constants[raw] ?: error("unknown route constant $raw")
            }
            text.substringBefore('?')
        }.toList()
        assertTrue("fewer NavHost destinations than expected: $found", found.size > 50)
        found.map { pattern ->
            pattern to Regex(
                pattern.split('/').joinToString("/") { seg ->
                    if (seg.startsWith("{")) "[^/]+" else Regex.escape(seg)
                },
            )
        }
    }

    private fun isDestination(route: String) = destinations.any { it.second.matches(route) }

    /** The base routes the search index names, and the tool routes it derives. */
    private val indexRoutes: List<String> by lazy {
        val source = File(appDir, "SettingsSearch.kt").readText()
        val literal = Regex("\"([a-z][a-z0-9_]*(?:/[a-z0-9_{}]+)*)\"")
            .findAll(source).map { it.groupValues[1] }.distinct().toList()
        assertTrue("no routes found in the index", literal.size > 20)
        literal + ToolbarTool.entries.map { "tool/${it.name}" }
    }

    @Test
    fun `every route the search index names is a real destination`() {
        val missing = indexRoutes.filterNot(::isDestination)
        assertEquals("index routes with no NavHost destination", emptyList<String>(), missing)
    }

    @Test
    fun `every index route has an icon and a colour`() {
        // A tool page borrows its tool's own glyph and accent; every other
        // destination the index can open needs an entry in both tables, or
        // the result draws a generic glyph in the fallback hue with no error.
        val plain = indexRoutes.filterNot { it.startsWith("tool/") }
        assertEquals(
            "index routes with no icon in SettingsRouteIcons",
            emptyList<String>(),
            plain.filterNot { it in SettingsRouteIcons },
        )
        assertEquals(
            "index routes with no colour in SettingsRouteColors",
            emptyList<String>(),
            plain.filterNot { it in SettingsRouteColors },
        )
    }

    @Test
    fun `every icon and colour is for a real destination`() {
        // The reverse: a table entry for a screen that no longer exists is
        // dead weight that hides the fact that a route was renamed.
        val tables = SettingsRouteColors.keys + iconRoutes()
        val stale = tables.distinct().filterNot(::isDestination)
        assertEquals("route table entries with no destination", emptyList<String>(), stale)
    }

    @Test
    fun `every storage category manage button opens a real screen`() {
        val source = File(appDir, "storage/StorageCategories.kt").readText()
        val routes = Regex("""manageRoute = "([^"]+)"""").findAll(source).map { it.groupValues[1] }.toList()
        assertTrue("no manageRoute literals found", routes.size > 10)
        assertEquals("storage manage routes with no destination", emptyList<String>(), routes.filterNot(::isDestination))
    }

    @Test
    fun `every route the keyboard opens by name is a real destination`() {
        // The IME lives in another module and asks for settings screens by
        // bare string over EXTRA_OPEN_ROUTE, which the activity does not
        // validate. A grep of this module never finds those callers.
        val ime = File("../feature/ime/src/main/java")
        if (!ime.isDirectory) return
        val onOpen = Regex("""onOpenRoute\("([a-z][a-z0-9_/]*)"\)""")
        val routeConst = Regex("""[A-Z_]*ROUTE = "([a-z][a-z0-9_/]*)"""")
        val named = ime.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                onOpen.findAll(text).map { it.groupValues[1] } + routeConst.findAll(text).map { it.groupValues[1] }
            }
            .distinct().toList()
        assertTrue("no route literals found in the keyboard module", named.isNotEmpty())
        assertEquals("keyboard-opened routes with no destination", emptyList<String>(), named.filterNot(::isDestination))
    }

    /** Keys of [SettingsRouteIcons], which exposes `contains` but not its key set. */
    private fun iconRoutes(): List<String> {
        val source = File(appDir, "SettingsSearchScreen.kt").readText()
        val table = source.substringAfter("object SettingsRouteIcons").substringBefore("operator fun")
        return Regex("\"([a-z][a-z0-9_/]*)\" to").findAll(table).map { it.groupValues[1] }.toList()
    }
}
