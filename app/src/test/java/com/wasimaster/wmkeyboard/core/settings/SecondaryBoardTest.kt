package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1230: a whole layout pinned on the Secondary layout tool leaves the
 * language switcher, and "Layout in one-handed mode" only ever names a layout
 * the keyboard will actually draw.
 */
class SecondaryBoardTest {

    private fun layout(id: String, lang: String, secondary: Boolean = false) = LayoutSpec(
        id = id,
        name = id,
        langId = lang,
        secondary = secondary,
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"))))),
    )

    private val qwerty = layout("custom_qwerty", "en")
    private val oneRow = layout("custom_one_row", "en")
    private val avro = layout("custom_avro", "bn")
    private val pad = layout("custom_pad", "en", secondary = true)

    private fun settings(enabled: List<String>, pinned: String? = null, oneHanded: String? = null) =
        KeyboardSettings().let {
            it.copy(
                customLayouts = listOf(qwerty, oneRow, avro, pad),
                enabledLayoutIds = enabled,
                layoutBehavior = it.layoutBehavior.copy(customLayoutToolId = pinned),
                oneHanded = it.oneHanded.copy(layoutId = oneHanded),
            )
        }

    @Test
    fun `a pinned enabled layout is a board and leaves the switcher`() {
        val s = settings(listOf(qwerty.id, oneRow.id, avro.id), pinned = oneRow.id)
        assertEquals(oneRow.id, s.pinnedBoardId())
        assertEquals(listOf(qwerty.id, avro.id), s.switchLayoutIds())
    }

    @Test
    fun `a pinned secondary grid or a disabled layout is not a board`() {
        assertNull(settings(listOf(qwerty.id), pinned = pad.id).pinnedBoardId())
        val off = settings(listOf(qwerty.id, avro.id), pinned = oneRow.id)
        assertNull(off.pinnedBoardId())
        assertEquals(listOf(qwerty.id, avro.id), off.switchLayoutIds())
    }

    @Test
    fun `the pinned board stays a stop when it is the only layout`() {
        val s = settings(listOf(oneRow.id), pinned = oneRow.id)
        assertEquals(listOf(oneRow.id), s.switchLayoutIds())
    }

    @Test
    fun `the secondary layout tool is usable once a board is pinned`() {
        val s = settings(listOf(qwerty.id, oneRow.id), pinned = oneRow.id).copy(customLayouts = listOf(qwerty, oneRow))
        assertNull(toolBlocker(ToolbarTool.CUSTOM_LAYOUT, s))
    }

    @Test
    fun `the one-handed picker offers only layouts sharing a language`() {
        val s = settings(listOf(qwerty.id, oneRow.id, avro.id))
        assertEquals(listOf(qwerty.id, oneRow.id), s.oneHandedLayoutChoices())
    }

    @Test
    fun `the one-handed layout applies only over its own language`() {
        val s = settings(listOf(qwerty.id, oneRow.id, avro.id), oneHanded = qwerty.id)
        assertEquals(qwerty.id, s.oneHandedLayoutFor(oneRow.id))
        assertNull(s.oneHandedLayoutFor(qwerty.id))
        assertNull(s.oneHandedLayoutFor(avro.id))
        assertNull(settings(listOf(oneRow.id, avro.id), oneHanded = qwerty.id).oneHandedLayoutFor(oneRow.id))
        assertNull(settings(listOf(qwerty.id, oneRow.id)).oneHandedLayoutFor(oneRow.id))
    }
}
