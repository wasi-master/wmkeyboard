package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sync.Stamped
import com.wasimaster.wmkeyboard.core.settings.sync.SyncMerge
import com.wasimaster.wmkeyboard.core.settings.sync.SyncRunner
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory side of a sync pass (#1508): what a pass leaves out when its
 * files would not fit the heap, and folding the other devices' files into
 * one table as they are read without changing what the merge decides.
 */
class SyncMemoryBudgetTest {

    private val mb = 1024L * 1024L

    @Test
    fun `sections that fit are all kept`() {
        val skipped = SyncRunner.oversized(
            mapOf(ConfigBackup.Section.THEMES to 2 * mb, ConfigBackup.Section.STICKERS to 3 * mb),
            budget = 64 * mb,
        )
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `the section that does not fit is left out and the smaller ones stay`() {
        val skipped = SyncRunner.oversized(
            mapOf(ConfigBackup.Section.STICKERS to 40 * mb, ConfigBackup.Section.THEMES to 4 * mb),
            budget = 64 * mb,
        )
        assertEquals(setOf(ConfigBackup.Section.STICKERS), skipped)
    }

    @Test
    fun `a too-big reason names its sections and no other reason does`() {
        assertEquals(
            listOf(ConfigBackup.Section.THEMES, ConfigBackup.Section.STICKERS),
            SyncRunner.tooBigSections("${SyncRunner.ERROR_TOO_BIG}:stickers,themes"),
        )
        assertNull(SyncRunner.tooBigSections(SyncRunner.ERROR_TOO_BIG_REMOTE))
        assertNull(SyncRunner.tooBigSections(SyncRunner.ERROR_MEMORY))
        assertNull(SyncRunner.tooBigSections(""))
    }

    @Test
    fun `folding the files merges the same as every file at once`() {
        val s = "snippets"
        fun v(x: String) = JsonPrimitive(x)
        val b = mapOf(s to mapOf("a" to Stamped(v("b1"), 300, "bbbbbbbb"), "c" to Stamped(v("b-c"), 100, "bbbbbbbb")))
        val c = mapOf(s to mapOf("a" to Stamped(v("c1"), 200, "cccccccc"), "d" to Stamped(null, 400, "cccccccc")))
        val local = mapOf(s to mapOf("a" to v("mine"), "d" to v("gone elsewhere")))
        val remembered = mapOf(s to mapOf("a" to com.wasimaster.wmkeyboard.core.settings.sync.Remembered(SyncMerge.hash(v("mine")), 50, "aaaaaaaa")))

        val folded = LinkedHashMap<String, MutableMap<String, Stamped>>()
        SyncMerge.fold(folded, b, keep = setOf(s))
        SyncMerge.fold(folded, c, keep = setOf(s))

        fun merge(remotes: List<Map<String, Map<String, Stamped>>>) = SyncMerge.merge(
            local = local,
            remembered = remembered,
            remotes = remotes,
            me = "aaaaaaaa",
            nowMs = 1_000,
            firstSync = false,
        )
        val all = merge(listOf(b, c))
        val one = merge(listOf(folded))
        assertEquals(all.merged, one.merged)
        assertEquals(all.changes, one.changes)
        assertEquals(all.remembered, one.remembered)
    }

    @Test
    fun `a section left out of the pass is not folded in`() {
        val folded = LinkedHashMap<String, MutableMap<String, Stamped>>()
        SyncMerge.fold(
            folded,
            mapOf("stickers" to mapOf("x" to Stamped(JsonPrimitive("big"), 1, "bbbbbbbb"))),
            keep = setOf("settings"),
        )
        assertTrue(folded.isEmpty())
    }
}
