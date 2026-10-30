package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.input.composer.reference.KotlinCjkNgrams
import com.wasimaster.wmkeyboard.core.input.composer.reference.KotlinConversionDictionary
import com.wasimaster.wmkeyboard.core.input.composer.reference.KotlinLattice
import java.io.File
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The conversion decoder and its tables in Rust against the Kotlin they
 * replaced: every candidate of every decode, in order, with its consumed length,
 * reading and repair flag, and its score within rounding.
 *
 * Generated tables and graphs here, with astral characters, learned history and
 * every option the composers set; the real packs too when the data repo sits
 * beside the app. The same decode through a table mapped from its `.wmcd` file
 * gives the same answer, since [open]'s table is what the composers run on.
 */
class NativeCjkParityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun reset() {
        HanLevels.table = HanLevels.EMPTY
    }

    private val syllables = listOf(
        "a", "ai", "an", "ba", "bu", "de", "di", "hao", "he", "hen", "hua", "ni", "nin", "wo", "xi", "xian", "zh", "zhong", "z", "h",
    )
    private val han = "我你好的不是中国人和西安先现呢您饿大小上下爱啊把琌硂𠮷𬘬𠀋乙起".let { s ->
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            out.add(s.substring(i, i + n))
            i += n
        }
        out
    }

    private fun packLines(r: Random, rows: Int): List<String> = List(rows) {
        val units = 1 + r.nextInt(3)
        val reading = (0 until units).joinToString("") { syllables[r.nextInt(syllables.size)] }
        val word = (0 until 1 + r.nextInt(units + 1)).joinToString("") { han[r.nextInt(han.size)] }
        "$reading\t$word\t${r.nextInt(1000)}"
    }.sortedBy { it.substringBefore('\t') } // As every pack is: Kotlin's sort of an unsorted one was not stable.

    private fun pairLines(r: Random, rows: Int): List<String> {
        val words = (0 until 60).map { (0 until 1 + r.nextInt(2)).joinToString("") { han[r.nextInt(han.size)] } }.distinct()
        return words.map { "$it\t${1 + r.nextInt(500)}" } +
            List(rows) { "${words[r.nextInt(words.size)]}\t${words[r.nextInt(words.size)]}\t${1 + r.nextInt(40)}" }
    }

    private class Case(
        val graph: Lattice.Graph,
        val opts: Lattice.Opts,
        val levels: Boolean,
        val context: String?,
    )

    private fun randomCase(r: Random): Case {
        val units = 1 + r.nextInt(6)
        val consumed = IntArray(units + 1) { it * 2 }
        val arcs = Array(units + 1) { node ->
            if (node == units) {
                emptyList()
            } else {
                List(1 + r.nextInt(3)) { i ->
                    val to = if (i > 0 && node + 2 <= units && r.nextInt(4) == 0) node + 2 else node + 1
                    Lattice.Arc(
                        to,
                        syllables[r.nextInt(syllables.size)],
                        if (i == 0) 0.0 else -r.nextDouble() * 3,
                        guess = i > 0 && r.nextBoolean(),
                        correction = i > 0 && r.nextInt(4) == 0,
                    )
                }
            }
        }
        val levels = r.nextBoolean()
        val opts = Lattice.Opts(
            maxAmbiguousSpan = if (r.nextBoolean()) 2 else Int.MAX_VALUE,
            limit = if (r.nextBoolean()) 12 else 128,
            charPerUnit = r.nextBoolean(),
            stitch = if (r.nextBoolean()) 3 else 0,
            coverageOrder = r.nextBoolean(),
            hanLevels = levels,
        )
        return Case(Lattice.Graph(consumed, arcs), opts, levels, if (r.nextInt(3) == 0) han[r.nextInt(han.size)] else null)
    }

    private fun oracleOpts(o: Lattice.Opts) = KotlinLattice.Opts(
        maxWordUnits = o.maxWordUnits,
        maxAmbiguousSpan = o.maxAmbiguousSpan,
        spanCandCap = o.spanCandCap,
        beam = o.beam,
        limit = o.limit,
        charPerUnit = o.charPerUnit,
        overLengthPenalty = o.overLengthPenalty,
        stitch = o.stitch,
        stitchBonus = o.stitchBonus,
        coverageOrder = o.coverageOrder,
        pathsPerStretch = o.pathsPerStretch,
        correctionMargin = o.correctionMargin,
        wordPenalty = if (o.hanLevels) HanLevels::penalty else null,
        pathMargin = o.pathMargin,
        stretchCap = o.stretchCap,
    )

    private fun oracleGraph(g: Lattice.Graph) = KotlinLattice.Graph(
        g.consumed,
        Array(g.arcs.size) { node ->
            g.arcs[node].map { KotlinLattice.Arc(it.to, it.reading, it.penalty, it.guess, it.correction) }
        },
    )

    /** What the user taught, in both the shapes the two decoders take it. */
    private class History(
        val picks: Map<String, Map<String, Int>>,
        val pairs: Map<String, Map<String, Int>>,
        val adjust: WordAdjust?,
    ) {
        val phrases = picks.filter { (_, w) -> w.keys.any { it.codePointCount(0, it.length) >= 2 } }.keys.sorted()
        val data = Lattice.UserData.of(picks, adjust, pairs)

        fun hasPrefix(prefix: String): Boolean = phrases.any { it.startsWith(prefix) }

        fun userWords(all: Boolean) = if (all) {
            KotlinLattice.UserWords({ hasPrefix(it) }, pairs)
        } else {
            KotlinLattice.UserWords.NONE
        }
    }

    private fun randomHistory(r: Random): History {
        val picks = HashMap<String, Map<String, Int>>()
        repeat(r.nextInt(12)) {
            val reading = (0 until 1 + r.nextInt(2)).joinToString("") { syllables[r.nextInt(syllables.size)] }
            picks[reading] = (0 until 1 + r.nextInt(3)).associate {
                (0 until 1 + r.nextInt(2)).joinToString("") { han[r.nextInt(han.size)] } to 1 + r.nextInt(9)
            }
        }
        val pairs = HashMap<String, Map<String, Int>>()
        repeat(r.nextInt(8)) { pairs[han[r.nextInt(han.size)]] = mapOf(han[r.nextInt(han.size)] to 1 + r.nextInt(5)) }
        val adjust = if (r.nextBoolean()) {
            WordAdjust(setOf(han[r.nextInt(han.size)]), mapOf(han[r.nextInt(han.size)] to -1 - r.nextInt(3)))
        } else {
            null
        }
        return History(picks, pairs, adjust)
    }

    private fun assertSame(what: String, kotlin: List<KotlinLattice.Cand>, native: List<Lattice.Cand>) {
        assertEquals("$what: texts", kotlin.map { it.text }, native.map { it.text })
        assertEquals("$what: consumed", kotlin.map { it.consumed }, native.map { it.consumed })
        assertEquals("$what: readings", kotlin.map { it.reading }, native.map { it.reading })
        assertEquals("$what: repairs", kotlin.map { it.corrected }, native.map { it.corrected })
        for (i in kotlin.indices) assertEquals("$what: score of ${kotlin[i].text}", kotlin[i].score, native[i].score, 1e-9)
    }

    @Test
    fun `every decode matches the Kotlin one`() {
        val r = Random(11)
        val lines = packLines(r, 3000)
        val pairs = pairLines(r, 2000)
        val levelsLine = sequenceOf("1\t我你好的", "2\t不是中", "3\t国人和")
        HanLevels.table = HanLevels.parse(levelsLine)
        val oracleDict = KotlinConversionDictionary.parse(lines.asSequence())
        val oracleNgrams = KotlinCjkNgrams.parse(pairs.asSequence())
        val dict = ConversionDictionary.parse(lines.asSequence())
        val ngrams = CjkNgrams.parse(pairs.asSequence())
        var candidates = 0
        repeat(2000) { n ->
            val case = randomCase(r)
            val withPairs = n % 2 == 0
            val history = randomHistory(r)
            val scope = when (n % 3) {
                0 -> Lattice.UserScope.ALL
                1 -> Lattice.UserScope.PICKS
                else -> Lattice.UserScope.MENU
            }
            val picks = scope != Lattice.UserScope.MENU
            val kotlin = KotlinLattice.decode(
                oracleGraph(case.graph),
                oracleDict,
                if (withPairs) oracleNgrams else KotlinCjkNgrams.EMPTY,
                oracleOpts(case.opts),
                learned = { reading -> if (picks) history.picks[reading] else null },
                adjust = history.adjust,
                context = case.context,
                user = history.userWords(scope == Lattice.UserScope.ALL),
            )
            val native = Lattice.decode(
                case.graph,
                dict,
                if (withPairs) ngrams else CjkNgrams.EMPTY,
                case.opts,
                history.data,
                scope,
                case.context,
            )
            assertSame("decode $n", kotlin, native)
            candidates += native.size
        }
        assertTrue("the decodes found something: $candidates", candidates > 2000)
    }

    @Test
    fun `the tables answer as the Kotlin ones did`() {
        val r = Random(5)
        val lines = listOf("# a comment", "", "broken", "\tno reading", "no word\t", "a\t我") + packLines(r, 2000)
        val kotlin = KotlinConversionDictionary.parse(lines.asSequence())
        val native = ConversionDictionary.parse(lines.asSequence())
        assertEquals(kotlin.size, native.size)
        assertEquals(kotlin.totalFreq, native.totalFreq)
        for (row in 0 until kotlin.size) {
            assertEquals(kotlin.reading(row), native.reading(row))
            assertEquals(kotlin.word(row), native.word(row))
            assertEquals(kotlin.wordLength(row), native.wordLength(row))
            assertEquals(kotlin.frequency(row), native.frequency(row))
        }
        val readings = (0 until kotlin.size).map { kotlin.reading(it) }.distinct()
        for (reading in readings + "zzz") {
            assertEquals(reading, kotlin.exact(reading), native.exact(reading))
            assertEquals(reading, kotlin.candidates(reading, 12), native.candidates(reading, 12))
            assertEquals(reading, kotlin.rowsFor(reading).toList(), native.rowsFor(reading).toList())
            assertEquals(reading, kotlin.prefixRange(reading).toList(), native.prefixRange(reading).toList())
        }
        for (q in listOf(0.0, 0.25, 0.5, 0.99, 1.0)) assertEquals(kotlin.frequencyQuantile(q), native.frequencyQuantile(q))

        val rows = List(200) {
            val reading = if (r.nextBoolean()) readings[r.nextInt(readings.size)] else "new${r.nextInt(30)}"
            val word = han[r.nextInt(han.size)]
            Triple(reading, word, r.nextInt(2000))
        }
        val raises = rows.map { r.nextBoolean() }
        val kotlinPlus = kotlin.plus(rows.indices.map { KotlinConversionDictionary.Row(rows[it].first, rows[it].second, rows[it].third, raises[it]) })
        val nativePlus = native.plus(rows.indices.map { ConversionDictionary.Row(rows[it].first, rows[it].second, rows[it].third, raises[it]) })
        assertEquals(kotlinPlus.size, nativePlus.size)
        // As sets: the Kotlin rebuild's sort was not stable, so its ties came out in no set order.
        for (reading in rows.map { it.first }.distinct()) {
            assertEquals(reading, kotlinPlus.exact(reading).sorted(), nativePlus.exact(reading).sorted())
        }
        val same = native.plus(listOf(ConversionDictionary.Row(native.reading(0), native.word(0), 1)))
        assertTrue("nothing new is the table itself", same === native)

        val visited = ArrayList<String>()
        var row = 0
        native.forEachRow { reading, length, frequency ->
            assertEquals(native.reading(row), reading)
            assertEquals(native.wordLength(row), length)
            assertEquals(native.frequency(row), frequency)
            visited.add(reading)
            row++
        }
        assertEquals(native.size, visited.size)
        var kept = 0
        native.forEachRow(floor = 500) { _, _, frequency ->
            assertTrue(frequency >= 500)
            kept++
        }
        assertEquals((0 until native.size).count { native.frequency(it) >= 500 }, kept)

        val pairs = pairLines(r, 1000)
        val kotlinNgrams = KotlinCjkNgrams.parse(pairs.asSequence())
        val ngrams = CjkNgrams.parse(pairs.asSequence())
        for (line in pairs) {
            val cells = line.split('\t')
            assertEquals(kotlinNgrams.unigram(cells[0]), ngrams.unigram(cells[0]))
            assertEquals(kotlinNgrams.successors(cells[0]), ngrams.successors(cells[0]))
            assertEquals(kotlinNgrams.completions(cells[0], 8), ngrams.completions(cells[0], 8))
            if (cells.size == 3) assertEquals(kotlinNgrams.bigram(cells[0], cells[1]), ngrams.bigram(cells[0], cells[1]))
            val prev = if (cells.size == 3) cells[1] else null
            assertEquals(kotlinNgrams.logProbability(prev, cells[0], 3, 99), ngrams.logProbability(prev, cells[0], 3, 99), 1e-12)
        }
    }

    @Test
    fun `a pack mapped from its file answers as one parsed`() {
        val r = Random(3)
        val lines = packLines(r, 1000)
        val pairs = pairLines(r, 400)
        val packFile = tmp.newFile("pack.tsv").apply { writeText(lines.joinToString("\n")) }
        val pairFile = tmp.newFile("pairs.tsv").apply { writeText(pairs.joinToString("\n")) }
        val parsed = ConversionDictionary.parse(lines.asSequence())
        val parsedPairs = CjkNgrams.parse(pairs.asSequence())
        for (pass in 1..2) {
            val mapped = ConversionDictionary.open(packFile)
            val mappedPairs = CjkNgrams.open(pairFile)
            assertTrue("pass $pass wrote the tables", File("${packFile.path}.wmcd").isFile && File("${pairFile.path}.wmcd").isFile)
            assertEquals(parsed.size, mapped.size)
            repeat(200) {
                val case = randomCase(r)
                assertEquals(
                    Lattice.decode(case.graph, parsed, parsedPairs, case.opts).map { it.text },
                    Lattice.decode(case.graph, mapped, mappedPairs, case.opts).map { it.text },
                )
            }
        }
        // A changed pack is read again, not answered from the old table.
        packFile.writeText("xin\t新\t5\n")
        packFile.setLastModified(packFile.lastModified() + 5000)
        assertEquals(listOf("新"), ConversionDictionary.open(packFile).exact("xin"))
    }

    @Test
    fun `the real packs decode the same`() {
        val data = File(System.getProperty("user.home"), "Work/wmkeyboard-data/cjk")
        val pack = File(data, "pinyin_v2.tsv")
        val pairs = File(data, "pinyin_ngrams.tsv")
        val eval = File(data, "eval/pinyin_eval.tsv")
        assumeTrue("data repo not on this machine", pack.isFile && pairs.isFile && eval.isFile)
        val asset = File("src/main/assets/dictionaries/pinyin_syllables.txt")
        assumeTrue(asset.isFile)
        val valid = PinyinSyllables.parse(asset.readText().lineSequence())
        val oracleDict = pack.bufferedReader().useLines(KotlinConversionDictionary::parse)
        val oracleNgrams = pairs.bufferedReader().useLines(KotlinCjkNgrams::parse)
        val dict = pack.bufferedReader().useLines(ConversionDictionary::parse)
        val ngrams = pairs.bufferedReader().useLines(CjkNgrams::parse)
        val opts = Lattice.Opts(limit = 128, coverageOrder = true)
        val clauses = eval.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }.take(300)
        for (clause in clauses) {
            val segs = PinyinSyllables.segment(clause, valid)
            if (segs.isEmpty()) continue
            val graph = Lattice.graph(Lattice.input(segs.map { it.syllable }, segs.map { it.inputLen }))
            assertSame(
                clause,
                KotlinLattice.decode(oracleGraph(graph), oracleDict, oracleNgrams, oracleOpts(opts)),
                Lattice.decode(graph, dict, ngrams, opts),
            )
        }
    }
}
