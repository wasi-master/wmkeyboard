package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.AlternateEntry
import com.wasimaster.wmkeyboard.core.layout.BottomRowRules
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.KeyRole
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.alternateEntries
import com.wasimaster.wmkeyboard.core.layout.arrangedBy
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.expandForTablet
import com.wasimaster.wmkeyboard.core.layout.opensAlternatesPopup
import com.wasimaster.wmkeyboard.core.layout.roleIn
import com.wasimaster.wmkeyboard.core.layout.tabletGridWidth
import com.wasimaster.wmkeyboard.core.layout.withoutGlobeKey
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LongPressLetterActions
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.EnterAction
import com.wasimaster.wmkeyboard.ime.FieldKind
import com.wasimaster.wmkeyboard.ime.KeyFaces
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutMode
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.ShiftState
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
     * An `actionPosition` of 0 is the trade the other way: the action moves to
     * entry 0, so a plain hold-and-release copies, and the accents move one
     * along rather than being taken away.
     */
    @Test
    fun `actionPosition 0 puts the hold shortcut at entry zero`() {
        val actions = copyOnC().copy(actionPosition = 0)
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

    /**
     * Issue #570's middle way: one along, a plain hold still types the accent
     * the corner hint shows, and copy is the next entry.
     */
    @Test
    fun `actionPosition 1 puts the hold shortcut after the first accent`() {
        val s = state(settings = plain().copy(longPressLetterActions = copyOnC().copy(actionPosition = 1)))
        val key = cKeyOf(s)
        val entries = key.alternateEntries()
        assertEquals(key.longPress.first(), (entries[0] as AlternateEntry.Character).text)
        assertEquals(KeyAction.Edit(TextEditAction.COPY), (entries[1] as AlternateEntry.Action).alternate.action)
    }

    /** Copy on `c`, every other hold shortcut off. */
    private fun copyOnC() = LongPressLetterActions(
        selectAll = false, copy = true, paste = false,
        cut = false, undo = false, redo = false,
    )

    private fun cKeyOf(s: KeyboardUiState): Key =
        currentLayout(s).keys().single { (it.output ?: it.label) == "c" }

    /** Settings with every default-on layout rewrite turned off. */
    /** A one-row kana pad whose globe key doubles as 小゛゜ (issue #340). */
    private fun kanaPad(settings: KeyboardSettings, ready: Boolean): KeyboardUiState {
        val letters = KeyboardLayout(
            name = "pad",
            rows = listOf(
                listOf(
                    Key("🌐", action = KeyAction.LanguageSwitch, kanaVariantWhileComposing = true, width = 1.5f),
                    Key("か"),
                ),
            ),
        )
        return KeyboardUiState(
            settings = settings,
            layouts = LayoutSet(letters, letters, letters),
            kanaVariantReady = ready,
        )
    }

    @Test
    fun `a flagged key is itself until a kana can take a mark`() {
        val globe = currentLayout(kanaPad(plain(), ready = false)).rows[0][0]
        assertEquals(KeyAction.LanguageSwitch, globe.action)
        assertEquals("🌐", globe.label)
    }

    @Test
    fun `a flagged key becomes the kana-mark key and keeps its seat`() {
        val key = currentLayout(kanaPad(plain(), ready = true)).rows[0][0]
        assertEquals(KeyAction.KanaVariant, key.action)
        assertEquals("小゛゜", key.label)
        assertEquals(1.5f, key.width)
    }

    /**
     * The emoji preference turns the globe into the emoji key before the swap
     * runs; the swap still wins while there is a kana to change.
     */
    @Test
    fun `the swap covers a globe the emoji preference already rewrote`() {
        val settings = plain().copy(globeAsEmoji = true)
        assertEquals(KeyAction.Emoji, currentLayout(kanaPad(settings, ready = false)).rows[0][0].action)
        assertEquals(KeyAction.KanaVariant, currentLayout(kanaPad(settings, ready = true)).rows[0][0].action)
    }

    /**
     * #874: with every adaptation off the grid comes back as it is, except
     * that a mode key with no label of its own still draws its mode's name.
     */
    @Test
    fun `a mode key is named on the untouched grid too`() {
        val base = state(settings = plain())
        val letters = base.layouts.letters
        val rows = letters.rows.toMutableList()
        rows[0] = listOf(Key("", action = KeyAction.Mode("code"))) + rows[0].drop(1)
        val settings = plain().copy(
            keyboardModes = listOf(com.wasimaster.wmkeyboard.core.settings.KeyboardMode(id = "code", name = "Code")),
        )
        val s = base.copy(settings = settings, layouts = base.layouts.copy(letters = letters.copy(rows = rows)))
        assertEquals("Code", currentLayout(s).rows[0][0].label)
    }

    private fun plain(): KeyboardSettings = KeyboardSettings(
        globeAsEmoji = false,
        swapCommaAndGlobe = false,
        numberRow = false,
        longPressLetterActions = LongPressLetterActions(
            selectAll = false, copy = false, paste = false,
            cut = false, undo = false, redo = false,
        ),
    )

    /**
     * Issue #108: the capital of every letter joins its popup, and joins it
     * *last* — the first entry is what a hold-and-release commits, and that
     * belongs to whatever the layout listed first.
     */
    @Test
    fun `shifted popup keys append each letter's capital`() {
        val base = plain()
        val s = state(
            settings = base.copy(
                layoutBehavior = base.layoutBehavior.copy(shiftedPopupKeys = true),
            ),
        )
        val a = currentLayout(s).keys().single { (it.output ?: it.label) == "a" }
        assertEquals("A", a.longPress.last())
        assertEquals(1, a.longPress.count { it == "A" })
        // Non-letters have no capital, so their popups are untouched.
        val period = currentLayout(s).keys().single { (it.output ?: it.label) == "." }
        assertTrue(period.longPress.none { it.length == 1 && it[0].isLetter() })
    }

    private fun keymanKey(label: String, vkey: Int, shifted: Boolean = false) = Key(
        label,
        action = KeyAction.KeymanKey(vkey = vkey, modifiers = if (shifted) KEYMAN_SHIFT else 0),
    )

    /**
     * A converted Keyman layout keeps its shifted keys on a page of their own,
     * with no shiftLabel on the keys under them, so the setting has to read that
     * page. The entry goes out as the shifted key's own press, so the rules type
     * it, and the popup's two lists stay paired by index.
     */
    @Test
    fun `shifted popup keys reach a keyman shift page`() {
        val letters = KeyboardLayout("ar", listOf(listOf(keymanKey("ض", 81), keymanKey("ص", 87))))
        val shift = KeyboardLayout("ar", listOf(listOf(keymanKey("َ", 81, true), keymanKey("ً", 87, true))))
        val base = plain()
        val s = KeyboardUiState(
            settings = base.copy(layoutBehavior = base.layoutBehavior.copy(shiftedPopupKeys = true)),
            layouts = LayoutSet(letters, letters, letters, keymanShift = shift),
        )
        val dad = currentLayout(s).keys().first()
        assertEquals(listOf("َ"), dad.longPress)
        val entry = dad.alternateEntries().single() as AlternateEntry.Action
        val press = entry.alternate.action as KeyAction.KeymanKey
        assertEquals(81, press.vkey)
        assertEquals(KEYMAN_SHIFT, press.modifiers)

        // Off, the popup is the layout's own.
        val off = s.copy(settings = base)
        assertTrue(currentLayout(off).keys().first().longPress.isEmpty())
    }

    /** On the shift page itself every key already is its twin. */
    @Test
    fun `the keyman shift page adds nothing to itself`() {
        val letters = KeyboardLayout("ar", listOf(listOf(keymanKey("ض", 81))))
        val shift = KeyboardLayout("ar", listOf(listOf(keymanKey("َ", 81, true))))
        val base = plain()
        val s = KeyboardUiState(
            settings = base.copy(layoutBehavior = base.layoutBehavior.copy(shiftedPopupKeys = true)),
            layouts = LayoutSet(letters, letters, letters, keymanShift = shift),
            shiftState = ShiftState.ON,
        )
        assertTrue(currentLayout(s).keys().single().longPress.isEmpty())
    }

    /** A row drawn at a different length on the shift page has no seats to pair. */
    @Test
    fun `a keyman row of another length pairs nothing`() {
        val letters = KeyboardLayout("ar", listOf(listOf(keymanKey("ض", 81), keymanKey("ص", 87))))
        val shift = KeyboardLayout("ar", listOf(listOf(keymanKey("َ", 81, true))))
        val base = plain()
        val s = KeyboardUiState(
            settings = base.copy(layoutBehavior = base.layoutBehavior.copy(shiftedPopupKeys = true)),
            layouts = LayoutSet(letters, letters, letters, keymanShift = shift),
        )
        assertTrue(currentLayout(s).keys().all { it.longPress.isEmpty() })
    }

    private fun enterKeyOf(s: KeyboardUiState): Key =
        currentLayout(s).keys().single { it.action == KeyAction.Enter }

    /**
     * A field declaring Send takes the enter key over, so the line break it
     * displaces moves to the key's long press — the only way to put one in a
     * chat message without an override.
     */
    @Test
    fun `a send field puts a newline on the enter key's long press`() {
        val s = state(settings = plain()).copy(keyFaces = KeyFaces(enterAction = EnterAction.SEND))
        val alternates = enterKeyOf(s).actionAlternates
        assertEquals(1, alternates.size)
        assertEquals(KeyAction.Newline, alternates.single().action)
    }

    /** Enter already types the break here; offering it again is noise. */
    @Test
    fun `an ordinary text field leaves the enter key alone`() {
        val s = state(settings = plain()).copy(keyFaces = KeyFaces(enterAction = EnterAction.DEFAULT))
        assertTrue(enterKeyOf(s).actionAlternates.isEmpty())
    }

    /**
     * The alternate is what the popup shows, and the popup only opens on a key
     * that reports it can — the same predicate the pointer handler reads.
     */
    @Test
    fun `the enter key opens its popup once it carries the alternate`() {
        val s = state(settings = plain()).copy(keyFaces = KeyFaces(enterAction = EnterAction.SEARCH))
        assertTrue(enterKeyOf(s).opensAlternatesPopup())
        // And draws no corner hint: that comes from the character alternates,
        // which this key still has none of.
        assertTrue(enterKeyOf(s).longPress.isEmpty())
    }

    private fun withEnterEmoji(base: KeyboardSettings): KeyboardSettings =
        base.copy(layoutBehavior = base.layoutBehavior.copy(enterLongPressEmoji = true))

    /** Off by default: a hold on enter keeps doing what it did. */
    @Test
    fun `the enter key offers no emoji until asked`() {
        val s = state(settings = plain()).copy(keyFaces = KeyFaces(enterAction = EnterAction.SEND))
        assertTrue(enterKeyOf(s).actionAlternates.none { it.action == KeyAction.Emoji })
    }

    /** On an ordinary field the emoji entry is the whole popup. */
    @Test
    fun `the setting puts emoji on the enter key's long press`() {
        val s = state(settings = withEnterEmoji(plain())).copy(keyFaces = KeyFaces(enterAction = EnterAction.DEFAULT))
        val enter = enterKeyOf(s)
        assertEquals(listOf(KeyAction.Emoji), enter.actionAlternates.map { it.action })
        assertTrue(enter.opensAlternatesPopup())
    }

    /**
     * A send field keeps its line break in the popup, but behind the emoji:
     * the first entry is what a plain hold and release commits, and that is
     * what the setting was turned on for.
     */
    @Test
    fun `emoji goes ahead of the newline on a send field`() {
        val s = state(settings = withEnterEmoji(plain())).copy(keyFaces = KeyFaces(enterAction = EnterAction.SEND))
        assertEquals(
            listOf(KeyAction.Emoji, KeyAction.Newline),
            enterKeyOf(s).actionAlternates.map { it.action },
        )
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
     * Issue #420: the keyboard and the layout editor draw the bottom row from
     * one pass, so under the shipped settings the keyboard's row is the one
     * [arrangedBy] makes of the stored grid, which is the row the editor shows.
     */
    @Test
    fun `the keyboard draws the bottom row the shared pass makes`() {
        val s = state()
        val drawn = currentLayout(s).rows.last()
        val shared = s.layouts.letters.arrangedBy(
            BottomRowRules(globeInOnePlace = true, swapCommaAndGlobe = true, globeAsEmoji = true),
        ).layout.rows.last()
        assertEquals(shared.map { it.label to it.action }, drawn.map { it.label to it.action })
        assertEquals(shared.map { it.width }, drawn.map { it.width })
    }

    /** Issue #420: a layer laid out by hand in the editor is past every bottom-row setting. */
    @Test
    fun `a layer laid out by hand is drawn as it is stored`() {
        val qwerty = BuiltInLayouts.QWERTY
        val letters = requireNotNull(qwerty.layer(LayoutLayer.LETTERS))
        val spec = qwerty.copy(
            layers = qwerty.layers + (LayoutLayer.LETTERS.key to letters.copy(bottomRowAsLaidOut = true)),
        )
        val s = state(spec = spec, settings = KeyboardSettings(showGlobeKey = false, commaAsEmoji = true))
        val stored = s.layouts.letters.rows.last()
        val drawn = currentLayout(s).rows.last()
        assertEquals(stored.map { it.label to it.action }, drawn.map { it.label to it.action })
        assertEquals(stored.map { it.width }, drawn.map { it.width })
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

    /**
     * Issue #529: a Japanese layout could not hold a literal full stop
     * anywhere, because the swap ran on every layer but the symbols pages.
     */
    @Test
    fun `only the letters page swaps the full stop for the script mark`() {
        val pad = oneRow(Key(".", role = KeyRole.Period), Key("0"))
        val s = KeyboardUiState(
            settings = plain(),
            layouts = setOf(BuiltInLayouts.QWERTY).copy(secondaries = mapOf("pad" to pad)),
            script = ScriptRegistry[ScriptId.JAPANESE],
            layoutMode = LayoutMode.SECONDARY,
            secondaryLayoutId = "pad",
        )
        val period = currentLayout(s).keys().first { it.role == KeyRole.Period }
        assertEquals(".", period.output ?: period.label)
        assertEquals("。", period.longPress.first())
    }

    /**
     * The smart punctuation key: the model's mark takes the period key's face
     * and the full stop leads its hold, on the letters page of an ordinary
     * text field only.
     */
    @Test
    fun `the smart punctuation mark takes the period key and the full stop leads its hold`() {
        val s = state(settings = plain()).copy(keyFaces = KeyFaces(smartPunctuation = "?"))
        val period = currentLayout(s).rows.last().first { it.role == KeyRole.Period }
        assertEquals("?", period.output ?: period.label)
        assertEquals(".", period.longPress.first())
        assertTrue("the mark the key now types must not also sit in its popup", period.longPress.none { it == "?" })
        // An email field's period key is spoken for by its domain endings.
        val email = state(fieldKind = FieldKind.EMAIL, settings = plain()).copy(keyFaces = KeyFaces(smartPunctuation = "?"))
        val emailPeriod = currentLayout(email).rows.last().first { it.role == KeyRole.Period }
        assertEquals(".", emailPeriod.output ?: emailPeriod.label)
        // And the symbols pages keep their full stop for numbers and files.
        val symbols = state(settings = plain()).copy(keyFaces = KeyFaces(smartPunctuation = "?"), layoutMode = LayoutMode.SYMBOLS)
        assertTrue(currentLayout(symbols).keys().none { it.role == KeyRole.Period && (it.output ?: it.label) == "?" })
    }

    /** On a danda script the model's full stop is the danda itself, so the key is left as swapped. */
    @Test
    fun `the smart punctuation mark on a bengali layout is the danda`() {
        val s = state(BuiltInLayouts.AVRO, settings = plain())
            .copy(script = ScriptRegistry[ScriptId.BENGALI], keyFaces = KeyFaces(smartPunctuation = "\u0964"))
        val period = currentLayout(s).rows.last().first { it.role == KeyRole.Period }
        assertEquals("\u0964", period.output ?: period.label)
        assertEquals(".", period.longPress.first())
        val asked = currentLayout(s.copy(keyFaces = KeyFaces(smartPunctuation = "!"))).rows.last().first { it.role == KeyRole.Period }
        assertEquals("!", asked.output ?: asked.label)
        assertEquals("\u0964", asked.longPress.first())
    }

    /** And on the letters page the swap still happens, as it has since #489. */
    @Test
    fun `a japanese letters page types the ideographic full stop`() {
        val s = state(settings = plain()).copy(script = ScriptRegistry[ScriptId.JAPANESE])
        val period = currentLayout(s).rows.last().first { it.role == KeyRole.Period }
        assertEquals("。", period.output ?: period.label)
        assertEquals(".", period.longPress.first())
    }

    /** [KeyRole.Plain] is the per-key way out of both that and field adaptation. */
    @Test
    fun `a key tagged plain keeps its full stop and takes no domain endings`() {
        val grid = oneRow(Key(".", role = KeyRole.Plain), Key(",", role = KeyRole.Plain))
        val s = KeyboardUiState(
            settings = plain(),
            layouts = setOf(BuiltInLayouts.QWERTY).copy(letters = grid),
            script = ScriptRegistry[ScriptId.JAPANESE],
            fieldKind = FieldKind.EMAIL,
        )
        val keys = currentLayout(s).keys()
        val period = keys.first { it.role == KeyRole.Plain && (it.output ?: it.label) == "." }
        assertEquals(emptyList<String>(), period.longPress)
        assertTrue("the comma slot must not become @", keys.none { it.label == "@" })
    }

    @Test
    fun `a latin layout keeps its full stop`() {
        val period = currentLayout(state(settings = plain()))
            .rows.last().first { it.role == KeyRole.Period }
        assertEquals(".", period.output ?: period.label)
    }

    /**
     * A letters grid in the shape most shipped layouts have: letters with no
     * alternates, and every question mark in the period key's popup behind
     * something else. The Persian layout of issue #408, near enough.
     */
    private fun buried(vararg periodPopup: String, letter: Key = Key("ب")) =
        com.wasimaster.wmkeyboard.core.layout.LayoutSpec(
            id = "custom_buried",
            name = "Buried",
            layers = mapOf(
                LayoutLayer.LETTERS.key to com.wasimaster.wmkeyboard.core.layout.LayerSpec(
                    listOf(
                        listOf(letter),
                        listOf(
                            Key(" ", action = KeyAction.Space),
                            Key(".", role = KeyRole.Period, longPress = periodPopup.toList()),
                        ),
                    ),
                ),
            ),
        )

    private fun periodOf(s: KeyboardUiState) =
        currentLayout(s).rows.last().first { it.role == KeyRole.Period }

    /** Issue #408: a hold on the period key types ؟ on Persian. */
    @Test
    fun `an arabic-script grid leads the period popup with its own question mark`() {
        val s = state(buried("…", "؟", "،", "?", "!"), settings = plain())
            .copy(script = ScriptRegistry[ScriptId.ARABIC])
        assertEquals(listOf("؟", "…", "،", "?", "!"), periodOf(s).longPress.take(5))
    }

    /** Issue #606: the harakat close the period key's hold, after the layout's own. */
    @Test
    fun `an arabic-script grid ends the period popup with the harakat`() {
        val s = state(buried("…", "\u064E", "!"), settings = plain())
            .copy(script = ScriptRegistry[ScriptId.ARABIC])
        val popup = periodOf(s).longPress
        // ؟ leads, lifted by issue #408 since nothing else on this grid types
        // it, so the plain hold still asks a question.
        assertEquals(listOf("؟", "…", "\u064E", "!"), popup.take(4))
        assertEquals(ScriptRegistry[ScriptId.ARABIC].periodAlternates - "\u064E", popup.drop(4))
    }

    /** Issue #972: Sorani is Arabic script but never written with the harakat. */
    @Test
    fun `sorani keeps the harakat off the period popup`() {
        val s = state(buried("…", "!"), settings = plain()).copy(
            script = ScriptRegistry[ScriptId.ARABIC],
            language = LanguageRegistry.byId("ckb"),
        )
        assertEquals(listOf("؟", "…", "!"), periodOf(s).longPress)
    }

    /** Only the letters page: the symbols pages' "." is for numbers. */
    @Test
    fun `the harakat stay off the symbols pages`() {
        val s = state(settings = plain())
            .copy(script = ScriptRegistry[ScriptId.ARABIC], layoutMode = LayoutMode.SYMBOLS)
        assertTrue(currentLayout(s).keys().none { "\u064E" in it.longPress })
    }

    /** Issue #605: the symbols page's ? key types ؟, with ? one hold away. */
    @Test
    fun `an arabic-script symbols page types its own question mark`() {
        val s = state(settings = plain())
            .copy(script = ScriptRegistry[ScriptId.ARABIC], layoutMode = LayoutMode.SYMBOLS)
        val keys = currentLayout(s).keys()
        assertTrue("no key may still type ?", keys.none { (it.output ?: it.label) == "?" })
        val asked = keys.first { (it.output ?: it.label) == "؟" }
        assertEquals("?", asked.longPress.first())
    }

    /** A letters key that typed ? counts as ؟ to hand, so nothing is lifted. */
    @Test
    fun `a swapped question key keeps the period popup as laid out`() {
        val s = state(buried("…", "!", letter = Key("?")), settings = plain())
            .copy(script = ScriptRegistry[ScriptId.ARABIC])
        assertEquals("؟", currentLayout(s).rows.first().first().label)
        assertEquals(listOf("…", "!"), periodOf(s).longPress.take(2))
    }

    /** A script that asks with ? leaves the key alone. */
    @Test
    fun `a latin symbols page keeps its question mark`() {
        val s = state(settings = plain()).copy(layoutMode = LayoutMode.SYMBOLS)
        assertTrue(currentLayout(s).keys().any { (it.output ?: it.label) == "?" })
    }

    @Test
    fun `a lone combining mark draws on a dotted circle`() {
        assertEquals("\u25CC\u064E", visibleAlternate("\u064E"))
        assertEquals("ب\u064E", visibleAlternate("ب\u064E"))
        assertEquals("ZWNJ", visibleAlternate("\u200C"))
    }

    /** The same fix for every other language whose layout buried "?" there. */
    @Test
    fun `a grid with its question mark buried lifts it to the front`() {
        val s = state(buried("…", ",", "?", "!"), settings = plain())
        assertEquals(listOf("?", "…", ",", "!"), periodOf(s).longPress)
    }

    /** QWERTY's hold on m already types "?", so its period key is left alone. */
    @Test
    fun `a grid with a question mark to hand keeps its period popup`() {
        val s = state(buried("…", "?", letter = Key("m", longPress = listOf("?"))), settings = plain())
        assertEquals(listOf("…", "?"), periodOf(s).longPress)
        val qwerty = periodOf(state(settings = plain()))
        assertEquals("…", qwerty.longPress.first())
    }

    /** A grid that never offered "?" (a kana pad, Morse) does not grow one. */
    @Test
    fun `a grid with no question mark at all is not given one`() {
        val s = state(buried("…", "!"), settings = plain())
        assertEquals(listOf("…", "!"), periodOf(s).longPress)
    }

    /** The question mark leads, the "." the danda displaced follows it. */
    @Test
    fun `a danda grid puts the question mark ahead of the displaced full stop`() {
        val s = state(buried("…", "?"), settings = plain())
            .copy(script = ScriptRegistry[ScriptId.BENGALI])
        val period = periodOf(s)
        assertEquals("।", period.label)
        assertEquals(listOf("?", ".", "…"), period.longPress)
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

    /**
     * Issue #139: a hidden 🌐 key leaves the bottom row, and the spacebar takes
     * its width, so the row is exactly as long as it was.
     */
    @Test
    fun `a hidden globe key gives its width to the spacebar`() {
        val shown = currentLayout(state(settings = plain())).rows.last()
        val hidden = currentLayout(state(settings = plain().copy(showGlobeKey = false))).rows.last()
        val globe = shown.single { it.action == KeyAction.LanguageSwitch }
        val space = shown.single { it.action == KeyAction.Space }
        assertTrue("the globe key is still there: $hidden", hidden.none { it.action == KeyAction.LanguageSwitch })
        assertEquals(shown.size - 1, hidden.size)
        assertEquals(space.width + globe.width, hidden.single { it.action == KeyAction.Space }.width, 0.001f)
        assertEquals(shown.sumOf { it.width.toDouble() }, hidden.sumOf { it.width.toDouble() }, 0.001)
    }

    /**
     * The shipped defaults turn the 🌐 key into an emoji key and swap it with
     * the comma. Hidden, neither has a key to act on: no emoji key is left in
     * the slot, and the row reads `?123 , space . enter`.
     */
    @Test
    fun `a hidden globe key takes the emoji key and the swap with it`() {
        val row = currentLayout(state(settings = KeyboardSettings(showGlobeKey = false))).rows.last()
        assertEquals(
            listOf(KeyAction.Symbols, KeyAction.Text, KeyAction.Space, KeyAction.Text, KeyAction.Enter),
            row.map { it.action },
        )
        assertEquals(KeyRole.Comma, row[1].role)
    }

    /** Both symbol layers too, so the row does not change shape on the way into ?123. */
    @Test
    fun `the symbol layers lose the globe key too`() {
        for (mode in listOf(LayoutMode.SYMBOLS, LayoutMode.SYMBOLS_SHIFTED)) {
            val shown = state(settings = plain()).copy(layoutMode = mode)
            assertEquals("$mode", 1, currentLayout(shown).keys().count { it.action == KeyAction.LanguageSwitch })
            val hidden = state(settings = plain().copy(showGlobeKey = false)).copy(layoutMode = mode)
            assertEquals("$mode", 0, currentLayout(hidden).keys().count { it.action == KeyAction.LanguageSwitch })
        }
    }

    /** A bottom row with no spacebar shares the width out instead of leaving a gap. */
    @Test
    fun `a row without a spacebar shares the freed width`() {
        val row = oneRow(Key("a"), Key("🌐", action = KeyAction.LanguageSwitch), Key("b", width = 2f))
            .withoutGlobeKey().rows.last()
        assertEquals(listOf("a", "b"), row.map { it.label })
        assertEquals(4f / 3f, row[0].width, 0.001f)
        assertEquals(8f / 3f, row[1].width, 0.001f)
    }

    /** A row of nothing but 🌐 keys would have no keys left, so it stays. */
    @Test
    fun `a row of only globe keys is left alone`() {
        val layout = oneRow(Key("🌐", action = KeyAction.LanguageSwitch))
        assertTrue(layout.withoutGlobeKey() === layout)
    }

    /** A secondary layout is drawn as its author made it: its 🌐 key can be the way out. */
    @Test
    fun `a secondary layout keeps its globe key`() {
        val pad = oneRow(Key("x"), Key("🌐", action = KeyAction.LanguageSwitch))
        val s = KeyboardUiState(
            settings = plain().copy(showGlobeKey = false),
            layouts = setOf(BuiltInLayouts.QWERTY).copy(secondaries = mapOf("custom_pad" to pad)),
            layoutMode = LayoutMode.SECONDARY,
            secondaryLayoutId = "custom_pad",
        )
        assertEquals(1, currentLayout(s).keys().count { it.action == KeyAction.LanguageSwitch })
    }

    /** The expanded tablet row loses it as well, and keeps its length. */
    @Test
    fun `a tablet grid loses the globe key too`() {
        val shown = currentLayout(tabletState(KeyboardSettings())).rows.last()
        val hidden = currentLayout(tabletState(KeyboardSettings(showGlobeKey = false))).rows.last()
        assertEquals(0, hidden.count { it.action == KeyAction.LanguageSwitch })
        assertEquals(shown.sumOf { it.width.toDouble() }, hidden.sumOf { it.width.toDouble() }, 0.001)
    }

    private fun symbolsState(settings: KeyboardSettings): KeyboardUiState =
        state(settings = settings).copy(layoutMode = LayoutMode.SYMBOLS)

    private fun withNumpadKey(base: KeyboardSettings): KeyboardSettings =
        base.copy(layoutBehavior = base.layoutBehavior.copy(symbolsNumpadKey = true))

    /** Issue #423: off by default, so the symbols page keeps the row it had. */
    @Test
    fun `the symbols page has no 1234 key until asked`() {
        val s = symbolsState(plain())
        assertTrue(currentLayout(s).keys().none { it.action == KeyAction.Numpad })
    }

    /**
     * Turned on, the 1234 key sits straight after ABC, on both symbols pages,
     * and the spacebar gives up the width so the row stays the same length.
     */
    @Test
    fun `the setting puts a 1234 key after ABC on the symbols pages`() {
        for (mode in listOf(LayoutMode.SYMBOLS, LayoutMode.SYMBOLS_SHIFTED)) {
            val before = currentLayout(symbolsState(plain()).copy(layoutMode = mode)).rows.last()
            val after = currentLayout(symbolsState(withNumpadKey(plain())).copy(layoutMode = mode)).rows.last()
            val letters = after.indexOfFirst { it.action == KeyAction.Letters }
            assertEquals(KeyAction.Numpad, after[letters + 1].action)
            assertEquals(before.sumOf { it.width.toDouble() }, after.sumOf { it.width.toDouble() }, 0.001)
        }
    }

    /** The letters page is left alone: the key belongs with the symbols. */
    @Test
    fun `the 1234 key stays off the letters page`() {
        val s = state(settings = withNumpadKey(plain()))
        assertTrue(currentLayout(s).keys().none { it.action == KeyAction.Numpad })
    }

    private fun withSymbolsAlternates(s: KeyboardUiState): KeyboardUiState {
        val letters = s.layouts.letters
        val rows = letters.rows.map { row ->
            row.map { if (it.action == KeyAction.Symbols) it.copy(longPress = listOf("½")) else it }
        }
        return s.copy(layouts = s.layouts.copy(letters = letters.copy(rows = rows)))
    }

    private fun symbolsKeyOf(s: KeyboardUiState): Key =
        currentLayout(s).keys().single { it.action == KeyAction.Symbols }

    /**
     * Issue #423: a ?123 key with alternates of its own opens them on a hold,
     * so the number pad joins that popup rather than being out of reach. The
     * layout's own entry stays first.
     */
    @Test
    fun `a symbols key with alternates offers the number pad in its popup`() {
        val key = symbolsKeyOf(withSymbolsAlternates(state(settings = plain())))
        assertEquals(listOf(KeyAction.Numpad), key.actionAlternates.map { it.action })
        assertEquals(AlternateEntry.Character("½"), key.alternateEntries().first())
    }

    /** Rides the hold setting: off, the popup is the layout's alone. */
    @Test
    fun `the popup entry follows the hold setting`() {
        val off = plain().let { it.copy(layoutBehavior = it.layoutBehavior.copy(symbolsLongPressNumpad = false)) }
        val key = symbolsKeyOf(withSymbolsAlternates(state(settings = off)))
        assertTrue(key.actionAlternates.isEmpty())
    }

    /** A plain ?123 key opens the pad on the hold itself and gets no popup. */
    @Test
    fun `a plain symbols key gets no popup entry`() {
        assertTrue(symbolsKeyOf(state(settings = plain())).actionAlternates.isEmpty())
    }

    private fun oneRow(vararg keys: Key): KeyboardLayout = com.wasimaster.wmkeyboard.core.layout.LayoutSpec(
        id = "custom_one_row",
        name = "One row",
        layers = mapOf(
            LayoutLayer.LETTERS.key to com.wasimaster.wmkeyboard.core.layout.LayerSpec(listOf(keys.toList())),
        ),
    ).compile(LayoutLayer.LETTERS)
}

/** Keyman's shift bit, as a converted shift page carries it on each key. */
private const val KEYMAN_SHIFT = 16
