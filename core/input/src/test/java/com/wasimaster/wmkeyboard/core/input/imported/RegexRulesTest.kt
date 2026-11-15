package com.wasimaster.wmkeyboard.core.input.imported

import com.wasimaster.wmkeyboard.core.layout.InputMethodKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The jquery.ime engine: the rule files that ship, a few of jquery.ime's own
 * test fixtures for them (the whole fixture set, 1,128 cases over these
 * methods, passed when the engine was written), and the parsing of both
 * file formats.
 */
class RegexRulesTest {

    private val shipped = File("../../app/src/main/assets/input_methods/jquery")

    private fun method(id: String): RegexRules {
        val file = shipped.listFiles()!!.first { it.name.substringBeforeLast('.') == id }
        return requireNotNull(JqueryImeFiles.parse(file.readText())) { id }
    }

    @Test fun everyShippedMethodParsesWhole() {
        val files = shipped.listFiles()!!.filter { it.name.endsWith(".js") || it.name.endsWith(".xml") }
        assertTrue(files.size >= 15)
        for (file in files) {
            val rules = JqueryImeFiles.parse(file.readText())
            assertNotNull(file.name, rules)
            assertEquals("${file.name}: rules this platform cannot compile", 0, rules!!.skipped)
            assertEquals(file.name, file.name.substringBeforeLast('.'), rules.id)
            assertTrue(file.name, rules.rules.size > 50)
        }
    }

    @Test fun jqueryImeFixtures() {
        // From jquery.ime's test/jquery.ime.test.fixtures.js.
        val cases = listOf(
            Triple("hi-transliteration", "namaH", "नमः"),
            Triple("hi-transliteration", "madhumaChiyoM", "मधुमक्खियों"),
            Triple("hi-transliteration", "raama", "राम"),
            Triple("hi-transliteration", "agar ", "अगर "),
            Triple("hi-transliteration", "agar~ ", "अगर् "),
            Triple("mr-transliteration", "oMkaara", "ओंकार"),
            Triple("ml-transliteration", "kshau", "ക്ഷൗ"),
            Triple("ta-transliteration", "thikShith", "திக்‌ஷித்"),
            Triple("or-lekhani", "dxiNeSwr", "ଦକ୍ଷିଣେଶ୍ୱର"),
            Triple("kn-transliteration", "gautam", "ಗೌತಮ್"),
            Triple("pa-transliteration", "k`haalasaa", "ਖ਼ਾਲਸਾ"),
            Triple("pa-transliteration", "hoiaa", "ਹੋਇਆ"),
        )
        for ((id, input, output) in cases) assertEquals("$id $input", output, method(id).run(input))
    }

    @Test fun nepaliReadsTheHistoryItsRulesNeed() {
        // The file declares maxKeyLength 1, too short for ([क-ह]़?)्a; on
        // jquery.ime itself "na" types न्अ.
        val ne = method("ne-transliteration")
        assertTrue(ne.maxKeyLength >= 4)
        assertEquals("नमस्ते", ne.run("namaste"))
        assertEquals("घर", ne.run("ghara"))
        assertEquals("नेपाल", ne.run("nepaala"))
        assertEquals("ख", ne.run("kha"))
    }

    @Test fun swanalekhaFromIndicKeyboardsXml() {
        val sw = method("ml-swanalekha")
        assertEquals("നന്നി", sw.run("nanni"))
        assertEquals("എന്താ", sw.run("enthaa"))
    }

    @Test fun theSignKeysAreFound() {
        assertTrue('`' in method("ne-transliteration").keys)
        assertTrue('.' in method("ne-transliteration").keys)
        assertTrue('~' in method("hi-transliteration").keys)
        // Digits stay digits whatever the rules say about them.
        assertTrue(method("ne-transliteration").keys.none { it.isDigit() })
    }

    @Test fun aSmallFileAndItsEscapes() {
        val js = """
            ( function ( ${'$'} ) {
                var t = {
                    id: 'xx-test', name: 'Test', // a comment
                    maxKeyLength: 2, contextLength: 1,
                    patterns: [
                        /* comment */ [ 'क्h', 'ख्' ],
                        [ 'क्a', 'क' ],
                        [ 'x', 'k', 'क्ष' ],
                        [ 'k', 'क्' ],
                        [ '(z)(y)?q', '${'$'}2${'$'}1' ],
                    ]
                };
                ${'$'}.ime.register( t );
            }( jQuery ) );
        """.trimIndent()
        assertTrue(JqueryImeFiles.looks(js))
        val r = JqueryImeFiles.parseJs(js)!!
        assertEquals("xx-test", r.id)
        assertEquals(5, r.rules.size)
        assertEquals("ख्", r.run("kh"))
        assertEquals("क", r.run("ka"))
        // The context rule only fires after a k.
        assertEquals("क्क्ष", r.run("kx"))
        assertEquals("x", r.run("x"))
        // An unmatched group writes nothing, as in JavaScript.
        assertEquals("z", r.run("zq"))
        // A group the pattern does not have stays literal, as in JavaScript.
        assertEquals("${'$'}3", JqueryImeFiles.parseJs(js.replace("'${'$'}2${'$'}1'", "'${'$'}3'"))!!.run("zq"))
    }

    @Test fun xmlAttributesAndAltGrRules() {
        val xml = """
            <?xml version="1.0"?>
            <inputmethod id="xx-x" name="X" description="" author="" version="1" contextLength="0" maxKeyLength="2">
              <pattern input="a" replacement="&lt;A&gt;"/>
              <pattern input="b" replacement="B" altGr="true"/>
            </inputmethod>
        """.trimIndent()
        val r = JqueryImeFiles.parse(xml)!!
        assertEquals(1, r.rules.size)
        assertEquals("<A>", r.run("a"))
    }

    @Test fun longestMatch() {
        assertEquals(4, JqueryImeFiles.longestMatch("([क-ह]़?)्a"))
        assertEquals(5, JqueryImeFiles.longestMatch("र्र्r"))
        assertEquals(2, JqueryImeFiles.longestMatch("(ओo|उu)"))
        assertEquals(2, JqueryImeFiles.longestMatch("\\\\([A-Za-z\\>_~\\.0-9])"))
    }

    @Test fun anImportedRuleFileBecomesARegexMethod() {
        val text = File(shipped, "ne-transliteration.js").readBytes()
        val result = InputMethodSources.convert(listOf(SourceFile("ne-transliteration.js", text)))
        assertTrue("$result", result is Conversion.Ready)
        val m = (result as Conversion.Ready).method
        assertEquals(MethodFormat.JQUERY, m.format)
        assertEquals(InputMethodKind.REGEX, m.kind)
        assertEquals("ne", m.languageTag)
        assertTrue(m.shifted)
        assertTrue('a' in m.keys && '`' in m.keys)
    }

    @Test fun notARuleFile() {
        assertNull(JqueryImeFiles.parse("var x = 1;"))
        assertNull(JqueryImeFiles.parseJs("\$.ime.register({ id: 'x', patterns: [] })"))
    }
}
