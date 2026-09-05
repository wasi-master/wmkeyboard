package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two jobs.
 *
 * The first is the drift guard. The index no longer copies the words of a
 * settings row; it names the row's string resource. So the test reads the
 * `R.string.…` names out of `SettingsSearch.kt` and checks two things about
 * each one: that the resource exists, and that some other screen file also
 * draws it. A key that only the index names is a row that was deleted or
 * renamed, which is the drift this test is here to catch. The check runs on
 * the sources, so it stays a plain JVM test with no Android resources.
 *
 * The second is the ranking. Those tests run on a small hand-built index,
 * because the real one now needs [android.content.res.Resources].
 */
class SettingsSearchIndexTest {

    private val appDir = File("src/main/java/com/wasimaster/wmkeyboard/app")
    private val valuesDir = File("src/main/res/values")

    /** The two files search is built from: the index, and the matcher. */
    private val searchFiles = listOf("SettingsSearch.kt", "SettingsSearchMatch.kt")

    private val indexSource: String by lazy {
        val file = File(appDir, "SettingsSearch.kt")
        assertTrue("search index not found at ${file.absolutePath}", file.isFile)
        file.readText()
    }

    private val searchSource: String by lazy {
        searchFiles.joinToString("\n") { name ->
            val file = File(appDir, name)
            assertTrue("$name not found at ${file.absolutePath}", file.isFile)
            file.readText()
        }
    }

    private val searchStringsXml: String by lazy {
        val file = File(valuesDir, "strings_search.xml")
        assertTrue("search strings not found at ${file.absolutePath}", file.isFile)
        file.readText()
    }

    /**
     * Every `R.string.…` the index names. `CommonR.string.…` is skipped: those
     * belong to :core:common and are not in this module's resource files.
     */
    private val indexedKeys: List<String> by lazy {
        Regex("""(?<!Common)R\.(?:string|array)\.([a-z0-9_]+)""")
            .findAll(searchSource)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    /** Every string this module defines, from every `values/strings*.xml`. */
    private val definedKeys: Set<String> by lazy {
        val files = valuesDir
            .listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }
            .orEmpty()
        assertTrue("no strings*.xml under ${valuesDir.absolutePath}", files.isNotEmpty())
        files.flatMapTo(mutableSetOf()) { file ->
            Regex("<string(?:-array)? name=\"([^\"]+)\"")
                .findAll(file.readText())
                .map { it.groupValues[1] }
                .toList()
        }
    }

    /**
     * Every string some settings screen draws. The index itself is excluded.
     *
     * Walks the tree rather than listing one directory: the screens are mostly
     * flat under `app/`, but a feature with several files of its own gets a
     * package (`app/updates/`), and a row drawn from there is still a row.
     */
    private val keysDrawnByScreens: Set<String> by lazy {
        appDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name in searchFiles }
            .flatMapTo(mutableSetOf()) { file ->
                Regex("""R\.string\.([a-z0-9_]+)""")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .toList()
            }
    }

    /**
     * Strings the index owns outright, because the screen has nothing to point
     * at. Keep this list short: each entry is a second copy of some wording
     * that a translator has to keep in step by hand.
     *
     * Everything named `search_…` is owned by definition: those are the words
     * the search matches on, and no screen draws them.
     */
    private val indexOwnedKeys = setOf(
        // The sticker editor has no row of its own: it opens from a photo the
        // user picked or from a sticker they tapped, so the index describes it
        // and lands on the pack list instead.
        "import_sticker_editor_subtitle",
    )

    private fun isIndexOwned(key: String) = key in indexOwnedKeys || key.startsWith("search_")

    @Test
    fun `the index names only strings this module defines`() {
        val missing = indexedKeys.filterNot { it in definedKeys }
        assertEquals("R.string names in the index with no resource", emptyList<String>(), missing)
    }

    @Test
    fun `every string the index names is drawn by a settings screen`() {
        val orphans = indexedKeys
            .filterNot { it in keysDrawnByScreens }
            .filterNot { isIndexOwned(it) }
        assertEquals("indexed rows no longer drawn by any screen", emptyList<String>(), orphans)
    }

    @Test
    fun `the index owns no wording it does not have to`() {
        val unused = indexOwnedKeys.filterNot { it in indexedKeys }
        assertEquals("index-owned strings the index stopped using", emptyList<String>(), unused)
    }

    @Test
    fun `every search-only string is used by the index`() {
        val declared = Regex("<string name=\"(search_[a-z0-9_]+)\"")
            .findAll(searchStringsXml)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("no search_ strings declared", declared.isNotEmpty())
        val unused = declared.filterNot { it in indexedKeys }
        assertEquals("search words no entry carries", emptyList<String>(), unused)
    }

    /**
     * The word groups are what makes a search for "vibration" find the haptics
     * rows. They are matched against text that [normalizeForSearch] has already
     * been through, so a group holding a capital, an accent or a comma would
     * hold a word that can never be typed back.
     */
    @Test
    fun `every word group is made of plain lower-case words`() {
        val groups = Regex("<item>([^<]+)</item>")
            .findAll(searchStringsXml)
            .map { it.groupValues[1].split(' ').filter(String::isNotEmpty) }
            .toList()
        assertTrue("no word groups found", groups.size > 20)
        val malformed = groups.flatten().filterNot { it == normalizeForSearch(it) }
        assertEquals("word-group words that no query can match", emptyList<String>(), malformed)
        val tooShort = groups.filter { it.size < 2 }
        assertEquals("word groups with nothing to map to", emptyList<List<String>>(), tooShort)
        val repeated = groups.flatten().groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .filterNot { it in setOf("password", "trace") }
        assertEquals("words in more than one group by accident", emptyList<String>(), repeated)
    }

    @Test
    fun `every screen a result can open has an icon`() {
        // The routes are the only lower-case string literals in the index.
        // Sub-routes ("typing/corrections") are routes too; the pattern must
        // admit a path, or a screen added under another is invisible here.
        val routes = Regex("\"([a-z][a-z0-9_]*(?:/[a-z0-9_{}]+)*)\"")
            .findAll(indexSource)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        assertTrue("no routes found in the index", routes.size > 20)
        val missing = routes.filterNot { it in SettingsRouteIcons }
        assertEquals("routes with no icon in SettingsRouteIcons", emptyList<String>(), missing)
    }

    // ---- ranking ----

    private fun row(
        title: String,
        subtitle: String = "",
        screen: String = "Typing",
        route: String = "typing",
        weight: EntryWeight = EntryWeight.NORMAL,
        tool: ToolbarTool? = null,
        keywords: String = "",
    ) = SettingsSearchEntry(title, subtitle, screen, route, weight, tool, keywords = keywords)

    /**
     * A stand-in for the real index: one entry for every ranking rule the tests
     * below check, and nothing else.
     */
    private val fixture: List<SettingsSearchEntry> = listOf(
        row("Autocorrect", "Fix typos automatically when you press space"),
        row("Block offensive words", "Keep the words that autocorrect offers clean"),
        row("Number row", "Show a dedicated digit row above the letters", "Layout & size", "layout"),
        row("Emoji row", "A dedicated row of your emoji", "Emoji", "emoji"),
        row(
            "Keyboard themes",
            "Light, dark and AMOLED themes",
            "Appearance",
            "themes",
            weight = EntryWeight.SECTION,
        ),
        row("Themes", "Change the theme", "Tools › Themes", "tool/THEMES", tool = ToolbarTool.THEMES),
        row(
            "Sticker packs",
            "Make, edit, import and export packs of your own",
            "Tools › Stickers",
            "tool/STICKER",
            tool = ToolbarTool.STICKER,
        ),
        row(
            "Sticker packs",
            "Your own sticker packs, images and all",
            "Backup & restore",
            "backup",
            weight = EntryWeight.MIRROR,
        ),
        row("Klipy API key", "Free from partner.klipy.com", "Tools › GIF", "tool/GIF", tool = ToolbarTool.GIF),
        row(
            "Include API keys",
            "Translate, GIF, search and AI keys",
            "Backup & restore",
            "backup",
            weight = EntryWeight.MIRROR,
        ),
    )

    @Test
    fun `search ranks an exact title above a subtitle mention`() {
        val results = searchSettings("autocorrect", fixture)
        assertTrue(results.isNotEmpty())
        assertEquals("Autocorrect", results.first().title)
    }

    @Test
    fun `every token must match`() {
        // "row" alone hits several settings; pairing it narrows to number row.
        val results = searchSettings("number row", fixture)
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { "number" in (it.title + it.subtitle + it.screen).lowercase() })
    }

    @Test
    fun `a query matching nothing returns nothing`() {
        assertEquals(emptyList<SettingsSearchEntry>(), searchSettings("zzzznotasetting", fixture))
    }

    @Test
    fun `a blank query returns nothing`() {
        assertEquals(emptyList<SettingsSearchEntry>(), searchSettings("   ", fixture))
    }

    @Test
    fun `a screen outranks the toolbar shortcut that opens it`() {
        // "Themes" is an exact hit on the toolbar tool and only a word of the
        // screen's name, so raw scoring put the shortcut on top.
        val results = searchSettings("themes", fixture)
        assertTrue(results.isNotEmpty())
        assertEquals("themes", results.first().route)
    }

    @Test
    fun `a feature outranks the backup toggle named after it`() {
        val results = searchSettings("sticker packs", fixture)
        assertTrue(results.isNotEmpty())
        assertEquals("Tools › Stickers", results.first().screen)
        // The toggle is still findable, just not first.
        assertTrue(results.any { it.route == "backup" })
    }

    @Test
    fun `a setting outranks the backup toggle that includes it`() {
        val results = searchSettings("api key", fixture)
        assertTrue(results.isNotEmpty())
        assertTrue(results.first().route.startsWith("tool/"))
    }

    // ---- matching ----

    /** What the resources hold, in the shape [searchSettings] wants it. */
    private val vocabulary = SettingsSearchVocabulary(
        groups = listOf(
            listOf("haptics", "vibration", "vibrate", "buzz"),
            listOf("theme", "themes", "skin"),
        ),
        stopWords = setOf("the", "a", "of", "on", "for"),
    )

    private val hapticRow = row("Haptics", "A short vibration under each key press", "Key press", "keypress")

    @Test
    fun `a word the screens never use finds the setting that means it`() {
        val results = searchSettings("vibrate", listOf(hapticRow) + fixture, vocabulary)
        assertTrue(results.isNotEmpty())
        assertEquals("Haptics", results.first().title)
    }

    @Test
    fun `the word as typed outranks a word that only means the same`() {
        val skin = row("Skin tone", "The default tone of a hand or a face", "Emoji", "emoji")
        val results = searchSettings("skin", listOf(skin) + fixture, vocabulary)
        assertTrue(results.isNotEmpty())
        assertEquals("Skin tone", results.first().title)
    }

    @Test
    fun `a misspelt word still finds the setting`() {
        val results = searchSettings("autocorect", fixture)
        assertTrue(results.isNotEmpty())
        assertEquals("Autocorrect", results.first().title)
    }

    @Test
    fun `two letters typed in the wrong order still find the setting`() {
        val results = searchSettings("hpatics", listOf(hapticRow), vocabulary)
        assertEquals(listOf("Haptics"), results.map { it.title })
    }

    @Test
    fun `a misspelling in a sentence is not a match`() {
        // "mode" is one edit from "more", and a subtitle long enough says
        // "more" about something. Only the names spell-correct.
        val row = row("Bottom row height", "Make the bottom row taller for more reach")
        assertEquals(emptyList<SettingsSearchEntry>(), searchSettings("mode", listOf(row)))
    }

    @Test
    fun `a plural finds the singular`() {
        val results = searchSettings("theme", fixture)
        assertTrue(results.any { it.title == "Keyboard themes" })
    }

    @Test
    fun `punctuation in a name does not have to be typed`() {
        val split = row("One-handed mode", "Move the keys to one side", "Layout & size", "layout")
        assertEquals(listOf("One-handed mode"), searchSettings("onehanded", listOf(split)).map { it.title })
        assertEquals(listOf("One-handed mode"), searchSettings("one handed", listOf(split)).map { it.title })
    }

    @Test
    fun `words a search cannot narrow with are ignored`() {
        val results = searchSettings("the autocorrect", fixture, vocabulary)
        assertTrue(results.isNotEmpty())
        assertEquals("Autocorrect", results.first().title)
    }

    @Test
    fun `a screen is found by a word it never draws`() {
        val themes = row(
            "Keyboard themes",
            "Light, dark and AMOLED themes",
            "Appearance",
            "themes",
            weight = EntryWeight.SECTION,
            keywords = "dark mode, wallpaper, colours",
        )
        val results = searchSettings("wallpaper", listOf(themes) + fixture)
        assertEquals(listOf("Keyboard themes"), results.map { it.title })
    }

    @Test
    fun `half a query answers with the half that lands`() {
        // No row is both offensive and blocked-by-name; the block one carries
        // most of the query and answers it rather than an empty screen.
        val results = searchSettings("offensive zzzznotasetting", fixture)
        assertTrue(results.isNotEmpty())
        assertEquals("Block offensive words", results.first().title)
    }

    @Test
    fun `one word out of four is not an answer`() {
        val results = searchSettings("offensive zzzza zzzzb zzzzc", fixture)
        assertEquals(emptyList<SettingsSearchEntry>(), results)
    }
}
