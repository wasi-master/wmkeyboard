package com.wasimaster.wmkeyboard.core.layout

import java.io.File
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typing rules (#1493): the engine, the placeholders, and every shipped rule
 * set typed the ways people type it. The sets are read from the app's assets,
 * so a broken file fails here rather than on a phone.
 */
class SequenceRulesTest {

    private fun rules(groups: Map<String, List<String>>, vararg rules: SequenceRule): CompiledSequenceRules {
        val compiled = CompiledSequenceRules.compile(groups, rules.toList())
        assertEquals("problems: ${compiled.problems}", emptyList<SequenceRuleProblem>(), compiled.problems)
        return requireNotNull(CompiledSequenceRules.of(listOf(compiled)))
    }

    private fun problem(groups: Map<String, List<String>>, rule: SequenceRule): SequenceRuleProblem? =
        CompiledSequenceRules.compile(groups, listOf(rule)).problems.firstOrNull()

    /** A shipped set, from the app's assets wherever the test runs from. */
    private fun shipped(id: String): CompiledSequenceRules {
        val file = listOf("app/src/main/assets", "../../app/src/main/assets", "src/main/assets")
            .map { "$it/sequence_rules" }
            .map { File(it, "$id.json") }
            .first { it.exists() }
        val set = requireNotNull(SequenceRuleSets.decode(file.readText())) { "not a rule set: $file" }
        assertEquals(id, set.id)
        val compiled = CompiledSequenceRules.compile(set.characterGroups, set.sequenceRules, id)
        assertEquals("$id problems", emptyList<SequenceRuleProblem>(), compiled.problems)
        return requireNotNull(CompiledSequenceRules.of(listOf(compiled)))
    }

    // ---- the engine ----

    @Test
    fun `a literal rule rewrites the tail it matches`() {
        val r = rules(emptyMap(), SequenceRule("ae", "æ"))
        assertEquals(SequenceEdit(1, "æ", "a", null, r.rules[0]), r.edit("ha", "e"))
        assertEquals("hæ", r.run("hae").text)
    }

    @Test
    fun `a rule fires only on the key that completes it`() {
        val r = rules(emptyMap(), SequenceRule("ae", "æ"))
        // The pattern is in the text already, but the key typed is not its end.
        assertNull(r.edit("ae", "x"))
        assertFalse(r.mayFire("x"))
        assertTrue(r.mayFire("e"))
    }

    @Test
    fun `groups swap by name`() {
        val g = mapOf("t" to listOf("1", "2"), "v" to listOf("a", "b"))
        val r = rules(g, SequenceRule("{t}{v}", "{v}{t}"))
        assertEquals("xb2", r.run("x2b").text)
        assertEquals("a1b2", r.run("1a2b").text)
    }

    @Test
    fun `dollar refs write groups by place`() {
        val g = mapOf("t" to listOf("1", "2"))
        val r = rules(g, SequenceRule("{t}{t}", "$2"))
        assertEquals("2", r.run("12").text)
        assertEquals("$", rules(emptyMap(), SequenceRule("s", "$$")).run("s").text)
    }

    @Test
    fun `a mapped group writes the item at the same place`() {
        val g = mapOf("plain" to listOf("a", "e"), "tone" to listOf("ā", "ē"))
        val r = rules(g, SequenceRule("{plain}1", "{tone:plain}", undo = true))
        val edit = requireNotNull(r.edit("he", "1"))
        assertEquals(1, edit.delete)
        assertEquals("ē", edit.text)
        assertEquals("e1", edit.restore)
        assertEquals("hē", r.run("he1").text)
    }

    @Test
    fun `the longest item of a group is tried first`() {
        val g = mapOf("x" to listOf("k", "kh"))
        val r = rules(g, SequenceRule("{x}a", "[{x}]"))
        assertEquals("[kh]", r.run("kha").text)
    }

    @Test
    fun `braces are literal when doubled`() {
        val r = rules(emptyMap(), SequenceRule("{{x}}", "y"))
        assertEquals("y", r.run("{x}").text)
    }

    @Test
    fun `one rewrite per key, so a swap and its reverse cannot loop`() {
        val g = mapOf("a" to listOf("1"), "b" to listOf("2"))
        val r = rules(g, SequenceRule("{a}{b}", "{b}{a}"), SequenceRule("{b}{a}", "{a}{b}"))
        assertEquals("21", r.run("12").text)
    }

    @Test
    fun `first matching rule wins`() {
        val r = rules(emptyMap(), SequenceRule("ab", "X"), SequenceRule("b", "Y"))
        assertEquals("X", r.run("ab").text)
        assertEquals("cY", r.run("cb").text)
    }

    @Test
    fun `a match inside a multi-character key keeps the head of the key`() {
        val r = rules(emptyMap(), SequenceRule("bc", "X"))
        val edit = requireNotNull(r.edit("", "abc"))
        assertEquals(0, edit.delete)
        assertEquals("aX", edit.text)
    }

    @Test
    fun `surrogate pairs are matched whole and never split`() {
        val r = rules(emptyMap(), SequenceRule("😀!", "😃"))
        assertEquals("x😃", r.run("x😀!").text)
        // A rule on the low half alone of a pair cannot start inside it.
        val low = "😀"[1].toString()
        val half = CompiledSequenceRules.of(listOf(CompiledSequenceRules.compile(emptyMap(), listOf(SequenceRule(low + "!", "?")))))!!
        assertNull(half.edit("😀", "!"))
    }

    @Test
    fun `a rule that writes back what was typed is not an edit`() {
        val r = rules(emptyMap(), SequenceRule("ab", "ab"))
        assertNull(r.edit("a", "b"))
    }

    @Test
    fun `run names the rule behind the last change`() {
        val r = rules(emptyMap(), SequenceRule("ab", "X"))
        assertEquals(r.rules[0], r.run("ab").lastRule)
        assertNull(r.run("ba").lastRule)
    }

    // ---- compile errors ----

    @Test
    fun `every compile error is reported`() {
        val g = mapOf("t" to listOf("1"), "big" to listOf("a", "b"), "small" to listOf("x"))
        assertEquals(SequenceRuleError.EMPTY_PATTERN, problem(g, SequenceRule("", "x"))?.error)
        assertEquals(SequenceRuleError.UNKNOWN_GROUP, problem(g, SequenceRule("{nope}", "x"))?.error)
        assertEquals(SequenceRuleError.BAD_PLACEHOLDER, problem(g, SequenceRule("{t", "x"))?.error)
        assertEquals(SequenceRuleError.BAD_PLACEHOLDER, problem(g, SequenceRule("a}", "x"))?.error)
        assertEquals(SequenceRuleError.BAD_PLACEHOLDER, problem(g, SequenceRule("{a b}", "x"))?.error)
        assertEquals(SequenceRuleError.PATTERN_TOO_LONG, problem(g, SequenceRule("a".repeat(MaxSequencePatternLength + 1), "x"))?.error)
        assertEquals(SequenceRuleError.NO_SUCH_SLOT, problem(g, SequenceRule("{t}", "$2"))?.error)
        assertEquals(SequenceRuleError.NO_SUCH_SLOT, problem(g, SequenceRule("a", "{t}"))?.error)
        assertEquals(SequenceRuleError.UNKNOWN_GROUP, problem(g, SequenceRule("a", "{nope}"))?.error)
        assertEquals(SequenceRuleError.UNEVEN_MAP, problem(g, SequenceRule("{big}", "{small:big}"))?.error)
        assertNull(problem(g, SequenceRule("{small}", "{big:small}")))
    }

    // ---- the editor's helpers ----

    @Test
    fun `renaming a group rewrites every placeholder but literal braces`() {
        assertEquals("{b}{{a}}{x:b}{b:x}", renameSequenceGroups("{a}{{a}}{x:a}{a:x}", mapOf("a" to "b")))
        assertTrue(SequenceRule("{a}x", "").usesGroup("a"))
        assertFalse(SequenceRule("{{a}}", "").usesGroup("a"))
    }

    @Test
    fun `codes are written as their characters`() {
        assertEquals("ำx", unescapeSequenceText("\\u0E33x"))
        assertEquals("😀", unescapeSequenceText("\\U0001F600"))
        assertEquals("\\uZZZZ", unescapeSequenceText("\\uZZZZ"))
    }

    @Test
    fun `copying a set renames a clashing group and keeps a shared one`() {
        val set = SequenceRuleSet(
            id = "s",
            characterGroups = mapOf("t" to listOf("1"), "same" to listOf("z")),
            sequenceRules = listOf(SequenceRule("{t}{same}", "{same}{t}")),
        )
        val (groups, rules) = copySequenceRuleSet(set, mapOf("t" to listOf("9"), "same" to listOf("z")), emptyList())
        assertEquals(listOf("9"), groups["t"])
        assertEquals(listOf("1"), groups["s_t"])
        assertEquals(listOf(SequenceRule("{s_t}{same}", "{same}{s_t}")), rules)
    }

    // ---- the shipped sets ----

    private val sho = "้" // ้ mai tho
    private val uu = "ู" // ู sara uu
    private val am = "ำ" // ำ sara am

    @Test
    fun `thai tone typed before its vowel goes after it`() {
        val thai = shipped("thai")
        assertEquals("ก${uu}${sho}", thai.run("ก${sho}${uu}").text)
        // Typed in order it is left alone.
        assertEquals("ก${uu}${sho}", thai.run("ก${uu}${sho}").text)
    }

    @Test
    fun `thai sara am goes after its tone mark either way`() {
        val thai = shipped("thai")
        assertEquals("น${sho}${am}", thai.run("น${am}${sho}").text)
        assertEquals("น${sho}${am}", thai.run("น${sho}${am}").text)
    }

    @Test
    fun `thai nikhahit and sara aa become sara am`() {
        val thai = shipped("thai")
        assertEquals("น${am}", thai.run("นํา").text)
        assertEquals("น${sho}${am}", thai.run("นํ${sho}า").text)
        assertEquals("น${sho}${am}", thai.run("น${sho}ํา").text)
    }

    @Test
    fun `two thai sara e are sara ae`() {
        assertEquals("แก", shipped("thai").run("เเก").text)
    }

    @Test
    fun `thai doubled vowels are left for multitap`() {
        // ิ twice is how a multitap key reaches ี; no rule may take it.
        assertEquals("กิิ", shipped("thai").run("กิิ").text)
    }

    @Test
    fun `lao follows the same order and merges its leading h letters`() {
        val lao = shipped("lao")
        assertEquals("ກູ້", lao.run("ກູ້").text)
        assertEquals("ນ້ຳ", lao.run("ນຳ້").text)
        assertEquals("ນຳ", lao.run("ນໍາ").text)
        assertEquals("ໜ", lao.run("ຫນ").text)
        assertEquals("ໝ", lao.run("ຫມ").text)
        assertEquals("ແ", lao.run("ເເ").text)
    }

    @Test
    fun `arabic hamza pairs give the letter, as NFC does where Unicode composes them`() {
        val ar = shipped("arabic_hamza")
        val canonical = listOf("أ", "إ", "آ", "ؤ", "ئ", "ۓ", "ۂ", "ۀ")
        for (pair in canonical) {
            assertEquals(pair, Normalizer.normalize(pair, Normalizer.Form.NFC), ar.run(pair).text)
        }
        assertEquals("ئ", ar.run("یٔ").text)
        assertEquals("ۀ", ar.run("هٔ").text)
    }

    @Test
    fun `pinyin tone numbers mark the vowel and undo back to the number`() {
        val py = shipped("pinyin_tones")
        assertEquals("mā", py.run("ma1").text)
        assertEquals("nǚ", py.run("nv3").text)
        assertEquals("NǛ", py.run("NV4").text)
        val edit = py.edit("ha", "3")
        assertNotNull(edit)
        assertEquals("ǎ", edit!!.text)
        assertEquals("a3", edit.restore)
    }

    @Test
    fun `accent then letter composes and every pair is precomposed`() {
        val dk = shipped("latin_dead_keys")
        assertEquals("é", dk.run("´e").text)
        assertEquals("ñ", dk.run("~n").text)
        assertEquals("å", dk.run("°a").text)
        assertEquals("~1", dk.run("~1").text)
    }

    @Test
    fun `ligatures fold and can be undone`() {
        val lig = shipped("latin_ligatures")
        assertEquals("æ", lig.run("ae").text)
        assertEquals("ae", lig.edit("a", "e")!!.restore)
    }
}
