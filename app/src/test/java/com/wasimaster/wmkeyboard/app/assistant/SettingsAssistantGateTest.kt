package com.wasimaster.wmkeyboard.app.assistant

import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.XmlSearchStrings
import com.wasimaster.wmkeyboard.app.rankSettings
import com.wasimaster.wmkeyboard.app.settingsSearchIndex
import com.wasimaster.wmkeyboard.app.settingsSearchVocabulary
import com.wasimaster.wmkeyboard.core.settings.AppUiSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Offer the Settings assistant" (#830, test coverage #878): what it hides
 * while off, what it leaves alone while on, and that the switch itself stays
 * findable. The default and the stored round trip are in
 * `SettingsAssistantSettingTest` under :feature:ime, which has Robolectric.
 *
 * The screens are Compose and :app has no Robolectric outside the doc shots,
 * so each one asks [SettingsAssistantGate] and the last tests pin that they
 * do: the rows, the route and the search button read the gate, and the gate's
 * answers are tested here.
 */
class SettingsAssistantGateTest {

    private val strings = XmlSearchStrings.forApp()
    private val index = settingsSearchIndex(strings)
    private val vocabulary = settingsSearchVocabulary(strings)

    private fun settings(on: Boolean) = KeyboardSettings(appUi = AppUiSettings(settingsAssistant = on))

    private fun search(query: String, on: Boolean) =
        rankSettings(query, SettingsAssistantGate.searchable(index, on), vocabulary).all

    @Test
    fun `the assistant is offered by default, as it shipped`() {
        assertTrue(SettingsDefaults.appUi.settingsAssistant)
        assertTrue(SettingsAssistantGate.offered(KeyboardSettings()))
        assertFalse(SettingsAssistantGate.offered(settings(on = false)))
    }

    @Test
    fun `off, no search result leads to the assistant`() {
        assertTrue("the index never offered the assistant", index.any(SettingsAssistantGate::opensAssistant))
        assertEquals(emptyList<Any>(), SettingsAssistantGate.searchable(index, offered = false)
            .filter(SettingsAssistantGate::opensAssistant).map { it.key })
        for (query in listOf("assistant", "settings assistant", "ask")) {
            assertEquals(query, emptyList<Any>(), search(query, on = false)
                .filter(SettingsAssistantGate::opensAssistant).map { it.key })
        }
    }

    @Test
    fun `the switch is found either way, so the assistant can be turned back on`() {
        for (on in listOf(true, false)) {
            val hits = search("settings assistant", on).take(5).map { it.titleRes }
            assertTrue("on=$on: $hits", R.string.toolai_hub_settings_assistant_title in hits)
        }
        assertTrue(search("assistant", on = false).take(5)
            .any { it.titleRes == R.string.toolai_hub_settings_assistant_title })
    }

    @Test
    fun `on, the search index and its results are what they were`() {
        assertEquals(index, SettingsAssistantGate.searchable(index, offered = true))
        assertTrue(search("settings assistant", on = true).take(5).any(SettingsAssistantGate::opensAssistant))
    }

    // The screens. Read as source, because nothing on the JVM here can draw
    // them; a change that stops asking the gate fails here.

    private fun source(name: String) = listOf(
        File("src/main/java/com/wasimaster/wmkeyboard/app/$name"),
        File("app/src/main/java/com/wasimaster/wmkeyboard/app/$name"),
    ).first { it.exists() }.readText()

    @Test
    fun `both AI screens show the assistant's row only while it is offered`() {
        val text = source("AiToolScreens.kt")
        val rows = Regex("""item\(([^)]*)\)\s*\{\s*NavRow\(\s*title = R\.string\.settings_assistant_title""")
            .findAll(text).map { it.groupValues[1].trim() }.toList()
        assertEquals("AiHubSettings and AiToolSettings each draw the row once", 2, rows.size)
        val gated = Regex("""val (\w+) = settings\.watch \{ SettingsAssistantGate\.offered\(it\) \}""")
            .findAll(text).map { it.groupValues[1] }.toSet()
        for (row in rows) {
            val flag = row.removePrefix("visible =").substringBefore(',').trim()
            assertTrue("the row is drawn with ($row), not under the gate", row.startsWith("visible =") && flag in gated)
        }
    }

    @Test
    fun `the search screen's button and results ask the gate`() {
        val text = source("SettingsSearchScreen.kt")
        assertTrue(text.contains("val assistant = settings.watch { SettingsAssistantGate.offered(it) }"))
        assertTrue(text.contains("SettingsAssistantGate.searchable(searchIndex ?: c.index, assistant)"))
        // The "Ask the settings assistant" button in the top bar.
        assertTrue(text.contains("if (onAsk != null && assistant) {"))
        assertTrue(text.contains("assistant || !SettingsAssistantGate.opensAssistant(it)"))
    }

    @Test
    fun `off, the route pops back before the assistant's screen is built`() {
        val text = source("MainActivity.kt")
        val route = text.substringAfter("composable(\"settings_assistant\") {")
        val gate = route.indexOf("SettingsAssistantGate.offered(it)")
        val back = route.indexOf("navController.popBackStack()")
        val leave = route.indexOf("return@composable")
        val screen = route.indexOf("SettingsAssistantScreen(")
        assertTrue("the route never asks the gate", gate >= 0)
        assertTrue("off must pop back and return before the screen", gate < back && back < leave && leave < screen)
    }
}
