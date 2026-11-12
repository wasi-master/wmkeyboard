package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Goldens for [LegacyFonts] (#1252), taken from the converters its tables
 * come from: run on words of the app's own Hindi, Tamil and Punjabi strings,
 * Nepali Wikipedia, and the Sinhala and Malayalam word lists of
 * FrequencyWords and Kalkandam.
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

    /** What legacy-fonts-to-unicode (aril's table) writes for each; open-tamil reads them back. */
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

    /** The letters Android-TamilUtil's table wrote wrong: aril and open-tamil agree on these. */
    @Test
    fun baminiWritesAythamAndTheUuGlyphs() {
        assertEquals("/", LegacyFonts.convert(LegacyFonts.Font.BAMINI, "\u0B83"))
        assertEquals("#", LegacyFonts.convert(LegacyFonts.Font.BAMINI, "\u0B9A\u0BC2"))
        assertEquals("Jh", LegacyFonts.convert(LegacyFonts.Font.BAMINI, "\u0BA4\u0BC2"))
        assertEquals("G+", LegacyFonts.convert(LegacyFonts.Font.BAMINI, "\u0BAA\u0BC2"))
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

    /** What legacy-fonts-to-unicode writes for each; preeti2unicode reads them back. */
    @Test
    fun preetiMatchesTheReferenceConverter() {
        val words = listOf(
            "\u0928\u093E\u092E\u0932\u0947" to "gfdn]",
            "\u0915\u0941\u0930\u0940\u0924\u093F\u0939\u0930\u0942\u092E\u093E" to "s'/Lltx\u00BFdf",
            "\u092A\u094D\u0930\u0923\u093E\u0932\u0940\u092C\u093E\u091F" to "k|0ffnLaf6",
            "\u0907\u0932\u0947\u0915\u094D\u091F\u094D\u0930\u094B\u0928\u093F\u0915" to "On]S6\u00ABf]lgs",
            "\u0905\u0928\u0941\u092A\u093E\u0924" to "cg'kft",
            "\u0936\u094B\u092D\u093E\u092E\u093E\u0928" to "zf]efdfg",
            "\u0909\u092A\u0924\u094D\u092F\u0915\u093E\u0924\u093F\u0930" to "pkTosflt/",
            "\u0935\u093E\u0930\u0941\u0923" to "jf?0f",
            "\u092A\u094D\u0930\u0935\u093E\u0938\u0940" to "k|jf;L",
            "\u0938\u092E\u0941\u0928\u094D\u0926\u094D\u0930" to ";d'G\u203A",
            "\u0932\u0917\u093E\u0908" to "nufO{",
            "\u0936\u093E\u0939\u0915\u0940" to "zfxsL",
            "\u092C\u093E\u0930\u094D\u0937\u093F\u0915" to "aflif{s",
            "\u0930\u0942\u092A\u093E" to "\u00BFkf",
        )
        for ((unicode, legacy) in words) assertEquals(unicode, legacy, LegacyFonts.convert(LegacyFonts.Font.PREETI, unicode))
    }

    /** What legacy-fonts-to-unicode writes for each; Pandukabhaya reads them back. */
    @Test
    fun fmAbhayaMatchesTheReferenceConverter() {
        val words = listOf(
            "\u0D88\u0DAD\u0D9A" to "wE;l",
            "\u0D91\u0DAD\u0DA7" to "t;g",
            "\u0DC0\u0DC4\u0DC5\u0DBA" to "jy<h",
            "\u0D9A\u0DC5\u0DAF" to "l<o",
            "\u0DBB\u0DA7\u0DA7\u0DAF" to "rggo",
            "\u0D8B\u0D9C\u0DC3\u0DA7" to "W.ig",
            "\u0D91\u0DC0\u0DBD\u0DAF" to "tj,o",
            "\u0DC5\u0D9F\u0D9A\u0DAF" to "<`.lo",
            "\u0DC4\u0DAC\u0D9A\u0DA7" to "y~lg",
            "\u0D8B\u0DBB\u0DB1\u0DBA" to "Wrkh",
            "\u0DAD\u0DBB\u0D9C\u0DBA\u0DAF" to ";r.ho",
            "\u0DC3\u0DC0\u0DC3\u0D9A\u0DB8" to "ijilu",
            "\u0D91\u0DAD\u0D9A\u0DA7" to "t;lg",
            "\u0DB4\u0DBD\u0DBD\u0D9A\u0DA7" to "m,,lg",
        )
        for ((unicode, legacy) in words) assertEquals(unicode, legacy, LegacyFonts.convert(LegacyFonts.Font.FM_ABHAYA, unicode))
    }

    /** What Kalkandam writes for each, and reads back unchanged. */
    @Test
    fun mlTtMatchesTheReferenceConverter() {
        val words = listOf(
            "\u0D2A\u0D41\u0D30\u0D41\u0D37\u0D7B" to "]pcpj\u00B3",
            "\u0D28\u0D1F\u0D28\u0D41\u0D02" to "\\S\\pw",
            "\u0D28\u0D1F\u0D2A\u0D4D\u0D2A\u0D3E\u0D15\u0D4D\u0D15\u0D3F\u0D2F" to "\\S\u00B8m\u00A1nb",
            "\u0D15\u0D41\u0D38\u0D41\u0D2E\u0D2E\u0D1E\u0D4D\u0D1C\u0D30\u0D3F" to "IpkpaaRvPcn",
            "\u0D37\u0D3E\u0D7B" to "jm\u00B3",
            "\u0D2A\u0D1F\u0D3F" to "]Sn",
            "\u0D1F\u0D46\u0D31\u0D3F\u0D1F\u0D4D\u0D1F\u0D31\u0D3F" to "sSdn\u00ABdn",
            "\u0D26\u0D4B\u0D39" to "tZml",
            "\u0D35\u0D28\u0D19\u0D4D\u0D19\u0D33\u0D3F\u0D7D" to "h\\\u00A7fn\u00C2",
            "\u0D2E\u0D30\u0D23\u0D38\u0D2E\u0D2F\u0D24\u0D4D\u0D24\u0D4D" to "acWkab\u00AFv",
            "\u0D06\u0D33\u0D3E\u0D2F\u0D3F\u0D30\u0D41\u0D28\u0D4D\u0D28\u0D41" to "Bfmbncp\u00B6p",
            "\u0D38\u0D39\u0D3F\u0D15\u0D4D\u0D15\u0D3E\u0D7B" to "kln\u00A1m\u00B3",
            "\u0D1F\u0D47\u0D2A\u0D4D\u0D2A\u0D4D" to "tS\u00B8v",
            "\u0D35\u0D47\u0D23\u0D4D\u0D1F\u0D41\u0D28\u0D4D\u0D28" to "th\u00ADp\u00B6",
        )
        for ((unicode, legacy) in words) assertEquals(unicode, legacy, LegacyFonts.convert(LegacyFonts.Font.ML_TT, unicode))
    }

    @Test
    fun preetiWritesTheReadmeExample() {
        assertEquals("g]kfn", LegacyFonts.convert(LegacyFonts.Font.PREETI, "\u0928\u0947\u092A\u093E\u0932"))
    }

    /** Kalkandam's own test: the output kuttipencil.in gives for the sentence. */
    @Test
    fun mlTtMatchesKuttipencil() {
        assertEquals(
            "C\u00B6s\u00AF G\u00E4hpw ]pXnb hm\u00C0\u00AFI\u00C4",
            LegacyFonts.convert(
                LegacyFonts.Font.ML_TT,
                "\u0D07\u0D28\u0D4D\u0D28\u0D24\u0D4D\u0D24\u0D46 \u0D0F\u0D31\u0D4D\u0D31\u0D35\u0D41\u0D02 " +
                    "\u0D2A\u0D41\u0D24\u0D3F\u0D2F \u0D35\u0D3E\u0D7C\u0D24\u0D4D\u0D24\u0D15\u0D7E",
            ),
        )
    }

    /** ML and FML differ only in ണ്ട. */
    @Test
    fun mlTtAndFmlDifferOnlyInNda() {
        val unda = "\u0D09\u0D23\u0D4D\u0D1F\u0D4D"
        assertEquals("D\u00ADv", LegacyFonts.convert(LegacyFonts.Font.ML_TT, unda))
        assertEquals("D\u00EFv", LegacyFonts.convert(LegacyFonts.Font.FML, unda))
    }

    @Test
    fun everyLanguageOffersItsFonts() {
        assertEquals(listOf(LegacyFonts.Font.PREETI, LegacyFonts.Font.KRUTI_DEV), LegacyFonts.fontsFor("ne"))
        assertEquals(listOf(LegacyFonts.Font.ML_TT, LegacyFonts.Font.FML), LegacyFonts.fontsFor("ml"))
        assertEquals(listOf(LegacyFonts.Font.FM_ABHAYA), LegacyFonts.fontsFor("si"))
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
