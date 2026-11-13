package com.wasimaster.wmkeyboard.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plugins as tools: the [ToolKey] form, the full order, placing one, and migrating the old tiles. */
class PluginToolsTest {

    private val grocy = pluginToolKey("com.example.grocy")
    private val polls = pluginToolKey("org.example.polls")
    private val emoji = ToolbarTool.EMOJI.key
    private val clip = ToolbarTool.CLIPBOARD.key
    private val calc = ToolbarTool.CALCULATOR.key

    @Test
    fun tokensRoundTrip() {
        assertEquals("EMOJI", emoji.token)
        assertEquals("plugin:com.example.grocy", grocy.token)
        assertEquals(emoji, ToolKey.of("EMOJI"))
        assertEquals(grocy, ToolKey.of("plugin:com.example.grocy"))
        assertEquals(ToolbarTool.EMOJI, emoji.tool)
        assertEquals("com.example.grocy", grocy.pluginId)
        assertNull(grocy.tool)
        assertNull(emoji.pluginId)
    }

    @Test
    fun unknownNamesAndBadIdsAreNotEntries() {
        assertNull(ToolKey.of("NOT_A_TOOL"))
        assertNull(ToolKey.of("plugin:"))
        assertNull(ToolKey.of("plugin:Upper.Case"))
        assertNull(ToolKey.of("plugin:../escape"))
        assertNull(ToolKey.of("plugin:ab"))
    }

    @Test
    fun theStoredListKeepsPluginsBesideToolsAndDropsJunk() {
        val keys = listOf(emoji, grocy, clip, polls)
        assertEquals("EMOJI,plugin:com.example.grocy,CLIPBOARD,plugin:org.example.polls", encodeToolKeys(keys))
        assertEquals(keys, decodeToolKeys(encodeToolKeys(keys)))
        assertEquals(listOf(emoji, grocy), decodeToolKeys("EMOJI,RETIRED_TOOL,plugin:com.example.grocy,plugin:,EMOJI"))
        assertTrue(decodeToolKeys(null).isEmpty())
        assertTrue(decodeToolKeys("").isEmpty())
    }

    @Test
    fun aBuiltInToolIsFoundInAListOfEntries() {
        val bar = listOf(emoji, grocy)
        assertTrue(ToolbarTool.EMOJI in bar)
        assertFalse(ToolbarTool.CLIPBOARD in bar)
        assertEquals(listOf(ToolbarTool.EMOJI), bar.builtinTools())
        assertEquals(listOf("com.example.grocy"), bar.pluginIds())
    }

    @Test
    fun anEmptyOrderIsTheDefaultOne() {
        assertEquals(DefaultToolOrderKeys, completedToolOrder(emptyList()))
    }

    @Test
    fun completingAnOrderKeepsEveryPluginWhereItWasStored() {
        val stored = DefaultToolOrderKeys.take(3) + grocy + DefaultToolOrderKeys.drop(3) + polls
        assertEquals(stored, completedToolOrder(stored))
    }

    @Test
    fun completingAnOrderAddsTheMissingBuiltInToolsAfterTheirPeers() {
        val second = DefaultToolOrder[1]
        val stored = DefaultToolOrderKeys - second.key
        val withPlugin = stored.take(1) + grocy + stored.drop(1)
        val completed = completedToolOrder(withPlugin)
        // Every tool is there once, the plugin too.
        assertEquals(DefaultToolOrderKeys.size + 1, completed.size)
        assertEquals(completed.size, completed.toSet().size)
        // The missing tool rejoins right after the tool that outranks it.
        assertEquals(completed.indexOf(DefaultToolOrder[0].key) + 1, completed.indexOf(second.key))
        assertTrue(grocy in completed)
    }

    @Test
    fun pinningAPluginPutsItAtTheEndOfTheBarAndKeepsItsCellInTheGrid() {
        val bar = listOf(emoji, clip)
        val order = listOf(calc, clip)
        val (pinnedBar, pinnedOrder) = withPluginToolPlace(bar, order, grocy, PluginToolPlace.TOOLBAR)
        assertEquals(listOf(emoji, clip, grocy), pinnedBar)
        assertEquals(listOf(calc, clip, grocy), pinnedOrder)
        assertEquals(PluginToolPlace.TOOLBAR, pluginToolPlaceIn(pinnedBar, pinnedOrder, emptyList(), "com.example.grocy"))
    }

    @Test
    fun movingAPluginToTheToolboxUnpinsItWhereItWas() {
        val bar = listOf(emoji, grocy, clip)
        val order = listOf(calc, grocy, clip)
        val (nextBar, nextOrder) = withPluginToolPlace(bar, order, grocy, PluginToolPlace.TOOLBOX)
        assertEquals(listOf(emoji, clip), nextBar)
        // Same cell in the grid as before it was pinned.
        assertEquals(order, nextOrder)
        assertEquals(PluginToolPlace.TOOLBOX, pluginToolPlaceIn(nextBar, nextOrder, emptyList(), "com.example.grocy"))
    }

    @Test
    fun noPlaceTakesThePluginOutOfBothLists() {
        val (bar, order) = withPluginToolPlace(listOf(emoji, grocy), listOf(grocy, calc), grocy, PluginToolPlace.NONE)
        assertEquals(listOf(emoji), bar)
        assertEquals(listOf(calc), order)
        assertEquals(PluginToolPlace.NONE, pluginToolPlaceIn(bar, order, emptyList(), "com.example.grocy"))
    }

    @Test
    fun aPluginInAFolderIsInTheToolbox() {
        val folders = listOf(ToolboxFolder("f1", "Kitchen", listOf(clip, grocy)))
        assertEquals(PluginToolPlace.TOOLBOX, pluginToolPlaceIn(listOf(emoji), listOf(clip), folders, "com.example.grocy"))
    }

    @Test
    fun uninstallingTakesThePluginOutOfAList() {
        assertEquals(listOf(emoji, polls), listOf(emoji, grocy, polls).withoutPlugin("com.example.grocy"))
    }

    @Test
    fun shownWithAsksTheBuiltInTestOrThePluginsThatAreLive() {
        val live = setOf("com.example.grocy")
        assertTrue(grocy.shownWith(live) { false })
        assertFalse(polls.shownWith(live) { true })
        assertTrue(emoji.shownWith(live) { it == ToolbarTool.EMOJI })
        assertFalse(clip.shownWith(live) { it == ToolbarTool.EMOJI })
    }

    @Test
    fun oldPluginTilesMigrateToTheBarAndTheToolbox() {
        val shortcut = CustomButton("c1", CustomButtonKind.SHORTCUT, "intent:#Intent;end")
        val onBar = CustomButton("c2", CustomButtonKind.PLUGIN, "com.example.grocy", onToolbar = true)
        val inBox = CustomButton("c3", CustomButtonKind.PLUGIN, "org.example.polls", onToolbar = false)
        val broken = CustomButton("c4", CustomButtonKind.PLUGIN, "Not An Id")
        val migrated = migratedPluginTiles(
            buttons = listOf(shortcut, onBar, inBox, broken),
            bar = listOf(emoji, clip),
            order = listOf(calc, clip),
        )
        assertEquals(listOf(emoji, clip, grocy), migrated.bar)
        assertEquals(listOf(calc, clip, grocy, polls), migrated.order)
        assertEquals(listOf(shortcut), migrated.buttons)
    }

    @Test
    fun migratingLeavesAPluginThatIsAlreadyAToolWhereItIs() {
        val tile = CustomButton("c2", CustomButtonKind.PLUGIN, "com.example.grocy", onToolbar = true)
        val migrated = migratedPluginTiles(listOf(tile), bar = listOf(emoji), order = listOf(grocy, calc))
        assertEquals(listOf(emoji), migrated.bar)
        assertEquals(listOf(grocy, calc), migrated.order)
        assertTrue(migrated.buttons.isEmpty())
    }

    @Test
    fun migratingWithNoTilesChangesNothing() {
        val shortcut = CustomButton("c1", CustomButtonKind.SHORTCUT, "intent:#Intent;end")
        val migrated = migratedPluginTiles(listOf(shortcut), bar = listOf(emoji), order = listOf(calc))
        assertEquals(MigratedPluginTiles(listOf(emoji), listOf(calc), listOf(shortcut)), migrated)
    }

    @Test
    fun theFloatingButtonNamesAPluginByItsId() {
        val action = FloatingAction.Plugin("com.example.grocy")
        assertEquals("plugin:com.example.grocy", action.token)
        assertEquals(action, FloatingAction.parse(action.token))
        assertNull(FloatingAction.parse("plugin:Bad Id"))
        assertTrue(action in FloatingAction.parseMenu("keyboard,plugin:com.example.grocy"))
    }
}
