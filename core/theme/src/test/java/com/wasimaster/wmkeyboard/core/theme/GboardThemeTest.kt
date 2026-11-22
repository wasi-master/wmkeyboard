package com.wasimaster.wmkeyboard.core.theme

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Reading Gboard theme ZIPs and Rboard packs.
 *
 * The archives are built here rather than committed: the Rboard themes are
 * other people's work under no stated licence, and the adversarial cases —
 * a `../` entry, a nine-digit colour, a comment that never ends — can only be
 * written by hand anyway. A sweep over the real repository runs when
 * `RBOARD_PACKS` names a folder of downloaded packs, and skips otherwise.
 */
class GboardThemeTest {

    // ---- helpers ----

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray, name: String = "file"): GboardResult =
        GboardTheme.read(bytes.inputStream(), name)

    private fun metadata(
        name: String = "Dusk",
        light: Boolean = false,
        preferBorder: Boolean = false,
        sheets: String = "\"style_sheet_md2.css\"",
        flavors: String = """{"type":"BORDER","style_sheets":["style_sheet_md2_border.css"]}""",
    ) = GboardTheme.METADATA to """
        {"format_version":3,"id":"x","name":"$name","prefer_key_border":$preferBorder,
         "is_light_theme":$light,"style_sheets":[$sheets],"flavors":[$flavors]}
    """.trimIndent().toByteArray()

    /** A 1x1 PNG, enough to pass the image sniff. */
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )

    /** A small theme in the shape of Rboard's generated ones. */
    private val mainSheet = """
        /* Generated colours */
        @def color_set_a1 #202124FF;
        @def color_set_a2 #3C4043FF;
        @def color_set_a3 #8AB4F8;
        @def color_set_a4 #FFFFFFFF ;
        @def color_label @color_set_a4;
        @def color_state_key_pressed @color_set_a3;
        .keyboard-body-area { background_color: @color_set_a1; }
        .keyboard-header-area, .candidates-area { background_color: #00000033; }
        .label, .icon { color: @color_label; }
        .keytop { background_color: @color_set_a1; background_corner_radius: 8; background_shape: rectangle; }
        .keytop.dark { background_color: #303134; }
        .keytop.for-action-key { background_color: @color_set_a3; }
        .icon.for-action-key { color: #000; }
        .track.for-gesture { color: @color_set_a3; }
        .popup { background_color: #444444; }
        .label.for-popup-item { color: #FFFFFF; }
        .keytop { padding_ratio_left: 1; shadow_color: #00000080; elevation: 1; }
        .label { font_family: "monospace"; }
        .keytop.pill-shaped > .icon.for-action-key { color: #FF0000; }
    """.trimIndent().toByteArray()

    private val borderSheet = """
        @def color_state_key @color_set_a2;
        .keytop { background_color: @color_state_key; edge_color: #5F6368; edge_width: 1.5; }
    """.trimIndent().toByteArray()

    private fun theme(result: GboardResult): GboardConverted {
        assertTrue("expected a conversion, got $result", result is GboardResult.Converted)
        return (result as GboardResult.Converted).themes.single()
    }

    // ---- colours ----

    @Test
    fun `eight hex digits are RRGGBBAA, alpha last`() {
        assertEquals(0x33FFFFFFL, gboardColor("#FFFFFF33"))
        assertEquals(0xFF2196F3L, gboardColor("#2196F3FF"))
        assertEquals(0x00000000L, gboardColor("#00000000"))
    }

    @Test
    fun `short forms, rgba and trailing junk are read`() {
        assertEquals(0xFFFF0000L, gboardColor("#f00"))
        assertEquals(0x88FF0000L, gboardColor("#f008"))
        assertEquals(0xFF61E991L, gboardColor("#61E991FF "))
        assertEquals(0x80FF0000L, gboardColor("rgba(255, 0, 0, 0.5)"))
        assertEquals(0x00000000L, gboardColor("transparent"))
    }

    @Test
    fun `a typo in a colour reads as nothing rather than as black`() {
        assertNull(gboardColor("#FFFFFFFFF"))
        assertNull(gboardColor("#12345"))
        assertNull(gboardColor("#GGGGGG"))
        assertNull(gboardColor("mgbt_color_label"))
        assertNull(gboardColor(""))
    }

    // ---- the stylesheet language ----

    @Test
    fun `variables resolve through chains and definitions made later`() {
        val sheet = GboardCss.parse(
            """
            @def color_header @late;
            .keyboard-header-area { background_color: @color_header; }
            @def late #102030;
            """.trimIndent(),
        )
        val style = GboardStyle(listOf(sheet))
        assertEquals(0xFF102030L, style.color(setOf("keyboard-header-area"), "background_color"))
    }

    @Test
    fun `a variable loop ends instead of hanging`() {
        val style = GboardStyle(listOf(GboardCss.parse("@def a @b; @def b @a; .x { color: @a; }")))
        assertNull(style.value(setOf("x"), "color"))
    }

    @Test
    fun `the more specific rule wins and a later rule wins a tie`() {
        val style = GboardStyle(
            listOf(
                GboardCss.parse(
                    """
                    .keytop.dark { background_color: #111111; }
                    .keytop { background_color: #222222; }
                    .keytop { background_color: #333333; }
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals(0xFF111111L, style.color(setOf("keytop", "dark"), "background_color"))
        assertEquals(0xFF333333L, style.color(setOf("keytop"), "background_color"))
    }

    @Test
    fun `a pressed colour is only what a pressed rule says`() {
        val style = GboardStyle(listOf(GboardCss.parse(".keytop { background_color: #222222; }")))
        assertNull(style.color(setOf("keytop"), "background_color", "pressed"))
    }

    @Test
    fun `stray semicolons, unclosed comments and combinators do not break the sheet`() {
        val sheet = GboardCss.parse(
            """
            ;.keytop { background_color: #010203; }
            .a > .b { color: #fff; }
            .label { color: #abcdef; } /* never closed
            .icon { color: #000; }
            """.trimIndent(),
        )
        val style = GboardStyle(listOf(sheet))
        assertEquals(0xFF010203L, style.color(setOf("keytop"), "background_color"))
        assertEquals(0xFFABCDEFL, style.color(setOf("label"), "color"))
        assertNull(style.color(setOf("icon"), "color"))
        assertTrue(sheet.rules.any { it.selector == null })
    }

    // ---- one theme ----

    @Test
    fun `a theme maps its board, keys, enter key and popups`() {
        val converted = theme(
            read(zip(metadata(), "style_sheet_md2.css" to mainSheet, "style_sheet_md2_border.css" to borderSheet)),
        )
        assertEquals("Dusk", converted.name)
        val flat = converted.looks.first()
        assertFalse(flat.bordered)
        val spec = flat.converted.theme
        assertTrue(spec.dark)
        assertEquals(0xFF202124L, spec.boardBackground)
        assertEquals(0xFF202124L, spec.keyBackground)
        assertEquals(0xFFFFFFFFL, spec.keyText)
        assertEquals(0xFF303134L, spec.modifierKeyBackground)
        assertEquals(0xFF8AB4F8L, spec.enterKeyBackground)
        assertEquals(0xFF000000L, spec.enterKeyText)
        assertEquals(0xFF8AB4F8L, spec.pressedKeyBackground)
        assertEquals(0xFF8AB4F8L, spec.accent)
        assertEquals(0xFF8AB4F8L, spec.gestureTrailColor)
        assertEquals(0x33000000L, spec.suggestionBarBackground)
        assertEquals(0xFF444444L, spec.popupBackground)
        assertEquals(KeyShapeKind.ROUNDED, spec.keyShape)
        assertEquals(8, spec.keyCornerRadiusDp)
        assertEquals(1f, spec.keyElevationDp)
        assertEquals(0x80000000L, spec.keyShadowColor)
    }

    @Test
    fun `the border flavour becomes a second look with the preferred one first`() {
        val bytes = zip(
            metadata(preferBorder = true),
            "style_sheet_md2.css" to mainSheet,
            "style_sheet_md2_border.css" to borderSheet,
        )
        val converted = theme(read(bytes))
        assertEquals(listOf(true, false), converted.looks.map { it.bordered })
        val bordered = converted.looks.first().converted.theme
        assertEquals(0xFF3C4043L, bordered.keyBackground)
        assertEquals(0xFF5F6368L, bordered.keyBorderColor)
        assertEquals(1.5f, bordered.keyBorderWidthDp)
        assertNull(converted.looks[1].converted.theme.keyBorderColor)
    }

    @Test
    fun `a border flavour that changes nothing is not a second look`() {
        val bytes = zip(
            metadata(),
            "style_sheet_md2.css" to mainSheet,
            "style_sheet_md2_border.css" to ".sticker-btn-background { color: #fff; }".toByteArray(),
        )
        assertEquals(1, theme(read(bytes)).looks.size)
    }

    @Test
    fun `losses are named`() {
        val converted = theme(
            read(
                zip(
                    metadata(sheets = "\"style_sheet_md2.css\", \"style_sheet_rules.binarypb\""),
                    "style_sheet_md2.css" to (
                        String(mainSheet) + """
                        .icon.for-space-branding { image_ref: "icon_space.png"; }
                        .keyboard-body-area { background_corner_radius_top_left: 14; }
                        .tab.in-keyboard-header-area { background_image_ref: "tab.png"; }
                        """.trimIndent()
                        ).toByteArray(),
                    "icon_space.png" to png,
                    "tab.png" to png,
                ),
            ),
        )
        assertEquals(
            setOf(
                GboardUnsupported.UNREADABLE_STYLESHEET,
                GboardUnsupported.KEY_ICONS,
                GboardUnsupported.KEY_SPACING,
                GboardUnsupported.FONT,
                GboardUnsupported.PER_CORNER_RADIUS,
                GboardUnsupported.EXTRA_IMAGES,
            ),
            converted.dropped.toSet(),
        )
    }

    // ---- key glyphs ----

    @Test
    fun `the theme's own key glyphs come across`() {
        val converted = theme(
            read(
                zip(
                    metadata(flavors = ""),
                    "style_sheet_md2.css" to (
                        String(mainSheet) + """
                        .icon_key_del { image_ref: "icon_del.png"; image_height: 25; }
                        .icon_key_shift_off { image_ref: "icon_shift_off.png"; }
                        .icon_key_ime_action_send { image_ref: "icon_send.png"; }
                        """.trimIndent()
                        ).toByteArray(),
                    "icon_del.png" to png,
                    "icon_shift_off.png" to png,
                    "icon_send.png" to png,
                ),
            ),
        )
        val images = converted.looks.first().converted.images
        assertEquals(
            setOf("keyIcon:key.backspace", "keyIcon:key.shift", "keyIcon:key.enter_send"),
            images.keys.filter { it.startsWith(ASSET_KEY_ICON_PREFIX) }.toSet(),
        )
        // Nothing was lost, so nothing is claimed to have been. The dialog used
        // to say "the app cannot use the icons" of every one of these themes.
        assertFalse(GboardUnsupported.KEY_ICONS in converted.dropped)
    }

    @Test
    fun `a glyph named without its extension still resolves`() {
        // `image_ref: "icon_emoticon"` is how Gboard names its own drawables,
        // and the file in the archive carries the extension.
        val converted = theme(
            read(
                zip(
                    metadata(flavors = ""),
                    "style_sheet_md2.css" to (
                        String(mainSheet) +
                            "\n.icon_key_main_category_smiley_dark_theme { image_ref: \"icon_emoticon\"; }"
                        ).toByteArray(),
                    "icon_emoticon.png" to png,
                ),
            ),
        )
        val images = converted.looks.first().converted.images
        assertTrue("keyIcon:key.emoji" in images)
    }

    @Test
    fun `a glyph this keyboard has no key for is still reported`() {
        val converted = theme(
            read(
                zip(
                    metadata(flavors = ""),
                    "style_sheet_md2.css" to (
                        String(mainSheet) + "\n.icon_g_icon { image_ref: \"g.png\"; }"
                        ).toByteArray(),
                    "g.png" to png,
                ),
            ),
        )
        assertTrue(GboardUnsupported.KEY_ICONS in converted.dropped)
    }

    @Test
    fun `an element's alpha fades the colour it draws`() {
        val converted = theme(
            read(
                zip(
                    metadata(flavors = ""),
                    "style_sheet_md2.css" to (
                        String(mainSheet) +
                            "\n.label.for-function-key { color: #FFFFFF; alpha: 0.5; }"
                        ).toByteArray(),
                ),
            ),
        )
        // Half of 0xFF is 0x80, and reading the alpha is the difference between
        // a faded modifier glyph and a glaring one.
        assertEquals(0x80FFFFFFL, converted.looks.first().converted.theme.modifierKeyText)
    }

    @Test
    fun `the long spelling of the enter glyph's colour is read`() {
        val sheet = String(mainSheet)
            .replace(".icon.for-action-key { color: #000; }", "") +
            "\n.icon.for-action-key.for-action-default-key { color: #112233; }"
        val converted = theme(
            read(zip(metadata(flavors = ""), "style_sheet_md2.css" to sheet.toByteArray())),
        )
        assertEquals(0xFF112233L, converted.looks.first().converted.theme.enterKeyText)
    }

    /** Cut down from Rboard's 3D_BlackMcLaren: black keys, orange edge, orange slab under each. */
    @Test
    fun `a 3D pack theme keeps its black enter key and its hard key slab`() {
        val main = """
            @def color_base #000000FF;
            @def color_label #FFFFFFFF;
            @def color_state_key #00000000;
            @def color_state_key_transparent #00000000;
            @def default_background_corner_radius 7;
            .keyboard-body-area { background_color: @color_base; }
            .keytop { background_color: @color_state_key; }
            .keytop.for-action-key { background_color: #00000000; }
            .background-icon.for-action-key { color: @color_state_key_transparent; }
            .track.for-gesture { color: #E0610EFF; }
            .keytop { background_shape: rectangle; background_corner_radius: @default_background_corner_radius; }
        """.trimIndent()
        // The stray brace on its second line is in the shipped file.
        val border = """
            /* Color */
            }
            @def color_state_border_key_action #E0610EFF;
            @def color_state_key #000000FF;
            @def color_state_key_dark #000000FF;
            .keytop { elevation: 4; shadow_color: #FF6E10FF; edge_width: 1; edge_color: #E0610EFF; }
            .keytop.for-action-key { background_color: @color_state_border_key_action; }
            .keytop.for-action-key.for-action-default-key { background_color: @color_state_key_dark; }
        """.trimIndent()
        val converted = theme(
            read(
                zip(
                    metadata(),
                    "style_sheet_md2.css" to main.toByteArray(),
                    "style_sheet_md2_border.css" to border.toByteArray(),
                ),
            ),
        )
        val flat = converted.looks.first { !it.bordered }.converted.theme
        // Every face clear, enter's too: not the accent.
        assertEquals(0x00000000L, flat.enterKeyBackground)
        assertNull(flat.keySkin)

        val bordered = converted.looks.first { it.bordered }.converted.theme
        assertEquals(0xFF000000L, bordered.keyBackground)
        assertEquals(0xFF000000L, bordered.enterKeyBackground)
        assertEquals(0xFFE0610EL, bordered.keyBorderColor)
        assertEquals(0xFFFF6E10L, bordered.keyShadowColor)
        assertNotNull(bordered.keySkin)
        val slot = bordered.keySkin!!.slots.getValue(SkinSlotName.LETTER)
        // Slab, face, held colour, edge.
        assertEquals(listOf("shadow", "face", "keyPressed", null), slot.layers.map { it.fill?.color })
        assertEquals("border", slot.layers.last().stroke?.color)
    }

    @Test
    fun `the rule count is honest about what landed`() {
        val converted = theme(read(zip(metadata(flavors = ""), "style_sheet_md2.css" to mainSheet)))
        assertTrue(converted.ruleCount > converted.mappedRuleCount)
        assertTrue(converted.mappedRuleCount > 0)
        // The combinator rule, the padding/shadow rule's second half and the
        // font rule are among those read and not used.
        assertTrue(converted.ruleCount - converted.mappedRuleCount >= 1)
    }

    @Test
    fun `a theme of variables alone converts through the fallback sheet`() {
        val sheet = """
            @def color_base #FAFAFA;
            @def color_state_key #FFFFFF;
            @def color_state_key_dark #E0E0E0;
            @def color_label #202124;
            @def default_generic_accent_color #1A73E8FF;
        """.trimIndent().toByteArray()
        val spec = theme(read(zip(metadata(light = true, flavors = ""), "style_sheet_md2.css" to sheet)))
            .looks.single().converted.theme
        assertFalse(spec.dark)
        assertEquals(0xFFFAFAFAL, spec.boardBackground)
        assertEquals(0xFFFFFFFFL, spec.keyBackground)
        assertEquals(0xFFE0E0E0L, spec.modifierKeyBackground)
        assertEquals(0xFF202124L, spec.keyText)
        assertEquals(0xFF1A73E8L, spec.enterKeyBackground)
    }

    @Test
    fun `the board photo and its landscape crop come across`() {
        val sheet = """
            @def image_keyboard_background "body_portrait.jpg";
            .keyboard-body-area { background_color: #00000000; }
            .keyboard-base-area { background_color: #5B92DB; }
            .keyboard-background { background_image_ref: @image_keyboard_background; }
            .keytop { background_color: #FFFFFF2E; }
        """.trimIndent().toByteArray()
        val converted = theme(
            read(
                zip(
                    metadata(
                        flavors = """{"type":"LANDSCAPE","style_sheets":["style_sheet_landscape.css"]}""",
                    ),
                    "style_sheet_md2.css" to sheet,
                    "style_sheet_landscape.css" to "@def image_keyboard_background \"body_landscape.jpg\";".toByteArray(),
                    "body_portrait.jpg" to png,
                    "body_landscape.jpg" to png + byteArrayOf(1),
                ),
            ),
        )
        val look = converted.looks.single().converted
        assertNotNull(look.images[FlexTheme.IMAGE_BACKGROUND])
        assertNotNull(look.images[GboardTheme.IMAGE_BACKGROUND_LANDSCAPE])
        // The photo shows through: the key area over it is transparent.
        assertEquals(0x00000000L, look.theme.boardBackground)
        assertEquals(0x2EFFFFFFL, look.theme.keyBackground)
    }

    @Test
    fun `an unreadable label colour is replaced and reported`() {
        val sheet = """
            .keyboard-body-area { background_color: #FFFFFF; }
            .keytop { background_color: #FFFFFF; }
            .label { color: #FEFEFE; }
        """.trimIndent().toByteArray()
        val converted = theme(read(zip(metadata(light = true, flavors = ""), "style_sheet_md2.css" to sheet)))
        assertEquals(onColorFor(0xFFFFFFFFL), converted.looks.single().converted.theme.keyText)
        assertTrue(GboardUnsupported.LOW_CONTRAST_FALLBACK in converted.dropped)
    }

    @Test
    fun `metadata with trailing junk is still read`() {
        val meta = GboardTheme.METADATA to (
            """{"name":"Foxy","is_light_theme":false,"style_sheets":["a.css"],"flavors":[]}""" + "\n]\n}\n"
            ).toByteArray()
        assertEquals("Foxy", theme(read(zip(meta, "a.css" to mainSheet))).name)
    }

    @Test
    fun `a theme with no metadata is read from its stylesheet names`() {
        val converted = theme(
            read(zip("style_sheet.css" to mainSheet, "style_sheet_border.css" to borderSheet), name = "Loose"),
        )
        assertEquals("Loose", converted.name)
        assertEquals(2, converted.looks.size)
    }

    @Test
    fun `a theme zipped as a folder is read from inside the folder`() {
        val (metaName, metaBytes) = metadata(flavors = "")
        val converted = theme(read(zip("Dusk/$metaName" to metaBytes, "Dusk/style_sheet_md2.css" to mainSheet)))
        assertEquals("Dusk", converted.name)
    }

    // ---- refusals ----

    @Test
    fun `a compiled theme is refused as compiled`() {
        val bytes = zip("metadata.binarypb" to byteArrayOf(1, 2, 3), "style_sheet.binarypb" to byteArrayOf(4))
        assertEquals(GboardResult.Compiled, read(bytes))
    }

    @Test
    fun `junk and other archives are refused without throwing`() {
        val junk = read(byteArrayOf(1, 2, 3, 4))
        assertTrue(junk == GboardResult.Unreadable || junk == GboardResult.NotAGboardTheme)
        assertEquals(GboardResult.NotAGboardTheme, read(zip("pack.json" to "{}".toByteArray())))
        assertEquals(GboardResult.NotAGboardTheme, read(zip(metadata(), "unrelated.txt" to byteArrayOf(1))))
    }

    @Test
    fun `a truncated archive does not throw`() {
        val bytes = zip(metadata(), "style_sheet_md2.css" to mainSheet)
        val result = read(bytes.copyOf(bytes.size / 2))
        assertTrue(result is GboardResult.Unreadable || result is GboardResult.NotAGboardTheme ||
            result is GboardResult.Converted)
    }

    @Test
    fun `an entry that climbs out of the archive is never read`() {
        val bytes = zip(
            metadata(sheets = "\"../style_sheet_md2.css\""),
            "../style_sheet_md2.css" to mainSheet,
        )
        // The only sheet was named with a path that climbs, so there is nothing to read.
        assertFalse(read(bytes) is GboardResult.Converted)
    }

    @Test
    fun `a zip bomb stops at the cap`() {
        val huge = ByteArray(20 * 1024 * 1024) { 'a'.code.toByte() }
        val bytes = zip(metadata(), "style_sheet_md2.css" to mainSheet, "filler.png" to huge)
        // The filler is truncated at the image cap rather than read whole.
        assertTrue(read(bytes) is GboardResult.Converted)
    }

    // ---- packs ----

    @Test
    fun `a pack reads every theme, its previews and its meta`() {
        val one = zip(metadata(name = "stock"), "style_sheet_md2.css" to mainSheet)
        val two = zip(metadata(name = "stock", light = true), "style_sheet_md2.css" to mainSheet)
        val compiled = zip("metadata.binarypb" to byteArrayOf(1))
        val pack = zip(
            "Night_Owl.zip" to one,
            "Night_Owl" to png,
            "Day.zip" to two,
            "Old.zip" to compiled,
            GboardTheme.PACK_META to "name=Owls\r\nauthor=Someone\r\ntags=Dark,Border".toByteArray(),
        )
        val result = read(pack) as GboardResult.Converted
        assertTrue(result.isPack)
        assertEquals("Owls", result.packName)
        assertEquals("Someone", result.packAuthor)
        assertEquals(1, result.skipped)
        // Named after the entry, not the metadata: "stock" twice says nothing.
        assertEquals(listOf("Day", "Night Owl"), result.themes.map { it.name })
        assertNotNull(result.themes.last().preview)
        assertNull(result.themes.first().preview)
    }

    // ---- the real repository ----

    /**
     * Every pack in a local copy of the Rboard repository converts without
     * throwing, and almost every CSS theme in it yields something. Set
     * `RBOARD_PACKS` to the folder the packs were downloaded into.
     */
    @Test
    fun `the Rboard repository converts`() {
        val dir = System.getenv("RBOARD_PACKS")?.let(::File)
        assumeTrue("RBOARD_PACKS not set", dir != null && dir.isDirectory)
        var themes = 0
        var skipped = 0
        var rules = 0
        var mapped = 0
        val losses = HashMap<GboardUnsupported, Int>()
        for (file in dir!!.listFiles().orEmpty().sortedBy { it.name }) {
            val result = file.inputStream().use { GboardTheme.read(it, file.nameWithoutExtension) }
            if (result !is GboardResult.Converted) {
                println("${file.name}: $result")
                continue
            }
            themes += result.themes.size
            skipped += result.skipped
            for (theme in result.themes) {
                rules += theme.ruleCount
                mapped += theme.mappedRuleCount
                for (loss in theme.dropped) losses[loss] = (losses[loss] ?: 0) + 1
                assertTrue(theme.looks.isNotEmpty())
            }
        }
        println("themes=$themes skipped=$skipped rules=$mapped/$rules losses=$losses")
        assertTrue(themes > 0)
    }
}
