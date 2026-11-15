package com.wasimaster.wmkeyboard.ime.lookup

import com.wasimaster.wmkeyboard.core.tools.offlinedict.OfflineDictionaries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Suggestions under the Dictionary's search box and Translate's text box
 * while they are typed into (#1085): headwords of the offline dictionaries on
 * the phone that start with the text, which include idioms and phrasal verbs
 * ("take o" offers "take off", "take on", "take out"). Nothing without an
 * offline dictionary; nothing leaves the phone.
 *
 * Kept out of the keyboard's UI state on purpose: that class sits at the
 * interpreter's frame limit, and these lists change on every key.
 */
object SearchCompletions {

    private val _dictionary = MutableStateFlow<List<String>>(emptyList())
    private val _translate = MutableStateFlow<List<String>>(emptyList())

    /** For the Dictionary's search box. */
    val dictionary: StateFlow<List<String>> = _dictionary.asStateFlow()

    /** For Translate's text box. */
    val translate: StateFlow<List<String>> = _translate.asStateFlow()

    private var dictionaryJob: Job? = null
    private var translateJob: Job? = null

    /** Translate suggests only while its text is a few words; a sentence is not an idiom being typed. */
    private const val TRANSLATE_MAX_WORDS = 4

    fun dictionary(scope: CoroutineScope, text: String) {
        dictionaryJob?.cancel()
        dictionaryJob = complete(scope, text, _dictionary)
    }

    fun translate(scope: CoroutineScope, text: String) {
        translateJob?.cancel()
        val words = text.trim().split(' ').count { it.isNotEmpty() }
        if (words > TRANSLATE_MAX_WORDS || text.length > CaretWord.MAX_LENGTH) {
            _translate.value = emptyList()
            return
        }
        translateJob = complete(scope, text, _translate)
    }

    fun clearDictionary() {
        dictionaryJob?.cancel()
        _dictionary.value = emptyList()
    }

    fun clearTranslate() {
        translateJob?.cancel()
        _translate.value = emptyList()
    }

    private fun complete(scope: CoroutineScope, text: String, into: MutableStateFlow<List<String>>): Job? {
        val typed = text.trim()
        if (typed.isEmpty()) {
            into.value = emptyList()
            return null
        }
        return scope.launch(Dispatchers.IO) {
            // The text itself is already being looked up or translated.
            into.value = OfflineDictionaries.complete(typed).filterNot { it.equals(typed, ignoreCase = true) }
        }
    }
}
