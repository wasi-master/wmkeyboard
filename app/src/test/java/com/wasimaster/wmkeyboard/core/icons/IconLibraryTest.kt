package com.wasimaster.wmkeyboard.core.icons

import com.wasimaster.wmkeyboard.core.settings.IconSettings
import com.wasimaster.wmkeyboard.ime.ui.buildIconSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Your icons: pictures kept apart from the slots that wear them, so a slot that
 * moves off one can come back to it (the tester's report behind it).
 */
class IconLibraryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var library: IconLibrary

    private val square = """<svg viewBox="0 0 24 24"><path d="M4 4h16v16H4Z"/></svg>""".toByteArray()
    private val red = """<svg viewBox="0 0 24 24"><path d="M4 4h16v16H4Z" fill="#ff0000"/></svg>""".toByteArray()

    @Before
    fun setUp() {
        dir = temp.newFolder(IconLibrary.DIR_NAME)
        library = IconLibrary(dir)
    }

    private fun add(bytes: ByteArray, name: String = "pic", now: Long = 1L): LibraryIcon =
        (library.add(bytes, name, now) as LibraryAddResult.Added).icon

    @Test
    fun `a picture is stored under the hash of its bytes`() {
        val icon = add(square)
        assertEquals(IconLibrary.idFor(square), icon.id)
        assertTrue(IconLibrary.isId(icon.id))
        assertEquals("svg", icon.extension)
        assertNotNull(library.fileFor(icon.id))
    }

    @Test
    fun `the same bytes twice keep one entry and refresh its use time`() {
        val first = add(square, now = 1L)
        val again = library.add(square, "other name", now = 5L) as LibraryAddResult.Added
        assertTrue(again.existed)
        assertEquals(first.id, again.icon.id)
        assertEquals(1, library.icons().size)
        assertEquals(5L, library.icon(first.id)?.usedAt)
        assertEquals("pic", library.icon(first.id)?.name)
    }

    @Test
    fun `a file that is no icon is refused and nothing is written`() {
        assertEquals(LibraryAddResult.NotAnIcon, library.add("hello".toByteArray(), "x"))
        assertTrue(library.icons().isEmpty())
        assertTrue(dir.listFiles().orEmpty().none { it.name != IconLibrary.MANIFEST })
    }

    @Test
    fun `most recently used comes first`() {
        val a = add(square, now = 1L)
        val b = add(red, now = 2L)
        assertEquals(listOf(b.id, a.id), library.icons().map { it.id })
        library.touch(a.id, now = 3L)
        assertEquals(listOf(a.id, b.id), library.icons().map { it.id })
    }

    @Test
    fun `it survives a reload`() {
        val icon = add(square, name = "Square")
        library.setKeepColours(icon.id, true)
        val reread = IconLibrary(dir)
        assertEquals("Square", reread.icon(icon.id)?.name)
        assertEquals(true, reread.icon(icon.id)?.keepColours)
        assertNotNull(reread.art(icon.id))
    }

    @Test
    fun `a reload drops entries whose file is gone and files nothing names`() {
        val icon = add(square)
        File(dir, "${icon.id}.svg").delete()
        File(dir, "stray.png").writeBytes(byteArrayOf(1, 2, 3))
        val reread = IconLibrary(dir)
        assertNull(reread.icon(icon.id))
        assertFalse(File(dir, "stray.png").exists())
    }

    @Test
    fun `delete removes the file`() {
        val icon = add(square)
        library.delete(icon.id)
        assertNull(library.icon(icon.id))
        assertFalse(File(dir, "${icon.id}.svg").exists())
    }

    @Test
    fun `rename keeps a blank name from replacing the old one`() {
        val icon = add(square, name = "Old")
        library.rename(icon.id, "   ")
        assertEquals("Old", library.icon(icon.id)?.name)
        library.rename(icon.id, "New")
        assertEquals("New", library.icon(icon.id)?.name)
    }

    @Test
    fun `the colour choice overrules the guess`() {
        val mono = IconArt.Vector(SvgParser.parse(square.decodeToString())!!)
        val icon = add(square)
        assertTrue(IconLibrary.monochromeOf(icon, mono))
        assertFalse(IconLibrary.monochromeOf(icon.copy(keepColours = true), mono))
        val colour = IconArt.Vector(SvgParser.parse(red.decodeToString())!!)
        assertTrue(IconLibrary.monochromeOf(icon.copy(keepColours = false), colour))
    }

    @Test
    fun `a library source round trips through the overrides`() {
        val id = IconLibrary.idFor(square)
        val map = mapOf(IconSlots.KEY_ENTER_SEND to IconOverrides.librarySource(id))
        val decoded = IconOverrides.decode(IconOverrides.encode(map))
        assertEquals(map, decoded)
        assertEquals(id, IconOverrides.libraryIdOf(decoded[IconSlots.KEY_ENTER_SEND]))
        assertNull(IconOverrides.libraryIdOf(IconOverrides.packSource("x")))
    }

    @Test
    fun `a slot pinned to a library picture draws it, and falls back once it is deleted`() {
        val store = IconPackStore(temp.newFolder("iconpacks"))
        val icon = add(red)
        val settings = IconSettings(
            overrides = mapOf(IconSlots.KEY_ENTER_SEND to IconOverrides.librarySource(icon.id)),
        )
        val drawn = buildIconSet(settings, store, library).resolve(IconSlots.KEY_ENTER_SEND)
        assertNotNull(drawn)
        assertFalse("a red picture keeps its colours", drawn!!.monochrome)

        library.setKeepColours(icon.id, false)
        assertTrue(buildIconSet(settings, store, library).resolve(IconSlots.KEY_ENTER_SEND)!!.monochrome)

        library.delete(icon.id)
        assertNull(buildIconSet(settings, store, library).resolve(IconSlots.KEY_ENTER_SEND))
    }
}
