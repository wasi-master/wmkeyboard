package com.wasimaster.wmkeyboard.core.phone

import com.wasimaster.wmkeyboard.core.clipboard.ClipEntities
import com.wasimaster.wmkeyboard.core.clipboard.ClipEntityKind
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.clipboard.PhoneFormats
import com.wasimaster.wmkeyboard.core.selection.SelectionKind
import com.wasimaster.wmkeyboard.core.selection.SelectionMacros
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phone numbers for every country: the bundled numbering plans
 * (`phone/phone_meta.tsv`, from libphonenumber), the region a national number
 * is read in, and the OS formatter behind its seam.
 *
 * The real [android.telephony.PhoneNumberUtils] is a stub on the JVM, so the
 * formatting tests install [FakeFormatter]; [SystemPhoneFormatter] is what
 * turns the stub's throw into "cannot format".
 */
class PhoneNumbersTest {

    private val asset = File("src/main/assets/${PhoneMetadata.ASSET}")
    private val table by lazy { PhoneMetadata.parse(asset.readText()) }

    private fun use(region: String?) = PhoneNumbers.install(table, region)

    @After
    fun reset() {
        PhoneNumbers.install(null, null)
        PhoneNumbers.formatter = SystemPhoneFormatter
    }

    // ---- the table ----

    @Test fun theTableCoversEveryCountryAndStaysSmall() {
        assertTrue("${table.size} regions", table.size >= 240)
        assertTrue("${asset.length()} bytes", asset.length() <= 100_000)
        assertEquals("880", table.plan("BD")?.countryCode)
        assertEquals("880", table.plan("bd")?.countryCode)
        assertNull(table.plan("ZZ"))
        // A shared dial code lists its main region first.
        assertEquals("US", table.plansFor("1").first().region)
        assertEquals("GB", table.plansFor("44").first().region)
    }

    @Test fun theTableRecordsWhereItCameFrom() {
        val header = asset.readLines().takeWhile { it.startsWith("#") }.joinToString("\n")
        assertTrue(header.contains("libphonenumber v"))
        assertTrue(header.contains("Apache License 2.0"))
    }

    // ---- parsing ----

    @Test fun aNationalNumberLosesItsTrunkZero() {
        use("BD")
        assertEquals(ParsedPhone("880", "1712345678", "BD", false), PhoneNumbers.parse("01712-345678"))
        assertEquals("+8801712345678", PhoneNumbers.parse("01712 345678")?.e164)
    }

    @Test fun aPlusNamesTheCountryWhateverThePhoneSays() {
        use("US")
        assertEquals(ParsedPhone("880", "1712345678", "BD", true), PhoneNumbers.parse("+880 1712 345678"))
        // The trunk zero written inside an international number.
        assertEquals("2079460958", PhoneNumbers.parse("+44 (0)20 7946 0958")?.national)
    }

    @Test fun theRegionsOwnInternationalPrefixNamesACountryToo() {
        use("US")
        assertEquals(ParsedPhone("44", "2079460958", "GB", true), PhoneNumbers.parse("011 44 20 7946 0958"))
        use("GB")
        assertEquals("BD", PhoneNumbers.parse("00880 1712 345678")?.region)
    }

    @Test fun theDialCodeWrittenWithoutAPlusIsRead() {
        use("BD")
        assertEquals(ParsedPhone("880", "1712345678", "BD", true), PhoneNumbers.parse("8801712345678"))
    }

    @Test fun argentinasMobilePrefixIsTransformed() {
        use("AR")
        // 0 11 15 2345-6789: the trunk 0 and the mobile 15 become a leading 9.
        assertEquals("91123456789", PhoneNumbers.parse("011 15-2345-6789")?.national)
        assertEquals(PhoneType.MOBILE, PhoneNumbers.type("011 15-2345-6789"))
    }

    @Test fun aSharedDialCodeFindsItsRegion() {
        use(null)
        assertEquals("AG", PhoneNumbers.parse("+1 268 464 1234")?.region)
        assertEquals("US", PhoneNumbers.parse("+1 201 555 0123")?.region)
        assertEquals("KZ", PhoneNumbers.parse("+7 701 234 5678")?.region)
        assertEquals("RU", PhoneNumbers.parse("+7 912 345 6789")?.region)
    }

    @Test fun digitsInOtherScriptsReadAsDigits() {
        use("BD")
        assertEquals("1712345678", PhoneNumbers.parse("০১৭১২-৩৪৫৬৭৮")?.national)
    }

    @Test fun textThatIsNotANumberDoesNotParse() {
        use("BD")
        assertNull(PhoneNumbers.parse("call me"))
        assertNull(PhoneNumbers.parse("12"))
        assertNull(PhoneNumbers.parse("0171x345678"))
    }

    // ---- region fallback ----

    @Test fun aNationalNumberNeedsARegion() {
        use(null)
        assertNull(PhoneNumbers.parse("01712-345678"))
        assertNull(PhoneNumbers.check("01712-345678"))
        // An international one does not.
        assertEquals(true, PhoneNumbers.check("+880 1712 345678"))
        assertEquals("BD", PhoneNumbers.parse("00880 1712 345678")?.region)
    }

    @Test fun theNetworkThenTheSimThenTheLocaleSayWhereThePhoneIs() {
        assertEquals("BD", PhoneRegions.pick("bd", "us", listOf("GB")))
        assertEquals("US", PhoneRegions.pick("", "us", listOf("GB")))
        assertEquals("GB", PhoneRegions.pick(null, null, listOf("", "GB")))
        assertNull(PhoneRegions.pick(null, "", emptyList()))
        // Not a two-letter region: the world, a typo.
        assertEquals("IN", PhoneRegions.pick("001", "i", listOf("IN")))
    }

    @Test fun anExplicitRegionArgumentBeatsTheDefault() {
        use("US")
        assertTrue(PhoneNumbers.isValid("01712-345678", "BD"))
        assertFalse(PhoneNumbers.isValid("01712-345678"))
    }

    @Test fun withNoTableNothingIsKnown() {
        PhoneNumbers.install(null, null)
        assertNull(PhoneNumbers.parse("+880 1712 345678"))
        assertNull(PhoneNumbers.check("+880 1712 345678"))
        assertEquals(PhoneType.UNKNOWN, PhoneNumbers.type("+880 1712 345678"))
        assertNull(PhoneNumbers.e164("01712-345678", "BD"))
    }

    // ---- validity and type ----

    @Test fun realNumbersAreValidAndTyped() {
        use("GB")
        assertEquals(PhoneType.MOBILE, PhoneNumbers.type("07400 123456"))
        assertEquals(PhoneType.FIXED_LINE, PhoneNumbers.type("020 7946 0958"))
        assertEquals(PhoneType.OTHER, PhoneNumbers.type("0800 123 4567"))
        use("US")
        assertEquals(PhoneType.FIXED_LINE_OR_MOBILE, PhoneNumbers.type("(201) 555-0123"))
        use("DE")
        // Germany has no one length; the plan knows each range's.
        assertEquals(PhoneType.FIXED_LINE, PhoneNumbers.type("030 123456"))
        assertEquals(PhoneType.MOBILE, PhoneNumbers.type("0151 23456789"))
        use("IN")
        assertEquals(PhoneType.MOBILE, PhoneNumbers.type("98765 43210"))
    }

    @Test fun digitRunsThatNoPlanHasAreInvalid() {
        use("US")
        assertFalse(PhoneNumbers.isValid("1234567890"))
        assertFalse(PhoneNumbers.isValid("555-12-3456"))
        use("BD")
        // The invoice totals and order numbers the shape rules let through.
        assertFalse(PhoneNumbers.isValid("4002345678"))
        assertFalse(PhoneNumbers.isValid("100234567890"))
        assertFalse(PhoneNumbers.isValid("+880 1712 34567"))
    }

    // ---- formatting ----

    private class FakeFormatter : PhoneFormatter {
        override fun format(number: String, region: String): String = "[$region ${number.filter(Char::isDigit)}]"
        override fun toE164(number: String, region: String): String? = null
    }

    @Test fun theOsFormatsInThePhonesRegion() {
        use("BD")
        PhoneNumbers.formatter = FakeFormatter()
        assertEquals("[BD 01712345678]", PhoneNumbers.format("01712345678"))
        assertEquals("[BD 01712345678]", PhoneFormats.display("01712345678", emptyList()))
    }

    @Test fun theUsersMaskWinsWhereItMatches() {
        use("BD")
        PhoneNumbers.formatter = FakeFormatter()
        val masks = PhoneFormats.parseAll(listOf("+880 1XXX-XXXXXX"))
        assertEquals("+880 1712-345678", PhoneFormats.display("01712345678", masks))
        // A number the mask does not match falls to the OS.
        assertEquals("[BD 4155552671]", PhoneFormats.display("4155552671", masks))
        assertEquals("+880 1712-345678", SelectionMacros.formatPhone("01712345678", listOf("+880 1XXX-XXXXXX")))
        assertEquals("[BD 01712345678]", SelectionMacros.formatPhone("01712345678", emptyList()))
    }

    @Test fun aStubOsFormatterReadsAsCannotFormat() {
        use("BD")
        // The JVM's android.jar throws on every call; the seam swallows it.
        assertNull(PhoneNumbers.format("01712345678"))
        assertEquals("01712-345678", PhoneFormats.display(" 01712-345678 ", emptyList()))
    }

    @Test fun dialDigitsGainTheDialCodeFromThePlans() {
        use("BD")
        assertEquals("8801712345678", SelectionMacros.dialDigits("01712-345678", emptyList()))
        assertEquals("+8801712345678", PhoneNumbers.e164("01712-345678"))
    }

    // ---- the detector and the selection bar use them ----

    private fun phones(text: String) =
        ClipEntities.extract(text).filter { it.kind == ClipEntityKind.PHONE }.map { it.value }

    @Test fun theDetectorDropsRunsThatAreNoRealNumber() {
        use("US")
        assertEquals(listOf("201) 555-0123"), phones("Invoice 1234567890 due. Call (201) 555-0123."))
        use("BD")
        assertEquals(listOf("01712-345678"), phones("Order 4002345678 ships today. Call 01712-345678 about it."))
    }

    @Test fun theDetectorKeepsAForeignNumberThatNamesItsCountry() {
        use("BD")
        assertEquals(listOf("+44 20 7946 0958"), phones("London office: +44 20 7946 0958."))
    }

    @Test fun theUsersMasksStillDecideWhenTheyAreSet() {
        use("BD")
        val masks = PhoneFormats.parseAll(listOf("XXXXXXXXXX"))
        // The plans would turn this down; the mask the user wrote says keep it.
        val found = ClipEntities.extract("ref 4002345678 ok", phoneFormats = masks)
            .filter { it.kind == ClipEntityKind.PHONE }
        assertEquals(listOf("4002345678"), found.map { it.value })
    }

    @Test fun mobilesComeBeforeLandlinesAndOneNumberIsOneChip() {
        use("GB")
        val items = listOf(
            ClipItem(id = 3, text = "Desk: 020 7946 0958, ask for Sam", timestamp = 3),
            ClipItem(id = 2, text = "Mobile 07400 123456 after six", timestamp = 2),
            ClipItem(id = 1, text = "Same one: +44 7400 123456 thanks", timestamp = 1),
        )
        val chips = ClipEntities.entitiesIn(items).filter { it.kind == ClipEntityKind.PHONE }
        assertEquals(listOf("07400 123456", "020 7946 0958"), chips.map { it.value })
    }

    @Test fun aChipShowsTheFormattedNumberAndPastesTheClipsOwn() {
        use("BD")
        PhoneNumbers.formatter = FakeFormatter()
        val chip = ClipEntities.extract("call 01712-345678 now").single { it.kind == ClipEntityKind.PHONE }
        assertEquals("01712-345678", chip.value)
        assertEquals("[BD 01712345678]", chip.display)
    }

    @Test fun theSelectionBarOffersOnlyRealNumbers() {
        use("US")
        assertEquals(SelectionKind.TEXT, SelectionMacros.detect("1234567890"))
        assertEquals(SelectionKind.PHONE, SelectionMacros.detect("(201) 555-0123"))
    }
}
