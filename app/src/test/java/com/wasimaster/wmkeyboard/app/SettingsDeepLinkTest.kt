package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A deep link names a screen as a plain string, and the nav graph is the only
 * thing that knows what those strings mean. Nothing but a test connects the
 * two — so these read the `composable("…")` calls out of `MainActivity.kt` and
 * fail the moment the allowlist and the graph disagree in either direction: a
 * route nobody can reach any more, or a screen added without a decision about
 * whether a link may open it.
 */
class SettingsDeepLinkTest {

    private val appDir = File("src/main/java/com/wasimaster/wmkeyboard/app")

    private val navHostSource: String by lazy { File(appDir, "MainActivity.kt").readText() }

    private val shortcutsXml: String by lazy {
        val file = File("src/main/res/xml/shortcuts.xml")
        assertTrue("shortcuts.xml not found at ${file.absolutePath}", file.isFile)
        file.readText()
    }

    private val manifest: String by lazy { File("src/main/AndroidManifest.xml").readText() }

    /**
     * `const val X_ROUTE = "…"` anywhere in the module, plus each object's own
     * `ROUTE` under the object's name, so `MusicApps.ROUTE` and
     * `AppLockTargets.ROUTE` both resolve. Same shape as [SettingsRoutesTest];
     * duplicated rather than shared because the two tests answer different
     * questions off the same source and neither should be able to break the
     * other by refactoring its own reader.
     */
    private val constants: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        val const = Regex("""const val ([A-Za-z_]+) = "([^"]+)"""")
        val holder = Regex("""object ([A-Za-z_]+)""")
        for (file in appDir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
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
     * Every destination the NavHost declares, as its path: constants resolved,
     * and the `?query` half dropped, because a link addresses a screen by its
     * path alone.
     */
    private val destinations: List<String> by lazy {
        val call = Regex("""composable\(\s*(?:route\s*=\s*)?("[^"]+"|[A-Za-z_.]+)""")
        val found = call.findAll(navHostSource).map { it.groupValues[1] }.map { raw ->
            val text = if (raw.startsWith("\"")) {
                raw.trim('"').replace(Regex("""\$\{?([A-Za-z_.]+)}?""")) { m ->
                    constants[m.groupValues[1]] ?: error("unknown route constant ${m.groupValues[1]}")
                }
            } else {
                constants[raw] ?: error("unknown route constant $raw")
            }
            text.substringBefore('?')
        }.distinct().toList()
        assertTrue("fewer NavHost destinations than expected: ${found.size}", found.size > 80)
        found
    }

    // ---- the allowlist and the graph agree ----------------------------------

    @Test
    fun `every destination is either addressable or excluded on purpose`() {
        val decided = SettingsRoutes.all.toSet() + SettingsRoutes.excluded.keys
        val undecided = destinations.filterNot { it in decided }
        assertEquals(
            "NavHost destinations missing from SettingsRoutes — add them to `all`, " +
                "or to `excluded` with the reason a link must not open them",
            emptyList<String>(),
            undecided,
        )
    }

    @Test
    fun `every allowlisted route is a real destination`() {
        val stale = (SettingsRoutes.all + SettingsRoutes.excluded.keys)
            .filterNot { it in destinations }
        assertEquals("routes in SettingsRoutes with no NavHost destination", emptyList<String>(), stale)
    }

    @Test
    fun `every excluded route carries a reason`() {
        val silent = SettingsRoutes.excluded.filterValues { it.isBlank() }.keys
        assertEquals("excluded routes with no reason", emptySet<String>(), silent)
    }

    @Test
    fun `every plain screen resolves from its own link`() {
        val unreachable = SettingsRoutes.plain
            .filter { SettingsDeepLink.parse("wmkeyboard://settings/$it")?.route != it }
        assertEquals("screens their own link does not open", emptyList<String>(), unreachable)
    }

    @Test
    fun `no two patterns claim the same address`() {
        // An `{arg}` accepts any segment, so two argument patterns of the same
        // shape would be one address and the second would be unreachable. The
        // literal-against-argument case is covered above: every plain screen
        // opening its own link is the statement that no `{arg}` swallowed it.
        val filled = SettingsRoutes.all.map { pattern ->
            pattern to pattern.split('/').joinToString("/") { seg ->
                if (seg.startsWith("{")) "wm-test-arg" else seg
            }
        }
        val shadowed = filled.filter { (pattern, address) ->
            SettingsRoutes.resolve(address) != pattern.split('/')
                .joinToString("/") { if (it.startsWith("{")) "wm-test-arg" else it }
        }
        assertEquals("patterns another pattern shadows", emptyList<Pair<String, String>>(), shadowed)
    }

    @Test
    fun `every route the keyboard opens by name is addressable`() {
        // The IME asks for settings screens by bare string over
        // EXTRA_OPEN_ROUTE, and the activity now drops a route the allowlist
        // does not have rather than letting navigate() throw on it. So a route
        // the keyboard sends and this list lacks is no longer a crash: it is a
        // button that silently does nothing, which is worse to find by hand.
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
        assertEquals(
            "routes the keyboard opens that no link can reach",
            emptyList<String>(),
            named.filterNot { it in SettingsRoutes.all },
        )
    }

    // ---- the shortcuts and the manifest ------------------------------------

    @Test
    fun `every link in the shortcut xml resolves`() {
        val links = Regex("""android:data="([^"]+)"""")
            .findAll(shortcutsXml)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("no android:data links found in shortcuts.xml", links.isNotEmpty())
        val unresolved = links.filter { SettingsDeepLink.parse(it) == null }
        assertEquals("shortcut links that resolve to no route", emptyList<String>(), unresolved)
    }

    @Test
    fun `shortcut ids match the route they open`() {
        val shortcuts = Regex(
            """android:shortcutId="([^"]+)"[\s\S]*?android:data="([^"]+)"""",
        ).findAll(shortcutsXml).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(shortcuts.isNotEmpty())
        for ((id, link) in shortcuts) {
            assertEquals("shortcut \"$id\" opens a different route", id, SettingsDeepLink.parse(link)?.route)
        }
    }

    @Test
    fun `the manifest declares both link hosts`() {
        // The parser accepting a host means nothing if no filter delivers it.
        for (host in listOf(SettingsDeepLink.SCREEN_HOST, SettingsDeepLink.SETTING_HOST)) {
            assertTrue(
                "no <data> entry for the \"$host\" host in AndroidManifest.xml",
                manifest.contains("""android:scheme="${SettingsDeepLink.SCHEME}" android:host="$host""""),
            )
        }
    }

    // ---- parsing -----------------------------------------------------------

    @Test
    fun `both uri forms parse`() {
        assertEquals("themes", SettingsDeepLink.parse("wmkeyboard://settings/themes")?.route)
        assertEquals("themes", SettingsDeepLink.parse("wmkeyboard:settings/themes")?.route)
    }

    @Test
    fun `a bare settings link opens the home list`() {
        assertEquals("home", SettingsDeepLink.parse("wmkeyboard://settings")?.route)
        assertEquals("home", SettingsDeepLink.parse("wmkeyboard://settings/")?.route)
    }

    @Test
    fun `sub-pages and arguments parse`() {
        assertEquals(
            "typing/corrections",
            SettingsDeepLink.parse("wmkeyboard://settings/typing/corrections")?.route,
        )
        assertEquals(
            "tool/${ToolbarTool.CLIPBOARD.name}",
            SettingsDeepLink.parse("wmkeyboard://settings/tool/${ToolbarTool.CLIPBOARD.name}")?.route,
        )
        assertEquals(
            "language/en",
            SettingsDeepLink.parse("wmkeyboard://settings/language/en")?.route,
        )
        assertEquals(
            "language/en/more",
            SettingsDeepLink.parse("wmkeyboard://settings/language/en/more")?.route,
        )
    }

    @Test
    fun `a literal segment is matched case-insensitively and an argument is not`() {
        // Screens are addressed by a name the docs write; the user's own ids
        // are addressed exactly as they are stored.
        assertEquals("themes", SettingsDeepLink.parse("wmkeyboard://SETTINGS/Themes")?.route)
        assertEquals("theme_edit/AbC", SettingsDeepLink.parse("wmkeyboard://settings/theme_edit/AbC")?.route)
    }

    @Test
    fun `an argument keeps its encoding`() {
        // navigate() decodes the route it is given, so a segment decoded here
        // would put a bare slash inside a repository URL and split the path.
        assertEquals(
            "addon_repo/https%3A%2F%2Fexample.com%2Frepo.json",
            SettingsDeepLink.parse("wmkeyboard://settings/addon_repo/https%3A%2F%2Fexample.com%2Frepo.json")?.route,
        )
    }

    @Test
    fun `a setting rides along with its screen`() {
        val target = SettingsDeepLink.parse("wmkeyboard://settings/typing/corrections?setting=typing_autocorrect_title")
        assertEquals("typing/corrections", target?.route)
        assertEquals("typing_autocorrect_title", target?.setting)
    }

    @Test
    fun `a setting host names no screen of its own`() {
        val target = SettingsDeepLink.parse("wmkeyboard://setting/typing_autocorrect_title")
        assertNotNull(target)
        assertEquals("", target?.route)
        assertEquals("typing_autocorrect_title", target?.setting)
    }

    @Test
    fun `an unlisted route is refused`() {
        assertNull(SettingsDeepLink.parse("wmkeyboard://settings/nope"))
        assertNull(SettingsDeepLink.parse("wmkeyboard://settings/typing/nope"))
        assertNull(SettingsDeepLink.parse("wmkeyboard://settings/onboarding"))
        assertNull(SettingsDeepLink.parse("wmkeyboard://settings/sticker_editor"))
        assertNull(SettingsDeepLink.parse("wmkeyboard://addons"))
        assertNull(SettingsDeepLink.parse("https://example.com/settings/themes"))
        assertNull(SettingsDeepLink.parse(null as String?))
        assertNull(SettingsDeepLink.parse(""))
    }

    @Test
    fun `a path that tries to climb is refused`() {
        assertNull(SettingsDeepLink.parse("wmkeyboard://settings/typing/../themes"))
        assertNull(SettingsDeepLink.parse("wmkeyboard://settings/typing//corrections"))
    }

    @Test
    fun `a setting name that is not a resource name is dropped`() {
        // The value reaches a lookup by name, so only the shape aapt gives a
        // string resource gets through. The screen still opens.
        val target = SettingsDeepLink.parse("wmkeyboard://settings/typing?setting=../../etc/passwd")
        assertEquals("typing", target?.route)
        assertEquals("", target?.setting)
        assertNull(SettingsDeepLink.parse("wmkeyboard://setting/Not%20A%20Name"))
    }

    // ---- rows resolve against the real index -------------------------------

    private val index by lazy { settingsSearchIndex(XmlSearchStrings.forApp()) }

    @Test
    fun `a row link finds the screen holding it`() {
        val entry = SettingsDeepLink.resolve(
            SettingsDeepLink.Target(route = "", setting = "typing_autocorrect_title"),
        ) { index }
        assertEquals("typing/corrections", entry?.route)
    }

    @Test
    fun `a row link scoped to a screen only matches on that screen`() {
        val wrong = SettingsDeepLink.resolve(
            SettingsDeepLink.Target(route = "themes", setting = "typing_autocorrect_title"),
        ) { index }
        assertNull(wrong)
    }

    @Test
    fun `a row that is on several screens resolves to the strongest`() {
        // Every indexed name has to land somewhere, and where a name appears
        // more than once the entry with the strongest claim wins rather than
        // whichever the index happened to build first.
        val ambiguous = index.groupBy { it.key.substringAfter('#') }.filterValues { it.size > 1 }
        for ((name, entries) in ambiguous) {
            val picked = SettingsDeepLink.resolve(SettingsDeepLink.Target(route = "", setting = name)) { index }
            assertEquals(
                "\"$name\" resolves to a weaker entry than the index holds",
                entries.minOf { it.weight.ordinal },
                picked?.weight?.ordinal,
            )
        }
    }

    @Test
    fun `an unknown row name resolves to nothing`() {
        assertNull(SettingsDeepLink.resolve(SettingsDeepLink.Target("", "no_such_row_title")) { index })
    }
}
