// The harness runs on the JVM only and stdout IS its report channel.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.prediction.eval

import com.wasimaster.wmkeyboard.core.prediction.MappedNgramPack
import com.wasimaster.wmkeyboard.core.prediction.MappedTrie
import com.wasimaster.wmkeyboard.core.prediction.NgramPack
import com.wasimaster.wmkeyboard.core.prediction.NgramReranker
import com.wasimaster.wmkeyboard.core.prediction.SecondaryDictionary
import com.wasimaster.wmkeyboard.core.prediction.SeedBigrams
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The typed path's strip for a list of queries, for comparing with the
 * desktop's port of it (`wm-engine`'s `Predictor::suggest`) on the very same
 * word lists. Runs only when asked:
 *
 *     PARITY_DATA=<a data dir holding dict/<lang>/main.wmdict and ngrams.wmng>
 *     PARITY_QUERIES=<file, one query a line> PARITY_OUT=<file>
 *     PARITY_SECONDARY=bn_rom [PARITY_RERANK=1] [PARITY_LEXICON=<json>] \
 *     ./gradlew :app:testEnDebugUnitTest --tests '*DesktopParityDump*' --rerun
 *
 * `--rerun`, because Gradle does not count the environment as an input and
 * would otherwise call the test up to date and leave PARITY_OUT as it was.
 *
 * A query is the text before the caret: the words before the last are the
 * field (seeding its language mix) and the previous words; the last is the
 * word being typed, or nothing when the line ends in a space. The desktop
 * side is `crates/wmk-core/examples/parity.rs` in wmkeyboard-desktop.
 */
class DesktopParityDump {

    @Test
    fun dump() {
        val data = System.getenv("PARITY_DATA")
        val queries = System.getenv("PARITY_QUERIES")
        val out = System.getenv("PARITY_OUT")
        assumeTrue(data != null && queries != null && out != null)
        val dict = File(data, "dict")
        val en = MappedTrie.open(File(dict, "en/main.wmdict")) ?: error("no en list in $dict")
        val seeds = File("src/main/assets/dictionaries/en_bigrams.txt").takeIf { it.exists() }
            ?: File("app/src/main/assets/dictionaries/en_bigrams.txt")
        // PARITY_LEXICON: a user_lexicon.json (a copy: the lexicon writes back).
        val lexicon = UserLexicon(System.getenv("PARITY_LEXICON")?.let(::File))
        val engine = SuggestionEngine(
            en,
            BengaliPhoneticIndex(emptyList()),
            lexicon,
            seedBigrams = seeds.inputStream().use { SeedBigrams.load(it) },
        ).apply {
            englishSources = true
            primaryLanguageId = "en"
            ngramPack = NgramPack.of(MappedNgramPack.open(File(dict, "en/ngrams.wmng")))
            secondaryDictionaries = System.getenv("PARITY_SECONDARY").orEmpty().split(',')
                .filter { it.isNotBlank() }
                .map { id -> SecondaryDictionary(id, MappedTrie.open(File(dict, "$id/main.wmdict")) ?: error(id)) }
            fieldDetectionShift = SuggestionEngine.FIELD_SHIFT_BALANCED
            autocorrectSplits = true; stripSplits = true
            digitSlipCorrections = true
        }
        // The phone's Context rerank (on by default): PARITY_RERANK=1.
        val rerank = System.getenv("PARITY_RERANK") == "1"
        if (rerank) {
            engine.reranker = NgramReranker(
                lexicon,
                seeds.inputStream().use { SeedBigrams.load(it) },
                dictionaryFrequency = { en.frequencyOf(it) },
                ngramPack = { engine.ngramPack },
            )
        }
        val lines = File(queries!!).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        File(out!!).bufferedWriter().use { w ->
            for (line in lines) {
                val words = line.trimEnd().split(' ').filter { it.isNotEmpty() }
                val composing = if (line.endsWith(" ")) "" else words.last()
                val before = if (composing.isEmpty()) words else words.dropLast(1)
                engine.seedFieldContext(before)
                val prev = before.asReversed().map { it.trim(',', '.', '!', '?').lowercase() }
                val got = engine.suggest(
                    composing,
                    previousWord = prev.getOrNull(0),
                    limit = 8,
                    previousWord2 = prev.getOrNull(1),
                    previousWord3 = prev.getOrNull(2),
                    recentWords = before,
                    allowRerank = rerank,
                )
                w.write("{\"q\":${json(line)},\"out\":[${got.joinToString(",") { json(it) }}]}\n")
            }
        }
        println("parity: ${lines.size} queries -> $out")
    }

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
