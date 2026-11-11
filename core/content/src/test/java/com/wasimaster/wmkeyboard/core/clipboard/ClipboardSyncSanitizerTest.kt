package com.wasimaster.wmkeyboard.core.clipboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard bundle a settings backup or a sync carries, checked item by
 * item before it is written (#1215, from saen1a's prototype).
 */
class ClipboardSyncSanitizerTest {

    private fun bundle(vararg items: String): JsonElement =
        Json.parseToJsonElement("""{"items":[${items.joinToString(",")}]}""")

    private fun texts(element: JsonElement): List<String?> =
        element.jsonObject.getValue("items").jsonArray.map { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }

    @Test
    fun `ordinary text, rich text and link clips travel as they are`() {
        val input = bundle(
            """{"id":1,"text":"hello","timestamp":10}""",
            """{"id":2,"text":"bold","timestamp":11,"kind":"HTML","htmlText":"<b>bold</b>","mimeType":"text/html"}""",
            """{"id":3,"text":"https://example.com","timestamp":12,"kind":"LINK","pinned":true}""",
        )
        val out = ClipboardSyncSanitizer.sanitize(input)
        assertEquals(input, out)
    }

    @Test
    fun `a password-shaped clip the user kept like any other still travels`() {
        // "Keep it like any other clip" marks nothing, and the sanitizer does
        // not second-guess that by reading the text.
        val out = ClipboardSyncSanitizer.sanitize(bundle("""{"id":1,"text":"Xk9#mQ2!vL7p","timestamp":10}"""))
        assertEquals(listOf("Xk9#mQ2!vL7p"), texts(out))
    }

    @Test
    fun `sensitive, media and file clips stay behind`() {
        val out = ClipboardSyncSanitizer.sanitize(
            bundle(
                """{"id":1,"text":"secret","timestamp":10,"sensitive":true}""",
                """{"id":2,"text":"","timestamp":11,"kind":"IMAGE","imagePath":"/data/x.png"}""",
                """{"id":3,"text":"a.pdf","timestamp":12,"kind":"FILE","uriString":"content://x/1"}""",
                """{"id":4,"text":"kept","timestamp":13}""",
            ),
        )
        assertEquals(listOf("kept"), texts(out))
    }

    @Test
    fun `a text clip naming a file to delete is refused`() {
        val out = ClipboardSyncSanitizer.sanitize(
            bundle(
                """{"id":1,"text":"x","timestamp":10,"imagePath":"/data/data/app/files/settings.pb"}""",
                """{"id":2,"text":"y","timestamp":10,"uriString":"content://evil"}""",
                """{"id":3,"text":"z","timestamp":10,"fileSize":12}""",
            ),
        )
        assertEquals(emptyList<String?>(), texts(out))
    }

    @Test
    fun `an item the store could not load is dropped alone`() {
        val out = ClipboardSyncSanitizer.sanitize(
            bundle(
                """{"text":"no id","timestamp":10}""",
                """{"id":2,"text":"no timestamp"}""",
                """{"id":3,"text":"bad pin","timestamp":10,"pinned":"yes"}""",
                """{"id":4,"text":12,"timestamp":10}""",
                """"not an object"""",
                """{"id":5,"text":"fine","timestamp":10}""",
            ),
        )
        assertEquals(listOf("fine"), texts(out))
    }

    @Test
    fun `a malformed section becomes an empty one`() {
        val empty = JsonObject(mapOf("items" to JsonArray(emptyList())))
        assertEquals(empty, ClipboardSyncSanitizer.sanitize(JsonPrimitive("x")))
        assertEquals(empty, ClipboardSyncSanitizer.sanitize(Json.parseToJsonElement("""{"items":3}""")))
        assertEquals(empty, ClipboardSyncSanitizer.sanitize(Json.parseToJsonElement("""{}""")))
    }

    @Test
    fun `a repeated id keeps the first clip`() {
        val out = ClipboardSyncSanitizer.sanitize(
            bundle("""{"id":1,"text":"first","timestamp":10}""", """{"id":1,"text":"second","timestamp":11}"""),
        )
        assertEquals(listOf("first"), texts(out))
    }

    @Test
    fun `an over-long clip is cut, not lost, and its markup goes`() {
        val long = "a".repeat(ClipboardStore.HARD_MAX_TEXT_CHARS + 10)
        val out = ClipboardSyncSanitizer.sanitize(
            bundle("""{"id":1,"text":"$long","timestamp":10,"kind":"HTML","htmlText":"<p>x</p>","mimeType":"text/html"}"""),
        )
        val item = out.jsonObject.getValue("items").jsonArray.single().jsonObject
        assertEquals(ClipboardStore.HARD_MAX_TEXT_CHARS, item.getValue("text").jsonPrimitive.content.length)
        assertEquals("TEXT", item.getValue("kind").jsonPrimitive.content)
        assertNull(item.getValue("htmlText").jsonPrimitive.contentOrNull)
    }

    @Test
    fun `the phone's own media and secret clips stay through a sync`() {
        val local = Json.parseToJsonElement(
            """[{"id":1,"text":"","timestamp":1,"kind":"IMAGE","imagePath":"/x.png"},
               {"id":2,"text":"pw","timestamp":2,"sensitive":true,"pinned":true},
               {"id":3,"text":"old text","timestamp":3}]""",
        ).jsonArray
        assertEquals(listOf(1L, 2L), local.filter(ClipboardSyncSanitizer::staysLocal).map { it.jsonObject.getValue("id").jsonPrimitive.long })
    }

    @Test
    fun `incoming ids that are taken here are moved above every id in use`() {
        val stays = Json.parseToJsonElement("""[{"id":5,"text":"","timestamp":1,"kind":"IMAGE","imagePath":"/x.png"}]""").jsonArray
        val incoming = Json.parseToJsonElement(
            """[{"id":5,"text":"a","timestamp":2},{"id":7,"text":"b","timestamp":3},{"id":7,"text":"c","timestamp":4}]""",
        ).jsonArray
        val merged = ClipboardSyncSanitizer.deconflictIds(incoming, stays)
        val ids = merged.map { it.jsonObject.getValue("id").jsonPrimitive.long }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(5L, ids.last())
        assertTrue(ids.contains(7L))
        assertEquals(listOf("a", "b", "c", ""), merged.map { it.jsonObject.getValue("text").jsonPrimitive.content })
    }
}
