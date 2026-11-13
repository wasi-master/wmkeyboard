package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.language.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reading CLDR keyboard3 (LDML) layouts, in CLDR's own spelling and in
 * FlorisBoard's.
 *
 * The fixtures under `resources/keyboard3/` are hand-written in the published
 * format, not copied from either project. A folder of real files (CLDR's
 * `keyboards/3.0`, FlorisBoard's extensions) is swept only when
 * `KEYBOARD3_LAYOUTS` points at it.
 */
class Keyboard3LayoutTest {

    // ---- fixtures ----

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("keyboard3/$name")) { "missing fixture $name" }
            .use { it.readBytes().decodeToString() }

    private fun converted(name: String): ConvertedLayout =
        checkNotNull(Keyboard3Layouts.convert(fixture(name), name)) { "$name did not convert" }

    private fun ConvertedLayout.layer(key: String): LayerSpec = checkNotNull(layout.layers[key]) { "no layer $key" }
    private fun ConvertedLayout.letters(): List<Key> = layer(LayoutLayer.LETTERS.key).rows.flatten()
    private fun ConvertedLayout.letter(typed: String): Key = letters().first { (it.output ?: it.label) == typed }
    private fun ConvertedLayout.noteCount(plural: Int): Int? = notes.firstOrNull { it.pluralsRes == plural }?.quantity

    // ---- sniffing ----

    @Test
    fun `sniffs the keyboard3 root and nothing else`() {
        assertTrue(Keyboard3Layouts.looksLikeKeyboard3(fixture("latin_touch.xml")))
        assertTrue(Keyboard3Layouts.looksLikeKeyboard3(fixture("floris_qwertz.xml")))
        assertFalse(Keyboard3Layouts.looksLikeKeyboard3("""<keyboard name="x"><row><key c="a"/></row></keyboard>"""))
        assertFalse(Keyboard3Layouts.looksLikeKeyboard3("""<keyboard3s/>"""))
        assertTrue(Keyboard3Layouts.isExtensionManifest("""{"${'$'}": "ime.extension.keyboard3", "meta": {}}"""))
        assertFalse(Keyboard3Layouts.isExtensionManifest("""{"${'$'}": "ime.extension.theme"}"""))
    }

    @Test
    fun `a DOCTYPE that could declare entities is refused`() {
        val evil = """
            <?xml version="1.0"?>
            <!DOCTYPE keyboard3 [ <!ENTITY x "a"> ]>
            <keyboard3 locale="en" conformsTo="45"><info name="x"/>
              <layers formId="touch"><layer id="base"><row keys="a b c"/></layer></layers>
            </keyboard3>
        """.trimIndent()
        assertNull(Keyboard3Layouts.convert(evil, "evil.xml"))
    }

    @Test
    fun `a file with no layers converts to nothing`() {
        assertNull(Keyboard3Layouts.convert("""<keyboard3 locale="en" conformsTo="45"><info name="x"/></keyboard3>""", "x.xml"))
        assertNull(Keyboard3Layouts.convert("not xml at all", "x.xml"))
    }

    // ---- a Latin touch layout ----

    @Test
    fun `touch layout keeps its name, language and source`() {
        val c = converted("latin_touch.xml")
        assertEquals("Test AZERTY", c.layout.name)
        assertEquals("fr", c.guessedLangId)
        assertEquals(ForeignSource.KEYBOARD3, c.source)
    }

    @Test
    fun `the set for the narrowest device is read`() {
        // The 150 mm set starts with q; the 1 mm set, a phone's, with a.
        val first = converted("latin_touch.xml").layer(LayoutLayer.LETTERS.key).rows.first()
        assertEquals("a", first.first().output ?: first.first().label)
    }

    @Test
    fun `long press puts the default first, and multi-tap comes across`() {
        val c = converted("latin_touch.xml")
        assertEquals(listOf("é", "è", "ê"), c.letter("e").longPress)
        val superTwo = c.layer(LayoutLayer.SYMBOLS.key).rows.flatten().first { (it.output ?: it.label) == "²" }
        assertEquals(listOf("₂", "2"), superTwo.multitap)
    }

    @Test
    fun `one-direction flicks become arms and a path is counted`() {
        val c = converted("latin_touch.xml")
        val a = c.letter("a")
        assertEquals("@", a.flick[FlickDirection.UP])
        assertEquals(KeyAction.Symbols, a.flickActions[FlickDirection.DOWN]?.action)
        assertTrue(a.hideHint)
        assertEquals(1, c.noteCount(R.plurals.core_lang_foreign_k3_flick_paths_dropped))
    }

    @Test
    fun `the shift layer folds into shift letters and its key becomes shift`() {
        val c = converted("latin_touch.xml")
        // é's capital is not what shift makes of the key it shares a place with.
        assertEquals("É", c.letters().first { it.label == "´" }.shiftLabel)
        assertNull(c.letter("a").shiftLabel)
        assertTrue(c.letters().any { it.action == KeyAction.Shift })
        assertNull(c.layout.layers.keys.firstOrNull { it.contains("shift") && it != LayoutLayer.SYMBOLS_SHIFTED.key })
    }

    @Test
    fun `a layer the base reaches is the symbols page, labelled by its display`() {
        val c = converted("latin_touch.xml")
        val symbols = c.layer(LayoutLayer.SYMBOLS.key).rows.flatten()
        assertTrue(symbols.any { it.label == "1" })
        // A display of a plain @ is a label, not one of FlorisBoard's @fl: references.
        assertTrue(symbols.any { it.action == KeyAction.Letters && it.label == "@" })
        assertTrue(c.letters().any { it.action == KeyAction.Symbols })
    }

    @Test
    fun `a stretched space takes what its row leaves`() {
        val c = converted("latin_touch.xml")
        val space = c.layer(LayoutLayer.SYMBOLS.key).rows.flatten().first { it.action == KeyAction.Space }
        // Row "base comma space period": 1.5 + 1 + 1 leaves 6.5 of the widest row's 10.
        assertEquals(6.5f, space.width, 0.01f)
    }

    // ---- layers and transforms ----

    @Test
    fun `layers land on symbols, the second symbols page and pages of their own`() {
        val c = converted("layers_transforms.xml")
        val keys = c.layout.layers.keys
        assertTrue(LayoutLayer.SYMBOLS.key in keys)
        assertTrue(LayoutLayer.SYMBOLS_SHIFTED.key in keys)
        assertTrue("k3:pad" in keys)
        assertFalse(keys.any { it.contains("unreached") })
        val symbols = c.layer(LayoutLayer.SYMBOLS.key).rows.flatten()
        assertTrue(symbols.any { it.action == KeyAction.LayerSwitch("k3:pad") })
        assertTrue(symbols.any { it.action == KeyAction.Delete })
    }

    @Test
    fun `a marker whose rules add one accent is a dead key`() {
        val c = converted("layers_transforms.xml")
        val grave = c.letters().first { it.output == "̀" }
        assertEquals("`", grave.label)
        // ² is not an accented 2, so it waits on press and hold.
        assertEquals(listOf("²"), grave.longPress)
    }

    @Test
    fun `a marker with no accent types its first result and holds the rest`() {
        val c = converted("layers_transforms.xml")
        val currency = c.letters().first { it.label == "€" }
        assertEquals(listOf("₤", "¥", "¤"), currency.longPress)
    }

    @Test
    fun `every rule is counted as dead key, press and hold or left out`() {
        val c = converted("layers_transforms.xml")
        assertEquals(2, c.noteCount(R.plurals.core_lang_foreign_k3_transforms_dead_keys))
        assertEquals(4, c.noteCount(R.plurals.core_lang_foreign_k3_transforms_on_hold))
        assertEquals(2, c.noteCount(R.plurals.core_lang_foreign_k3_transforms_dropped))
        assertEquals(2, c.noteCount(R.plurals.core_lang_foreign_k3_reorder_dropped))
        assertEquals(1, c.noteCount(R.plurals.core_lang_foreign_k3_backspace_dropped))
    }

    @Test
    fun `FlorisBoard actions become keys, and one with no key here is named`() {
        val c = converted("layers_transforms.xml")
        assertTrue(c.letters().any { it.action == KeyAction.Delete })
        assertTrue(c.letters().any { it.action == KeyAction.Enter })
        val dropped = c.notes.first {
            it.pluralsRes == R.plurals.core_lang_foreign_labels_dropped
        }
        assertTrue(dropped.args[1].toString().contains("@fl:action/toggle_actions_overflow"))
    }

    @Test
    fun `a shift layer laid out differently keeps capitals and is counted`() {
        val c = converted("layers_transforms.xml")
        assertEquals(1, c.noteCount(R.plurals.core_lang_foreign_shifted_pages_dropped))
    }

    // ---- a hardware-only layout ----

    @Test
    fun `a computer layout folds like a desktop file`() {
        val c = converted("hardware_iso.xml")
        assertEquals("Hardware only", c.layout.name)
        assertEquals(ForeignSource.KEYBOARD3, c.source)
        assertEquals("pcm", c.guessedLangId)
        // The letter after l on the home row is kept, with its capital.
        val odot = c.letter("ọ")
        assertNull(odot.shiftLabel)
        // The marker's accent rules land on the vowels' press and hold.
        assertTrue("á" in c.letter("a").longPress)
        assertTrue("ú" in c.letter("u").longPress)
        // AltGr's naira goes on the full stop, since its key is not on the grid.
        assertTrue("₦" in c.letters().first { (it.output ?: it.label) == "." }.longPress)
    }

    @Test
    fun `a Caps Lock page and an unknown import are named`() {
        val c = converted("hardware_iso.xml")
        assertEquals(1, c.noteCount(R.plurals.core_lang_foreign_k3_layers_dropped))
        val missing = c.notes.first {
            it.pluralsRes == R.plurals.core_lang_foreign_desktop_includes_missing
        }
        assertTrue(missing.args[1].toString().contains("99/keys-Zzzz-unknown.xml"))
    }

    // ---- FlorisBoard ----

    @Test
    fun `a FlorisBoard file reads its actions and leaves its fn slot to this keyboard`() {
        val c = converted("floris_qwertz.xml")
        assertEquals("Test QWERTZ", c.layout.name)
        assertEquals("de", c.guessedLangId)
        assertTrue(c.letters().any { it.action == KeyAction.Delete })
        assertTrue(c.letters().any { it.action == KeyAction.Enter })
        assertFalse(c.letters().any { it.action == KeyAction.None && it.label.isEmpty() })
        assertTrue(c.letters().any { it.action == KeyAction.Symbols && it.label == "?123" })
        // Its own import of another extension's file, which did not come along.
        val missing = c.notes.first {
            it.pluralsRes == R.plurals.core_lang_foreign_desktop_includes_missing
        }
        assertEquals("flex://test.extension/extra-keys.xml", missing.args[1])
    }

    @Test
    fun `a FlorisBoard extension imports with its own files`() {
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                fun put(name: String, text: String) {
                    out.putNextEntry(ZipEntry(name))
                    out.write(text.toByteArray())
                    out.closeEntry()
                }
                put("extension.json", """{"${'$'}": "ime.extension.keyboard3", "meta": {"id": "test.extension"}}""")
                put("keyboard/qwertz.xml", fixture("floris_qwertz.xml"))
                put("import/extra-keys.xml", fixture("floris_extra_keys.xml"))
            }
        }.toByteArray()
        val layouts = Keyboard3Layouts.convertArchive(ByteArrayInputStream(zip))
        assertEquals(1, layouts.size)
        val c = layouts.single()
        assertEquals(listOf("ä"), c.letter("a").longPress)
        assertFalse(
            c.notes.any {
                it.pluralsRes == R.plurals.core_lang_foreign_desktop_includes_missing
            },
        )
    }

    // ---- every result is a layout that repair leaves alone ----

    @Test
    fun `every fixture converts to a repaired layout`() {
        for (name in listOf("latin_touch.xml", "layers_transforms.xml", "hardware_iso.xml", "floris_qwertz.xml")) {
            val c = converted(name)
            assertNotNull(c.layout.layers[LayoutLayer.LETTERS.key])
            assertTrue("$name repairs again", c.layout.repair().repairNotes.isEmpty())
            assertFalse(c.letters().any { it.action is KeyAction.Unknown })
        }
    }

    /** CLDR's `keyboards/3.0` or FlorisBoard's extensions, when `KEYBOARD3_LAYOUTS` names a folder of them. */
    @Test
    fun `real files convert`() {
        val dir = System.getenv("KEYBOARD3_LAYOUTS")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
        val failures = files.filter { file ->
            val text = file.readText()
            Keyboard3Layouts.looksLikeKeyboard3(text) && Keyboard3Layouts.convert(text, file.name) == null
        }
        assertTrue("did not convert: ${failures.map { it.name }}", failures.isEmpty())
    }
}
