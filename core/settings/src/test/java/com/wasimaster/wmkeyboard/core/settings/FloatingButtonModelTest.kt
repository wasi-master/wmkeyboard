package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The floating button's actions (#922, #923): apps and categories, their stored form, and what a ring holds. */
class FloatingButtonModelTest {

    private val voice = FloatingAction.Tool(ToolbarTool.VOICE)
    private val clipboard = FloatingAction.Tool(ToolbarTool.CLIPBOARD)
    private val maps = FloatingAction.App("com.google.android.apps.maps")

    @Test
    fun anAppAndACategoryReadBackFromTheirTokens() {
        assertEquals("app:com.google.android.apps.maps", maps.token)
        assertEquals(maps, FloatingAction.parse(maps.token))
        val category = FloatingAction.Category("c1")
        assertEquals("category:c1", category.token)
        assertEquals(category, FloatingAction.parse(category.token))
    }

    @Test
    fun aTokenThatIsNoPackageOrNoIdReadsAsNothing() {
        assertNull(FloatingAction.parse("app:"))
        assertNull(FloatingAction.parse("app:nodots"))
        assertNull(FloatingAction.parse("app:com.example/.Main"))
        assertNull(FloatingAction.parse("category:"))
        assertNull(FloatingAction.parse("category:a b"))
    }

    @Test
    fun aWorkAppKeepsItsProfileInItsToken() {
        val work = FloatingAction.App("com.google.android.apps.maps", profile = 10)
        assertEquals("app:com.google.android.apps.maps@10", work.token)
        assertEquals(work, FloatingAction.parse(work.token))
        assertTrue(work.otherProfile)
        assertFalse(maps.otherProfile)
        // The same package in two profiles is two slots.
        assertEquals(listOf(maps, work), FloatingAction.parseMenu(FloatingAction.menuToken(listOf(maps, work))))
        assertNull(FloatingAction.parse("app:com.a.b@"))
        assertNull(FloatingAction.parse("app:com.a.b@ten"))
    }

    @Test
    fun aCategoryKeepsItsIcon() {
        val categories = listOf(FloatingCategory("c1", "Work", listOf(maps), icon = "work"), FloatingCategory("c2", "B"))
        assertEquals(categories, decodeFloatingCategories(encodeFloatingCategories(categories)))
        val blank = decodeFloatingCategories("""[{"id":"c1","name":"A","items":[],"icon":"  "}]""")
        assertNull(blank.single().icon)
    }

    @Test
    fun aMenuKeepsItsAppsAndCategoriesInOrder() {
        val menu = listOf(voice, maps, FloatingAction.Category("c2"))
        assertEquals(menu, FloatingAction.parseMenu(FloatingAction.menuToken(menu)))
    }

    @Test
    fun aCategoryHoldsNoCategory() {
        assertTrue(FloatingAction.Category("c1").fitsMenu())
        assertFalse(FloatingAction.Category("c1").fitsCategory())
        assertTrue(maps.fitsCategory())
        assertFalse(FloatingAction.Menu.fitsCategory())
        assertFalse(FloatingAction.None.fitsCategory())
    }

    @Test
    fun categoriesRoundTripThroughTheirStoredForm() {
        val categories = listOf(
            FloatingCategory("c1", "Work", listOf(maps, voice)),
            FloatingCategory("c2", "Fun, games", listOf(clipboard)),
        )
        assertEquals(categories, decodeFloatingCategories(encodeFloatingCategories(categories)))
        assertNull(encodeFloatingCategories(emptyList()))
        assertEquals(emptyList<FloatingCategory>(), decodeFloatingCategories("not json"))
    }

    @Test
    fun readingBackDropsNestingDuplicatesAndOverflow() {
        val raw = """[
            {"id":"c1","name":"  A  ","items":["category:c2","tool:VOICE","tool:VOICE","menu","app:com.a.b"]},
            {"id":"c1","name":"again","items":[]},
            {"id":"c2","name":"${"x".repeat(80)}","items":[${(1..12).joinToString(",") { "\"app:com.a.p$it\"" }}]}
        ]"""
        val read = decodeFloatingCategories(raw)
        assertEquals(listOf("c1", "c2"), read.map { it.id })
        assertEquals("A", read[0].name)
        assertEquals(listOf(voice, FloatingAction.App("com.a.b")), read[0].items)
        assertEquals(FLOATING_CATEGORY_NAME_MAX, read[1].name.length)
        assertEquals(MAX_FLOATING_MENU_ITEMS, read[1].items.size)
    }

    @Test
    fun aCategoryWithoutItsSlotIsAnOrphan() {
        val categories = listOf(FloatingCategory("c1", "A"), FloatingCategory("c2", "B"))
        val menu = listOf(voice, FloatingAction.Category("c2"))
        assertEquals(listOf(FloatingCategory("c2", "B")), categories.withoutOrphans(menu))
        assertEquals("c3", categories.nextCategoryId())
        assertEquals("c1", listOf(FloatingCategory("c2", "B")).nextCategoryId())
    }

    @Test
    fun theSlotsAGestureReachesTakeInEveryCategoryTheMenuOpens() {
        val work = FloatingCategory("c1", "Work", listOf(FloatingAction.AskAi, maps))
        val button = SummonButtonSettings(
            menu = listOf(voice, FloatingAction.Category("c1")),
            categories = listOf(work),
        )
        assertEquals(listOf(voice, FloatingAction.Category("c1"), FloatingAction.AskAi, maps), button.opensMenuItems())
        assertTrue(button.usesAskAi())
        assertTrue(button.readsScreen())
        assertEquals(work, button.categoryOf(FloatingAction.Category("c1")))
        assertEquals(work.items, button.ringOf(FloatingAction.Category("c1")))
        assertEquals(button.menu, button.ringOf(FloatingAction.Menu))
        assertNull(button.ringOf(voice))
        assertNull(button.categoryOf(FloatingAction.Category("gone")))
    }

    @Test
    fun noGestureOpeningTheMenuReachesNoneOfItsCategories() {
        val button = SummonButtonSettings(
            hold = FloatingAction.None,
            menu = listOf(FloatingAction.Category("c1")),
            categories = listOf(FloatingCategory("c1", "Work", listOf(FloatingAction.AskAi))),
        )
        assertEquals(emptyList<FloatingAction>(), button.opensMenuItems())
        assertFalse(button.usesAskAi())
    }

    @Test
    fun theDefaultsKeepTheButtonAsItWas() {
        val button = SummonButtonSettings()
        assertEquals(FloatingButtonShape.CIRCLE, button.shape)
        assertEquals(FloatingMenuAnimation.SPRING, button.menuAnimation)
        assertEquals(22, button.menuGapDp)
        assertTrue(button.menuGapDp in FloatingMenuGapRange)
        assertFalse(button.floatsOverKeyboard)
        assertFalse(button.copy(overKeyboard = true, whileTyping = false).floatsOverKeyboard)
        assertTrue(button.copy(overKeyboard = true).floatsOverKeyboard)
    }
}
