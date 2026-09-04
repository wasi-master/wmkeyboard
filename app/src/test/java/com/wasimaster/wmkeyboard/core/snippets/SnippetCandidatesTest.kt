package com.wasimaster.wmkeyboard.core.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a matched snippet has to offer, and which half of the index it lands in.
 *
 * These two answers are the whole of the feature's contract with the keyboard:
 * one decides what the strip draws, the other whether anything was inserted
 * before it drew.
 */
class SnippetCandidatesTest {

    private fun store(vararg snippets: Snippet): SnippetStore {
        val store = SnippetStore(null)
        for (snippet in snippets) store.add(snippet)
        return store
    }

    @Test
    fun `own expansions come first, then each link's default`() {
        val store = SnippetStore(null)
        val asia = store.add(Snippet(id = 0, label = "Asia", text = "Asia", alternates = listOf("ASIA")))
        val africa = store.add(Snippet(id = 0, label = "Africa", text = "Africa"))
        val parent = store.add(
            Snippet(
                id = 0,
                label = "Continent",
                text = "Continent",
                alternates = listOf("Landmass"),
                children = listOf(asia.id, africa.id),
            ),
        )
        val candidates = store.candidates(parent)
        assertEquals(
            listOf("Continent", "Landmass", "Asia", "Africa"),
            candidates.map { it.text },
        )
        // Only a link's second level is behind a walk; its own first expansion
        // is right there on the strip.
        assertEquals(listOf(false, false, true, true), candidates.map { it.viaChild })
        assertEquals(listOf(false, false, true, false), candidates.map { it.drillable })
        assertEquals(asia.id, candidates[2].snippetId)
    }

    @Test
    fun `two expansions that come out the same are offered once`() {
        val store = SnippetStore(null)
        val child = store.add(Snippet(id = 0, label = "Asia", text = "Asia"))
        val parent = store.add(
            Snippet(id = 0, label = "C", text = "Asia", alternates = listOf("Africa"), children = listOf(child.id)),
        )
        assertEquals(listOf("Asia", "Africa"), store.candidates(parent).map { it.text })
    }

    @Test
    fun `a loop between two snippets is a dead end rather than a hang`() {
        val store = SnippetStore(null)
        val a = store.add(Snippet(id = 0, label = "A", text = "A"))
        val b = store.add(Snippet(id = 0, label = "B", text = "B", children = listOf(a.id)))
        store.update(a.copy(children = listOf(b.id)))
        val from = store.items().first { it.id == a.id }
        assertEquals(listOf("A", "B"), store.candidates(from).map { it.text })
        assertEquals(listOf("B", "A"), store.drillIn(b.id).map { it.text })
    }

    @Test
    fun `a snippet that links to itself does not repeat itself`() {
        val store = SnippetStore(null)
        val self = store.add(Snippet(id = 0, label = "A", text = "A"))
        store.update(self.copy(children = listOf(self.id)))
        assertEquals(listOf("A"), store.candidates(store.items().single()).map { it.text })
    }

    @Test
    fun `a grandchild waits behind a walk`() {
        val store = SnippetStore(null)
        val city = store.add(Snippet(id = 0, label = "Tehran", text = "Tehran"))
        val country = store.add(Snippet(id = 0, label = "Iran", text = "Iran", children = listOf(city.id)))
        val parent = store.add(
            Snippet(id = 0, label = "Country", text = "Country", children = listOf(country.id)),
        )
        assertEquals(listOf("Country", "Iran"), store.candidates(parent).map { it.text })
        assertEquals(listOf("Iran", "Tehran"), store.drillIn(country.id).map { it.text })
    }

    @Test
    fun `drilling into a snippet that is gone shows nothing`() {
        assertTrue(SnippetStore(null).drillIn(404L).isEmpty())
    }

    @Test
    fun `a shouted trigger shouts every expansion, links included`() {
        val store = SnippetStore(null)
        val child = store.add(Snippet(id = 0, label = "Africa", text = "Africa"))
        val parent = store.add(
            Snippet(
                id = 0,
                label = "Continent",
                text = "Asia",
                alternates = listOf("Europe"),
                children = listOf(child.id),
                trigger = "cont",
                propagateCase = true,
            ),
        )
        assertEquals(
            listOf("ASIA", "EUROPE", "AFRICA"),
            store.candidates(parent, typed = "CONT").map { it.text },
        )
    }

    @Test
    fun `every expansion of a pattern sees the same captures`() {
        val store = store(
            Snippet(
                id = 0,
                label = "Greet",
                text = "Hello, \$1!",
                alternates = listOf("Hi \$1", "Good day, \$1"),
                triggerPattern = "^hello (\\w+)$",
            ),
        )
        store.setMultiExpandMode(MultiExpandMode.INSERT_FIRST)
        val hit = store.matchPattern("hello john", atFieldStart = true)!!
        assertEquals(listOf("Hello, john!", "Hi john", "Good day, john"), store.candidates(hit).map { it.text })
    }

    @Test
    fun `the first candidate of a pattern hit is the hit's own text`() {
        // {random} answers differently every time it is asked, and a chip that
        // says it replaced what was inserted has to mean the same string.
        val store = store(
            Snippet(
                id = 0,
                label = "Roll",
                text = "{random:a|b|c|d|e|f|g|h}",
                alternates = listOf("second"),
                triggerPattern = "^roll$",
            ),
        )
        store.setMultiExpandMode(MultiExpandMode.INSERT_FIRST)
        val hit = store.matchPattern("roll", atFieldStart = true)!!
        assertEquals(hit.text, store.candidates(hit).first().text)
    }

    @Test
    fun `a panel pick leaves a pattern's blanks empty`() {
        val store = store(
            Snippet(
                id = 0,
                label = "Greet",
                text = "Hello, \$1!",
                alternates = listOf("Hi \$1"),
                triggerPattern = "^hello (\\w+)$",
            ),
        )
        val snippet = store.items().single()
        assertEquals(listOf("Hello, !", "Hi "), store.candidates(snippet, blank = true).map { it.text })
    }

    @Test
    fun `candidateCount counts the expansions and the links`() {
        val store = SnippetStore(null)
        val child = store.add(Snippet(id = 0, label = "Asia", text = "Asia"))
        val parent = store.add(
            Snippet(
                id = 0,
                label = "C",
                text = "Continent",
                alternates = listOf("Landmass"),
                children = listOf(child.id, 404L),
            ),
        )
        assertEquals(3, store.candidateCount(parent))
        assertEquals(1, store.candidateCount(child))
    }

    // ---- which half of the index -----------------------------------------

    @Test
    fun `asking first still means asking first`() {
        val store = store(Snippet(id = 0, label = "Reply", text = "Thanks!", trigger = "ty", confirm = true))
        assertTrue(store.offers(store.items().single()))
    }

    @Test
    fun `one expansion never asks, whatever the app is set to`() {
        val store = store(Snippet(id = 0, label = "Sig", text = "Regards", trigger = "sig"))
        store.setMultiExpandMode(MultiExpandMode.CHIPS_ONLY)
        assertFalse(store.offers(store.items().single()))
    }

    @Test
    fun `several expansions follow the app setting, and a snippet may overrule it`() {
        val store = store(
            Snippet(id = 0, label = "Greet", text = "Hi", alternates = listOf("Hello"), trigger = "g"),
        )
        val snippet = store.items().single()
        store.setMultiExpandMode(MultiExpandMode.CHIPS_ONLY)
        assertTrue(store.offers(snippet))
        store.setMultiExpandMode(MultiExpandMode.INSERT_FIRST)
        assertFalse(store.offers(snippet))

        store.update(snippet.copy(multiExpand = MultiExpand.CHIPS_ONLY))
        assertTrue(store.offers(store.items().single()))
        store.update(snippet.copy(multiExpand = MultiExpand.INSERT_FIRST))
        store.setMultiExpandMode(MultiExpandMode.CHIPS_ONLY)
        assertFalse(store.offers(store.items().single()))
    }

    @Test
    fun `changing the app setting moves a trigger between the two halves`() {
        val store = store(
            Snippet(id = 0, label = "Greet", text = "Hi", alternates = listOf("Hello"), trigger = "g"),
        )
        store.setMultiExpandMode(MultiExpandMode.CHIPS_ONLY)
        assertTrue(store.hasConfirmTriggers())
        store.setMultiExpandMode(MultiExpandMode.INSERT_FIRST)
        assertFalse(store.hasConfirmTriggers())
    }

    @Test
    fun `a chips-only pattern is offered and never fires on its own`() {
        val store = store(
            Snippet(
                id = 0,
                label = "Greet",
                text = "Hello, \$1!",
                alternates = listOf("Hi \$1"),
                triggerPattern = "^hello (\\w+)$",
            ),
        )
        store.setMultiExpandMode(MultiExpandMode.CHIPS_ONLY)
        assertTrue(store.hasConfirmPatterns())
        assertFalse(store.hasAutoPatterns())
        assertNull(store.matchPattern("hello john", atFieldStart = true))
        assertEquals(
            "Hello, john!",
            store.matchPattern("hello john", atFieldStart = true, confirm = true)?.text,
        )
    }

    @Test
    fun `a chips-only prefix trigger offers instead of expanding`() {
        val store = store(
            Snippet(id = 0, label = "Shrug", text = "one", alternates = listOf("two"), trigger = ":shrug"),
        )
        store.setMultiExpandMode(MultiExpandMode.CHIPS_ONLY)
        assertTrue(store.hasConfirmPrefixTriggers())
        assertNull(store.matchPrefix("shrug", "say :"))
        assertEquals("Shrug", store.matchPrefix("shrug", "say :", confirm = true)?.snippet?.label)
    }
}
