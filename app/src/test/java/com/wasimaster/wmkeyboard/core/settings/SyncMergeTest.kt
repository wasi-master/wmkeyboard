package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.settings.sync.Remembered
import com.wasimaster.wmkeyboard.core.settings.sync.Stamped
import com.wasimaster.wmkeyboard.core.settings.sync.SyncMerge
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two phones, A and B, and the rules that keep them the same: the newest
 * change wins entry by entry, deletions spread, and a phone joining late
 * takes what the others agreed on.
 */
class SyncMergeTest {

    private val s = "settings"
    private fun v(x: Any) = JsonPrimitive(x.toString())
    private fun remembered(vararg pairs: Pair<String, Any?>, t: Long = 100, by: String = "aaaaaaaa") =
        mapOf(s to pairs.associate { (k, value) -> k to Remembered(value?.let { SyncMerge.hash(v(it)) }, t, by) })

    @Test
    fun `a change here is stamped now and goes out`() {
        val r = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("loud"))),
            remembered = remembered("sound" to "soft"),
            remotes = emptyList(),
            me = "aaaaaaaa",
            nowMs = 5_000,
            firstSync = false,
        )
        assertEquals(5_000, r.merged[s]!!["sound"]!!.t)
        assertTrue(r.changes.isEmpty())
    }

    @Test
    fun `a newer change elsewhere wins and is applied here`() {
        val remote = mapOf(s to mapOf("sound" to Stamped(v("off"), 9_000, "bbbbbbbb")))
        val r = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("soft"))),
            remembered = remembered("sound" to "soft"),
            remotes = listOf(remote),
            me = "aaaaaaaa",
            nowMs = 5_000,
            firstSync = false,
        )
        assertEquals(v("off"), r.changes[s]!!["sound"])
        assertEquals("bbbbbbbb", r.merged[s]!!["sound"]!!.by)
    }

    @Test
    fun `an older change elsewhere loses to one made here since`() {
        val remote = mapOf(s to mapOf("sound" to Stamped(v("off"), 50, "bbbbbbbb")))
        val r = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("loud"))),
            remembered = remembered("sound" to "soft"),
            remotes = listOf(remote),
            me = "aaaaaaaa",
            nowMs = 5_000,
            firstSync = false,
        )
        assertTrue(r.changes.isEmpty())
        assertEquals(v("loud"), r.merged[s]!!["sound"]!!.value)
    }

    @Test
    fun `a slow clock still beats what it has already seen`() {
        // This phone's clock says 10, but it has seen a change stamped 9 000:
        // an edit made now must still be the newest one.
        val remote = mapOf(s to mapOf("other" to Stamped(v(1), 9_000, "bbbbbbbb")))
        val r = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("loud"), "other" to v(1))),
            remembered = remembered("sound" to "soft", "other" to 1),
            remotes = listOf(remote),
            me = "aaaaaaaa",
            nowMs = 10,
            firstSync = false,
        )
        assertEquals(9_001, r.merged[s]!!["sound"]!!.t)
    }

    @Test
    fun `a reset here spreads as a deletion`() {
        val r = SyncMerge.merge(
            local = mapOf(s to emptyMap()),
            remembered = remembered("sound" to "soft"),
            remotes = emptyList(),
            me = "aaaaaaaa",
            nowMs = 5_000,
            firstSync = false,
        )
        val tomb = r.merged[s]!!["sound"]!!
        assertTrue(tomb.deleted)
        assertEquals(5_000, tomb.t)
    }

    @Test
    fun `a newer deletion elsewhere removes it here`() {
        val remote = mapOf(s to mapOf("sound" to Stamped(null, 9_000, "bbbbbbbb")))
        val r = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("soft"))),
            remembered = remembered("sound" to "soft"),
            remotes = listOf(remote),
            me = "aaaaaaaa",
            nowMs = 5_000,
            firstSync = false,
        )
        assertTrue(r.changes[s]!!.containsKey("sound"))
        assertNull(r.changes[s]!!["sound"])
    }

    @Test
    fun `joining late takes what the others agreed on`() {
        val remote = mapOf(s to mapOf("sound" to Stamped(v("off"), 1_000, "bbbbbbbb")))
        val r = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("loud"), "only-here" to v(1))),
            remembered = emptyMap(),
            remotes = listOf(remote),
            me = "aaaaaaaa",
            nowMs = 5_000,
            firstSync = true,
        )
        assertEquals(v("off"), r.changes[s]!!["sound"])
        // What only this phone has still goes out.
        assertEquals(v(1), r.merged[s]!!["only-here"]!!.value)
    }

    @Test
    fun `nothing changed anywhere means nothing to apply and the same memory`() {
        val before = remembered("sound" to "soft")
        val remote = mapOf(s to mapOf("sound" to Stamped(v("soft"), 100, "aaaaaaaa")))
        val r = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("soft"))),
            remembered = before,
            remotes = listOf(remote),
            me = "aaaaaaaa",
            nowMs = 5_000,
            firstSync = false,
        )
        assertTrue(r.changes.isEmpty())
        assertEquals(before, r.remembered)
    }

    @Test
    fun `a tie goes the same way on both phones`() {
        val a = Stamped(v("a"), 1_000, "aaaaaaaa")
        val b = Stamped(v("b"), 1_000, "bbbbbbbb")
        val onA = SyncMerge.merge(
            local = mapOf(s to mapOf("k" to v("a"))),
            remembered = mapOf(s to mapOf("k" to Remembered(SyncMerge.hash(v("a")), 1_000, "aaaaaaaa"))),
            remotes = listOf(mapOf(s to mapOf("k" to b))),
            me = "aaaaaaaa",
            nowMs = 2_000,
            firstSync = false,
        )
        val onB = SyncMerge.merge(
            local = mapOf(s to mapOf("k" to v("b"))),
            remembered = mapOf(s to mapOf("k" to Remembered(SyncMerge.hash(v("b")), 1_000, "bbbbbbbb"))),
            remotes = listOf(mapOf(s to mapOf("k" to a))),
            me = "bbbbbbbb",
            nowMs = 2_000,
            firstSync = false,
        )
        assertEquals(onA.merged[s]!!["k"]!!.value, onB.merged[s]!!["k"]!!.value)
    }

    @Test
    fun `old tombstones are dropped`() {
        val old = Stamped(null, 0, "bbbbbbbb")
        val r = SyncMerge.merge(
            local = mapOf(s to emptyMap()),
            remembered = emptyMap(),
            remotes = listOf(mapOf(s to mapOf("gone" to old))),
            me = "aaaaaaaa",
            nowMs = SyncMerge.TOMBSTONE_TTL_MS + 1,
            firstSync = false,
        )
        assertFalse(r.merged[s]!!.containsKey("gone"))
    }

    @Test
    fun `a section ticked again takes what changed elsewhere while it was off`() {
        // Settings has a memory; themes was just ticked and has none. The
        // theme here is older than the one another phone saved meanwhile.
        val remote = mapOf("themes" to mapOf("t1" to Stamped(v("new"), 1_000, "bbbbbbbb")))
        val r = SyncMerge.merge(
            local = mapOf(s to emptyMap(), "themes" to mapOf("t1" to v("old"))),
            remembered = remembered(),
            remotes = listOf(remote),
            me = "aaaaaaaa",
            nowMs = 5_000,
            firstSync = false,
        )
        assertEquals(v("new"), r.changes["themes"]!!["t1"])
    }

    @Test
    fun `a setting let go of from this device takes what the others have`() {
        // The toolbar was kept on this phone, so the last pass neither sent
        // nor remembered it. Untick it: the others' toolbar wins, although
        // this phone's copy has no stamp to lose with.
        val remote = mapOf(s to mapOf("toolbar_tools" to Stamped(v("theirs"), 1_000, "bbbbbbbb")))
        val r = SyncMerge.merge(
            local = mapOf(s to mapOf("toolbar_tools" to v("mine"), "sound" to v("soft"))),
            remembered = remembered("sound" to "soft"),
            remotes = listOf(remote),
            me = "aaaaaaaa",
            nowMs = 5_000,
            firstSync = false,
            rejoining = { _, key -> key == "toolbar_tools" },
        )
        assertEquals(v("theirs"), r.changes[s]!!["toolbar_tools"])
    }

    @Test
    fun `a setting let go of that nobody else has is sent from here`() {
        val r = SyncMerge.merge(
            local = mapOf(s to mapOf("toolbar_tools" to v("mine"))),
            remembered = remembered(),
            remotes = listOf(mapOf(s to mapOf("sound" to Stamped(v("soft"), 1_000, "bbbbbbbb")))),
            me = "aaaaaaaa",
            nowMs = 5_000,
            firstSync = false,
            rejoining = { _, key -> key == "toolbar_tools" },
        )
        assertEquals(5_000, r.merged[s]!!["toolbar_tools"]!!.t)
        assertNull(r.changes[s]?.get("toolbar_tools"))
    }

    @Test
    fun `a joining phone loses even a tie to the phone it joins`() {
        // Phone A was first: nothing else there, so it stamped for real.
        val a = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("soft"))),
            remembered = emptyMap(),
            remotes = emptyList(),
            me = "00000001",
            nowMs = 1_000,
            firstSync = true,
        )
        assertEquals(1_000, a.merged[s]!!["sound"]!!.t)
        // Phone B joins with a larger id and its own value: A's still wins.
        val b = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("loud"))),
            remembered = emptyMap(),
            remotes = listOf(a.merged),
            me = "ffffffff",
            nowMs = 2_000,
            firstSync = true,
        )
        assertEquals(v("soft"), b.changes[s]!!["sound"])
    }

    @Test
    fun `the copy in a profile joins even before the main copy's file has come over`() {
        // The private space passes first, its mailbox still empty: it joins
        // anyway, so its defaults carry no stamp that could win.
        val private = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("default"), "only-private" to v(1))),
            remembered = emptyMap(),
            remotes = emptyList(),
            me = "ffffffff",
            nowMs = 9_000,
            firstSync = true,
            joinAlone = true,
        )
        assertEquals(0L, private.merged[s]!!["sound"]!!.t)
        // The main copy, first sync too, has only that file: a file that only
        // joined is nothing to join, so the main copy founds over it.
        val main = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("set by hand"))),
            remembered = emptyMap(),
            remotes = listOf(private.merged),
            me = "00000001",
            nowMs = 10_000,
            firstSync = true,
        )
        assertEquals(10_000, main.merged[s]!!["sound"]!!.t)
        assertNull(main.changes[s]?.get("sound"))
        // What only the private copy had still reaches the main one.
        assertEquals(v(1), main.changes[s]!!["only-private"])
        // Back in the private space, the main copy's setting wins.
        val again = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("default"), "only-private" to v(1))),
            remembered = private.remembered,
            remotes = listOf(main.merged),
            me = "ffffffff",
            nowMs = 11_000,
            firstSync = false,
        )
        assertEquals(v("set by hand"), again.changes[s]!!["sound"])
    }

    @Test
    fun `the copy in a profile joins a main copy that synced first`() {
        val main = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("set by hand"))),
            remembered = emptyMap(),
            remotes = emptyList(),
            me = "00000001",
            nowMs = 1_000,
            firstSync = true,
        )
        val private = SyncMerge.merge(
            local = mapOf(s to mapOf("sound" to v("default"))),
            remembered = emptyMap(),
            remotes = listOf(main.merged),
            me = "ffffffff",
            nowMs = 9_000,
            firstSync = true,
            joinAlone = true,
        )
        assertEquals(v("set by hand"), private.changes[s]!!["sound"])
    }
}
