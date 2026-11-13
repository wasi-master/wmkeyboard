package com.wasimaster.wmkeyboard.core.notes.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRelatedTest {

    private fun note(id: Long, text: String) = RelatedSource(RelatedKind.NOTE, id, "", text)

    private val invoice = note(1, "Invoice for Rahim: project budget review")
    private val budget = note(2, "Rahim sent the project budget invoice")
    private val groceries = note(3, "Buy milk, eggs and bread")

    @Test
    fun `notes sharing their unusual words are related, and an unrelated one is not`() {
        val hits = NoteRelated.related(invoice, listOf(invoice, budget, groceries))
        assertEquals(listOf(2L), hits.map { it.id })
        assertTrue(hits.single().score > 0.5)
        // Four words in common, all as rare as each other: any three of them say why.
        assertEquals(3, hits.single().shared.size)
        assertTrue(setOf("invoice", "rahim", "project", "budget").containsAll(hits.single().shared))
    }

    @Test
    fun `the same phone number relates a note and a pinned clip with nothing else in common`() {
        val landlord = note(1, "Landlord +880 1712-345678")
        val clip = RelatedSource(RelatedKind.CLIP, 9, "", "call 01712345678 about the flat")
        val hit = NoteRelated.related(landlord, listOf(clip)).single()
        assertEquals(RelatedKind.CLIP, hit.kind)
        assertEquals("712345678", hit.shared.first())
    }

    @Test
    fun `groups gather what belongs together and leave the rest out`() {
        val groups = NoteRelated.groups(listOf(groceries, invoice, budget))
        assertEquals(listOf(listOf(1L, 2L)), groups.map { group -> group.map { it.id } })
    }

    @Test
    fun `words fold to a term, and stopwords, numbers and short words are dropped`() {
        assertEquals("note", NoteRelated.fold("Notes"))
        assertEquals("party", NoteRelated.fold("parties"))
        assertEquals("rahim", NoteRelated.fold("Rahim's"))
        assertEquals("class", NoteRelated.fold("class"))
        assertNull(NoteRelated.fold("the"))
        assertNull(NoteRelated.fold("2026"))
        assertNull(NoteRelated.fold("ab"))
    }

    @Test
    fun `a blank note is related to nothing`() {
        assertTrue(NoteRelated.related(note(5, "  "), listOf(invoice, budget)).isEmpty())
    }
}
