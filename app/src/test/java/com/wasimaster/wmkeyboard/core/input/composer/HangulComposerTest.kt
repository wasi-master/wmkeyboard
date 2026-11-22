package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Korean composition, tested by driving jamo sequences through the composer the
 * way the keyboard feeds them and asserting the syllable blocks that result.
 */
class HangulComposerTest {

    private fun compose(jamos: String) = HangulComposer.composeBuffer(jamos)

    @Test fun `it is a transliterating input method`() {
        assertTrue(HangulComposer.isTransliterating)
    }

    @Test fun `a simple initial-medial-final forms one syllable`() {
        assertEquals("한", compose("ㅎㅏㄴ"))
        assertEquals("가", compose("ㄱㅏ"))
        assertEquals("강", compose("ㄱㅏㅇ"))
    }

    @Test fun `a trailing consonant starts a new syllable when no vowel follows`() {
        // 한 + 글: the ㄱ has no vowel yet, so 한 flushes and ㄱ begins the next.
        assertEquals("한글", compose("ㅎㅏㄴㄱㅡㄹ"))
    }

    @Test fun `a final becomes the next initial when a vowel follows`() {
        // 간 + ㅏ: the final ㄴ moves to a fresh syllable → 가나.
        assertEquals("가나", compose("ㄱㅏㄴㅏ"))
    }

    @Test fun `compound medials combine`() {
        assertEquals("과", compose("ㄱㅗㅏ")) // ㅗ + ㅏ → ㅘ
        assertEquals("귀", compose("ㄱㅜㅣ")) // ㅜ + ㅣ → ㅟ
    }

    @Test fun `compound finals combine and re-split before a vowel`() {
        assertEquals("갉", compose("ㄱㅏㄹㄱ")) // ㄹ + ㄱ → ㄺ
        // 갉 + ㅣ: ㄺ splits, ㄹ stays as 갈's final, ㄱ starts 기 → 갈기.
        assertEquals("갈기", compose("ㄱㅏㄹㄱㅣ"))
    }

    @Test fun `non-jamo characters pass through and flush the syllable`() {
        assertEquals("가 나", compose("ㄱㅏ ㄴㅏ"))
        assertEquals("한.", compose("ㅎㅏㄴ."))
    }

    @Test fun `a lone leading vowel is emitted as-is`() {
        assertEquals("ㅏ", compose("ㅏ"))
    }

    @Test fun `every syllable produced is in the Hangul block`() {
        for (ch in compose("ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ")) {
            if (ch.isWhitespace()) continue
            assertTrue("'$ch' should be a Hangul syllable", ch.code in 0xAC00..0xD7A3)
        }
    }

    // --- Conjoining jamo (U+1100 block), what the three-set layouts emit. ---
    //
    // Spelled by code point rather than as literals: the conjoining and
    // compatibility forms of a letter look identical in an editor, and a test
    // that quietly used the wrong block would pass for the wrong reason.

    private fun cho(compat: Char) = (0x1100 + "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ".indexOf(compat)).toChar()
    private fun jung(compat: Char) = (0x1161 + "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ".indexOf(compat)).toChar()
    private fun jong(compat: Char) = (0x11A7 + " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ".indexOf(compat)).toChar()

    @Test fun `conjoining initial, medial and final form one syllable`() {
        assertEquals("한", compose("${cho('ㅎ')}${jung('ㅏ')}${jong('ㄴ')}"))
        assertEquals("가", compose("${cho('ㄱ')}${jung('ㅏ')}"))
        assertEquals("한글", compose("${cho('ㅎ')}${jung('ㅏ')}${jong('ㄴ')}${cho('ㄱ')}${jung('ㅡ')}${jong('ㄹ')}"))
    }

    @Test fun `a conjoining initial never becomes a final`() {
        // Two-set ㄱ after 가 closes the syllable as 각; three-set ᄀ says it is
        // an initial, so 가 flushes and ᄀ starts the next syllable.
        assertEquals("각", compose("ㄱㅏㄱ"))
        assertEquals("가ㄱ", compose("${cho('ㄱ')}${jung('ㅏ')}${cho('ㄱ')}"))
        assertEquals("가기", compose("${cho('ㄱ')}${jung('ㅏ')}${cho('ㄱ')}${jung('ㅣ')}"))
    }

    @Test fun `a vowel after a conjoining final does not re-split the syllable`() {
        // The final was typed as a final. Compare the two-set 간+ㅏ → 가나 above.
        assertEquals("간ㅏ", compose("${cho('ㄱ')}${jung('ㅏ')}${jong('ㄴ')}${jung('ㅏ')}"))
        assertEquals("간아", compose("${cho('ㄱ')}${jung('ㅏ')}${jong('ㄴ')}${cho('ㅇ')}${jung('ㅏ')}"))
    }

    @Test fun `a conjoining initial typed twice doubles`() {
        for ((plain, tense) in listOf('ㄱ' to "까", 'ㄷ' to "따", 'ㅂ' to "빠", 'ㅅ' to "싸", 'ㅈ' to "짜")) {
            assertEquals(tense, compose("${cho(plain)}${cho(plain)}${jung('ㅏ')}"))
        }
        // Only the five tense letters double; ㄴㄴ is two initials.
        assertEquals("ㄴ나", compose("${cho('ㄴ')}${cho('ㄴ')}${jung('ㅏ')}"))
        // A tense final typed as itself still works.
        assertEquals("갂", compose("${cho('ㄱ')}${jung('ㅏ')}${jong('ㄲ')}"))
    }

    @Test fun `conjoining compound medials and finals combine`() {
        assertEquals("과", compose("${cho('ㄱ')}${jung('ㅗ')}${jung('ㅏ')}"))
        assertEquals("귀", compose("${cho('ㄱ')}${jung('ㅜ')}${jung('ㅣ')}"))
        assertEquals("갉", compose("${cho('ㄱ')}${jung('ㅏ')}${jong('ㄹ')}${jong('ㄱ')}"))
        assertEquals("갉", compose("${cho('ㄱ')}${jung('ㅏ')}${jong('ㄺ')}"))
        // A pair with no compound form: the second final stands alone.
        assertEquals("간ㄷ", compose("${cho('ㄱ')}${jung('ㅏ')}${jong('ㄴ')}${jong('ㄷ')}"))
    }

    @Test fun `isolated conjoining jamo are emitted as compatibility jamo`() {
        assertEquals("ㄴ", compose("${jong('ㄴ')}"))
        assertEquals("ㄱ", compose("${cho('ㄱ')}"))
        assertEquals("ㅏ", compose("${jung('ㅏ')}"))
        assertEquals("ㄴ가", compose("${jong('ㄴ')}${cho('ㄱ')}${jung('ㅏ')}"))
        for (ch in compose("${cho('ㄱ')}${jong('ㅅ')}")) {
            assertTrue("'$ch' should not be a conjoining jamo", ch.code !in 0x1100..0x11FF)
        }
    }

    @Test fun `three-set layouts type every syllable the two-set one does`() {
        // Each syllable of 안녕하세요, spelled positionally, lands on the same block.
        val threeSet = buildString {
            append(cho('ㅇ')); append(jung('ㅏ')); append(jong('ㄴ'))
            append(cho('ㄴ')); append(jung('ㅕ')); append(jong('ㅇ'))
            append(cho('ㅎ')); append(jung('ㅏ'))
            append(cho('ㅅ')); append(jung('ㅔ'))
            append(cho('ㅇ')); append(jung('ㅛ'))
        }
        assertEquals(compose("ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ"), compose(threeSet))
        assertEquals("안녕하세요", compose(threeSet))
    }

    @Test fun `old jamo build a syllable out of conjoining jamo`() {
        assertEquals("\u1112\u119E\u11AB", compose("ㅎㆍㄴ")) // ᄒᆞᆫ
        assertEquals("\u1140\u1161", compose("ㅿㅏ")) // ᅀᅡ
        assertEquals("\u1100\u1161\u11F0", compose("ㄱㅏㆁ")) // 가ᇰ
        assertEquals("\u1159\u1173", compose("ㆆㅡ")) // ᅙᅳ
    }

    @Test fun `an old syllable hands its final on and the next syllable is modern`() {
        assertEquals("\u1112\u119E나", compose("ㅎㆍㄴㅏ"))
        assertEquals("\u1140\u1161가", compose("ㅿㅏㄱㅏ"))
    }

    @Test fun `araea compounds and lone old jamo stay letters`() {
        assertEquals("ㆎ", compose("ㆍㅣ"))
        assertEquals("ㆍ", compose("ㆍ"))
        assertEquals("ㅿ", compose("ㅿ"))
        assertEquals("\u1109\u11A2", compose("ㅅㆍㆍ")) // ᄉᆢ
    }

    @Test fun `conjoining old jamo compose by their own position`() {
        assertEquals("\u1140\u119E\u11EB", compose("\u1140\u119E\u11EB"))
    }
}
