package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Key materials, board effects, lighting and key art as theme data. */
class ThemeLooksTest {

    @Test
    fun `a material takes its preset where the spec says nothing`() {
        val metal = resolveMaterial(KeyMaterialSpec(kind = "METAL"))
        assertEquals(materialPreset(KeyMaterialKind.METAL), metal)
        assertEquals(MaterialTexture.BRUSHED, metal.texture)
        val tuned = resolveMaterial(KeyMaterialSpec(kind = "metal", gloss = 0.9f, texture = "WOOD", edge = "stitch"))
        assertEquals(0.9f, tuned.gloss)
        assertEquals(MaterialTexture.WOOD, tuned.texture)
        assertEquals(MaterialEdge.STITCH, tuned.edge)
        assertEquals(metal.bevelDp, tuned.bevelDp)
    }

    @Test
    fun `out of range values are held to their ranges`() {
        val m = resolveMaterial(KeyMaterialSpec(kind = "CAP", bevelDp = 40f, gloss = -1f, lightAngleDeg = 300f))
        assertEquals(MATERIAL_BEVEL_RANGE.endInclusive, m.bevelDp)
        assertEquals(0f, m.gloss)
        assertEquals(MATERIAL_LIGHT_RANGE.endInclusive, m.lightAngleDeg)
    }

    @Test
    fun `an unknown kind or texture costs only itself`() {
        val m = resolveMaterial(KeyMaterialSpec(kind = "PLASMA", texture = "FUR", gloss = 0.5f))
        assertEquals(MaterialTexture.NONE, m.texture)
        assertEquals(0.5f, m.gloss)
        assertTrue(resolveMaterial(KeyMaterialSpec(kind = "CUSTOM")).isPlain)
        assertFalse(resolveMaterial(KeyMaterialSpec(kind = "NEON")).isPlain)
    }

    @Test
    fun `every preset draws something`() {
        for (kind in KeyMaterialKind.entries - KeyMaterialKind.CUSTOM) {
            assertFalse("$kind draws nothing", materialPreset(kind).isPlain)
        }
    }

    @Test
    fun `the four looks survive a round trip and an old file reads without them`() {
        val theme = ThemeSpec(
            id = "t",
            name = "Looks",
            keyMaterial = KeyMaterialSpec(kind = "WOOD", gloss = 0.2f),
            boardEffect = BoardEffectSpec(kind = "SNOW", colors = listOf(0xFFFFFFFF), reactive = 0.5f),
            keyLighting = KeyLightingSpec(pattern = "RIPPLE", target = "EDGE", hueSpanDeg = 180f),
            touchBloom = TouchBloomSpec(color = 0xFF22D3EE, radiusDp = 120f, wobble = 0.8f),
            keySkin = KeySkinSpec(
                slots = mapOf(
                    SkinSlotName.LETTER to SkinSlot(
                        size = listOf(100f, 130f),
                        stretchX = listOf(30f, 70f),
                        layers = listOf(
                            SkinLayer(
                                d = "M0 0L100 0L100 130Z",
                                fill = SkinPaint(linear = listOf(0f, 0f, 0f, 130f), stops = listOf(SkinStop(0f, "face:lighten:0.2"))),
                                press = SkinPress(translate = listOf(0f, 2.5f), opacity = listOf(1f, 0.5f)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val back = ThemeCodec.decode(ThemeCodec.encode(theme))
        assertEquals(theme, back)
        val old = ThemeCodec.decode("""{"id": "o", "name": "Old"}""")
        assertNotNull(old)
        assertNull(old!!.keyMaterial)
        assertNull(old.boardEffect)
        assertNull(old.keyLighting)
        assertNull(old.touchBloom)
        assertNull(old.keySkin)
    }

    @Test
    fun `a skin document with fields of its own around the slots reads as a skin`() {
        // The shape of a skin document from another keyboard: an id, a name, a
        // tier, then the slots and the board.
        val text = """{"schema": "catalog.v1", "kind": "skin", "id": "x", "name": "X", "requiredTier": "PRO",
            "slots": {"letter": {"size": [100, 130], "labelBox": [10, 8, 90, 112], "layers": [
              {"d": "M0 0L1 1", "fill": {"color": "face"}, "glow": {"width": 1.6, "color": "signal", "alpha": 0.35}}]}},
            "board": {"size": [1000, 544], "layers": []}}"""
        val skin = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(KeySkinSpec.serializer(), text)
        assertEquals(setOf("letter"), skin.slots.keys)
        assertEquals("signal", skin.slots.getValue("letter").layers.single().glow?.color)
        assertNotNull(skin.board)
    }

    @Test
    fun `reactive patterns are the ones that answer presses`() {
        assertTrue(KeyLightingPattern.RIPPLE.reactive)
        assertFalse(KeyLightingPattern.WAVE.reactive)
        assertNull(keyLightingPatternOrNull("DISCO"))
        assertEquals(BoardEffectKind.CODE_RAIN, boardEffectKindOrNull("code_rain"))
    }
}
