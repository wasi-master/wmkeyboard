package com.wasimaster.wmkeyboard.core.snippets.espanso

import com.wasimaster.wmkeyboard.core.snippets.MultiExpand
import com.wasimaster.wmkeyboard.core.snippets.SnippetIndex
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.snippets.SnippetVariable
import com.wasimaster.wmkeyboard.core.snippets.UppercaseStyle
import com.wasimaster.wmkeyboard.content.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every fixture here is hand-written from Espanso's published YAML schema, not
 * copied from a Hub package. Same rule the FlorisBoard and HeliBoard layout
 * readers follow, and for the same reason.
 */
class EspansoFileTest {

    private fun read(yaml: String) = EspansoFile.read(yaml.trimIndent(), "pack.yml")

    private fun noteCount(import: EspansoImport, note: EspansoNote): Int =
        import.notes.firstOrNull { it.pluralsRes == note.pluralsRes }?.quantity ?: 0

    @Test
    fun `reads a plain trigger and replacement`() {
        val import = read(
            """
            matches:
              - trigger: ":sig"
                replace: "Kind regards"
            """,
        )
        assertNotNull(import)
        assertEquals(1, import!!.snippets.size)
        assertEquals(":sig", import.snippets[0].trigger)
        assertEquals("Kind regards", import.snippets[0].text)
        // No label given, so the first line of the text names it.
        assertEquals("Kind regards", import.snippets[0].label)
    }

    @Test
    fun `a triggers list becomes a trigger plus aliases`() {
        val import = read(
            """
            matches:
              - triggers: [":hi", ":hello", ":hey"]
                replace: "Hello there"
                label: Greeting
            """,
        )!!
        val snippet = import.snippets.single()
        assertEquals(":hi", snippet.trigger)
        assertEquals(listOf(":hello", ":hey"), snippet.aliases)
        assertEquals("Greeting", snippet.label)
    }

    @Test
    fun `propagate case and uppercase style come across`() {
        val import = read(
            """
            matches:
              - trigger: "alh"
                replace: "although"
                propagate_case: true
                uppercase_style: capitalize_words
                word: true
            """,
        )!!
        val snippet = import.snippets.single()
        assertTrue(snippet.propagateCase)
        assertEquals(UppercaseStyle.CAPITALIZE_WORDS, snippet.uppercaseStyle)
        // `word: true` is what this app always does, so it costs no note.
        assertEquals(0, noteCount(import, EspansoNote.MID_WORD))
    }

    @Test
    fun `a package that omits word is reported as needing mid-word matching`() {
        val import = read(
            """
            matches:
              - trigger: "alh"
                replace: "although"
            """,
        )!!
        assertEquals(1, noteCount(import, EspansoNote.MID_WORD))
        // The snippet still arrives; only its reach is different.
        assertEquals("alh", import.snippets.single().trigger)
    }

    @Test
    fun `the cursor hint becomes the cursor token`() {
        val import = read(
            """
            matches:
              - trigger: ":div"
                replace: "<div>${'$'}|${'$'}</div>"
            """,
        )!!
        assertEquals("<div>${SnippetVariable.CURSOR.token}</div>", import.snippets.single().text)
    }

    @Test
    fun `a block scalar keeps its line breaks`() {
        val import = read(
            """
            matches:
              - trigger: ":addr"
                replace: |-
                  Line one
                  Line two
            """,
        )!!
        assertEquals("Line one\nLine two", import.snippets.single().text)
    }

    @Test
    fun `echo and clipboard variables are inlined`() {
        val import = read(
            """
            matches:
              - trigger: ":a"
                replace: "Hi {{who}}, see {{clip}}"
                vars:
                  - name: who
                    type: echo
                    params:
                      echo: "Jon"
                  - name: clip
                    type: clipboard
            """,
        )!!
        assertEquals("Hi Jon, see ${SnippetVariable.CLIP.token}", import.snippets.single().text)
    }

    @Test
    fun `global variables resolve, including one built from another`() {
        val import = read(
            """
            global_vars:
              - name: firstname
                type: echo
                params:
                  echo: Jon
              - name: fullname
                type: echo
                params:
                  echo: "{{firstname}} Snow"

            matches:
              - trigger: ":me"
                replace: "I am {{fullname}}"
            """,
        )!!
        assertEquals("I am Jon Snow", import.snippets.single().text)
    }

    @Test
    fun `a date variable becomes a date token with its offset`() {
        val import = read(
            """
            matches:
              - trigger: ":tom"
                replace: "See you {{d}}"
                vars:
                  - name: d
                    type: date
                    params:
                      format: "%d/%m/%Y"
                      offset: 86400
            """,
        )!!
        assertEquals("See you {date+86400:dd/MM/yyyy}", import.snippets.single().text)
    }

    @Test
    fun `a random variable becomes a random token`() {
        val import = read(
            """
            matches:
              - trigger: ":q"
                replace: "{{out}}"
                vars:
                  - name: out
                    type: random
                    params:
                      choices:
                        - "one"
                        - "two"
            """,
        )!!
        assertEquals("{random:one|two}", import.snippets.single().text)
    }

    @Test
    fun `a choice match becomes a snippet with several expansions`() {
        // Espanso's choice is "pick one of these", and so is a snippet with
        // several expansions — nothing is lost, so nothing is reported.
        val import = read(
            """
            matches:
              - trigger: ":q"
                replace: "{{out}}"
                vars:
                  - name: out
                    type: choice
                    params:
                      values:
                        - "one"
                        - label: "Two"
                          id: 2
            """,
        )!!
        val snippet = import.snippets.single()
        assertEquals("one", snippet.text)
        assertEquals(listOf("Two"), snippet.alternates)
        assertEquals(MultiExpand.CHIPS_ONLY, snippet.multiExpand)
        assertEquals(":q", snippet.trigger)
        assertEquals(0, noteCount(import, EspansoNote.CHOICE))
    }

    @Test
    fun `a choice inside a sentence is still a random token`() {
        // Only part of the text would be up for choosing, and a snippet
        // chooses the whole of what it inserts.
        val import = read(
            """
            matches:
              - trigger: ":q"
                replace: "Yours {{out}}, Wasi"
                vars:
                  - name: out
                    type: choice
                    params:
                      values:
                        - "sincerely"
                        - "truly"
            """,
        )!!
        val snippet = import.snippets.single()
        assertEquals("Yours {random:sincerely|truly}, Wasi", snippet.text)
        assertTrue(snippet.alternates.isEmpty())
        assertEquals(1, noteCount(import, EspansoNote.CHOICE))
    }

    @Test
    fun `a choice with one value is not a choice`() {
        val import = read(
            """
            matches:
              - trigger: ":q"
                replace: "{{out}}"
                vars:
                  - name: out
                    type: choice
                    params:
                      values:
                        - "only"
            """,
        )!!
        assertEquals("only", import.snippets.single().text)
        assertTrue(import.snippets.single().alternates.isEmpty())
    }

    @Test
    fun `a shell variable is dropped, never run, and reported`() {
        val import = read(
            """
            matches:
              - trigger: ":ip"
                replace: "My IP is {{output}}"
                vars:
                  - name: output
                    type: shell
                    params:
                      cmd: "curl https://api.ipify.org"
            """,
        )!!
        val snippet = import.snippets.single()
        assertEquals("My IP is ", snippet.text)
        assertFalse(snippet.text.contains("curl"))
        assertEquals(1, noteCount(import, EspansoNote.SHELL))
    }

    @Test
    fun `a script variable is dropped the same way`() {
        val import = read(
            """
            matches:
              - trigger: ":py"
                replace: "out: {{output}}"
                vars:
                  - name: output
                    type: script
                    params:
                      args: [python, "script.py"]
            """,
        )!!
        assertFalse(import.snippets.single().text.contains("python"))
        assertEquals(1, noteCount(import, EspansoNote.SHELL))
    }

    @Test
    fun `a regex match becomes a pattern with numbered captures`() {
        val import = read(
            """
            matches:
              - regex: ":greet\\((?P<person>.*)\\)"
                replace: "Hi {{person}}!"
            """,
        )!!
        val snippet = import.snippets.single()
        assertEquals(":greet\\((?<person>.*)\\)", snippet.triggerPattern)
        assertEquals("Hi ${'$'}1!", snippet.text)
        assertNull(snippet.trigger)
    }

    @Test
    fun `an unnamed group in front shifts the numbering`() {
        val import = read(
            """
            matches:
              - regex: "(a|b):(?P<who>\\w+)"
                replace: "Hi {{who}}"
            """,
        )!!
        assertEquals("Hi ${'$'}2", import.snippets.single().text)
    }

    @Test
    fun `a literal dollar in a regex match is escaped so it is not read as a capture`() {
        val import = read(
            """
            matches:
              - regex: ":cost\\((?P<n>\\d+)\\)"
                replace: "costs ${'$'}1 per {{n}}"
            """,
        )!!
        // The "$1" the author wrote is money, not a capture; the one this
        // reader put in for {{n}} is a capture.
        assertEquals("costs ${'$'}${'$'}1 per ${'$'}1", import.snippets.single().text)
    }

    @Test
    fun `a pattern this app will not run is dropped and the snippet kept`() {
        val import = read(
            """
            matches:
              - regex: "(a+)+${'$'}"
                replace: "boom"
            """,
        )!!
        val snippet = import.snippets.single()
        assertNull(snippet.triggerPattern)
        assertEquals("boom", snippet.text)
        assertEquals(1, noteCount(import, EspansoNote.REGEX))
    }

    @Test
    fun `a form keeps its layout with the caret in the first blank`() {
        val import = read(
            """
            matches:
              - trigger: ":greet"
                form: |
                  Hey [[name]],
                  [[message]]
            """,
        )!!
        val text = import.snippets.single().text
        assertTrue(text.startsWith("Hey ${SnippetVariable.CURSOR.token},"))
        assertTrue(text.contains("[[message]]"))
        assertEquals(1, noteCount(import, EspansoNote.FORM))
    }

    @Test
    fun `markdown and html are flattened to plain text`() {
        val import = read(
            """
            matches:
              - trigger: ":md"
                markdown: "**bold** and [a link](https://example.com)"
              - trigger: ":html"
                html: "<p>one</p><p>two &amp; three</p>"
            """,
        )!!
        assertEquals("bold and a link (https://example.com)", import.snippets[0].text)
        assertEquals("one\ntwo & three", import.snippets[1].text)
        assertEquals(2, noteCount(import, EspansoNote.RICH_TEXT))
    }

    @Test
    fun `an image match is skipped and reported`() {
        val import = read(
            """
            matches:
              - trigger: ":pic"
                image_path: "/home/me/cat.png"
            """,
        )!!
        assertTrue(import.snippets.isEmpty())
        assertEquals(1, noteCount(import, EspansoNote.IMAGE))
        assertEquals(1, import.total)
    }

    @Test
    fun `an app filter is reported rather than imported`() {
        val import = read(
            """
            matches:
              - trigger: ":x"
                replace: "y"
                apps: [chrome.exe]
            """,
        )!!
        assertEquals(1, noteCount(import, EspansoNote.APPS))
        assertEquals("y", import.snippets.single().text)
    }

    @Test
    fun `a trigger made only of symbols is dropped and the snippet kept`() {
        val import = read(
            """
            matches:
              - trigger: "->"
                replace: "→"
            """,
        )!!
        val snippet = import.snippets.single()
        assertNull(snippet.trigger)
        assertEquals("→", snippet.text)
        assertEquals(1, noteCount(import, EspansoNote.SYMBOL_TRIGGER))
    }

    @Test
    fun `a match with nothing to insert is skipped`() {
        val import = read(
            """
            matches:
              - trigger: ":nothing"
              - trigger: ":ok"
                replace: "fine"
            """,
        )!!
        assertEquals(1, import.snippets.size)
        assertEquals(2, import.total)
        assertEquals(1, noteCount(import, EspansoNote.EMPTY))
    }

    @Test
    fun `imports are reported since one file cannot carry the others`() {
        val import = read(
            """
            imports:
              - "other.yml"
            matches:
              - trigger: ":a"
                replace: "b"
            """,
        )!!
        assertEquals(1, noteCount(import, EspansoNote.IMPORTS))
    }

    @Test
    fun `text that already holds one of this app's tokens is reported`() {
        val import = read(
            """
            matches:
              - trigger: ":t"
                replace: "template uses {date} here"
            """,
        )!!
        assertEquals(1, noteCount(import, EspansoNote.TOKEN))
    }

    @Test
    fun `a file that is not a match file is refused`() {
        assertNull(EspansoFile.read("just: some other yaml\n", "x.yml"))
        assertNull(EspansoFile.read("not yaml at all: [", "x.yml"))
        assertFalse(EspansoFile.looksLikeEspanso("""{"format":"wmkeyboard-snippets"}"""))
        assertTrue(EspansoFile.looksLikeEspanso("matches:\n  - trigger: a\n"))
    }

    @Test
    fun `an oversized file keeps the first snippets only`() {
        val yaml = buildString {
            append("matches:\n")
            repeat(EspansoFile.MAX_SNIPPETS + 10) {
                append("  - trigger: \":t$it\"\n    replace: \"r$it\"\n")
            }
        }
        val import = EspansoFile.read(yaml, "big.yml")!!
        assertEquals(EspansoFile.MAX_SNIPPETS, import.snippets.size)
        assertEquals(EspansoFile.MAX_SNIPPETS + 10, import.total)
    }

    @Test
    fun `an imported prefix trigger actually fires`() {
        // The end-to-end point of the whole feature: the trigger convention
        // every Hub package uses has to reach the matcher in a usable shape.
        val import = read(
            """
            matches:
              - trigger: ":shrug"
                replace: "¯\\_(ツ)_/¯"
            """,
        )!!
        val index = SnippetIndex.of(import.snippets)
        assertNull(index.matchTrigger("shrug"))
        val hit = index.matchPrefix("shrug", "hello :")
        assertNotNull(hit)
        assertEquals(":", hit!!.prefix)
        assertEquals("¯\\_(ツ)_/¯", hit.snippet.text)
    }

    @Test
    fun `an imported propagate-case snippet shouts back`() {
        val import = read(
            """
            matches:
              - trigger: "alh"
                replace: "although"
                propagate_case: true
                word: true
            """,
        )!!
        val snippet = import.snippets.single()
        assertEquals(
            "ALTHOUGH",
            SnippetStore.expandWithCursor(
                snippet.text,
                casing = SnippetStore.casingFor(snippet, "ALH"),
            ).text,
        )
        assertEquals(
            "Although",
            SnippetStore.expandWithCursor(
                snippet.text,
                casing = SnippetStore.casingFor(snippet, "Alh"),
            ).text,
        )
        assertEquals(
            "although",
            SnippetStore.expandWithCursor(
                snippet.text,
                casing = SnippetStore.casingFor(snippet, "alh"),
            ).text,
        )
    }

    @Test
    fun `every note kind names a real plural resource`() {
        // A note whose resource id is 0 renders as a crash, not as a line.
        for (note in EspansoNote.entries) {
            assertTrue(note.name, note.pluralsRes != 0)
        }
        assertEquals(R.plurals.core_content_espanso_note_shell, EspansoNote.SHELL.pluralsRes)
    }
}
