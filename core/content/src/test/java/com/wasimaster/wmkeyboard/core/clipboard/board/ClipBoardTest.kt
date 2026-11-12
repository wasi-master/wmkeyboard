package com.wasimaster.wmkeyboard.core.clipboard.board

import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.ClipKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipBoardTest {

    private fun entry(id: String, text: String, stamp: Long, by: String = "aaaaaaaa", deleted: Boolean = false) =
        BoardEntry(id = id, text = text, createdAtMs = 1, stampMs = stamp, stampBy = by, deleted = deleted)

    @Test
    fun `the later change to one entry wins on every side`() {
        val phone = listOf(entry("1", "old", 10), entry("2", "only here", 5))
        val tablet = listOf(entry("1", "new", 20))
        val merged = ClipBoard.merge(listOf(phone, tablet)).associateBy { it.id }
        assertEquals("new", merged.getValue("1").text)
        assertEquals("only here", merged.getValue("2").text)
        assertEquals(merged, ClipBoard.merge(listOf(tablet, phone)).associateBy { it.id })
    }

    @Test
    fun `a tie goes to the same install on every device`() {
        val a = entry("1", "from a", 10, by = "aaaaaaaa")
        val b = entry("1", "from b", 10, by = "bbbbbbbb")
        assertEquals("from b", ClipBoard.later(a, b).text)
        assertEquals("from b", ClipBoard.later(b, a).text)
    }

    @Test
    fun `a delete made offline beats the older text and is not sent back`() {
        val deleted = ClipBoard.delete(listOf(entry("1", "secret plan", 10)), "1", "aaaaaaaa", 30)
        val other = listOf(entry("1", "secret plan", 10))
        val merged = ClipBoard.merge(listOf(other, deleted)).single()
        assertTrue(merged.deleted)
        assertEquals("", merged.text)
        assertTrue(ClipBoard.live(listOf(merged)).isEmpty())
    }

    @Test
    fun `old markers are forgotten and the live count is capped`() {
        val now = ClipBoard.TOMBSTONE_MS * 2
        val gone = entry("x", "", 1, deleted = true)
        val live = (1..ClipBoard.MAX_ENTRIES + 3).map {
            entry("e$it", "t$it", now).copy(createdAtMs = it.toLong())
        }
        val pruned = ClipBoard.prune(live + gone, "aaaaaaaa", now)
        assertNull(pruned.firstOrNull { it.id == "x" })
        assertEquals(ClipBoard.MAX_ENTRIES, ClipBoard.live(pruned).size)
        // The oldest go as markers, so another device takes them off too.
        assertTrue(pruned.first { it.id == "e1" }.deleted)
    }

    @Test
    fun `only text that is not a secret goes on the board`() {
        val text = ClipItem(id = 1, text = "hello", timestamp = 1)
        assertEquals("hello", ClipBoard.portableText(text)?.first)
        assertNull(ClipBoard.portableText(text.copy(sensitive = true)))
        assertNull(ClipBoard.portableText(text.copy(kind = ClipKind.IMAGE, imagePath = "/data/x.png")))
    }

    @Test
    fun `an entry from another device is cleaned and capped`() {
        val long = "a".repeat(ClipBoard.TEXT_MAX + 10)
        val cleaned = ClipBoard.clean(entry("1", long, 1))
        assertNotNull(cleaned)
        assertEquals(ClipBoard.TEXT_MAX, cleaned!!.text.length)
        assertNull(ClipBoard.clean(entry("", "no id", 1)))
    }

    @Test
    fun `a file round-trips and a broken one is not one`() {
        val file = BoardFile(installId = "aaaaaaaa", device = "Pixel", writtenAtMs = 5, entries = listOf(entry("1", "hi", 2)))
        assertEquals(file, ClipBoard.decode(ClipBoard.encode(file)))
        assertNull(ClipBoard.decode("{not json"))
    }
}
