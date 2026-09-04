package com.wasimaster.wmkeyboard.core.snippets

import java.io.File
import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnippetStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixedTime(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 19, 16, 45, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `add update remove roundtrip`() {
        val store = SnippetStore(null)
        val snippet = store.add("Sig", "Cheers,\nWasi")
        assertEquals(1, store.items().size)
        store.update(snippet.copy(label = "Signature", text = "Best,\nWasi"))
        assertEquals("Signature", store.items().single().label)
        assertEquals("Best,\nWasi", store.items().single().text)
        store.remove(snippet.id)
        assertTrue(store.items().isEmpty())
    }

    @Test
    fun `trigger match is case-insensitive and blank triggers are dropped`() {
        val store = SnippetStore(null)
        val snippet = store.add("Sig", "On my way!", trigger = "omw")
        assertEquals(snippet.id, store.matchTrigger("OMW")?.id)
        assertEquals(null, store.matchTrigger("omww"))

        val blank = store.add("Blank", "text", trigger = "   ")
        assertEquals(null, blank.trigger)

        store.update(snippet.copy(trigger = ""))
        assertEquals(null, store.items().first { it.id == snippet.id }.trigger)
        assertEquals(null, store.matchTrigger("omw"))
    }

    @Test
    fun `the first snippet wins a duplicate trigger`() {
        // The linear scan this replaced kept the first match, and a map built
        // the obvious way keeps the last.
        val store = SnippetStore(null)
        val first = store.add("A", "first", trigger = "omw")
        store.add("B", "second", trigger = "OMW")
        assertEquals(first.id, store.matchTrigger("omw")?.id)
    }

    @Test
    fun `a pattern snippet is matched without reloading`() {
        val store = SnippetStore(null)
        assertTrue(!store.hasPatterns())
        store.add(Snippet(id = 0, label = "Greet", text = "Hello, \$1!", triggerPattern = "^hi (.+)$"))
        assertTrue(store.hasPatterns())
        assertEquals(
            "Hello, John!",
            store.matchPattern("hi John", atFieldStart = true)?.text,
        )
        assertTrue(store.couldStartPattern('h'))
        assertTrue(!store.couldStartPattern('z'))
    }

    @Test
    fun `update can clear a pattern`() {
        val store = SnippetStore(null)
        val snippet = store.add(
            Snippet(id = 0, label = "Greet", text = "Hello, \$1!", triggerPattern = "^hi (.+)$"),
        )
        // A whole snippet goes in, so clearing a field means handing back a
        // snippet that does not have it.
        store.update(snippet.copy(trigger = "hey", triggerPattern = null))
        assertEquals(null, store.items().single().triggerPattern)
        assertTrue(!store.hasPatterns())
    }

    @Test
    fun `add keeps every field of the snippet it is given`() {
        // Import and add-on installation both come through here, so a field
        // dropped on this path is a pack that installs with no trigger and no
        // complaint.
        val store = SnippetStore(null)
        val added = store.add(
            Snippet(
                id = 99,
                label = "  Greet  ",
                text = "Hello, \$1!",
                triggerPattern = "^hi (.+)$",
                triggerWords = 4,
            ),
        )
        assertEquals("Greet", added.label)
        assertEquals("^hi (.+)$", added.triggerPattern)
        assertEquals(4, added.triggerWords)
        // The id in the file is never trusted.
        assertTrue(added.id != 99L)
    }

    @Test
    fun `persists and reloads`() {
        val file = File(tmp.root, "snippets.json")
        val store = SnippetStore(file)
        store.add("Addr", "12 Road, Dhaka")
        store.add("Mail", "me@example.com")
        store.save()

        val reloaded = SnippetStore(file)
        assertEquals(listOf("Addr", "Mail"), reloaded.items().map { it.label })
        // New ids continue after the highest persisted one.
        val next = reloaded.add("X", "y")
        assertTrue(next.id > reloaded.items().first().id)
    }

    @Test
    fun `a pattern survives a save and reload`() {
        val file = File(tmp.root, "snippets.json")
        val store = SnippetStore(file)
        store.add(
            Snippet(
                id = 0,
                label = "Greet",
                text = "Hello, \$1!",
                triggerPattern = "^hi (.+)$",
                triggerWords = 2,
            ),
        )
        store.save()

        val reloaded = SnippetStore(file).items().single()
        assertEquals("^hi (.+)$", reloaded.triggerPattern)
        assertEquals(2, reloaded.triggerWords)
    }

    @Test
    fun `the ask-first flag survives an edit and a reload`() {
        val file = File(tmp.newFolder(), "snippets.json")
        val store = SnippetStore(file)
        val snippet = store.add(
            Snippet(id = 0, label = "Reply", text = "Thanks!", trigger = "ty", confirm = true),
        )
        assertTrue(store.matchTrigger("ty")!!.confirm)
        store.save()
        assertTrue(SnippetStore(file).matchTrigger("TY")!!.confirm)

        // The dialog hands every field back on save, so the flag has to be
        // clearable through the same call that sets it.
        store.update(snippet.copy(confirm = false))
        store.save()
        assertTrue(!SnippetStore(file).matchTrigger("ty")!!.confirm)
    }

    @Test
    fun `a folder that is switched off disarms its triggers and nothing else`() {
        val store = SnippetStore(null)
        val work = store.addFolder("Work")
        val filed = store.add(
            Snippet(id = 0, label = "Sig", text = "Regards", trigger = "sig", folderId = work.id),
        )
        store.add(Snippet(id = 0, label = "Way", text = "On my way!", trigger = "omw"))
        assertEquals(filed.id, store.matchTrigger("sig")?.id)

        store.setFolderEnabled(work.id, false)
        // Gone from the index...
        assertEquals(null, store.matchTrigger("sig"))
        // ...and from nowhere else: the panel still lists it and a tap still
        // inserts it, which is the whole point of the switch.
        assertEquals(2, store.items().size)
        assertTrue(store.items().any { it.id == filed.id })
        // A snippet outside the folder is untouched.
        assertEquals("On my way!", store.matchTrigger("omw")?.text)

        store.setFolderEnabled(work.id, true)
        assertEquals(filed.id, store.matchTrigger("sig")?.id)
    }

    @Test
    fun `a folder that is switched off disarms its patterns too`() {
        val store = SnippetStore(null)
        val folder = store.addFolder("Greetings")
        store.add(
            Snippet(
                id = 0,
                label = "Greet",
                text = "Hello, \$1!",
                triggerPattern = "^hi (.+)$",
                folderId = folder.id,
            ),
        )
        assertTrue(store.hasPatterns())
        store.setFolderEnabled(folder.id, false)
        // Every question the index answers has to agree, not just the match:
        // the keystroke gate asks these before it reads the field at all.
        assertTrue(!store.hasPatterns())
        assertTrue(!store.couldStartPattern('h'))
        assertEquals(null, store.matchPattern("hi John", atFieldStart = true))
    }

    @Test
    fun `deleting a folder keeps its snippets unless asked otherwise`() {
        val store = SnippetStore(null)
        val folder = store.addFolder("Work")
        store.add(Snippet(id = 0, label = "Sig", text = "Regards", folderId = folder.id))
        store.removeFolder(folder.id)
        assertTrue(store.folders().isEmpty())
        assertEquals(1, store.items().size)
        assertEquals(0L, store.items().single().folderId)

        val second = store.addFolder("Work")
        store.add(Snippet(id = 0, label = "Note", text = "…", folderId = second.id))
        store.removeFolder(second.id, withSnippets = true)
        assertEquals(listOf("Sig"), store.items().map { it.label })
    }

    @Test
    fun `an empty folder is pruned and a folder with anything left in it is not`() {
        val store = SnippetStore(null)
        val pack = store.addFolder("Pack")
        val own = store.add(Snippet(id = 0, label = "A", text = "a", folderId = pack.id))
        store.add(Snippet(id = 0, label = "Mine", text = "b", folderId = pack.id))
        // What uninstalling a pack does: drop the pack's own rows, then offer
        // the folder up. Something else is in it, so it stays.
        store.remove(own.id)
        store.removeFolderIfEmpty(pack.id)
        assertEquals(1, store.folders().size)

        store.remove(store.items().single().id)
        store.removeFolderIfEmpty(pack.id)
        assertTrue(store.folders().isEmpty())
    }

    @Test
    fun `addAll recreates a file's folders under fresh ids`() {
        val store = SnippetStore(null)
        // Something already here, so the ids in the file cannot accidentally
        // line up with the ids they are given.
        store.addFolder("Existing")
        val added = store.addAll(
            snippets = listOf(
                Snippet(id = 7, label = "A", text = "a", folderId = 1),
                Snippet(id = 8, label = "B", text = "b", folderId = 1),
                Snippet(id = 9, label = "C", text = "c", folderId = 0),
            ),
            folders = listOf(SnippetFolder(id = 1, name = "Replies")),
        )
        val replies = store.folders().single { it.name == "Replies" }
        assertTrue(replies.id != 1L)
        assertEquals(listOf(replies.id, replies.id, 0L), added.map { it.folderId })
    }

    @Test
    fun `addAll files everything under the fallback when the file declares no folders`() {
        // How a snippet pack installs: one folder named after the pack, and
        // whatever the file thought its own grouping was is flattened into it.
        val store = SnippetStore(null)
        val pack = store.addFolder("Dev shortcuts")
        val added = store.addAll(
            snippets = listOf(
                Snippet(id = 1, label = "A", text = "a", folderId = 4),
                Snippet(id = 2, label = "B", text = "b"),
            ),
            fallbackFolderId = pack.id,
        )
        assertEquals(listOf(pack.id, pack.id), added.map { it.folderId })
    }

    @Test
    fun `folders survive a save and reload`() {
        val file = File(tmp.newFolder(), "snippets.json")
        val store = SnippetStore(file)
        val work = store.addFolder("Work")
        store.setFolderEnabled(work.id, false)
        store.add(Snippet(id = 0, label = "Sig", text = "Regards", trigger = "sig", folderId = work.id))
        store.save()

        val reloaded = SnippetStore(file)
        val folder = reloaded.folders().single()
        assertEquals("Work", folder.name)
        assertTrue(!folder.enabled)
        assertEquals(folder.id, reloaded.items().single().folderId)
        // The switch has to survive the trip too, or a folder someone silenced
        // starts firing again on the next launch.
        assertEquals(null, reloaded.matchTrigger("sig"))
        // Fresh folder ids continue past the highest persisted one.
        assertTrue(reloaded.addFolder("Other").id > folder.id)
    }

    @Test
    fun `a snippet pointing at a folder nobody declared ends up in none`() {
        val file = File(tmp.newFolder(), "snippets.json")
        file.writeText(
            """{"snippets":[{"id":1,"label":"A","text":"a","folderId":42}],"folders":[]}""",
        )
        assertEquals(0L, SnippetStore(file).items().single().folderId)
    }

    @Test
    fun `an edit files the snippet where the edited snippet says`() {
        // The whole snippet goes in and the whole snippet is what is stored,
        // folder included: an editor that means to leave the filing alone
        // hands back the folder it was given.
        val store = SnippetStore(null)
        val folder = store.addFolder("Work")
        val snippet = store.add(Snippet(id = 0, label = "Sig", text = "Regards", folderId = folder.id))
        store.update(snippet.copy(label = "Signature", text = "Best"))
        assertEquals(folder.id, store.items().single().folderId)
        store.update(snippet.copy(folderId = 0))
        assertEquals(0L, store.items().single().folderId)
    }

    @Test
    fun `update keeps the stored id and creation time`() {
        val store = SnippetStore(null)
        val snippet = store.add(Snippet(id = 0, label = "Sig", text = "Regards"), now = 1_000L)
        store.update(snippet.copy(id = snippet.id, createdAt = 9_999L, text = "Best"))
        val stored = store.items().single()
        assertEquals(snippet.id, stored.id)
        assertEquals(1_000L, stored.createdAt)
        assertEquals("Best", stored.text)
    }

    @Test
    fun `update ignores a snippet the store does not have`() {
        val store = SnippetStore(null)
        store.add(Snippet(id = 0, label = "Sig", text = "Regards"))
        store.update(Snippet(id = 404, label = "Ghost", text = "Boo"))
        assertEquals("Sig", store.items().single().label)
    }

    // ---- several expansions ----------------------------------------------

    @Test
    fun `expansions are the text and then the alternates`() {
        val snippet = Snippet(id = 1, label = "Greet", text = "Hi", alternates = listOf("Hello", "Hey"))
        assertEquals(listOf("Hi", "Hello", "Hey"), snippet.expansions())
        assertTrue(snippet.hasChoices())
        assertEquals(listOf("Hi"), Snippet(id = 1, label = "Greet", text = "Hi").expansions())
        assertTrue(!Snippet(id = 1, label = "Greet", text = "Hi").hasChoices())
    }

    @Test
    fun `withExpansions makes the first one the default`() {
        val snippet = Snippet(id = 1, label = "Greet", text = "Hi", alternates = listOf("Hello"))
        val moved = snippet.withExpansions(listOf("Hello", "Hi"))
        assertEquals("Hello", moved.text)
        assertEquals(listOf("Hi"), moved.alternates)
        // Nothing to insert is not a snippet, so an empty list changes nothing.
        assertEquals(snippet, snippet.withExpansions(listOf("", "  ")))
    }

    @Test
    fun `alternates drop blanks and repeats on the way in`() {
        val store = SnippetStore(null)
        val stored = store.add(
            Snippet(
                id = 0,
                label = "Greet",
                text = "Hi",
                alternates = listOf("Hello", "", "Hi", "Hello", "Hey"),
            ),
        )
        assertEquals(listOf("Hello", "Hey"), stored.alternates)
    }

    @Test
    fun `alternates are capped`() {
        val store = SnippetStore(null)
        val many = (1..SnippetStore.MAX_ALTERNATES + 20).map { "line $it" }
        val stored = store.add(Snippet(id = 0, label = "Long", text = "first", alternates = many))
        assertEquals(SnippetStore.MAX_ALTERNATES, stored.alternates.size)
    }

    @Test
    fun `a snippet may carry far more than sixteen spellings`() {
        // The old cap was 16, which is a limit on how many ways a person may
        // spell a thing — the point of the feature.
        val store = SnippetStore(null)
        val spellings = (1..100).map { "way$it" }
        val stored = store.add(
            Snippet(id = 0, label = "Many", text = "text", trigger = spellings.first(), aliases = spellings.drop(1)),
        )
        assertEquals(100, stored.spellings().size)
        assertEquals(stored.id, store.matchTrigger("WAY99")?.id)
    }

    // ---- linked snippets --------------------------------------------------

    @Test
    fun `children drop self, zero and repeats`() {
        val store = SnippetStore(null)
        val child = store.add(Snippet(id = 0, label = "Asia", text = "Asia"))
        val parent = store.add(
            Snippet(
                id = 0,
                label = "Continent",
                text = "Continent",
                children = listOf(child.id, 0L, child.id),
            ),
        )
        assertEquals(listOf(child.id), parent.children)
        store.update(parent.copy(children = listOf(parent.id, child.id)))
        assertEquals(listOf(child.id), store.items().first { it.id == parent.id }.children)
    }

    @Test
    fun `childrenOf skips links to snippets that are gone`() {
        val store = SnippetStore(null)
        val child = store.add(Snippet(id = 0, label = "Asia", text = "Asia"))
        val parent = store.add(
            Snippet(id = 0, label = "Continent", text = "Continent", children = listOf(child.id, 404L)),
        )
        assertEquals(listOf("Asia"), store.childrenOf(parent).map { it.label })
    }

    @Test
    fun `removing a snippet unlinks it from everything that pointed at it`() {
        val store = SnippetStore(null)
        val child = store.add(Snippet(id = 0, label = "Asia", text = "Asia"))
        val a = store.add(Snippet(id = 0, label = "Continent", text = "C", children = listOf(child.id)))
        val b = store.add(Snippet(id = 0, label = "Region", text = "R", children = listOf(child.id)))
        store.remove(child.id)
        assertTrue(store.items().first { it.id == a.id }.children.isEmpty())
        assertTrue(store.items().first { it.id == b.id }.children.isEmpty())
    }

    @Test
    fun `deleting a folder with its snippets unlinks them too`() {
        val store = SnippetStore(null)
        val folder = store.addFolder("Places")
        val child = store.add(
            Snippet(id = 0, label = "Asia", text = "Asia", folderId = folder.id),
        )
        val parent = store.add(Snippet(id = 0, label = "Continent", text = "C", children = listOf(child.id)))
        store.removeFolder(folder.id, withSnippets = true)
        assertTrue(store.items().first { it.id == parent.id }.children.isEmpty())
    }

    @Test
    fun `addAll re-points links at the ids it hands out`() {
        val store = SnippetStore(null)
        // Ids as they were in some other phone's file, including one the file
        // never declared.
        val file = listOf(
            Snippet(id = 7, label = "Continent", text = "Continent", children = listOf(8L, 99L)),
            Snippet(id = 8, label = "Asia", text = "Asia"),
        )
        val added = store.addAll(file)
        val parent = store.items().first { it.label == "Continent" }
        assertEquals(listOf(added[1].id), parent.children)
        assertTrue(added[1].id != 8L)
    }

    @Test
    fun `links and tags survive a save and a reload`() {
        val file = File(tmp.newFolder(), "snippets.json")
        val store = SnippetStore(file)
        val child = store.add(Snippet(id = 0, label = "Asia", text = "Asia"))
        store.add(
            Snippet(
                id = 0,
                label = "Continent",
                text = "Continent",
                alternates = listOf("Landmass"),
                children = listOf(child.id),
                tags = listOf("geography"),
                multiExpand = MultiExpand.INSERT_FIRST,
            ),
        )
        store.save()
        val reloaded = SnippetStore(file).items().first { it.label == "Continent" }
        assertEquals(listOf("Landmass"), reloaded.alternates)
        assertEquals(listOf(child.id), reloaded.children)
        assertEquals(listOf("geography"), reloaded.tags)
        assertEquals(MultiExpand.INSERT_FIRST, reloaded.multiExpand)
    }

    @Test
    fun `a file written before any of this loads unchanged`() {
        val file = File(tmp.newFolder(), "snippets.json")
        file.writeText(
            """{"snippets":[{"id":1,"label":"Sig","text":"Regards","trigger":"sig"}],"folders":[]}""",
        )
        val stored = SnippetStore(file).items().single()
        assertEquals("Regards", stored.text)
        assertEquals(listOf("Regards"), stored.expansions())
        assertTrue(stored.alternates.isEmpty() && stored.children.isEmpty() && stored.tags.isEmpty())
        assertEquals(MultiExpand.DEFAULT, stored.multiExpand)
    }

    @Test
    fun `a word this build does not know is not a broken file`() {
        // Without coercion one unrecognised enum value throws out of the whole
        // decode, and every snippet in the file goes with it.
        val file = File(tmp.newFolder(), "snippets.json")
        file.writeText(
            """{"snippets":[{"id":1,"label":"Sig","text":"Regards","multiExpand":"from_the_future"}]}""",
        )
        val stored = SnippetStore(file).items().single()
        assertEquals("Regards", stored.text)
        assertEquals(MultiExpand.DEFAULT, stored.multiExpand)
    }

    // ---- tags -------------------------------------------------------------

    @Test
    fun `tags are trimmed, deduped ignoring case and listed across the store`() {
        val store = SnippetStore(null)
        val stored = store.add(
            Snippet(id = 0, label = "Asia", text = "Asia", tags = listOf(" geography ", "Geography", "")),
        )
        assertEquals(listOf("geography"), stored.tags)
        store.add(Snippet(id = 0, label = "Iran", text = "Iran", tags = listOf("Places")))
        assertEquals(listOf("geography", "Places"), store.tags())
    }

    @Test
    fun `expands date and time variables`() {
        val expanded = SnippetStore.expand("Meeting on {date} at {time}", now = fixedTime())
        if (Locale.getDefault().language == "en") {
            assertEquals("Meeting on 19 Jul 2026 at 16:45", expanded)
        } else {
            assertTrue(expanded.contains("2026") && expanded.contains("16:45"))
        }
    }

    @Test
    fun `expands datetime variable`() {
        val expanded = SnippetStore.expand("{datetime}", now = fixedTime())
        assertTrue(expanded.contains("2026") && expanded.contains("16:45"))
    }

    @Test
    fun `expands clipboard variable`() {
        assertEquals(
            "See: pasted-thing",
            SnippetStore.expand("See: {clip}", clipboard = "pasted-thing"),
        )
        assertEquals("See: ", SnippetStore.expand("See: {clip}", clipboard = null))
    }

    @Test
    fun `text without variables is untouched`() {
        assertEquals("plain text", SnippetStore.expand("plain text"))
    }
}
