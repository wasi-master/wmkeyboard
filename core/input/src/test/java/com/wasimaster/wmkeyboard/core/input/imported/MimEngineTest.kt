package com.wasimaster.wmkeyboard.core.input.imported

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The m17n reader and rule engine, on small methods written for the test.
 * Each one exercises a construct the shipped `.mim` files rely on; the
 * corpus itself is not checked in.
 */
class MimEngineTest {

    /**
     * An ITRANS-shaped method: a consonant writes its dead form, a vowel after
     * it writes the sign in its place, and a word that ends on a dead
     * consonant loses the virama, the way hi-itrans does it with a marker.
     */
    private val itrans = """
        ;; A comment, then the declaration.
        (input-method hi test-itrans)
        (description (_"A small ITRANS for the test."))
        (title "क")
        (variable (trim (_"Drop the last virama.") 1 0 1))
        (map
         (starter ("k") ("a") ("." ))
         (consonant ("k" "क्") ("kh" "ख्"))
         (independent ("a" "अ") ("aa" "आ"))
         (dependent ("a" (delete @-)) ("aa" (delete @-) "ा"))
         (danda (".." "।")))
        (state
         (init (starter (pushback 1) (shift first)))
         (first
          (consonant (mark HALANT) (shift second))
          (independent (shift init))
          (danda (shift init)))
         (second
          (consonant (mark HALANT))
          (dependent (shift init))
          (danda (cond (trim (move HALANT) (delete @-) (move @>))) (shift init))
          (nil (cond (trim (move HALANT) (delete @-) (move @>))) (shift init))))
    """.trimIndent()

    private val engine = MimEngine(MimProgram.compile(itrans))

    @Test
    fun `a consonant shows its dead form while the word is open`() {
        assertEquals("क्", engine.run("k").text)
        assertEquals("ख्", engine.run("kh").text)
    }

    @Test
    fun `a longer sequence replaces the shorter one rather than adding to it`() {
        // k then h is ख्, not क्ह or क्ख्: each step restores the state's text first.
        assertEquals("ख्", engine.run("kh").text)
        assertEquals("आ", engine.run("aa").text)
    }

    @Test
    fun `a vowel sign takes the virama's place`() {
        assertEquals("क", engine.run("ka").text)
        assertEquals("का", engine.run("kaa").text)
        assertEquals("कक", engine.run("kaka").text)
    }

    @Test
    fun `the word end drops a trailing virama through the nil branch`() {
        assertEquals("क", engine.finish("k"))
        assertEquals("कक", engine.finish("kak"))
    }

    @Test
    fun `a danda after a dead consonant drops its virama too`() {
        assertEquals("क।", engine.finish("k.."))
    }

    @Test
    fun `a key no map takes ends the word and is typed as itself`() {
        // z closes the word, so the virama goes before z is typed.
        assertEquals("कz", engine.run("kz").text)
        assertEquals("कz", engine.finish("kz"))
    }

    @Test
    fun `the keys the method reads exclude named keys and the space`() {
        val program = MimProgram.compile(itrans)
        assertTrue('k' in program.keys)
        assertTrue('.' in program.keys)
        assertFalse(' ' in program.keys)
        assertEquals("hi", program.language)
        assertEquals("test-itrans", program.name)
        assertEquals("A small ITRANS for the test.", program.description)
    }

    @Test
    fun `the reader takes character literals, escapes and hex numbers`() {
        val forms = MimReader.read(
            """
            (a ?x ?\" ?க 0x41 #x42 -3 "t\x41 b\n" sym\;bol)
            """.trimIndent(),
        )
        val items = (forms.single() as MimValue.Lst).items
        assertEquals(MimValue.Sym("a"), items[0])
        assertEquals(MimValue.Num('x'.code), items[1])
        assertEquals(MimValue.Num('"'.code), items[2])
        assertEquals(MimValue.Num('க'.code), items[3])
        assertEquals(MimValue.Num(0x41), items[4])
        assertEquals(MimValue.Num(0x42), items[5])
        assertEquals(MimValue.Num(-3), items[6])
        assertEquals(MimValue.Text("tAb\n"), items[7])
        assertEquals(MimValue.Sym("sym;bol"), items[8])
    }

    @Test(expected = MimReader.SyntaxError::class)
    fun `an unclosed list is a syntax error`() {
        MimReader.read("(input-method hi x")
    }

    @Test
    fun `a state hook runs when the next key arrives`() {
        val program = MimProgram.compile(
            """
            (input-method t hook)
            (map (m ("a" "A") ("b" "B")))
            (state
             (init (m (shift other)))
             (other (t (insert "*")) (m (shift init))))
            """.trimIndent(),
        )
        // a → A, enters `other`; b arrives, the hook writes * first, then B.
        assertEquals("A*B", MimEngine(program).run("ab").text)
    }

    @Test
    fun `a surrounding marker reaches into text the word already committed`() {
        val program = MimProgram.compile(
            """
            (input-method t surround)
            (map (m ("a" "x") ("b" (delete @-1) "y")))
            (state (init (m)))
            """.trimIndent(),
        )
        // a commits x at once (a leaf in the initial state); b then deletes
        // the x behind the cursor, which is committed text, and writes y.
        assertEquals("y", MimEngine(program).run("ab").text)
    }

    @Test
    fun `a candidate list writes its first choice`() {
        val program = MimProgram.compile(
            """
            (input-method t choices)
            (map (m ("a" ("xyz")) ("b" (("one" "two")))))
            (state (init (m)))
            """.trimIndent(),
        )
        val engine = MimEngine(program)
        assertEquals("x", engine.run("a").text)
        assertEquals("one", engine.run("b").text)
        assertTrue(MimFeature.CANDIDATE_LIST !in program.unsupported)
        assertTrue(program.listsCandidates)
    }

    @Test
    fun `the list a run ends on is offered, with select moving the choice`() {
        val program = MimProgram.compile(
            """
            (input-method t pick)
            (map (m ("k" "K") ("a" ("xyz"))) (sel ("n" (select @+))))
            (state (init (m (shift pick))) (pick (sel)))
            """.trimIndent(),
        )
        val engine = MimEngine(program)
        val first = engine.run("a").choices!!
        assertEquals(listOf("x", "y", "z"), first.list)
        assertEquals(0, first.index)
        assertEquals("y", first.textWith(1))
        assertEquals(1, engine.run("an").choices!!.index)
        assertEquals("y", engine.run("an").text)
        // A list the initial state commits at once is already settled.
        assertEquals(null, engine.run("k").choices)
        assertFalse(MimProgram.compile(itrans).listsCandidates)
    }

    @Test
    fun `an include is resolved from the other files and reported when absent`() {
        val shared = """
            (input-method t shared-util)
            (map (common ("q" "Q")))
        """.trimIndent()
        val main = """
            (input-method xx main)
            (include (t shared-util) map)
            (map (own ("a" "A")))
            (state (init (own) (common)))
        """.trimIndent()
        val withLib = MimProgram.compile(main, listOf(shared))
        assertEquals("QA", MimEngine(withLib).run("qa").text)
        assertTrue(withLib.missingIncludes.isEmpty())

        val without = MimProgram.compile(main)
        assertEquals(listOf("t shared-util"), without.missingIncludes)
        assertEquals("qA", MimEngine(without).run("qa").text)
    }

    @Test
    fun `a plug-in call is flagged and otherwise ignored`() {
        val program = MimProgram.compile(
            """
            (input-method ja plug)
            (module (libmimx-test convert))
            (map (m ("a" (call libmimx-test convert) "A")))
            (state (init (m)))
            """.trimIndent(),
        )
        assertTrue(MimFeature.EXTERNAL_MODULE in program.unsupported)
        assertEquals("A", MimEngine(program).run("a").text)
    }

    @Test
    fun `rules that loop are stopped rather than hanging`() {
        val program = MimProgram.compile(
            """
            (input-method t loop)
            (map (m ("a" (pushback 1))))
            (state (init (m (shift init))))
            """.trimIndent(),
        )
        // Pushing the key back forever: the step limit ends it.
        MimEngine(program).run("aaa")
    }

    @Test
    fun `code points beyond the basic plane count as one character`() {
        val program = MimProgram.compile(
            """
            (input-method sa brahmi)
            (map (m ("k" "𑀓") ("x" (delete @-) "!")))
            (state (init (m (shift second))) (second (m)))
            """.trimIndent(),
        )
        // 𑀓 is two UTF-16 units and one character: one delete removes it whole.
        assertEquals("!", MimEngine(program).run("kx").text)
    }
}
