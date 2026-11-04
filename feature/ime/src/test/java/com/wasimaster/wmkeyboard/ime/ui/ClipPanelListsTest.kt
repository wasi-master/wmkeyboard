package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.parseClipTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The clipboard panel's list arithmetic from #612: empty slots, tags and duplicates. */
class ClipPanelListsTest {

    private fun clip(id: Long, text: String = "clip $id", tags: List<String> = emptyList(), pinned: Boolean = false) =
        ClipItem(id = id, text = text, timestamp = id, tags = tags, pinned = pinned)

    private fun ids(items: List<ClipItem>) = items.map { it.id }

    @Test fun `a deleted clip keeps its place as an empty slot`() {
        val before = listOf(clip(1), clip(2), clip(3), clip(4))
        val after = listOf(clip(1), clip(2), clip(4))
        val live = setOf(1L, 2L, 4L)
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(withDeletedSlots(before, after, live)))
    }

    @Test fun `slots stay through later deletes and a new copy at the top`() {
        val first = withDeletedSlots(listOf(clip(1), clip(2), clip(3)), listOf(clip(1), clip(3)), setOf(1L, 3L))
        val second = withDeletedSlots(first, listOf(clip(9), clip(3)), setOf(9L, 3L))
        // 1 and 2 are both gone, and each keeps the place it had in front of
        // 3; the new copy comes after the empty slots, which draw nothing.
        assertEquals(listOf(1L, 2L, 9L, 3L), ids(second))
    }

    @Test fun `a clip that only left this view is not a slot, and one put back takes its own place`() {
        // Clip 2 was pinned onto the other tab: still in the history.
        assertEquals(listOf(1L, 3L), ids(withDeletedSlots(listOf(clip(1), clip(2), clip(3)), listOf(clip(1), clip(3)), setOf(1L, 2L, 3L))))
        // Clip 2 came back with Undo.
        val back = listOf(clip(1), clip(2), clip(3))
        assertSame(back, withDeletedSlots(listOf(clip(1), clip(2), clip(3)), back, setOf(1L, 2L, 3L)))
    }

    @Test fun `tags are split, trimmed and kept once`() {
        assertEquals(listOf("work", "Links", "to read"), parseClipTags(" #work, Links,, to read ,WORK,#links\n"))
        assertEquals(emptyList<String>(), parseClipTags(" , #, "))
    }

    @Test fun `the tags in the history, most used first, each once whatever its case`() {
        val items = listOf(clip(1, tags = listOf("a", "B")), clip(2, tags = listOf("b")), clip(3, tags = listOf("c", "b")))
        assertEquals(listOf("B", "a", "c"), clipTagsIn(items))
    }

    @Test fun `duplicates differ only in case and spacing and sit together`() {
        val items = listOf(clip(1, "GitHub"), clip(2, "other"), clip(3, "github "), clip(4, "two  words"), clip(5, "Two words"))
        assertEquals(listOf(1L, 3L, 4L, 5L), ids(duplicateGroups(items)))
    }

    @Test fun `pinned folders are one per tag of a pinned clip, in alphabetical order`() {
        val items = listOf(
            clip(1, tags = listOf("Survey"), pinned = true),
            clip(2, tags = listOf("event", "survey"), pinned = true),
            clip(3, tags = listOf("unpinned only")),
            clip(4, pinned = true),
        )
        assertEquals(listOf(ClipFolder("event", 1), ClipFolder("Survey", 2)), pinnedClipFolders(items))
    }

    @Test fun `filed pins leave the list, loose pins and unpinned clips stay`() {
        val items = listOf(
            clip(1, tags = listOf("survey"), pinned = true),
            clip(2, pinned = true),
            clip(3, tags = listOf("survey")),
        )
        assertEquals(listOf(2L, 3L), ids(withoutFiledPins(items)))
    }

    @Test fun `the notes' tags join the clips' in the filter row, each once whatever its case`() {
        val notes = listOf(
            com.wasimaster.wmkeyboard.core.notes.Note(id = 1, body = "", tags = listOf("work", "Quotes")),
        )
        assertEquals(listOf("Work", "Quotes"), withNoteTags(listOf("Work"), notes))
    }
}
