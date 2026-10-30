package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.feedback.SoundPackFile
import com.wasimaster.wmkeyboard.core.icons.IconPackFile
import com.wasimaster.wmkeyboard.core.layout.KeysCafeLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.whatsapp.WaStickersFile
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
 * is told apart by its file name or the keys every export writes, a known
 * compromise.
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
    fun `a theme is recognised by its name or by the keys every export writes`() {
        val theme = ThemeCodec.encode(
            com.wasimaster.wmkeyboard.core.theme.ThemeSpec(id = "x", name = "X"),
        )
        assertTrue(WMFileTypes.textKindFor(theme, "x.wmtheme.json") is WMFileTypes.Opened.Theme)
        // A chat app's cache or a `msf:` URI loses the name; the export's own
        // keys still say what it is.
        assertTrue(WMFileTypes.textKindFor(theme, "file_1234.json") is WMFileTypes.Opened.Theme)
        assertTrue(WMFileTypes.textKindFor(theme, "") is WMFileTypes.Opened.Theme)
        // Any other JSON object under another name is still nobody's theme.
        assertEquals(
            WMFileTypes.Opened.Unrecognized,
            WMFileTypes.textKindFor("""{"id":"x","name":"X"}""", "x.json"),
        )
    }

    @Test
    fun `a layout from another keyboard is never read as a theme`() {
        // The one test this file exists for. A FlorisBoard or HeliBoard layout
        // carries no tag of ours, so if it reached the theme branch under a
        // .wmtheme.json name it would decode to an all-defaults theme and the
        // user would have imported a black keyboard instead of their grid.
        val foreign = """[[{"${'$'}":"text_key","code":113,"label":"q"}]]"""
        // It is recognised as what it is: a FlorisBoard layout.
        assertTrue(WMFileTypes.textKindFor(foreign, "qwerty.json") is WMFileTypes.Opened.ForeignLayout)
        assertTrue(WMFileTypes.textKindFor(foreign, "qwerty.txt") is WMFileTypes.Opened.ForeignLayout)
        // Even renamed, it is refused: it decodes to a theme that sets nothing,
        // and importing that is worse than importing nothing.
        val renamed = WMFileTypes.textKindFor(foreign, "qwerty.wmtheme.json")
        assertTrue(
            "a foreign layout was read as a theme",
            renamed !is WMFileTypes.Opened.Theme,
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

    // ---- the two YAML formats ----

    @Test
    fun `a FUTO layout is a layout rather than text for the editor`() {
        val yaml = "name: QWERTY\nlanguages: [fr]\nrows:\n  - letters: q w e\n  - letters: a s d"
        val opened = WMFileTypes.textKindFor(yaml, "qwerty.yaml")
        assertTrue("a FUTO layout was not recognised", opened is WMFileTypes.Opened.FutoLayout)
        val converted = (opened as WMFileTypes.Opened.FutoLayout).converted
        assertEquals("QWERTY", converted.layout.name)
        // The language the dialog's picker starts from. A blank one would be
        // migrated to English on the next read.
        assertEquals("fr", converted.guessedLangId)
    }

    @Test
    fun `an Espanso match file is snippets`() {
        val yaml = "matches:\n  - trigger: \":sig\"\n    replace: \"Wasi Master\""
        val opened = WMFileTypes.textKindFor(yaml, "base.yml")
        assertTrue("an Espanso file was not recognised", opened is WMFileTypes.Opened.ForeignSnippets)
        val parsed = (opened as WMFileTypes.Opened.ForeignSnippets).parsed
        assertEquals(1, parsed.snippets.size)
        assertTrue("an Espanso file was read as one of ours", parsed.isEspanso)
    }

    @Test
    fun `a Gboard dictionary is its own kind, not snippets`() {
        val text = "# Gboard Dictionary version:1\nomw\ton my way\ten-US\n\tWasimaster\t\n"
        val opened = WMFileTypes.textKindFor(text, "dictionary.txt")
        assertTrue("a Gboard dictionary was not recognised", opened is WMFileTypes.Opened.GboardDictionaryFile)
        assertEquals(listOf("Wasimaster"), (opened as WMFileTypes.Opened.GboardDictionaryFile).contents.words)
    }

    @Test
    fun `a snippet table needs a header or a csv name`() {
        val headed = WMFileTypes.textKindFor("trigger,text\nomw,on my way\n", "export")
        assertTrue("a headed table was not recognised", headed is WMFileTypes.Opened.ForeignSnippets)
        val named = WMFileTypes.textKindFor("omw,on my way\n", "replies.csv")
        assertTrue("a .csv was not recognised", named is WMFileTypes.Opened.ForeignSnippets)
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor("omw,on my way\n", "notes.txt"))
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor("Name,Phone\nAda,555\n", "people.csv"))
    }

    @Test
    fun `an ordinary YAML file is only text`() {
        // The whole reason the two tests above are a key at the start of a line:
        // everything else that is YAML has to keep falling through to the
        // editor. A CI config and a compose file are the common ones.
        val compose = "services:\n  web:\n    image: nginx\n    ports:\n      - 80:80\n"
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor(compose, "compose.yaml"))
        assertTrue(WMFileTypes.isEditableText(compose))
    }

    @Test
    fun `a name key alone is not a FUTO layout`() {
        // Half the YAML files ever written open with a name. Both keys are
        // wanted, and the one that means something is `rows:`.
        val yaml = "name: my-package\nversion: 1.0.0\ndependencies:\n  - thing\n"
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor(yaml, "package.yaml"))
    }

    // ---- the encrypted format ----

    @Test
    fun `a Keys Cafe grid is a layout, told by its contents`() {
        // The smallest share file: one letter row with a space bar and a
        // delete key, wrapped the way the reader expects.
        val model = """{"keyboards":[{"defaultKeyboard":{"elements":[{"ROW":{"rowType":1,"elements":[
            {"KEY":{"keyAttribute":{"keyType":1},"normalKey":{"keyCodeLabel":{"keyCodes":[113],"keyLabel":"q"}},"size":{"width":0.08}}},
            {"KEY":{"keyAttribute":{"keyType":65540},"normalKey":{"keyCodeLabel":{"keyCodes":[32],"keyLabel":""}},"size":{"width":0.4}}},
            {"KEY":{"keyAttribute":{"keyType":4},"normalKey":{"keyCodeLabel":{"keyCodes":[-5],"keyLabel":""}},"size":{"width":0.12}}}
        ]}}]}}]}"""
        val share = """{"keyboardName":"Mine","languageCode":"en","countryCode":"US","model":${kotlinx.serialization.json.JsonPrimitive(model)}}"""
        val kcf = KeysCafeLayouts.encode(share)
        // The name says nothing: the file is told by what it decrypts to.
        val opened = WMFileTypes.textKindFor(kcf, "Mine.kcf")
        assertTrue("a Keys Cafe grid was not recognised", opened is WMFileTypes.Opened.KeysCafeLayout)
        assertEquals("Mine", (opened as WMFileTypes.Opened.KeysCafeLayout).converted.layout.name)
        assertTrue(WMFileTypes.textKindFor(kcf, "whatever.bin") is WMFileTypes.Opened.KeysCafeLayout)
    }

    @Test
    fun `base64 that is not a Keys Cafe grid is not claimed`() {
        // Looks the part and is not one: it has to fall through rather than be
        // imported as an empty layout, whatever the extension says.
        val blob = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5YWJjZGVmZ2hpamts"
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor(blob, "x.kcf"))
        assertTrue(WMFileTypes.isEditableText(blob))
    }

    // ---- Desktop layouts ----

    @Test
    fun `an XKB symbols file is a desktop layout, told by its contents`() {
        val xkb = """
            |default partial alphanumeric_keys
            |xkb_symbols "basic" {
            |    include "us(basic)"
            |    name[Group1] = "Mine";
            |    key <AC10> { [ odiaeresis, Odiaeresis ] };
            |};
        """.trimMargin()
        // No extension at all, as XKB files have none.
        val opened = WMFileTypes.textKindFor(xkb, "mine")
        assertTrue("an XKB file was not recognised", opened is WMFileTypes.Opened.DesktopLayoutFile)
        assertEquals("Mine", (opened as WMFileTypes.Opened.DesktopLayoutFile).layouts.single().layout.name)
    }

    @Test
    fun `a keylayout is a desktop layout, not an Unexpected Keyboard one`() {
        // Both are XML with a <keyboard> and <key>s; the desktop reader goes first.
        val keys = "qwertyuiopasdfghjklzxcvbnm".mapIndexed { i, c ->
            val code = listOf(12, 13, 14, 15, 17, 16, 32, 34, 31, 35, 0, 1, 2, 3, 5, 4, 38, 40, 37, 6, 7, 8, 9, 11, 45, 46)[i]
            """<key code="$code" output="$c"/>"""
        }.joinToString("\n")
        val xml = """<?xml version="1.1" encoding="UTF-8"?>
            |<keyboard group="126" id="-5" name="Mac Mine" maxout="1">
            |<layouts><layout first="0" last="17" mapSet="m" modifiers="f"/></layouts>
            |<modifierMap id="f" defaultIndex="0"><keyMapSelect mapIndex="0"><modifier keys=""/></keyMapSelect></modifierMap>
            |<keyMapSet id="m"><keyMap index="0">
            |$keys
            |</keyMap></keyMapSet>
            |</keyboard>
        """.trimMargin()
        val opened = WMFileTypes.textKindFor(xml, "mine.keylayout")
        assertTrue("a keylayout was not recognised", opened is WMFileTypes.Opened.DesktopLayoutFile)
        assertEquals("Mac Mine", (opened as WMFileTypes.Opened.DesktopLayoutFile).layouts.single().layout.name)
    }

    // ---- Multiling O ----

    @Test
    fun `a Multiling O DIY layout is told by its onScreen object`() {
        val diy = """{"title":"Pad","onScreen":{"main":["qwe","[SHIFT]asd[DEL]","[SYM:.][SPACE][ENTER]"]}}"""
        val opened = WMFileTypes.textKindFor(diy, "pad.json")
        assertTrue("a DIY layout was not recognised", opened is WMFileTypes.Opened.MultilingFile)
        opened as WMFileTypes.Opened.MultilingFile
        assertEquals("Pad", opened.layouts.single().layout.name)
        assertEquals(null, opened.theme)
    }

    @Test
    fun `a Multiling O theme alone is a theme with no layouts`() {
        val theme = "OK_Settings_Begin\n__V.co0::ff000000::X\n__V.co1::ff333333::X\nOK_Settings_End"
        val opened = WMFileTypes.textKindFor(theme, "theme.txt")
        assertTrue(opened is WMFileTypes.Opened.MultilingFile)
        opened as WMFileTypes.Opened.MultilingFile
        assertTrue(opened.layouts.isEmpty())
        assertEquals(0xFF000000, opened.theme?.theme?.boardBackground)
    }

    @Test
    fun `a page that only mentions the Multiling markers is not claimed`() {
        val prose = "Copy the text from OK_Layout_Begin to OK_Layout_End and paste it."
        assertEquals(WMFileTypes.Opened.Unrecognized, WMFileTypes.textKindFor(prose, "notes.txt"))
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
        assertEquals(
            WMFileTypes.Opened.SoundPack,
            WMFileTypes.archiveKindFor("""{"schemaVersion":1,"sounds":{"keypress.default":{}}}"""),
        )
    }

    @Test
    fun `a WhatsApp pack is told by its two text files, never by pictures alone`() {
        // The one archive format with no manifest. Its signature is the
        // title.txt / author.txt beside the pictures; a ZIP of pictures with
        // neither is any ZIP of pictures, and a pack.json makes it ours.
        assertTrue(WaStickersFile.looksLikeWaStickers(listOf("title.txt", "author.txt", "tray.png", "sticker_1.webp")))
        assertFalse(WaStickersFile.looksLikeWaStickers(listOf("tray.png", "sticker_1.webp")))
        assertFalse(WaStickersFile.looksLikeWaStickers(listOf("pack.json", "title.txt", "sticker_1.webp")))
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

    @Test
    fun `a Gboard theme and an Rboard pack are recognised by their manifests`() {
        assertTrue(WMFileTypes.isGboardManifest("""{"name":"Dusk","style_sheets":["style_sheet_md2.css"]}"""))
        assertTrue(WMFileTypes.isGboardManifest("name=Gradients\r\nauthor=RKBDI"))
        // One of this app's own pack manifests names a pack too, as JSON.
        assertFalse(WMFileTypes.isGboardManifest("""{"format":"wm-sticker-pack","name":"x"}"""))
        assertFalse(WMFileTypes.isGboardManifest("""{"${'$'}":"${FlexTheme.FORMAT}"}"""))
        assertFalse(WMFileTypes.isGboardManifest("just some text"))
    }

    @Test
    fun `a sound pack is told apart from the other pack_json formats`() {
        // Sticker, icon and sound packs all name their manifest pack.json, so
        // the format tag is the whole decision. A sound pack sent to the sticker
        // importer would be refused with a message about stickers.
        assertEquals(
            WMFileTypes.Opened.SoundPack,
            WMFileTypes.archiveKindFor("""{"format":"${SoundPackFile.FORMAT}","id":"click"}"""),
        )
        assertEquals(
            WMFileTypes.Opened.Icons,
            WMFileTypes.archiveKindFor("""{"format":"${IconPackFile.FORMAT}"}"""),
        )
    }

    // ---- text that is nobody's ----

    @Test
    fun `someone else's JSON opens in the editor rather than on an error`() {
        // The app is offered for every .json now, so this is the common case
        // rather than a mistake, and it has to end somewhere useful.
        val opened = WMFileTypes.textKindFor("""{"services":{"web":{"image":"nginx"}}}""", "compose.json")
        assertEquals(WMFileTypes.Opened.Unrecognized, opened)
        assertTrue(WMFileTypes.isEditableText("""{"services":{}}"""))
    }

    @Test
    fun `broken JSON is still text`() {
        // Half-typed or truncated: worth opening precisely because it is broken.
        assertTrue(WMFileTypes.isEditableText("""{"a": 1,"""))
    }

    @Test
    fun `a binary file is not offered to the editor`() {
        // decodeToString turns every unreadable byte into U+FFFD rather than
        // failing, so without this a PNG opens as a screen of garbage.
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13).decodeToString()
        assertFalse(WMFileTypes.isEditableText(png))
        assertFalse(WMFileTypes.isEditableText(""))
    }

    @Test
    fun `one odd byte does not condemn a readable file`() {
        // A stray control character in an otherwise readable config is not a
        // reason to refuse it, so the test is a share of the whole.
        assertTrue(WMFileTypes.isEditableText("key = value\u0007\n" + "line\n".repeat(50)))
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
