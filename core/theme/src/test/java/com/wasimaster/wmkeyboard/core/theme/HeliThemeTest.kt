package com.wasimaster.wmkeyboard.core.theme

import java.io.File
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a HeliBoard or LeanType colour theme.
 *
 * The fixtures are hand-written from the published shape, not themes taken from
 * either project's collections: those are their authors' work, and this only
 * needs the shape of the document. HeliBoard and LeanType are GPL-3.0 and this
 * repo is MIT.
 */
class HeliThemeTest {

    private fun converted(text: String): HeliResult.Converted {
        val result = HeliTheme.read(text)
        assertTrue("expected a theme, got $result", result is HeliResult.Converted)
        return result as HeliResult.Converted
    }

    // ---- the named shape ----

    @Test
    fun `the ten named colours land on their fields`() {
        val theme = converted(
            """
            {"name":"Midnight","moreColors":0,"colors":{
              "background":[-16777216,false],
              "keys":[-14342875,false],
              "functional_keys":[-12566464,false],
              "text":[-1,false],
              "hint_text":[-5000269,false],
              "suggestion_text":[-1,false],
              "accent":[-16738680,false],
              "gesture":[-16738680,false],
              "spacebar":[-13421773,false],
              "spacebar_text":[-3355444,false]
            }}
            """.trimIndent(),
        ).theme
        assertEquals("Midnight", theme.name)
        assertEquals(0xFF000000, theme.boardBackground)
        assertEquals(0xFF252525, theme.keyBackground)
        assertEquals(0xFF404040, theme.modifierKeyBackground)
        assertEquals(0xFFFFFFFF, theme.keyText)
        assertEquals(0xFFB3B3B3, theme.hintText)
        assertEquals(0xFF009688, theme.accent)
        assertEquals(0xFF009688, theme.gestureTrailColor)
        // The spacebar is the one key with colours of its own over there, and
        // a per-key override is where those belong here.
        val space = checkNotNull(theme.keyOverrides["SPACE"])
        assertEquals(0xFF333333, space.background)
        assertEquals(0xFFCCCCCC, space.text)
    }

    @Test
    fun `a colour the other keyboard derived is left to derive here`() {
        // `true` is that keyboard saying it worked the colour out. Freezing it
        // in would be indistinguishable from a colour the user chose.
        val theme = converted(
            """{"name":"x","moreColors":0,"colors":{
                 "background":[-16777216,false],
                 "keys":[-14342875,false],
                 "hint_text":[-5000269,true],
                 "suggestion_text":[null,true]
               }}""",
        ).theme
        assertNull(theme.hintText)
        assertNull(theme.suggestionText)
    }

    @Test
    fun `a light background makes a light theme`() {
        val dark = converted(
            """{"moreColors":0,"colors":{"background":[-16777216,false]}}""",
        ).theme
        val light = converted(
            """{"moreColors":0,"colors":{"background":[-1,false]}}""",
        ).theme
        assertTrue(dark.dark)
        assertFalse(light.dark)
    }

    @Test
    fun `a theme with no name still has one`() {
        assertTrue(
            converted("""{"moreColors":0,"colors":{"background":[-1,false]}}""")
                .theme.name.isNotBlank(),
        )
    }

    // ---- the all-colours shape ----

    @Test
    fun `the all-colours shape reads the roles it names`() {
        val result = converted(
            """
            {"MAIN_BACKGROUND":-16777216,"KEY_BACKGROUND":-14342875,"KEY_TEXT":-1,
             "FUNCTIONAL_KEY_BACKGROUND":-12566464,"FUNCTIONAL_KEY_TEXT":-1,
             "ACTION_KEY_BACKGROUND":-16738680,"ACTION_KEY_ICON":-16777216,
             "POPUP_KEYS_BACKGROUND":-13421773,"POPUP_KEY_TEXT":-1,
             "STRIP_BACKGROUND":-15658735,"SUGGESTION_TYPED_WORD":-1,
             "TOOL_BAR_KEY":-3355444,"NAVIGATION_BAR":-16777216,
             "GESTURE_TRAIL":-16738680,"SHIFT_KEY_ICON":-256}
            """.trimIndent(),
        )
        assertEquals(HeliShape.ALL, result.shape)
        val theme = result.theme
        assertEquals(0xFF000000, theme.boardBackground)
        assertEquals(0xFF009688, theme.enterKeyBackground)
        assertEquals(0xFF333333, theme.popupBackground)
        assertEquals(0xFF111111, theme.suggestionBarBackground)
        assertEquals(0xFFCCCCCC, theme.toolbarIcon)
        assertEquals(0xFF000000, theme.navigationBarBackground)
        assertEquals(0xFFFFFF00, checkNotNull(theme.keyOverrides["SHIFT"]).text)
    }

    @Test
    fun `the base-36 entry is the theme's name, not a colour`() {
        val name = "Solar"
        val encoded = BigInteger(name.toByteArray()).toString(36)
        val result = converted(
            """{"MAIN_BACKGROUND":-1,"KEY_BACKGROUND":-1118482,"$encoded":0}""",
        )
        assertEquals(name, result.theme.name)
        // And it is not counted as one of the colours read.
        assertEquals(2, result.coloursRead)
    }

    @Test
    fun `an unknown role is neither a colour nor a name`() {
        // A role from a newer build of the other keyboard must not become a
        // theme called by a run of nonsense.
        val result = converted(
            """{"MAIN_BACKGROUND":-1,"KEY_BACKGROUND":-1118482,"SOMETHING_NEW":-256}""",
        )
        assertEquals(2, result.coloursRead)
        assertFalse(result.theme.name.contains("SOMETHING"))
    }

    @Test
    fun `the count says how much of the theme landed`() {
        // KEY_TEXT and KEY_ICON both answer "what colour is a key's label",
        // and only the first present one is read — so three roles are stated
        // and two are used, and the dialog can say so honestly. The count used
        // to be "roles this app knows of", which called this three of three.
        val result = converted(
            """{"MAIN_BACKGROUND":-1,"KEY_TEXT":-16777216,"KEY_ICON":-7829368}""",
        )
        assertEquals(3, result.coloursRead)
        assertEquals(2, result.coloursUsed)
        assertTrue(HeliUnsupported.UNUSED_COLOURS in result.dropped)
    }

    @Test
    fun `the emoji board's colours land now that there are fields for them`() {
        // All seven of these were read and then thrown away: the import knew
        // the roles but named no field for any of them.
        val result = converted(
            """{"MAIN_BACKGROUND":-1,"EMOJI_SEARCH_BACKGROUND":-1118482,
                "EMOJI_SEARCH_TEXT":-16777216,"EMOJI_KEY_TEXT":-16777216,
                "EMOJI_CATEGORY":-7829368,"REMOVE_SUGGESTION_ICON":-7829368,
                "ACTION_KEY_POPUP_KEYS_BACKGROUND":-3355444}""",
        )
        assertEquals(7, result.coloursRead)
        assertEquals(7, result.coloursUsed)
        assertFalse(HeliUnsupported.UNUSED_COLOURS in result.dropped)
        val theme = result.theme
        assertEquals(0xFFEEEEEE, theme.chipBackground)
        assertEquals(0xFF000000, theme.chipText)
        assertEquals(0xFF000000, theme.suggestionText)
        assertEquals(0xFF888888, theme.secondaryText)
        assertEquals(0xFF888888, theme.toolbarIcon)
        assertEquals(0xFFCCCCCC, theme.popupBackground)
    }

    @Test
    fun `the emoji search bar and the clipboard chip keep colours of their own`() {
        // Gap list item 28: both used to fold into the shared card colour, so a
        // theme that coloured them apart lost the difference.
        val theme = converted(
            """{"MAIN_BACKGROUND":-1,"EMOJI_SEARCH_BACKGROUND":-1118482,
                "EMOJI_SEARCH_TEXT":-16777216,"CLIPBOARD_SUGGESTION_BACKGROUND":-16776961,
                "CLIPBOARD_SUGGESTION_ICON":-1}""",
        ).theme
        assertEquals(0xFFEEEEEE, theme.emojiSearchBackground)
        assertEquals(0xFF000000, theme.emojiSearchText)
        assertEquals(0xFF0000FF, theme.clipboardChipBackground)
        assertEquals(0xFFFFFFFF, theme.clipboardChipText)
        // The cards still get a colour from the same file.
        assertEquals(0xFF0000FF, theme.chipBackground)
    }

    @Test
    fun `a theme without those roles leaves the new fields to fall back`() {
        val theme = converted("""{"MAIN_BACKGROUND":-1,"KEY_BACKGROUND":-1118482}""").theme
        assertNull(theme.emojiSearchBackground)
        assertNull(theme.emojiSearchText)
        assertNull(theme.clipboardChipBackground)
        assertNull(theme.clipboardChipText)
    }

    @Test
    fun `a theme that leaves colours to the keyboard says so`() {
        val result = converted(
            """{"name":"Half","moreColors":0,"colors":{
                 "background":[-16777216,false],
                 "keys":[null,true],
                 "text":[-1,false]
               }}""",
        )
        assertTrue(HeliUnsupported.DERIVED_COLOURS in result.dropped)
    }

    @Test
    fun `every role the reader knows reaches a field`() {
        // The guard on the gap this batch closed. A role added to the table
        // without a home in one of the lists is a colour read off the user's
        // file and dropped on the floor, which is exactly what happened to the
        // emoji board's seven.
        val homeless = HeliTheme.AllColours - HeliTheme.Landing
        assertTrue("no field reads $homeless", homeless.isEmpty())
        assertTrue(
            "no field reads ${HeliTheme.NamedColours - HeliTheme.Landing}",
            (HeliTheme.NamedColours - HeliTheme.Landing).isEmpty(),
        )
    }

    // ---- what is not a theme ----

    @Test
    fun `anything else is not a theme`() {
        assertEquals(HeliResult.NotATheme, HeliTheme.read(""))
        assertEquals(HeliResult.NotATheme, HeliTheme.read("hello"))
        assertEquals(HeliResult.NotATheme, HeliTheme.read("[1,2,3]"))
        assertEquals(HeliResult.NotATheme, HeliTheme.read("""{"shopping":["milk"]}"""))
        // A theme with no background has nothing to build on.
        assertEquals(
            HeliResult.NotATheme,
            HeliTheme.read("""{"moreColors":0,"colors":{"gesture":[-1,false]}}"""),
        )
    }

    @Test
    fun `this app's own theme file is not read as a HeliBoard one`() {
        val own = ThemeCodec.encode(ThemeSpec(id = "x", name = "Mine"))
        assertEquals(HeliResult.NotATheme, HeliTheme.read(own))
    }

    @Test
    fun `a huge paste is refused before it is parsed`() {
        assertEquals(HeliResult.NotATheme, HeliTheme.read("{".repeat(HeliTheme.MAX_LENGTH + 1)))
    }

    @Test
    fun `real themes read, when a folder of them is pointed at`() {
        // Takes a folder of themes from `HELI_THEMES` and skips silently when
        // it is unset, the way the Keyman corpus sweep does, so an ordinary
        // build downloads nothing. Point it at a clone of one of the theme
        // collections people actually share.
        val folder = System.getenv("HELI_THEMES")?.let(::File) ?: return
        val files = folder.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
        if (files.isEmpty()) return
        for (file in files) {
            val result = HeliTheme.read(file.readText())
            assertTrue("${file.name} did not read: $result", result is HeliResult.Converted)
            val theme = (result as HeliResult.Converted).theme
            assertTrue("${file.name} has no name", theme.name.isNotBlank())
            assertTrue("${file.name} used nothing", result.coloursUsed > 0)
            // A theme whose keys cannot be read against the board is the one
            // failure a user cannot work around in the editor.
            assertTrue(
                "${file.name} keys are unreadable",
                contrastRatio(
                    composite(theme.keyText, composite(theme.keyBackground, theme.boardBackground)),
                    composite(theme.keyBackground, theme.boardBackground),
                ) >= Readability.POOR_CONTRAST,
            )
        }
    }

    @Test
    fun `an unreadable key text is dropped rather than left unreadable`() {
        // White on white. The file is honest, the result would not be.
        val theme = converted(
            """{"moreColors":0,"colors":{
                 "background":[-1,false],"keys":[-1,false],"text":[-1,false]
               }}""",
        ).theme
        assertNotNull(theme.keyText)
        assertTrue(contrastRatio(theme.keyText, 0xFFFFFFFF) > 2f)
    }
}
