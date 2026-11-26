package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ways a tool opens without a field (#970) against the manifest and the
 * stored values they read back. Each drifts in silence: a tool with no alias
 * has a switch that does nothing, an alias declared above its target makes
 * the APK uninstallable, and a token the menu stored that no longer parses
 * drops a line from the user's menu.
 */
class ToolShortcutsTest {

    private val manifest = File("src/main/AndroidManifest.xml").readText()
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    /** Opening-tag attributes and body of each tool's alias, keyed by its android:name. */
    private val toolAliases: Map<String, Pair<String, String>> =
        Regex("""<activity-alias\s([^>]*)>(.*?)</activity-alias>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (attributes, _) -> attribute(attributes, "name").startsWith(ToolAppList.ALIAS_PREFIX) }
            .associateBy { (attributes, _) -> attribute(attributes, "name") }

    private fun attribute(attributes: String, name: String): String =
        Regex("""android:$name="([^"]*)"""").find(attributes)?.groupValues?.get(1).orEmpty()

    @Test
    fun `every tool that opens on its own has an icon and an alias`() {
        assertTrue("no tool opens on its own", ToolLaunch.tools.size > 40)
        assertEquals("tools with no app icon", emptyList<ToolbarTool>(), ToolLaunch.tools.filterNot { it in ToolAppIcons })
        val missing = ToolLaunch.tools.map { ToolAppList.ALIAS_PREFIX + it.name }.filterNot { it in toolAliases }
        assertEquals("tools with no <activity-alias>", emptyList<String>(), missing)
    }

    @Test
    fun `every alias is a tool with a panel`() {
        // Not "launchable": a build without a tool's engine (F-Droid's has no
        // ML Kit) keeps its alias, off, with no switch to turn it on.
        val stale = toolAliases.keys.filter { ToolAppList.toolOf(it) == null }
        assertEquals("aliases for no tool", emptyList<String>(), stale)
    }

    @Test
    fun `each alias is an app-list entry that starts off`() {
        val target = manifest.indexOf("""android:name="${ToolShortcutActivity::class.java.name}"""")
        assertTrue("ToolShortcutActivity is missing from the manifest", target >= 0)
        for ((name, alias) in toolAliases) {
            val (attributes, body) = alias
            val tool = checkNotNull(ToolAppList.toolOf(name))
            assertEquals("$name must open ToolShortcutActivity", ToolShortcutActivity::class.java.name, attribute(attributes, "targetActivity"))
            assertEquals("$name must start off: the switch turns it on", "false", attribute(attributes, "enabled"))
            assertEquals("$name must be exported to be launchable", "true", attribute(attributes, "exported"))
            assertEquals("@mipmap/ic_tool_app_${tool.name.lowercase()}", attribute(attributes, "icon"))
            assertTrue("$name has no label", attribute(attributes, "label").startsWith("@string/"))
            assertTrue("$name has no LAUNCHER category", "android.intent.category.LAUNCHER" in body)
            assertTrue("$name is declared above its target", target < manifest.indexOf("""android:name="$name""""))
        }
    }

    @Test
    fun `a class name reads back as its tool`() {
        for (tool in ToolLaunch.tools) assertEquals(tool, ToolAppList.toolOf(ToolAppList.ALIAS_PREFIX + tool.name))
        assertNull(ToolAppList.toolOf(MainActivity::class.java.name))
        assertNull(ToolAppList.toolOf(null))
    }

    @Test
    fun `every line of the icon's menu reads back from its token`() {
        val all = AppIconMenu.Page.entries.map(AppIconMenu.Entry::PageEntry) + ToolLaunch.tools.map(AppIconMenu.Entry::ToolEntry)
        for (entry in all) assertEquals(entry, AppIconMenu.Entry.of(entry.token))
        assertNull(AppIconMenu.Entry.of("page:GONE"))
        assertNull(AppIconMenu.Entry.of("tool:GONE"))
        assertNull(AppIconMenu.Entry.of("THEMES"))
    }

    @Test
    fun `a fresh menu holds the four pages the manifest used to`() {
        assertEquals(
            listOf(AppIconMenu.Page.THEMES, AppIconMenu.Page.AI_CHAT, AppIconMenu.Page.LANGUAGES, AppIconMenu.Page.TOOLS),
            AppIconMenu.Default.map { (it as AppIconMenu.Entry.PageEntry).page },
        )
    }

    @Test
    fun `a tool called exactly what was typed comes first on the Tools screen`() {
        // Names as normalizeForSearch leaves them, in the screen's own order;
        // the dictionary is in only because its description says notes.
        val matches = listOf("dictionary", "smart notes", "notes tags", "notes")
        assertEquals(
            listOf("notes", "notes tags", "smart notes", "dictionary"),
            rankToolMatches(matches, { it }, listOf("notes"), literal = { true }),
        )
        // A match that came in through another form of a word stays last.
        assertEquals(
            listOf("notes", "note pad"),
            rankToolMatches(listOf("note pad", "notes"), { it }, listOf("notes"), literal = { it == "notes" }),
        )
    }
}
