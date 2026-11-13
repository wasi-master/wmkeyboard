package com.wasimaster.wmkeyboard.core.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Email addresses inside clips (gap list item 26). */
class ClipEntitiesEmailTest {

    private fun values(text: String, kind: ClipEntityKind) =
        ClipEntities.extract(text).filter { it.kind == kind }.map { it.value }

    @Test fun findsAnAddressInASentence() {
        assertEquals(listOf("sam.lee+news@example.co.uk"), values("Write to sam.lee+news@example.co.uk today.", ClipEntityKind.EMAIL))
        assertEquals(listOf("a@b.io"), values("mailto:a@b.io", ClipEntityKind.EMAIL))
    }

    @Test fun theDomainIsNotAlsoALink() {
        val text = "mail someone@example.com about it"
        assertEquals(listOf("someone@example.com"), values(text, ClipEntityKind.EMAIL))
        assertEquals(emptyList<String>(), values(text, ClipEntityKind.URL))
    }

    @Test fun aLinkWithAUserInItStaysALink() {
        val text = "see https://me@example.com/path now"
        assertEquals(1, values(text, ClipEntityKind.URL).size)
        assertEquals(emptyList<String>(), values(text, ClipEntityKind.EMAIL))
    }

    @Test fun digitsInAnAddressAreNotAPhoneNumber() {
        val text = "reach 4479111234567@example.com"
        assertEquals(listOf("4479111234567@example.com"), values(text, ClipEntityKind.EMAIL))
        assertEquals(emptyList<String>(), values(text, ClipEntityKind.PHONE))
    }

    @Test fun nearMissesAreNotAddresses() {
        assertEquals(emptyList<String>(), values("user@localhost and @handle and a@b.c", ClipEntityKind.EMAIL))
    }

    @Test fun aClipThatIsAnAddressGetsNoChipButStillComposes() {
        val clip = ClipItem(id = 1, text = "sam@example.com", timestamp = 0)
        assertEquals(emptyList<ClipEntity>(), ClipEntities.entitiesIn(listOf(clip)))
        assertEquals("sam@example.com", ClipEntities.emailIn(clip.text))
        assertNull(ClipEntities.emailIn("no address here"))
    }

    @Test fun addressesSortAfterPhonesAndBeforeLinks() {
        val clip = ClipItem(id = 1, text = "Call +1 415 555 0100, mail sam@example.com, or see https://example.com", timestamp = 0)
        assertEquals(
            listOf(ClipEntityKind.PHONE, ClipEntityKind.EMAIL, ClipEntityKind.URL),
            ClipEntities.entitiesIn(listOf(clip)).map { it.kind },
        )
    }
}
