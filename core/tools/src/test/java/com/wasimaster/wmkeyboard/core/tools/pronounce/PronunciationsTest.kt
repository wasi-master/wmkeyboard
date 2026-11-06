package com.wasimaster.wmkeyboard.core.tools.pronounce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Recorded pronunciations from Wiktionary and Forvo (#1146). */
class PronunciationsTest {

    @Test
    fun `reads the language, accent and speaker out of a Commons file name`() {
        assertEquals(
            Pronunciations.AudioFile("en", "US", null, "house"),
            Pronunciations.describeFile("File:En-us-house.ogg"),
        )
        assertEquals(
            Pronunciations.AudioFile("eng", null, "Vealhurl", "house"),
            Pronunciations.describeFile("File:LL-Q1860 (eng)-Vealhurl-house.wav"),
        )
        assertEquals(Pronunciations.AudioFile("de", null, null, "Haus"), Pronunciations.describeFile("De-Haus.ogg"))
        // A picture, and a sound whose name gives no language.
        assertNull(Pronunciations.describeFile("File:House.jpg"))
        assertNull(Pronunciations.describeFile("File:Doorbell.ogg"))
    }

    @Test
    fun `a three-letter code matches its two-letter language`() {
        assertTrue(Pronunciations.sameLanguage("en", "en"))
        assertTrue(Pronunciations.sameLanguage("eng", "en"))
        assertTrue(Pronunciations.sameLanguage("fra", "fr"))
        assertFalse(Pronunciations.sameLanguage("deu", "en"))
    }

    @Test
    fun `keeps the word's own files in the language, accents before speakers`() {
        val body = """
            {"batchcomplete":true,"query":{"pages":[
              {"ns":6,"title":"File:De-Haus.ogg","imageinfo":[{"url":"https://upload.wikimedia.org/a/De-Haus.ogg"}]},
              {"ns":6,"title":"File:LL-Q1860 (eng)-Vealhurl-house.wav","imageinfo":[{"url":"https://upload.wikimedia.org/b/LL.wav"}]},
              {"ns":6,"title":"File:En-us-house-noun.ogg","imageinfo":[{"url":"https://upload.wikimedia.org/c/noun.ogg"}]},
              {"ns":6,"title":"File:En-uk-house.ogg","imageinfo":[{"url":"https://upload.wikimedia.org/d/uk.ogg"}]},
              {"ns":6,"title":"File:En-us-house.ogg","imageinfo":[{"url":"https://upload.wikimedia.org/e/us.ogg"}]},
              {"ns":6,"title":"File:A house.jpg","imageinfo":[{"url":"https://upload.wikimedia.org/f/house.jpg"}]}
            ]}}
        """.trimIndent()
        val found = Pronunciations.parseWiktionary(body, "house", "en")
        assertEquals(listOf("UK", "US 1", "Vealhurl", "US 2"), found.map { it.label })
        assertEquals("https://upload.wikimedia.org/d/uk.ogg", found.first().url)
        assertTrue(found.all { it.source == PronunciationSource.WIKTIONARY })
        assertTrue(Pronunciations.parseWiktionary("""{"batchcomplete":true}""", "house", "en").isEmpty())
    }

    @Test
    fun `forvo voices keep its order and name the user and country`() {
        val body = """
            {"attributes":{"total":2},"items":[
              {"id":1,"word":"house","username":"jsmith","sex":"m","country":"United States","pathmp3":"https://apifree.forvo.com/audio/1.mp3","pathogg":"https://apifree.forvo.com/audio/1.ogg"},
              {"id":2,"word":"house","username":"","country":null,"pathogg":"https://apifree.forvo.com/audio/2.ogg"}
            ]}
        """.trimIndent()
        val found = Pronunciations.parseForvo(body)
        assertEquals(listOf("jsmith (United States)", "Forvo"), found.map { it.label })
        assertEquals("https://apifree.forvo.com/audio/1.mp3", found.first().url)
        assertEquals("https://apifree.forvo.com/audio/2.ogg", found[1].url)
    }

    @Test
    fun `the forvo address carries the key, the word and the language`() {
        assertEquals(
            "https://apifree.forvo.com/key/abc/format/json/action/word-pronunciations/word/ice%20cream/language/en/order/rate-desc",
            Pronunciations.forvoUrl(" abc ", "ice cream", "en"),
        )
    }

    @Test
    fun `the store remembers a word's voices across look-ups`() {
        val dir = Files.createTempDirectory("pron").toFile()
        try {
            val store = PronunciationStore(dir)
            val voices = listOf(
                Recording("https://upload.wikimedia.org/e/us.ogg", "US", PronunciationSource.WIKTIONARY),
                Recording("https://apifree.forvo.com/audio/1.mp3", "jsmith (United States)", PronunciationSource.FORVO),
            )
            assertTrue(store.remembered("House", "en").isEmpty())
            store.remember("House", "en", voices)
            assertEquals(voices, store.remembered("house ", "EN"))
            assertTrue(store.remembered("house", "de").isEmpty())
            assertNull(store.local(voices.first()))
            assertEquals(voices, PronunciationStore.decode(PronunciationStore.encode(voices)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
