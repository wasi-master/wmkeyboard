package com.wasimaster.wmkeyboard.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking against the real index: the rows the settings screens draw, read
 * from the same `strings*.xml` files, with the real word groups and stop words.
 *
 * Each case is a query a person types and the result they mean by it. The
 * first list is the bar the ranking must clear: the meant row is the first
 * result. The second is looser: the meant row is in the first three, for the
 * queries where two rows are equally good answers. A change to the scoring
 * that moves a case out of its list is a change to argue for, not to make.
 *
 * `dumpRankings` writes the first eight results for every case to
 * `build/reports/settings-search-rankings.txt`, which is how a new case gets
 * its expectation and how a failure gets read.
 */
class SettingsSearchRankingTest {

    private val strings = XmlSearchStrings.forApp()
    private val index = settingsSearchIndex(strings)
    private val vocabulary = settingsSearchVocabulary(strings)

    private fun search(query: String) = rankSettings(query, index, vocabulary)

    /** A row by its title resource name, the stable half of [SettingsSearchEntry.key]. */
    private fun SettingsSearchEntry.named(name: String) = key.substringAfter('#') == name

    /** A row by route, for the screens. */
    private fun SettingsSearchEntry.at(route: String) = this.route == route && weight == EntryWeight.SECTION

    private class Case(val query: String, val describe: String, val meant: (SettingsSearchEntry) -> Boolean)

    private fun first(query: String, name: String) = Case(query, name) { it.named(name) }
    private fun screen(query: String, route: String) = Case(query, "screen $route") { it.at(route) }

    /** The meant row must be first. */
    private val top1 = listOf(
        // The switch that is the feature, by its own name or by a synonym.
        first("vibrate", "keypress_haptics_title"),
        first("vibration", "keypress_haptics_title"),
        first("autocorrect", "typing_autocorrect_title"),
        first("autocorect", "typing_autocorrect_title"),
        first("number row", "layout_number_row_title"),
        first("key height", "layout_key_height_title"),
        first("height", "layout_key_height_title"),
        first("glide", "typing_glide_typing_title"),
        first("swipe typing", "typing_glide_typing_title"),
        first("long press delay", "keypress_long_press_delay_title"),
        first("caps lock", "keypress_caps_lock_title"),
        first("skin tone", "langemoji_emoji_skin_tone_title"),
        first("clipboard history", "clipboard_history_title"),
        first("key sound", "hardware_sound_key_title"),
        first("double space", "typing_double_space_title"),
        first("space", "typing_double_space_title"),
        first("delete", "typing_backspace_swipe_title"),
        first("emoji row", "langemoji_emoji_row_title"),
        first("add language", "langemoji_lang_add_title"),
        first("incognito", "privacy_incognito_title"),
        first("offensive", "typing_block_offensive_title"),
        first("swear", "typing_block_offensive_title"),
        // The screen, when the query names one.
        screen("haptic", "keypress/haptics"),
        screen("haptics", "keypress/haptics"),
        screen("sound", "keypress/haptics"),
        screen("popup", "keypress/popup"),
        screen("key popup", "keypress/popup"),
        screen("one handed", "layout/onehanded"),
        screen("one-handed", "layout/onehanded"),
        screen("size", "layout/size"),
        screen("theme", "themes"),
        screen("themes", "themes"),
        screen("dark mode", "themes"),
        screen("wallpaper", "themes"),
        screen("language", "languages"),
        screen("languages", "languages"),
        screen("emoji", "emoji"),
        screen("clipboard", "clipboard"),
        screen("voice", "voice"),
        screen("backup", "backup"),
        screen("privacy", "privacy"),
        screen("font", "fonts"),
        screen("fonts", "fonts"),
        screen("suggestions", "typing/suggestions"),
        screen("gestures", "typing/gestures"),
        screen("toolbar", "appearance/toolbar"),
        screen("dictionary", "dictionary"),
        screen("fingerprint", "applock"),
        screen("storage", "storage"),
        screen("otp", "typing/codes"),
        // The tool's page, when only a tool is called that.
        first("translate", "ime_tool_translate"),
        first("calculator", "ime_tool_calculator"),
    )

    /** The meant row must be in the first three: two rows are equally good answers. */
    private val top3 = listOf(
        first("haptic", "keypress_haptics_title"),
        first("haptics", "keypress_haptics_title"),
        first("auto correct", "typing_autocorrect_title"),
        // Autocorrect, reached through the synonym, is as good an answer as the page.
        screen("corrections", "typing/corrections"),
        first("popup", "keypress_popup_title"),
        first("key popup", "keypress_popup_title"),
        first("one handed", "layout_one_handed_title"),
        first("split", "layout_split_title"),
        first("floating", "layout_floating_title"),
        first("capitalize", "typing_auto_capitalize_title"),
        first("sound", "hardware_sound_key_title"),
        first("volume", "hardware_sound_volume_title"),
        first("keyboard height", "layout_key_height_title"),
        first("backspace", "typing_backspace_swipe_title"),
        first("shift", "typing_shift_enter_title"),
        first("otp", "typing_otp_chip_title"),
        first("codes", "typing_otp_chip_title"),
        first("suggestions", "typing_suggestions_title"),
        first("night", "theme_auto_dark_title"),
    )

    @Test
    fun `the index builds from the xml with every row present`() {
        assertTrue("index too small: ${index.size}", index.size > 500)
        val blank = index.filter { it.title.isBlank() }
        assertEquals("rows with no title", emptyList<SettingsSearchEntry>(), blank)
    }

    @Test
    fun `no two rows share a key`() {
        val repeated = index.groupBy { it.key }.filterValues { it.size > 1 }.keys
        assertEquals("rows listed twice", emptySet<String>(), repeated)
    }

    @Test
    fun `the meant row is the first result`() {
        val misses = top1.mapNotNull { case ->
            val results = search(case.query).all
            val at = results.indexOfFirst(case.meant)
            if (at == 0) null else "'${case.query}' → ${case.describe} is at $at; first is ${results.firstOrNull()?.title}"
        }
        assertEquals(emptyList<String>(), misses)
    }

    @Test
    fun `the meant row is in the first three`() {
        val misses = top3.mapNotNull { case ->
            val results = search(case.query).all
            val at = results.indexOfFirst(case.meant)
            if (at in 0..2) null else "'${case.query}' → ${case.describe} is at $at; first is ${results.firstOrNull()?.title}"
        }
        assertEquals(emptyList<String>(), misses)
    }

    @Test
    fun `a word found only in subtitles is a mention, not a hit`() {
        // "height" names a handful of rows and appears in the subtitles of
        // several more ("taller", "the height of…"). The named ones are the
        // hits; the rest are mentions. No word group carries "height", so a
        // hit cannot have come through a synonym.
        val results = search("height")
        assertTrue(results.hits.isNotEmpty())
        val unnamed = results.hits.filterNot { "height" in normalizeForSearch(it.title + " " + it.keywords) }
        assertEquals("hits that do not carry the word in their name", emptyList<SettingsSearchEntry>(), unnamed)
        assertTrue(results.mentions.isNotEmpty())
    }

    @Test
    fun `a short word is not found inside longer words`() {
        val results = search("row").all
        val inside = results.filter { entry ->
            val words = normalizeForSearch(entry.title + " " + entry.keywords + " " + entry.subtitle + " " + entry.screen)
                .split(' ')
            words.none { it == "row" || it == "rows" || it.startsWith("row") }
        }
        assertEquals("rows found via 'arrow', 'narrow', 'browser'", emptyList<SettingsSearchEntry>(), inside)
    }

    @Test
    fun `dumpRankings`() {
        val out = File("build/reports/settings-search-rankings.txt")
        out.parentFile.mkdirs()
        val queries = (top1 + top3).map { it.query } + listOf(
            "key", "press", "row", "colour", "color", "layout", "tools", "gif", "sticker", "password", "reset",
            "keyboard", "sensitive", "typing", "swipe", "cursor", "long press", "spacebar", "background", "learn",
        )
        out.printWriter().use { w ->
            for (q in queries.distinct()) {
                val r = search(q)
                w.println("== $q  (${r.hits.size} hits, ${r.mentions.size} mentions)")
                r.hits.take(8).forEachIndexed { i, e -> w.println("  $i ${e.weight.name.padEnd(7)} ${e.title}  [${e.screen}]  ${e.key}") }
                r.mentions.take(3).forEach { e -> w.println("  ~ ${e.title}  [${e.screen}]") }
            }
        }
    }
}
