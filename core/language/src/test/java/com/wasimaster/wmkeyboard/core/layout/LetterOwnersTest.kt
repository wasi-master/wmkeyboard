package com.wasimaster.wmkeyboard.core.layout

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a key-set letter is placed for glide and the touch model: at its own
 * key when it has one, at the key that holds it behind a long press when that
 * key carries it, and otherwise at every key that carries it.
 */
class LetterOwnersTest {

    private fun ambiguous(set: String, vararg longPress: String) =
        Key(set.uppercase(), output = set.take(1), letters = set, longPress = longPress.toList())

    private fun placed(layout: KeyboardLayout, key: Key): String {
        val owners = layout.letterOwners()
        return key.lettersPlacedHere(owners).joinToString("") { String(Character.toChars(it)) }
    }

    @Test
    fun `a keypad's letters have no key of their own, so each sits on the key that carries it`() {
        val two = ambiguous("abc", "2", "a", "b", "c")
        val three = ambiguous("def", "3", "d", "e", "f")
        val pad = KeyboardLayout("test", listOf(listOf(two, three)))

        assertEquals("abc", placed(pad, two))
        assertEquals("def", placed(pad, three))
    }

    @Test
    fun `overlapping sets place each letter at its own key and each accent at its base key`() {
        // One row: every key carries its neighbours and their accents.
        val w = Key("w", output = "w", letters = "wseé", longPress = listOf("2", "w"))
        val s = Key("s", output = "s", letters = "sweé", longPress = listOf("#", "s", "ß"))
        val e = Key("e", output = "e", letters = "eésw", longPress = listOf("3", "e", "é"))
        val row = KeyboardLayout("test", listOf(listOf(w, s, e)))

        assertEquals("only its anchor: s, e and é all belong elsewhere", "w", placed(row, w))
        assertEquals("s", placed(row, s))
        assertEquals("é behind e's hold belongs to e", "eé", placed(row, e))
    }

    @Test
    fun `a shifted copy of a key is placed like the key itself`() {
        val e = Key("e", output = "e", letters = "eé", longPress = listOf("3", "e", "é"))
        val d = Key("d", output = "d", letters = "deé", longPress = listOf("$", "d"))
        val row = KeyboardLayout("test", listOf(listOf(e, d)))

        assertEquals("eé", placed(row, e.copy(label = "E")))
        assertEquals("d", placed(row, d.copy(label = "D")))
    }
}
