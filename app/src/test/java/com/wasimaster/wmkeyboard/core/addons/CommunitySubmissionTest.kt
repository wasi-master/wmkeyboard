package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.share.RemixCredit
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import java.net.URLDecoder
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The submission link and payload encoding the community repository's bot
 * reads. The vectors file is shared with the bot and was encoded by a
 * different implementation (Python's zlib), so decoding it here is what keeps
 * the two sides from drifting.
 */
class CommunitySubmissionTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("addons/$name")) {
            "missing test fixture addons/$name"
        }.use { it.readBytes().decodeToString() }

    private val fields = CommunitySubmission.Fields(name = "Night Owl", tags = listOf("dark", " blue ", "dark"))

    @Test
    fun `every shared vector decodes to its json`() {
        val vectors = Json.parseToJsonElement(fixture("community-submission-vectors.json"))
            .jsonObject.getValue("vectors").jsonArray
        assertTrue(vectors.size >= 3)
        for (vector in vectors) {
            val v = vector.jsonObject
            val name = v.getValue("name").jsonPrimitive.content
            val json = v.getValue("json").jsonPrimitive.content
            val encoded = v.getValue("encoded").jsonPrimitive.content
            assertEquals(name, json, CommunitySubmission.decodePayload(encoded))
            assertEquals(name, json, CommunitySubmission.decodePayload(CommunitySubmission.encodePayload(json)))
        }
    }

    @Test
    fun `a payload survives the whitespace a pasted text box adds`() {
        val encoded = CommunitySubmission.encodePayload("""{"a":1}""")
        assertEquals("""{"a":1}""", CommunitySubmission.decodePayload("\n  $encoded \n"))
    }

    @Test
    fun `text that is not a payload decodes to null`() {
        assertNull(CommunitySubmission.decodePayload("""{"a":1}"""))
        assertNull(CommunitySubmission.decodePayload("wmkb:1:!!!"))
        assertNull(CommunitySubmission.decodePayload("wmkb:1:AAAA"))
        assertNull(CommunitySubmission.decodePayload("wmkb:2:" + CommunitySubmission.encodePayload("{}").removePrefix("wmkb:1:")))
    }

    @Test
    fun `a small theme rides in the link with every field`() {
        val json = fixture("midnight.wmtheme.json")
        val plan = CommunitySubmission.plan(
            CommunitySubmission.Kind.THEME,
            fields.copy(description = "Dark & quiet", basedOn = "com.example/ocean"),
            json,
        )
        assertEquals(CommunitySubmission.Tier.LINK, plan.tier)
        assertTrue(plan.url.length <= CommunitySubmission.LINK_LIMIT)
        assertTrue(plan.url.startsWith("https://github.com/wasi-master/wmkeyboard-community-addons/issues/new?"))
        val params = query(plan.url)
        assertEquals("submit-theme.yml", params["template"])
        assertEquals("[Theme] Night Owl", params["title"])
        assertEquals("Dark & quiet", params["description"])
        assertEquals("dark, blue", params["tags"])
        assertEquals("CC0-1.0", params["license"])
        assertEquals("com.example/ocean", params["based_on"])
        assertEquals(json, CommunitySubmission.decodePayload(params.getValue("payload")))
        // GitHub's prefill does not read + as a space.
        assertTrue("+" !in plan.url)
    }

    @Test
    fun `blank optional fields stay out of the link`() {
        val url = CommunitySubmission.issueUrl(CommunitySubmission.Kind.LAYOUT, CommunitySubmission.Fields("Mine"), null)
        val params = query(url)
        assertEquals(setOf("template", "title", "name", "license"), params.keys)
        assertEquals("submit-layout.yml", params["template"])
    }

    @Test
    fun `a payload too long for a link is pasted, and a huge one attached`() {
        // Random-looking text barely compresses, so its length decides the tier.
        val random = java.util.Random(7)
        fun noise(n: Int) = buildString { repeat(n) { append('a' + random.nextInt(26)) } }
        val paste = CommunitySubmission.plan(CommunitySubmission.Kind.THEME, fields, """{"x":"${noise(20_000)}"}""")
        assertEquals(CommunitySubmission.Tier.PASTE, paste.tier)
        assertTrue("payload" !in query(paste.url))
        val attach = CommunitySubmission.plan(CommunitySubmission.Kind.THEME, fields, """{"x":"${noise(120_000)}"}""")
        assertEquals(CommunitySubmission.Tier.ATTACH, attach.tier)
    }

    @Test
    fun `the attachment holds the one file the bot looks for`() {
        val json = fixture("braille.wmlayout.json")
        val zip = CommunitySubmission.attachment(CommunitySubmission.Kind.LAYOUT, json)
        ZipInputStream(zip.inputStream()).use { input ->
            val entry = input.nextEntry!!
            assertEquals("layout.wmlayout.json", entry.name)
            assertEquals(json, input.readBytes().decodeToString())
            assertNull(input.nextEntry)
        }
        assertEquals("Braille 6.zip", CommunitySubmission.attachmentName(CommunitySubmission.Kind.LAYOUT, "Braille: 6"))
    }

    @Test
    fun `a pack is attached as its own zip, not wrapped`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("pack.json"))
            zip.write("""{"format":"wmkeyboard-icons","version":1,"pack":{"id":"r","name":"R"}}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("icons/tool.clipboard.svg"))
            zip.write("<svg/>".toByteArray())
            zip.closeEntry()
        }
        val pack = out.toByteArray()
        assertTrue(pack.contentEquals(CommunitySubmission.attachment(CommunitySubmission.Kind.ICON_PACK, pack)))
        val plan = CommunitySubmission.plan(CommunitySubmission.Kind.ICON_PACK, fields, pack)
        assertEquals("submit-icon-pack.yml", query(plan.url)["template"])
        assertTrue(pack.contentEquals(CommunitySubmission.decodePayloadBytes(plan.encoded)))
    }

    @Test
    fun `a remix credit survives a theme export and import`() {
        val credit = RemixCredit("com.wasimaster.wmkeyboard.community/night-owl", "Night Owl", "someone")
        val theme = ThemeSpec(id = "custom_1", name = "Mine", basedOn = credit)
        assertEquals(credit, ThemeCodec.decode(ThemeCodec.encode(theme))?.basedOn)
    }

    @Test
    fun `a remix credit survives a layout export and import`() {
        val credit = RemixCredit("com.wasimaster.wmkeyboard.community/kasroz", "KASROZ", "someone")
        val layout = LayoutFile.decode(fixture("braille.wmlayout.json"))!!.layout.copy(basedOn = credit)
        assertEquals(credit, LayoutFile.decode(LayoutFile.encode(layout, 1, "1"))?.layout?.basedOn)
    }

    private fun query(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
}
