package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proximity used to be five hand-written tables of row strings. They are kept
 * here verbatim as the oracle: deriving the same adjacency from the grids is
 * only safe if it reproduces them exactly, and asserting that over every pair of
 * characters turns "I checked it by eye" into something the build enforces.
 *
 * When a built-in layout is deliberately changed, the matching literal below has
 * to change with it — that is the point, not an inconvenience.
 */
class KeyProximityTest {

    private val oldTables = mapOf(
        BuiltInLayouts.QWERTY_ID to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        BuiltInLayouts.AZERTY_ID to listOf("azertyuiop", "qsdfghjklm", "wxcvbn'"),
        BuiltInLayouts.DVORAK_ID to listOf("',.pyfgcrl", "aoeuidhtns", "qjkxbmwvz"),
        BuiltInLayouts.GERMAN_ID to listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
        BuiltInLayouts.SPANISH_ID to listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
    )

    /** Reimplements the old constructor input so the two can be compared. */
    private fun reference(rows: List<String>): KeyProximity =
        KeyProximity.forLayout(
            LayoutSpec(id = "reference", name = "reference", proximityRows = rows),
        )

    @Test
    fun `derived adjacency matches the old tables for every character pair`() {
        for ((layoutId, rows) in oldTables) {
            val expected = reference(rows)
            val actual = KeyProximity.forLayout(BuiltInLayouts.byId(layoutId)!!)
            val chars = rows.joinToString("").toSet()

            for (a in chars) {
                for (b in chars) {
                    assertEquals(
                        "$layoutId: adjacency of '$a' and '$b' changed",
                        expected.areAdjacent(a, b),
                        actual.areAdjacent(a, b),
                    )
                }
            }
        }
    }

    @Test
    fun `the letter rows project exactly as the old tables spelled them`() {
        for ((layoutId, rows) in oldTables) {
            val proximity = KeyProximity.forLayout(BuiltInLayouts.byId(layoutId)!!)
            // Every character the table names has to be known to the derived map,
            // which catches a row being dropped or shortened rather than merely
            // reordered.
            for (c in rows.joinToString("")) {
                assertTrue(
                    "$layoutId: '$c' is missing from the derived proximity",
                    rows.joinToString("").any { other -> other != c && proximity.areAdjacent(c, other) },
                )
            }
        }
    }

    @Test
    fun `avro borrows the qwerty grid so it keeps qwerty proximity`() {
        val avro = KeyProximity.forLayout(BuiltInLayouts.AVRO)
        assertTrue(avro.areAdjacent('q', 'w'))
        assertTrue(avro.areAdjacent('a', 's'))
        assertFalse(avro.areAdjacent('q', 'p'))
    }

    @Test
    fun `french borrows the azerty grid`() {
        val french = KeyProximity.forLayout(BuiltInLayouts.FRENCH)
        assertTrue("'a' and 'z' lead the AZERTY top row", french.areAdjacent('a', 'z'))
        assertTrue("'q' sits directly under 'a'", french.areAdjacent('a', 'q'))
        assertFalse("opposite ends of the top row are not neighbours", french.areAdjacent('a', 'p'))
    }

    @Test
    fun `the spacebar row is excluded so punctuation is not made a neighbour`() {
        // The bottom row of every built-in holds "," and "." as text keys. If it
        // were projected they would become a fourth row and neighbours of the
        // letters above, which is exactly the bug the skip exists to prevent.
        val qwerty = KeyProximity.forLayout(BuiltInLayouts.QWERTY)
        assertFalse("',' must not be adjacent to a letter", qwerty.areAdjacent(',', 'z'))
        assertFalse("'.' must not be adjacent to a letter", qwerty.areAdjacent('.', 'm'))
    }

    @Test
    fun `dvorak keeps its punctuation keys so the columns do not shift`() {
        val dvorak = KeyProximity.forLayout(BuiltInLayouts.DVORAK)
        // ' , . lead Dvorak's top row. Dropping them as non-letters would move
        // p y f g c r l three columns left and break every column relation.
        assertTrue(dvorak.areAdjacent('\'', ','))
        assertTrue(dvorak.areAdjacent(',', '.'))
        assertTrue("'a' sits under '\'' in the real grid", dvorak.areAdjacent('a', '\''))
    }

    @Test
    fun `a custom grid gets its own proximity rather than borrowing qwerty`() {
        val custom = LayoutSpec(
            id = "custom_1",
            name = "Mine",
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(
                    listOf(
                        listOf(Key("z"), Key("y"), Key("x")),
                        listOf(Key("a"), Key("b"), Key("c")),
                        listOf(Key(" ", action = KeyAction.Space)),
                    ),
                ),
            ),
        )
        val proximity = KeyProximity.forLayout(custom)
        assertTrue(proximity.areAdjacent('z', 'y'))
        assertTrue(proximity.areAdjacent('z', 'b'))
        assertFalse("'q' and 'w' are not in this layout at all", proximity.areAdjacent('q', 'w'))
    }

    @Test
    fun `an explicit proximity override wins over the grid`() {
        val custom = LayoutSpec(
            id = "custom_1",
            name = "Mine",
            layers = mapOf(
                LayoutLayer.LETTERS.key to LayerSpec(listOf(listOf(Key("a"), Key("b")))),
            ),
            proximityRows = listOf("xy", "zw"),
        )
        val proximity = KeyProximity.forLayout(custom)
        assertTrue(proximity.areAdjacent('x', 'y'))
        assertFalse(proximity.areAdjacent('a', 'b'))
    }

    @Test
    fun `two builds from the same layout are equal`() {
        // The IME rebuilds the proximity on every settings emission; cache
        // invalidation keys on value equality, not identity.
        val a = KeyProximity.forLayout(BuiltInLayouts.QWERTY)
        val b = KeyProximity.forLayout(BuiltInLayouts.QWERTY)
        assertTrue(a !== b)
        assertTrue(a == b)
        assertTrue(a.hashCode() == b.hashCode())
        val dvorak = KeyProximity.forLayout(BuiltInLayouts.DVORAK)
        assertFalse(a == dvorak)
    }

    @Test fun `number row digits neighbour the top letter row`() {
        val p = KeyProximity.forLayout(BuiltInLayouts.QWERTY, numberRow = true)
        assertTrue(p.areAdjacent('3', 'e'))
        assertTrue(p.areAdjacent('7', 'i'))
        assertTrue(p.areAdjacent('8', 'i'))
        assertTrue(p.areAdjacent('9', 'o'))
        assertTrue(p.areAdjacent('e', '3'))
        assertFalse(p.areAdjacent('3', 's'))
        // Without the flag the grid stays digit-free, and the two variants
        // must not compare equal (the engine's cache keys on value equality).
        assertFalse(KeyProximity.QWERTY.areAdjacent('3', 'e'))
        assertFalse(KeyProximity.QWERTY == p)
    }

    private fun withNumberRow(letters: List<List<Key>>, numberRow: List<Key>?) = LayoutSpec(
        id = "custom_970",
        name = "Mine",
        layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(letters, numberRow = numberRow)),
    )

    @Test
    fun `a layout's own number row is the one stitched over its letters`() {
        // Eastern Arabic digits over the top row (#970): ٨ sits where 8 would.
        val digits = "١٢٣٤٥٦٧٨٩٠".map { Key(it.toString()) }
        val spec = withNumberRow(
            listOf("qwertyuiop", "asdfghjkl").map { row -> row.map { Key(it.toString()) } },
            digits,
        )
        val p = KeyProximity.forLayout(spec, numberRow = true)
        assertTrue(p.areAdjacent('٨', 'i'))
        assertTrue(p.areAdjacent('i', '٨'))
        assertFalse("the built-in digits are not on this board", p.areAdjacent('8', 'i'))
        // Off, no number row at all, authored or not.
        assertFalse(KeyProximity.forLayout(spec).areAdjacent('٨', 'i'))
        // A layout that authored none keeps the built-in digits.
        val plain = withNumberRow(listOf("qwertyuiop", "asdfghjkl").map { row -> row.map { Key(it.toString()) } }, null)
        assertTrue(KeyProximity.forLayout(plain, numberRow = true).areAdjacent('8', 'i'))
    }

    @Test
    fun `a key carries the letters on its shift, its corner and its hold`() {
        val spec = withNumberRow(
            listOf(
                listOf(
                    Key("ه", shiftLabel = "ة"),
                    Key("ع", shiftLabel = "ّ"),
                    Key("ا", longPress = listOf("أ", "إ", "آ", "!")),
                    Key("s", longPress = listOf("ß")),
                    Key("q"),
                ),
                listOf(Key("؟", hint = "ئ"), Key("e"), Key("u", shiftLabel = "U")),
            ),
            listOf(Key("8", longPress = listOf("i", "¾"))),
        )
        val p = KeyProximity.forLayout(spec, numberRow = true)
        assertTrue(p.carries('ه', 'ة'))
        assertTrue(p.carries('ا', 'أ'))
        assertTrue(p.carries('ا', 'آ'))
        assertTrue(p.carries('s', 'ß'))
        assertTrue("a corner hint counts", p.carries('؟', 'ئ'))
        assertTrue("so does a number-row key's hold", p.carries('8', 'i'))
        assertFalse("punctuation is not a letter a word holds", p.carries('ا', '!'))
        assertFalse("nor is a fraction", p.carries('8', '¾'))
        assertFalse("a mark on Shift never stands in for the letter", p.carries('ع', 'ّ'))
        assertFalse("Latin's Shift is a capital, nothing to carry", p.carries('u', 'U'))
        assertFalse("one way: the shifted letter typed on purpose stays itself", p.carries('ة', 'ه'))
        assertFalse(p.carries('q', 'w'))
        val hebrew = withNumberRow(listOf(listOf(Key("ק", shiftLabel = "q", longPress = listOf("q")))), null)
        assertFalse("a Latin letter is not what a Hebrew tap stood in for", KeyProximity.forLayout(hebrew).carries('ק', 'q'))
        // The carried letters are part of the model's identity: the engine
        // rebuilds its search only when a proximity stops comparing equal.
        val letters = spec.layer(LayoutLayer.LETTERS)!!.rows
        val bare = withNumberRow(
            letters.map { row -> row.map { it.copy(shiftLabel = null, longPress = emptyList(), hint = null) } },
            null,
        )
        assertFalse(KeyProximity.forLayout(bare) == KeyProximity.forLayout(spec))
    }
}
