package com.wasimaster.wmkeyboard.core.snippets

import com.wasimaster.wmkeyboard.content.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every snippet as one text document: [SnippetText]. */
class SnippetTextTest {

    private val folders = listOf(SnippetFolder(id = 7, name = "Work"), SnippetFolder(id = 9, name = "Home", enabled = false))

    private val rich = listOf(
        Snippet(id = 1, label = "omw", text = "On my way!", trigger = "omw", createdAt = 10),
        Snippet(
            id = 2,
            label = "Address",
            text = "12 Example Road\nTown",
            trigger = "addr",
            aliases = listOf("adr", "address"),
            propagateCase = true,
            uppercaseStyle = UppercaseStyle.CAPITALIZE_WORDS,
            confirm = true,
            immediate = true,
            insideWords = true,
            folderId = 7,
            alternates = listOf("PO Box 7", "Line one\n\nLine three"),
            children = listOf(1, 3),
            tags = listOf("home", "post, office"),
            multiExpand = MultiExpand.INSERT_FIRST,
            createdAt = 20,
        ),
        Snippet(
            id = 3,
            label = "Double",
            text = "$1 $1",
            triggerPattern = "(\\w+) x2",
            triggerWords = 2,
            folderId = 9,
            createdAt = 30,
        ),
        Snippet(id = 4, label = "Cat", text = "", trigger = "cat", image = "s_cat.png", createdAt = 40),
        Snippet(id = 5, label = "Panel only", text = "No trigger at all", createdAt = 50),
    )

    private fun roundTrip(snippets: List<Snippet>, folderList: List<SnippetFolder> = folders): SnippetText.Plan {
        val doc = SnippetText.encode(snippets, folderList, header = listOf("a comment"))
        val parsed = SnippetText.parse(doc)
        assertEquals(doc, emptyList<SnippetText.Problem>(), parsed.problems)
        return SnippetText.plan(snippets, folderList, parsed, knownImages = setOf("s_cat.png"))
    }

    @Test fun everyFieldSurvivesTheRoundTrip() {
        val plan = roundTrip(rich)
        assertTrue("added ${plan.added} changed ${plan.changed} removed ${plan.removed}", plan.isEmpty)
        assertEquals(rich, plan.snippets)
    }

    @Test fun textThatLooksLikeMarkupIsEscaped() {
        val awkward = listOf(
            "@john thanks",
            "=== not a header ===",
            "---",
            "--- dashes",
            "\n\nstarts with blank lines",
            "ends with blank lines\n\n",
            "\\alpha stays as typed",
            "\\@ a backslash then an at",
            "\\",
            "  ",
            "middle\n\n\nblank lines\n   \nkept",
            "# not a comment inside a snippet",
        ).mapIndexed { i, text -> Snippet(id = i + 1L, label = "s$i", text = text, trigger = "s$i") }
        val plan = roundTrip(awkward, emptyList())
        assertTrue("changed ${plan.changed.map { it.text }}", plan.isEmpty)
    }

    @Test fun aPlainDocumentReads() {
        val parsed = SnippetText.parse(
            """
            # my snippets

            === omw ===
            On my way!

            === sig ===
            @label: Signature
            @folder: Work
            Best,
            Sam
            ---
            Cheers, Sam
            """.trimIndent(),
        )
        assertEquals(emptyList<SnippetText.Problem>(), parsed.problems)
        assertEquals(listOf("omw", "sig"), parsed.entries.map { it.snippet.trigger })
        val sig = parsed.entries[1]
        assertEquals("Signature", sig.snippet.label)
        assertEquals("Best,\nSam", sig.snippet.text)
        assertEquals(listOf("Cheers, Sam"), sig.snippet.alternates)
        assertEquals("Work", sig.folder)
        assertEquals(6, sig.line)
        assertEquals("omw", parsed.entries[0].snippet.label)
    }

    @Test fun mistakesAreReportedByLine() {
        val parsed = SnippetText.parse(
            listOf(
                "stray text", // 1
                "=== a ===", // 2
                "@lable: oops", // 3
                "text", // 4
                "=== b ===", // 5
                "@words: nine", // 6
                "text", // 7
                "=== c ===", // 8
                "", // 9
                "=== d ===", // 10
                "@pattern: (unclosed", // 11
                "x", // 12
                "=== e ===", // 13
                "=== half a header", // 14
                "=== ===", // 15
                "no trigger, no label", // 16
                "=== f ===", // 17
                "@flags: confirm, sometimes", // 18
                "fine otherwise", // 19
            ).joinToString("\n"),
        )
        val byLine = parsed.problems.associate { it.line to it.message.stringRes }
        assertEquals(R.string.core_content_snippet_text_before_header, byLine[1])
        assertEquals(R.string.core_content_snippet_text_unknown_setting, byLine[3])
        assertEquals(R.string.core_content_snippet_text_bad_value, byLine[6])
        assertEquals(R.string.core_content_snippet_text_no_text, byLine[8])
        assertEquals(R.string.core_content_snippet_text_bad_pattern, byLine[11])
        assertEquals(R.string.core_content_snippet_text_bad_header, byLine[14])
        assertEquals(R.string.core_content_snippet_text_no_trigger, byLine[15])
        assertEquals(R.string.core_content_snippet_text_bad_value, byLine[18])
        // Every block had a mistake, so none was read.
        assertEquals(emptyList<String>(), parsed.entries.map { it.key })
    }

    @Test fun linksAndPicturesMustNameSomethingReal() {
        val parsed = SnippetText.parse("=== a ===\n@link: nowhere\n@picture: s_new.png\ntext")
        val plan = SnippetText.plan(emptyList(), emptyList(), parsed, knownImages = setOf("s_cat.png"))
        assertEquals(
            setOf(R.string.core_content_snippet_text_unknown_link, R.string.core_content_snippet_text_unknown_picture),
            plan.problems.map { it.message.stringRes }.toSet(),
        )
        assertTrue(plan.problems.all { it.line == 1 })
    }

    @Test fun theDiffNamesWhatSavingWouldDo() {
        val doc = SnippetText.encode(rich, folders)
            .replace("On my way!", "Leaving now!")
            .replace("=== cat ===", "=== kitty ===")
            .replace("=== ===\n@label: Panel only\nNo trigger at all\n", "")
            .plus("\n=== brb ===\nBe right back\n")
        val plan = SnippetText.plan(rich, folders, SnippetText.parse(doc), setOf("s_cat.png"))
        assertEquals(emptyList<SnippetText.Problem>(), plan.problems)
        assertEquals(listOf("brb"), plan.added.map { it.trigger })
        // The renamed trigger matched on its picture and text, so it is a change, not a delete and an add.
        assertEquals(listOf(1L, 4L), plan.changed.map { it.id })
        assertEquals("kitty", plan.changed[1].trigger)
        assertEquals(40L, plan.changed[1].createdAt)
        assertEquals(listOf(5L), plan.removed.map { it.id })
        assertTrue(plan.added.single().id < 0)
    }

    @Test fun reorderingAloneIsSaved() {
        val doc = SnippetText.encode(rich.reversed(), folders)
        val plan = SnippetText.plan(rich, folders, SnippetText.parse(doc), setOf("s_cat.png"))
        assertTrue(plan.reordered)
        assertFalse(plan.isEmpty)
        assertEquals(rich.reversed().map { it.id }, plan.snippets.map { it.id })
    }

    @Test fun savingMakesFoldersIdsAndLinks() {
        val store = SnippetStore(null)
        store.add(Snippet(id = 0, label = "omw", text = "On my way!", trigger = "omw"))
        val doc = SnippetText.encode(store.items(), store.folders()) +
            "\n=== brb ===\n@folder: Chat\n@link: omw\nBe right back\n" +
            "\n=== gtg ===\n@folder: Chat\n@link: brb\nGot to go\n"
        val plan = SnippetText.plan(store.items(), store.folders(), SnippetText.parse(doc), emptySet())
        assertEquals(listOf("Chat"), plan.newFolders)
        val made = store.replaceFromText(plan)
        assertEquals(listOf("Chat"), made.map { it.name })
        val items = store.items()
        assertEquals(listOf("omw", "brb", "gtg"), items.map { it.trigger })
        assertTrue(items.all { it.id > 0 })
        val omw = items[0]
        val brb = items[1]
        assertEquals(listOf(omw.id), brb.children)
        assertEquals(listOf(brb.id), items[2].children)
        assertEquals(made.single().id, brb.folderId)
        assertEquals(0L, omw.folderId)
        // A second look at the saved list finds nothing left to do.
        val again = SnippetText.plan(items, store.folders(), SnippetText.parse(SnippetText.encode(items, store.folders())), emptySet())
        assertTrue(again.isEmpty)
    }

    @Test fun aTextFileImportsThroughThePayloadReader() {
        val doc = "# from another phone\n=== omw ===\n@folder: Chat\nOn my way!\n\n=== pic ===\n@picture: s_cat.png\nA cat\n\n=== bad ===\n@nope: 1\nx\n"
        val parsed = SnippetPayload.readText(doc, "snippets.txt")
        assertNotNull(parsed)
        assertEquals(SnippetPayload.Source.TEXT, parsed!!.source)
        assertEquals(listOf("omw", "pic"), parsed.snippets.map { it.trigger })
        // A text file carries no pictures: the snippet comes in as its words.
        assertNull(parsed.snippets[1].image)
        assertEquals(listOf("Chat"), parsed.folders.map { it.name })
        assertEquals(parsed.folders.single().id, parsed.snippets[0].folderId)
        assertEquals(
            listOf(R.string.core_content_snippet_text_skipped_line, 0),
            parsed.notes.map { it.stringRes },
        )
    }

    @Test fun onlyAHeaderFirstReadsAsText() {
        assertTrue(SnippetText.looksLikeText("\n# hi\n=== a ===\nb"))
        assertFalse(SnippetText.looksLikeText("trigger,text\nomw,On my way"))
        assertFalse(SnippetText.looksLikeText("matches:\n  - trigger: omw"))
    }
}
