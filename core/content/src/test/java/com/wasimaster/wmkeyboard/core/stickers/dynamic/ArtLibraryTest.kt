package com.wasimaster.wmkeyboard.core.stickers.dynamic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ArtLibrary] parses one drawing at a time out of the library's bytes; it
 * has to hand back exactly what parsing the whole file did.
 */
class ArtLibraryTest {

    @Test
    fun everyDrawingReadsAsTheWholeFileParsedDid() {
        val bytes = File(BuiltinPackTest.findAssets(), "art/art.json").readBytes()
        val whole = LottieDoc.json.parseToJsonElement(bytes.decodeToString()) as JsonObject
        val library = ArtLibrary(bytes)
        assertEquals(whole.keys, library.ids)
        for ((id, element) in whole) {
            val want = checkNotNull(ArtLibrary.artOf(element as JsonObject)) { "$id does not read" }
            val got = checkNotNull(library[id]) { "$id missing" }
            assertEquals(id, want.width, got.width, 0f)
            assertEquals(id, want.height, got.height, 0f)
            assertEquals(id, want.shapes, got.shapes)
        }
    }

    @Test
    fun spansSkipPunctuationInsideStrings() {
        val text = """ { "a\"b" : {"s":"x}],{[\\\"","n":[1,[2,{"k":"]"}]]} , "num": 12.5 ,"t":true,"z":"é}" ,"last":{"w":1}} """
        val bytes = text.toByteArray()
        val spans = ArtLibrary.scan(bytes)
        val whole = Json.parseToJsonElement(text) as JsonObject
        assertEquals(whole.keys, spans.keys)
        for ((key, span) in spans) {
            val start = (span ushr 32).toInt()
            val end = span.toInt()
            assertEquals(key, whole[key], Json.parseToJsonElement(String(bytes, start, end - start, Charsets.UTF_8)))
        }
    }

    @Test
    fun aBrokenFileKeepsWhatCameBeforeTheBreak() {
        assertTrue(ArtLibrary.scan("[1,2]".toByteArray()).isEmpty())
        assertTrue(ArtLibrary.scan("{}".toByteArray()).isEmpty())
        assertTrue(ArtLibrary.scan("""{"a":{"b":1""".toByteArray()).isEmpty())
        assertEquals(setOf("a"), ArtLibrary.scan("""{"a":1,"b":{"c":""".toByteArray()).keys)
    }

    @Test
    fun aDrawingWithoutItsBoxIsNotOne() {
        val library = ArtLibrary("""{"ok":{"w":2,"h":3,"shapes":[]},"bad":{"shapes":[]}}""".toByteArray())
        assertNotNull(library["ok"])
        assertNull(library["bad"])
        assertNull(library["absent"])
    }
}
