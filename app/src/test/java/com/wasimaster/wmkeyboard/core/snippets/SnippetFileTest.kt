package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.content.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetFileTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("addons/$name")) {
            "missing test fixture addons/$name"
        }.use { it.readBytes().decodeToString() }

    private fun snippet(id: Long, label: String, text: String, trigger: String? = null) =
        Snippet(id = id, label = label, text = text, trigger = trigger)

    @Test
    fun `reads the sample repository's snippet pack`() {
        val imported = SnippetFile.decode(fixture("dev-shortcuts.wmsnippets.json"))
        assertNotNull(imported)
        assertTrue(imported!!.snippets.isNotEmpty())
        // The sample deliberately includes one entry with no trigger.
        assertTrue(imported.snippets.any { it.trigger == null })
        assertTrue(imported.snippets.all { it.text.isNotBlank() })
    }

    @Test
    fun `the sample repository's pattern pack expands the way it is advertised`() {
        // The published pack, decoded and run end to end. The two examples are
        // the ones the documentation promises, so a change that quietly breaks
        // them fails here rather than on somebody's phone.
        val imported = SnippetFile.decode(fixture("pattern-replies.wmsnippets.json"))
        assertNotNull(imported)
        assertTrue(imported!!.repairs.isEmpty())
        assertTrue(imported.snippets.all { !it.triggerPattern.isNullOrBlank() })

        val index = SnippetIndex.of(imported.snippets)
        assertEquals(
            "Hello, John! Nice to meet you.",
            index.matchPattern("hello John", atFieldStart = true)?.text,
        )
        val letter = index.matchPattern("thanks Sarah", atFieldStart = true)
        assertTrue(letter!!.text.startsWith("Dear Sarah,"))
        assertEquals("thanks Sarah", letter.consumedText)
        // The transform is what turns a typed name into a written one.
        assertEquals(
            "Happy birthday, Sarah! Have a wonderful one.",
            index.matchPattern("bday sarah", atFieldStart = true)?.text,
        )
        // Every pattern starts with a plain word, so none of them costs a
        // check after every word the user types.
        assertTrue(imported.snippets.all { SnippetMatcher.headOf(it.triggerPattern.orEmpty()) != null })
    }

    @Test
    fun `round-trips`() {
        val original = listOf(
            snippet(1, "Shrug", "¯\\_(ツ)_/¯", "shrug"),
            snippet(2, "Sign-off", "Best,\nWasi"),
        )
        val decoded = SnippetFile.decode(SnippetFile.encode(original, 41, "1.4.0"))
        assertNotNull(decoded)
        assertEquals(original, decoded!!.snippets)
        assertEquals(41, decoded.fromAppVersion)
    }

    @Test
    fun `rejects a file that is not a snippet pack`() {
        assertNull(SnippetFile.decode("""{"format":"wmkeyboard-layout","version":1}"""))
        assertNull(SnippetFile.decode("""{"snippets":[]}"""))
        assertNull(SnippetFile.decode("not json"))
    }

    @Test
    fun `a snippet with no text is dropped and reported`() {
        // There is nothing for it to insert.
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"Empty","text":""},
              {"id":2,"label":"Real","text":"hello"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertEquals(listOf("Real"), imported.snippets.map { it.label })
        // The note names the snippet it dropped: the name is the argument
        // that fills %1$s, so that is what the assertion checks.
        assertTrue(
            imported.repairs.any {
                it.stringRes == R.string.core_content_snippet_repair_no_text &&
                    it.args == listOf<Any>("Empty")
            },
        )
    }

    @Test
    fun `a snippet with no label is named after its text`() {
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"","text":"first line\nsecond line"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertEquals("first line", imported.snippets.single().label)
        assertTrue(imported.repairs.isNotEmpty())
    }

    @Test
    fun `a blank trigger becomes no trigger`() {
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"A","text":"a","trigger":"   "}
            ]}
        """.trimIndent()
        assertNull(SnippetFile.decode(text)!!.snippets.single().trigger)
    }

    @Test
    fun `an over-long snippet is truncated rather than refused`() {
        val huge = "x".repeat(50_000)
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"Huge","text":"$huge"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertTrue(imported.snippets.single().text.length < huge.length)
        assertTrue(
            imported.repairs.any {
                it.pluralsRes == R.plurals.core_content_snippet_repair_shortened &&
                    it.args.first() == "Huge"
            },
        )
    }

    @Test
    fun `an absurd number of snippets is capped`() {
        val many = (1..600).joinToString(",") {
            """{"id":$it,"label":"S$it","text":"t$it"}"""
        }
        val imported = SnippetFile.decode(
            """{"format":"wmkeyboard-snippets","version":1,"snippets":[$many]}""",
        )!!
        assertEquals(500, imported.snippets.size)
        assertTrue(
            imported.repairs.any {
                it.pluralsRes == R.plurals.core_content_snippet_repair_kept_first &&
                    it.args == listOf<Any>(500)
            },
        )
    }

    @Test
    fun `a pattern that will not compile is removed and the snippet kept`() {
        // A row is dropped only when there is nothing left to insert. A snippet
        // whose trigger stopped working still inserts from the panel.
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"Greet","text":"Hello","triggerPattern":"^hi (.+$"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertEquals("Greet", imported.snippets.single().label)
        assertNull(imported.snippets.single().triggerPattern)
        assertTrue(
            imported.repairs.any {
                it.stringRes == R.string.core_content_snippet_repair_bad_pattern &&
                    it.args == listOf<Any>("Greet")
            },
        )
    }

    @Test
    fun `an over-long pattern is removed and reported`() {
        val huge = "a".repeat(SnippetMatcher.MAX_PATTERN_LENGTH + 1)
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"Long","text":"t","triggerPattern":"$huge"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertNull(imported.snippets.single().triggerPattern)
        assertTrue(
            imported.repairs.any {
                it.pluralsRes == R.plurals.core_content_snippet_repair_pattern_too_long &&
                    it.args.first() == "Long"
            },
        )
    }

    @Test
    fun `an out of range word budget is clamped without a note`() {
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"A","text":"a","triggerPattern":"^a (.+)$","triggerWords":99},
              {"id":2,"label":"B","text":"b","triggerPattern":"^b (.+)$","triggerWords":-1}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertEquals(SnippetMatcher.MAX_WORDS, imported.snippets[0].triggerWords)
        assertEquals(0, imported.snippets[1].triggerWords)
        // A number nudged into range is not lost content.
        assertTrue(imported.repairs.isEmpty())
    }

    @Test
    fun `a plain snippet exports no pattern keys`() {
        // The published packs are hand-maintained files, so a plain snippet
        // must not grow three empty keys it never uses.
        val encoded = SnippetFile.encode(listOf(snippet(1, "Shrug", "x", "shrug")), 41, "1.4.0")
        assertTrue(!encoded.contains("triggerPattern"))
        assertTrue(!encoded.contains("triggerWords"))
        assertTrue(!encoded.contains("confirm"))
    }

    @Test
    fun `a pack can ask before it expands`() {
        // What the pattern-replies pack rides on: a downloaded snippet says so
        // in the file, and the flag comes back out of it.
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"Greet","text":"Hello, ${'$'}1!",
               "triggerPattern":"^hello (.+)${'$'}","confirm":true},
              {"id":2,"label":"Shrug","text":"x","trigger":"shrug"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!.snippets
        assertTrue(imported[0].confirm)
        assertTrue(!imported[1].confirm)
        // A pattern the app refuses drops the pattern, not the whole row —
        // and the flag has nothing to do with that.
        assertEquals("^hello (.+)$", imported[0].triggerPattern)
    }

    @Test
    fun `a pattern snippet round-trips`() {
        val original = listOf(
            Snippet(
                id = 1,
                label = "Greet",
                text = "Hello, \$1!",
                triggerPattern = "^hi (.+)$",
                triggerWords = 2,
            ),
        )
        assertEquals(original, SnippetFile.decode(SnippetFile.encode(original, 41, "1.4.0"))!!.snippets)
    }

    @Test
    fun `a file written before patterns existed decodes without one`() {
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"A","text":"a","trigger":"a"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!.snippets.single()
        assertNull(imported.triggerPattern)
        assertEquals(0, imported.triggerWords)
    }

    @Test
    fun `a file written before expansions and links decodes without them`() {
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"A","text":"a","trigger":"a"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!.snippets.single()
        assertEquals(listOf("a"), imported.expansions())
        assertTrue(imported.alternates.isEmpty())
        assertTrue(imported.children.isEmpty())
        assertTrue(imported.tags.isEmpty())
        assertEquals(MultiExpand.DEFAULT, imported.multiExpand)
    }

    @Test
    fun `expansions, links and tags round-trip`() {
        val original = listOf(
            Snippet(
                id = 1,
                label = "Continent",
                text = "Asia",
                alternates = listOf("Africa", "Europe"),
                children = listOf(2),
                tags = listOf("geography"),
                multiExpand = MultiExpand.INSERT_FIRST,
            ),
            Snippet(id = 2, label = "Country", text = "Iran"),
        )
        val back = SnippetFile.decode(SnippetFile.encode(original, 41, "1.4.0"))!!
        assertEquals(original, back.snippets)
        assertEquals(SnippetFile.VERSION, 2)
    }

    @Test
    fun `a plain snippet still writes none of the new keys`() {
        val written = SnippetFile.encode(listOf(snippet(1, "A", "a", "a")), 41, "1.4.0")
        assertTrue(!written.contains("alternates"))
        assertTrue(!written.contains("children"))
        assertTrue(!written.contains("multiExpand"))
    }

    @Test
    fun `a link the file never declared is dropped`() {
        val text = """
            {"format":"wmkeyboard-snippets","snippets":[
              {"id":1,"label":"A","text":"a","children":[2,99,1]},
              {"id":2,"label":"B","text":"b"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertEquals(listOf(2L), imported.snippets.first().children)
        // Filing, not content: nothing to tell the user about.
        assertTrue(imported.repairs.isEmpty())
    }

    @Test
    fun `too many expansions are dropped and reported`() {
        val many = (1..SnippetStore.MAX_ALTERNATES + 3).joinToString(",") { "\"line $it\"" }
        val text = """
            {"format":"wmkeyboard-snippets","snippets":[
              {"id":1,"label":"A","text":"a","alternates":[$many]}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertEquals(SnippetStore.MAX_ALTERNATES, imported.snippets.single().alternates.size)
        assertTrue(
            imported.repairs.any {
                it.pluralsRes == R.plurals.core_content_snippet_repair_alternates_dropped
            },
        )
    }

    @Test
    fun `an over-long expansion is shortened and reported`() {
        val long = "x".repeat(20_001)
        val text = """
            {"format":"wmkeyboard-snippets","snippets":[
              {"id":1,"label":"A","text":"a","alternates":["$long"]}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertEquals(20_000, imported.snippets.single().alternates.single().length)
        assertTrue(
            imported.repairs.any {
                it.pluralsRes == R.plurals.core_content_snippet_repair_shortened
            },
        )
    }

    @Test
    fun `a word this build does not know does not throw the file away`() {
        val text = """
            {"format":"wmkeyboard-snippets","snippets":[
              {"id":1,"label":"A","text":"a","multiExpand":"from_the_future"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)
        assertNotNull(imported)
        assertEquals(MultiExpand.DEFAULT, imported!!.snippets.single().multiExpand)
    }

    @Test
    fun `the semantic pack installs with its links intact`() {
        val imported = SnippetFile.decode(fixture("semantic-places.wmsnippets.json"))!!
        val store = SnippetStore(null)
        val added = store.addAll(imported.snippets, imported.folders)
        val continent = store.items().first { it.label == "Continent" }
        // Two parents point at one child, and the ids they point at are the
        // ones this store handed out rather than the ones the file used.
        val country = store.items().first { it.label == "Country" }
        val asia = store.items().first { it.label == "Asia" }
        assertTrue(asia.id in continent.children)
        assertTrue(asia.id in country.children)
        assertTrue(added.none { it.id == 1L && it.label != "Continent" })
        // A cycle in the file survives as a cycle, and reading it terminates.
        assertEquals(
            listOf("Asia", "Africa"),
            store.candidates(continent).drop(1).map { it.text },
        )
        assertEquals(listOf("geography"), store.tags())
    }

    @Test
    fun `unknown fields are ignored`() {
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"futureThing":7,"snippets":[
              {"id":1,"label":"A","text":"a","colour":"red"}
            ]}
        """.trimIndent()
        assertEquals(1, SnippetFile.decode(text)!!.snippets.size)
    }

    @Test
    fun `folders round-trip with the snippets filed in them`() {
        val folders = listOf(SnippetFolder(id = 3, name = "Work", enabled = false))
        val original = listOf(
            Snippet(id = 1, label = "Sig", text = "Regards", folderId = 3),
            Snippet(id = 2, label = "Loose", text = "x"),
        )
        val decoded = SnippetFile.decode(
            SnippetFile.encode(original, 41, "1.4.0", folders = folders),
        )!!
        assertEquals(folders, decoded.folders)
        assertEquals(listOf(3L, 0L), decoded.snippets.map { it.folderId })
    }

    @Test
    fun `an empty folder is not exported`() {
        // Exporting a subset must not carry its empty shelves along.
        val encoded = SnippetFile.encode(
            listOf(snippet(1, "Loose", "x")),
            41,
            "1.4.0",
            folders = listOf(SnippetFolder(id = 3, name = "Work")),
        )
        assertTrue(!encoded.contains("Work"))
    }

    @Test
    fun `a plain snippet exports no folder key`() {
        val encoded = SnippetFile.encode(listOf(snippet(1, "Shrug", "x", "shrug")), 41, "1.4.0")
        assertTrue(!encoded.contains("folderId"))
    }

    @Test
    fun `a folder with no name, a repeated id or id zero is dropped and reported`() {
        // Id 0 is how a snippet spells "in no folder", so a folder claiming it
        // would swallow every ungrouped row in the file.
        val text = """
            {"format":"wmkeyboard-snippets","version":1,
             "folders":[{"id":1,"name":"Work"},{"id":1,"name":"Again"},
                        {"id":2,"name":"  "},{"id":0,"name":"Nothing"}],
             "snippets":[
              {"id":1,"label":"A","text":"a","folderId":1},
              {"id":2,"label":"B","text":"b","folderId":2},
              {"id":3,"label":"C","text":"c"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertEquals(listOf("Work"), imported.folders.map { it.name })
        // The row that pointed at a dropped folder keeps its text and loses
        // only its filing.
        assertEquals(listOf(1L, 0L, 0L), imported.snippets.map { it.folderId })
        assertTrue(
            imported.repairs.any {
                it.pluralsRes == R.plurals.core_content_snippet_repair_folders_dropped
            },
        )
    }

    @Test
    fun `a file written before folders existed decodes with none`() {
        val text = """
            {"format":"wmkeyboard-snippets","version":1,"snippets":[
              {"id":1,"label":"A","text":"a","trigger":"a"}
            ]}
        """.trimIndent()
        val imported = SnippetFile.decode(text)!!
        assertTrue(imported.folders.isEmpty())
        assertEquals(0L, imported.snippets.single().folderId)
        assertTrue(imported.repairs.isEmpty())
    }

    @Test
    fun `the file extension stays compound so plain json is unclaimed`() {
        // A bare .json association would offer this app for every JSON file on
        // the device.
        assertTrue(SnippetFile.FILE_EXTENSION.endsWith(".json"))
        assertTrue(SnippetFile.FILE_EXTENSION.startsWith("wm"))
    }
}
