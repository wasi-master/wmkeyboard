package com.wasimaster.wmkeyboard.core.theme

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** FUTO Keyboard's two theme files, built from their published shape. */
class FutoThemeTest {

    @Test
    fun tomlThemeMapsTheRolesFutoDrawsWith() {
        val result = FutoTheme.read(mapOf(FutoTheme.TOML_CONFIG to TOML.toByteArray())) as FutoThemeResult.Converted
        val theme = result.converted.theme
        assertEquals("Midnight", theme.name)
        assertEquals("Someone", result.author)
        assertEquals(0xFF101418L, theme.boardBackground)
        assertEquals(0xFF2A2F3AL, theme.keyBackground)
        assertEquals(0xFFE0E0E0L, theme.keyText)
        assertEquals(0xFF1C2028L, theme.modifierKeyBackground)
        assertEquals(0xFF8AB4F8L, theme.enterKeyBackground)
        assertEquals(0xFF0B1220L, theme.enterKeyText)
        assertEquals(0xFF8AB4F8L, theme.accent)
        // `#3A404C80` is RGBA: alpha last.
        assertEquals(0x803A404CL, theme.pressedKeyBackground)
        assertEquals(0x80E0E0E0L, theme.hintText)
        assertTrue(theme.dark)
        assertEquals(1.2f, theme.fontScale)
        assertEquals(KeyShapeKind.ROUNDED, theme.keyShape)
        assertEquals(14, theme.keyCornerRadiusDp)
    }

    @Test
    fun wallpaperColoursResolveAndMissingPicturesAreNamed() {
        val text = TOML.replace("primary = \"#8AB4F8\"", "primary = \"system_accent1_200\"") +
            "\n[[matchrules.border]]\nselector = \"normal\"\nasset = \"key.png\"\n"
        val result = FutoTheme.read(mapOf(FutoTheme.TOML_CONFIG to text.toByteArray())) as FutoThemeResult.Converted
        val theme = result.converted.theme
        // No phone palette handed in: Material's baseline stands in, and says so.
        assertTrue(FutoThemeUnsupported.WALLPAPER_STAND_IN in result.dropped)
        // `accent1_200` is the primary palette at tone 80.
        assertEquals(0xFFD0BCFFL, theme.enterKeyBackground)
        assertTrue(theme.followWallpaper)
        assertEquals(0xFFD0BCFFL, theme.wallpaperRoles[wallpaperToneKey("primary", 80)])
        // The picture the rule names is not in the ZIP.
        assertTrue(FutoThemeUnsupported.KEY_PICTURES in result.dropped)
    }

    @Test
    fun withoutBordersTheKeysAreNotDrawn() {
        val text = TOML.replace("auto_borders = true", "auto_borders = false")
        val theme = (FutoTheme.read(mapOf(FutoTheme.TOML_CONFIG to text.toByteArray())) as FutoThemeResult.Converted)
            .converted.theme
        assertEquals(KeyShapeKind.NONE, theme.keyShape)
        assertEquals(0xFFF0F0F0L, theme.keyText)
    }

    @Test
    fun jsonThemeReadsAlphaFirstAndCarriesItsPicture() {
        val picture = byteArrayOf(1, 2, 3, 4)
        val zip = zip(
            FutoTheme.VERSION_ENTRY to byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0),
            FutoTheme.JSON_CONFIG to JSON.toByteArray(),
            "bg.png" to picture,
        )
        val result = FutoTheme.read(zip.inputStream()) as FutoThemeResult.Converted
        val theme = result.converted.theme
        assertEquals("Editor theme", theme.name)
        assertEquals(0xFF202124L, theme.boardBackground)
        assertEquals(0x80303134L, theme.keyBackground)
        assertEquals(0xFF112233L, theme.navigationBarBackground)
        assertEquals(0.75f, theme.backgroundImageOpacity)
        assertArrayEquals(picture, result.converted.images[FlexTheme.IMAGE_BACKGROUND])
        assertTrue(FutoThemeUnsupported.SHADER in result.dropped)
    }

    @Test
    fun aZipWithoutThemeSettingsIsNotATheme() {
        assertEquals(FutoThemeResult.NotATheme, FutoTheme.read(zip("readme.txt" to "hi".toByteArray()).inputStream()))
        assertFalse(FutoTheme.looksLikeFutoTheme(listOf("readme.txt")))
        assertTrue(FutoTheme.looksLikeFutoTheme(listOf("theme.txt")))
    }

    @Test
    fun theOtherTextNamedThemeTxtIsNotATheme() {
        val result = FutoTheme.read(mapOf(FutoTheme.TOML_CONFIG to "just some notes".toByteArray()))
        assertEquals(FutoThemeResult.NotATheme, result)
    }

    @Test
    fun hexColoursInEveryLength() {
        assertEquals(0xFFAABBCCL, FutoTheme.parseHex("#abc", alphaLast = true))
        assertEquals(0xFF102030L, FutoTheme.parseHex("#102030", alphaLast = false))
        assertEquals(0x80102030L, FutoTheme.parseHex("#10203080", alphaLast = true))
        assertEquals(0x80102030L, FutoTheme.parseHex("#80102030", alphaLast = false))
        assertNull(FutoTheme.parseHex("#12345", alphaLast = true))
        assertNull(FutoTheme.parseHex("#zzzzzz", alphaLast = true))
    }

    @Test
    fun miniTomlReadsTablesArraysAndComments() {
        val doc = MiniToml.parse(
            """
            name = "A # not a comment" # a comment
            [options]
            cropping = [0.0, 0.1,
              1.0, 1.0]
            font = { font = "f.ttf" }
            [[matchrules.icon]]
            selector = "code -5"
            asset = 'del.png'
            """.trimIndent(),
        )
        assertNotNull(doc)
        assertEquals("A # not a comment", doc!!["name"])
        @Suppress("UNCHECKED_CAST")
        val options = doc["options"] as Map<String, Any?>
        assertEquals(4, (options["cropping"] as List<*>).size)
        assertEquals("f.ttf", (options["font"] as Map<*, *>)["font"])
        val icons = (doc["matchrules"] as Map<*, *>)["icon"] as List<*>
        assertEquals("del.png", (icons.single() as Map<*, *>)["asset"])
    }

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

    private companion object {
        val TOML = """
            # Format version: 1
            name = "Midnight"
            author = "Someone"
            id = "midnight"

            [options]
            auto_borders = true
            roundedness = 1.5
            scale_text = 1.2

            [colors]
            primary = "#8AB4F8"
            on_primary = "#0B1220"
            on_background = "#F0F0F0"
            background = "#101418"
            keyboard_surface = "#101418"
            keyboard_surface_dim = "#0C0F12"
            keyboard_container = "#2A2F3A"
            keyboard_container_variant = "#1C2028"
            on_keyboard_container = "#E0E0E0"
            keyboard_press = "#30343C"
            keyboard_container_pressed = "#3A404C80"
            on_keyboard_container_pressed = "#FFFFFF"
        """.trimIndent()

        val JSON = """
            {
              "name": "Editor theme",
              "primary": "#FF8AB4F8",
              "onPrimary": "#FF0B1220",
              "keyboardSurface": "#FF202124",
              "keyboardContainer": "#80303134",
              "keyboardContainerVariant": "#FF28292C",
              "onKeyboardContainer": "#FFE8EAED",
              "navigationBarColor": "#112233",
              "backgroundImage": "bg.png",
              "backgroundImageOpacity": 0.75,
              "keyboardBackgroundShader": "uniform float t;"
            }
        """.trimIndent()
    }
}
