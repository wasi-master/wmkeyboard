package com.wasimaster.wmkeyboard.core.tools.pronounce

import android.content.Context
import com.wasimaster.wmkeyboard.core.vocab.VocabSpeaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The speaker button on a definition card, with recordings (#1146): finds the
 * voices a word has, plays the one picked, and falls back to the phone's own
 * voice through [speaker] when there are none or one cannot be played. Shared
 * by the keyboard's Dictionary tool and the Look up card.
 */
class PronunciationPlayer(context: Context, private val speaker: VocabSpeaker) {

    private val appContext = context.applicationContext

    /**
     * What the settings and the build allow. [enabled] is the recordings
     * setting, [online] whether this build and the user let the keyboard
     * reach the internet, [keep] whether fetched recordings stay for offline
     * use, and [forvoKey] the user's own Forvo key, blank for none.
     */
    data class Options(val enabled: Boolean, val online: Boolean, val keep: Boolean, val forvoKey: String)

    /**
     * The voices of [word] in [language]: asked for when online and kept, else
     * the ones kept from the last look-up. Empty when recordings are off or
     * nobody has recorded the word.
     */
    suspend fun voices(word: String, language: String, options: Options): List<Recording> {
        if (!options.enabled || word.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val store = PronunciationStore.open(appContext, options.keep)
            if (!options.online) return@withContext store.remembered(word, language)
            try {
                Pronunciations.fetch(word, language, options.forvoKey).also { found ->
                    if (found.isNotEmpty()) store.remember(word, language, found)
                }
            } catch (e: java.io.IOException) {
                // HTTP failures too: ToolHttpException is one.
                store.remembered(word, language)
            } catch (e: kotlinx.serialization.SerializationException) {
                store.remembered(word, language)
            }
        }
    }

    /**
     * Plays [recording], fetched to the phone first so it plays offline next
     * time. The phone's voice reads [word] in [locale] instead when the file
     * cannot be had, and when it will not play. [onDone] as [VocabSpeaker.speak].
     */
    suspend fun play(
        recording: Recording,
        word: String,
        locale: Locale,
        options: Options,
        rate: Float = 1f,
        pitch: Float = 1f,
        onDone: (() -> Unit)? = null,
    ) {
        val file = withContext(Dispatchers.IO) {
            val store = PronunciationStore.open(appContext, options.keep)
            store.local(recording) ?: if (options.online) runCatching { store.fetch(recording) }.getOrNull() else null
        }
        speaker.speak(word, file?.path, rate, pitch, locale, onDone)
    }
}
