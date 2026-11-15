package com.wasimaster.wmkeyboard.core.emoji

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmojiUsageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun usage(file: File? = null) = EmojiUsage(file)

    @Test fun favouritesLeadRecentsEvenWhenNeverUsed() {
        val u = usage()
        u.record("😂")
        u.record("👍")
        u.toggleFavourite("🎂")
        assertEquals(listOf("🎂", "👍", "😂"), u.recents())
    }

    @Test fun favouritesLeadFrequents() {
        val u = usage()
        repeat(5) { u.record("😂") }
        repeat(3) { u.record("👍") }
        u.toggleFavourite("🙏")
        assertEquals(listOf("🙏", "😂", "👍"), u.frequents())
    }

    @Test fun favouritedUsedEmojiIsNotListedTwice() {
        val u = usage()
        u.record("😂")
        u.record("👍")
        u.toggleFavourite("😂")
        assertEquals(listOf("😂", "👍"), u.recents())
    }

    @Test fun toggleFavouriteRoundTrips() {
        val u = usage()
        assertTrue(u.toggleFavourite("🎂"))
        assertTrue(u.isFavourite("🎂"))
        assertFalse(u.toggleFavourite("🎂"))
        assertFalse(u.isFavourite("🎂"))
    }

    @Test fun newestFavouriteComesLast() {
        val u = usage()
        u.toggleFavourite("🎂")
        u.toggleFavourite("🙏")
        // A new favourite is pinned to the end of the list (see toggleFavourite).
        assertEquals(listOf("🎂", "🙏"), u.favourites())
    }

    @Test fun variantPrefRememberedAndReset() {
        val u = usage()
        u.setPreferredVariant("👍", "👍🏽")
        assertEquals("👍🏽", u.preferredVariant("👍"))
        // Picking the plain base clears the preference.
        u.setPreferredVariant("👍", "👍")
        assertNull(u.preferredVariant("👍"))
    }

    @Test fun everythingSurvivesSaveAndReload() {
        val file = File(tmp.root, "usage.json")
        val u = usage(file)
        u.record("😂")
        u.toggleFavourite("🎂")
        u.setPreferredVariant("🤝", "🫱🏻‍🫲🏾")
        u.save()

        val reloaded = usage(file)
        assertEquals(listOf("🎂", "😂"), reloaded.recents())
        assertTrue(reloaded.isFavourite("🎂"))
        assertEquals("🫱🏻‍🫲🏾", reloaded.preferredVariant("🤝"))
    }

    @Test fun oldSnapshotWithoutNewFieldsStillLoads() {
        val file = File(tmp.root, "usage.json")
        file.writeText("""{"recents":["😂"],"counts":{"😂":2}}""")
        val u = usage(file)
        assertEquals(listOf("😂"), u.recents())
        assertTrue(u.favourites().isEmpty())
    }

    @Test fun clearRecentsKeepsFavouritesAndCounts() {
        val u = usage()
        u.record("😂")
        u.toggleFavourite("🎂")
        u.clearRecents()
        assertEquals(listOf("🎂"), u.recents())
        assertEquals(listOf("🎂", "😂"), u.frequents())
    }

    @Test fun rankedByUseBringsTheUsedOnesForwardStably() {
        val u = usage()
        assertEquals(listOf("🐶", "🐕", "🦮"), u.rankedByUse(listOf("🐶", "🐕", "🦮")))
        repeat(3) { u.record("🦮") }
        u.record("🐕")
        assertEquals(listOf("🦮", "🐕", "🐶"), u.rankedByUse(listOf("🐶", "🐕", "🦮")))
        u.toggleFavourite("🐶")
        assertEquals(listOf("🐶", "🦮", "🐕"), u.rankedByUse(listOf("🐶", "🐕", "🦮")))
    }
}
