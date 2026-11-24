package com.wasimaster.wmkeyboard.ime.wmchroma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WM Chroma's JSON as its KeyboardProvider writes it. */
class ChromaJsonTest {
    @Test fun aDescribedColour() {
        val d = ChromaJson.described(
            """{"argb":-12877066,"name":"Azure Radiance","text":"#3b82f6","formats":[""" +
                """{"id":"hex","label":"Hex","text":"#3b82f6"},{"id":"rgb","label":"RGB","text":"rgb(59 130 246)"},{"id":"x","label":"X","text":""}]}""",
        )!!
        assertEquals(ChromaColour(-12877066, "Azure Radiance", "#3b82f6"), d.colour)
        // A format with no text is dropped.
        assertEquals(listOf("hex", "rgb"), d.formats.map { it.id })
    }

    @Test fun aLibrary() {
        val l = ChromaJson.library(
            """{"recent":[{"argb":-1,"name":"White","text":"#ffffff"}],"tray":[],""" +
                """"palettes":[{"id":4,"name":"Brand","pinned":true,"swatches":[{"argb":-16777216,"name":"","text":"#000000"},{"bad":1}]}]}""",
        )!!
        assertEquals(1, l.recent.size)
        assertEquals(0, l.tray.size)
        assertEquals(ChromaPalette(4, "Brand", true, listOf(ChromaColour(-16777216, "", "#000000"))), l.palettes.single())
    }

    @Test fun nonsenseIsNull() {
        assertNull(ChromaJson.described("not json"))
        assertNull(ChromaJson.described("""{"name":"no argb"}"""))
        assertNull(ChromaJson.library("[]"))
    }

    @Test fun theChipOffersOnlyFormatsThatChangeTheCode() {
        val formats = listOf(
            ChromaFormat("hex", "Hex", "#3B82F6"),
            ChromaFormat("rgb", "RGB", "rgb(59 130 246)"),
            ChromaFormat("rgb2", "RGB", "rgb(59 130 246)"),
            ChromaFormat("hsl", "HSL", "hsl(217 91% 60%)"),
        )
        assertEquals(listOf("rgb", "hsl"), ColourChip.otherFormats("#3b82f6", formats).map { it.id })
        assertEquals(listOf("hex", "hsl"), ColourChip.otherFormats("rgb(59  130 246)", formats).map { it.id })
    }
}
