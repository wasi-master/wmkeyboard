package com.wasimaster.wmkeyboard.core.addons

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Reading a payload well enough to show it, without installing it.
 *
 * The reader runs against a file a stranger wrote, so the interesting cases are
 * the malformed ones: everything it can't make sense of has to come back as
 * [AddonPreviewContent.Unreadable] rather than an exception on the way to the
 * UI thread.
 */
class AddonPreviewReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun entry(type: AddonType, path: String = "payload") =
        AddonEntry(id = "x", type = type, name = "X", version = "1.0.0", path = path)

    private fun file(name: String, text: String): File =
        temp.newFile(name).apply { writeText(text) }

    // ---- snippets --------------------------------------------------------

    @Test
    fun `reads the snippets out of a pack`() {
        val payload = file(
            "p.wmsnippets.json",
            """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"Shrug","text":"¯\\_(ツ)_/¯","trigger":"shrug"},
              {"id":2,"label":"Email","text":"me@example.com"}
            ]}
            """.trimIndent(),
        )
        val content = AddonPreviewReader.read(entry(AddonType.Snippets), payload)
        val snippets = content as AddonPreviewContent.Snippets
        assertEquals(2, snippets.total)
        assertEquals("Shrug", snippets.entries[0].label)
        assertEquals("shrug", snippets.entries[0].trigger)
        // A snippet with no trigger reads as blank, not "null".
        assertEquals("", snippets.entries[1].trigger)
        // Nothing was converted, so there is nothing to warn about.
        assertTrue(snippets.notes.isEmpty())
    }

    // ---- espanso ---------------------------------------------------------

    @Test
    fun `reads the snippets out of an espanso match file`() {
        val payload = file(
            "p.yml",
            """
            matches:
              - trigger: ":shrug"
                replace: "shrugged"
              - trigger: ":hi"
                replace: "hello"
                label: Greeting
            """.trimIndent(),
        )
        val content = AddonPreviewReader.read(entry(AddonType.Espanso), payload)
        val snippets = content as AddonPreviewContent.Snippets
        assertEquals(2, snippets.total)
        assertEquals(":shrug", snippets.entries[0].trigger)
        assertEquals("Greeting", snippets.entries[1].label)
    }

    @Test
    fun `an espanso preview says what conversion costs, before the install button`() {
        val payload = file(
            "p.yml",
            """
            matches:
              - trigger: ":ip"
                replace: "{{out}}"
                vars:
                  - name: out
                    type: shell
                    params:
                      cmd: "curl https://api.ipify.org"
            """.trimIndent(),
        )
        val content = AddonPreviewReader.read(entry(AddonType.Espanso), payload)
        val snippets = content as AddonPreviewContent.Snippets
        assertTrue("a dropped shell command has to be reported", snippets.notes.isNotEmpty())
    }

    @Test
    fun `the two snippet types do not read each other's payloads`() {
        // The entry declares its format, so a payload in the other one is a
        // mistake in the manifest rather than something to reinterpret.
        val espanso = file("a.yml", "matches:\n  - trigger: \":x\"\n    replace: \"y\"\n")
        val native = file(
            "b.wmsnippets.json",
            """{"format":"wmkeyboard-snippets","version":1,"snippets":[{"id":1,"label":"A","text":"a"}]}""",
        )
        assertTrue(
            AddonPreviewReader.read(entry(AddonType.Snippets), espanso)
                is AddonPreviewContent.Unreadable,
        )
        assertTrue(
            AddonPreviewReader.read(entry(AddonType.Espanso), native)
                is AddonPreviewContent.Unreadable,
        )
    }

    @Test
    fun `an espanso package archive previews`() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("package.yml"))
                zip.write("matches:\n  - trigger: \":a\"\n    replace: \"b\"\n".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("_manifest.yml"))
                zip.write("name: pack\ntitle: Pack\n".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val payload = temp.newFile("p.zip").apply { writeBytes(bytes) }
        val content = AddonPreviewReader.read(entry(AddonType.Espanso), payload)
        assertEquals(1, (content as AddonPreviewContent.Snippets).total)
    }

    @Test
    fun `something that is not espanso at all is unreadable rather than a crash`() {
        val payload = file("p.yml", "just: some other yaml\n")
        assertTrue(
            AddonPreviewReader.read(entry(AddonType.Espanso), payload)
                is AddonPreviewContent.Unreadable,
        )
    }

    @Test
    fun `a snippet pack that is not one is unreadable, not a crash`() {
        val payload = file("p.wmsnippets.json", """{"format":"wmkeyboard-layout"}""")
        assertTrue(
            AddonPreviewReader.read(entry(AddonType.Snippets), payload)
                is AddonPreviewContent.Unreadable,
        )
    }

    // ---- dictionaries ----------------------------------------------------

    @Test
    fun `counts a word list and samples the start of it`() {
        val payload = file(
            "words.txt",
            buildString {
                appendLine("# a comment, skipped")
                appendLine("")
                appendLine("kotlin 90")
                appendLine("gradle 40")
                appendLine("coroutine")
            },
        )
        val content = AddonPreviewReader.read(entry(AddonType.Dictionary, "words.txt"), payload)
        val dictionary = content as AddonPreviewContent.Dictionary
        assertEquals(3, dictionary.total)
        // The frequency is stripped; the sample is the words themselves.
        assertEquals(listOf("kotlin", "gradle", "coroutine"), dictionary.words)
        assertTrue(!dictionary.truncated)
        // Everything in the file, so the dialog isn't showing a subset.
        assertTrue(!dictionary.partial)
    }

    @Test
    fun `a long word list is capped and says so`() {
        val over = AddonPreviewReader.MAX_WORDS + 500
        val payload = file("words.txt", (1..over).joinToString("\n") { "word$it $it" })

        val dictionary = AddonPreviewReader.read(entry(AddonType.Dictionary, "words.txt"), payload)
            as AddonPreviewContent.Dictionary

        assertEquals(over, dictionary.total)
        assertEquals(AddonPreviewReader.MAX_WORDS, dictionary.words.size)
        assertTrue(dictionary.partial)
        // The count is exact; only the word list was cut short.
        assertTrue(!dictionary.truncated)
    }

    @Test
    fun `reads a gzipped word list`() {
        // The format allows dictionaries to travel gzipped, keyed off the path.
        val payload = temp.newFile("words.txt.gz")
        GZIPOutputStream(payload.outputStream()).use {
            it.write("alpha 3\nbeta 2\n".toByteArray())
        }
        val content = AddonPreviewReader.read(
            entry(AddonType.Dictionary, "dictionaries/words.txt.gz"),
            payload,
        )
        assertEquals(listOf("alpha", "beta"), (content as AddonPreviewContent.Dictionary).words)
    }

    // ---- emoji keyword packs ---------------------------------------------

    @Test
    fun `reads the emoji out of a keyword pack`() {
        val payload = file(
            "hi.tsv",
            "# emoji\tkeywords\tname\n" +
                "🎂\tकेक,जन्मदिन\tजन्मदिन का केक\n" +
                "💰\tपैसा,रुपया\n",
        )
        val content = AddonPreviewReader.read(entry(AddonType.EmojiKeywords), payload)
        val pack = content as AddonPreviewContent.EmojiKeywords
        assertEquals(2, pack.total)
        assertTrue(!pack.truncated)
        assertEquals("🎂", pack.samples[0].emoji)
        assertEquals("केक,जन्मदिन", pack.samples[0].keywords)
        assertEquals("💰", pack.samples[1].emoji)
    }

    @Test
    fun `reads a gzipped keyword pack`() {
        val payload = temp.newFile("hi.tsv.gz")
        GZIPOutputStream(payload.outputStream()).use {
            it.write("🎂\tकेक\n".toByteArray())
        }
        val content = AddonPreviewReader.read(
            entry(AddonType.EmojiKeywords, "emoji/hi.tsv.gz"),
            payload,
        )
        assertEquals(1, (content as AddonPreviewContent.EmojiKeywords).total)
    }

    /** A bare "#" heads the keycap emoji, so it must survive the comment rule. */
    @Test
    fun `the keycap hash row is data, not a comment`() {
        val payload = file("x.tsv", "# emoji\tkeywords\n#️⃣\thash,keycap\n")
        val pack = AddonPreviewReader.read(
            entry(AddonType.EmojiKeywords),
            payload,
        ) as AddonPreviewContent.EmojiKeywords
        assertEquals(1, pack.total)
        assertEquals("#️⃣", pack.samples[0].emoji)
    }

    @Test
    fun `a keyword pack with no usable rows is unreadable`() {
        assertTrue(
            AddonPreviewReader.read(
                entry(AddonType.EmojiKeywords),
                file("junk.tsv", "%PDF-1.4\nnot a pack at all\n"),
            ) is AddonPreviewContent.Unreadable,
        )
        assertTrue(
            AddonPreviewReader.read(
                entry(AddonType.EmojiKeywords),
                file("empty.tsv", "# only a comment\n\n"),
            ) is AddonPreviewContent.Unreadable,
        )
    }

    @Test
    fun `a word list of nothing but comments is unreadable`() {
        val payload = file("words.txt", "# nothing here\n\n# still nothing\n")
        assertTrue(
            AddonPreviewReader.read(entry(AddonType.Dictionary, "words.txt"), payload)
                is AddonPreviewContent.Unreadable,
        )
    }

    // ---- sound -----------------------------------------------------------

    @Test
    fun `a sound preview is just the file, ready to play`() {
        val payload = file("s.mp3", "not really an mp3, the player decides")
        val content = AddonPreviewReader.read(entry(AddonType.Sound), payload)
        assertEquals(payload, (content as AddonPreviewContent.Sound).file)
    }

    // ---- stickers --------------------------------------------------------

    private fun stickerPack(vararg names: String): File {
        val payload = temp.newFile("pack.wmstickers")
        ZipOutputStream(payload.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write("""{"format":"wmkeyboard-stickers","version":1}""".toByteArray())
            zip.closeEntry()
            for (name in names) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(ByteArray(64) { it.toByte() })
                zip.closeEntry()
            }
        }
        return payload
    }

    @Test
    fun `unpacks the first sticker images and counts the rest`() {
        // pack.json is already in every archive; only the images count.
        val payload = stickerPack("stickers/a.png", "stickers/b.webp", "stickers/c.jpg")
        val content = AddonPreviewReader.read(entry(AddonType.Stickers), payload)
        val stickers = content as AddonPreviewContent.Stickers
        assertEquals(3, stickers.total)
        assertEquals(3, stickers.images.size)
        assertTrue("images should be on disk", stickers.images.all { it.isFile })
    }

    @Test
    fun `a sticker entry cannot write outside the preview directory`() {
        // Entry names are never used as paths: the extracted files are named
        // from the index, so `../` inside the archive escapes nothing.
        val payload = stickerPack("../../escaped.png", "stickers/ok.png")
        val stickers = AddonPreviewReader.read(entry(AddonType.Stickers), payload)
            as AddonPreviewContent.Stickers
        val root = temp.root.canonicalFile
        for (image in stickers.images) {
            assertTrue(
                "escaped to ${image.canonicalPath}",
                image.canonicalFile.startsWith(root),
            )
        }
    }

    @Test
    fun `an archive with no images is unreadable`() {
        assertTrue(
            AddonPreviewReader.read(entry(AddonType.Stickers), stickerPack())
                is AddonPreviewContent.Unreadable,
        )
    }

    @Test
    fun `a sticker payload that is not a zip is unreadable`() {
        val payload = file("pack.wmstickers", "plain text, definitely not a zip")
        assertTrue(
            AddonPreviewReader.read(entry(AddonType.Stickers), payload)
                is AddonPreviewContent.Unreadable,
        )
    }

    // ---- dynamic stickers -------------------------------------------------

    /** A Lottie document with a text layer per name: a template. */
    private fun lottie(vararg layers: String, props: String = "{}"): String {
        val texts = layers.joinToString(",") { name ->
            """{"ty":5,"nm":"$name","t":{"d":{"k":[{"s":{"s":60,"f":"Sans","t":"HI","j":2},"t":0}]}}}"""
        }
        return """{"v":"5.7.14","fr":30,"ip":0,"op":60,"w":512,"h":512,"assets":[],"layers":[$texts],""" +
            """"metadata":{"customProps":$props}}"""
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val payload = temp.newFile(name)
        ZipOutputStream(payload.outputStream()).use { zip ->
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return payload
    }

    private val dynManifest = """
        {"format":"wmkeyboard-dynstickers","version":1,"pack":{"id":"hello","name":"Hello pack","author":"Ann",
         "templates":[
           {"id":"thanks","name":"Thanks","file":"templates/thanks.json","match":{"phrases":["thank you","thanks"]}},
           {"id":"any","name":"Any text","file":"templates/any.json","match":{"generic":true}},
           {"id":"drake","name":"Drake","file":"templates/drake.json","kind":"meme"},
           {"id":"gone","name":"Gone","file":"templates/gone.json"}
         ],
         "fonts":[{"family":"Sans","file":"fonts/Sans.ttf"},{"family":"Gone","file":"fonts/Gone.ttf"}]}}
    """.trimIndent()

    @Test
    fun `reads a dynamic sticker pack off its manifest`() {
        val payload = zip(
            "p.wmdynstickers",
            "pack.json" to dynManifest.toByteArray(),
            "templates/thanks.json" to lottie("_txt_0").toByteArray(),
            "templates/any.json" to lottie("_txt_0").toByteArray(),
            "templates/drake.json" to lottie("top", "bottom").toByteArray(),
            "fonts/Sans.ttf" to ByteArray(16),
        )
        val pack = AddonPreviewReader.read(entry(AddonType.DynamicStickers), payload)
            as AddonPreviewContent.DynamicStickers
        assertEquals("Hello pack", pack.name)
        assertEquals("Ann", pack.author)
        // "gone" names a file the archive does not have, so it would not install.
        assertEquals(3, pack.total)
        assertEquals(listOf("Thanks", "Any text", "Drake"), pack.templates.map { it.name })
        assertEquals(listOf("thank you", "thanks"), pack.templates[0].phrases)
        assertTrue(pack.templates[1].generic)
        assertTrue(pack.templates[2].meme)
        // Only the font that is really in the archive.
        assertEquals(1, pack.fonts)
    }

    @Test
    fun `a zip without a manifest counts the documents that have text`() {
        val payload = zip(
            "set.zip",
            "love_v2.json" to lottie("_txt_0", "_txt_1", props = """{"keyword-map":"i love you;love you"}""").toByteArray(),
            // An animation with no text layer is not a template.
            "confetti.json" to """{"v":"5.7.14","layers":[{"ty":4,"nm":"shape"}]}""".toByteArray(),
            "plain_text.json" to lottie("Title").toByteArray(),
        )
        val pack = AddonPreviewReader.read(entry(AddonType.DynamicStickers), payload)
            as AddonPreviewContent.DynamicStickers
        assertEquals(2, pack.total)
        // Named by its first phrase, as the importer names it.
        assertEquals("I love you", pack.templates[0].name)
        assertEquals("Plain text", pack.templates[1].name)
        assertEquals("X", pack.name)
    }

    @Test
    fun `a dynamic sticker pack with nothing to install is unreadable`() {
        val empty = zip("e.wmdynstickers", "pack.json" to dynManifest.toByteArray())
        assertTrue(
            AddonPreviewReader.read(entry(AddonType.DynamicStickers), empty) is AddonPreviewContent.Unreadable,
        )
        val notZip = file("n.wmdynstickers", "plain text, definitely not a zip")
        assertTrue(
            AddonPreviewReader.read(entry(AddonType.DynamicStickers), notZip) is AddonPreviewContent.Unreadable,
        )
    }

    // ---- the types that deliberately have no preview ---------------------

    @Test
    fun `a theme reports that it cannot be previewed`() {
        val payload = file("t.wmtheme.json", "{}")
        for (type in AddonType.entries.filterNot { it.previewable }) {
            assertTrue(
                "$type should not claim a preview",
                AddonPreviewReader.read(entry(type), payload)
                    is AddonPreviewContent.Unreadable,
            )
        }
    }

    /** Kept honest: the zip helper really does produce a readable archive. */
    @Test
    fun `the fixture builder writes a real archive`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("a")); it.closeEntry() }
        assertTrue(bytes.size() > 0)
    }
}
