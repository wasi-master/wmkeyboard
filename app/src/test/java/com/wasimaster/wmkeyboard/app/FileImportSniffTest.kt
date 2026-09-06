package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.icons.IconPackFile
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.theme.FlexTheme
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which format a file is.
 *
 * The ordering inside `WMFileTypes.identify` is a safety property, not a
 * convenience: every format it recognises carries a tag except the theme, which
 * is told apart by its file name alone and is documented as a known compromise.
 * The tests that matter here are therefore the negative ones — a file that is
 * *not* ours has to come back unrecognised rather than falling into that last
 * branch and being imported as an all-defaults theme.
 */
class FileImportSniffTest {

    // ---- text ----

    @Test
    fun `a tagged layout is a layout`() {
        val text = LayoutFile.encode(
            com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts.default,
            appVersion = 1,
            appVersionName = "1",
        )
        assertTrue(WMFileTypes.textKindFor(text, "grid.wmlayout.json") is WMFileTypes.Opened.Layout)
    }

    @Test
    fun `a theme is recognised only by its name`() {
        val theme = ThemeCodec.encode(
            com.wasimaster.wmkeyboard.core.theme.ThemeSpec(id = "x", name = "X"),
        )
        assertTrue(WMFileTypes.textKindFor(theme, "x.wmtheme.json") is WMFileTypes.Opened.Theme)
        // The same bytes under another name are not a theme, because there is
        // nothing in them that says they are one.
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.textKindFor(theme, "x.json"),
        )
    }

    @Test
    fun `a layout from another keyboard is never read as a theme`() {
        // The one test this file exists for. A FlorisBoard or HeliBoard layout
        // carries no tag of ours, so if it reached the theme branch under a
        // .wmtheme.json name it would decode to an all-defaults theme and the
        // user would have imported a black keyboard instead of their grid.
        val foreign = """[[{"${'$'}":"text_key","code":113,"label":"q"}]]"""
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.textKindFor(foreign, "qwerty.json"),
        )
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.textKindFor(foreign, "qwerty.txt"),
        )
        // Even renamed, it is refused: it decodes to a theme that sets nothing,
        // and importing that is worse than importing nothing.
        val renamed = WMFileTypes.textKindFor(foreign, "qwerty.wmtheme.json")
        assertTrue(
            "a foreign layout was read as a theme",
            renamed !is WMFileTypes.Opened.Layout,
        )
    }

    @Test
    fun `an unrelated file is unrecognised`() {
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.textKindFor("""{"shopping":["milk","eggs"]}""", "list.json"),
        )
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor("hello", "notes.txt"))
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor("", "empty.json"))
    }

    // ---- archives ----

    @Test
    fun `an archive is told apart by the tag in its manifest`() {
        assertEquals(
            WMFileTypes.Opened.Icons,
            WMFileTypes.archiveKindFor("""{"format":"${IconPackFile.FORMAT}"}"""),
        )
        assertEquals(
            WMFileTypes.Opened.Stickers,
            WMFileTypes.archiveKindFor("""{"format":"${StickerPackFile.FORMAT}"}"""),
        )
        assertEquals(
            WMFileTypes.Opened.Plugin,
            WMFileTypes.archiveKindFor("""{"format":"${PluginFile.FORMAT}"}"""),
        )
    }

    @Test
    fun `a manifest with no tag we know is unrecognised`() {
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.archiveKindFor("{}"))
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.archiveKindFor("not json"))
    }

    @Test
    fun `a FlorisBoard extension is recognised by its tag and not its file name`() {
        assertTrue(WMFileTypes.isFlexManifest("""{"${'$'}":"${FlexTheme.FORMAT}"}"""))
        // FlorisBoard's other extension kinds use the same manifest name and
        // hold nothing this app can read, so the name alone must never be enough.
        assertFalse(WMFileTypes.isFlexManifest("""{"${'$'}":"ime.extension.keyboard"}"""))
        assertFalse(WMFileTypes.isFlexManifest("""{"${'$'}":"ime.extension.languagepack"}"""))
        assertFalse(WMFileTypes.isFlexManifest("{}"))
        // And a flex manifest is not one of the app's own archive formats.
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.archiveKindFor("""{"${'$'}":"${FlexTheme.FORMAT}"}"""),
        )
    }
}

class VocabImportSniffTest {

    private val pack = com.wasimaster.wmkeyboard.core.vocab.VocabPack(
        com.wasimaster.wmkeyboard.core.vocab.VocabPackMeta(id = "mine", name = "Mine"),
        listOf(com.wasimaster.wmkeyboard.core.vocab.VocabWord("abhor")),
    )

    @Test
    fun `a tagged vocabulary pack is a vocabulary pack`() {
        val text = com.wasimaster.wmkeyboard.core.vocab.VocabPackFile.encode(pack, 1, "1")
        val opened = WMFileTypes.textKindFor(text, "mine.wmvocab.json")
        assertTrue(opened is WMFileTypes.Opened.Vocabulary)
        assertEquals("abhor", (opened as WMFileTypes.Opened.Vocabulary).pack.words.single().word)
    }

    @Test
    fun `the tag beats a misleading theme name`() {
        val text = com.wasimaster.wmkeyboard.core.vocab.VocabPackFile.encode(pack, 1, "1")
        assertTrue(WMFileTypes.textKindFor(text, "oops.wmtheme.json") is WMFileTypes.Opened.Vocabulary)
    }
}
