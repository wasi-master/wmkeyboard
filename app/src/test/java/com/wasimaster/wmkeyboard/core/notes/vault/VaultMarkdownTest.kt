package com.wasimaster.wmkeyboard.core.notes.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Front matter and wiki-links as the notes vault reads them (#1211). */
class VaultMarkdownTest {

    @Test
    fun frontMatterSplitsOffAndJoinsBackByteForByte() {
        val text = "---\ntitle: Trip\ntags: [travel, \"to do\"]\n---\n# Packing\n- socks\n"
        val split = VaultMarkdown.split(text)
        assertEquals("---\ntitle: Trip\ntags: [travel, \"to do\"]\n---\n", split.frontMatter)
        assertEquals("# Packing\n- socks\n", split.body)
        assertEquals(text, split.joined())
        assertEquals("Trip", VaultMarkdown.title(split.frontMatter, "trip.md"))
        assertEquals(listOf("travel", "to do"), VaultMarkdown.tags(split.frontMatter))
        assertEquals(listOf("a", "b"), VaultMarkdown.tags("---\ntags: a #b\n---\n"))
    }

    @Test
    fun aRuleWithNoClosingFenceIsBody() {
        val text = "---\nJust a rule above some text\n"
        assertEquals("", VaultMarkdown.split(text).frontMatter)
        assertEquals(text, VaultMarkdown.split(text).body)
    }

    @Test
    fun tagsAsAListUnderTheKey() {
        val fm = "---\ntags:\n  - work\n  - \"#urgent\"\naliases: []\n---\n"
        assertEquals(listOf("work", "urgent"), VaultMarkdown.tags(fm))
    }

    @Test
    fun titleFallsBackToTheFileName() {
        assertEquals("Shopping list", VaultMarkdown.title("", "Shopping list.md"))
    }

    @Test
    fun wikiLinksReadTargetHeadingAndAlias() {
        val links = VaultMarkdown.wikiLinks("See [[Trip#Packing|the list]] and [[Ideas]], again [[ideas]], ![[photo.png]].")
        assertEquals(listOf("Trip", "Ideas", "photo.png"), links.map { it.target })
        assertEquals("the list", links[0].shown)
        assertEquals("Packing", links[0].heading)
    }

    @Test
    fun displayTurnsLinksIntoClaimableMarkdownLinks() {
        val shown = VaultMarkdown.forDisplay("Go to [[My note|here]] and ![[pic.png]]")
        assertEquals("Go to [here](wmvault:My%20note) and `pic.png`", shown)
        assertEquals("My note", VaultMarkdown.targetOf("wmvault:My%20note"))
        assertNull(VaultMarkdown.targetOf("https://example.com"))
    }

    @Test
    fun fileNamesDropWhatNoFileSystemTakes() {
        assertEquals("a b c.md", VaultMarkdown.fileNameFor("a/b:c"))
        assertEquals("Notes.md", VaultMarkdown.fileNameFor("Notes.md"))
        assertNull(VaultMarkdown.fileNameFor("  /// "))
    }

    @Test
    fun capturesGoOnTheEndOnALineOfTheirOwn() {
        assertEquals("one\ntwo\n", VaultMarkdown.appended("one", "two"))
        assertEquals("one\ntwo\n", VaultMarkdown.appended("one\n", "two  "))
        assertEquals("two\n", VaultMarkdown.appended("", "two"))
        assertEquals("Buy milk and eggs", VaultMarkdown.captureName("- Buy milk and eggs\nlater", "stamp"))
        assertEquals("stamp", VaultMarkdown.captureName("   \n", "stamp"))
    }

    @Test
    fun notesAreMarkdownAndText() {
        assertTrue(VaultMarkdown.isNote("a.md"))
        assertTrue(VaultMarkdown.isNote("b.TXT"))
        assertTrue(!VaultMarkdown.isNote("c.png"))
    }
}
