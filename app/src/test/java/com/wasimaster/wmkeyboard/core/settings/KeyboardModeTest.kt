package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.tools.BuiltInSymbolSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardModeTest {

    private val email = KeyboardMode(
        id = "email", name = "Email",
        symbolSetIds = listOf(BuiltInSymbolSets.EMAIL_ID),
        fieldKinds = listOf(ModeField.EMAIL),
    )
    private val password = KeyboardMode(
        id = "password", name = "Passwords",
        emojiBarMode = EmojiBarMode.OFF,
        symbolRowEnabled = false,
        fieldKinds = listOf(ModeField.PASSWORD),
    )
    private val browser = KeyboardMode(
        id = "browser", name = "Browser",
        apps = listOf("com.android.chrome"),
    )
    private val chat = KeyboardMode(
        id = "chat", name = "Chat",
        emojiBarMode = EmojiBarMode.ALWAYS,
        toolbarTools = listOf(ToolbarTool.GIF, ToolbarTool.STICKER),
        toolbarToolsAppend = true,
        toolboxOrder = listOf(ToolbarTool.GIF, ToolbarTool.STICKER),
        apps = listOf("com.whatsapp"),
        fieldKinds = listOf(ModeField.TEXT),
    )
    private val modes = listOf(password, email, browser, chat)

    @Test
    fun `no match resolves to null`() {
        assertNull(resolveKeyboardMode(modes, "com.example.other", emptySet(), null))
    }

    @Test
    fun `app binding matches`() {
        assertEquals(
            "browser",
            resolveKeyboardMode(modes, "com.android.chrome", emptySet(), null)?.id,
        )
    }

    @Test
    fun `field kind beats app-only binding`() {
        // A password box inside the browser gets the password mode.
        assertEquals(
            "password",
            resolveKeyboardMode(
                modes, "com.android.chrome", setOf(ModeField.PASSWORD), null,
            )?.id,
        )
    }

    @Test
    fun `app plus field binding needs both`() {
        // The chat composer in WhatsApp: app and text field both match.
        assertEquals(
            "chat",
            resolveKeyboardMode(modes, "com.whatsapp", setOf(ModeField.TEXT), null)?.id,
        )
        // A text field in some other app is not enough.
        assertNull(resolveKeyboardMode(modes, "com.example.other", setOf(ModeField.TEXT), null))
        // Neither is a non-text field inside WhatsApp — its search box, say.
        assertNull(resolveKeyboardMode(modes, "com.whatsapp", emptySet(), null))
    }

    @Test
    fun `a bound field type still wins inside a chat app`() {
        assertEquals(
            "password",
            resolveKeyboardMode(modes, "com.whatsapp", setOf(ModeField.PASSWORD), null)?.id,
        )
    }

    @Test
    fun `notification reply matches on the field alone`() {
        val replyChat = chat.copy(
            fieldKinds = listOf(ModeField.TEXT, ModeField.NOTIFICATION_REPLY),
        )
        // The reply box reports the system UI's package, never WhatsApp's —
        // the app binding is ignored for this one field kind.
        assertEquals(
            "chat",
            resolveKeyboardMode(
                listOf(password, email, browser, replyChat),
                "com.android.systemui",
                setOf(ModeField.TEXT, ModeField.NOTIFICATION_REPLY),
                null,
            )?.id,
        )
        // A mode without the reply binding still needs its app to match.
        assertNull(
            resolveKeyboardMode(
                modes,
                "com.android.systemui",
                setOf(ModeField.TEXT, ModeField.NOTIFICATION_REPLY),
                null,
            ),
        )
    }

    @Test
    fun `topUpModeApps appends only to the named mode`() {
        val additions = mapOf("chat" to listOf("com.facebook.katana"))
        val topped = topUpModeApps(modes, additions)
        assertEquals(
            listOf("com.whatsapp", "com.facebook.katana"),
            topped.first { it.id == "chat" }.apps,
        )
        assertEquals(browser.apps, topped.first { it.id == "browser" }.apps)
    }

    @Test
    fun `topUpModeApps skips a package the user already routed`() {
        // Bound to the same mode already: no duplicate.
        val same = topUpModeApps(modes, mapOf("chat" to listOf("com.whatsapp")))
        assertEquals(chat.apps, same.first { it.id == "chat" }.apps)
        // Bound to a different mode: the user's routing wins.
        val other = topUpModeApps(modes, mapOf("chat" to listOf("com.android.chrome")))
        assertEquals(chat.apps, other.first { it.id == "chat" }.apps)
    }

    @Test
    fun `topUpModeApps leaves a deleted mode deleted`() {
        val withoutChat = listOf(password, email, browser)
        val topped = topUpModeApps(withoutChat, mapOf("chat" to listOf("com.facebook.katana")))
        assertEquals(withoutChat, topped)
    }

    @Test
    fun `topUpModeFields appends without duplicating`() {
        val additions = mapOf("chat" to listOf(ModeField.NOTIFICATION_REPLY, ModeField.TEXT))
        val topped = topUpModeFields(modes, additions)
        assertEquals(
            listOf(ModeField.TEXT, ModeField.NOTIFICATION_REPLY),
            topped.first { it.id == "chat" }.fieldKinds,
        )
        assertEquals(email.fieldKinds, topped.first { it.id == "email" }.fieldKinds)
    }

    @Test
    fun `default chat mode carries the social apps and the reply binding`() {
        val defaultChat = DefaultKeyboardModes.first { it.id == "mode_chat" }
        for (pkg in ModeAppsAddedInSeedVersion3.getValue("mode_chat")) {
            assertTrue(pkg, pkg in defaultChat.apps)
        }
        assertTrue(ModeField.NOTIFICATION_REPLY in defaultChat.fieldKinds)
    }

    @Test
    fun `a mode with no bindings never matches automatically`() {
        val manualOnly = listOf(KeyboardMode(id = "manual", name = "Manual"))
        assertNull(resolveKeyboardMode(manualOnly, "com.whatsapp", setOf(ModeField.TEXT), null))
        assertEquals(
            "manual",
            resolveKeyboardMode(manualOnly, "com.whatsapp", emptySet(), "manual")?.id,
        )
    }

    @Test
    fun `manual pick beats everything`() {
        assertEquals(
            "email",
            resolveKeyboardMode(
                modes, "com.android.chrome", setOf(ModeField.PASSWORD), "email",
            )?.id,
        )
    }

    @Test
    fun `stale manual id falls back to automatic`() {
        assertEquals(
            "browser",
            resolveKeyboardMode(modes, "com.android.chrome", emptySet(), "deleted-mode")?.id,
        )
    }

    @Test
    fun `applyMode overrides only the mode's fields`() {
        val base = KeyboardSettings(emojiBarMode = EmojiBarMode.ALWAYS, symbolRowEnabled = true)
        val applied = base.applyMode(password)
        assertEquals(EmojiBarMode.OFF, applied.emojiBarMode)
        assertEquals(false, applied.symbolRowEnabled)
        // Untouched fields inherit.
        assertEquals(base.toolbarTools, applied.toolbarTools)
        assertEquals(base.symbolRowSetIds, applied.symbolRowSetIds)
    }

    @Test
    fun `applyMode with sets switches the active set to the mode's first`() {
        val base = KeyboardSettings(symbolRowActiveSetId = BuiltInSymbolSets.PUNCTUATION_ID)
        val applied = base.applyMode(email)
        assertEquals(listOf(BuiltInSymbolSets.EMAIL_ID), applied.symbolRowSetIds)
        assertEquals(BuiltInSymbolSets.EMAIL_ID, applied.symbolRowActiveSetId)
    }

    @Test
    fun `append mode adds to the user's pins without duplicating them`() {
        val base = KeyboardSettings(
            toolbarTools = listOf(ToolbarTool.EMOJI, ToolbarTool.GIF),
            enabledTools = listOf(ToolbarTool.EMOJI, ToolbarTool.GIF),
        )
        val applied = base.applyMode(chat)
        assertEquals(
            listOf(ToolbarTool.EMOJI, ToolbarTool.GIF, ToolbarTool.STICKER),
            applied.toolbarTools,
        )
        // A pinned tool the user had switched off is enabled while active.
        assertEquals(
            listOf(ToolbarTool.EMOJI, ToolbarTool.GIF, ToolbarTool.STICKER),
            applied.enabledTools,
        )
    }

    @Test
    fun `replace mode swaps the pins outright`() {
        val base = KeyboardSettings(toolbarTools = listOf(ToolbarTool.EMOJI, ToolbarTool.GIF))
        val applied = base.applyMode(chat.copy(toolbarToolsAppend = false))
        assertEquals(listOf(ToolbarTool.GIF, ToolbarTool.STICKER), applied.toolbarTools)
    }

    @Test
    fun `mode toolbox order leads, the rest keeps its global rank`() {
        val base = KeyboardSettings(
            toolboxOrder = listOf(ToolbarTool.CLIPBOARD, ToolbarTool.STICKER, ToolbarTool.VOICE),
        )
        val applied = base.applyMode(chat)
        assertEquals(
            listOf(
                ToolbarTool.GIF, ToolbarTool.STICKER, ToolbarTool.CLIPBOARD, ToolbarTool.VOICE,
            ),
            applied.toolboxOrder,
        )
    }

    @Test
    fun `mode list round-trips through the codec`() {
        val decoded = KeyboardModeCodec.decodeList(KeyboardModeCodec.encodeList(modes))
        assertEquals(modes, decoded)
    }

    @Test
    fun `sanitizeBarOrder repairs missing and duplicate rows`() {
        assertEquals(
            listOf(BarRow.EMOJI, BarRow.TOOLS, BarRow.TOPBAR, BarRow.SYMBOL, BarRow.FANCY),
            sanitizeBarOrder(listOf(BarRow.EMOJI, BarRow.EMOJI, BarRow.TOPBAR)),
        )
        assertEquals(DefaultBarOrder, sanitizeBarOrder(emptyList()))
        // An order stored before the fancy and tools rows existed: the fancy
        // row is appended, nearest the keys, and the tools row lands just over
        // the strip — where it always drew — rather than at the bottom.
        assertEquals(
            listOf(BarRow.TOOLS, BarRow.TOPBAR, BarRow.EMOJI, BarRow.SYMBOL, BarRow.FANCY),
            sanitizeBarOrder(listOf(BarRow.TOPBAR, BarRow.EMOJI, BarRow.SYMBOL)),
        )
        // A stored order is never reshuffled, only filled in.
        val reversed = DefaultBarOrder.reversed()
        assertEquals(reversed, sanitizeBarOrder(reversed))
    }

    @Test
    fun `a mode with no theme leaves the theme settings alone`() {
        val base = KeyboardSettings(
            keyboardThemeId = "custom_1",
            autoTheme = AutoThemeSettings(enabled = true, darkThemeId = "dracula"),
        )
        val applied = base.applyMode(email)
        assertEquals("custom_1", applied.keyboardThemeId)
        assertTrue(applied.autoTheme.enabled)
    }

    @Test
    fun `a mode theme beats both the picked theme and the auto pair`() {
        val base = KeyboardSettings(
            keyboardThemeId = "custom_1",
            autoTheme = AutoThemeSettings(enabled = true, darkThemeId = "dracula"),
        )
        val applied = base.applyMode(password.copy(themeId = "nord"))
        assertEquals("nord", applied.keyboardThemeId)
        // Auto-theme would otherwise ignore keyboardThemeId entirely.
        assertFalse(applied.autoTheme.enabled)
        // Scoped to the view: the pair itself is untouched and comes back.
        assertEquals("dracula", applied.autoTheme.darkThemeId)
        assertEquals("custom_1", base.keyboardThemeId)
    }
}
