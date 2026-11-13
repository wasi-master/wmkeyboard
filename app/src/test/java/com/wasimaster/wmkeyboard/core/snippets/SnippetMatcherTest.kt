package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetMatcherTest {

    private fun index(vararg snippets: Snippet) = SnippetIndex.of(snippets.toList())

    private fun snip(
        pattern: String,
        text: String,
        words: Int = 0,
        id: Long = 1,
    ) = Snippet(id = id, label = "s$id", text = text, triggerPattern = pattern, triggerWords = words)

    // ---- literal head extraction ----

    @Test
    fun `head is the literal run a pattern always starts with`() {
        assertEquals("hello", SnippetMatcher.headOf("^hello (.+)$"))
        assertEquals("hello", SnippetMatcher.headOf("^hello$"))
        assertEquals("hi", SnippetMatcher.headOf("^hi (.+)$"))
    }

    @Test
    fun `head does not need an anchor`() {
        // matchPattern uses matches(), so the span is anchored either way.
        assertEquals("hello", SnippetMatcher.headOf("hello (.+)"))
    }

    @Test
    fun `head skips a leading inline flag group`() {
        assertEquals("hello", SnippetMatcher.headOf("(?i)^hello "))
        assertEquals("hello", SnippetMatcher.headOf("(?im)^hello "))
    }

    @Test
    fun `a quantified last character is not part of the head`() {
        assertEquals("hell", SnippetMatcher.headOf("^hello?"))
        assertEquals("hell", SnippetMatcher.headOf("^hello{2}"))
    }

    @Test
    fun `an escaped literal decodes into the head`() {
        assertEquals("$", SnippetMatcher.headOf("^\\$(\\d+)"))
    }

    @Test
    fun `patterns with no literal start have no head`() {
        assertNull(SnippetMatcher.headOf("^(?:hello|hi) "))
        assertNull(SnippetMatcher.headOf("^\\d+"))
        assertNull(SnippetMatcher.headOf("^(.+)$"))
        assertNull(SnippetMatcher.headOf(".*foo"))
    }

    @Test
    fun `a case-sensitive pattern is not gated`() {
        // The head index is lowercased, so it cannot answer for this one.
        assertNull(SnippetMatcher.headOf("(?-i)^Hello "))
    }

    // ---- matching ----

    @Test
    fun `a pattern expands the words behind the cursor`() {
        val hit = index(snip("^hello (.+)$", "Hello, \$1! Nice to meet you."))
            .matchPattern("hello John", atFieldStart = true)
        assertNotNull(hit)
        assertEquals("Hello, John! Nice to meet you.", hit!!.text)
        assertEquals(10, hit.consumedChars)
        assertEquals("hello John", hit.consumedText)
    }

    @Test
    fun `matching ignores case`() {
        val hit = index(snip("^hello (.+)$", "Hi \$1"))
            .matchPattern("HELLO john", atFieldStart = true)
        assertEquals("Hi john", hit?.text)
    }

    @Test
    fun `the span is measured against itself not the whole window`() {
        val hit = index(snip("^hello (.+)$", "Hi \$1"))
            .matchPattern("say hello John", atFieldStart = true)
        assertEquals(10, hit?.consumedChars)
        assertEquals("hello John", hit?.consumedText)
    }

    @Test
    fun `a pattern cannot reach further back than its word budget`() {
        val two = index(snip("^hello (.+)$", "Hi \$1", words = 2))
        assertNull(two.matchPattern("hello John Smith", atFieldStart = true))
        val three = index(snip("^hello (.+)$", "Hi \$1", words = 3))
        assertEquals("Hi John Smith", three.matchPattern("hello John Smith", atFieldStart = true)?.text)
    }

    @Test
    fun `an unset word budget falls back to the default`() {
        val hit = index(snip("^hello (.+)$", "Hi \$1", words = 0))
            .matchPattern("hello John Smith", atFieldStart = true)
        // DEFAULT_WORDS is 3, which is exactly this span.
        assertEquals("Hi John Smith", hit?.text)
    }

    @Test
    fun `the shortest span wins`() {
        val hit = index(
            snip("^(.+)$", "ALL:\$1", id = 1),
            snip("^hi (.+)$", "HI:\$1", id = 2),
        ).matchPattern("hi there", atFieldStart = true)
        // Nearest-first: "there" alone is tried before "hi there", and the
        // catch-all matches it. The destructive operation errs toward less.
        assertEquals("ALL:there", hit?.text)
        assertEquals(5, hit?.consumedChars)
    }

    @Test
    fun `a span may start at the spaces in front of a word`() {
        // The regression behind this: a "space before punctuation" fixer can
        // only ever match a span that begins with the space it removes, and
        // word-only starts never handed it one.
        val hit = index(snip("\\s+([.,?!:;])", "\$1")).matchPattern("hi .", atFieldStart = true)
        assertNotNull(hit)
        assertEquals(".", hit?.text)
        assertEquals(2, hit?.consumedChars)
        assertEquals(" .", hit?.consumedText)
    }

    @Test
    fun `a word is tried before the spaces in front of it`() {
        val hit = index(snip("(.*)\\.", "[\$1]")).matchPattern("hi .", atFieldStart = true)
        assertEquals("[]", hit?.text)
        assertEquals(1, hit?.consumedChars)
    }

    @Test
    fun `the spaces in front of a word share its word budget`() {
        val one = index(snip("\\s+b x", "!", words = 1))
        assertNull(one.matchPattern("a b x", atFieldStart = true))
        val two = index(snip("\\s+b x", "!", words = 2))
        assertEquals(4, two.matchPattern("a b x", atFieldStart = true)?.consumedChars)
    }

    @Test
    fun `spaces at the start of a cut window never anchor a match`() {
        val idx = index(snip("\\s+x", "!"))
        assertNull(idx.matchPattern(" x", atFieldStart = false))
        assertEquals("!", idx.matchPattern(" x", atFieldStart = true)?.text)
    }

    @Test
    fun `a pattern never reaches across a line`() {
        assertNull(
            index(snip("^hello (.+)$", "Hi \$1"))
                .matchPattern("hello\nJohn", atFieldStart = true),
        )
    }

    @Test
    fun `a truncated window never anchors a match at its first character`() {
        val single = index(snip("^hello (.+)$", "Hi \$1"))
        // The window may have cut "hello" out of "othello"; matching the tail
        // would delete text whose start the user cannot see.
        assertNull(single.matchPattern("hello John", atFieldStart = false))
        assertNotNull(single.matchPattern(" hello John", atFieldStart = false))
    }

    @Test
    fun `a window cut at the cap is not a field start whatever the caller says`() {
        // The cut can land mid-word, so the first character of what is left
        // begins nothing, even when the caller believed it read from the start.
        val single = index(snip("^hello (.+)$", "Hi \$1"))
        // The cut falls inside one long word, so nothing is left to anchor on.
        assertNull(
            single.matchPattern(
                "z".repeat(SnippetMatcher.MAX_WINDOW) + "hello John",
                atFieldStart = true,
            ),
        )
        // The same cut, with a space in front of the match: the span starts at
        // a real word boundary rather than at the window's edge, so it stands.
        assertNotNull(
            single.matchPattern(
                "z ".repeat(SnippetMatcher.MAX_WINDOW / 2) + "hello John",
                atFieldStart = true,
            ),
        )
    }

    @Test
    fun `a line break is a real start even mid-field`() {
        assertNotNull(
            index(snip("^hello (.+)$", "Hi \$1"))
                .matchPattern("chatter\nhello John", atFieldStart = false),
        )
    }

    @Test
    fun `a plain trigger keeps a snippet off the pattern side`() {
        val both = Snippet(
            id = 1,
            label = "s",
            text = "x",
            trigger = "omw",
            triggerPattern = "^hello (.+)$",
        )
        val index = index(both)
        assertEquals(1L, index.matchTrigger("OMW")?.id)
        assertNull(index.matchPattern("hello John", atFieldStart = true))
    }

    @Test
    fun `no match answers null`() {
        assertNull(
            index(snip("^hello (.+)$", "Hi \$1"))
                .matchPattern("goodbye John", atFieldStart = true),
        )
    }

    // ---- capture-group substitution ----

    @Test
    fun `a captured group is not expanded as a template variable`() {
        // The security assertion. Under a naive "substitute, then expand"
        // order, text the user typed into the field would paste the clipboard
        // into an outgoing message.
        val hit = index(snip("^note (.+)$", "Note: \$1"))
            .matchPattern(
                "note remember {clip}",
                atFieldStart = true,
                context = SnippetStore.Companion.Context(clipboard = "SECRET"),
            )
        assertEquals("Note: remember {clip}", hit?.text)
        assertTrue(hit!!.text.contains("{clip}"))
        assertTrue(!hit.text.contains("SECRET"))
    }

    @Test
    fun `template variables outside a group still expand`() {
        val hit = index(snip("^note (.+)$", "Note: \$1 [{clip}]"))
            .matchPattern(
                "note buy milk",
                atFieldStart = true,
                context = SnippetStore.Companion.Context(clipboard = "PASTED"),
            )
        assertEquals("Note: buy milk [PASTED]", hit?.text)
    }

    @Test
    fun `a captured group is not substituted again`() {
        val hit = index(snip("^calc (.+) (.+)$", "\$1 then \$2"))
            .matchPattern("calc \$2 tail", atFieldStart = true)
        assertEquals("\$2 then tail", hit?.text)
    }

    @Test
    fun `a captured group cannot move the cursor`() {
        val hit = index(snip("^mark (.+)$", "[\$1]{cursor}!"))
            .matchPattern("mark a${SnippetStore.CURSOR_MARKER}b", atFieldStart = true)
        assertEquals("[ab]!", hit?.text)
        // The template's own marker sits after the closing bracket.
        assertEquals(4, hit?.cursorOffset)
    }

    @Test
    fun `dollar escapes and out of range references`() {
        assertEquals("\$", expand("^x$", "\$\$", "x"))
        assertEquals("\$5", expand("^up (.+)$", "\$\$\$1", "up 5"))
        assertEquals("\$x", expand("^x$", "\$x", "x"))
        assertEquals("up 5", expand("^up (.+)$", "\$0", "up 5"))
        // Two groups exist, so $7 stands for nothing at all.
        assertEquals("|", expand("^a (.+) (.+)$", "\$7|\$7", "a b c"))
    }

    @Test
    fun `group transforms`() {
        assertEquals("JOHN", expand("^up (.+)$", "\${1:upper}", "up john"))
        assertEquals("john", expand("^up (.+)$", "\${1:lower}", "up JOHN"))
        assertEquals("John Smith", expand("^up (.+)$", "\${1:title}", "up jOHN sMITH"))
        assertEquals("john", expand("^up (.+)$", "\${1:trim}", "up   john  "))
        // An unknown transform leaves the group alone rather than failing.
        assertEquals("john", expand("^up (.+)$", "\${1:sideways}", "up john"))
    }

    private fun expand(pattern: String, text: String, window: String): String? =
        index(snip(pattern, text)).matchPattern(window, atFieldStart = true)?.text

    // ---- panel insertion ----

    @Test
    fun `a panel tap blanks every reference and marks the first`() {
        val blank = SnippetMatcher.blankTemplate("Hello, \$1! I am \$2.")
        assertEquals("Hello, ! I am .", blank.text)
        assertEquals(7, blank.blankCaret)
    }

    @Test
    fun `a cursor marker outranks the first blank on a panel tap`() {
        val blank = SnippetMatcher.blankTemplate("[\$1]{cursor}")
        assertEquals("[]", blank.text)
        assertEquals(2, blank.blankCaret)
    }

    // ---- refusing and stopping runaway patterns ----

    @Test
    fun `validate refuses what no snippet needs and what nothing can bound`() {
        assertEquals(SnippetMatcher.Fault.EMPTY, SnippetMatcher.validate("  ")?.fault)
        assertEquals(
            SnippetMatcher.Fault.TOO_LONG,
            SnippetMatcher.validate("a".repeat(SnippetMatcher.MAX_PATTERN_LENGTH + 1))?.fault,
        )
        assertEquals(SnippetMatcher.Fault.SYNTAX, SnippetMatcher.validate("^hello (.+$")?.fault)
        assertEquals(SnippetMatcher.Fault.BACKREFERENCE, SnippetMatcher.validate("^(.+) \\1$")?.fault)
        assertEquals(
            SnippetMatcher.Fault.NESTED_QUANTIFIER,
            SnippetMatcher.validate("^(a+)+$")?.fault,
        )
        assertNull(SnippetMatcher.validate("^hello (.+)$"))
        // A quantified group whose body quantifies nothing is ordinary.
        assertNull(SnippetMatcher.validate("^(abc)+$"))
    }

    @Test
    fun `a pattern that will not compile is left out of the index`() {
        assertNull(
            index(snip("^hello (.+$", "Hi \$1"))
                .matchPattern("hello John", atFieldStart = true),
        )
    }

    @Test(timeout = 5_000)
    fun `a pattern that backtracks too much is stopped for good`() {
        // A chain of optional letters followed by a run that cannot be there:
        // exponential, and it holds no quantified group, so validate has
        // nothing to object to. That is the point of the step budget — the
        // screen catches the shapes it knows, and the budget catches the rest.
        val source = "^" + "a?".repeat(16) + "a{16}b$"
        assertNull(SnippetMatcher.validate(source))
        val runaway = SnippetIndex.of(
            listOf(Snippet(id = 7, label = "s", text = "x", triggerPattern = source)),
        )
        val window = "a".repeat(16)
        assertNull(runaway.matchPattern(window, atFieldStart = true))
        assertEquals(setOf(7L), runaway.stopped())
        // Stopped means stopped: the second call does not run it again.
        assertNull(runaway.matchPattern(window, atFieldStart = true))
    }

    @Test
    fun `the window is cut to its cap`() {
        // A pattern that needs 200 characters never matches, because it never
        // sees more than MAX_WINDOW of them.
        assertNull(
            index(snip("^x{200}$", "seen"))
                .matchPattern("x".repeat(400), atFieldStart = true),
        )
        assertEquals(
            "seen",
            index(snip("^x{80}$", "seen"))
                .matchPattern("x".repeat(80), atFieldStart = true)?.text,
        )
    }

    @Test(timeout = 5_000)
    fun `many ungated patterns stay within the attempt budget`() {
        val many = (1..100L).map {
            Snippet(id = it, label = "s$it", text = "x", triggerPattern = "^(zzz$it)$")
        }
        assertNull(SnippetIndex.of(many).matchPattern("hello there", atFieldStart = true))
    }

    @Test
    fun `an index with no patterns answers without looking`() {
        val plain = index(Snippet(id = 1, label = "s", text = "On my way!", trigger = "omw"))
        assertTrue(!plain.hasPatterns)
        assertNull(plain.matchPattern("hello John", atFieldStart = true))
    }

    // ---- asking versus expanding ----

    @Test
    fun `neither kind of pattern answers for the other`() {
        // The two are asked for at different moments — one at the commit that
        // rewrites text, one on every keystroke to fill a chip — so a leak
        // either way is a pattern firing when nobody asked it to.
        val asks = index(snip("^hello (.+)$", "Hello, \$1!").copy(confirm = true))
        assertNull(asks.matchPattern("hello John", atFieldStart = true))
        assertEquals(
            "Hello, John!",
            asks.matchPattern("hello John", atFieldStart = true, confirm = true)?.text,
        )

        val expands = index(snip("^hello (.+)$", "Hello, \$1!"))
        assertNull(expands.matchPattern("hello John", atFieldStart = true, confirm = true))
        assertNotNull(expands.matchPattern("hello John", atFieldStart = true))
    }

    @Test
    fun `an asking pattern does not stop one that expands from firing`() {
        // Both halves of a mixed list have to stay reachable: the search skips
        // the wrong kind rather than stopping at it.
        val mixed = index(
            snip("^hello (.+)$", "asked", id = 1).copy(confirm = true),
            snip("^hi (.+)$", "expanded", id = 2),
        )
        assertEquals("expanded", mixed.matchPattern("hi John", atFieldStart = true)?.text)
        assertEquals(
            "asked",
            mixed.matchPattern("hello John", atFieldStart = true, confirm = true)?.text,
        )
        assertTrue(mixed.hasConfirmPatterns && mixed.hasAutoPatterns)
    }

    @Test
    fun `a half-typed word still matches, so the chip arrives before the space`() {
        // The whole point of the offer: it is derived from the text as it
        // stands, not from a word that has been finished.
        val asks = index(snip("^hello (.+)$", "Hello, \$1!").copy(confirm = true))
        assertEquals(
            "Hello, Jo!",
            asks.matchPattern("hello Jo", atFieldStart = true, confirm = true)?.text,
        )
    }

    // ---- name groups (#1291) ----

    @Test
    fun `name groups are numbered as the pattern counts them`() {
        assertTrue(SnippetMatcher.nameGroupsOf("^hello (.+)$").isEmpty())
        assertEquals(listOf(1), SnippetMatcher.nameGroupsOf("^hello (?<name>.+)$").toList())
        assertEquals(listOf(2), SnippetMatcher.nameGroupsOf("^(hi|hey) (?<name2>.+)$").toList())
        // Lookbehinds, non-capturing groups and escaped brackets open no group.
        assertEquals(
            listOf(2),
            SnippetMatcher.nameGroupsOf("^(?:meet) (?<=t )(\\d+) \\((?<name>[a-z(]+)$").toList(),
        )
        // Another named group is a group like any other.
        assertEquals(listOf(2), SnippetMatcher.nameGroupsOf("^(?<when>\\d+) (?<name>.+)$").toList())
    }

    @Test
    fun `a name group only takes what reads as a name`() {
        val names = setOf("john", "priya")
        val isName: (String) -> Boolean = { it.first().isUpperCase() || it.lowercase() in names }
        val greet = index(snip("^hello (?<name>.+)$", "Hello, \$1! Nice to meet you."))
        assertNull(greet.matchPattern("hello am", atFieldStart = true, isName = isName))
        assertEquals(
            "Hello, john! Nice to meet you.",
            greet.matchPattern("hello john", atFieldStart = true, isName = isName)?.text,
        )
        assertEquals(
            "Hello, Am! Nice to meet you.",
            greet.matchPattern("hello Am", atFieldStart = true, isName = isName)?.text,
        )
        // Every word of a longer capture has to be one.
        assertNull(greet.matchPattern("hello john there", atFieldStart = true, isName = isName))
        assertNotNull(greet.matchPattern("hello Priya Rao", atFieldStart = true, isName = isName))
    }

    @Test
    fun `with nothing to ask, a name group takes any word`() {
        val greet = index(snip("^hello (?<name>.+)$", "Hi \$1"))
        assertEquals("Hi am", greet.matchPattern("hello am", atFieldStart = true)?.text)
    }

    @Test
    fun `an ordinary group is never asked`() {
        val greet = index(snip("^hello (.+)$", "Hi \$1"))
        assertEquals("Hi am", greet.matchPattern("hello am", atFieldStart = true, isName = { false })?.text)
    }
}
