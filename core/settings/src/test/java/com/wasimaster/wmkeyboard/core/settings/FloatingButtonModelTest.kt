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
    fun anAppPairReadsBackFromItsToken() {
        val pair = FloatingAction.AppPair("com.a.one", "com.b.two")
        assertEquals("pair:com.a.one+com.b.two", pair.token)
        assertEquals(pair, FloatingAction.parse(pair.token))
        assertTrue(pair.fitsCategory())
        assertNull(FloatingAction.parse("pair:com.a.one"))
        assertNull(FloatingAction.parse("pair:com.a.one+nodots"))
        assertNull(FloatingAction.parse("pair:a.b+c.d+e.f"))
    }

    @Test
    fun aFloatingToolReadsBackOnlyForAToolThatFloats() {
        val calc = FloatingAction.Applet(ToolbarTool.CALCULATOR)
        assertEquals("applet:CALCULATOR", calc.token)
        assertEquals(calc, FloatingAction.parse(calc.token))
        assertTrue(calc.fitsCategory())
        // Voice has no floating box.
        assertNull(FloatingAction.parse("applet:VOICE"))
        assertTrue(ToolbarTool.NOTES.floats)
        assertFalse(ToolbarTool.VOICE.floats)
    }

    @Test
    fun aHoldBoundToTheFloatingBoxKeepsOnlyOnAToolThatFloats() {
        val stored = ToolHoldActions.encode(mapOf(ToolbarTool.TODO to ToolHoldAction.Floating))
        assertEquals("TODO=FLOAT", stored)
        assertEquals(mapOf(ToolbarTool.TODO to ToolHoldAction.Floating), ToolHoldActions.decode(stored))
        assertEquals(emptyMap<ToolbarTool, ToolHoldAction>(), ToolHoldActions.decode("VOICE=FLOAT"))
    }

    @Test
    fun aSystemButtonReadsBackFromItsToken() {
        val back = FloatingAction.System(SystemButton.BACK)
        assertEquals("system:BACK", back.token)
        assertEquals(back, FloatingAction.parse(back.token))
        assertNull(FloatingAction.parse("system:HOME_SCREEN"))
        val button = SummonButtonSettings(tap = FloatingAction.System(SystemButton.RECENTS))
        assertTrue(button.pressesSystemButtons())
        assertFalse(SummonButtonSettings().pressesSystemButtons())
    }

    @Test
    fun aSwipeGoesToTheNearestBoundDirection() {
        val all = FlickDirection.entries
        // Screen y points down: a swipe with negative dy is up.
        assertEquals(FlickDirection.UP, flickDirectionOf(0f, -50f, all))
        assertEquals(FlickDirection.DOWN_RIGHT, flickDirectionOf(40f, 40f, all))
        assertEquals(FlickDirection.LEFT, flickDirectionOf(-60f, 5f, all))
        // Only the four sides bound: a diagonal lands on the nearer side.
        val sides = listOf(FlickDirection.UP, FlickDirection.RIGHT, FlickDirection.DOWN, FlickDirection.LEFT)
        assertEquals(FlickDirection.UP, flickDirectionOf(20f, -50f, sides))
        // Only up bound: a swipe down is near nothing.
        assertNull(flickDirectionOf(0f, 50f, listOf(FlickDirection.UP)))
        assertNull(flickDirectionOf(0f, 0f, all))
    }

    @Test
    fun swipesRoundTripThroughTheirStoredForm() {
        val flicks = mapOf(
            FlickDirection.UP to voice,
            FlickDirection.LEFT to FloatingAction.System(SystemButton.BACK),
            FlickDirection.DOWN_RIGHT to maps,
        )
        assertEquals(flicks, decodeFloatingFlicks(encodeFloatingFlicks(flicks)))
        assertEquals(mapOf(FlickDirection.UP to voice), decodeFloatingFlicks("UP=tool:VOICE,SIDEWAYS=tool:VOICE,DOWN=nonsense"))
        // Unfixed, the swipes run nothing, and reach nothing.
        val button = SummonButtonSettings(flicks = flicks)
        assertTrue(button.activeFlicks.isEmpty())
        assertTrue(button.copy(docked = true).pressesSystemButtons())
    }

    @Test
    fun aMenuHoldsSixteenSlots() {
        val many = (1..20).map { FloatingAction.App("com.a.p$it") }
        assertEquals(16, FloatingAction.parseMenu(FloatingAction.menuToken(many)).size)
        assertEquals(MAX_FLOATING_MENU_ITEMS, categoryItems(many).size)
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
            {"id":"c2","name":"${"x".repeat(80)}","items":[${(1..MAX_FLOATING_MENU_ITEMS + 4).joinToString(",") { "\"app:com.a.p$it\"" }}]}
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
