package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gallery's Group and Ungroup. What matters most: every look keeps its id,
 * so the theme in use and the auto-theme slots survive either one.
 */
class ThemeRegroupTest {

    private fun look(id: String, name: String = id) = ThemeSpec(id = id, name = name)

    private val nord = groupAsFamily(listOf(look("n", "Nord"), look("n_v0", "Dark"), look("n_v1", "Light")), "Nord")
    private val ocean = look("o", "Ocean")
    private val sand = look("s", "Sand")
    private val store = listOf(nord, ocean, sand)

    private fun List<ThemeSpec>.ids() = flattenedThemes().map { it.id }.sorted()

    @Test
    fun `grouping lone themes makes one family headed by the first`() {
        val change = store.gatheringLooks(listOf("o", "s"), "Sea")!!
        assertEquals(listOf("o", "s"), change.before.map { it.id })
        val family = change.after.single()
        assertEquals("o", family.id)
        assertEquals("Sea", family.familyName)
        assertEquals(listOf("s"), family.variants.map { it.id })
    }

    @Test
    fun `grouping a family with a theme keeps every look and id`() {
        val change = store.gatheringLooks(nord.selfAndVariants().map { it.id } + "o", "Nord")!!
        val family = change.after.single()
        assertEquals(listOf("n", "n_v0", "n_v1", "o"), family.selfAndVariants().map { it.id })
        assertEquals(change.before.ids(), change.after.ids())
    }

    @Test
    fun `taking one look out of a family leaves the rest together`() {
        val change = store.gatheringLooks(listOf("n", "o"), "Mix")!!
        val left = change.after.single { it.id != "n" }
        assertEquals("n_v0", left.id)
        assertEquals(listOf("n_v1"), left.variants.map { it.id })
        assertEquals("Nord", left.familyName)
        assertEquals(change.before.ids(), change.after.ids())
    }

    @Test
    fun `grouping fewer than two looks is nothing`() {
        assertNull(store.gatheringLooks(listOf("o"), "x"))
        assertNull(store.gatheringLooks(listOf("o", "gone"), "x"))
    }

    @Test
    fun `ungrouping a family gives one theme per look, renamed`() {
        val change = store.separatingLooks(nord.selfAndVariants().map { it.id }) { family, look ->
            "${family.familyName} (${look.name})"
        }!!
        assertEquals(listOf("n"), change.before.map { it.id })
        assertEquals(listOf("n", "n_v0", "n_v1"), change.after.map { it.id })
        assertEquals(listOf("Nord (Nord)", "Nord (Dark)", "Nord (Light)"), change.after.map { it.name })
        assertEquals(true, change.after.all { it.variants.isEmpty() && it.familyName == null })
    }

    @Test
    fun `ungrouping one look keeps the family without it`() {
        val change = store.separatingLooks(listOf("n_v1")) { _, look -> look.name }!!
        val family = change.after.first()
        assertEquals("n", family.id)
        assertEquals(listOf("n_v0"), family.variants.map { it.id })
        assertEquals("n_v1", change.after.last().id)
        assertEquals(change.before.ids(), change.after.ids())
    }

    @Test
    fun `ungrouping lone themes is nothing`() {
        assertNull(store.separatingLooks(listOf("o", "s")) { _, look -> look.name })
    }
}
