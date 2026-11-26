package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The user's own detects (#612): their matches are parts of a clip, like links. */
class ClipDetectsTest {

    private fun found(text: String, vararg detects: ClipDetect): List<Pair<String?, String>> =
        ClipEntities.extract(text, detects = ClipDetects.compileAll(detects.toList()))
            .filter { it.kind == ClipEntityKind.CUSTOM }
            .sortedBy { it.start }
            .map { it.label to it.value }

    @Test fun issueNumbers() {
        assertEquals(
            listOf("Issues" to "#612", "Issues" to "#1354", "Issues" to "#1099"),
            found("active issues: #612 #1354 #1099", ClipDetect("Issues", "#\\d+")),
        )
    }

    @Test fun handles() {
        assertEquals(
            listOf("Shortcuts" to "@gif_3", "Shortcuts" to "@calc"),
            found("shortcuts @gif_3, @calc", ClipDetect("Shortcuts", "@\\w+")),
        )
    }

    @Test fun aLinkKeepsItsOwnFragment() {
        val parts = ClipEntities.extract(
            "see github.com/a/b/issues/612#issuecomment-1 and #7",
            detects = ClipDetects.compileAll(listOf(ClipDetect("Issues", "#\\w+"))),
        )
        assertEquals(listOf("#7"), parts.filter { it.kind == ClipEntityKind.CUSTOM }.map { it.value })
    }

    @Test fun offOrBrokenOrEmptyMatchingDetectsFindNothing() {
        assertEquals(emptyList<Pair<String?, String>>(), found("#612", ClipDetect("Off", "#\\d+", enabled = false)))
        assertNull(ClipDetects.compile(ClipDetect("Broken", "#(")))
        assertNull(ClipDetects.compile(ClipDetect("Empty", "x*")))
    }

    @Test fun storedAndReadBack() {
        val detects = listOf(ClipDetect("Issues", "#\\d+"), ClipDetect("", "@\\w+", enabled = false))
        assertEquals(detects, ClipDetects.decode(ClipDetects.encode(detects)))
        assertEquals(emptyList<ClipDetect>(), ClipDetects.decode("not json"))
    }
}
