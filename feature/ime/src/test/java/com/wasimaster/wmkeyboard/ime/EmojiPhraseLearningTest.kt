package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.plantDictionary
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.space
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An emoji is a member of the run of words it lands in: the phrase before it
 * teaches the bigram and the trigram that end on it, and the word after it
 * teaches the pair that starts on it. Before this only the word → emoji
 * bigram was learned, and "❤️ see you" taught nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
class EmojiPhraseLearningTest {

    private fun learningKeyboard(): Pair<WMKeyboardService, RecordingEditor> {
        val learning = settings(twoThumb = false).copy(learnFromTyping = true)
        val (service, editor) = TwoThumbKit.keyboard(learning)
        seedState(service, glideReadyState(settings = learning, fieldNoSuggestions = false))
        plantDictionary(service, "love" to 500, "you" to 500, "see" to 500)
        return service to editor
    }

    private fun <T> field(service: WMKeyboardService, name: String): T {
        val field = WMKeyboardService::class.java.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(service) as T
    }

    private fun call(service: WMKeyboardService, name: String, vararg args: Any) {
        val method = WMKeyboardService::class.java.declaredMethods.first {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        method.invoke(service, *args)
    }

    private fun lexicon(service: WMKeyboardService): UserLexicon = field(service, "userLexicon")

    private fun word(service: WMKeyboardService, text: String) {
        for (letter in text) tap(service, letter.toString())
        service.onKey(space)
        drain(service)
    }

    /** "love you ❤️ see", then the text settles. */
    private fun typePhrase(service: WMKeyboardService, before: String = "love you") {
        before.split(' ').forEach { word(service, it) }
        call(service, "learnEmoji", HEART)
        word(service, "see")
        call(service, "flushLearningBuffer", true)
    }

    @Test
    fun `the word before an emoji predicts it`() {
        val (service, _) = learningKeyboard()
        typePhrase(service)
        assertTrue(lexicon(service).nextWords("you", 5).contains(HEART))
    }

    @Test
    fun `the two words before an emoji predict it`() {
        val (service, _) = learningKeyboard()
        typePhrase(service)
        assertTrue(lexicon(service).nextWordsAfter("love", "you", 5).contains(HEART))
    }

    @Test
    fun `an emoji predicts the word after it`() {
        val (service, _) = learningKeyboard()
        typePhrase(service)
        assertEquals(listOf("see"), lexicon(service).nextWords(HEART, 5))
        assertTrue(lexicon(service).nextWordsAfter("you", HEART, 5).contains("see"))
    }

    @Test
    fun `the context moves on a slot at an emoji`() {
        val (service, _) = learningKeyboard()
        word(service, "love")
        word(service, "you")
        call(service, "learnEmoji", HEART)
        assertEquals(HEART, field<String?>(service, "previousWord"))
        assertEquals("you", field<String?>(service, "previousWord2"))
        assertEquals("love", field<String?>(service, "previousWord3"))
    }

    @Test
    fun `a word nothing knows teaches no emoji`() {
        val (service, _) = learningKeyboard()
        typePhrase(service, before = "qy")
        assertFalse(lexicon(service).nextWords("qy", 5).contains(HEART))
        // The emoji is still a member of the run, so the word after it learns.
        assertEquals(listOf("see"), lexicon(service).nextWords(HEART, 5))
    }

    @Test
    fun `control - learning off teaches nothing`() {
        val off = settings(twoThumb = false)
        val (service, _) = TwoThumbKit.keyboard(off)
        seedState(service, glideReadyState(settings = off, fieldNoSuggestions = false))
        plantDictionary(service, "love" to 500, "you" to 500, "see" to 500)
        typePhrase(service)
        assertTrue(lexicon(service).nextWords("you", 5).isEmpty())
        assertTrue(lexicon(service).nextWords(HEART, 5).isEmpty())
    }

    private companion object {
        const val HEART = "❤️"
    }
}
