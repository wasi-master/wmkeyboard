package com.wasimaster.wmkeyboard.core.theme

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a FlorisBoard `.flex` theme extension.
 *
 * The archives are built here rather than committed. Two reasons, and the second
 * is the important one: FlorisBoard is Apache-2.0 and this repo is MIT, so no
 * community pack belongs in the tree; and an archive built in the test is the
 * only way to write the adversarial cases — three hundred entries, an entry
 * named `../../etc/passwd`, a stylesheet in the older dialect — at all.
 */
class FlexThemeTest {

    // ---- helpers ----

    private fun flex(vararg entries: Pair<String, ByteArray>): ByteArray {
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

    private fun read(vararg entries: Pair<String, ByteArray>): FlexResult =
        FlexTheme.read(flex(*entries).inputStream())

    private fun manifest(themes: String, license: String = "Apache-2.0"): Pair<String, ByteArray> =
        FlexTheme.MANIFEST to """
            {
              "${'$'}": "ime.extension.theme",
              "meta": {
                "id": "com.example.dusk",
                "version": "1.0.0",
                "title": "Dusk",
                "maintainers": ["Someone"],
                "license": "$license"
              },
              "themes": [$themes]
            }
        """.trimIndent().toByteArray()

    private fun sheet(body: String): ByteArray = body.trimIndent().toByteArray()

    private val dayEntry = """{ "id": "day", "label": "Dusk", "isNight": false }"""
    private val nightEntry = """{ "id": "night", "label": "Dusk", "isNight": true }"""

    /** A minimal v2 stylesheet: it names the elements only the newer dialect has. */
    private val v2Sheet = sheet(
        """
        {
          "window": { "background": "#101014" },
          "key": {
            "background": "#2C2C34",
            "foreground": "#FFFFFF",
            "shape": "rounded-corner(8dp)",
            "border-color": "#40FFFFFF",
            "border-width": "1dp"
          },
          "key:pressed": { "background": "#3C3C48" },
          "key hint": { "foreground": "#A0A0A8" },
          "key popup box": { "background": "#20202A", "foreground": "#FFFFFF" },
          "smartbar candidate": { "foreground": "#E0E0E8" }
        }
        """,
    )

    private fun converted(result: FlexResult): FlexResult.Converted {
        assertTrue("expected a conversion, got $result", result is FlexResult.Converted)
        return result as FlexResult.Converted
    }

    // ---- the archive ----

    @Test
    fun `a theme extension is read`() {
        val result = converted(read(manifest(dayEntry), "stylesheets/day.json" to v2Sheet))
        assertEquals(1, result.themes.size)
        assertEquals("Dusk", result.themes[0].theme.name)
        assertEquals("Apache-2.0", result.license)
        assertEquals(listOf("Someone"), result.authors)
    }

    @Test
    fun `a ZIP that is not a theme extension is refused`() {
        assertEquals(FlexResult.NotAFlex, read("pack.json" to """{"format":"wmstickers"}""".toByteArray()))
        assertEquals(FlexResult.NotAFlex, read("readme.txt" to "hello".toByteArray()))
        // The right manifest name but the wrong kind of extension.
        assertEquals(
            FlexResult.NotAFlex,
            read(FlexTheme.MANIFEST to """{"${'$'}":"ime.extension.keyboard"}""".toByteArray()),
        )
    }

    @Test
    fun `something that is not a ZIP is refused`() {
        // Plain text yields no entries rather than an error, so it comes back as
        // "not one of ours" — which is what it is, and the wording the import
        // shows for it is the right one.
        assertEquals(FlexResult.NotAFlex, FlexTheme.read("not a zip at all".byteInputStream()))
        assertEquals(FlexResult.NotAFlex, FlexTheme.read(ByteArray(0).inputStream()))
    }

    @Test
    fun `a truncated archive is refused rather than half read`() {
        val whole = flex(manifest(dayEntry), "stylesheets/day.json" to v2Sheet)
        val result = FlexTheme.read(whole.copyOf(whole.size / 2).inputStream())
        assertTrue("$result", result is FlexResult.Unreadable || result is FlexResult.NotAFlex)
    }

    @Test
    fun `an archive of three hundred entries stops at the cap`() {
        val padding = Array(300) { "junk/$it.bin" to ByteArray(64) }
        // The manifest is first, so it is read; the entry cap stops the rest.
        // The point is that this returns rather than running to completion.
        val result = read(manifest(dayEntry), "stylesheets/day.json" to v2Sheet, *padding)
        assertTrue("$result", result is FlexResult.Converted)
    }

    @Test
    fun `an entry name that walks upwards is only ever a map key`() {
        // Nothing here touches the filesystem, so the guard is structural. The
        // assertion that matters is that such a name cannot be resolved into
        // anything either: the stylesheet path below does not match it.
        val result = read(
            manifest(dayEntry),
            "../../../../etc/passwd" to "root:x:0:0".toByteArray(),
            "stylesheets/day.json" to v2Sheet,
        )
        assertTrue(result is FlexResult.Converted)
        assertNull(FlexTheme.lookUp(mapOf("day.json" to ByteArray(1)), "../../day.txt"))
    }

    @Test
    fun `a stylesheet is found by the path the manifest declares`() {
        val entry = """{ "id": "x", "label": "X", "stylesheetPath": "styles/custom.json" }"""
        assertTrue(read(manifest(entry), "styles/custom.json" to v2Sheet) is FlexResult.Converted)
    }

    // ---- the dialect ----

    @Test
    fun `a v1 stylesheet is refused rather than half mapped`() {
        val v1 = sheet(
            """
            {
              "keyboard": { "background": "rgba(16,16,20,1)" },
              "smartbar": { "background": "rgba(16,16,20,1)" }
            }
            """,
        )
        assertEquals(FlexResult.SnyggV1, read(manifest(dayEntry), "stylesheets/day.json" to v1))
    }

    // ---- what lands ----

    @Test
    fun `colours land on the fields that mean the same surface`() {
        val theme = converted(read(manifest(dayEntry), "stylesheets/day.json" to v2Sheet)).themes[0].theme
        assertEquals(0xFF101014, theme.boardBackground)
        assertEquals(0xFF2C2C34, theme.keyBackground)
        assertEquals(0xFFFFFFFF, theme.keyText)
        assertEquals(0xFF3C3C48, theme.pressedKeyBackground)
        assertEquals(0xFF20202A, theme.popupBackground)
        assertEquals(0xFFA0A0A8, theme.hintText)
        assertEquals(0xFFE0E0E8, theme.suggestionText)
        assertEquals(KeyShapeKind.ROUNDED, theme.keyShape)
        assertEquals(8, theme.keyCornerRadiusDp)
        assertEquals(1f, theme.keyBorderWidthDp, 0.001f)
    }

    @Test
    fun `a field the sheet says nothing about is left to derive`() {
        val theme = converted(read(manifest(dayEntry), "stylesheets/day.json" to v2Sheet)).themes[0].theme
        // Null is not "missing": these derive a legible colour from their
        // neighbours, which beats one scraped off a different surface.
        assertNull(theme.modifierKeyText)
        assertNull(theme.chipText)
        assertNull(theme.toolbarIcon)
        assertNull(theme.toolCircleBackground)
    }

    @Test
    fun `a day and night pair becomes two themes, told apart`() {
        val result = converted(read(manifest("$dayEntry, $nightEntry"), "stylesheets/day.json" to v2Sheet, "stylesheets/night.json" to v2Sheet))
        assertEquals(2, result.themes.size)
        assertEquals(listOf(false, true), result.themes.map { it.theme.dark })
        assertEquals(listOf("Dusk (light)", "Dusk (dark)"), result.themes.map { it.theme.name })
        // The extension's own title travels out, so the pair can become one
        // family named after the extension rather than after its first look.
        assertEquals("Dusk", result.title)
    }

    @Test
    fun `per-key rules become key overrides`() {
        val withCodes = sheet(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#2C2C34", "foreground": "#FFFFFF" },
              "key popup box": { "background": "#20202A" },
              "key[code=-11]": { "background": "#404050" },
              "key[code=32]": { "background": "#1A1A22" },
              "key[code=97]": { "background": "#503030" }
            }
            """,
        )
        val theme = converted(read(manifest(dayEntry), "stylesheets/day.json" to withCodes)).themes[0].theme
        assertEquals(0xFF404050, theme.keyOverrides["SHIFT"]?.background)
        assertEquals(0xFF1A1A22, theme.keyOverrides["SPACE"]?.background)
        // A printable code styles the letter it types, so it follows that letter
        // across every layout.
        assertEquals(0xFF503030, theme.keyOverrides["a"]?.background)
    }

    @Test
    fun `unreadable key text is dropped rather than shipped`() {
        val unreadable = sheet(
            """
            {
              "window": { "background": "#101014" },
              "key": { "background": "#101014", "foreground": "#131318" },
              "key popup box": { "background": "#20202A" }
            }
            """,
        )
        val result = converted(read(manifest(dayEntry), "stylesheets/day.json" to unreadable))
        assertTrue(FlexUnsupported.LOW_CONTRAST_FALLBACK in result.dropped)
        val theme = result.themes[0].theme
        assertTrue(contrastRatio(theme.keyText, theme.keyBackground) > 3f)
    }

    @Test
    fun `what could not be carried is named`() {
        val fancy = sheet(
            """
            {
              "@font": { "family": "Comic" },
              "window": { "background": "#101014" },
              "key": {
                "background": "#2C2C34",
                "foreground": "#FFFFFF",
                "shadow-elevation": "4dp",
                "margin": "2dp",
                "shape": "rounded-corner(12dp, 4dp, 12dp, 4dp)"
              },
              "key popup box": { "background": "#20202A" },
              "incognito-indicator": { "foreground": "#FF0000" }
            }
            """,
        )
        val result = converted(read(manifest(dayEntry), "stylesheets/day.json" to fancy))
        assertTrue(FlexUnsupported.FONT in result.dropped)
        assertTrue(FlexUnsupported.ELEVATION in result.dropped)
        assertTrue(FlexUnsupported.PER_ELEMENT_SPACING in result.dropped)
        assertTrue(FlexUnsupported.PER_CORNER_RADIUS in result.dropped)
        assertTrue(FlexUnsupported.UNKNOWN_ELEMENT in result.dropped)
    }

    @Test
    fun `the counts describe how much of the sheet was used`() {
        val result = converted(read(manifest(dayEntry), "stylesheets/day.json" to v2Sheet))
        assertEquals(6, result.ruleCount)
        assertEquals(6, result.mappedRuleCount)
    }

    @Test
    fun `a variable is resolved against the defines block`() {
        val withVars = sheet(
            """
            {
              "@defines": { "--surface": "#123456", "--ink": "#FFFFFF" },
              "window": { "background": "var(--surface)" },
              "key": { "background": "var(--surface)", "foreground": "var(--ink)" },
              "key popup box": { "background": "var(--surface)" }
            }
            """,
        )
        val theme = converted(read(manifest(dayEntry), "stylesheets/day.json" to withVars)).themes[0].theme
        assertEquals(0xFF123456, theme.boardBackground)
        assertEquals(0xFFFFFFFF, theme.keyText)
    }

    @Test
    fun `a sheet that names no surface is not a theme`() {
        val empty = sheet("""{ "key hint": { "foreground": "#FFFFFF" } }""")
        assertEquals(FlexResult.NotAFlex, read(manifest(dayEntry), "stylesheets/day.json" to empty))
    }

    @Test
    fun `an image the sheet points at comes back as bytes`() {
        val withImage = sheet(
            """
            {
              "window": { "background": "#101014", "background-image": "images/bg.png" },
              "key": { "background": "#2C2C34" },
              "key popup box": { "background": "#20202A" }
            }
            """,
        )
        val bytes = ByteArray(32) { it.toByte() }
        val result = converted(
            read(manifest(dayEntry), "stylesheets/day.json" to withImage, "images/bg.png" to bytes),
        )
        // Bytes, not base64 and not a file: encoding needs android.util.Base64,
        // which would put this parser out of reach of this test.
        assertNotNull(result.themes[0].images[FlexTheme.IMAGE_BACKGROUND])
        assertEquals(32, result.themes[0].images[FlexTheme.IMAGE_BACKGROUND]?.size)
    }

    @Test
    fun `a converted theme survives the codec`() {
        val theme = converted(read(manifest(dayEntry), "stylesheets/day.json" to v2Sheet)).themes[0].theme
        assertEquals(theme, ThemeCodec.decode(ThemeCodec.encode(theme)))
    }
}
