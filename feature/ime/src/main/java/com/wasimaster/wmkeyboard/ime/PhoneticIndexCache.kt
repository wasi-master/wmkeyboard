package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.PhoneticIndex
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The phonetic indexes [WMKeyboardService.loadExtraPhonetic] builds, kept for
 * the process rather than for one service instance (issue #716).
 *
 * An index is a fold of a language's whole downloaded word list, and the Urdu
 * one runs to tens of megabytes of heap while it is being built. The system
 * destroys and recreates an input method service far more often than it kills
 * the process — every few seconds in the reports — and each new instance used
 * to build its own index from scratch. The build is not a suspension point, so
 * a destroyed instance's build ran to the end anyway, beside the new
 * instance's: a handful of those in a row filled the 256 MB heap, slowed every
 * keystroke with GC, and ended in an OutOfMemoryError inside the build.
 *
 * So builds take turns ([mutex]), a caller cancelled while waiting never
 * starts one, and a finished index is handed to the next instance as long as
 * nothing it was built from has changed ([Signature]). One index per language
 * is kept, and [retain] drops the languages no layout types through any more.
 */
internal object PhoneticIndexCache {

    /**
     * What an index was built over: every file it read, by path, size and
     * modification time, so a download, an import or a list switched off
     * reads as a different signature.
     */
    data class Signature(val files: List<Triple<String, Long, Long>>) {
        companion object {
            fun of(files: List<File>): Signature =
                Signature(files.filter(File::isFile).map { Triple(it.path, it.length(), it.lastModified()) })
        }
    }

    private class Built(val signature: Signature, val index: PhoneticIndex)

    private val mutex = Mutex()
    private val built = HashMap<String, Built>()

    suspend fun get(languageId: String, signature: Signature, build: () -> PhoneticIndex): PhoneticIndex =
        mutex.withLock {
            built[languageId]?.takeIf { it.signature == signature }?.let { return@withLock it.index }
            // Dropped before the build, not after, so the old index and the
            // new one are never both held here.
            built.remove(languageId)
            currentCoroutineContext().ensureActive()
            build().also { built[languageId] = Built(signature, it) }
        }

    /**
     * The Bengali index, by the same rules. It is built apart from the other
     * languages' (its own type, and every Bengali board reads it, not only the
     * extra phonetic layouts), so [retain] never drops it; but it was the one
     * fold still built per instance, beside a destroyed instance's build, and
     * it ran out of memory in [com.wasimaster.wmkeyboard.core.transliteration.PhoneticTableBuilder]
     * the way the Urdu one did.
     */
    suspend fun bengali(key: Any, build: () -> BengaliPhoneticIndex): BengaliPhoneticIndex =
        mutex.withLock {
            builtBengali?.takeIf { it.first == key }?.let { return@withLock it.second }
            builtBengali = null
            currentCoroutineContext().ensureActive()
            build().also { builtBengali = key to it }
        }

    private var builtBengali: Pair<Any, BengaliPhoneticIndex>? = null

    suspend fun retain(languageIds: Set<String>) {
        mutex.withLock { built.keys.retainAll(languageIds) }
    }
}
