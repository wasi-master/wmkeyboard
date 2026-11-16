package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Links written as the tail of the one before them (#612), and every real ending. */
class ClipLinkChainTest {

    private fun links(text: String): List<String> =
        ClipEntities.extract(text).filter { it.kind == ClipEntityKind.URL }.sortedBy { it.start }.map { it.value }

    @Test fun aQueryValueAfterAnEqualsSign() {
        assertEquals(
            listOf("youtube.com/watch?v=Z7pkvfRl5S8", "youtube.com/watch?v=A1bcdeFg2H3"),
            links("youtube.com/watch?v=Z7pkvfRl5S8  =A1bcdeFg2H3"),
        )
    }

    @Test fun aBareIdShapedLikeTheValue() {
        assertEquals(
            listOf("youtube.com/watch?v=Z7pkvfRl5S8", "youtube.com/watch?v=A1bcdeFg2H3"),
            links("youtube.com/watch?v=Z7pkvfRl5S8; A1bcdeFg2H3"),
        )
    }

    @Test fun aPathFromTheRoot() {
        assertEquals(
            listOf("github.com/wasi-master/wmkeyboard", "github.com/wasi-master/wmkeyboard-addon-repository/"),
            links("wasi-master: github.com/wasi-master/wmkeyboard; /wasi-master/wmkeyboard-addon-repository/"),
        )
    }

    @Test fun theWorkedExampleFromTheIssue() {
        val text = "the videos youtube.com/watch?v=Z7pkvfRl5S8,=A1bcdeFg2H3; issue 1: " +
            "github.com/wasi-master/wmkeyboard/issues/612; (issue 2): /1312#issuecomment-6917569273; " +
            "downloads: github.com/wasi-master/wmkeyboard-data"
        assertEquals(
            listOf(
                "youtube.com/watch?v=Z7pkvfRl5S8",
                "youtube.com/watch?v=A1bcdeFg2H3",
                "github.com/wasi-master/wmkeyboard/issues/612",
                "github.com/wasi-master/wmkeyboard/issues/1312#issuecomment-6917569273",
                "github.com/wasi-master/wmkeyboard-data",
            ),
            links(text),
        )
        // The card's count and its list, which the panel takes from byClip.
        val item = ClipItem(id = 1, text = text, timestamp = 0)
        assertEquals(5, ClipEntities.byClip(listOf(item))[1L].orEmpty().count { it.kind == ClipEntityKind.URL })
    }

    @Test fun wordsAfterALinkAreNotTails() {
        assertEquals(
            listOf("github.com/wasi-master/wmkeyboard"),
            links("github.com/wasi-master/wmkeyboard is well-known and downloads fast"),
        )
    }

    @Test fun aSchemeIsKept() {
        assertEquals(
            "https://example.com/a/c",
            ClipLinkChain.resolve("https://example.com/a/b", "/c"),
        )
        assertNull(ClipLinkChain.resolve("https://example.com/a/b", "plain"))
    }

    @Test fun everyRealEndingIsALink() {
        for (ending in listOf("to", "st", "finance", "li", "su", "moe", "cr", "monster", "click", "lv", "tube", "gl", "now", "si", "rip")) {
            assertEquals("https://site.$ending", ClipLinks.asUrl("site.$ending"))
        }
        // Endings that are file names too need a path.
        for (ending in listOf("sh", "gd", "pm")) {
            assertNull(ClipLinks.asUrl("site.$ending"))
            assertEquals("https://site.$ending/x", ClipLinks.asUrl("site.$ending/x"))
        }
        assertNull(ClipLinks.asUrl("install.sh"))
        assertNull(ClipLinks.asUrl("notes.txt"))
        assertNull(ClipLinks.asUrl("main.py"))
    }
}
