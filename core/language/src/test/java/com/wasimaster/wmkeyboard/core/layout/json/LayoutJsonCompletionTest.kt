package com.wasimaster.wmkeyboard.core.layout.json

import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The suggestions: what each place in a layout offers, and what choosing one writes. */
class LayoutJsonCompletionTest {

    private fun complete(marked: String, root: LayoutJsonRoot = LayoutJsonRoot.LAYOUT, explicit: Boolean = false): JsonCompletions? {
        val caret = marked.indexOf('|')
        val text = marked.removeRange(caret, caret + 1)
        return LayoutJsonCompletion.at(JsonTree.parse(text), caret, root, explicit = explicit)
    }

    /** [marked] with the chosen [label] applied, and the caret marked where the item puts it. */
    private fun choose(marked: String, label: String, root: LayoutJsonRoot = LayoutJsonRoot.LAYOUT, explicit: Boolean = false): String {
        val caret = marked.indexOf('|')
        val text = marked.removeRange(caret, caret + 1)
        val found = LayoutJsonCompletion.at(JsonTree.parse(text), caret, root, explicit = explicit)!!
        val item = found.items.first { it.label == label }
        val after = text.substring(0, found.start) + item.insert + text.substring(found.end)
        val at = found.start + item.caret
        return after.substring(0, at) + "|" + after.substring(at)
    }

    private fun inKey(inside: String) = "{\"id\": \"x\", \"name\": \"X\", \"layers\": {\"letters\": {\"rows\": [[$inside]]}}}"

    private fun labels(found: JsonCompletions?) = found?.items?.map { it.label }.orEmpty()

    @Test
    fun `a new key object offers its properties, the required label first`() {
        val found = complete(inKey("{|}"))
        assertEquals("label", labels(found).first())
        assertTrue("width" in labels(found))
    }

    @Test
    fun `choosing a property writes its key, its colon and a blank`() {
        assertEquals(inKey("{\"width\": |}"), choose(inKey("{|}"), "width"))
        assertEquals(inKey("{\"label\": \"|\"}"), choose(inKey("{|}"), "label"))
        assertEquals(inKey("{\"longPress\": [\"|\"]}"), choose(inKey("{|}"), "longPress"))
        assertEquals(inKey("{\"action\": {\"type\": \"|\"}}"), choose(inKey("{|}"), "action"))
    }

    @Test
    fun `a boolean property is written as the value that is not its default`() {
        assertEquals(inKey("{\"hideHint\": true|}"), choose(inKey("{|}"), "hideHint"))
        assertEquals("{\"tabletExpand\": false|}", choose("{|}", "tabletExpand"))
    }

    @Test
    fun `typing inside a string narrows the list, and a property with values reopens it`() {
        val found = complete(inKey("{\"label\": \"a\", \"wi|\"}"))!!
        assertEquals("width", found.items.first().label)
        assertTrue(found.items.first().reopen)
    }

    @Test
    fun `a property already in the object is not offered again`() {
        assertFalse("label" in labels(complete(inKey("{\"label\": \"a\", \"|\"}"))))
    }

    @Test
    fun `a comma is added in front when the previous member lacks one`() {
        val written = choose(inKey("{\"label\": \"a\" \"wi|\"}"), "width")
        assertEquals(inKey("{\"label\": \"a\", \"width\": |}"), written)
    }

    @Test
    fun `renaming a key that has its colon changes only the key`() {
        assertEquals(inKey("{\"width\"|: 1}"), choose(inKey("{\"wi|\": 1}"), "width"))
    }

    @Test
    fun `an action type offers the tags, and a tag with a field brings the field`() {
        val written = choose(inKey("{\"label\": \"\", \"action\": {\"type\": \"to|\"}}"), "tool")
        assertEquals(inKey("{\"label\": \"\", \"action\": {\"type\": \"tool\", \"tool\": \"|\"}}"), written)
        val tools = complete(inKey("{\"label\": \"\", \"action\": {\"type\": \"tool\", \"tool\": \"|\"}}"))
        assertTrue("VOICE" in labels(tools))
    }

    @Test
    fun `an enum property offers its values after the colon`() {
        assertEquals(listOf("Period", "Comma"), labels(complete(inKey("{\"label\": \".\", \"role\": |}"))))
    }

    @Test
    fun `a value after a finished value offers nothing`() {
        assertNull(complete(inKey("{\"label\": \"a\", \"width\": 1 |}")))
    }

    @Test
    fun `a layers map offers the layers it lacks, each with a row to fill`() {
        val text = "{\"id\": \"x\", \"name\": \"X\", \"layers\": {\"letters\": {\"rows\": []}, \"|\"}}"
        assertFalse("letters" in labels(complete(text)))
        assertEquals(
            "{\"id\": \"x\", \"name\": \"X\", \"layers\": {\"letters\": {\"rows\": []}, \"symbols\": {\"rows\": [[|]]}}}",
            choose(text, "symbols"),
        )
    }

    @Test
    fun `a word typed in a row offers whole keys`() {
        val found = complete(inKey("sh|"))
        assertNotNull(found)
        assertEquals("{shift}", found!!.items.first().label)
    }

    @Test
    fun `asking in a row offers keys, and adds the comma between two keys`() {
        val written = choose(inKey("{\"label\": \"a\"}, | {\"label\": \"b\"}"), "{label}", explicit = true)
        assertEquals(inKey("{\"label\": \"a\"}, {\"label\": \"|\"}, {\"label\": \"b\"}"), written)
    }

    @Test
    fun `a language id is found by its name`() {
        val typed = LanguageRegistry.byId("bn").englishName.take(4)
        val found = complete("{\"id\": \"x\", \"name\": \"X\", \"langId\": \"$typed|\"}")
        assertTrue("bn" in labels(found))
    }

    @Test
    fun `a panel's component cell offers only that panel's components`() {
        val text = "{\"panel\": \"emoji\", \"grid\": {\"rows\": [[{\"label\": \"\", \"action\": {\"type\": \"field\", \"kind\": \"|\"}}]]}}"
        assertEquals(listOf("emoji_tabs", "emoji_search", "emoji_grid", "media_tabs"), labels(complete(text, LayoutJsonRoot.PANEL)))
    }

    @Test
    fun `an empty document offers a whole layout when asked`() {
        val found = complete("|", explicit = true)!!
        assertTrue(found.items.single().insert.contains("\"layers\""))
        assertNull(complete("|"))
    }

    @Test
    fun `matching prefers a prefix, then the capitals of a name, then letters in order`() {
        assertEquals(6, matchScore("longPress", "long"))
        assertEquals(4, matchScore("longPress", "lp"))
        assertEquals(4, matchScore("language_switch", "ls"))
        assertEquals(3, matchScore("longPress", "press"))
        assertEquals(2, matchScore("longPress", "lgs"))
        assertEquals(null, matchScore("longPress", "zz"))
    }
}
