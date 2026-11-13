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
    fun `hint binding matches on the placeholder text alone`() {
        val extension = KeyboardMode(
            id = "ext", name = "Extensions", autocorrect = false,
            hints = listOf("extension"),
        )
        val all = modes + extension
        // Case-ignored "contains": "File extension" carries "extension".
        assertEquals(
            "ext",
            resolveKeyboardMode(all, "com.mixplorer", setOf(ModeField.TEXT), null, "File Extension")?.id,
        )
        // The same box with a different hint is not it.
        assertNull(
            resolveKeyboardMode(all, "com.mixplorer", setOf(ModeField.TEXT), null, "Enter name"),
        )
        // Nor is a field with no hint at all.
        assertNull(resolveKeyboardMode(all, "com.mixplorer", setOf(ModeField.TEXT), null, null))
    }

    @Test
    fun `hint binding is one more condition on top of app and field`() {
        val rename = KeyboardMode(
            id = "rename", name = "Rename",
            apps = listOf("com.mixplorer"),
            fieldKinds = listOf(ModeField.SINGLE_LINE),
            hints = listOf("name"),
        )
        val fields = setOf(ModeField.TEXT, ModeField.SINGLE_LINE)
        assertEquals(
            "rename",
            resolveKeyboardMode(listOf(rename), "com.mixplorer", fields, null, "Enter name")?.id,
        )
        // Right app and hint, wrong shape: the editor's multi-line box.
        assertNull(
            resolveKeyboardMode(
                listOf(rename), "com.mixplorer",
                setOf(ModeField.TEXT, ModeField.MULTILINE), null, "Enter name",
            ),
        )
        // Right shape and hint, wrong app.
        assertNull(resolveKeyboardMode(listOf(rename), "com.example.other", fields, null, "Enter name"))
        // Right app and shape, no hint.
        assertNull(resolveKeyboardMode(listOf(rename), "com.mixplorer", fields, null, null))
    }

    @Test
    fun `a hint-bound mode beats a field-bound one, which beats an app-bound one`() {
        val editor = KeyboardMode(id = "editor", name = "Editor", apps = listOf("com.mixplorer"))
        val oneLine = KeyboardMode(
            id = "oneline", name = "One line", fieldKinds = listOf(ModeField.SINGLE_LINE),
        )
        val ext = KeyboardMode(id = "ext", name = "Extension", hints = listOf("extension"))
        // Listed least specific first, so order alone would pick the wrong one.
        val all = listOf(editor, oneLine, ext)
        val fields = setOf(ModeField.TEXT, ModeField.SINGLE_LINE)
        assertEquals("ext", resolveKeyboardMode(all, "com.mixplorer", fields, null, "Extension")?.id)
        assertEquals("oneline", resolveKeyboardMode(all, "com.mixplorer", fields, null, "Enter name")?.id)
        assertEquals(
            "editor",
            resolveKeyboardMode(all, "com.mixplorer", setOf(ModeField.TEXT, ModeField.MULTILINE), null, null)?.id,
        )
    }

    @Test
    fun `notification reply still needs its hint when one is bound`() {
        val replyChat = chat.copy(
            fieldKinds = listOf(ModeField.NOTIFICATION_REPLY),
            hints = listOf("reply"),
        )
        val fields = setOf(ModeField.TEXT, ModeField.NOTIFICATION_REPLY)
        assertEquals(
            "chat",
            resolveKeyboardMode(listOf(replyChat), "com.android.systemui", fields, null, "Reply")?.id,
        )
        assertNull(resolveKeyboardMode(listOf(replyChat), "com.android.systemui", fields, null, "Type"))
    }

    @Test
    fun `text shape fields follow the multi-line and no-suggestions flags`() {
        val text = android.text.InputType.TYPE_CLASS_TEXT
        val multi = android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        val noSuggest = android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        // A rename box: plain single-line text.
        assertEquals(setOf(ModeField.SINGLE_LINE), textShapeFields(text, secure = false))
        // A text editor.
        assertEquals(setOf(ModeField.MULTILINE), textShapeFields(text or multi, secure = false))
        // A code editor asks for no suggestions on top.
        assertEquals(
            setOf(ModeField.MULTILINE, ModeField.NO_SUGGESTIONS),
            textShapeFields(text or multi or noSuggest, secure = false),
        )
        // An email box is text-class too, so it has a shape.
        assertEquals(
            setOf(ModeField.SINGLE_LINE),
            textShapeFields(text or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, secure = false),
        )
        // A keypad has none, and neither does a password box — a mode bound
        // to single-line text must never take over a login form.
        assertEquals(emptySet<ModeField>(), textShapeFields(android.text.InputType.TYPE_CLASS_NUMBER, secure = false))
        assertEquals(emptySet<ModeField>(), textShapeFields(text, secure = true))
    }

    @Test
    fun `hints survive the codec, and an old mode without them decodes empty`() {
        val withHints = chat.copy(hints = listOf("extension"))
        val decoded = KeyboardModeCodec.decodeList(KeyboardModeCodec.encodeList(listOf(withHints)))
        assertEquals(listOf("extension"), decoded.single().hints)
        val legacy = KeyboardModeCodec.encodeList(listOf(chat)).replace(",\"hints\":[]", "")
        assertEquals(emptyList<String>(), KeyboardModeCodec.decodeList(legacy).single().hints)
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
    fun `a mode switched off leaves the keyboard's view and takes its bindings with it`() {
        // The feature is on; only the browser mode is off (#152).
        val settings = KeyboardSettings(
            modesEnabled = true,
            keyboardModes = listOf(password, email, browser.copy(enabled = false), chat),
        )
        val seen = settings.withoutModes()
        assertEquals(listOf("password", "email", "chat"), seen.keyboardModes.map { it.id })
        // The tool stays: modes are still on, one of them is merely off.
        assertEquals(settings.toolbarTools, seen.toolbarTools)
        // What the keyboard resolves from is what it sees, so the browser app
        // no longer matches anything, and a manual pick of it goes nowhere.
        assertNull(resolveKeyboardMode(seen.keyboardModes, "com.android.chrome", emptySet(), null))
        assertNull(resolveKeyboardMode(seen.keyboardModes, "com.android.chrome", emptySet(), "browser"))
    }

    @Test
    fun `modes overriding a row are the ones that set it the other way`() {
        val writing = KeyboardMode(id = "writing", name = "Writing", symbolRowEnabled = true)
        val off = KeyboardSettings(
            modesEnabled = true,
            symbolRowEnabled = false,
            emojiBarMode = EmojiBarMode.OFF,
            keyboardModes = listOf(password, email, writing, chat),
        )
        // Passwords sets the row off, which is what the global switch says too.
        assertEquals(listOf("writing"), off.modesOverridingRow(BarRow.SYMBOL).map { it.id })
        assertEquals(listOf("chat"), off.modesOverridingRow(BarRow.EMOJI).map { it.id })
        val on = off.copy(symbolRowEnabled = true, emojiBarMode = EmojiBarMode.ALWAYS)
        assertEquals(listOf("password"), on.modesOverridingRow(BarRow.SYMBOL).map { it.id })
        assertEquals(listOf("password"), on.modesOverridingRow(BarRow.EMOJI).map { it.id })
        // Rows no mode can set, and modes the keyboard is not using, say nothing.
        assertTrue(off.modesOverridingRow(BarRow.DICTIONARY).isEmpty())
        assertTrue(off.copy(modesEnabled = false).modesOverridingRow(BarRow.SYMBOL).isEmpty())
        val writingOff = off.copy(keyboardModes = listOf(writing.copy(enabled = false)))
        assertTrue(writingOff.modesOverridingRow(BarRow.SYMBOL).isEmpty())
    }

    @Test
    fun `with every mode on the view is the settings themselves`() {
        val settings = KeyboardSettings(modesEnabled = true, keyboardModes = modes)
        assertTrue(settings.withoutModes() === settings)
    }

    @Test
    fun `a stored mode from before the switch decodes as on, and off survives the codec`() {
        val json = KeyboardModeCodec.encodeList(listOf(browser.copy(enabled = false)))
        assertFalse(KeyboardModeCodec.decodeList(json).single().enabled)
        val legacy = json.replace("\"enabled\":false,", "").replace(",\"enabled\":false", "")
        assertFalse(legacy.contains("enabled"))
        assertTrue(KeyboardModeCodec.decodeList(legacy).single().enabled)
    }

    @Test
    fun `one unreadable mode no longer takes the rest of the list with it`() {
        val good = KeyboardModeCodec.encodeList(listOf(browser)).removeSurrounding("[", "]")
        val json = "[{\"id\":\"broken\",\"emojiBarMode\":\"NOT_A_MODE\"},$good]"
        assertEquals(listOf(browser.id), KeyboardModeCodec.decodeList(json).map { it.id })
    }

    @Test
    fun `a store with nothing readable falls back to the shipped modes`() {
        assertEquals(DefaultKeyboardModes, KeyboardModeCodec.decodeList("not json"))
        assertEquals(DefaultKeyboardModes, KeyboardModeCodec.decodeList("[{\"name\":1}]"))
        assertEquals(emptyList<KeyboardMode>(), KeyboardModeCodec.decodeList("[]"))
    }

    @Test
    fun `missing defaults lists only the built-ins that are gone`() {
        val kept = DefaultKeyboardModes.drop(2)
        assertEquals(DefaultKeyboardModes.take(2), KeyboardModeCodec.missingDefaults(kept))
        assertEquals(emptyList<KeyboardMode>(), KeyboardModeCodec.missingDefaults(DefaultKeyboardModes))
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
    fun `a mode that pins nothing leaves a switched-off tool switched off`() {
        // Switching a tool off leaves it on the toolbar list, so a mode that
        // inherits that list must not read it as "the mode pins this" and turn
        // it back on: Voice came back in every chat app that way.
        val base = KeyboardSettings(
            toolbarTools = listOf(ToolbarTool.EMOJI, ToolbarTool.VOICE),
            enabledTools = listOf(ToolbarTool.EMOJI),
        )
        val applied = base.applyMode(browser)
        assertEquals(listOf(ToolbarTool.EMOJI, ToolbarTool.VOICE), applied.toolbarTools)
        assertEquals(listOf(ToolbarTool.EMOJI), applied.enabledTools)
    }

    @Test
    fun `an appending mode enables only what it pins itself`() {
        val base = KeyboardSettings(
            toolbarTools = listOf(ToolbarTool.EMOJI, ToolbarTool.VOICE),
            enabledTools = listOf(ToolbarTool.EMOJI),
        )
        val applied = base.applyMode(chat)
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
            listOf(
                BarRow.STICKERS, BarRow.EMOJI, BarRow.TOOLS, BarRow.MACROS, BarRow.RECENT, BarRow.TOPBAR,
                BarRow.SYMBOL, BarRow.DICTIONARY, BarRow.FANCY, BarRow.KEYBOARD,
            ),
            sanitizeBarOrder(listOf(BarRow.EMOJI, BarRow.EMOJI, BarRow.TOPBAR)),
        )
        assertEquals(DefaultBarOrder, sanitizeBarOrder(emptyList()))
        // An order stored before the fancy, tools and macros rows existed: the
        // fancy row is appended, nearest the keys, and the tools and macros
        // rows land just over the strip — where tools always drew — rather
        // than at the bottom. The keys come last, so nothing moves below them.
        // The sticker tray, newer than all of them, goes on top.
        assertEquals(
            listOf(
                BarRow.STICKERS, BarRow.TOOLS, BarRow.MACROS, BarRow.RECENT, BarRow.TOPBAR, BarRow.EMOJI,
                BarRow.SYMBOL, BarRow.DICTIONARY, BarRow.FANCY, BarRow.KEYBOARD,
            ),
            sanitizeBarOrder(listOf(BarRow.TOPBAR, BarRow.EMOJI, BarRow.SYMBOL)),
        )
        // A stored order is never reshuffled, only filled in.
        val reversed = DefaultBarOrder.reversed()
        assertEquals(reversed, sanitizeBarOrder(reversed))
    }

    @Test
    fun `bar order splits around the keys`() {
        val order = listOf(BarRow.TOPBAR, BarRow.EMOJI, BarRow.KEYBOARD, BarRow.TOOLS, BarRow.SYMBOL)
        assertEquals(listOf(BarRow.TOPBAR, BarRow.EMOJI), barRowsAboveKeys(order))
        assertEquals(listOf(BarRow.TOOLS, BarRow.SYMBOL), barRowsBelowKeys(order))
        // Keys first: everything is below them.
        assertEquals(emptyList<BarRow>(), barRowsAboveKeys(listOf(BarRow.KEYBOARD, BarRow.TOPBAR)))
        assertEquals(listOf(BarRow.TOPBAR), barRowsBelowKeys(listOf(BarRow.KEYBOARD, BarRow.TOPBAR)))
        // The default puts every row above the keys, as before the entry existed.
        assertEquals(DefaultBarOrder.dropLast(1), barRowsAboveKeys(DefaultBarOrder))
        assertEquals(emptyList<BarRow>(), barRowsBelowKeys(DefaultBarOrder))
        // No keyboard entry at all: nothing goes below.
        val noKeys = listOf(BarRow.TOPBAR, BarRow.EMOJI)
        assertEquals(noKeys, barRowsAboveKeys(noKeys))
        assertEquals(emptyList<BarRow>(), barRowsBelowKeys(noKeys))
    }

    @Test
    fun `autospace off silences all three automatic spaces`() {
        val base = KeyboardSettings(
            autoText = AutoTextSettings(capitalize = true, spaceAfterPunctuation = true),
            suggestionStrip = SuggestionStripSettings(autoSpaceAfterSuggestion = true),
            gesture = GestureSettings(autoSpaceAfterGlide = true),
        )
        val applied = base.applyMode(password.copy(autoSpace = false))
        assertFalse(applied.autoText.spaceAfterPunctuation)
        assertFalse(applied.suggestionStrip.autoSpaceAfterSuggestion)
        assertFalse(applied.gesture.autoSpaceAfterGlide)
        // The space in front of a glided word too (#184): it has no switch of
        // its own, and the mode is the one thing that may turn it off.
        assertFalse(applied.gesture.autoSpaceBeforeGlide)
        // A view, not a write: the globals are untouched.
        assertTrue(base.autoText.spaceAfterPunctuation)
    }

    @Test
    fun `autospace on turns the three back on, punctuation included`() {
        // Punctuation spacing is off globally by default — a mode asking for
        // automatic spaces has to mean that one too.
        val base = KeyboardSettings(
            autoText = AutoTextSettings(spaceAfterPunctuation = false),
            suggestionStrip = SuggestionStripSettings(autoSpaceAfterSuggestion = false),
            gesture = GestureSettings(autoSpaceAfterGlide = false),
        )
        val applied = base.applyMode(password.copy(autoSpace = true))
        assertTrue(applied.autoText.spaceAfterPunctuation)
        assertTrue(applied.suggestionStrip.autoSpaceAfterSuggestion)
        assertTrue(applied.gesture.autoSpaceAfterGlide)
    }

    @Test
    fun `autospace and automatic capitals share a group without clobbering`() {
        // Both land in autoText; applied from the base each, one would win.
        val base = KeyboardSettings(
            autoText = AutoTextSettings(capitalize = true, spaceAfterPunctuation = true),
        )
        val applied = base.applyMode(
            password.copy(autoCapitalize = false, autoSpace = false),
        )
        assertFalse(applied.autoText.capitalize)
        assertFalse(applied.autoText.spaceAfterPunctuation)
    }

    @Test
    fun `a mode with no autospace override leaves every space setting alone`() {
        val base = KeyboardSettings(
            autoText = AutoTextSettings(spaceAfterPunctuation = true),
            suggestionStrip = SuggestionStripSettings(autoSpaceAfterSuggestion = false),
            gesture = GestureSettings(autoSpaceAfterGlide = false),
        )
        val applied = base.applyMode(email)
        assertTrue(applied.autoText.spaceAfterPunctuation)
        assertFalse(applied.suggestionStrip.autoSpaceAfterSuggestion)
        assertFalse(applied.gesture.autoSpaceAfterGlide)
    }

    @Test
    fun `glide off turns gesture typing off`() {
        val base = KeyboardSettings(gestureTyping = true)
        assertFalse(base.applyMode(email.copy(glide = false)).gestureTyping)
        assertTrue(KeyboardSettings(gestureTyping = false).applyMode(email.copy(glide = true)).gestureTyping)
    }

    @Test
    fun `two-thumb off turns two-thumb glide off`() {
        val base = KeyboardSettings(gesture = GestureSettings(twoThumbGlide = true))
        assertFalse(base.applyMode(email.copy(twoThumb = false)).gesture.twoThumbGlide)
    }

    @Test
    fun `a mode with no glide or two-thumb override inherits both`() {
        val on = KeyboardSettings(gestureTyping = true, gesture = GestureSettings(twoThumbGlide = true))
        assertTrue(on.applyMode(email).gestureTyping)
        assertTrue(on.applyMode(email).gesture.twoThumbGlide)
        val off = KeyboardSettings(gestureTyping = false, gesture = GestureSettings(twoThumbGlide = false))
        assertFalse(off.applyMode(email).gestureTyping)
        assertFalse(off.applyMode(email).gesture.twoThumbGlide)
    }

    @Test
    fun `autospace and two-thumb share the gesture group without clobbering`() {
        val base = KeyboardSettings(
            gesture = GestureSettings(
                autoSpaceAfterGlide = true, autoSpaceBeforeGlide = true, twoThumbGlide = true,
            ),
        )
        val applied = base.applyMode(email.copy(autoSpace = false, twoThumb = false))
        assertFalse(applied.gesture.autoSpaceAfterGlide)
        assertFalse(applied.gesture.autoSpaceBeforeGlide)
        assertFalse(applied.gesture.twoThumbGlide)
    }

    @Test
    fun `glide and two-thumb survive the codec, and an old mode without them decodes null`() {
        val peck = email.copy(glide = false, twoThumb = false)
        assertEquals(peck, KeyboardModeCodec.decodeList(KeyboardModeCodec.encodeList(listOf(peck))).single())
        val legacy = KeyboardModeCodec.encodeList(listOf(email))
            .replace(",\"glide\":null", "").replace(",\"twoThumb\":null", "")
        assertFalse(legacy.contains("glide"))
        assertFalse(legacy.contains("twoThumb"))
        val decoded = KeyboardModeCodec.decodeList(legacy).single()
        assertNull(decoded.glide)
        assertNull(decoded.twoThumb)
        assertEquals(email, decoded)
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
