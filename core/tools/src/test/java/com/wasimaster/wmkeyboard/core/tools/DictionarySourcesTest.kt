package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.vocab.VocabSense
import com.wasimaster.wmkeyboard.core.vocab.VocabTranslation
import com.wasimaster.wmkeyboard.core.vocab.VocabWord
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionarySourcesTest {

    @Test
    fun `source list round-trips with order and switches`() {
        // Every source, so decode has none missing to append: a reordered list with switches off comes back as it was.
        val choices = listOf(
            DictionarySourceChoice(DictionarySource.DICTIONARY_API),
            DictionarySourceChoice(DictionarySource.WIKTIONARY, enabled = false),
            DictionarySourceChoice(DictionarySource.IMPORTED),
            DictionarySourceChoice(DictionarySource.VOCAB_PACKS),
            DictionarySourceChoice(DictionarySource.OFFLINE_WORDNET, enabled = false),
            DictionarySourceChoice(DictionarySource.WIKTIONARY_REST, enabled = false),
            DictionarySourceChoice(DictionarySource.OFFLINE_WIKTIONARY),
            DictionarySourceChoice(DictionarySource.OFFLINE_BENGALI, enabled = false),
            DictionarySourceChoice(DictionarySource.URBAN_DICTIONARY),
        )
        assertEquals(DictionarySource.entries.size, choices.size)
        assertEquals(choices, DictionarySources.decode(DictionarySources.encode(choices)))
    }

    @Test
    fun `unknown ids drop and missing sources join at the end, on unless they start off`() {
        assertEquals(
            listOf(
                DictionarySourceChoice(DictionarySource.DICTIONARY_API),
                DictionarySourceChoice(DictionarySource.WIKTIONARY, enabled = false),
                DictionarySourceChoice(DictionarySource.VOCAB_PACKS),
                DictionarySourceChoice(DictionarySource.WIKTIONARY_REST),
                DictionarySourceChoice(DictionarySource.OFFLINE_WIKTIONARY),
                DictionarySourceChoice(DictionarySource.OFFLINE_WORDNET),
                DictionarySourceChoice(DictionarySource.OFFLINE_BENGALI),
                DictionarySourceChoice(DictionarySource.IMPORTED),
                DictionarySourceChoice(DictionarySource.URBAN_DICTIONARY, enabled = false),
            ),
            DictionarySources.decode("dictionary_api,-bogus,-wiktionary,dictionary_api"),
        )
    }

    @Test
    fun `default asks the packs first, the free dictionary API last online, then the offline and imported dictionaries`() {
        val order = DictionarySources.DEFAULT.filter { it.enabled }.map { it.source }
        assertEquals(DictionarySource.VOCAB_PACKS, order.first())
        assertEquals(DictionarySource.DICTIONARY_API, order.last { it.online })
        assertEquals(
            listOf(
                DictionarySource.OFFLINE_WIKTIONARY, DictionarySource.OFFLINE_WORDNET,
                DictionarySource.OFFLINE_BENGALI, DictionarySource.IMPORTED,
            ),
            order.dropWhile { it != DictionarySource.DICTIONARY_API }.drop(1),
        )
        assertEquals(
            listOf(DictionarySource.URBAN_DICTIONARY),
            DictionarySources.DEFAULT.filterNot { it.enabled }.map { it.source },
        )
    }

    @Test
    fun `every offline dictionary source names a catalogue pack and is not online`() {
        for (source in DictionarySource.entries.filter { it.packId != null }) {
            assertTrue(source.name, !source.online)
            assertTrue(source.name, com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaryCatalog.byId(source.packId!!) != null)
        }
    }

    @Test
    fun `vocab record becomes one entry grouped by part of speech`() {
        val record = VocabWord(
            word = "run",
            ipa = mapOf("uk" to "/rʌn/"),
            audio = mapOf("us" to "https://example.org/run.mp3"),
            senses = listOf(
                VocabSense(pos = "verb", definition = "To move fast on foot.", example = " She runs. ", synonyms = listOf("sprint")),
                VocabSense(pos = "noun", definition = "An act of running."),
                VocabSense(pos = "verb", definition = "To operate."),
                VocabSense(pos = "verb", definition = "  "),
            ),
            synonyms = listOf("dash"),
        )
        val entry = DictionaryLookup.fromVocabWord(record).single()
        assertEquals("run", entry.word)
        assertEquals("/rʌn/", entry.phonetic)
        assertEquals("https://example.org/run.mp3", entry.audioUrl)
        assertEquals(listOf("verb", "noun"), entry.meanings.map { it.partOfSpeech })
        assertEquals(
            listOf(
                DictDefinition("To move fast on foot.", "She runs.", listOf("sprint")),
                DictDefinition("To operate.", null, emptyList()),
            ),
            entry.meanings[0].definitions,
        )
        // Two parts of speech: the record-wide synonyms belong to neither.
        assertTrue(entry.meanings.all { it.synonyms.isEmpty() })
    }

    @Test
    fun `vocab record with no definitions is no entry`() {
        assertTrue(DictionaryLookup.fromVocabWord(VocabWord(word = "x", audio = mapOf("us" to "x.mp3"))).isEmpty())
    }

    private val found = listOf(DictEntry("word", "", null, listOf(DictMeaning("noun", listOf(DictDefinition("A unit.", null, emptyList())), emptyList(), emptyList()))))

    @Test
    fun `lookup falls through a failing source and one without the word`() = runBlocking {
        val asked = ArrayList<DictionarySource>()
        val result = DictionaryLookup.resolve(
            " word ",
            listOf(DictionarySource.DICTIONARY_API, DictionarySource.VOCAB_PACKS, DictionarySource.WIKTIONARY),
        ) { source, word ->
            asked += source
            assertEquals("word", word)
            when (source) {
                DictionarySource.DICTIONARY_API -> throw IOException("522")
                DictionarySource.VOCAB_PACKS -> emptyList()
                else -> found
            }
        }
        assertEquals(DictionaryLookup.Result.Found(found, DictionarySource.WIKTIONARY), result)
        assertEquals(listOf(DictionarySource.DICTIONARY_API, DictionarySource.VOCAB_PACKS, DictionarySource.WIKTIONARY), asked)
    }

    @Test
    fun `lookup tells no entry from nothing reached`() = runBlocking {
        val sources = listOf(DictionarySource.WIKTIONARY, DictionarySource.DICTIONARY_API)
        assertEquals(
            DictionaryLookup.Result.NotFound,
            DictionaryLookup.resolve("word", sources) { source, _ ->
                if (source == DictionarySource.WIKTIONARY) throw IOException("down") else emptyList()
            },
        )
        assertEquals(
            DictionaryLookup.Result.Failed,
            DictionaryLookup.resolve("word", sources) { _, _ -> throw IOException("offline") },
        )
    }

    @Test
    fun `packs without the word and no online source reached is not a verdict`() = runBlocking {
        val sources = listOf(DictionarySource.VOCAB_PACKS, DictionarySource.WIKTIONARY)
        assertEquals(
            DictionaryLookup.Result.NotOnDevice,
            DictionaryLookup.resolve("word", sources) { source, _ ->
                if (source.online) throw IOException("offline") else emptyList()
            },
        )
        assertEquals(
            DictionaryLookup.Result.NotFound,
            DictionaryLookup.resolve("word", listOf(DictionarySource.VOCAB_PACKS)) { _, _ -> emptyList() },
        )
    }

    @Test
    fun `a record's translations ride on its entry`() {
        val record = VocabWord(
            word = "house",
            senses = listOf(VocabSense(pos = "noun", definition = "A building to live in.")),
            translations = mapOf("de" to VocabTranslation(w = listOf("Haus"))),
        )
        val entry = DictionaryLookup.fromVocabWord(record).single()
        assertEquals(listOf("Haus"), entry.translations.getValue("de").w)
    }

    @Test
    fun `translation rows follow the picked order and skip empty languages`() {
        val entry = DictEntry(
            word = "house",
            phonetic = "",
            audioUrl = null,
            meanings = emptyList(),
            translations = mapOf(
                "de" to VocabTranslation(w = listOf("Haus")),
                "bn" to VocabTranslation(w = listOf("বাড়ি"), r = listOf("baṛi")),
                "fr" to VocabTranslation(w = listOf(" ")),
            ),
        )
        assertEquals(
            listOf("bn", "de"),
            DictionaryLookup.translationsFor(entry, listOf("bn", "fr", "es", "de")).map { it.first },
        )
        assertTrue(DictionaryLookup.translationsFor(entry, emptyList()).isEmpty())
    }
}
