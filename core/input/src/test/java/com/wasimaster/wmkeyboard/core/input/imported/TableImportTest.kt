package com.wasimaster.wmkeyboard.core.input.imported

import com.wasimaster.wmkeyboard.core.layout.InputMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.cin` tables, Rime schemas and dictionaries, and the spelling algebra, on
 * small files written for the test.
 */
class TableImportTest {

    private fun file(name: String, text: String) = SourceFile(name, text.toByteArray())

    private fun ready(vararg files: SourceFile): ConvertedMethod {
        val result = InputMethodSources.convert(files.toList())
        assertTrue("expected a method, got $result", result is Conversion.Ready)
        return (result as Conversion.Ready).method
    }

    // ---- .cin ----

    private val cin = """
        %gen_inp
        # a comment
        %ename testcj
        %cname 測試倉頡
        %selkey 1234567890
        %keyname begin
        a 日
        b 月
        , ，
        %keyname end
        %chardef begin
        a	日
        ab	明
        ab	昍
        B	大寫
        ,	，
        %chardef end
    """.trimIndent()

    @Test
    fun `a cin table keeps its names, caps and rows in file order`() {
        val table = CinTable.parse(cin)
        assertNotNull(table)
        table!!
        assertEquals("測試倉頡", table.name)
        assertEquals("日", table.keyNames['a'])
        assertEquals("，", table.keyNames[','])
        assertEquals(listOf("a" to "日", "ab" to "明", "ab" to "昍", "b" to "大寫", "," to "，"), table.rows)
    }

    @Test
    fun `case is folded unless the table keeps it`() {
        val kept = CinTable.parse(cin.replace("%selkey", "%keep_key_case\n%selkey"))!!
        assertTrue("B" to "大寫" in kept.rows)
    }

    @Test
    fun `a cin converts to a table method with its punctuation keys`() {
        val method = ready(file("test.cin", cin))
        assertEquals(MethodFormat.CIN, method.format)
        assertEquals(InputMethodKind.TABLE, method.kind)
        assertTrue(',' in method.keys)
        assertEquals("日", method.keyNames['a'])
        assertEquals(5, method.entries)
    }

    @Test
    fun `a Big5 table is read`() {
        val big5 = cin.replace("%encoding UTF-8", "").toByteArray(charset("Big5"))
        val method = InputMethodSources.convert(listOf(SourceFile("big5.cin", big5)))
        assertTrue(method is Conversion.Ready)
        assertEquals("測試倉頡", (method as Conversion.Ready).method.name)
    }

    @Test
    fun `a jyutping table is guessed as Cantonese`() {
        val method = ready(file("jyutping.cin", cin.replace("testcj", "jyutping")))
        assertEquals("yue", method.languageTag)
    }

    // ---- spelling algebra ----

    @Test
    fun `xform replaces, derive adds, erase drops, xlit maps characters`() {
        val algebra = SpellingAlgebra.of(
            listOf(
                "derive/^([zcs])h/$1/",
                "xform/^([nl])v/$1ü/",
                "erase/^xx$/",
                "xlit/abc/ABC/",
            ),
        )
        assertEquals(listOf("zhAng", "zAng"), algebra.spell("zhang").map { it.text })
        assertEquals(listOf("lü"), algebra.spell("lv").map { it.text })
        assertTrue(algebra.spell("xx").isEmpty())
    }

    @Test
    fun `abbreviations rank behind real spellings and keep their kind`() {
        val algebra = SpellingAlgebra.of(listOf("abbrev/^([a-z]).+$/$1/"))
        val spellings = algebra.spell("hao")
        assertEquals("hao", spellings.first().text)
        assertEquals(SpellingAlgebra.Kind.ABBREVIATION, spellings.last().kind)
        assertEquals("h", spellings.last().text)
    }

    @Test
    fun `a replacement reads braces, a digit after a group, and case changes`() {
        val algebra = SpellingAlgebra.of(listOf("xform/([a-z])v$/\${1}4/", "xform/^(.)/\\U$1\\E/"))
        assertEquals("Ga4", algebra.spell("gav").single().text)
    }

    @Test
    fun `a rule that does not parse is reported and skipped`() {
        val problems = ArrayList<SpellingAlgebra.Problem>()
        SpellingAlgebra.of(listOf("xform/(/x/", "bogus"), problems)
        assertEquals(2, problems.size)
    }

    @Test
    fun `key caps come from an xlit over lowercase keys`() {
        val names = SpellingAlgebra.keyNames(
            listOf("xlit|ABC|日月金|"),
            listOf("xlit|abc|日月金|"),
        )
        assertEquals("月", names['b'])
    }

    // ---- Rime ----

    private val schema = """
        schema:
          schema_id: test_pinyin
          name: "測試拼音"
        engine:
          translators:
            - punct_translator
            - script_translator
            - table_translator@side
        speller:
          alphabet: zyxwvutsrqponmlkjihgfedcba
          delimiter: " '"
          algebra:
            __patch:
              - shared:/abbreviation
              - shared:/missing_but_optional?
        translator:
          dictionary: test_pinyin
          preedit_format:
            - "xform/([nl])v/${'$'}1ü/"
            - "xlit|q|\/|"
    """.trimIndent()

    private val shared = """
        abbreviation:
          __append:
            - abbrev/^([a-z]).+$/$1/
    """.trimIndent()

    private val dict = """
        # Rime dictionary
        ---
        name: test_pinyin
        version: "1"
        import_tables:
          - test_pinyin.extra
        vocabulary: essay
        ...

        你	ni
        好	hao
        好	hao	50%
        號	hao	10%
        你好	ni hao
    """.trimIndent()

    private val extra = """
        ---
        name: test_pinyin.extra
        ...
        中	zhong	900
        國	guo	800
    """.trimIndent()

    private val essay = "你\t1000\n好\t800\n你好\t300\n中國\t500\n"

    @Test
    fun `a script schema becomes a reading method with its algebra and vocabulary`() {
        val method = ready(
            file("test_pinyin.schema.yaml", schema),
            file("shared.yaml", shared),
            file("test_pinyin.dict.yaml", dict),
            file("test_pinyin.extra.dict.yaml", extra),
            file("essay.txt", essay),
        )
        assertEquals(MethodFormat.RIME, method.format)
        assertEquals(InputMethodKind.READING, method.kind)
        assertEquals("測試拼音", method.name)
        val payload = method.payload as MethodPayload.Reading
        // The abbreviation rule from the shared file reached the spellings.
        val h = payload.spellings["h"]
        assertNotNull(h)
        assertEquals(SpellingAlgebra.Kind.ABBREVIATION, h!!.first().kind)
        assertEquals("'", payload.delimiters)
        // Rows join their syllables; 中國 came from the vocabulary.
        val rows = payload.rows.zip(payload.weights.toList())
        assertTrue(rows.any { it.first == ("zhongguo" to "中國") })
        // A row with no weight takes the vocabulary's; a percentage is a share of it.
        assertEquals(1000, rows.first { it.first == ("ni" to "你") }.second)
        assertEquals(400, rows.first { it.first.second == "好" && it.second != 800 }.second)
        // Nothing is reported missing for an optional reference.
        assertTrue(method.notes.none { it is ImportNote.MissingFiles })
    }

    @Test
    fun `the vocabulary is reported missing when it was not picked`() {
        val method = ready(
            file("test_pinyin.schema.yaml", schema),
            file("shared.yaml", shared),
            file("test_pinyin.dict.yaml", dict),
            file("test_pinyin.extra.dict.yaml", extra),
        )
        val missing = method.notes.filterIsInstance<ImportNote.MissingFiles>().flatMap { it.names }
        assertEquals(listOf("essay.txt"), missing)
    }

    @Test
    fun `a schema without its dictionary is refused by name`() {
        val result = InputMethodSources.convert(listOf(file("test_pinyin.schema.yaml", schema)))
        assertEquals(Conversion.Refused(ImportRefusal.NO_DICTIONARY, "test_pinyin.dict.yaml"), result)
    }

    @Test
    fun `a dictionary of plain codes without a schema is a table`() {
        val method = ready(file("codes.dict.yaml", "---\nname: codes\n...\n日\ta\n明\tab\t10\n"))
        assertEquals(InputMethodKind.TABLE, method.kind)
        val payload = method.payload as MethodPayload.Table
        assertEquals(listOf("a" to "日", "ab" to "明"), payload.rows)
    }

    @Test
    fun `a code table spells its uncoded phrases and the vocabulary's with its encoder`() {
        // Wubi 86's rules: two characters take two letters of each, three take
        // one of the first two and two of the last, four and more one of the
        // first three and the last. A z code is excluded from building phrases.
        val wubi = """
            ---
            name: wubi
            use_preset_vocabulary: true
            encoder:
              exclude_patterns:
                - '^z.*${'$'}'
              rules:
                - length_equal: 2
                  formula: "AaAbBaBb"
                - length_equal: 3
                  formula: "AaBaCaCb"
                - length_in_range: [4, 10]
                  formula: "AaBaCaZa"
            ...
            工	aaaa
            工	zzz
            人	wwww
            民	nav
            中	khk
            国	lgyi
            人民
        """.trimIndent()
        val method = ready(file("wubi.dict.yaml", wubi), file("essay.txt", "中国\t900\n中国人民\t80\n人民\t500\n"))
        val payload = method.payload as MethodPayload.Table
        val rows = payload.rows.zip(payload.weights.toList()).toMap()
        assertEquals(500, rows["wwna" to "人民"])
        assertEquals(900, rows["khlg" to "中国"])
        assertEquals(80, rows["klwn" to "中国人民"])
        // The z code never joined a phrase.
        assertTrue(payload.rows.none { it.second.length > 1 && it.first.startsWith("z") })
    }

    @Test
    fun `a formula counts from the back past U`() {
        assertEquals(listOf(0 to 0, -1 to -1, -2 to 1), RimeTableEncoder.formula("AaZzYb"))
        assertNull(RimeTableEncoder.formula("Aa1"))
        assertNull(RimeTableEncoder.formula("aA"))
    }

    @Test
    fun `a reading schema that spells with the space bar marks it as a key`() {
        val bopomofo = """
            schema:
              schema_id: test_bopomofo
            engine:
              translators:
                - script_translator
            speller:
              alphabet: "1qaz "
              algebra:
                - xform/^ba1${'$'}/1 /
            translator:
              dictionary: test_bopomofo
        """.trimIndent()
        val d = "---\nname: test_bopomofo\n...\n八\tba1\n"
        val method = ready(file("test_bopomofo.schema.yaml", bopomofo), file("test_bopomofo.dict.yaml", d))
        val payload = method.payload as MethodPayload.Reading
        assertTrue(payload.spellings.keys.any { ' ' in it })
        assertTrue(method.notes.none { it is ImportNote.SpaceKey })
    }

    @Test
    fun `a slash escaped in a double-quoted YAML 1_2 string is read`() {
        val parsed = RimeFiles.loadYaml(RimeFiles.yaml12Escapes("a: \"x\\/y\"\nb: 'p\\/q'\n")) as Map<*, *>
        assertEquals("x/y", parsed["a"])
        assertEquals("p\\/q", parsed["b"])
    }

    @Test
    fun `a dictionary is not mistaken for a schema for mentioning one`() {
        val text = "# schema: other\n# schema_id: other\n---\nname: d\n...\n日\ta\n"
        assertTrue(RimeFiles.looksLikeDict("d.dict.yaml", text))
        assertTrue(!RimeFiles.looksLikeSchema("d.dict.yaml", text))
    }

    // ---- sniffing ----

    @Test
    fun `files that are none of the three formats are refused`() {
        val result = InputMethodSources.convert(listOf(file("notes.txt", "hello")))
        assertEquals(ImportRefusal.NOT_A_METHOD, (result as Conversion.Refused).reason)
    }

    @Test
    fun `an m17n file wins over other picked files, which are named back`() {
        val method = ready(
            file("x.mim", "(input-method xx t1)\n(map (m (\"a\" \"A\")))\n(state (init (m)))"),
            file("readme.txt", "hello"),
        )
        assertEquals(MethodFormat.M17N, method.format)
        assertEquals(InputMethodKind.RULES, method.kind)
        assertEquals(listOf("readme.txt"), method.notes.filterIsInstance<ImportNote.UnusedFiles>().single().names)
        assertNull(method.notes.filterIsInstance<ImportNote.Unsupported>().firstOrNull())
    }
}
