package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.AlternateEntry
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.KeyRole
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.alternateEntries
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.expandForTablet
import com.wasimaster.wmkeyboard.core.layout.opensAlternatesPopup
import com.wasimaster.wmkeyboard.core.layout.roleIn
import com.wasimaster.wmkeyboard.core.layout.tabletGridWidth
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.core.settings.LongPressLetterActions
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.EnterAction
import com.wasimaster.wmkeyboard.ime.FieldKind
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutMode
import com.wasimaster.wmkeyboard.ime.LayoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field adaptation used to find the comma and period slots by matching their
 * labels on the last row, which silently skipped any layout arranged
 * differently — an email box in such a layout kept its comma and never got its
 * @ key, with nothing to tell the user why.
 */
class CurrentLayoutTest {

    private fun setOf(spec: com.wasimaster.wmkeyboard.core.layout.LayoutSpec) = LayoutSet(
        spec.compile(LayoutLayer.LETTERS),
        spec.compile(LayoutLayer.SYMBOLS),
        spec.compile(LayoutLayer.SYMBOLS_SHIFTED),
    )

    private fun state(
        spec: com.wasimaster.wmkeyboard.core.layout.LayoutSpec = BuiltInLayouts.QWERTY,
        fieldKind: FieldKind = FieldKind.TEXT,
        settings: KeyboardSettings = KeyboardSettings(),
    ) = KeyboardUiState(
        settings = settings,
        layouts = setOf(spec),
        fieldKind = fieldKind,
    )

    private fun KeyboardLayout.keys() = rows.flatten()

    /**
     * With every adaptation off the grid is handed back as-is rather than
     * rebuilt. Globe-as-emoji, the comma/globe swap, the number row and the six
     * clipboard shortcuts all ship *on*, so every one is switched off to get
     * here — the number row because it strips the digits from the top-row
     * letters' long press, which is a rewrite too.
     */
    @Test
    fun `a plain text field with no adaptations returns the layout untouched`() {
        val s = state(settings = plain())
        assertEquals(s.layouts.letters, currentLayout(s))
    }

    /**
     * The hold shortcut is an ordinary popup entry, after the accents the key
     * already had — and entry 0, the one a plain hold commits, is still the
     * accent it has always been.
     */
    @Test
    fun `a hold shortcut lands after the accents by default`() {
        val s = state(settings = plain().copy(longPressLetterActions = copyOnC()))
        val entries = cKeyOf(s).alternateEntries()
        assertTrue("the c key must still offer its accents", entries.size > 1)
        assertTrue("entry 0 stays a character", entries.first() is AlternateEntry.Character)
        assertEquals(
            KeyAction.Edit(TextEditAction.COPY),
            (entries.last() as AlternateEntry.Action).alternate.action,
        )
    }

    /**
     * `actionFirst` is the trade the other way: the action moves to entry 0, so
     * a plain hold-and-release copies, and the accents move one along rather
     * than being taken away.
     */
    @Test
    fun `actionFirst puts the hold shortcut at entry zero`() {
        val actions = copyOnC().copy(actionFirst = true)
        val s = state(settings = plain().copy(longPressLetterActions = actions))
        val entries = cKeyOf(s).alternateEntries()
        assertEquals(
            KeyAction.Edit(TextEditAction.COPY),
            (entries.first() as AlternateEntry.Action).alternate.action,
        )
        // The accents are still reachable, and in their own order.
        val characters = entries.drop(1).map { (it as AlternateEntry.Character).text }
        assertEquals(cKeyOf(s).longPress, characters)
    }

    /** Copy on `c`, every other hold shortcut off. */
    private fun copyOnC() = LongPressLetterActions(
        selectAll = false, copy = true, paste = false,
        cut = false, undo = false, redo = false,
    )

    private fun cKeyOf(s: KeyboardUiState): Key =
        currentLayout(s).keys().single { (it.output ?: it.label) == "c" }

    /** Settings with every default-on layout rewrite turned off. */
    private fun plain(): KeyboardSettings = KeyboardSettings(
        globeAsEmoji = false,
        swapCommaAndGlobe = false,
        numberRow = false,
        longPressLetterActions = LongPressLetterActions(
            selectAll = false, copy = false, paste = false,
            cut = false, undo = false, redo = false,
        ),
    )

    private fun enterKeyOf(s: KeyboardUiState): Key =
        currentLayout(s).keys().single { it.action == KeyAction.Enter }

    /**
     * A field declaring Send takes the enter key over, so the line break it
     * displaces moves to the key's long press — the only way to put one in a
     * chat message without an override.
     */
    @Test
    fun `a send field puts a newline on the enter key's long press`() {
        val s = state(settings = plain()).copy(enterAction = EnterAction.SEND)
        val alternates = enterKeyOf(s).actionAlternates
        assertEquals(1, alternates.size)
        assertEquals(KeyAction.Newline, alternates.single().action)
    }

    /** Enter already types the break here; offering it again is noise. */
    @Test
    fun `an ordinary text field leaves the enter key alone`() {
        val s = state(settings = plain()).copy(enterAction = EnterAction.DEFAULT)
        assertTrue(enterKeyOf(s).actionAlternates.isEmpty())
    }

    /**
     * The alternate is what the popup shows, and the popup only opens on a key
     * that reports it can — the same predicate the pointer handler reads.
     */
    @Test
    fun `the enter key opens its popup once it carries the alternate`() {
        val s = state(settings = plain()).copy(enterAction = EnterAction.SEARCH)
        assertTrue(enterKeyOf(s).opensAlternatesPopup())
        // And draws no corner hint: that comes from the character alternates,
        // which this key still has none of.
        assertTrue(enterKeyOf(s).longPress.isEmpty())
    }

    private fun spaceKeyOf(s: KeyboardUiState): Key =
        currentLayout(s).keys().single { it.action == KeyAction.Space }

    /**
     * Issue #57: the spacebar's long press is a setting, for a user who switches
     * language some other way and wants their own keys under the hold.
     */
    @Test
    fun `the spacebar takes the user's hold keys`() {
        val s = state(
            settings = plain().copy(
                layoutBehavior = plain().layoutBehavior.copy(spaceHoldKeys = listOf("\uD83D\uDE42", "\u2764\uFE0F")),
            ),
        )
        val space = spaceKeyOf(s)
        assertEquals(listOf("\uD83D\uDE42", "\u2764\uFE0F"), space.longPress)
        assertTrue("the hold has to actually open the popup", space.opensAlternatesPopup())
    }

    /** Every layer, or a hold that works on the letters dies in the symbols. */
    @Test
    fun `the hold keys reach the symbols layer too`() {
        val s = state(
            settings = plain().copy(
                layoutBehavior = plain().layoutBehavior.copy(spaceHoldKeys = listOf("\uD83D\uDE42")),
            ),
        ).copy(layoutMode = LayoutMode.SYMBOLS)
        assertEquals(listOf("\uD83D\uDE42"), spaceKeyOf(s).longPress)
    }

    /** Nobody who has not asked for it loses the space repeat or the picker. */
    @Test
    fun `an unset spacebar keeps its hold`() {
        val space = spaceKeyOf(state(settings = plain()))
        assertTrue(space.longPress.isEmpty())
        assertTrue(!space.opensAlternatesPopup())
    }

    /** The same layout set, widened the way the service widens it on a tablet. */
    private fun tabletState(
        settings: KeyboardSettings = KeyboardSettings(),
        fieldKind: FieldKind = FieldKind.TEXT,
    ): KeyboardUiState {
        val spec = BuiltInLayouts.QWERTY
        val letters = spec.compile(LayoutLayer.LETTERS)
        return KeyboardUiState(
            settings = settings,
            fieldKind = fieldKind,
            layouts = LayoutSet(
                letters = letters.expandForTablet(DeviceForm.LARGE_TABLET, numberRowShown = true),
                symbols = spec.compile(LayoutLayer.SYMBOLS),
                symbolsShifted = spec.compile(LayoutLayer.SYMBOLS_SHIFTED),
                gridWidth = tabletGridWidth(letters, DeviceForm.LARGE_TABLET),
            ),
        )
    }

    /**
     * `commaAsEmoji` and `globeAsEmoji` exist because a phone's bottom row has no
     * spare slot, so an existing key has to give one up. The tablet grid has a
     * real emoji key already; applying either here would draw a second one, and
     * `globeAsEmoji` — which ships **on** — would take language switching off the
     * board entirely to do it.
     */
    @Test
    fun `a tablet grid has exactly one emoji key however the preferences are set`() {
        for (comma in listOf(true, false)) {
            for (globe in listOf(true, false)) {
                val s = tabletState(
                    KeyboardSettings(commaAsEmoji = comma, globeAsEmoji = globe),
                )
                val keys = currentLayout(s).keys()
                assertEquals(
                    "commaAsEmoji=$comma globeAsEmoji=$globe",
                    1,
                    keys.count { it.action == KeyAction.Emoji },
                )
                assertEquals(
                    "language switching must survive: commaAsEmoji=$comma globeAsEmoji=$globe",
                    1,
                    keys.count { it.action == KeyAction.LanguageSwitch },
                )
            }
        }
    }

    /**
     * The relocated comma carries an explicit [KeyRole.Comma] rather than
     * relying on `roleIn`'s positional fallback, which only fires on the bottom
     * row. Without the stamp an email field on a tablet would keep its comma and
     * never get its @ key — the exact failure this whole test class exists for.
     */
    @Test
    fun `an email field on a tablet still gets its at key`() {
        val keys = currentLayout(tabletState(fieldKind = FieldKind.EMAIL)).keys()
        assertTrue("no @ key on the tablet grid", keys.any { it.label == "@" })
        assertTrue("the comma survived as itself", keys.none { it.label == "," })
    }

    /**
     * The swap trades the two keys either side of the spacebar, which is what
     * puts the emoji key (whichever of the two it is) in the outer slot.
     */
    @Test
    fun `the comma and globe keys trade places`() {
        val plain = currentLayout(state(settings = plain())).rows.last()
        val swapped = currentLayout(
            state(settings = KeyboardSettings(globeAsEmoji = false, swapCommaAndGlobe = true)),
        ).rows.last()
        val comma = plain.indexOfFirst { it.role == KeyRole.Comma }
        val globe = plain.indexOfFirst { it.action == KeyAction.LanguageSwitch }
        assertTrue(comma >= 0 && globe >= 0)
        assertEquals(plain[globe], swapped[comma])
        assertEquals(plain[comma], swapped[globe])
        // Everything else stays where it was.
        assertEquals(plain.size, swapped.size)
        for (i in plain.indices) {
            if (i != comma && i != globe) assertEquals(plain[i], swapped[i])
        }
    }

    /** The emoji key follows its slot: swapped, it sits where the globe was. */
    @Test
    fun `the swap carries the emoji key with it`() {
        val row = currentLayout(
            state(settings = KeyboardSettings(globeAsEmoji = true, swapCommaAndGlobe = true)),
        ).rows.last()
        val emoji = row.indexOfFirst { it.action == KeyAction.Emoji }
        val comma = row.indexOfFirst { it.role == KeyRole.Comma }
        assertTrue("no emoji key in $row", emoji >= 0)
        assertTrue("emoji key should lead the comma", emoji < comma)
    }

    /**
     * The six shortcuts ship on, and as popup entries: the key keeps its accents
     * and its popup, and the action is appended after them. Nothing takes the
     * hold outright any more — a [Key.clipboardAction] would, and that is now
     * only ever written into a layout by hand.
     */
    @Test
    fun `the default clipboard shortcuts ride in the popup`() {
        val layout = currentLayout(state())
        val c = layout.keys().first { it.label == "c" }
        assertEquals(
            listOf(KeyAlternate(KeyAction.Edit(TextEditAction.COPY))),
            c.actionAlternates,
        )
        assertNull("the accents must survive it", c.clipboardAction)
        assertTrue("the key's own accents come first", c.longPress.isNotEmpty())
        assertTrue(c.opensAlternatesPopup())
    }

    /** Each toggle lands its action on the matching key, including Z/Y. */
    @Test
    fun `enabled clipboard shortcuts land on a c v x z y`() {
        val layout = currentLayout(state())
        fun actionsOn(label: String) =
            layout.keys().first { it.label == label }.actionAlternates.map { it.action }
        assertEquals(listOf(KeyAction.Edit(TextEditAction.SELECT_ALL)), actionsOn("a"))
        assertEquals(listOf(KeyAction.Edit(TextEditAction.COPY)), actionsOn("c"))
        assertEquals(listOf(KeyAction.Edit(TextEditAction.PASTE)), actionsOn("v"))
        assertEquals(listOf(KeyAction.Edit(TextEditAction.CUT)), actionsOn("x"))
        assertEquals(listOf(KeyAction.Tool(ToolbarTool.UNDO)), actionsOn("z"))
        assertEquals(listOf(KeyAction.Tool(ToolbarTool.REDO)), actionsOn("y"))
        assertEquals(emptyList<KeyAction>(), actionsOn("q"))
    }

    /** Turning one off takes only its own entry away. */
    @Test
    fun `a switched-off clipboard shortcut leaves its key alone`() {
        val layout = currentLayout(
            state(
                settings = KeyboardSettings(
                    longPressLetterActions = LongPressLetterActions(copy = false),
                ),
            ),
        )
        assertEquals(emptyList<KeyAlternate>(), layout.keys().first { it.label == "c" }.actionAlternates)
        assertTrue(layout.keys().first { it.label == "x" }.actionAlternates.isNotEmpty())
    }

    @Test
    fun `an email field trades the comma for an at sign`() {
        val layout = currentLayout(state(fieldKind = FieldKind.EMAIL))
        assertTrue("@ should be on the grid", layout.keys().any { it.label == "@" })
        assertTrue(
            "the bottom-row comma should be gone",
            layout.rows.last().none { it.label == "," && it.action == KeyAction.Text },
        )
    }

    @Test
    fun `a bengali layout types dari from the period key`() {
        val s = state(BuiltInLayouts.AVRO, settings = plain())
            .copy(script = ScriptRegistry[ScriptId.BENGALI])
        val period = currentLayout(s).rows.last().first { it.role == KeyRole.Period }
        assertEquals("।", period.output ?: period.label)
        assertEquals(".", period.longPress.first())
        assertTrue(
            "the mark the key now types must not also sit in its own popup",
            period.longPress.none { it == "।" },
        )
    }

    /**
     * বিসর্গ is drawn as a colon and does a different job, and the symbol layers
     * are one grid shared by every language, so the colon key's popup is the
     * only place it can live.
     */
    @Test
    fun `a bengali layout hangs bisarga on the colon key`() {
        val s = state(BuiltInLayouts.AVRO, settings = plain())
            .copy(script = ScriptRegistry[ScriptId.BENGALI], layoutMode = LayoutMode.SYMBOLS)
        val colon = currentLayout(s).keys().first { (it.output ?: it.label) == ":" }
        assertEquals(listOf("ঃ"), colon.longPress)
    }

    /** Devanagari's visarga rides the same key, for the same reason. */
    @Test
    fun `a devanagari layout hangs visarga on the colon key`() {
        val s = state(settings = plain())
            .copy(script = ScriptRegistry[ScriptId.DEVANAGARI], layoutMode = LayoutMode.SYMBOLS)
        val colon = currentLayout(s).keys().first { (it.output ?: it.label) == ":" }
        assertEquals(listOf("ः"), colon.longPress)
    }

    /** The same key on a Latin layout is untouched, popup and all. */
    @Test
    fun `a latin layout leaves the colon key alone`() {
        val s = state(settings = plain()).copy(layoutMode = LayoutMode.SYMBOLS)
        val colon = currentLayout(s).keys().first { (it.output ?: it.label) == ":" }
        assertEquals(emptyList<String>(), colon.longPress)
    }

    @Test
    fun `a latin layout keeps its full stop`() {
        val period = currentLayout(state(settings = plain()))
            .rows.last().first { it.role == KeyRole.Period }
        assertEquals(".", period.output ?: period.label)
    }

    @Test
    fun `a uri field puts domain endings on the period key`() {
        val layout = currentLayout(state(fieldKind = FieldKind.URI))
        val period = layout.rows.last().first { it.role == KeyRole.Period }
        assertTrue(period.longPress.contains("https://"))
    }

    /**
     * The regression the roles exist to prevent. This layout's bottom row has no
     * key labelled "," at all — the slot is spelled differently — so the old
     * label match would have found nothing and the email box would have gone
     * without its @ key.
     */
    @Test
    fun `a custom bottom row gets field adaptation through its role tag`() {
        val custom = com.wasimaster.wmkeyboard.core.layout.LayoutSpec(
            id = "custom_1",
            name = "Mine",
            layers = mapOf(
                LayoutLayer.LETTERS.key to com.wasimaster.wmkeyboard.core.layout.LayerSpec(
                    listOf(
                        listOf(Key("a"), Key("b")),
                        listOf(
                            Key("؛", role = KeyRole.Comma),
                            Key(" ", action = KeyAction.Space),
                            Key("۔", role = KeyRole.Period),
                            Key("⏎", action = KeyAction.Enter),
                        ),
                    ),
                ),
            ),
        )
        val layout = currentLayout(state(custom, fieldKind = FieldKind.EMAIL))
        assertTrue(
            "the tagged comma slot should have become the @ key",
            layout.keys().any { it.label == "@" },
        )
    }

    @Test
    fun `an untagged bottom row still adapts through the label fallback`() {
        // A layout written before roles existed: no tags anywhere.
        val legacy = com.wasimaster.wmkeyboard.core.layout.LayoutSpec(
            id = "custom_legacy",
            name = "Legacy",
            layers = mapOf(
                LayoutLayer.LETTERS.key to com.wasimaster.wmkeyboard.core.layout.LayerSpec(
                    listOf(
                        listOf(Key("a"), Key("b")),
                        listOf(Key(","), Key(" ", action = KeyAction.Space), Key(".")),
                    ),
                ),
            ),
        )
        val layout = currentLayout(state(legacy, fieldKind = FieldKind.EMAIL))
        assertTrue(layout.keys().any { it.label == "@" })
    }

    @Test
    fun `dvorak's top-row punctuation is never mistaken for the bottom-row slots`() {
        val layout = currentLayout(state(BuiltInLayouts.DVORAK, fieldKind = FieldKind.EMAIL))
        val topRow = layout.rows.first()
        assertEquals("Dvorak types ' , . from its top row", "'", topRow[0].label)
        assertEquals(",", topRow[1].label)
        assertEquals(".", topRow[2].label)
        assertNull("a top-row comma has no role", topRow[1].roleIn(0, layout.rows.lastIndex))
    }

    @Test
    fun `the clipboard shortcut follows what a key types not what it shows`() {
        val shouting = com.wasimaster.wmkeyboard.core.layout.LayoutSpec(
            id = "custom_shout",
            name = "Shouty",
            layers = mapOf(
                LayoutLayer.LETTERS.key to com.wasimaster.wmkeyboard.core.layout.LayerSpec(
                    listOf(listOf(Key("A", output = "a"), Key("C", output = "c"))),
                ),
            ),
        )
        val layout = currentLayout(
            state(
                shouting,
                settings = KeyboardSettings(
                    longPressLetterActions = LongPressLetterActions(selectAll = true),
                ),
            ),
        )
        assertEquals(
            "a key labelled A that outputs a should still get select-all",
            listOf(KeyAlternate(KeyAction.Edit(TextEditAction.SELECT_ALL))),
            layout.keys().first { it.label == "A" }.actionAlternates,
        )
    }

    @Test
    fun `comma-as-emoji keeps the key's own width`() {
        val wide = com.wasimaster.wmkeyboard.core.layout.LayoutSpec(
            id = "custom_wide",
            name = "Wide",
            layers = mapOf(
                LayoutLayer.LETTERS.key to com.wasimaster.wmkeyboard.core.layout.LayerSpec(
                    listOf(
                        listOf(Key("a")),
                        listOf(Key(",", role = KeyRole.Comma, width = 2.5f)),
                    ),
                ),
            ),
        )
        val layout = currentLayout(state(wide, settings = KeyboardSettings(commaAsEmoji = true)))
        val emoji = layout.keys().first { it.action == KeyAction.Emoji }
        assertEquals("rebuilding the key from scratch used to reset its width", 2.5f, emoji.width, 0.001f)
        assertTrue("comma moves to the long-press alternates", emoji.longPress.first() == ",")
    }

    /** The same trap on the field-adaptation path: the @ key inherits the slot. */
    @Test
    fun `the field key keeps the slot's own width`() {
        val wide = com.wasimaster.wmkeyboard.core.layout.LayoutSpec(
            id = "custom_wide_field",
            name = "Wide",
            layers = mapOf(
                LayoutLayer.LETTERS.key to com.wasimaster.wmkeyboard.core.layout.LayerSpec(
                    listOf(
                        listOf(Key("a")),
                        listOf(Key(",", role = KeyRole.Comma, width = 2.5f, labelScale = 1.4f)),
                    ),
                ),
            ),
        )
        val at = currentLayout(state(wide, fieldKind = FieldKind.EMAIL))
            .keys().first { it.label == "@" }
        assertEquals("a fresh Key used to reset the width", 2.5f, at.width, 0.001f)
        assertEquals("and the label scale with it", 1.4f, at.labelScale!!, 0.001f)
    }

    /**
     * Issue #25: a custom layout whose comma key was retyped as shift kept the
     * [KeyRole.Comma] tag the editor no longer showed it, so a URL field turned
     * the shift key into "/" — and resized it on the way. A role belongs to the
     * text a key types; a key that does something else has none.
     */
    @Test
    fun `a tagged key that no longer types text is not a punctuation slot`() {
        val retyped = com.wasimaster.wmkeyboard.core.layout.LayoutSpec(
            id = "custom_retyped",
            name = "Retyped",
            layers = mapOf(
                LayoutLayer.LETTERS.key to com.wasimaster.wmkeyboard.core.layout.LayerSpec(
                    listOf(
                        listOf(Key("a")),
                        listOf(
                            Key("⇧", action = KeyAction.Shift, role = KeyRole.Comma, width = 1.5f),
                            Key(" ", action = KeyAction.Space),
                            Key(".", role = KeyRole.Period),
                        ),
                    ),
                ),
            ),
        )
        for (kind in listOf(FieldKind.URI, FieldKind.EMAIL)) {
            val shift = currentLayout(state(retyped, fieldKind = kind))
                .keys().first { it.action == KeyAction.Shift }
            assertEquals("the shift key was rewritten in a $kind field", "⇧", shift.label)
            assertEquals(1.5f, shift.width, 0.001f)
        }
    }

    /** ...and the same key must not be swapped around the spacebar either. */
    @Test
    fun `a tagged shift key is not swapped with the globe key`() {
        val retyped = com.wasimaster.wmkeyboard.core.layout.LayoutSpec(
            id = "custom_retyped_swap",
            name = "Retyped",
            layers = mapOf(
                LayoutLayer.LETTERS.key to com.wasimaster.wmkeyboard.core.layout.LayerSpec(
                    listOf(
                        listOf(Key("a")),
                        listOf(
                            Key("⇧", action = KeyAction.Shift, role = KeyRole.Comma),
                            Key("🌐", action = KeyAction.LanguageSwitch),
                            Key(" ", action = KeyAction.Space),
                        ),
                    ),
                ),
            ),
        )
        val row = currentLayout(
            state(
                retyped,
                settings = KeyboardSettings(globeAsEmoji = false, swapCommaAndGlobe = true),
            ),
        ).rows.last()
        assertEquals(KeyAction.Shift, row[0].action)
        assertEquals(KeyAction.LanguageSwitch, row[1].action)
    }
}
