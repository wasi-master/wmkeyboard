package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Triggers that reach back past the composing buffer — a punctuation lead-in
 * like `:shrug`, a phrase like `gr db` — extra spellings of a trigger, and
 * carrying the trigger's capitals into what it expands to.
 *
 * The prefix path exists because the keyboard's composing buffer only ever
 * holds the last word, so neither `:shrug` nor `gr db` can go through the plain
 * whole-word lookup. That is the property most of these guard.
 */
class SnippetPrefixTriggerTest {

    private fun snip(
        trigger: String? = null,
        text: String = "expanded",
        id: Long = 1,
        aliases: List<String> = emptyList(),
        confirm: Boolean = false,
        propagateCase: Boolean = false,
        style: UppercaseStyle = UppercaseStyle.CAPITALIZE,
    ) = Snippet(
        id = id,
        label = "s$id",
        text = text,
        trigger = trigger,
        aliases = aliases,
        confirm = confirm,
        propagateCase = propagateCase,
        uppercaseStyle = style,
    )

    // ---- splitting ----

    @Test
    fun `a prefix trigger splits into its punctuation and its word`() {
        assertEquals(SnippetMatcher.Prefixed(":", "shrug"), SnippetMatcher.splitPrefix(":shrug"))
        assertEquals(SnippetMatcher.Prefixed(";", "ty"), SnippetMatcher.splitPrefix(";ty"))
        assertEquals(SnippetMatcher.Prefixed("//", "date"), SnippetMatcher.splitPrefix("//date"))
    }

    @Test
    fun `a plain word is not a prefix trigger`() {
        assertNull(SnippetMatcher.splitPrefix("omw"))
        assertNull(SnippetMatcher.splitPrefix("brb2"))
        assertNull(SnippetMatcher.splitPrefix("it's"))
    }

    @Test
    fun `a trigger with no word part is refused`() {
        // Nothing would ever be looked up, so it must not be stored as if it
        // worked. The Espanso importer reports these.
        assertNull(SnippetMatcher.splitPrefix("->"))
        assertNull(SnippetMatcher.splitPrefix("!!"))
    }

    @Test
    fun `a trigger that ends in punctuation is refused`() {
        // The last word is what the buffer holds and what the lookup is on, so
        // a trigger with nothing after its punctuation has no way in.
        assertNull(SnippetMatcher.splitPrefix(":x:"))
        assertNull(SnippetMatcher.splitPrefix("a.b."))
    }

    @Test
    fun `a multi-word trigger splits at its last word`() {
        assertEquals(SnippetMatcher.Prefixed("gr ", "db"), SnippetMatcher.splitPrefix("gr db"))
        assertEquals(
            SnippetMatcher.Prefixed("on my ", "way"),
            SnippetMatcher.splitPrefix("on my way"),
        )
        // Punctuation in the middle is only more of the same: everything up to
        // the last word is confirmed by reading the field.
        assertEquals(SnippetMatcher.Prefixed(":a-", "b"), SnippetMatcher.splitPrefix(":a-b"))
        assertEquals(SnippetMatcher.Prefixed("a.", "b"), SnippetMatcher.splitPrefix("a.b"))
    }

    @Test
    fun `a lead-in with whitespace that is not a space is refused`() {
        // Neither is reachable from a keyboard, so storing one is storing an
        // inert trigger.
        assertNull(SnippetMatcher.splitPrefix("gr\tdb"))
        assertNull(SnippetMatcher.splitPrefix("gr\ndb"))
    }

    @Test
    fun `an over-long prefix is refused`() {
        assertNotNull(SnippetMatcher.splitPrefix(":".repeat(SnippetMatcher.MAX_PREFIX) + "x"))
        assertNull(SnippetMatcher.splitPrefix(":".repeat(SnippetMatcher.MAX_PREFIX + 1) + "x"))
    }

    // ---- the index ----

    @Test
    fun `a prefix trigger is not in the plain lookup`() {
        val index = SnippetIndex.of(listOf(snip(trigger = ":shrug")))
        assertNull(index.matchTrigger("shrug"))
        assertNull(index.matchTrigger(":shrug"))
        assertTrue(index.hasPrefixTriggers)
    }

    @Test
    fun `a prefix trigger matches when the punctuation is in front of the word`() {
        val index = SnippetIndex.of(listOf(snip(trigger = ":shrug", text = "shrugged")))
        val hit = index.matchPrefix("shrug", "hello :")
        assertNotNull(hit)
        assertEquals(":", hit!!.prefix)
        assertEquals("shrugged", hit.snippet.text)
    }

    @Test
    fun `a prefix trigger does not match without its punctuation`() {
        val index = SnippetIndex.of(listOf(snip(trigger = ":shrug")))
        assertNull(index.matchPrefix("shrug", "hello "))
        assertNull(index.matchPrefix("shrug", ""))
    }

    @Test
    fun `the longest prefix wins`() {
        val index = SnippetIndex.of(
            listOf(
                snip(trigger = ":x", text = "one", id = 1),
                snip(trigger = "::x", text = "two", id = 2),
            ),
        )
        assertEquals("two", index.matchPrefix("x", "a ::")!!.snippet.text)
        assertEquals("one", index.matchPrefix("x", "a :")!!.snippet.text)
    }

    @Test
    fun `matching is case-insensitive on the word part`() {
        val index = SnippetIndex.of(listOf(snip(trigger = ":Shrug")))
        assertNotNull(index.matchPrefix("SHRUG", "x :"))
        assertNotNull(index.matchPrefix("shrug", "x :"))
    }

    // ---- multi-word triggers ----

    @Test
    fun `a multi-word trigger matches the words in front of the buffer`() {
        val index = SnippetIndex.of(listOf(snip(trigger = "gr db", text = "./gradlew assembledebug")))
        val hit = index.matchPrefix("db", "run gr ")
        assertNotNull(hit)
        assertEquals("gr ", hit!!.prefix)
        assertEquals("./gradlew assembledebug", hit.snippet.text)
        // At the very start of the field too: there is nothing in front to
        // break the boundary.
        assertNotNull(index.matchPrefix("db", "gr "))
    }

    @Test
    fun `a multi-word trigger is case-insensitive on its earlier words`() {
        val index = SnippetIndex.of(listOf(snip(trigger = "gr db")))
        assertNotNull(index.matchPrefix("DB", "GR "))
        assertNotNull(index.matchPrefix("db", "Gr "))
    }

    @Test
    fun `a multi-word trigger needs a word boundary in front of it`() {
        val index = SnippetIndex.of(listOf(snip(trigger = "gr db")))
        // "xgr db" is not "gr db": the earlier word has to be a word of its own.
        assertNull(index.matchPrefix("db", "xgr "))
        assertNull(index.matchPrefix("db", "9gr "))
        // Punctuation is a boundary, so this one still fires.
        assertNotNull(index.matchPrefix("db", "(gr "))
    }

    @Test
    fun `punctuation in front of a word trigger stays a match`() {
        // The rule above must not tighten a lead-in that starts with
        // punctuation: `hello:shrug` has always fired `:shrug`.
        val index = SnippetIndex.of(listOf(snip(trigger = ":shrug")))
        assertNotNull(index.matchPrefix("shrug", "hello:"))
    }

    @Test
    fun `the longer multi-word trigger wins over the shorter one`() {
        val index = SnippetIndex.of(
            listOf(
                snip(trigger = "db", text = "one", id = 1),
                snip(trigger = "gr db", text = "two", id = 2),
            ),
        )
        // The plain trigger is still in the plain lookup and the phrase in the
        // prefix one; the commit path asks the prefix side first.
        assertEquals("one", index.matchTrigger("db")!!.text)
        assertEquals("two", index.matchPrefix("db", "x gr ")!!.snippet.text)
        assertNull(index.matchPrefix("db", "x "))
    }

    @Test
    fun `capitals are read from the whole phrase`() {
        val on = snip(propagateCase = true)
        assertEquals(TriggerCasing.UPPER, SnippetStore.casingFor(on, "GR DB"))
        assertEquals(TriggerCasing.CAPITALIZE, SnippetStore.casingFor(on, "Gr db"))
        assertEquals(TriggerCasing.NONE, SnippetStore.casingFor(on, "gr db"))
    }

    @Test
    fun `the free gate answers without reading anything`() {
        val index = SnippetIndex.of(listOf(snip(trigger = ":shrug")))
        assertTrue(index.prefixCandidates("shrug").isNotEmpty())
        assertTrue(index.prefixCandidates("something-else").isEmpty())
        // And a list with no prefix trigger costs nothing at all.
        val plain = SnippetIndex.of(listOf(snip(trigger = "omw")))
        assertFalse(plain.hasPrefixTriggers)
        assertTrue(plain.prefixCandidates("omw").isEmpty())
    }

    @Test
    fun `an asking trigger never answers for an expanding one`() {
        val index = SnippetIndex.of(listOf(snip(trigger = ":ask", confirm = true)))
        assertNull(index.matchPrefix("ask", "x :", confirm = false))
        assertNotNull(index.matchPrefix("ask", "x :", confirm = true))
        assertTrue(index.hasConfirmPrefixTriggers)
    }

    // ---- aliases ----

    @Test
    fun `every alias fires the same snippet`() {
        val index = SnippetIndex.of(
            listOf(snip(trigger = "hi", aliases = listOf("hello", ":hey"), text = "Hello there")),
        )
        assertEquals("Hello there", index.matchTrigger("hi")!!.text)
        assertEquals("Hello there", index.matchTrigger("hello")!!.text)
        // Including one that leads with punctuation.
        assertEquals("Hello there", index.matchPrefix("hey", "x :")!!.snippet.text)
    }

    @Test
    fun `spellings lists the trigger and its aliases`() {
        assertEquals(
            listOf("hi", "hello"),
            snip(trigger = "hi", aliases = listOf("hello")).spellings(),
        )
        assertEquals(emptyList<String>(), snip(trigger = null).spellings())
        assertEquals(listOf("hi"), snip(trigger = "hi", aliases = listOf(" ", "")).spellings())
    }

    @Test
    fun `a snippet with a trigger never reaches the pattern side`() {
        // The rule that already held for one trigger has to hold for aliases.
        val both = Snippet(
            id = 1,
            label = "s",
            text = "t",
            trigger = ":x",
            triggerPattern = "^anything$",
        )
        val index = SnippetIndex.of(listOf(both))
        assertFalse(index.hasPatterns)
        assertNotNull(index.matchPrefix("x", "a :"))
    }

    // ---- capitals ----

    @Test
    fun `capitals are carried only when the snippet asks`() {
        val off = snip(propagateCase = false)
        assertEquals(TriggerCasing.NONE, SnippetStore.casingFor(off, "OMW"))

        val on = snip(propagateCase = true)
        assertEquals(TriggerCasing.UPPER, SnippetStore.casingFor(on, "OMW"))
        assertEquals(TriggerCasing.CAPITALIZE, SnippetStore.casingFor(on, "Omw"))
        assertEquals(TriggerCasing.NONE, SnippetStore.casingFor(on, "omw"))
    }

    @Test
    fun `the style only decides what a leading capital means`() {
        val words = snip(propagateCase = true, style = UppercaseStyle.CAPITALIZE_WORDS)
        assertEquals(TriggerCasing.CAPITALIZE_WORDS, SnippetStore.casingFor(words, "Omw"))
        // An all-caps trigger always shouts back, whatever the style says.
        assertEquals(TriggerCasing.UPPER, SnippetStore.casingFor(words, "OMW"))
    }

    @Test
    fun `a punctuation prefix is ignored when reading the capitals`() {
        val on = snip(propagateCase = true)
        assertEquals(TriggerCasing.CAPITALIZE, SnippetStore.casingFor(on, ":Omw"))
        assertEquals(TriggerCasing.UPPER, SnippetStore.casingFor(on, ":OMW"))
    }

    @Test
    fun `a single letter is not shouting`() {
        val on = snip(propagateCase = true)
        // "A" is a capital, not capitals: it takes the style, not the shout.
        assertEquals(TriggerCasing.CAPITALIZE, SnippetStore.casingFor(on, "A"))
    }

    @Test
    fun `the casings do what they say`() {
        assertEquals("on my way", TriggerCasing.NONE.apply("on my way"))
        assertEquals("ON MY WAY", TriggerCasing.UPPER.apply("on my way"))
        assertEquals("On my way", TriggerCasing.CAPITALIZE.apply("on my way"))
        assertEquals("On My Way", TriggerCasing.CAPITALIZE_WORDS.apply("on my way"))
    }

    @Test
    fun `capitalizing leaves the rest of the text exactly as written`() {
        // A snippet that deliberately holds an acronym keeps it.
        assertEquals("Send the PDF", TriggerCasing.CAPITALIZE.apply("send the PDF"))
        assertEquals("Send The PDF", TriggerCasing.CAPITALIZE_WORDS.apply("send the PDF"))
    }

    @Test
    fun `capitalizing text that starts with punctuation finds the first letter`() {
        assertEquals("\"Hello\"", TriggerCasing.CAPITALIZE.apply("\"hello\""))
        assertEquals("  Hi there", TriggerCasing.CAPITALIZE.apply("  hi there"))
    }

    @Test
    fun `a case mapping that changes length does not lose a character`() {
        // "ß" uppercases to two characters, so an index into the builder is not
        // an index into the source.
        assertEquals("SStraße", TriggerCasing.CAPITALIZE.apply("ßtraße"))
    }
}
