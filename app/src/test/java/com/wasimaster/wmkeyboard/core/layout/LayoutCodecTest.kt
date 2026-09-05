package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutCodecTest {

    private fun spec(vararg rows: List<Key>) = LayoutSpec(
        id = "custom_1",
        name = "Test",
        langId = "en",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(rows.toList())),
    )

    @Test
    fun `round trips a layout`() {
        val original = spec(
            listOf(
                Key("a", longPress = listOf("@", "à")),
                Key("⇧", action = KeyAction.Shift, width = 1.5f),
                Key(".", role = KeyRole.Period),
                Key("x", clipboardAction = ClipboardKeyAction.CUT),
            ),
        )
        assertEquals(original, LayoutCodec.decode(LayoutCodec.encode(original)))
    }

    /** Issue #62: a key that opens a secondary layout, and the two new flags, survive the file. */
    @Test
    fun `round trips an open-layout key and the secondary and persistent flags`() {
        val original = spec(listOf(Key("Pad", action = KeyAction.Layout("custom_pad"))))
            .let { it.copy(secondary = true, layers = it.layers.mapValues { (_, l) -> l.copy(persistent = true) }) }
        val decoded = LayoutCodec.decode(LayoutCodec.encode(original))
        assertEquals(original, decoded)
        assertTrue(decoded!!.secondary)
        assertTrue(decoded.layer(LayoutLayer.LETTERS)!!.persistent)
    }

    /** …and a file from before either flag existed is an ordinary layout whose layers spring back. */
    @Test
    fun `a layout written before secondary and persistent existed is an ordinary layout`() {
        val old = """
            {"id":"custom_old","name":"Old","langId":"en","version":2,
             "layers":{"letters":{"rows":[[{"label":"a"}]]}}}
        """.trimIndent()
        val decoded = LayoutCodec.decode(old)
        assertNotNull(decoded)
        assertFalse(decoded!!.secondary)
        assertFalse(decoded.layer(LayoutLayer.LETTERS)!!.persistent)
    }

    /**
     * A file written before `tabletExpand` existed has to mean "yes". Every one
     * of the 1,256 shipped assets and every stored custom layout is such a file, so
     * the other default would silently opt the whole corpus out of the tablet
     * grid at once, with nothing to show for it but a keyboard that never widens.
     */
    @Test
    fun `a layout written before tabletExpand existed opts in`() {
        val old = """
            {"id":"custom_old","name":"Old","langId":"en","version":2,
             "layers":{"letters":{"rows":[[{"label":"a"}]]}}}
        """.trimIndent()
        val decoded = LayoutCodec.decode(old)
        assertNotNull(decoded)
        assertTrue("an old file must default to opted in", decoded!!.tabletExpand)
    }

    /**
     * …and adding the field must not have bumped the format version, which would
     * rewrite the effective version of every asset and custom layout on read for
     * no migration.
     */
    @Test
    fun `adding tabletExpand did not bump the format version`() {
        assertEquals(2, CurrentLayoutSpecVersion)
        val old = """
            {"id":"custom_old","name":"Old","langId":"en","version":2,
             "layers":{"letters":{"rows":[[{"label":"a"}]]}}}
        """.trimIndent()
        assertEquals(2, LayoutCodec.decode(old)!!.version)
    }

    @Test
    fun `round trips an opted-out layout`() {
        val original = spec(listOf(Key("a"))).copy(tabletExpand = false)
        assertEquals(original, LayoutCodec.decode(LayoutCodec.encode(original)))
    }

    @Test
    fun `round trips a layout's own font and label sizes`() {
        val original = spec(
            listOf(Key("a"), Key("Send", labelScale = 0.7f)),
        ).copy(appearance = LayoutAppearance(fontId = "google:Roboto Mono", fontScale = 0.85f))
        assertEquals(original, LayoutCodec.decode(LayoutCodec.encode(original)))
    }

    /**
     * A file written before the layout could carry type of its own — which is
     * every asset and every stored custom layout — has to read as "says
     * nothing", not as a layout that has asked for the defaults. Only the first
     * one keeps following the theme and the settings.
     */
    @Test
    fun `a layout written before appearance existed says nothing`() {
        val old = """
            {"id":"custom_old","name":"Old","langId":"en","version":2,
             "layers":{"letters":{"rows":[[{"label":"a"}]]}}}
        """.trimIndent()
        val decoded = checkNotNull(LayoutCodec.decode(old))
        assertNull(decoded.appearance)
        assertNull(decoded.layer(LayoutLayer.LETTERS)!!.rows[0][0].labelScale)
        // And it did not bump the format version, for the reason above.
        assertEquals(2, decoded.version)
    }

    @Test
    fun `a layer's own label size overrides the layout's, per layer`() {
        // The complaint this exists for: one size for the whole layout reached
        // the symbol page too, so the two could not be set apart.
        val spec = LayoutSpec(
            id = "custom_1",
            name = "Test",
            langId = "en",
            appearance = LayoutAppearance(fontScale = 1.4f),
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a")))),
                LayoutLayer.SYMBOLS.key to LayerSpec(listOf(listOf(Key("!"))), fontScale = 0.8f),
            ),
        )
        assertEquals(1.4f, spec.compile(LayoutLayer.LETTERS).appearance.drawnFontScale(), 0f)
        assertEquals(0.8f, spec.compile(LayoutLayer.SYMBOLS).appearance.drawnFontScale(), 0f)
        // It replaces rather than multiplies. 1.4 × 0.8 would be 1.12, and the
        // point of the field is that the two layers do not move together.
        assertEquals(
            "google:Roboto Mono",
            spec.copy(appearance = LayoutAppearance("google:Roboto Mono", 1.4f))
                .compile(LayoutLayer.SYMBOLS).appearance?.fontId,
        )
    }

    @Test
    fun `a layer size works on a layout that sets nothing else`() {
        val spec = LayoutSpec(
            id = "custom_2",
            name = "Test",
            langId = "en",
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"))), fontScale = 0.6f),
            ),
        )
        assertEquals(0.6f, spec.compile(LayoutLayer.LETTERS).appearance.drawnFontScale(), 0f)
        // A layer it does not define takes the built-in grid and no size at all.
        assertEquals(1f, spec.compile(LayoutLayer.SYMBOLS).appearance.drawnFontScale(), 0f)
    }

    @Test
    fun `round trips a layer's own label size`() {
        val original = LayoutSpec(
            id = "custom_3",
            name = "Test",
            langId = "en",
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"))), fontScale = 1.3f),
            ),
        )
        assertEquals(original, LayoutCodec.decode(LayoutCodec.encode(original)))
    }

    @Test
    fun `an out-of-range size is clamped at draw time, not stored`() {
        // Both of these come off a file, so neither can be trusted; both are
        // pulled back to the range the renderer honours without the stored
        // layout being rewritten under the user.
        assertEquals(2f, LayoutAppearance(fontScale = 9f).drawnFontScale(), 0f)
        assertEquals(0.5f, LayoutAppearance(fontScale = 0f).drawnFontScale(), 0f)
        assertEquals(1f, (null as LayoutAppearance?).drawnFontScale(), 0f)
        assertEquals(1f, LayoutAppearance(fontScale = Float.NaN).drawnFontScale(), 0f)
        assertEquals(2f, checkNotNull(Key("a", labelScale = 40f).drawnLabelScale()), 0f)
        assertNull(Key("a").drawnLabelScale())
    }

    @Test
    fun `round trips flick keys and the kana-variant action`() {
        val original = spec(
            listOf(
                Key(
                    "あ",
                    flick = mapOf(
                        FlickDirection.LEFT to "い",
                        FlickDirection.UP to "う",
                        FlickDirection.RIGHT to "え",
                        FlickDirection.DOWN to "お",
                    ),
                ),
                Key("小゛゜", action = KeyAction.KanaVariant),
            ),
        )
        assertEquals(original, LayoutCodec.decode(LayoutCodec.encode(original)))
    }

    /**
     * A key that hides its corner hint keeps its alternates: the two are separate
     * fields on purpose, since clearing `longPress` is what an author does *not*
     * want here. Also pins the default, because a file written before the field
     * existed has to keep drawing its hints.
     */
    @Test
    fun `round trips a hint-hiding key without touching its alternates`() {
        val original = spec(listOf(Key("a", longPress = listOf("@", "à"), hideHint = true)))
        val decoded = LayoutCodec.decode(LayoutCodec.encode(original))
        assertEquals(original, decoded)
        val key = decoded!!.layers.getValue(LayoutLayer.LETTERS.key).rows[0][0]
        assertEquals(listOf("@", "à"), key.longPress)

        val old = """
            {"id":"custom_old","name":"Old","langId":"en","version":2,
             "layers":{"letters":{"rows":[[{"label":"a","longPress":["@"]}]]}}}
        """.trimIndent()
        val oldKey = LayoutCodec.decode(old)!!.layers.getValue("letters").rows[0][0]
        assertEquals(false, oldKey.hideHint)
        assertEquals(false, oldKey.forceHint)
    }

    /**
     * The other direction (issue #33): a key that keeps its hint while the
     * global switch is off. Separate from `hideHint` in the file, so a layout
     * written before the field existed still reads as "follow the switch".
     */
    @Test
    fun `round trips a hint-forcing key`() {
        val original = spec(listOf(Key("a", longPress = listOf("@"), forceHint = true)))
        val decoded = LayoutCodec.decode(LayoutCodec.encode(original))
        assertEquals(original, decoded)
        val key = decoded!!.layers.getValue(LayoutLayer.LETTERS.key).rows[0][0]
        assertEquals(true, key.forceHint)
        assertEquals(false, key.hideHint)
    }

    @Test
    fun `round trips a list`() {
        val list = listOf(spec(listOf(Key("a"))), spec(listOf(Key("b"))).copy(id = "custom_2"))
        assertEquals(list, LayoutCodec.decodeList(LayoutCodec.encodeList(list)))
    }

    /**
     * The load-bearing one. An action tag from a newer build has to cost the
     * user that one key, not the whole file — so this asserts the other two
     * keys survive, not merely that decoding did not throw.
     */
    @Test
    fun `an unknown action tag decodes to Unknown without losing the other keys`() {
        val json = """
            {
              "id": "custom_1",
              "name": "Test",
              "baseMode": "ENGLISH",
              "layers": {
                "letters": {
                  "rows": [[
                    {"label": "a"},
                    {"label": "z", "action": {"type": "teleport", "destination": "mars"}},
                    {"label": "e"}
                  ]]
                }
              }
            }
        """.trimIndent()

        val decoded = LayoutCodec.decode(json)
        assertNotNull("an unknown action must not fail the whole file", decoded)

        val row = decoded!!.layer(LayoutLayer.LETTERS)!!.rows.single()
        assertEquals(3, row.size)
        assertEquals(KeyAction.Text, row[0].action)
        assertEquals(KeyAction.Unknown("teleport"), row[1].action)
        assertEquals(KeyAction.Text, row[2].action)
    }

    @Test
    fun `an unknown layer key is ignored rather than fatal`() {
        val json = """
            {"id":"custom_1","name":"T","layers":{
              "letters":{"rows":[[{"label":"a"}]]},
              "hyperspace":{"rows":[[{"label":"b"}]]}
            }}
        """.trimIndent()
        val decoded = LayoutCodec.decode(json)
        assertNotNull(decoded)
        assertNotNull(decoded!!.layer(LayoutLayer.LETTERS))
        assertTrue("the foreign layer is kept verbatim, not resolvable", "hyperspace" in decoded.layers)
    }

    @Test
    fun `an unknown legacy base mode migrates to the default language`() {
        val json = """
            {"id":"custom_1","name":"T","baseMode":"KLINGON",
             "layers":{"letters":{"rows":[[{"label":"a"}]]}}}
        """.trimIndent()
        assertEquals("en", LayoutCodec.decode(json)?.langId)
    }

    /**
     * The registry migration: a layout written before langId existed stored an
     * `InputMode` name in `baseMode`. Decoding must translate it to a langId and
     * carry Avro's transliteration onto the composer, or an upgrade silently
     * turns a Bengali phonetic layout into English.
     */
    @Test
    fun `a pre-registry baseMode migrates to langId and composer`() {
        val avro = """
            {"id":"custom_1","name":"Mine","baseMode":"AVRO","version":1,
             "layers":{"letters":{"rows":[[{"label":"a"}]]}}}
        """.trimIndent()
        val decoded = LayoutCodec.decode(avro)!!
        assertEquals("bn", decoded.langId)
        assertEquals(com.wasimaster.wmkeyboard.core.script.ComposerType.TRANSLITERATE, decoded.composer)
        assertNull("the legacy field is cleared once migrated", decoded.legacyBaseMode)
        assertEquals(CurrentLayoutSpecVersion, decoded.version)

        val probhat = avro.replace("AVRO", "PROBHAT")
        val fixed = LayoutCodec.decode(probhat)!!
        assertEquals("bn", fixed.langId)
        assertNull("fixed Bengali keeps the script-default composer", fixed.composer)
    }

    /**
     * The popup entries that run an action (issue #21). A tool alternate is the
     * one that carries a payload, so it is the one worth round-tripping: the
     * tool has to survive as the tool, not as the enum's first entry.
     */
    @Test
    fun `round trips action alternates`() {
        val original = spec(
            listOf(
                Key(
                    "⏎",
                    action = KeyAction.Enter,
                    actionAlternates = listOf(
                        KeyAlternate(KeyAction.SendKey(61)),
                        KeyAlternate(KeyAction.Tool(ToolbarTool.VOICE)),
                        KeyAlternate(KeyAction.Tool(ToolbarTool.TEXT_EDIT), label = "Edit"),
                    ),
                ),
            ),
        )
        val decoded = LayoutCodec.decode(LayoutCodec.encode(original))
        assertEquals(original, decoded)
        val key = decoded!!.layers.getValue(LayoutLayer.LETTERS.key).rows[0][0]
        assertEquals(KeyAction.Tool(ToolbarTool.VOICE), key.actionAlternates[1].action)
    }

    /**
     * A tool a newer build knows and this one does not must cost the layout one
     * popup entry, not the whole file. `coerceInputValues` is what does it — the
     * same guarantee the role and clipboard-action enums already lean on.
     */
    @Test
    fun `an unknown tool coerces instead of failing the file`() {
        val json = """
            {"id":"custom_1","name":"T","langId":"en","version":2,
             "layers":{"letters":{"rows":[[
               {"label":"a"},
               {"label":"⏎","action":{"type":"enter"},
                "actionAlternates":[{"action":{"type":"tool","tool":"TELEPORT"}}]}
             ]]}}}
        """.trimIndent()
        val decoded = LayoutCodec.decode(json)
        assertNotNull("an unknown tool must not fail the whole file", decoded)
        val row = decoded!!.layers.getValue(LayoutLayer.LETTERS.key).rows[0]
        assertEquals(2, row.size)
        assertEquals(1, row[1].actionAlternates.size)
    }

    /**
     * And an action tag from a newer build inside a popup entry drops to the
     * same [KeyAction.Unknown] a key's own action does, which is what lets
     * `repair` recognise it and take the entry out.
     */
    @Test
    fun `an unknown alternate action decodes to Unknown`() {
        val json = """
            {"id":"custom_1","name":"T","langId":"en","version":2,
             "layers":{"letters":{"rows":[[
               {"label":"a","actionAlternates":[{"action":{"type":"teleport","to":"mars"}}]}
             ]]}}}
        """.trimIndent()
        val decoded = LayoutCodec.decode(json)!!
        val key = decoded.layers.getValue(LayoutLayer.LETTERS.key).rows[0][0]
        assertEquals(KeyAction.Unknown("teleport"), key.actionAlternates[0].action)
        // …and repair takes it out rather than drawing a dead popup button.
        val repaired = decoded.repair().spec.layers
            .getValue(LayoutLayer.LETTERS.key).rows[0][0]
        assertTrue("a dead alternate is dropped", repaired.actionAlternates.isEmpty())
    }

    /**
     * The rule the keyboard, the corner hint and the editor all read (issue
     * #22). Enter used to be excluded by every one of them testing for a text
     * key; the keys that stay excluded are the ones whose hold is already spent.
     */
    @Test
    fun `every key whose hold is free can open alternates`() {
        assertTrue(Key("⏎", action = KeyAction.Enter, longPress = listOf("\n")).opensAlternatesPopup())
        assertTrue(
            Key("⏎", action = KeyAction.Enter, actionAlternates = listOf(KeyAlternate(KeyAction.SendKey(61))))
                .opensAlternatesPopup(),
        )
        assertTrue(Key("a", longPress = listOf("à")).opensAlternatesPopup())

        assertFalse("nothing to show", Key("a").opensAlternatesPopup())
        // Issue #57: the spacebar's hold has defaults (the language picker, the
        // space repeat), not an owner. Keys authored onto it take it over.
        assertTrue(
            "authored keys claim the space hold",
            Key(" ", action = KeyAction.Space, longPress = listOf("🙂")).opensAlternatesPopup(),
        )
        assertFalse(
            "a bare spacebar still holds to repeat",
            Key(" ", action = KeyAction.Space).opensAlternatesPopup(),
        )
        assertFalse(
            "the hold repeats",
            Key("⌫", action = KeyAction.Delete, longPress = listOf("x")).opensAlternatesPopup(),
        )
        assertFalse(
            "the hold is a chord",
            Key("1", action = KeyAction.BrailleDot(1), longPress = listOf("x")).opensAlternatesPopup(),
        )
        assertFalse(
            "the shortcut takes the hold",
            Key("c", longPress = listOf("ç"), clipboardAction = ClipboardKeyAction.COPY)
                .opensAlternatesPopup(),
        )

        // The editor asks the other question: not "has any" but "may have".
        assertTrue(Key("⏎", action = KeyAction.Enter).canHoldAlternates())
        assertTrue(Key(" ", action = KeyAction.Space).canHoldAlternates())
    }

    @Test
    fun `malformed json decodes to null rather than throwing`() {
        assertNull(LayoutCodec.decode("{not json"))
        assertEquals(emptyList<LayoutSpec>(), LayoutCodec.decodeList("{not json"))
    }
}
