package com.wasimaster.wmkeyboard.core.gesture

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [GlideShapeStore]'s binary file, its JSON fallback and its backup form (issue #1130). */
class GlideShapeFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val layout = 0x5e1fL
    private val landscape = -0x2a3bL

    private fun bin(): File = File(temp.root, "learning/glide_shapes.bin")
    private fun legacy(): File = File(temp.root, "learning/glide_shapes.json")
    private fun bak(): File = File(temp.root, "learning/glide_shapes.bin.bak")
    private fun corrupt(): File = File(temp.root, "learning/glide_shapes.bin.corrupt")

    private fun flat(value: Int): ByteArray = ByteArray(GlideShapeStore.POINTS) { value.toByte() }

    /** A shape whose coordinates differ point to point, so a byte out of place shows. */
    private fun ramp(start: Int): ByteArray = ByteArray(GlideShapeStore.POINTS) { (start + it % 7).toByte() }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** A store with words on two layouts, one of them with two ways of drawing it, saved to [bin]. */
    private fun savedStore(): GlideShapeStore {
        val store = GlideShapeStore(bin())
        store.learn(GlideShapeSample(layout, ramp(10)), "hello")
        store.learn(GlideShapeSample(layout, ramp(60)), "hello")
        store.learn(GlideShapeSample(layout, ramp(-20)), "naïve")
        store.learn(GlideShapeSample(landscape, flat(5)), "world")
        store.save()
        return store
    }

    private fun assertSavedContent(store: GlideShapeStore) {
        assertEquals(2, store.countFor("hello"))
        assertEquals(1, store.countFor("naïve"))
        assertEquals(1, store.countFor("world"))
        val shapes = store.forLayout(layout)!!.shapesOf("hello")
        assertTrue(shapes.any { it.contentEquals(ramp(10)) })
        assertTrue(shapes.any { it.contentEquals(ramp(60)) })
        assertArrayEquals(flat(5), store.forLayout(landscape)!!.shapesOf("world").single())
    }

    @Test
    fun `a saved store is binary and reads back whole`() {
        savedStore()
        val bytes = bin().readBytes()
        assertEquals("WMKS", String(bytes, 0, 4, Charsets.US_ASCII))
        assertSavedContent(GlideShapeStore(bin()))
    }

    @Test
    fun `the same store always writes the same bytes`() {
        savedStore()
        val first = bin().readBytes()
        // Decoded and encoded again, in whatever order the maps now iterate.
        assertArrayEquals(first, GlideShapeBinary.encode(GlideShapeBinary.decode(first)!!))
        // And a store read back and saved again writes it unchanged.
        val exported = GlideShapeStore.exportJson(bin())!!
        val copy = File(temp.root, "copy/glide_shapes.bin")
        assertTrue(GlideShapeStore.importJson(exported, copy))
        assertArrayEquals(first, copy.readBytes())
    }

    @Test
    fun `the binary file is about half the size of the json it replaces`() {
        savedStore()
        val json = GlideShapeStore.exportJson(bin())!!
        assertTrue(bin().length() * 3 < json.toByteArray().size * 2)
    }

    @Test
    fun `a damaged byte anywhere is caught, set aside and the previous generation read`() {
        val store = savedStore()
        // A second save rotates the first into .bak.
        store.learn(GlideShapeSample(layout, ramp(30)), "later")
        store.save()
        assertTrue(bak().exists())
        val bytes = bin().readBytes()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3] + 1).toByte()
        bin().writeBytes(bytes)

        val reopened = GlideShapeStore(bin())
        // The previous generation: everything but the last word.
        assertSavedContent(reopened)
        assertEquals(0, reopened.countFor("later"))
        assertTrue(corrupt().exists())
        assertFalse(bin().exists())
        // And the next save writes a good live file again.
        reopened.save()
        assertEquals(1, GlideShapeStore(bin()).countFor("naïve"))
    }

    @Test
    fun `a damaged file with no previous generation starts empty without throwing`() {
        savedStore()
        val bytes = bin().readBytes()
        // In the header: the tick, which the digest covers too.
        bytes[20] = (bytes[20] + 1).toByte()
        bin().writeBytes(bytes)
        assertTrue(GlideShapeStore(bin()).isEmpty())
        assertTrue(corrupt().exists())
    }

    @Test
    fun `truncated, unknown-version and garbage files are refused`() {
        val good = savedStore().let { bin().readBytes() }
        for (bad in listOf(
            good.copyOf(good.size - 1),
            good.copyOf(40),
            good.copyOf().also { it[5] = 2 },
            "WMKS not really".toByteArray(),
            ByteArray(500) { 7 },
        )) {
            assertNull(GlideShapeBinary.decode(bad))
            bin().writeBytes(bad)
            bak().delete()
            assertTrue(GlideShapeStore(bin()).isEmpty())
        }
    }

    @Test
    fun `a v2 json store beside the binary path is migrated once and then removed`() {
        legacy().parentFile?.mkdirs()
        // As 0.6.4 wrote it: defaults left out.
        legacy().writeText("""{"layouts":{"5e1f":{"hello":{"s":[{"p":"${hex(ramp(10))}"}]}}}}""")
        val store = GlideShapeStore(bin())
        assertEquals(1, store.countFor("hello"))
        assertFalse(bin().exists())
        store.save()
        assertTrue(GlideShapeBinary.isBinary(bin().readBytes()))
        assertFalse(legacy().exists())
        val reopened = GlideShapeStore(bin())
        assertArrayEquals(ramp(10), reopened.forLayout(layout)!!.shapesOf("hello").single())
    }

    @Test
    fun `a json store at the store's own path still loads and is rewritten as binary`() {
        val file = legacy()
        file.parentFile?.mkdirs()
        file.writeText("""{"v":2,"tick":3,"layouts":{"5e1f":{"hello":{"t":3,"s":[{"p":"${hex(ramp(10))}","a":2,"r":0}]}}}}""")
        val store = GlideShapeStore(file)
        assertEquals(1, store.countFor("hello"))
        store.save()
        assertTrue(GlideShapeBinary.isBinary(file.readBytes()))
        assertEquals(1, GlideShapeStore(file).countFor("hello"))
    }

    @Test
    fun `a backup carries json that restores into a binary store`() {
        savedStore()
        val json = GlideShapeStore.exportJson(bin())
        assertNotNull(json)
        assertTrue(json!!.trimStart().startsWith("{"))

        val restored = File(temp.root, "other/learning/glide_shapes.bin")
        assertTrue(GlideShapeStore.importJson(json, restored))
        assertTrue(GlideShapeBinary.isBinary(restored.readBytes()))
        assertSavedContent(GlideShapeStore(restored))
    }

    @Test
    fun `a backup that is not a v2 snapshot replaces nothing`() {
        savedStore()
        val before = bin().readBytes()
        assertFalse(GlideShapeStore.importJson("not json", bin()))
        assertFalse(GlideShapeStore.importJson("""{"v":3,"layouts":{}}""", bin()))
        assertArrayEquals(before, bin().readBytes())
    }

    @Test
    fun `an empty store exports nothing`() {
        assertNull(GlideShapeStore.exportJson(bin()))
    }

    @Test
    fun `clear deletes the file, its sidecars and the old json`() {
        val store = savedStore()
        store.learn(GlideShapeSample(layout, ramp(30)), "later")
        store.save()
        corrupt().writeText("x")
        legacy().writeText("{}")
        store.clear()
        for (f in listOf(bin(), bak(), corrupt(), legacy())) assertFalse(f.name, f.exists())
        assertTrue(GlideShapeStore(bin()).isEmpty())
    }
}
