package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.layout.json.JsonIssue
import com.wasimaster.wmkeyboard.core.layout.json.JsonSeverity
import com.wasimaster.wmkeyboard.core.layout.json.JsonTree
import com.wasimaster.wmkeyboard.core.layout.json.LayoutJsonCheck
import com.wasimaster.wmkeyboard.core.layout.json.LayoutJsonRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The keyboard-wide number and arrow rows as text: storage, the JSON editor, and what is refused. */
class KeyRowCodecTest {

    private val digits = BuiltInLayouts.defaultNumberRow(LayoutLayer.LETTERS)

    @Test
    fun `a row comes back from storage unchanged`() {
        assertEquals(digits, KeyRowCodec.decode(KeyRowCodec.encode(digits)))
    }

    @Test
    fun `a row comes back from the editor's text unchanged`() {
        assertEquals(digits, KeyRowCodec.decode(KeyRowCodec.encodeForEditing(digits)))
    }

    @Test
    fun `keys copied in the layout editor paste in as a row`() {
        val keys = listOf(Key("("), Key(")"))
        assertEquals(keys, KeyRowCodec.decode(KeyFile.encode(keys, appVersion = 1)))
    }

    @Test
    fun `blank text, an empty list and something else are not a row`() {
        assertNull(KeyRowCodec.decode(""))
        assertNull(KeyRowCodec.decode("[]"))
        assertNull(KeyRowCodec.decode("{\"id\": \"x\"}"))
        assertNull(KeyRowCodec.decode("not json"))
    }

    @Test
    fun `the built-in row as the editor prints it has nothing wrong`() {
        val problems = LayoutJsonCheck.check(JsonTree.parse(KeyRowCodec.encodeForEditing(digits)), LayoutJsonRoot.ROW)
            .filter { it.severity != JsonSeverity.INFO }
        assertEquals(emptyList<JsonIssue>(), problems)
    }
}
