package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.settings.AutomationPermission
import com.wasimaster.wmkeyboard.core.settings.AutomationSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.theme.BuiltInThemes
import com.wasimaster.wmkeyboard.core.theme.DEFAULT_THEME_ID
import com.wasimaster.wmkeyboard.ime.KeyboardAutomation.Outcome
import com.wasimaster.wmkeyboard.ime.KeyboardAutomation.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an automation intent resolves to, and what it needs. Every layout answer
 * has to be one of the enabled layouts or a refusal; every action needs a
 * permission the user can switch off.
 */
class KeyboardAutomationTest {

    private val qwerty = BuiltInLayouts.QWERTY_ID
    private val avro = BuiltInLayouts.AVRO_ID
    private val probhat = BuiltInLayouts.PROBHAT_ID
    private val french = BuiltInLayouts.FRENCH_ID
    private val dvorak = BuiltInLayouts.DVORAK_ID

    private val enabled = listOf(qwerty, avro, probhat, french)

    private fun resolve(
        action: String,
        layout: String? = null,
        language: String? = null,
        current: String = qwerty,
        recent: List<String> = emptyList(),
        on: List<String> = enabled,
    ) = KeyboardAutomation.resolve(action, layout, language, on, current, recent, customs = emptyList())

    @Test
    fun `set layout by id switches`() {
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = french))
    }

    @Test
    fun `set layout by id ignores case`() {
        assertEquals(
            Outcome.Switch(avro),
            resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = avro.uppercase()),
        )
    }

    @Test
    fun `set layout by name`() {
        val name = BuiltInLayouts.byId(probhat)!!.name
        assertEquals(
            Outcome.Switch(probhat),
            resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = name.lowercase()),
        )
    }

    @Test
    fun `set layout refuses one that is not turned on`() {
        assertTrue(resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = dvorak) is Outcome.Failed)
    }

    @Test
    fun `set layout without the extra is refused`() {
        assertTrue(resolve(KeyboardAutomation.ACTION_SET_LAYOUT) is Outcome.Failed)
        assertTrue(resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = "  ") is Outcome.Failed)
    }

    @Test
    fun `set layout to the current one changes nothing`() {
        assertEquals(Outcome.Unchanged(qwerty), resolve(KeyboardAutomation.ACTION_SET_LAYOUT, layout = qwerty))
    }

    @Test
    fun `set language picks the first layout of it in switch order`() {
        assertEquals(Outcome.Switch(avro), resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "bn"))
    }

    @Test
    fun `set language prefers the layout of it used last`() {
        assertEquals(
            Outcome.Switch(probhat),
            resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "bn", recent = listOf(french, probhat, avro)),
        )
    }

    @Test
    fun `set language already on screen stays on the layout in use`() {
        assertEquals(
            Outcome.Unchanged(probhat),
            resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "bn", current = probhat, recent = listOf(avro)),
        )
    }

    @Test
    fun `set language accepts a locale tag, a region variant and an English name`() {
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "fr"))
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "fr-CA"))
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "French"))
        assertEquals(Outcome.Switch(avro), resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "bn_BD"))
    }

    @Test
    fun `set language with nothing of it turned on is refused`() {
        assertTrue(resolve(KeyboardAutomation.ACTION_SET_LANGUAGE, language = "de") is Outcome.Failed)
    }

    @Test
    fun `next and previous wrap around the switch order`() {
        assertEquals(Outcome.Switch(avro), resolve(KeyboardAutomation.ACTION_NEXT_LAYOUT))
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_PREVIOUS_LAYOUT))
        assertEquals(Outcome.Switch(qwerty), resolve(KeyboardAutomation.ACTION_NEXT_LAYOUT, current = french))
    }

    @Test
    fun `next from a layout outside the ring enters it at the matching end`() {
        assertEquals(Outcome.Switch(qwerty), resolve(KeyboardAutomation.ACTION_NEXT_LAYOUT, current = dvorak))
        assertEquals(Outcome.Switch(french), resolve(KeyboardAutomation.ACTION_PREVIOUS_LAYOUT, current = dvorak))
    }

    @Test
    fun `next with one layout turned on changes nothing`() {
        assertEquals(
            Outcome.Unchanged(qwerty),
            resolve(KeyboardAutomation.ACTION_NEXT_LAYOUT, on = listOf(qwerty)),
        )
    }

    @Test
    fun `list marks the layout on screen`() {
        val lines = KeyboardAutomation.describe(enabled, avro, emptyList()).lines()
        assertEquals(enabled.size, lines.size)
        assertTrue(lines[1].startsWith("* $avro\tbn\t"))
        assertTrue(lines[0].startsWith("  $qwerty\ten\t"))
    }

    @Test
    fun `nothing is allowed until the master switch is on`() {
        val off = AutomationSettings()
        assertFalse(off.enabled)
        assertTrue(AutomationPermission.entries.none(off::allows))
        val on = off.copy(enabled = true)
        assertTrue(on.allows(AutomationPermission.LAYOUT))
        assertFalse(on.allows(AutomationPermission.TYPE_TEXT))
    }

    @Test
    fun `the trusted group starts off and the keyboard controls start on`() {
        val trusted = setOf(
            AutomationPermission.INCOGNITO_OFF,
            AutomationPermission.WORDS,
            AutomationPermission.BACKUP,
            AutomationPermission.BACKUP_PAUSE,
            AutomationPermission.EXPORT_DATA,
            AutomationPermission.LAYOUT_EVENTS,
            AutomationPermission.TYPE_TEXT,
        )
        assertEquals(AutomationPermission.entries.toSet() - trusted, AutomationSettings.DEFAULT_ALLOWED)
    }

    @Test
    fun `the Play build leaves typing out, even when a restore allowed it`() {
        val everything = AutomationSettings(enabled = true, allowed = AutomationPermission.entries.toSet())
        assertEquals(!BuildConfig.ENABLE_PLAY_STORE, AutomationPermission.TYPE_TEXT.offered)
        assertEquals(AutomationPermission.TYPE_TEXT.offered, everything.allows(AutomationPermission.TYPE_TEXT))
        assertEquals(
            AutomationPermission.entries.filter { it.offered },
            AutomationPermission.entries.filter(everything::allows),
        )
        assertTrue(AutomationPermission.entries.all { it.offered || it == AutomationPermission.TYPE_TEXT })
    }

    @Test
    fun `stored keys are unique`() {
        val keys = AutomationPermission.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `every action needs a permission, incognito by direction`() {
        assertEquals(KeyboardAutomation.actions - KeyboardAutomation.ACTION_SET_INCOGNITO, KeyboardAutomation.permissions.keys)
        assertEquals(AutomationPermission.INCOGNITO_ON, KeyboardAutomation.incognitoPermission(true))
        assertEquals(AutomationPermission.INCOGNITO_OFF, KeyboardAutomation.incognitoPermission(false))
    }

    private val modes = listOf(
        KeyboardMode(id = "chat", name = "Chat"),
        KeyboardMode(id = "code", name = "Code"),
        KeyboardMode(id = "dup1", name = "Twin"),
        KeyboardMode(id = "dup2", name = "Twin"),
    )

    @Test
    fun `modes match by id, by name, and auto`() {
        assertEquals("chat", KeyboardAutomation.matchMode("chat", modes))
        assertEquals("code", KeyboardAutomation.matchMode("CODE", modes))
        assertEquals("chat", KeyboardAutomation.matchMode("Chat", modes))
        assertEquals(KeyboardAutomation.MODE_AUTO, KeyboardAutomation.matchMode("Auto", modes))
        assertNull(KeyboardAutomation.matchMode("twin", modes))
        assertNull(KeyboardAutomation.matchMode("nope", modes))
    }

    @Test
    fun `themes match default, a built-in by id and by name`() {
        val theme = BuiltInThemes.first()
        assertEquals(DEFAULT_THEME_ID, KeyboardAutomation.matchTheme("Default", emptyList()))
        assertEquals(theme.id, KeyboardAutomation.matchTheme(theme.id, emptyList()))
        assertEquals(theme.id, KeyboardAutomation.matchTheme(theme.id.uppercase(), emptyList()))
        assertNull(KeyboardAutomation.matchTheme("no such theme", emptyList()))
    }

    @Test
    fun `a custom theme wins a name it shares with a built-in`() {
        val builtIn = BuiltInThemes.first()
        val custom = builtIn.copy(id = "mine", variants = emptyList())
        assertEquals("mine", KeyboardAutomation.matchTheme(builtIn.name, listOf(custom)))
    }

    @Test
    fun `positions parse and clear each other`() {
        assertEquals(Position.SPLIT, Position.parse(" Split "))
        assertNull(Position.parse("sideways"))
        assertNull(Position.parse(null))
        assertEquals(OneHandedMode.OFF, Position.FLOATING.oneHanded)
        assertFalse(Position.LEFT.split || Position.LEFT.floating)
        assertTrue(Position.entries.all { listOf(it.oneHanded != OneHandedMode.OFF, it.split, it.floating).count { on -> on } <= 1 })
    }

    @Test
    fun `only tools with a panel can be opened`() {
        assertEquals(ToolbarTool.EMOJI, KeyboardAutomation.panelTool("emoji"))
        assertEquals(ToolbarTool.TRANSLATE, KeyboardAutomation.panelTool("TRANSLATE"))
        assertNull(KeyboardAutomation.panelTool("PASTE"))
        assertNull(KeyboardAutomation.panelTool("SELECT_ALL"))
        assertNull(KeyboardAutomation.panelTool("FLASHLIGHT"))
        assertNull(KeyboardAutomation.panelTool("nope"))
    }

    @Test
    fun `switch extras read booleans and the strings automation apps send`() {
        assertEquals(true, KeyboardAutomation.parseSwitch(true))
        assertEquals(false, KeyboardAutomation.parseSwitch("off"))
        assertEquals(true, KeyboardAutomation.parseSwitch(" TRUE "))
        assertEquals(false, KeyboardAutomation.parseSwitch(0))
        assertNull(KeyboardAutomation.parseSwitch("maybe"))
        assertNull(KeyboardAutomation.parseSwitch(null))
    }

    @Test
    fun `a word is one token`() {
        assertEquals("hello", KeyboardAutomation.cleanWord("  hello "))
        assertNull(KeyboardAutomation.cleanWord("two words"))
        assertNull(KeyboardAutomation.cleanWord(""))
        assertNull(KeyboardAutomation.cleanWord("x".repeat(65)))
    }

    @Test
    fun `a pause reads hours, and refuses what it cannot read rather than pausing forever`() {
        assertEquals(0, KeyboardAutomation.pauseHours(null))
        assertEquals(0, KeyboardAutomation.pauseHours(""))
        assertEquals(0, KeyboardAutomation.pauseHours(0))
        assertEquals(24, KeyboardAutomation.pauseHours(24))
        assertEquals(24, KeyboardAutomation.pauseHours(" 24 "))
        assertEquals(168, KeyboardAutomation.pauseHours(168L))
        assertNull(KeyboardAutomation.pauseHours(169))
        assertNull(KeyboardAutomation.pauseHours(-1))
        assertNull(KeyboardAutomation.pauseHours(1.5))
        assertNull(KeyboardAutomation.pauseHours("a day"))
        assertNull(KeyboardAutomation.pauseHours(true))
    }

    @Test
    fun `pausing and resuming need their own permission, not back up now`() {
        for (action in listOf(
            KeyboardAutomation.ACTION_PAUSE_BACKUP,
            KeyboardAutomation.ACTION_RESUME_BACKUP,
            KeyboardAutomation.ACTION_PAUSE_SYNC,
            KeyboardAutomation.ACTION_RESUME_SYNC,
        )) {
            assertEquals(action, AutomationPermission.BACKUP_PAUSE, KeyboardAutomation.permissions[action])
            assertTrue(action, action in KeyboardAutomation.actions)
        }
    }
}
