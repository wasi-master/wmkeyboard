package com.wasimaster.wmkeyboard.core.transliteration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Goldens for [LegacyFonts] (#1252), taken from the converters its tables
 * come from: run on words of the app's own Hindi, Tamil and Punjabi strings,
 * Nepali Wikipedia, and the Sinhala and Malayalam word lists of
 * FrequencyWords and Kalkandam, and the app's Kannada strings.
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

    /** What sanchaya's table gives in the order real Nudi text uses; aravindavk's knconverter reads them back. */
    @Test
    fun nudiMatchesTheReferenceConverter() {
        val words = listOf(
            "\u0C8E\u0C95\u0CCD\u0CB8\u0CBF\u0C9F\u0CBE\u0CB0\u0CCD\u0C9A\u0CCD" to "JQ\u00ECm\u00C1g\u00EFZ\u00EF",
            "\u0CB5\u0CC8\u0CAC\u0CCD\u0CB0\u0CC6\u0C82\u0C9F\u0CCD" to "\u00AA\u00C9\u00CA\u00A8\u00C9\u00E6Am\u00EF",
            "\u0CAA\u0CCD\u0CAF\u0CBE\u0CB0\u0CBF\u0CB8\u0CCD" to "\u00A5\u00C1\u00E5j\u00B8\u00EF",
            "\u0C9C\u0CCB\u0CB0\u0CBE\u0C97\u0CBF\u0CB0\u0CC1\u0CB5" to "e\u00C9\u00C6\u00C3g\u00C1Vg\u00C0\u00C4\u00AA\u00C0",
            "\u0CAA\u0CCD\u0CB0\u0CCA\u0CB5\u0CC8\u0CA1\u0CB0\u0CCD\u0CB8\u0CCD" to "\u00A5\u00C9\u00C6\u00E6\u00AA\u00C9\u00CAq\u00C0g\u00EF\u00B8\u00EF",
            "\u0CB8\u0CCD\u0CB5\u0CC0\u0C95\u0CB0\u0CBF\u0CB8\u0CBF" to "\u00B9\u00E9\u00C3P\u00C0j\u00B9",
            "\u0C85\u0C95\u0CCD\u0CB7\u0CB0\u0CB6\u0C83" to "CP\u00C0\u00EBg\u00C0\u00B1\u00C0B",
            "\u0C95\u0CCD\u0CB7\u0CC7\u0CA4\u0CCD\u0CB0\u0CA6\u0CB2\u0CCD\u0CB2\u0CBF" to "P\u00C9\u00EB\u00C3v\u00C0\u00E6z\u00C0\u00B0\u00E8",
            "\u0CB8\u0CBE\u0C97\u0CC1\u0CA4\u0CCD\u0CA4\u0CBF\u0CA6\u0CC6" to "\u00B8\u00C1U\u00C0\u00C4w\u00DBz\u00C9",
            "\u0CAA\u0C82\u0C9A\u0CAD\u0CC1\u0C9C\u0CBE\u0C95\u0CC3\u0CA4\u0CBF" to "\u00A5\u00C0AZ\u00C0\u00A8s\u00C0\u00C4e\u00C1P\u00C0\u00C8w",
            "\u0C86\u0CCD\u0CAF\u0CAA\u0CCD\u200C\u0CA8\u0CBF\u0C82\u0CA6" to "D\u00E5\u00A5\u00EF\u00A4Az\u00C0",
            "\u0C97\u0CBE\u0CA4\u0CCD\u0CB0\u0C97\u0CB3\u0CC1" to "U\u00C1v\u00C0\u00E6U\u00C0\u00BC\u00C0\u00C4",
            "\u0C87\u0CB2\u0CCD\u0CB2\u0CBF\u0CA6\u0CCD\u0CA6\u0CB0\u0CC6" to "E\u00B0\u00E8z\u00C0\u00DDg\u00C9",
        )
        for ((unicode, legacy) in words) assertEquals(unicode, legacy, LegacyFonts.convert(LegacyFonts.Font.NUDI, unicode))
    }

    /** Words from real Nudi and Baraha documents (aravindavk/ascii2unicode's samples). */
    @Test
    fun nudiWritesWhatRealDocumentsHold() {
        assertEquals("\u00AA\u00C0\u00C4v\u00C0\u00C4\u00DB", LegacyFonts.convert(LegacyFonts.Font.NUDI, "\u0CAE\u0CA4\u0CCD\u0CA4\u0CC1"))
        assertEquals("\u00A5\u00C0\u00E6w", LegacyFonts.convert(LegacyFonts.Font.NUDI, "\u0CAA\u0CCD\u0CB0\u0CA4\u0CBF"))
        assertEquals(
            "\u00BA\u00C9\u00C6\u00C3U\u00C0\u00C4v\u00C9\u00DB\u00C3\u00A3\u00C9",
            LegacyFonts.convert(LegacyFonts.Font.NUDI, "\u0CB9\u0CCB\u0C97\u0CC1\u0CA4\u0CCD\u0CA4\u0CC7\u0CA8\u0CC6"),
        )
        assertEquals("123", LegacyFonts.convert(LegacyFonts.Font.NUDI, "\u0CE7\u0CE8\u0CE9"))
    }

    /** What IIT Delhi's table gives; its own converter reads each back unchanged. */
    @Test
    fun chanakyaMatchesTheReferenceConverter() {
        val words = listOf(
            "\u0930\u093F\u0938\u0930\u094D\u091A" to "fjlpZ",
            "\u092A\u094D\u0930\u094B\u091F\u093E\u0928" to "\u00E7\u00A8Vku",
            "\u0932\u0930\u094D\u0928\u093F\u0902\u0917" to "yfuaZx",
            "\u092B\u093C\u094B\u0930\u092E" to "iQ+\u00A8je",
            "\u092C\u0922\u093C\u093E\u090F\u0901\u0964" to "c<+k,\u00A1A",
            "\u0939\u0949\u091F\u0935\u0930\u094D\u0921\u094D\u0938" to "g\u201AVoM~lZ",
            "\u0932\u0915\u094D\u0937\u094D\u092F" to "y{;",
            "\u0918\u094B\u0937\u0923\u093E\u092A\u0924\u094D\u0930" to "\u00C4\u00A8\"k.kki=k",
            "\u0915\u0947\u0902\u0926\u094D\u0930\u093F\u0924" to "osQan~fjr",
            "\u0938\u0930\u0932\u0940\u0915\u0943\u0924" to "ljyh\u2014r",
            "\u092B\u0947\u0938\u094B\u0902" to "iQsl\u00A8a",
            "\u091F\u094D\u0930\u0948\u0936" to "V\u00AAS'k",
        )
        for ((unicode, legacy) in words) assertEquals(unicode, legacy, LegacyFonts.convert(LegacyFonts.Font.CHANAKYA, unicode))
    }

    @Test
    fun chanakyaWritesNasalisedESigns() {
        // The converter's table also holds its own clean-up rules (ंे to ें); taken the other way they left ें unconverted.
        assertEquals("jgsaxs", LegacyFonts.convert(LegacyFonts.Font.CHANAKYA, "\u0930\u0939\u0947\u0902\u0917\u0947"))
        assertEquals("fgUnh", LegacyFonts.convert(LegacyFonts.Font.CHANAKYA, "\u0939\u093F\u0928\u094D\u0926\u0940"))
    }

    /** Words as Stiehl's manual for Itranslator 99 prints them in Sanskrit 99 (its ITX spelling after each). */
    @Test
    fun sanskrit99WritesWhatTheManualShows() {
        assertEquals("s<Sk\u00ABtm!", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u0938\u0902\u0938\u094D\u0915\u0943\u0924\u092E\u094D")) // saMskR^itam
        assertEquals("AwR>", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u0905\u0930\u094D\u0925\u0903")) // arthaH
        assertEquals("k\u2026\u00E9-]e\u00C7m!", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u0915\u0941\u0930\u0941-\u0915\u094D\u0937\u0947\u0924\u094D\u0930\u092E\u094D")) // kuru-kShetram
        assertEquals("pZyit", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u092A\u0936\u094D\u092F\u0924\u093F")) // pashyati
        assertEquals(";q\u0153", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u0937\u091F\u094D")) // SaT
        assertEquals("DaT\u00C7>", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u091B\u093E\u0924\u094D\u0924\u094D\u0930\u0903")) // ChAttraH
        assertEquals("A\u00BC\u201AlI", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u0905\u0919\u094D\u0917\u0941\u0932\u0940")) // aN^gulI
        assertEquals("ij\u00FEa", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u091C\u093F\u0939\u094D\u0935\u093E")) // jihvA
        assertEquals("\\iTvk\u0153", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u090B\u0924\u094D\u0935\u093F\u0915\u094D")) // R^itvik
        assertEquals("t\u2022ha", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u0924\u0931\u094D\u0939\u093E")) // taRhA
        assertEquals("is\u02C6)R", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u0938\u093F\u0930\u094D\u092B\u093C")) // sirfa
    }

    @Test
    fun sanskrit99KeepsTheRaOfAClusterOutOfTheReph() {
        // त्र्य is t, ra and ya: the ra under त, not a reph over य.
        assertEquals("\u00C8ya", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u0924\u094D\u0930\u094D\u092F\u093E"))
        assertEquals("kIitR>", LegacyFonts.convert(LegacyFonts.Font.SANSKRIT_99, "\u0915\u0940\u0930\u094D\u0924\u093F\u0903"))
    }

    /** Each glyph-table font's own name for its language, as Padma reads it back. */
    @Test
    fun glyphTableFontsWriteTheirLanguagesName() {
        assertEquals("\u00A8\u00C9\u00AE\u00C9`\u00D2", LegacyFonts.convert(LegacyFonts.Font.of("dvtt")!!, "\u092E\u0930\u093E\u0920\u0940")) // dvtt
        assertEquals("\u00A8\u00C9\u00AE\u00C9`\u00D2", LegacyFonts.convert(LegacyFonts.Font.of("dvwtt")!!, "\u092E\u0930\u093E\u0920\u0940")) // dvwtt
        assertEquals("_amRr", LegacyFonts.convert(LegacyFonts.Font.of("shreedev714")!!, "\u092E\u0930\u093E\u0920\u0940")) // shreedev714
        assertEquals("_amRr", LegacyFonts.convert(LegacyFonts.Font.of("shreepudhari")!!, "\u092E\u0930\u093E\u0920\u0940")) // shreepudhari
        assertEquals("_amRr", LegacyFonts.convert(LegacyFonts.Font.of("shreepud77")!!, "\u092E\u0930\u093E\u0920\u0940")) // shreepud77
        assertEquals("\u00D7\u00DA\u00E6\u00C6\u00E8", LegacyFonts.convert(LegacyFonts.Font.of("htchanakya")!!, "\u092E\u0930\u093E\u0920\u0940")) // htchanakya
        assertEquals("marazI", LegacyFonts.convert(LegacyFonts.Font.of("shusha")!!, "\u092E\u0930\u093E\u0920\u0940")) // shusha
        assertEquals("marazI", LegacyFonts.convert(LegacyFonts.Font.of("shivaji")!!, "\u092E\u0930\u093E\u0920\u0940")) // shivaji
        assertEquals("gaujaratI", LegacyFonts.convert(LegacyFonts.Font.of("vakil")!!, "\u0A97\u0AC1\u0A9C\u0AB0\u0ABE\u0AA4\u0AC0")) // vakil
        assertEquals("\u2122\u00F0\u00CB#V#", LegacyFonts.convert(LegacyFonts.Font.of("shreetel900")!!, "\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41")) // shreetel900
        assertEquals("\u0153\u00C9\u00A8\u00C5\u008F\u00C5", LegacyFonts.convert(LegacyFonts.Font.of("telugulipi")!!, "\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41")) // telugulipi
        assertEquals("\u00C5\u00E3\u2122\u00D5\u2019\u00D5", LegacyFonts.convert(LegacyFonts.Font.of("eenadu")!!, "\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41")) // eenadu
        assertEquals("\u00E9\u009E\u00AA\u00D7\u2019\u00D7", LegacyFonts.convert(LegacyFonts.Font.of("vaartha")!!, "\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41")) // vaartha
        assertEquals("\u0099\u00D4\\Q>Q", LegacyFonts.convert(LegacyFonts.Font.of("vikaas")!!, "\u0C24\u0C46\u0C32\u0C41\u0C17\u0C41")) // vikaas
        assertEquals("u\u00AA\u00CC", LegacyFonts.convert(LegacyFonts.Font.of("shreetam802")!!, "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD")) // shreetam802
        assertEquals("R\u2013\u00B2", LegacyFonts.convert(LegacyFonts.Font.of("elango")!!, "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD")) // elango
        assertEquals("jkpH:", LegacyFonts.convert(LegacyFonts.Font.of("amudham")!!, "\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD")) // amudham
        assertEquals("\u00CE\u00DC\u00CF\u00DE{\u00A2", LegacyFonts.convert(LegacyFonts.Font.of("manorama")!!, "\u0D2E\u0D32\u0D2F\u0D3E\u0D33\u0D02")) // manorama
        assertEquals("hki\u00EAq\u00F9", LegacyFonts.convert(LegacyFonts.Font.of("matweb")!!, "\u0D2E\u0D32\u0D2F\u0D3E\u0D33\u0D02")) // matweb
        assertEquals("\u00C4\u00F2\u00F0\u00A1\u00F8\u00AC", LegacyFonts.convert(LegacyFonts.Font.of("kairali")!!, "\u0D2E\u0D32\u0D2F\u0D3E\u0D33\u0D02")) // kairali
        assertEquals("hki\u00A1q\u00AB", LegacyFonts.convert(LegacyFonts.Font.of("thoolika")!!, "\u0D2E\u0D32\u0D2F\u0D3E\u0D33\u0D02")) // thoolika
        assertEquals("\u00C4\u00F2\u00F0\u00A1\u00F8\u00AC", LegacyFonts.convert(LegacyFonts.Font.of("panchami")!!, "\u0D2E\u0D32\u0D2F\u0D3E\u0D33\u0D02")) // panchami
        assertEquals("\u00D2\u00D5\u00D3\u00E3\u00DB\u00ED", LegacyFonts.convert(LegacyFonts.Font.of("kalakaumudi")!!, "\u0D2E\u0D32\u0D2F\u0D3E\u0D33\u0D02")) // kalakaumudi
        assertEquals("pSjfbI", LegacyFonts.convert(LegacyFonts.Font.of("drchatrik")!!, "\u0A2A\u0A70\u0A1C\u0A3E\u0A2C\u0A40")) // drchatrik
    }

    /** Akruti Ori Sarala: the reph goes in by its own glyph, a dead ra at a word's end as ra and virama. */
    @Test
    fun akrutiOriWritesTheRephAndADeadRaApart() {
        assertEquals("IW\u00FF\u00F2@\u00FB", LegacyFonts.convert(LegacyFonts.Font.AKRUTI_ORI, "\u0B13\u0B21\u0B3C\u0B3F\u0B06"))
        assertEquals("]c\u00F0", LegacyFonts.convert(LegacyFonts.Font.AKRUTI_ORI, "\u0B27\u0B30\u0B4D\u0B2E"))
        assertEquals("i\u00FBe\u00FE", LegacyFonts.convert(LegacyFonts.Font.AKRUTI_ORI, "\u0B38\u0B3E\u0B30\u0B4D"))
    }

    @Test
    fun everyGlyphTableFontHasItsTable() {
        for (font in LegacyFonts.Font.entries.filter { it.block != 0 }) {
            assertEquals(font.id, true, GlyphFontTables.encoding(font.id) != null)
        }
    }

    @Test
    fun glyphTableFontsLeaveOtherScriptsAlone() {
        assertEquals("abc \u0939\u093F", LegacyFonts.convert(LegacyFonts.Font.VAKIL, "abc \u0939\u093F"))
        assertEquals(false, LegacyFonts.isScriptChar(LegacyFonts.Font.MANORAMA, '\u0928'))
    }

    /** What open-tamil's tscii2utf8 table gives; the Grantha u signs are TSCII 0xA4 and 0xA5, as in its ஜு. */
    @Test
    fun tsciiMatchesTheReferenceConverter() {
        val words = listOf(
            "\u0BAA\u0BCD\u0BB0\u0BCA\u0B9C\u0BC6\u0B95\u0BCD\u0B9F\u0BB0\u0BCD" to "\u00F4\u00A6\u00C3\u00A1\u00A6\u0192\u00EC\u00BC\u00F7",
            "\u0B95\u0BCB\u0B9F\u0BBE\u0B95\u0BC1\u0BAE\u0BCD" to "\u00A7\u00B8\u00A1\u00BC\u00A1\u00CC\u00F5",
            "\u0BAE\u0BCC\u0BA9\u0BAE\u0BBE\u0B95" to "\u00A6\u00C1\u00AA\u00C9\u00C1\u00A1\u00B8",
            "\u0B9A\u0BC2\u0BB4\u0BB2\u0BCD" to "\u00DD\u00C6\u00F8",
            "\u0BAA\u0B9F\u0BBF\u0BB5\u0BA4\u0BCD\u0BA4\u0BC8\u0BAF\u0BC1\u0BAE\u0BCD" to "\u00C0\u00CA\u00C5\u00F2\u00A8\u00BE\u00D4\u00F5",
            "\u0BAE\u0BC0\u0B9F\u0BCD\u0B9F\u0BB0\u0BBF\u0BB2\u0BCD" to "\u00C1\u00A3\u00F0\u00BC\u00C3\u00A2\u00F8",
            "\u0B9F\u0BBF\u0B9F\u0BC6\u0B95\u0BCD\u0BB7\u0BA9\u0BCD" to "\u00CA\u00A6\u00BC\u2021\u00FD",
            "\u0B9A\u0BC8\u0BB0\u0BA9\u0BCD\u0B95\u0BB3\u0BCD" to "\u00A8\u00BA\u00C3\u00FD\u00B8\u00FB",
            "\u0BAA\u0BC7\u0B99\u0BCD\u0B95\u0BCD\u0BB2\u0BBF\u0BB7\u0BCD" to "\u00A7\u00C0\u00ED\u00EC\u00C4\u00A2\u2030",
            "\u0B85\u0BB4\u0BC1\u0BA4\u0BCD\u0BA4\u0BBF" to "\u00AB\u00D8\u00F2\u00BE\u00A2",
            "\u0B95\u0BA3\u0BBF\u0BA9\u0BBF\u0B95\u0BB3\u0BC8\u0BA4\u0BCD" to "\u00B8\u00BD\u00A2\u00C9\u00A2\u00B8\u00A8\u00C7\u00F2",
            "\u0BB7\u0BBF\u0BB8\u0BC1\u0B95\u0BC1\u0BB5\u0BC8\u0BAA\u0BCD" to "\u201E\u00A2\u2026\u00A4\u00CC\u00A8\u00C5\u00F4",
            "\u0B93\u0BB5\u0BB0\u0BCD\u0BB7\u0BC2\u0B9F\u0BCD" to "\u00B5\u00C5\u00F7\u201E\u00A5\u00F0",
            "\u0B9C\u0BC1\u0BAF\u0BBF\u0BA9\u0BCD" to "\u0192\u00A4\u00C2\u00A2\u00FD",
        )
        for ((unicode, legacy) in words) assertEquals(unicode, legacy, LegacyFonts.convert(LegacyFonts.Font.TSCII, unicode))
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
        assertEquals(listOf(LegacyFonts.Font.PREETI, LegacyFonts.Font.KRUTI_DEV, LegacyFonts.Font.CHANAKYA, LegacyFonts.Font.SANSKRIT_99), LegacyFonts.fontsFor("ne").take(4))
        assertEquals(listOf(LegacyFonts.Font.SANSKRIT_99, LegacyFonts.Font.KRUTI_DEV, LegacyFonts.Font.CHANAKYA), LegacyFonts.fontsFor("sa").take(3))
        assertEquals(listOf(LegacyFonts.Font.KRUTI_DEV, LegacyFonts.Font.CHANAKYA, LegacyFonts.Font.SANSKRIT_99, LegacyFonts.Font.DV_TT), LegacyFonts.fontsFor("hi").take(4))
        assertEquals(listOf(LegacyFonts.Font.KRUTI_DEV, LegacyFonts.Font.DV_TT, LegacyFonts.Font.DVW_TT), LegacyFonts.fontsFor("mr").take(3))
        assertEquals(listOf(LegacyFonts.Font.VAKIL), LegacyFonts.fontsFor("gu"))
        assertEquals(LegacyFonts.Font.SHREE_TEL_900, LegacyFonts.fontsFor("te").first())
        assertEquals(listOf(LegacyFonts.Font.BAMINI, LegacyFonts.Font.TSCII), LegacyFonts.fontsFor("ta").take(2))
        assertEquals(listOf(LegacyFonts.Font.NUDI), LegacyFonts.fontsFor("kn"))
        assertEquals(listOf(LegacyFonts.Font.ML_TT, LegacyFonts.Font.FML), LegacyFonts.fontsFor("ml").take(2))
        // Every badge tells its font apart on the strip.
        assertEquals(LegacyFonts.Font.entries.size, LegacyFonts.Font.entries.map { it.badge }.toSet().size)
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
