package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Goldens for [LegacyFonts] (#1252), taken from the converters its tables
 * come from, run on words of the app's own Hindi, Tamil and Punjabi strings.
 */
class LegacyFontsTest {

    /** Npm-hindi-converter reads each of these back to the Hindi it came from. */
    @Test
    fun krutiDevMatchesTheReferenceConverter() {
        val words = listOf(
            "\u0905\u0902\u0924\u0930" to "varj",
            "\u0906\u0907\u0921\u0947\u0902\u091F\u093F\u092B\u093E\u0908" to "vkbMsafVQkbZ",
            "\u0906\u090F\u0964" to "vk,A",
            "\u0909\u092A" to "mi",
            "\u0915\u0902\u092A\u094B\u091C\u093C\u093F\u0902\u0917" to "daiksft+ax",
            "\u0915\u0930\u0942\u0901" to "d:\u00A1",
            "\u0915\u0941\u0902\u091C\u093F\u092F\u093E\u0902" to "dqaft;ka",
            "\u0915\u094D\u0932\u092C" to "Dyc",
            "\u091C\u0941\u0921\u093C\u093E" to "tqM+k",
            "\u091F\u0948\u0917" to "VSx",
            "\u0924\u0925\u094D\u092F" to "rF;",
            "\u0925\u0902\u092C" to "Fkac",
            "\u092A\u0924\u0932\u0947" to "irys",
            "\u092B\u094B\u0928\u093F\u0915\u0941\u0921" to "QksfudqM",
            "\u092C\u0922\u093C\u093E\u090F\u0902\u0964" to "c<+k,aA",
            "\u092E\u093E\u0924\u094D\u0930\u093E\u0915\u0943\u0924" to "ek=k\u00D1r",
            "\u092E\u0941\u0915\u093E\u092C\u0932\u0947" to "eqdkcys",
            "\u092E\u0947\u091C\u093C\u0930\u093F\u0902\u0917" to "est+fjax",
            "\u0930\u0939\u0947\u0917\u093E\u0964" to "jgsxkA",
            "\u0930\u094B" to "jks",
            "\u0932\u091F\u0915\u0947" to "yVds",
            "\u0938\u092E\u093E\u0928" to "leku",
            "\u0938\u0941\u091D\u093E" to "lq>k",
            "\u0938\u094D\u0915\u094D\u0930\u093F\u092C\u0932" to "fL\u00D8cy",
        )
        for ((unicode, legacy) in words) assertEquals(unicode, legacy, LegacyFonts.convert(LegacyFonts.Font.KRUTI_DEV, unicode))
    }

    /** What Android-TamilUtil writes for each. */
    @Test
    fun baminiMatchesTheReferenceConverter() {
        val words = listOf(
            "\u0B85\u0BAA\u0BCD\u0BAA\u0BBF\u0BB2\u0BCD" to "mg;gpy;",
            "\u0B85\u0BB3\u0BB5\u0BC8\u0BAA\u0BCD" to "msitg;",
            "\u0B87\u0B9F\u0BA4\u0BCD\u0BA4\u0BBF\u0BB1\u0BCD\u0B95\u0BC1\u0BAE\u0BCD" to ",lj;jpw;Fk;",
            "\u0B87\u0BA3\u0BC8" to ",iz",
            "\u0B8E\u0BB2\u0BCD\u0BB2\u0BC8\u0B95\u0BB3\u0BC1\u0B9F\u0BA9\u0BCD" to "vy;iyfSld;",
            "\u0B95\u0BA3\u0B95\u0BCD\u0B95\u0BBE\u0BA9" to "fzf;fhd",
            "\u0B95\u0BBF\u0BB0\u0BBF\u0BAA\u0BCD" to "fpupg;",
            "\u0B9A\u0BB0\u0BBF\u0BAA\u0BBE\u0BB0\u0BCD\u0BA4\u0BCD\u0BA4\u0BC1\u0BB5\u0BBF\u0B9F\u0BCD\u0B9F\u0BC1" to "rupghu;j;Jtpl;L",
            "\u0B9A\u0BC7\u0BB0\u0BC1\u0BAE\u0BCD" to "NrUk;",
            "\u0BAA\u0BBF\u0BA9\u0BCD\u0BA9\u0BA3\u0BBF" to "gpd;dzp",
            "\u0BAA\u0BC2\u0BB0\u0BCD\u0BA4\u0BCD\u0BA4\u0BBF\u0BAF\u0BBE\u0B95\u0BC1\u0BAE\u0BCD" to "G+u;j;jpahFk;",
            "\u0BAA\u0BC6\u0B9F\u0BCD\u0B9F\u0BBF\u0B95\u0BB3\u0BBF\u0BB2\u0BCD" to "ngl;bfspy;",
            "\u0BAE\u0BC0\u0BA4\u0BAE\u0BC1\u0BB3\u0BCD\u0BB3" to "kPjKs;s",
            "\u0BAE\u0BC1\u0B9F\u0BBF\u0BB5\u0BC8\u0BAA\u0BCD" to "Kbitg;",
            "\u0BB5\u0BA3\u0BCD\u0BA3\u0BA4\u0BCD\u0BA4\u0BC8\u0BAA\u0BCD" to "tz;zj;ijg;",
            "\u0BB5\u0BC8\u0BA4\u0BCD\u0BA4\u0BB2\u0BCD" to "itj;jy;",
        )
        for ((unicode, legacy) in words) assertEquals(unicode, legacy, LegacyFonts.convert(LegacyFonts.Font.BAMINI, unicode))
    }

    /** What anvaad-js writes for each, and reads back unchanged. */
    @Test
    fun anmolLipiMatchesTheReferenceConverter() {
        val words = listOf(
            "\u0A05\u0A30" to "Ar",
            "\u0A10\u0A02\u0A21\u0A30\u0A4C\u0A07\u0A21" to "AYNfrOief",
            "\u0A16\u0A47\u0A24\u0A30" to "Kyqr",
            "\u0A1C\u0A4B\u0A07\u0A38\u0A1F\u0A3F\u0A15" to "joiesitk",
            "\u0A24\u0A38\u0A35\u0A40\u0A30\u0A3E\u0A02" to "qsvIrW",
            "\u0A24\u0A40\u0A2C\u0A30\u0A24\u0A3E" to "qIbrqw",
            "\u0A26\u0A47" to "dy",
            "\u0A2C\u0A48\u0A15\u0A17\u0A30\u0A3E\u0A0A\u0A02\u0A21" to "bYkgrwaUNf",
            "\u0A32\u0A5C\u0A3F\u0A06" to "liVAw",
            "\u0A32\u0A71\u0A2D\u0A3F\u0A06" to "l`iBAw",
            "\u0A38\u0A3C\u0A41\u0A71\u0A27" to "Su`D",
            "\u0A38\u0A3E\u0A30\u0A23\u0A40" to "swrxI",
            "\u0A38\u0A70\u0A2D\u0A35" to "sMBv",
            "\u0A39\u0A3F\u0A32\u0A23\u0A3E" to "ihlxw",
            "\u0A39\u0A4B\u0A35\u0A47\u0A17\u0A40" to "hovygI",
            "\u0A39\u0A71\u0A25\u0A40\u0A02" to "h`QIN",
        )
        for ((unicode, legacy) in words) assertEquals(unicode, legacy, LegacyFonts.convert(LegacyFonts.Font.ANMOL_LIPI, unicode))
    }

    @Test
    fun krutiDevPlacesTheISignAndTheReph() {
        assertEquals("fgUnh", LegacyFonts.convert(LegacyFonts.Font.KRUTI_DEV, "\u0939\u093F\u0928\u094D\u0926\u0940"))
        assertEquals("/keZ", LegacyFonts.convert(LegacyFonts.Font.KRUTI_DEV, "\u0927\u0930\u094D\u092E"))
        assertEquals("dhfrZ", LegacyFonts.convert(LegacyFonts.Font.KRUTI_DEV, "\u0915\u0940\u0930\u094D\u0924\u093F"))
    }

    @Test
    fun krutiDevMovesTheMarksItDrawsAsLetters() {
        assertEquals("gSa\\", LegacyFonts.convert(LegacyFonts.Font.KRUTI_DEV, "\u0939\u0948\u0902?"))
        assertEquals("]", LegacyFonts.convert(LegacyFonts.Font.KRUTI_DEV, ","))
    }

    @Test
    fun textWithNothingToConvertIsLeftAlone() {
        assertEquals("hello", LegacyFonts.convert(LegacyFonts.Font.BAMINI, "hello"))
        assertFalse(LegacyFonts.needsConversion(LegacyFonts.Font.ANMOL_LIPI, "hello, world"))
    }
}
