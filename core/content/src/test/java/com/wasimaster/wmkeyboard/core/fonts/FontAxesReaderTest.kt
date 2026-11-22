package com.wasimaster.wmkeyboard.core.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The `fvar` reader against fonts built byte by byte here: only the table
 * directory, `fvar` and `name` are read, so a font needs nothing else to test.
 */
class FontAxesReaderTest {

    private class Axis(val tag: String, val min: Float, val def: Float, val max: Float, val nameId: Int, val hidden: Boolean = false)

    private fun fixed(v: Float) = (v * 65536f).toInt()

    /** A TrueType file with the given tables, in order, offsets laid out after the directory. */
    private fun sfnt(tables: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(0x00010000)
        d.writeShort(tables.size)
        d.writeShort(0); d.writeShort(0); d.writeShort(0)
        var offset = 12 + tables.size * 16
        for ((tag, bytes) in tables) {
            d.writeBytes(tag)
            d.writeInt(0) // checksum, not read
            d.writeInt(offset)
            d.writeInt(bytes.size)
            offset += bytes.size
        }
        for ((_, bytes) in tables) d.write(bytes)
        return out.toByteArray()
    }

    private fun fvar(axes: List<Axis>, instances: List<Pair<Int, List<Float>>>): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        val axisCount = axes.size
        d.writeShort(1); d.writeShort(0)
        d.writeShort(16) // axes array offset
        d.writeShort(2)
        d.writeShort(axisCount); d.writeShort(20)
        d.writeShort(instances.size); d.writeShort(4 + axisCount * 4)
        for (a in axes) {
            d.writeBytes(a.tag)
            d.writeInt(fixed(a.min)); d.writeInt(fixed(a.def)); d.writeInt(fixed(a.max))
            d.writeShort(if (a.hidden) 1 else 0)
            d.writeShort(a.nameId)
        }
        for ((nameId, coords) in instances) {
            d.writeShort(nameId); d.writeShort(0)
            for (c in coords) d.writeInt(fixed(c))
        }
        return out.toByteArray()
    }

    /** A `name` table of Windows English records. */
    private fun names(entries: Map<Int, String>): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        val strings = entries.mapValues { it.value.toByteArray(Charsets.UTF_16BE) }
        d.writeShort(0); d.writeShort(entries.size); d.writeShort(6 + entries.size * 12)
        var at = 0
        for ((id, bytes) in strings) {
            d.writeShort(3); d.writeShort(1); d.writeShort(0x409); d.writeShort(id)
            d.writeShort(bytes.size); d.writeShort(at)
            at += bytes.size
        }
        for (bytes in strings.values) d.write(bytes)
        return out.toByteArray()
    }

    private val flexLike = sfnt(
        listOf(
            "fvar" to fvar(
                listOf(
                    Axis("wght", 100f, 400f, 1000f, 256),
                    Axis("wdth", 25f, 100f, 151f, 257),
                    Axis("GRAD", -200f, 0f, 150f, 258, hidden = true),
                ),
                listOf(258 + 1 to listOf(700f, 100f, 0f), 260 to listOf(400f, 25f, 0f)),
            ),
            "name" to names(mapOf(256 to "Weight", 257 to "Width", 258 to "Grade", 259 to "Bold", 260 to "Compressed")),
        ),
    )

    @Test
    fun `axes, their ranges and names are read`() {
        val v = FontAxesReader.read(flexLike)!!
        assertTrue(v.isVariable)
        assertEquals(listOf("wght", "wdth", "GRAD"), v.axes.map { it.tag })
        val wght = v.axis("wght")!!
        assertEquals(100f, wght.min)
        assertEquals(400f, wght.default)
        assertEquals(1000f, wght.max)
        assertEquals("Weight", wght.name)
        assertTrue(v.axis("GRAD")!!.hidden)
        assertFalse(wght.hidden)
    }

    @Test
    fun `named instances carry one coordinate per axis`() {
        val v = FontAxesReader.read(flexLike)!!
        assertEquals(listOf("Bold", "Compressed"), v.instances.map { it.name })
        assertEquals(700f, v.instances[0].coords.getValue("wght"))
        assertEquals(25f, v.instances[1].coords.getValue("wdth"))
    }

    @Test
    fun `a font with no fvar is static, not unreadable`() {
        val static = sfnt(listOf("name" to names(mapOf(1 to "Plain"))))
        val v = FontAxesReader.read(static)
        assertEquals(FontVariations.STATIC, v)
        assertFalse(v!!.isVariable)
    }

    @Test
    fun `a file that is not a font reads as nothing`() {
        assertNull(FontAxesReader.read("<html>nope</html>".toByteArray()))
        assertNull(FontAxesReader.read(ByteArray(3)))
    }

    @Test
    fun `a truncated fvar reads as static rather than throwing`() {
        val whole = flexLike
        val cut = whole.copyOf(12 + 2 * 16 + 20)
        val v = FontAxesReader.read(cut)
        assertTrue(v == null || !v.isVariable || v.axes.size < 3)
    }

    @Test
    fun `an axis with no name falls back to the registered name`() {
        val font = sfnt(listOf("fvar" to fvar(listOf(Axis("opsz", 8f, 14f, 144f, 999)), emptyList())))
        assertEquals("Optical size", FontAxesReader.read(font)!!.axis("opsz")!!.name)
    }

    @Test
    fun `clamp holds values to the axes and drops unknown tags`() {
        val v = FontAxesReader.read(flexLike)!!
        val held = v.clamp(mapOf("GRAD" to -50f, "wdth" to 100f, "XXXX" to 1f, "wght" to 5000f))
        assertEquals(mapOf("wght" to 1000f, "wdth" to 100f, "GRAD" to -50f), held)
        // In the font's own axis order, so the signature is stable.
        assertEquals(listOf("wght", "wdth", "GRAD"), held.keys.toList())
    }

    @Test
    fun `an untuned weight rests at the weight asked for, other axes at their default`() {
        val v = FontAxesReader.read(flexLike)!!
        assertEquals(400f, v.restValue("wght"))
        assertEquals(700f, v.restValue("wght", 700))
        assertEquals(100f, v.restValue("wdth"))
        assertNull(v.restValue("XXXX"))
    }

    @Test
    fun `the css form round-trips through the signature order`() {
        assertEquals("'wght' 700.0, 'wdth' 80.0", axesCss(linkedMapOf("wght" to 700f, "wdth" to 80f)))
        assertEquals("wdth=80.0,wght=700.0", axesSignature(mapOf("wght" to 700f, "wdth" to 80f)))
    }
}
