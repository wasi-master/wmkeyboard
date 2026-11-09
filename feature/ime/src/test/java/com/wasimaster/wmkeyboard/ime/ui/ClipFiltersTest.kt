package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clipboard filter row's chips combined (#1187), and the single-chip taps from before (#612, #1093). */
class ClipFiltersTest {

    private fun clip(
        id: Long,
        kind: ClipKind = ClipKind.TEXT,
        pinned: Boolean = false,
        tags: List<String> = emptyList(),
        text: String = "clip $id",
    ) = ClipItem(id = id, text = text, timestamp = id, kind = kind, pinned = pinned, tags = tags)

    private val history = listOf(
        clip(1, pinned = true, tags = listOf("bug")),
        clip(2, ClipKind.LINK, pinned = true, tags = listOf("critical")),
        clip(3, ClipKind.IMAGE, pinned = true),
        clip(4, tags = listOf("Bug", "critical")),
        clip(5, ClipKind.LINK),
        clip(6, ClipKind.FILE, tags = listOf("work")),
        clip(7, ClipKind.HTML, pinned = true, tags = listOf("CRITICAL")),
    )

    private fun ClipFilters.ids() = apply(history).map { it.id }

    @Test fun `nothing set shows the whole history, the same list`() {
        assertTrue(ClipFilters().isEmpty)
        assertSame(history, ClipFilters().apply(history))
    }

    @Test fun `a scope and a kind both hold`() {
        // Pinned + Text is the pinned text clips; HTML counts as text.
        val pinnedText = ClipFilters().tapped(ClipFilter.PINNED).held(ClipFilter.TEXT)
        assertEquals(listOf(1L, 7L), pinnedText.ids())
    }

    @Test fun `two kinds are either, since a clip is only one`() {
        val textOrLinks = ClipFilters().tapped(ClipFilter.TEXT).held(ClipFilter.LINKS)
        assertEquals(listOf(1L, 2L, 4L, 5L, 7L), textOrLinks.ids())
    }

    @Test fun `several tags are any of them, and narrow the scope`() {
        // Pinned, #bug and #critical: the pinned clips tagged either, any case.
        val filters = ClipFilters().tapped(ClipFilter.PINNED).tappedTag("bug").heldTag("critical")
        assertEquals(listOf("bug", "critical"), filters.tags)
        assertEquals(listOf(1L, 2L, 7L), filters.ids())
    }

    @Test fun `a Not kind leaves its clips out`() {
        // Pinned + Not links: a tap on a set kind turns it to Not.
        val notLinks = ClipFilters().tapped(ClipFilter.PINNED).held(ClipFilter.LINKS).tapped(ClipFilter.LINKS)
        assertEquals(ClipChipState.NOT, notLinks.stateOf(ClipFilter.LINKS))
        assertEquals(listOf(1L, 3L, 7L), notLinks.ids())
    }

    @Test fun `a Not tag leaves its clips out, with the wanted tags still any of them`() {
        val filters = ClipFilters().tappedTag("bug").heldTag("critical").heldTag("work").tappedTag("work")
        // #bug or #critical, and not #work; then not #bug either.
        assertEquals(listOf(1L, 2L, 4L, 7L), filters.ids())
        val notBug = filters.tappedTag("bug")
        assertEquals(listOf("critical"), notBug.tags)
        assertEquals(listOf("work", "bug"), notBug.notTags)
        assertEquals(listOf(2L, 7L), notBug.ids())
    }

    @Test fun `Pinned and Unpinned never combine`() {
        val pinnedText = ClipFilters().tapped(ClipFilter.PINNED).held(ClipFilter.TEXT)
        // Held, the other scope takes its place and the kinds stay.
        val swapped = pinnedText.held(ClipFilter.UNPINNED)
        assertEquals(ClipFilter.UNPINNED, swapped.scope)
        assertEquals(setOf(ClipFilter.TEXT), swapped.kinds)
        assertEquals(ClipChipState.OFF, swapped.stateOf(ClipFilter.PINNED))
        assertEquals(listOf(4L), swapped.ids())
        // Held, All lets the scope go and keeps the kinds.
        val all = swapped.held(ClipFilter.ALL)
        assertEquals(ClipFilter.ALL, all.scope)
        assertEquals(setOf(ClipFilter.TEXT), all.kinds)
        // Tapped, it clears the kind row and leaves the tags.
        val cleared = pinnedText.tappedTag("bug").tapped(ClipFilter.ALL)
        assertEquals(ClipFilters(tags = listOf("bug")), cleared)
        assertEquals(ClipChipState.ON, cleared.stateOf(ClipFilter.ALL))
    }

    @Test fun `a tap on a chip that is off sets it alone in its row, as before chips combined`() {
        val combined = ClipFilters().tapped(ClipFilter.PINNED).held(ClipFilter.TEXT).tappedTag("bug").heldTag("work")
        assertEquals(ClipFilters(kinds = setOf(ClipFilter.IMAGES), tags = listOf("bug", "work")), combined.tapped(ClipFilter.IMAGES))
        assertEquals(ClipFilters(scope = ClipFilter.UNPINNED, tags = listOf("bug", "work")), combined.tapped(ClipFilter.UNPINNED))
        val kinds = ClipFilters(scope = ClipFilter.PINNED, kinds = setOf(ClipFilter.TEXT))
        assertEquals(kinds.copy(tags = listOf("critical")), combined.tappedTag("critical"))
    }

    @Test fun `a set chip goes on, Not, off, and Pinned has no Not and goes straight off`() {
        val text = ClipFilters().tapped(ClipFilter.TEXT)
        assertEquals(ClipChipState.ON, text.stateOf(ClipFilter.TEXT))
        assertEquals(ClipChipState.NOT, text.tapped(ClipFilter.TEXT).stateOf(ClipFilter.TEXT))
        assertTrue(text.tapped(ClipFilter.TEXT).tapped(ClipFilter.TEXT).isEmpty)
        // A hold on a set chip does what a tap does.
        assertEquals(text.tapped(ClipFilter.TEXT), text.held(ClipFilter.TEXT))
        assertTrue(ClipFilters().tapped(ClipFilter.PINNED).tapped(ClipFilter.PINNED).isEmpty)

        val tag = ClipFilters().tappedTag("bug")
        assertEquals(ClipChipState.NOT, tag.tappedTag("BUG").stateOfTag("bug"))
        assertTrue(tag.tappedTag("bug").tappedTag("bug").isEmpty)
        assertEquals(ClipChipState.OFF, tag.stateOfTag("work"))
    }

    @Test fun `duplicates hold with the rest, and Not duplicates is the clips without a twin`() {
        val items = listOf(
            clip(1, text = "GitHub", pinned = true),
            clip(2, text = "other"),
            clip(3, text = "github "),
            clip(4, text = "same"),
            clip(5, text = "Same", pinned = true),
            clip(6, text = "alone", pinned = true),
        )
        assertEquals(listOf(1L, 3L, 4L, 5L), ClipFilters().tapped(ClipFilter.DUPLICATES).apply(items).map { it.id })
        // Among the pinned clips alone, nothing has a twin.
        val pinned = ClipFilters().tapped(ClipFilter.PINNED).held(ClipFilter.DUPLICATES)
        assertTrue(pinned.duplicates)
        assertEquals(emptyList<Long>(), pinned.apply(items).map { it.id })
        val unique = ClipFilters().held(ClipFilter.DUPLICATES).tapped(ClipFilter.DUPLICATES)
        assertFalse(unique.duplicates)
        assertEquals(listOf(2L, 6L), unique.apply(items).map { it.id })
    }

    @Test fun `the Clear chip counts every chip set, and a tag gone from the history drops out`() {
        val filters = ClipFilters().tapped(ClipFilter.PINNED).held(ClipFilter.TEXT).held(ClipFilter.LINKS)
            .tapped(ClipFilter.LINKS).tappedTag("bug").heldTag("old").tappedTag("old")
        assertEquals(5, filters.count)
        val kept = filters.keepingTags(listOf("Bug", "work"))
        assertEquals(listOf("bug"), kept.tags)
        assertEquals(emptyList<String>(), kept.notTags)
        assertEquals(4, kept.count)
        assertSame(kept, kept.keepingTags(listOf("bug")))
    }

    @Test fun `the panel keeps the kind row for next time but never the tags`() {
        val filters = ClipFilters().tapped(ClipFilter.UNPINNED).held(ClipFilter.IMAGES).tappedTag("bug").heldTag("x").tappedTag("x")
        assertEquals(ClipFilters(scope = ClipFilter.UNPINNED, kinds = setOf(ClipFilter.IMAGES)), filters.withoutTags())
    }

    @Test fun `a pinned folder is one tag and nothing against one`() {
        assertEquals("survey", ClipFilters().withOnlyTag("survey").singleTag)
        assertEquals(null, ClipFilters().tappedTag("a").heldTag("b").singleTag)
        assertEquals(null, ClipFilters().tappedTag("a").tappedTag("a").singleTag)
        // Opening a folder keeps the kind row and replaces the tags.
        val opened = ClipFilters(kinds = setOf(ClipFilter.TEXT), notTags = listOf("x")).withOnlyTag("survey")
        assertEquals(ClipFilters(kinds = setOf(ClipFilter.TEXT), tags = listOf("survey")), opened)
        assertEquals(ClipFilters(kinds = setOf(ClipFilter.TEXT)), opened.withOnlyTag(null))
    }

    @Test fun `notes join a search only under filters a note can meet`() {
        assertTrue(ClipFilters().admitsNotes)
        assertTrue(ClipFilters().tapped(ClipFilter.TEXT).held(ClipFilter.LINKS).admitsNotes)
        assertTrue(ClipFilters().tapped(ClipFilter.LINKS).tapped(ClipFilter.LINKS).admitsNotes)
        assertTrue(ClipFilters().tappedTag("bug").admitsNotes)
        assertFalse(ClipFilters().tapped(ClipFilter.UNPINNED).admitsNotes)
        assertFalse(ClipFilters().tapped(ClipFilter.LINKS).admitsNotes)
        assertFalse(ClipFilters().tapped(ClipFilter.TEXT).tapped(ClipFilter.TEXT).admitsNotes)
        assertFalse(ClipFilters().tapped(ClipFilter.TEXT).held(ClipFilter.DUPLICATES).admitsNotes)
    }
}
