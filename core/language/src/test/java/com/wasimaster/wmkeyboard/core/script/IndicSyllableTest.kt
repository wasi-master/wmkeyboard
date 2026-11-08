package com.wasimaster.wmkeyboard.core.script

import com.wasimaster.wmkeyboard.core.script.IndicSyllable.Edit
import com.wasimaster.wmkeyboard.core.script.IndicSyllable.Syllable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndicSyllableTest {

    private fun typed(before: String, mark: Char): String {
        val edit = IndicSyllable.edit(before, mark) ?: return before + mark
        return before.dropLast(edit.delete) + edit.text
    }

    @Test
    fun `a bare consonant is a syllable with nothing in its slots`() {
        assertEquals(Syllable("क", "", ""), IndicSyllable.trailing("क"))
        assertEquals(Syllable("क", "", ""), IndicSyllable.trailing("नमस्ते क"))
    }

    @Test
    fun `no syllable at a word start, after a space or after an independent vowel`() {
        assertNull(IndicSyllable.trailing(""))
        assertNull(IndicSyllable.trailing("क "))
        assertNull(IndicSyllable.trailing("कई"))
        assertNull(IndicSyllable.trailing("hello"))
    }

    @Test
    fun `a joiner left after a virama waits for the next consonant`() {
        assertNull(IndicSyllable.trailing("क्‍"))
    }

    @Test
    fun `the cluster runs back over viramas, nuktas and joiners`() {
        assertEquals(Syllable("क्ष", "ि", ""), IndicSyllable.trailing("क्षि"))
        assertEquals(Syllable("स्त्र", "ी", "ं"), IndicSyllable.trailing("स्त्रीं"))
        assertEquals(Syllable("क्‍ष", "", ""), IndicSyllable.trailing("क्‍ष"))
        assertEquals(Syllable("ज़", "ा", ""), IndicSyllable.trailing("ज़ा"))
    }

    @Test
    fun `a virama on the last consonant sits in the vowel slot`() {
        assertEquals(Syllable("क", "्", ""), IndicSyllable.trailing("क्"))
    }

    @Test
    fun `a vowel sign is awaited after a consonant, a nukta or a vowel sign`() {
        assertEquals("क", IndicSyllable.awaitingVowel("क")?.text)
        assertEquals("ज़", IndicSyllable.awaitingVowel("ज़")?.text)
        assertEquals("का", IndicSyllable.awaitingVowel("का")?.text)
        assertEquals("क्ष", IndicSyllable.awaitingVowel("क्ष")?.text)
    }

    @Test
    fun `no vowel sign is awaited after a virama, a bindu or Tamil shri`() {
        assertNull(IndicSyllable.awaitingVowel("क्"))
        assertNull(IndicSyllable.awaitingVowel("कं"))
        assertNull(IndicSyllable.awaitingVowel("काः"))
        assertNull(IndicSyllable.awaitingVowel("ஸ்ரீ"))
        assertEquals("கீ", IndicSyllable.awaitingVowel("கீ")?.text)
    }

    @Test
    fun `a vowel sign goes on a consonant and replaces the one already there`() {
        assertEquals("का", typed("क", 'ा'))
        assertEquals("की", typed("का", 'ी'))
        assertEquals("कू", typed("की", 'ू'))
        assertEquals("दे", typed("दू", 'े'))
    }

    @Test
    fun `only the tail that changes is edited`() {
        assertEquals(Edit(0, "ा"), IndicSyllable.edit("क", 'ा'))
        assertEquals(Edit(1, "ी"), IndicSyllable.edit("का", 'ी'))
        assertEquals(Edit(0, "ं"), IndicSyllable.edit("का", 'ं'))
        assertEquals(Edit(0, ""), IndicSyllable.edit("का", 'ा'))
    }

    @Test
    fun `a vowel sign keeps the bindu, and a bindu keeps the vowel sign`() {
        assertEquals("कीं", typed("कां", 'ी'))
        assertEquals("कां", typed("का", 'ं'))
        assertEquals("काः", typed("कां", 'ः'))
    }

    @Test
    fun `a virama replaces the vowel sign and drops the bindu`() {
        assertEquals("क्", typed("कां", '्'))
        assertEquals("कि", typed("क्", 'ि'))
        assertEquals("कं", typed("क्", 'ं'))
    }

    @Test
    fun `a nukta goes on the consonant, under the vowel sign`() {
        assertEquals("ज़ा", typed("जा", '़'))
        assertEquals("ज़ा", typed("ज़ा", '़'))
    }

    @Test
    fun `candra E on an A sign is candra O`() {
        assertEquals("कॉ", typed("का", 'ॅ'))
        assertEquals("कॅ", typed("क", 'ॅ'))
        assertEquals("કૉ", typed("કા", 'ૅ'))
    }

    @Test
    fun `two-part vowels compose the way they are typed`() {
        assertEquals("கொ", typed("கெ", 'ா'))
        assertEquals("கோ", typed("கே", 'ா'))
        assertEquals("কো", typed("কে", 'া'))
        assertEquals("කේ", typed("කෙ", '්'))
        assertEquals("ಕೀ", typed("ಕಿ", 'ೕ'))
    }

    @Test
    fun `every script with a consonant page reads its syllables`() {
        assertEquals("কি", typed("কা", 'ি'))
        assertEquals("ਕਿ", typed("ਕਾ", 'ਿ'))
        assertEquals("કી", typed("કા", 'ી'))
        assertEquals("କି", typed("କା", 'ି'))
        assertEquals("கி", typed("கா", 'ி'))
        assertEquals("కి", typed("కా", 'ి'))
        assertEquals("ಕಿ", typed("ಕಾ", 'ಿ'))
        assertEquals("കി", typed("കാ", 'ി'))
        assertEquals("කි", typed("කා", 'ි'))
    }

    @Test
    fun `a mark with no syllable to go on is typed as it is`() {
        assertNull(IndicSyllable.edit("", 'ा'))
        assertNull(IndicSyllable.edit("अ", 'ा'))
        assertNull(IndicSyllable.edit("क", 'क'))
    }

    @Test
    fun `slots`() {
        assertEquals(IndicSyllable.Slot.VOWEL, IndicSyllable.slotOf('ा'))
        assertEquals(IndicSyllable.Slot.VOWEL, IndicSyllable.slotOf('्'))
        assertEquals(IndicSyllable.Slot.NUKTA, IndicSyllable.slotOf('़'))
        assertEquals(IndicSyllable.Slot.BINDU, IndicSyllable.slotOf('ं'))
        assertEquals(IndicSyllable.Slot.BINDU, IndicSyllable.slotOf('ੱ'))
        assertEquals(IndicSyllable.Slot.VOWEL, IndicSyllable.slotOf('ൊ'))
        assertEquals(IndicSyllable.Slot.VOWEL, IndicSyllable.slotOf('്'))
        assertNull(IndicSyllable.slotOf('क'))
        assertNull(IndicSyllable.slotOf('ஃ'))
    }

    @Test
    fun `letters that take no vowel sign are not consonants`() {
        assertEquals(false, IndicSyllable.isConsonant('ൻ'))
        assertEquals(false, IndicSyllable.isConsonant('ৎ'))
        assertEquals(false, IndicSyllable.isConsonant('आ'))
        assertEquals(true, IndicSyllable.isConsonant('ৰ'))
    }
}
