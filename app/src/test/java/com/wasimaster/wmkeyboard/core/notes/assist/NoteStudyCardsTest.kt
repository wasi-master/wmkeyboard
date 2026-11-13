package com.wasimaster.wmkeyboard.core.notes.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteStudyCardsTest {

    private fun pairs(text: String) = NoteStudyCards.fromText(text).map { it.term to it.definition }

    @Test
    fun `term and meaning lines become cards, whichever separator they use`() {
        val note = """
            # Biology
            - photosynthesis: how plants make food - from light
            - mitochondria - powerhouse of the cell
            **osmosis** water moving through a membrane
            enzyme = a protein that speeds up a reaction
        """.trimIndent()
        assertEquals(
            listOf(
                "photosynthesis" to "how plants make food - from light",
                "mitochondria" to "powerhouse of the cell",
                "osmosis" to "water moving through a membrane",
                "enzyme" to "a protein that speeds up a reaction",
            ),
            pairs(note),
        )
    }

    @Test
    fun `a question and its answer make a card, however long the question`() {
        assertEquals(
            listOf("What is the capital of France?" to "Paris"),
            pairs("Q: What is the capital of France?\nA: Paris"),
        )
    }

    @Test
    fun `a table's rows are cards and its header is not`() {
        val note = "| Term | Meaning |\n|---|---|\n| atom | smallest unit |\n| ion | charged atom |"
        assertEquals(listOf("atom" to "smallest unit", "ion" to "charged atom"), pairs(note))
    }

    @Test
    fun `sentences, links, times and answers without a question are not cards`() {
        val note = """
            This is a long sentence that goes on and on and on: really
            https://example.com: a link
            10:30 standup
            A: an answer on its own
            plain line with no separator
        """.trimIndent()
        assertTrue(pairs(note).isEmpty())
    }

    @Test
    fun `a model's answer is read the same way, fences and repeats dropped`() {
        val answer = "```\nAtom | smallest unit\natom | again\nIon | charged atom\n```"
        assertEquals(listOf("Atom" to "smallest unit", "Ion" to "charged atom"), NoteStudyCards.fromModel(answer).map { it.term to it.definition })
    }

    @Test
    fun `a deck is the user's own English list, named after the note`() {
        val pack = NoteStudyCards.toPack(NoteStudyCards.fromText("atom: smallest unit"), "Chemistry", nowMillis = 42L)
        assertEquals("Chemistry", pack.meta.name)
        assertEquals("en", pack.meta.langId)
        assertTrue(pack.meta.userCreated)
        assertEquals(listOf("atom"), pack.words.map { it.word })
    }
}
