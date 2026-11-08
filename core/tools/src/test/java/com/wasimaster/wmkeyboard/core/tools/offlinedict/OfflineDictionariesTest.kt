package com.wasimaster.wmkeyboard.core.tools.offlinedict

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OfflineDictionariesTest {

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("offline-dict").toFile()
        OfflineDictionaries.attach(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun sense(pos: String, definition: String, example: String = "", synonyms: String = "", antonyms: String? = null) =
        listOfNotNull(pos, definition, example, synonyms, antonyms).joinToString("\u001f")

    /** A pack as the build script writes it: header, then lines sorted by key bytes. */
    private fun pack(id: String = "oewn", extra: List<String> = emptyList()): String {
        val lines = listOf(
            "house\thouse\thaʊs\t" + sense("noun", "a dwelling", "he has a house", "home|dwelling") +
                "\t" + sense("verb", "contain or cover", "", "", "evict"),
            "may\tMay\tmeɪ\t" + sense("noun", "the month following April"),
            "may\tmay\t\t" + sense("noun", "thorny Eurasian shrub"),
            "mice\t@mouse",
            "mouse\tmouse\t\t" + sense("noun", "a small rodent"),
            "run\trun\tɹʌn\t" + sense("verb", "move fast"),
            "cry\tcry\t\t" + sense("verb", "shed tears"),
        ) + extra
        val sorted = lines.sortedWith(compareBy<String, ByteArray>(ByteArrayComparator) { it.substringBefore('\t').toByteArray() })
        return "#wmdict\t1\n#id\t$id\n#name\tTest\n" + sorted.joinToString("") { "$it\n" }
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private fun installText(text: String, gzipped: Boolean = true, name: String = "oewn.wmdict.gz"): OfflineDictionaries.Installed {
        val source = File(filesDir, name)
        source.writeBytes(if (gzipped) gzip(text) else text.toByteArray())
        return OfflineDictionaries.install(filesDir, source)
    }

    @Test
    fun `an installed pack defines a word with its senses grouped`() {
        val installed = installText(pack())
        assertEquals("oewn", installed.pack.id)
        assertTrue(OfflineDictionaries.isInstalled(filesDir, "oewn"))
        val entry = OfflineDictionaries.lookup("oewn", "House").single()
        assertEquals("house", entry.word)
        assertEquals("/haʊs/", entry.phonetic)
        assertEquals(listOf("noun", "verb"), entry.meanings.map { it.partOfSpeech })
        assertEquals("he has a house", entry.meanings[0].definitions.single().example)
        assertEquals(listOf("home", "dwelling"), entry.meanings[0].definitions.single().synonyms)
        assertEquals(listOf("evict"), entry.meanings[1].antonyms)
        assertEquals(null, entry.meanings[1].definitions.single().example)
    }

    @Test
    fun `a language pack keeps its redirects but not the English rules`() {
        installText(pack("de_wiktionary"), name = "de_wiktionary.wmdict.gz")
        assertEquals("mouse", OfflineDictionaries.lookup("de_wiktionary", "mice").single().word)
        assertTrue(OfflineDictionaries.lookup("de_wiktionary", "houses").isEmpty())
        // Whose word it is rides the entry, for the card and the speaker (#1085).
        assertEquals("de", OfflineDictionaries.lookup("de_wiktionary", "mice").single().language)
    }

    @Test
    fun `a word in one language pack reads as usual, and in two says whose it is`() {
        installText(pack("de_wiktionary"), name = "de_wiktionary.wmdict.gz")
        assertEquals("noun", OfflineDictionaries.lookupLanguages("mouse").single().meanings.single().partOfSpeech)
        installText(pack("nl_wiktionary"), name = "nl_wiktionary.wmdict.gz")
        val german = java.util.Locale.forLanguageTag("de").displayLanguage
        val dutch = java.util.Locale.forLanguageTag("nl").displayLanguage
        assertEquals(
            listOf("$dutch: noun", "$german: noun"),
            OfflineDictionaries.lookupLanguages("mouse").map { it.meanings.single().partOfSpeech }.sortedBy { !it.startsWith(dutch) },
        )
    }

    @Test
    fun `the language packs are published under their own language`() {
        for (pack in OfflineDictionaryCatalog.LANGUAGES) {
            assertEquals("${pack.langId}_wiktionary", pack.id)
            assertEquals(pack, OfflineDictionaryCatalog.byId(pack.id))
        }
        assertEquals(OfflineDictionaryCatalog.entries.size, OfflineDictionaryCatalog.entries.distinctBy { it.id }.size)
    }

    @Test
    fun `forms find their lemma through redirects and the English rules`() {
        installText(pack(), gzipped = false, name = "oewn.wmdict")
        assertEquals("mouse", OfflineDictionaries.lookup("oewn", "mice").single().word)
        assertEquals("house", OfflineDictionaries.lookup("oewn", "houses").single().word)
        assertEquals("run", OfflineDictionaries.lookup("oewn", "running").single().word)
        assertEquals("cry", OfflineDictionaries.lookup("oewn", "cried").single().word)
        assertEquals("house", OfflineDictionaries.lookup("oewn", "house’s").single().word)
    }

    @Test
    fun `every headword sharing a key comes back`() {
        installText(pack())
        assertEquals(listOf("May", "may"), OfflineDictionaries.lookup("oewn", "may").map { it.word })
    }

    @Test
    fun `binary search finds the first, the last and every word between, and nothing else`() {
        val words = (0 until 3000).map { "w%05d".format(it * 7).replace('0', 'a').replace('1', 'b') }.distinct()
        installText(pack(extra = words.map { "$it\t$it\t\t" + sense("noun", "def of $it") }))
        val dict = WmDictFile.open(OfflineDictionaries.file(filesDir, "oewn"))!!
        dict.use {
            for (word in words + listOf("cry", "run", "house")) {
                val found = OfflineDictionaries.lookup(it, word)
                assertEquals(word, found.single().word)
            }
            assertTrue(OfflineDictionaries.lookup(it, "aardvark").isEmpty())
            assertTrue(OfflineDictionaries.lookup(it, "zzz").isEmpty())
            assertTrue(OfflineDictionaries.lookup(it, "hous").isEmpty())
        }
    }

    @Test
    fun `nothing installed or nothing attached is no entry, not an error`() {
        assertTrue(OfflineDictionaries.lookup("oewn", "house").isEmpty())
        assertTrue(OfflineDictionaries.lookup("wiktionary", "house").isEmpty())
    }

    @Test
    fun `a file that is not a pack, or a pack this build does not list, is refused and leaves no trace`() {
        installText(pack())
        for (bad in listOf("hello world\n", pack(id = "klingon"), "#wmdict\t9\n#id\toewn\n")) {
            try {
                installText(bad)
                fail("installed: ${bad.take(20)}")
            } catch (_: OfflineDictionaries.NotAPackException) {
            }
        }
        // The working pack is still there, and no part file is left behind.
        assertEquals("house", OfflineDictionaries.lookup("oewn", "house").single().word)
        assertFalse(File(filesDir, OfflineDictionaries.DIR_NAME).listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun `a download is checked against the pack it asked for`() {
        try {
            OfflineDictionaries.install(filesDir, gzip(pack(id = "oewn")).inputStream(), expectedId = "wiktionary")
            fail("installed under the wrong id")
        } catch (_: OfflineDictionaries.NotAPackException) {
        }
        assertFalse(OfflineDictionaries.isInstalled(filesDir, "oewn"))
    }

    @Test
    fun `published names are recognised for import`() {
        assertTrue(OfflineDictionaries.recognises(OfflineDictionaryCatalog.OEWN.fileName))
        assertTrue(OfflineDictionaries.recognises("wiktionary.wmdict"))
        assertFalse(OfflineDictionaries.recognises("en_full.txt.gz"))
    }

    @Test
    fun `lemma candidates undo regular inflection`() {
        val candidates = EnglishLemmas.candidates("stopped")
        assertTrue(candidates.toString(), "stop" in candidates)
        assertTrue("happy" in EnglishLemmas.candidates("happiest"))
        assertTrue("box" in EnglishLemmas.candidates("boxes"))
        assertTrue("fall" in EnglishLemmas.candidates("falling"))
        assertFalse("fal" in EnglishLemmas.candidates("falling"))
        assertTrue(EnglishLemmas.candidates("is").isEmpty())
    }

    private object ByteArrayComparator : Comparator<ByteArray> {
        override fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (d != 0) return d
            }
            return a.size - b.size
        }
    }
}
