package com.wasimaster.wmkeyboard.core.snippets.espanso

import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetFolder
import com.wasimaster.wmkeyboard.core.snippets.SnippetVariable
import com.wasimaster.wmkeyboard.core.snippets.UppercaseStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class EspansoWriterTest {

    private fun snippet(
        id: Long = 1,
        label: String = "Test",
        text: String = "hello",
        trigger: String? = ":t",
        aliases: List<String> = emptyList(),
        pattern: String? = null,
        propagateCase: Boolean = false,
        style: UppercaseStyle = UppercaseStyle.CAPITALIZE,
        confirm: Boolean = false,
        folderId: Long = 0,
    ) = Snippet(
        id = id,
        label = label,
        text = text,
        trigger = trigger,
        aliases = aliases,
        propagateCase = propagateCase,
        uppercaseStyle = style,
        triggerPattern = pattern,
        confirm = confirm,
        folderId = folderId,
    )

    /** Reads the written file straight back, which is the only claim worth making. */
    private fun roundTrip(vararg snippets: Snippet, folders: List<SnippetFolder> = emptyList()): EspansoImport {
        val written = EspansoWriter.encodeMatchFile(snippets.toList(), folders)
        return checkNotNull(EspansoFile.read(written.text, "out.yml")) {
            "wrote something Espanso's own shape does not read back:\n${written.text}"
        }
    }

    @Test
    fun `a plain snippet round-trips`() {
        val back = roundTrip(snippet(trigger = ":sig", text = "Kind regards", label = "Sign-off"))
        val out = back.snippets.single()
        assertEquals(":sig", out.trigger)
        assertEquals("Kind regards", out.text)
        assertEquals("Sign-off", out.label)
    }

    @Test
    fun `aliases round-trip as a triggers list`() {
        val written = EspansoWriter.encodeMatchFile(
            listOf(snippet(trigger = ":hi", aliases = listOf(":hello", ":hey"))),
        )
        assertTrue(written.text.contains("triggers: ["))
        val out = roundTrip(snippet(trigger = ":hi", aliases = listOf(":hello", ":hey"))).snippets.single()
        assertEquals(":hi", out.trigger)
        assertEquals(listOf(":hello", ":hey"), out.aliases)
    }

    @Test
    fun `plain expansions round-trip as a choice`() {
        val snippet = snippet(
            trigger = ":greet",
            text = "Hi",
        ).copy(alternates = listOf("Hello", "Hey"))
        val written = EspansoWriter.encodeMatchFile(listOf(snippet))
        assertTrue(written.text.contains("type: choice"))
        val out = roundTrip(snippet).snippets.single()
        assertEquals("Hi", out.text)
        assertEquals(listOf("Hello", "Hey"), out.alternates)
        assertEquals(0, written.notes.count { it.pluralsRes == EspansoNote.ALTERNATES.pluralsRes })
    }

    @Test
    fun `expansions that need expanding cannot ride in a choice, and it says so`() {
        // A choice entry is a literal Espanso types as it stands, so a date or
        // the clipboard cannot travel in one.
        val snippet = snippet(trigger = ":sig", text = "Sent {date}")
            .copy(alternates = listOf("Sent today"))
        val written = EspansoWriter.encodeMatchFile(listOf(snippet))
        assertFalse(written.text.contains("type: choice"))
        assertEquals(1, written.notes.count { it.pluralsRes == EspansoNote.ALTERNATES.pluralsRes })
        val out = roundTrip(snippet).snippets.single()
        // The date comes back spelled out, as it does for any exported date.
        assertTrue(out.text.startsWith("Sent {date"))
        assertTrue(out.alternates.isEmpty())
    }

    @Test
    fun `links are reported as something espanso cannot carry`() {
        val snippet = snippet(trigger = ":c", text = "Continent").copy(children = listOf(2L))
        val written = EspansoWriter.encodeMatchFile(listOf(snippet))
        assertEquals(1, written.notes.count { it.pluralsRes == EspansoNote.LINKS.pluralsRes })
    }

    @Test
    fun `word true is written so the trigger behaves the same on a desktop`() {
        val written = EspansoWriter.encodeMatchFile(listOf(snippet(trigger = "omw")))
        assertTrue(written.text.contains("word: true"))
    }

    @Test
    fun `propagate case round-trips with its style`() {
        val out = roundTrip(
            snippet(propagateCase = true, style = UppercaseStyle.CAPITALIZE_WORDS),
        ).snippets.single()
        assertTrue(out.propagateCase)
        assertEquals(UppercaseStyle.CAPITALIZE_WORDS, out.uppercaseStyle)
    }

    @Test
    fun `multi-line text is written as a block scalar and reads back intact`() {
        val text = "Line one\nLine two\n\nLine four"
        val written = EspansoWriter.encodeMatchFile(listOf(snippet(text = text)))
        assertTrue("expected a block scalar:\n${written.text}", written.text.contains("replace: |-"))
        assertEquals(text, roundTrip(snippet(text = text)).snippets.single().text)
    }

    @Test
    fun `text a block scalar cannot carry falls back to a quoted line`() {
        // A line ending in a space would lose it to the block's indentation.
        val text = "trailing \nspace"
        val written = EspansoWriter.encodeMatchFile(listOf(snippet(text = text)))
        assertFalse(written.text.contains("replace: |-"))
        assertEquals(text, roundTrip(snippet(text = text)).snippets.single().text)
    }

    @Test
    fun `the cursor token becomes espanso's cursor hint and comes back`() {
        val text = "<div>${SnippetVariable.CURSOR.token}</div>"
        val written = EspansoWriter.encodeMatchFile(listOf(snippet(text = text)))
        assertTrue(written.text.contains("\$|\$"))
        assertEquals(text, roundTrip(snippet(text = text)).snippets.single().text)
    }

    @Test
    fun `the clipboard token becomes a clipboard variable and comes back`() {
        val text = "see ${SnippetVariable.CLIP.token} please"
        val written = EspansoWriter.encodeMatchFile(listOf(snippet(text = text)))
        assertTrue(written.text.contains("type: clipboard"))
        assertEquals(text, roundTrip(snippet(text = text)).snippets.single().text)
    }

    @Test
    fun `a date token becomes a date variable with the right strftime`() {
        val written = EspansoWriter.encodeMatchFile(listOf(snippet(text = "on {date+86400:dd/MM/yyyy}")))
        assertTrue(written.text.contains("type: date"))
        assertTrue(written.text.contains("%d/%m/%Y"))
        assertTrue(written.text.contains("offset: 86400"))
        assertEquals("on {date+86400:dd/MM/yyyy}", roundTrip(snippet(text = "on {date+86400:dd/MM/yyyy}")).snippets.single().text)
    }

    @Test
    fun `a random token round-trips`() {
        val text = "{random:one|two|three}"
        assertEquals(text, roundTrip(snippet(text = text)).snippets.single().text)
    }

    @Test
    fun `tokens espanso cannot say are left as they stand and reported`() {
        val written = EspansoWriter.encodeMatchFile(listOf(snippet(text = "id ${SnippetVariable.UUID.token}")))
        assertTrue(written.text.contains("{uuid}"))
        assertTrue(written.notes.any { it.pluralsRes == EspansoNote.NO_ESPANSO_VARIABLE.pluralsRes })
    }

    @Test
    fun `asking before expanding is reported as something espanso cannot do`() {
        val written = EspansoWriter.encodeMatchFile(listOf(snippet(confirm = true)))
        assertTrue(written.notes.any { it.pluralsRes == EspansoNote.CONFIRM.pluralsRes })
    }

    @Test
    fun `a pattern snippet becomes a regex with named groups and comes back`() {
        val original = snippet(
            trigger = null,
            pattern = "^hello (.+)${'$'}",
            text = "Hello, ${'$'}1! Nice to meet you.",
        )
        val written = EspansoWriter.encodeMatchFile(listOf(original))
        assertTrue("expected a named group:\n${written.text}", written.text.contains("(?P<g1>"))
        assertTrue(written.text.contains("{{g1}}"))

        val out = roundTrip(original).snippets.single()
        assertEquals("^hello (?<g1>.+)${'$'}", out.triggerPattern)
        assertEquals("Hello, ${'$'}1! Nice to meet you.", out.text)
    }

    @Test
    fun `a capture transform is reported as lost`() {
        val written = EspansoWriter.encodeMatchFile(
            listOf(snippet(trigger = null, pattern = "^x (.+)${'$'}", text = "\${1:upper}")),
        )
        assertTrue(written.notes.any { it.pluralsRes == EspansoNote.TRANSFORM.pluralsRes })
    }

    @Test
    fun `a snippet with no trigger at all still gets a key espanso can use`() {
        val written = EspansoWriter.encodeMatchFile(listOf(snippet(trigger = null, label = "My Address")))
        assertTrue(written.text.contains("\":myaddress\""))
        assertNotNull(EspansoFile.read(written.text, "x.yml"))
    }

    @Test
    fun `folders become comments and a disabled one is reported`() {
        val folders = listOf(
            SnippetFolder(id = 1, name = "Work"),
            SnippetFolder(id = 2, name = "Off", enabled = false),
        )
        val written = EspansoWriter.encodeMatchFile(
            listOf(snippet(id = 1, folderId = 1), snippet(id = 2, folderId = 2), snippet(id = 3)),
            folders,
        )
        assertTrue(written.text.contains("# Work"))
        assertTrue(written.text.contains("# Off"))
        assertTrue(written.notes.any { it.pluralsRes == EspansoNote.DISABLED_FOLDER.pluralsRes })
        // Every snippet is written, the disabled one included.
        assertEquals(3, EspansoFile.read(written.text, "x.yml")!!.snippets.size)
    }

    @Test
    fun `a label that yaml would read as something else survives quoting`() {
        for (awkward in listOf("yes", "no", "null", "1.0", "on", "- x", "a: b", "\"q\"", "back\\slash")) {
            val out = roundTrip(snippet(label = awkward, text = awkward, trigger = ":x")).snippets.single()
            assertEquals(awkward, out.label)
            assertEquals(awkward, out.text)
        }
    }

    @Test
    fun `a package archive holds the three files espanso wants`() {
        val manifest = EspansoManifest(
            name = "My Pack!",
            title = "My Pack",
            description = "Some snippets",
            author = "Me",
        )
        val (bytes, _) = EspansoWriter.encodePackage(listOf(snippet()), emptyList(), manifest)
        val entries = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().decodeToString()
            }
        }
        assertEquals(setOf("package.yml", "_manifest.yml", "README.md"), entries.keys)
        // The specification is strict about the name: lowercase, digits, hyphens.
        assertTrue(entries.getValue("_manifest.yml").contains("name: \"my-pack\""))
        assertNotNull(EspansoFile.read(entries.getValue("package.yml"), "package.yml"))

        // And it reads back through the archive reader.
        val back = EspansoPackage.read(ByteArrayInputStream(bytes), "my-pack.zip")
        assertNotNull(back)
        assertEquals("My Pack", back!!.folderName)
        assertEquals(1, back.snippets.size)
    }

    @Test
    fun `package names are sanitized the way the specification requires`() {
        assertEquals("my-package", EspansoManifest.sanitizeName("My Package"))
        assertEquals("my-package1234", EspansoManifest.sanitizeName("my_package1234"))
        assertEquals("nice-package", EspansoManifest.sanitizeName("nice@package"))
        assertEquals("wmkeyboard-snippets", EspansoManifest.sanitizeName("!!!"))
    }

    @Test
    fun `nothing written ever contains a shell variable`() {
        // Belt and braces on the one rule that is not a preference: an export
        // must not hand somebody a file that runs a command on their machine.
        val written = EspansoWriter.encodeMatchFile(
            listOf(snippet(text = "rm -rf / && echo {clip}"), snippet(id = 2, text = "\$(whoami)")),
        )
        assertFalse(written.text.contains("type: shell"))
        assertFalse(written.text.contains("type: script"))
        assertNull(EspansoFile.read(written.text, "x.yml")!!.snippets.firstOrNull { it.text.contains("cmd:") })
    }
}
