package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wasimaster.wmkeyboard.core.selection.SelectionMacro
import com.wasimaster.wmkeyboard.core.theme.PhotoAttribution
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBootSettingsTest {

    @Test
    fun `tools that need locked storage are dropped`() {
        val settings = KeyboardSettings(
            enabledTools = listOf(
                ToolbarTool.CLIPBOARD, ToolbarTool.SNIPPETS, ToolbarTool.AI, ToolbarTool.CALENDAR,
                ToolbarTool.EMOJI, ToolbarTool.CALCULATOR, ToolbarTool.CURSOR_LEFT,
            ),
        ).restrictedToDirectBoot()

        assertEquals(
            listOf(ToolbarTool.EMOJI, ToolbarTool.CALCULATOR, ToolbarTool.CURSOR_LEFT),
            settings.enabledTools,
        )
    }

    @Test
    fun `the toolbar and toolbox shrink to the same set`() {
        val settings = KeyboardSettings(
            toolbarTools = listOf(ToolbarTool.CLIPBOARD, ToolbarTool.EMOJI),
            toolboxOrder = listOf(ToolbarTool.GIF, ToolbarTool.LEVEL),
        ).restrictedToDirectBoot()

        assertEquals(listOf(ToolbarTool.EMOJI), settings.toolbarTools)
        assertEquals(listOf(ToolbarTool.LEVEL), settings.toolboxOrder)
    }

    @Test
    fun `selection macros that need the unlocked half are dropped`() {
        val settings = KeyboardSettings(
            selectionMacros = SelectionMacroSettings(
                macros = setOf(
                    SelectionMacro.UNDO, SelectionMacro.CUT, SelectionMacro.PASTE, SelectionMacro.READ_ALOUD,
                    SelectionMacro.ADD_CONTACT, SelectionMacro.MAP, SelectionMacro.CALENDAR, SelectionMacro.SHARE,
                ),
                order = listOf(SelectionMacro.SHARE, SelectionMacro.CUT),
            ),
        ).restrictedToDirectBoot()

        assertEquals(setOf(SelectionMacro.UNDO, SelectionMacro.CUT), settings.selectionMacros.macros)
        // The order only orders; it is left as the user made it.
        assertEquals(listOf(SelectionMacro.SHARE, SelectionMacro.CUT), settings.selectionMacros.order)
    }

    @Test
    fun `the settings app is not offered from a lock screen`() {
        // It would have to start an activity, which a locked device refuses.
        assertFalse(isDirectBootSafeTool(ToolbarTool.SETTINGS))
    }

    @Test
    fun `everything backed by filesDir falls back to a bundled default`() {
        val settings = KeyboardSettings(
            keyFontId = "custom",
            scriptFontIds = mapOf(
                "BENGALI" to "google:Hind Siliguri",
                "DEVANAGARI" to "google:Noto Sans Devanagari",
            ),
            emojiFont = EmojiFontChoice.CUSTOM,
            customThemes = listOf(
                ThemeSpec(
                    id = "mine",
                    name = "Mine",
                    backgroundImage = "/data/user/0/pkg/files/theme_images/mine.img",
                    backgroundImageLandscape = "/data/user/0/pkg/files/theme_images/mine_land.img",
                ),
            ),
        ).restrictedToDirectBoot()

        assertEquals("default", settings.keyFontId)
        assertTrue(settings.scriptFontIds.isEmpty())
        assertEquals(EmojiFontChoice.SYSTEM, settings.emojiFont)
        // The theme itself survives — only the unreadable image path goes.
        assertEquals("mine", settings.customThemes.single().id)
        assertNull(settings.customThemes.single().backgroundImage)
        assertNull(settings.customThemes.single().backgroundImageLandscape)
    }

    @Test
    fun `a photo credit goes with the photo it belongs to`() {
        val credit = PhotoAttribution(
            provider = "unsplash",
            photoId = "abc",
            photographer = "Ana Silva",
        )
        val settings = KeyboardSettings(
            customThemes = listOf(
                ThemeSpec(
                    id = "mine",
                    name = "Mine",
                    backgroundImage = "/data/user/0/pkg/files/theme_images/mine.img",
                    backgroundPhoto = credit,
                    backgroundImageLandscape = "/data/user/0/pkg/files/theme_images/mine_land.img",
                    backgroundPhotoLandscape = credit,
                ),
            ),
            photoBackground = PhotoBackgroundSettings(rotateEnabled = true),
        ).restrictedToDirectBoot()

        // Naming a photographer under a photo that is not being drawn would
        // just be false.
        assertNull(settings.customThemes.single().backgroundPhoto)
        assertNull(settings.customThemes.single().backgroundPhotoLandscape)
        // The rotation pool is under filesDir too, so there is nothing to
        // rotate to and nothing downstream needs to work that out.
        assertFalse(settings.photoBackground.rotateEnabled)
    }

    @Test
    fun `nothing personal is read or written while locked`() {
        val settings = KeyboardSettings(
            suggestionSources = SuggestionSourceSettings(
                contacts = true,
                contactEmails = true,
                appNames = true,
            ),
            addWordsToSystemDictionary = true,
        ).restrictedToDirectBoot()

        assertFalse(settings.suggestionSources.contacts)
        assertFalse(settings.suggestionSources.contactEmails)
        assertFalse(settings.suggestionSources.appNames)
        assertFalse(settings.addWordsToSystemDictionary)
        assertFalse(settings.clipboard.suggestRecent)
    }

    @Test
    fun `emoji mix sources wait for the unlock`() {
        // Their indexes are under filesDir. Loaded while locked they came up
        // empty, and the same list after the unlock read as nothing changed.
        val source = EmojiMixSource("m1", EmojiMixSourceKind.INDEX, "https://example.org/mixes.json", pairCount = 10)
        val settings = KeyboardSettings(
            emoji = EmojiSettings(mix = EmojiMixSettings(sources = listOf(source))),
        )

        assertEquals(emptyList<EmojiMixSource>(), settings.restrictedToDirectBoot().emoji.mix.sources)
        assertEquals(listOf(source), settings.emoji.mix.sources)
    }

    @Test
    fun `credentials are never mirrored into device-protected storage`() {
        // What LockedSettings writes is exactly a secretless settings export,
        // so the guarantee is testable without an Android SharedPreferences.
        val prefs = mutablePreferencesOf(
            *SettingsBackup.SECRET_KEYS
                .map { stringPreferencesKey(it) to "secret-$it" }
                .toTypedArray(),
            stringPreferencesKey("active_layout_id") to "qwerty",
        )

        val mirrored = SettingsBackup.encodeSettings(prefs, includeSecrets = false)

        assertEquals(setOf("active_layout_id"), mirrored.keys)
    }

    @Test
    fun `every credential the settings hold is on the secret list`() {
        // The mirror filters by key name, so a credential added to
        // KeyboardSettings without a SECRET_KEYS entry would silently land in
        // storage that is not covered by the user's credential.
        val credentials = setOf(
            "translate_api_key", "deepl_api_key", "translate_server_key",
            "klipy_api_key", "brave_api_key", "giphy_api_key", "telegram_bot_token",
            "ai_anthropic_key", "ai_openai_key", "ai_gemini_key", "hf_token",
        )
        assertTrue(credentials.all { it in SettingsBackup.SECRET_KEYS })
    }
}
