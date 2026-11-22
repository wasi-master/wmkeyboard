package com.wasimaster.wmkeyboard.core.icons

import com.wasimaster.wmkeyboard.core.settings.IconSettings
import com.wasimaster.wmkeyboard.ime.ui.buildIconSet
import com.wasimaster.wmkeyboard.ime.ui.toImageVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the screen would have resolved from `core_icons_pack_mine_label`. The
 * store takes the wording as an argument because it has no context of its own,
 * so a plain JVM test can hand it any name.
 */
private const val MINE_NAME = "My icons"

/**
 * The whole chain the keyboard actually walks: an SVG on disk → a parsed
 * document → a Compose vector → the resolved set a slot is looked up in.
 *
 * Worth its own test because each piece passing in isolation says nothing
 * about the precedence rules between them, which is where the user-visible
 * behaviour lives.
 */
class IconPipelineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: IconPackStore

    private val monochrome = """<svg viewBox="0 0 24 24"><path d="M4 4h16v16H4Z"/></svg>"""
    private val coloured = """<svg viewBox="0 0 24 24"><path d="M4 4h16v16H4Z" fill="#ff0000"/></svg>"""

    @Before
    fun setUp() {
        store = IconPackStore(temp.newFolder("iconpacks"))
    }

    @Test
    fun `a parsed svg becomes a sized vector`() {
        val doc = SvgParser.parse("""<svg viewBox="0 0 48 32"><path d="M0 0h48v32H0Z"/></svg>""")!!
        val vector = doc.toImageVector("test")
        assertEquals("test", vector.name)
        // The coordinate space is the file's; the intrinsic size is ours, and
        // never exceeds the 24dp icon box — see SvgVectorsTest.
        assertEquals(48f, vector.viewportWidth, 0f)
        assertEquals(32f, vector.viewportHeight, 0f)
        assertEquals(24f, vector.defaultWidth.value, 0.01f)
        assertEquals(16f, vector.defaultHeight.value, 0.01f)
    }

    @Test
    fun `no pack and no overrides is the stock keyboard`() {
        val set = buildIconSet(IconSettings(), store)
        assertEquals(0, set.size)
        assertNull(set.resolve(IconSlots.KEY_SHIFT))
    }

    @Test
    fun `an active pack supplies its slots and nothing else`() {
        val pack = store.createPack("Rounded")!!
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, monochrome)

        val set = buildIconSet(IconSettings(activePackId = pack.id), store)
        assertNotNull(set.resolve(IconSlots.KEY_SHIFT))
        assertTrue(set.resolve(IconSlots.KEY_SHIFT)!!.monochrome)
        assertNull(set.resolve(IconSlots.KEY_BACKSPACE))
    }

    @Test
    fun `a coloured svg is not tintable`() {
        val pack = store.createPack("Colourful")!!
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, coloured)

        val set = buildIconSet(IconSettings(activePackId = pack.id), store)
        assertFalse(set.resolve(IconSlots.KEY_SHIFT)!!.monochrome)
    }

    @Test
    fun `a per-slot override beats the active pack`() {
        val pack = store.createPack("Rounded")!!
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, coloured)
        val mine = store.mine(MINE_NAME)!!
        store.setIcon(mine.id, IconSlots.KEY_SHIFT, monochrome)

        val set = buildIconSet(
            IconSettings(
                activePackId = pack.id,
                overrides = mapOf(IconSlots.KEY_SHIFT to IconOverrides.packSource(mine.id)),
            ),
            store,
        )
        // The override's icon is monochrome; the pack's is not.
        assertTrue(set.resolve(IconSlots.KEY_SHIFT)!!.monochrome)
    }

    @Test
    fun `a builtin override resolves and stays tintable`() {
        val set = buildIconSet(
            IconSettings(overrides = mapOf(IconSlots.KEY_SHIFT to IconOverrides.builtinSource("Star"))),
            store,
        )
        assertTrue(set.resolve(IconSlots.KEY_SHIFT)!!.monochrome)
    }

    /**
     * The failure mode that matters: a pack deleted while a slot still points
     * at it must fall back to the default, not draw nothing.
     */
    @Test
    fun `an override naming a missing pack falls back`() {
        val set = buildIconSet(
            IconSettings(overrides = mapOf(IconSlots.KEY_SHIFT to IconOverrides.packSource("gone"))),
            store,
        )
        assertNull(set.resolve(IconSlots.KEY_SHIFT))
    }

    @Test
    fun `an override naming a missing builtin falls back`() {
        val set = buildIconSet(
            IconSettings(overrides = mapOf(IconSlots.KEY_SHIFT to IconOverrides.builtinSource("NoSuchIcon"))),
            store,
        )
        assertNull(set.resolve(IconSlots.KEY_SHIFT))
    }

    /**
     * A dangling override must not *unset* what the active pack provides for
     * some other slot — only its own.
     */
    @Test
    fun `a dangling override only affects its own slot`() {
        val pack = store.createPack("Rounded")!!
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, monochrome)
        store.setIcon(pack.id, IconSlots.KEY_BACKSPACE, monochrome)

        val set = buildIconSet(
            IconSettings(
                activePackId = pack.id,
                overrides = mapOf(IconSlots.KEY_SHIFT to IconOverrides.packSource("gone")),
            ),
            store,
        )
        assertNull(set.resolve(IconSlots.KEY_SHIFT))
        assertNotNull(set.resolve(IconSlots.KEY_BACKSPACE))
    }

    // ---- store behaviour ----

    @Test
    fun `setIcon refuses an unknown slot and an unparseable svg`() {
        val pack = store.createPack("Rounded")!!
        assertFalse(store.setIcon(pack.id, "tool.timetravel", monochrome))
        assertFalse(store.setIcon(pack.id, IconSlots.KEY_SHIFT, "not xml"))
        assertTrue(store.pack(pack.id)!!.slots.isEmpty())
    }

    @Test
    fun `removing an icon drops its file and its slot`() {
        val pack = store.createPack("Rounded")!!
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, monochrome)
        assertTrue(store.fileFor(pack.id, IconSlots.KEY_SHIFT)!!.isFile)

        store.removeIcon(pack.id, IconSlots.KEY_SHIFT)
        assertTrue(store.pack(pack.id)!!.slots.isEmpty())
        assertFalse(store.fileFor(pack.id, IconSlots.KEY_SHIFT)!!.exists())
    }

    @Test
    fun `the parse cache refreshes when an icon changes`() {
        val pack = store.createPack("Rounded")!!
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, monochrome)
        assertTrue(store.art(pack.id).getValue(IconSlots.KEY_SHIFT).monochrome)

        store.setIcon(pack.id, IconSlots.KEY_SHIFT, coloured)
        assertFalse(store.art(pack.id).getValue(IconSlots.KEY_SHIFT).monochrome)
    }

    // ---- raster icons (#504) ----

    /** A 10x10 PNG header — enough for the sniff, not for a decoder. */
    private val pngBytes: ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0, 0, 0, 10, 0, 0, 0, 10, 8, 6, 0, 0, 0,
    )

    @Test
    fun `a png is stored under its own extension and read back as raster`() {
        val pack = store.createPack("Rounded")!!
        assertTrue(store.setIcon(pack.id, IconSlots.KEY_SHIFT, pngBytes))

        val file = store.fileFor(pack.id, IconSlots.KEY_SHIFT)!!
        assertEquals("${IconSlots.KEY_SHIFT}.png", file.name)
        val art = store.art(pack.id).getValue(IconSlots.KEY_SHIFT)
        assertTrue(art is IconArt.Raster)
        assertEquals(10, (art as IconArt.Raster).width)
    }

    @Test
    fun `replacing a vector with a picture leaves only one file behind`() {
        val pack = store.createPack("Rounded")!!
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, monochrome)
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, pngBytes)

        // The stale SVG would otherwise shadow the PNG — vectors are tried
        // first — and come back in the next export.
        val dir = store.fileFor(pack.id, IconSlots.KEY_SHIFT)!!.parentFile!!
        assertEquals(
            listOf("${IconSlots.KEY_SHIFT}.png"),
            dir.list()!!.sorted(),
        )
        assertTrue(store.art(pack.id).getValue(IconSlots.KEY_SHIFT) is IconArt.Raster)
    }

    @Test
    fun `the extension comes from the bytes, not from the name`() {
        val pack = store.createPack("Rounded")!!
        // An archive entry calling an SVG a PNG, which is what deciding by
        // name would have stored wrongly.
        assertEquals(IconSlots.KEY_SHIFT, IconPackFile.slotForEntry("icons/${IconSlots.KEY_SHIFT}.png"))
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, monochrome.toByteArray())
        assertEquals("${IconSlots.KEY_SHIFT}.svg", store.fileFor(pack.id, IconSlots.KEY_SHIFT)!!.name)
    }

    @Test
    fun `a raster slot survives a reload`() {
        val pack = store.createPack("Rounded")!!
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, pngBytes)

        store.reload()
        assertEquals(listOf(IconSlots.KEY_SHIFT), store.pack(pack.id)!!.slots)
        assertTrue(store.fileFor(pack.id, IconSlots.KEY_SHIFT)!!.isFile)
    }

    @Test
    fun `a theme's own icons sit under the pack and the overrides`() {
        // An SVG rather than the PNG above: resolving a raster needs a real
        // bitmap decoder, which a plain JVM test has not got. What this is
        // about is the precedence, which is the same either way.
        val file = temp.newFile("themeicon.img").apply { writeText(coloured) }
        val pack = store.createPack("Rounded")!!
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, monochrome)

        // Alone, the theme dresses the slot.
        val themeOnly = buildIconSet(
            IconSettings(themeIcons = mapOf(IconSlots.KEY_SHIFT to file.absolutePath)),
            store,
        )
        assertNotNull(themeOnly.resolve(IconSlots.KEY_SHIFT))

        assertFalse(themeOnly.resolve(IconSlots.KEY_SHIFT)!!.monochrome)

        // With a pack installed, the pack wins: it is a standing choice about
        // the whole keyboard and a theme must not quietly undo one. The pack's
        // icon is the monochrome one, so the flag says which came through.
        val withPack = buildIconSet(
            IconSettings(
                activePackId = pack.id,
                themeIcons = mapOf(IconSlots.KEY_SHIFT to file.absolutePath),
            ),
            store,
        )
        assertTrue(withPack.resolve(IconSlots.KEY_SHIFT)!!.monochrome)
    }

    @Test
    fun `a theme naming its own icon set decides its keys`() {
        val file = temp.newFile("themeicon2.img").apply { writeText(coloured) }
        val global = store.createPack("Global")!!
        store.setIcon(global.id, IconSlots.KEY_SHIFT, monochrome)
        store.setIcon(global.id, IconSlots.KEY_BACKSPACE, monochrome)
        val ownSet = store.createPack("Theme set")!!
        store.setIcon(ownSet.id, IconSlots.KEY_BACKSPACE, coloured)

        // The built-in set: the theme's picture on Shift, and the global pack
        // kept off the keys the theme has no picture for (#1488).
        val builtin = buildIconSet(
            IconSettings(
                activePackId = global.id,
                themeIcons = mapOf(IconSlots.KEY_SHIFT to file.absolutePath),
                themePackId = "",
            ),
            store,
        )
        assertFalse(builtin.resolve(IconSlots.KEY_SHIFT)!!.monochrome)
        assertNull(builtin.resolve(IconSlots.KEY_BACKSPACE))

        // A pack of the theme's own, named by its name as an exported theme
        // arriving on another phone would: its icon, not the global pack's.
        val named = buildIconSet(
            IconSettings(activePackId = global.id, themePackId = "Theme set"),
            store,
        )
        assertFalse(named.resolve(IconSlots.KEY_BACKSPACE)!!.monochrome)

        // A pack this phone does not have leaves the keys to the Icons screen.
        val missing = buildIconSet(
            IconSettings(activePackId = global.id, themePackId = "nowhere"),
            store,
        )
        assertTrue(missing.resolve(IconSlots.KEY_BACKSPACE)!!.monochrome)
    }

    @Test
    fun `a theme icon pointing at nothing leaves the slot alone`() {
        val set = buildIconSet(
            IconSettings(themeIcons = mapOf(IconSlots.KEY_SHIFT to "/does/not/exist.png")),
            store,
        )
        assertNull(set.resolve(IconSlots.KEY_SHIFT))
    }

    @Test
    fun `a slot whose file vanished is dropped on reload`() {
        val pack = store.createPack("Rounded")!!
        store.setIcon(pack.id, IconSlots.KEY_SHIFT, monochrome)
        store.fileFor(pack.id, IconSlots.KEY_SHIFT)!!.delete()

        store.reload()
        assertTrue(store.pack(pack.id)!!.slots.isEmpty())
    }

    @Test
    fun `mine is stable and created once`() {
        val first = store.mine(MINE_NAME)!!
        assertEquals(IconPackStore.MINE_ID, first.id)
        assertEquals(MINE_NAME, first.name)
        // A second call must reuse the pack rather than make another one, even
        // when the caller resolved the name in a different language.
        assertEquals(first.id, store.mine("Meine Symbole")!!.id)
        assertEquals(1, store.packs().count { it.id == IconPackStore.MINE_ID })
        assertTrue(first.isMine)
    }
}
