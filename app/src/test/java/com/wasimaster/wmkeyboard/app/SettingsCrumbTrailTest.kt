package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The path strip is only as honest as this trail. It has to match the back
 * stack in every shape navigation can leave it in: a push, a return, the two
 * frames of an animation where the arriving and the leaving screen are both
 * drawn, and a screen replaced out from under itself by
 * `popUpTo(inclusive = true)`.
 */
class SettingsCrumbTrailTest {

    /**
     * A back stack and the trail that follows it, driven the way navigation
     * drives the real one: a screen records itself once it is drawn, and the
     * trail asks the stack which screen is on top.
     */
    private class Walk {
        val stack = mutableListOf<String>()
        val trail = SettingsCrumbTrail()

        init {
            trail.bind(
                topEntryId = { stack.lastOrNull() },
                pop = {
                    val more = stack.size > 1
                    if (more) stack.removeAt(stack.lastIndex)
                    more
                },
            )
        }

        /** Opens a screen: navigation pushes the entry, then the screen draws. */
        fun push(id: String, title: String) = apply {
            stack.add(id)
            trail.enter(id, title)
        }

        /** Goes back to a screen already on the stack, the way a back press does. */
        fun back(id: String, title: String) = apply {
            while (stack.size > 1 && stack.last() != id) stack.removeAt(stack.lastIndex)
            trail.enter(id, title)
        }

        fun titles(crumbs: List<SettingsCrumb>) = crumbs.map { it.title }
    }

    private fun walkTo(vararg steps: Pair<String, String>) = Walk().apply {
        steps.forEach { (id, title) -> push(id, title) }
    }

    @Test
    fun `a push adds a step and the home screen has no path`() {
        val walk = walkTo("a" to "Home", "b" to "Appearance", "c" to "Themes")

        assertEquals(emptyList<String>(), walk.titles(walk.trail.ancestorsOf("a")))
        assertEquals(listOf("Home"), walk.titles(walk.trail.ancestorsOf("b")))
        assertEquals(listOf("Home", "Appearance"), walk.titles(walk.trail.ancestorsOf("c")))
    }

    @Test
    fun `returning to a screen drops everything that was above it`() {
        val walk = walkTo("a" to "Home", "b" to "Appearance", "c" to "Themes")

        walk.back("b", "Appearance")

        assertEquals(listOf("Home", "Appearance"), walk.titles(walk.trail.path))
        assertEquals(listOf("Home"), walk.titles(walk.trail.ancestorsOf("b")))
    }

    @Test
    fun `a screen the trail has not heard of yet reads the whole path`() {
        // The frame between a screen being drawn and its own step being
        // recorded, and every frame of a screen animating out after the one
        // below it trimmed the trail. Both want the trail exactly as it stands.
        val walk = walkTo("a" to "Home", "b" to "Appearance", "c" to "Themes")

        assertEquals(
            listOf("Home", "Appearance", "Themes"),
            walk.titles(walk.trail.ancestorsOf("d")),
        )
    }

    @Test
    fun `a replaced screen is forgotten and stops showing in the path`() {
        // popUpTo(inclusive = true): the list is replaced by what it opened.
        val walk = walkTo("a" to "Home", "b" to "AI chats", "c" to "New chat")

        walk.trail.forget("b")

        assertEquals(listOf("Home", "New chat"), walk.titles(walk.trail.path))
        assertEquals(listOf("Home"), walk.titles(walk.trail.ancestorsOf("c")))
    }

    @Test
    fun `a screen renamed under the one above it keeps that screen`() {
        // A theme renamed while its own editor sits on top of it. The rename
        // must not read as a return and take the editor out of the trail.
        val walk = walkTo("a" to "Home", "b" to "Untitled theme", "c" to "Colours")

        walk.trail.enter("b", "Midnight")

        assertEquals(listOf("Home", "Midnight", "Colours"), walk.titles(walk.trail.path))
    }

    @Test
    fun `pressing a step pops exactly as far as that step`() {
        val walk = walkTo(
            "a" to "Home",
            "b" to "Tools",
            "c" to "Clipboard",
            "d" to "Phone number formats",
        )

        walk.trail.popTo("b")

        assertEquals(listOf("a", "b"), walk.stack)
    }

    @Test
    fun `pressing the step already on top pops nothing`() {
        val walk = walkTo("a" to "Home", "b" to "Tools")

        walk.trail.popTo("b")

        assertEquals(listOf("a", "b"), walk.stack)
    }

    @Test
    fun `a step the trail does not hold pops nothing at all`() {
        // The stack must never be emptied by a strip that lost its place.
        val walk = walkTo("a" to "Home", "b" to "Tools")

        walk.trail.popTo("zzz")

        assertEquals(listOf("a", "b"), walk.stack)
    }

    @Test
    fun `a saved path comes back with its steps in order`() {
        val walk = walkTo("a" to "Home", "b" to "Tools", "c" to "Clipboard")
        // The scope only decides what may go in a Bundle, and every value the
        // saver writes is a String.
        val scope = SaverScope { true }

        val saved = with(SettingsCrumbTrail.Saver) { scope.save(walk.trail) }!!
        val restored = SettingsCrumbTrail.Saver.restore(saved)!!

        assertEquals(listOf("Home", "Tools", "Clipboard"), restored.path.map { it.title })
        assertEquals(listOf("Home", "Tools"), restored.ancestorsOf("c").map { it.title })
    }

    @Test
    fun `a saved path keeps each step's route and a step without one stays without`() {
        // The route is what a step's glyph is looked up by, so it has to come
        // back with the step — and a tool page, which has none, must not come
        // back with an empty one.
        val walk = Walk()
        walk.stack.add("a")
        walk.trail.enter("a", "Home", "home")
        walk.stack.add("b")
        walk.trail.enter("b", "Tools", "tools")
        walk.stack.add("c")
        walk.trail.enter("c", "Clipboard")
        val scope = SaverScope { true }

        val saved = with(SettingsCrumbTrail.Saver) { scope.save(walk.trail) }!!
        val restored = SettingsCrumbTrail.Saver.restore(saved)!!

        assertEquals(listOf("home", "tools", null), restored.path.map { it.route })
    }
}
