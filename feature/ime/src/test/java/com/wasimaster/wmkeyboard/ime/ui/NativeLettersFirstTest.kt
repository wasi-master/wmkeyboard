package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.compile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Discussion #382: on German, a hold on u typed 7, because the digit led the
 * popup and the umlaut sat one slide along.
 */
class NativeLettersFirstTest {

    private val german = NativeLetters.of("de-DE")
    private val english = NativeLetters.of("en-US")

    private fun qwertz(label: String): Key =
        BuiltInLayouts.GERMAN.compile(LayoutLayer.LETTERS).rows.flatten().first { it.label == label }

    @Test
    fun `german puts the umlaut ahead of the digit and keeps the rest in order`() {
        assertEquals(listOf("ü", "7", "ù", "ú", "û"), nativeLettersFirst(qwertz("u"), german).longPress)
        assertEquals(listOf("ö", "9", "ò", "ó", "ô"), nativeLettersFirst(qwertz("o"), german).longPress)
        assertEquals(listOf("ä", "@", "à", "á", "â"), nativeLettersFirst(qwertz("a"), german).longPress)
        assertEquals(listOf("ß", "#", "ś"), nativeLettersFirst(qwertz("s"), german).longPress)
    }

    @Test
    fun `accents german does not use stay behind the hint`() {
        // ż and ź are Polish: z keeps its 6.
        val z = qwertz("z")
        assertSame(z, nativeLettersFirst(z, german))
        val e = qwertz("e")
        assertSame(e, nativeLettersFirst(e, german))
    }

    @Test
    fun `english, whose alphabet is a-z, changes nothing`() {
        val u = qwertz("u")
        assertSame(u, nativeLettersFirst(u, english))
        assertSame(u, nativeLettersFirst(u, emptySet()))
    }

    @Test
    fun `a popup that already leads with a letter is left as authored`() {
        val key = Key("u", longPress = listOf("ù", "7", "ü"))
        assertSame(key, nativeLettersFirst(key, german))
    }

    @Test
    fun `a combining mark is not a hint`() {
        val key = Key("ो", longPress = listOf("ॉ", "ऑ"))
        assertSame(key, nativeLettersFirst(key, setOf("ॉ", "ऑ")))
    }

    @Test
    fun `the keys of an ambiguous grid keep their keypad digit first`() {
        val key = Key("ABC", output = "a", letters = "abcä", longPress = listOf("2", "a", "b", "c", "ä"))
        assertSame(key, nativeLettersFirst(key, german))
    }

    @Test
    fun `the table knows german and leaves english empty`() {
        assertEquals(setOf("ä", "ö", "ü", "ß"), german)
        assertEquals(emptySet<String>(), english)
        // A regional tag the table does not list finds its language by subtag.
        assertEquals(german, NativeLetters.of("de-CH"))
    }

    @Test
    fun `brazilian portuguese letters are grouped under the key they are typed from`() {
        val byBase = NativeLetters.byBase("pt-BR")
        assertEquals(listOf("á", "à", "â", "ã"), byBase['a'])
        assertEquals(listOf("ç"), byBase['c'])
        assertEquals(listOf("ó", "ò", "ô", "õ"), byBase['o'])
        // ß does not come apart, and lands under s by the accent table.
        assertEquals(listOf("ß"), NativeLetters.byBase("de-DE")['s'])
        assertEquals(emptyMap<Char, List<String>>(), NativeLetters.byBase("en-US"))
    }
}
