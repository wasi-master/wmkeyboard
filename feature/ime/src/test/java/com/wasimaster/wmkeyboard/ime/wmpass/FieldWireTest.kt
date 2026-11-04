package com.wasimaster.wmkeyboard.ime.wmpass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldWireTest {
    @Test
    fun readsTheTagWmPassWrites() {
        // The same line WM Pass's FieldWireTest writes.
        val raw = "com.wasimaster.suite.field=1;token=Kx3m9QaZ0bLw7RtE;actions=wifi_qr+wifi_saved"
        assertEquals(FieldWire.Tag("Kx3m9QaZ0bLw7RtE", listOf(FieldWire.WIFI_QR, FieldWire.WIFI_SAVED)), FieldWire.parse(raw))
        assertEquals(raw, FieldWire.tag("Kx3m9QaZ0bLw7RtE", listOf(FieldWire.WIFI_QR, FieldWire.WIFI_SAVED)))
    }

    @Test
    fun findsItAmongOtherAppsOptions() {
        val raw = "nm,com.wasimaster.suite.field=1;token=abcdefgh12;actions=card_scan, com.example.other=1"
        assertEquals(FieldWire.Tag("abcdefgh12", listOf(FieldWire.CARD_SCAN)), FieldWire.parse(raw))
    }

    @Test
    fun buttonsWithNoWordsHereAreSkipped() {
        val later = "com.wasimaster.suite.field=2;token=abcdefgh12;colour=teal;actions=card_scan+print_card+card_scan"
        assertEquals(FieldWire.Tag("abcdefgh12", listOf(FieldWire.CARD_SCAN)), FieldWire.parse(later))
        assertNull(FieldWire.parse("com.wasimaster.suite.field=1;token=abcdefgh12;actions=print_card"))
    }

    @Test
    fun whatIsNotATagOffersNothing() {
        assertNull(FieldWire.parse(null))
        assertNull(FieldWire.parse("nm"))
        assertNull(FieldWire.parse("com.wasimaster.suite.field=x;token=abcdefgh12;actions=card_scan"))
        assertNull(FieldWire.parse("com.wasimaster.suite.field=1;token=abc;actions=card_scan"))
        assertNull(FieldWire.parse("com.wasimaster.suite.field=1;token=has space;actions=card_scan"))
        assertNull(FieldWire.parse("com.wasimaster.suite.field=1;actions=card_scan"))
    }
}
