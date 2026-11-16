package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** The order of the clipboard's tag chips (#612): dragged, and matched to what is typed. */
class ClipTagOrderTest {

    private fun clip(id: Long, vararg tags: String) = ClipItem(id = id, text = "c$id", timestamp = id, tags = tags.toList())

    @Test fun `a dragged order goes first and the rest follow by use`() {
        val items = listOf(clip(1, "work", "links"), clip(2, "links"), clip(3, "read"))
        assertEquals(listOf("links", "work", "read"), clipTagsIn(items))
        assertEquals(listOf("read", "work", "links"), clipTagsIn(items, listOf("READ", "work")))
    }

    @Test fun `what is typed of the tag at the caret comes first`() {
        val offered = listOf("work", "homework", "links", "worries")
        assertEquals(listOf("work", "worries", "homework", "links"), clipTagsForTyping(offered, "a, wor", 6))
        // Only up to the caret: the tag after it is not the one being typed.
        assertEquals(offered, clipTagsForTyping(offered, "a, wor", 3))
        assertEquals(offered, clipTagsForTyping(offered, "", 0))
    }
}
