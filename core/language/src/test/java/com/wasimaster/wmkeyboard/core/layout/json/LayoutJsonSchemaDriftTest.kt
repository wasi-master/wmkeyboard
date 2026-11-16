package com.wasimaster.wmkeyboard.core.layout.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schema is built from the model's descriptors, and the docs and hints are
 * written by hand beside it. This holds the two together in both directions, the
 * way `LuaApiDriftTest` holds the Lua API to its paragraphs.
 */
class LayoutJsonSchemaDriftTest {

    private val objects = LayoutJsonSchema.reachableObjects()
    private val propertyKeys = objects.flatMap { shape -> shape.properties.map { it.docKey } }.toSet()
    private val actionKeys = LayoutJsonSchema.action.variants.values.map { it.typeName }.toSet()

    @Test
    fun `every property and every action has a paragraph`() {
        val missing = (propertyKeys + actionKeys + LayoutJsonDocs.ACTION_TYPE).filter { LayoutJsonDocs.of(it) == null }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `no paragraph names a property or an action the model lacks`() {
        val stale = LayoutJsonDocs.keys - propertyKeys - actionKeys - LayoutJsonDocs.ACTION_TYPE
        assertEquals(emptySet<String>(), stale)
    }

    @Test
    fun `every hint names a real property, and every hidden tag a real action`() {
        assertEquals(emptySet<String>(), LayoutJsonHints.keys - propertyKeys)
        assertEquals(emptySet<String>(), LayoutJsonHints.hiddenTags - LayoutJsonSchema.action.variants.keys)
    }

    @Test
    fun `no paragraph uses a dash for a pause`() {
        for (key in LayoutJsonDocs.keys) {
            val text = LayoutJsonDocs.of(key).orEmpty()
            assertFalse(key, '–' in text || '—' in text)
        }
    }

    @Test
    fun `the descriptors say what the model says`() {
        val key = LayoutJsonSchema.key
        assertFalse(key.property("label")!!.optional)
        assertTrue(key.property("width")!!.optional)
        assertTrue(key.property("role")!!.nullable)
        assertTrue(LayoutJsonSchema.layout.property("layers")!!.shape is MapShape)
        assertTrue("shift" in LayoutJsonSchema.action.variants)
        assertEquals(listOf("tool", "custom"), LayoutJsonSchema.action.variants.getValue("tool").properties.map { it.name })
        assertEquals(
            listOf("left", "up", "right", "down", "up_left", "up_right", "down_left", "down_right"),
            ((key.property("flick")!!.shape as MapShape).key as EnumShape).values,
        )
    }

    @Test
    fun `defaults are read from the decoder`() {
        assertEquals("1.0", LayoutJsonSchema.key.property("width")!!.default)
        assertEquals("true", LayoutJsonSchema.layout.property("tabletExpand")!!.default)
        assertEquals("\"EMOJI\"", LayoutJsonSchema.action.variants.getValue("tool").property("tool")!!.default)
        assertEquals(null, LayoutJsonSchema.key.property("label")!!.default)
    }
}
