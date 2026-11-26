package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipRulesTest {

    private val stripScheme = ClipCopyRule("https?://(www\\.)?")

    @Test fun `a rule removes its match wherever it is in the copy`() {
        val rules = listOf(stripScheme)
        assertEquals(
            "wmkeyboard.pages.dev/tools/clipboard/#numbered-clips",
            ClipCopyRules.apply("https://wmkeyboard.pages.dev/tools/clipboard/#numbered-clips", rules),
        )
        assertEquals("github.com/wasi-master/wmkeyboard/", ClipCopyRules.apply("https://www.github.com/wasi-master/wmkeyboard/", rules))
        assertEquals(
            "New keyboard: github.com/wasi-master/wmkeyboard/. Seems highly recommended",
            ClipCopyRules.apply("New keyboard: https://www.github.com/wasi-master/wmkeyboard/. Seems highly recommended", rules),
        )
    }

    @Test fun `a replacement can add to the match`() {
        val rules = listOf(ClipCopyRule("(\\d{6})", "code $1"))
        assertEquals("Your code 482913 here", ClipCopyRules.apply("Your 482913 here", rules))
    }

    @Test fun `off, broken and empty rules leave the copy alone`() {
        val rules = listOf(
            stripScheme.copy(enabled = false),
            ClipCopyRule("[0-9"),
            ClipCopyRule(""),
            ClipCopyRule("x", "$9"),
        )
        assertEquals("https://x.com", ClipCopyRules.apply("https://x.com", rules))
    }

    @Test fun `rules survive a round trip through storage`() {
        val rules = listOf(stripScheme, ClipCopyRule("a", "b", enabled = false))
        assertEquals(rules, ClipCopyRules.decode(ClipCopyRules.encode(rules)))
        assertEquals(emptyList<ClipCopyRule>(), ClipCopyRules.decode("not json"))
        assertEquals(emptyList<ClipCopyRule>(), ClipCopyRules.decode(null))
    }

    @Test fun `clips join with the joiner between them`() {
        assertEquals("a | b | c", ClipCombine.combine(listOf("a", "b", "c"), " | "))
        assertEquals("a\nb", ClipCombine.combine(listOf("a", "b"), "\n"))
    }

    @Test fun `base and append keep only the matching part of each clip`() {
        val joined = ClipCombine.combine(
            listOf("https://m.youtube.com/watch?v=Z7pkvfRl5S8", "https://m.youtube.com/watch?v=A1bcdeFg2H3", "no id here"),
            joiner = "; ",
            base = "youtube.com/watch?v",
            append = "\\=[a-zA-Z0-9_]{11}",
        )
        assertEquals("youtube.com/watch?v=Z7pkvfRl5S8; =A1bcdeFg2H3", joined)
    }

    @Test fun `a template rewrites each match and the prefix reads the first clip's groups`() {
        val joined = ClipCombine.combine(
            listOf("https://github.com/wasi-master/wmkeyboard", "https://github.com/wasi-master/wmkeyboard-addon-repository/"),
            joiner = "; ",
            base = "$1: github.com",
            append = "github\\.com/([^/]+)(/[^/]+)",
            template = "/$1$2",
        )
        assertEquals("wasi-master: github.com/wasi-master/wmkeyboard; /wasi-master/wmkeyboard-addon-repository", joined)
    }

    @Test fun `a template keeps an escaped dollar and drops a missing group`() {
        val match = Regex("(a)(b)?").find("a")!!
        assertEquals("\$1 is a, 2 is []", ClipCombine.expand("\\$1 is $1, 2 is [$2]", match))
        assertEquals("a", ClipCombine.expand("\${1}", match))
    }

    @Test fun `the preview marks what each clip gives`() {
        assertEquals(listOf(0..1, null), ClipCombine.matchedRanges(listOf("42 x", "none"), "\\d+"))
        assertEquals(listOf(0..3), ClipCombine.matchedRanges(listOf("text"), ""))
    }

    @Test fun `decorated letters become plain ones`() {
        assertEquals("Bold Italic", ClipPlainLetters.plain("𝐁𝐨𝐥𝐝 𝘐𝘵𝘢𝘭𝘪𝘤"))
        assertEquals("ABC 123", ClipPlainLetters.plain("ＡＢＣ １２３"))
        assertEquals("small caps", ClipPlainLetters.plain("ꜱᴍᴀʟʟ ᴄᴀᴘꜱ"))
        assertEquals("strike", ClipPlainLetters.plain("s̶t̶r̶i̶k̶e̶"))
        assertEquals("RN", ClipPlainLetters.plain("ℝℕ"))
        // Symbols of their own stay: ™ and ℃ are not dressed-up letters.
        assertEquals("Brand™ 20℃ café", ClipPlainLetters.plain("Brand™ 20℃ café"))
    }

    @Test fun `superscript and subscript letters become plain ones`() {
        assertEquals("aeiou", ClipPlainLetters.plain("ᵃᵉⁱᵒᵘ"))
        assertEquals("tiny text", ClipPlainLetters.plain("ᵗⁱⁿʸ ᵗᵉˣᵗ"))
        assertEquals("ABDE", ClipPlainLetters.plain("ᴬᴮᴰᴱ"))
        assertEquals("aeox", ClipPlainLetters.plain("ₐₑₒₓ"))
        // Raised and lowered digits and signs keep what they say.
        assertEquals("x² H₂O 10⁻³", ClipPlainLetters.plain("x² H₂O 10⁻³"))
        // An accent from the same block is not a letter.
        assertEquals("a˘", ClipPlainLetters.plain("a˘"))
    }

    @Test fun `nothing to join is null`() {
        assertNull(ClipCombine.combine(listOf("abc"), " ", append = "\\d+"))
        assertNull(ClipCombine.combine(listOf("abc"), " ", append = "[0-9"))
        assertNull(ClipCombine.combine(emptyList(), " "))
    }
}
