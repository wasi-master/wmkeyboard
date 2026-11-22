package com.wasimaster.wmkeyboard.core.input.chewing

import android.content.Context
import android.os.UserManager
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * What [ChewingComposer] asks: libchewing's answer for a reading, and
 * telling it a pick. [ChewingRuntime] in the keyboard; a stand-in in tests,
 * which have no native library.
 */
interface ChewingConverter {

    /** Bumped when what libchewing would answer changes: opening, a learnt pick, a cleared history. */
    val generation: Int

    /** [reading] converted, with up to [limit] candidates; null while libchewing cannot answer. */
    fun convert(reading: String, limit: Int): ChewingRuntime.Menu?

    fun learnPhrase(reading: String, consumed: Int, phrase: String)

    fun learnSentence(reading: String, sentence: Int)
}

/**
 * libchewing's one instance in this process: whether it can run, opening it
 * on the downloaded dictionary, and the converter every [ChewingComposer]
 * asks.
 *
 * The keyboard asks from the main thread on every keystroke and must never
 * wait, so every call takes [lock] with `tryLock`: while libchewing is
 * opening or closing, a composer gets null back and converts with the
 * built-in Zhuyin composer instead. Opening (it reads a dictionary of a few
 * megabytes) runs on [scope].
 *
 * What libchewing learns lives in `filesDir/chewing/user/chewing.dat`,
 * credential-protected like the other learned words, so before the first
 * unlock libchewing does not start and Zhuyin types on the built-in engine.
 * The settings backup carries that file ([exportLearned], [restoreLearned]).
 */
object ChewingRuntime : ChewingConverter {

    sealed interface Status {
        /** Not asked yet, or Chewing is not picked. */
        data object Off : Status

        /** Before the first unlock: the dictionary and the learning are behind the credential. */
        data object Locked : Status

        /** The library is not on the device yet ([com.wasimaster.wmkeyboard.core.modules.FeatureModules.chewing]). */
        data object NeedsLibrary : Status

        /** The dictionary pack ([ChewingData.pack]) is not downloaded. */
        data object NeedsData : Status

        data object Starting : Status

        /** Converting, on dictionary release [dataVersion]. */
        data class Ready(val dataVersion: String) : Status

        /** libchewing would not open the dictionary; [detail] is its own English. */
        data class Failed(val detail: String) : Status
    }

    /** One answer: candidate texts, the reading characters each consumes, and what the composing region shows. */
    class Menu(
        val texts: List<String>,
        val consumed: IntArray,
        /** Which whole-sentence conversion a candidate is (0 the best), or -1 for a phrase. */
        val sentence: IntArray,
        /** The complete syllables converted, as the composing region shows them. */
        val preedit: String,
        /** Where in the reading the syllable still being typed begins. */
        val tail: Int,
    )

    private const val TAG = "ChewingRuntime"

    /** How long after a pick the learned phrases are written out, so a burst of picks writes once. */
    private const val SYNC_DELAY_MS = 5_000L

    /** Past this, a backup leaves the learned phrases out rather than hold them in one string. */
    private const val EXPORT_MAX_BYTES = 8L * 1024 * 1024

    private val mutableStatus = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    private val lock = ReentrantLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var filesDir: File? = null
    @Volatile private var started = false
    @Volatile private var startJob: Job? = null
    @Volatile private var syncJob: Job? = null

    @Volatile
    override var generation: Int = 0
        private set

    /** Whether composers may convert through libchewing right now. */
    val ready: Boolean get() = started

    /** Whether this build has the library at all, fetched or not; for the licences screen. */
    val libraryShipped: Boolean get() = ChewingNative.library.shipped

    fun userDir(filesDir: File) = File(filesDir, "chewing")

    fun userFile(filesDir: File) = File(userDir(filesDir), "user/chewing.dat")

    /**
     * Points the runtime at this install and opens libchewing off the main
     * thread when it can: storage unlocked, library here, dictionary
     * downloaded. Cheap to call again.
     */
    fun prepare(context: Context) {
        val app = context.applicationContext
        val unlocked = app.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        if (!unlocked) {
            mutableStatus.value = Status.Locked
            return
        }
        if (filesDir == null) filesDir = app.filesDir
        if (started || startJob?.isActive == true) return
        startJob = scope.launch { start() }
    }

    /** Re-reads the disk, after the dictionary was downloaded, imported or deleted. */
    fun recheck(context: Context) {
        val dir = context.applicationContext.filesDir
        if (started && ChewingData.file(dir) == null) {
            scope.launch { stop(Status.NeedsData) }
            return
        }
        prepare(context)
    }

    /** Closes libchewing, saving what it learned; the composers fall back to the built-in one. */
    fun shutDown() {
        scope.launch { stop(Status.Off) }
    }

    private fun start() {
        val dir = filesDir ?: return
        val data = ChewingData.file(dir)
        if (data == null) {
            mutableStatus.value = Status.NeedsData
            return
        }
        if (!ChewingNative.library.available) {
            mutableStatus.value = Status.NeedsLibrary
            return
        }
        mutableStatus.value = Status.Starting
        lock.lock()
        try {
            if (started) return
            val user = userFile(dir).apply { parentFile?.mkdirs() }
            // A library from another build of the bridge links but lacks a
            // symbol; that is a failed start, not a crash of the keyboard.
            val error = try {
                ChewingNative.nativeStart(data.path, user.path)
            } catch (e: UnsatisfiedLinkError) {
                e.message ?: "libwmchewing does not match this version"
            }
            if (error != null) {
                DebugLog.w(TAG, "start failed: $error")
                mutableStatus.value = Status.Failed(error)
                return
            }
            started = true
            generation++
            mutableStatus.value = Status.Ready(ChewingNative.nativeDictVersion())
        } finally {
            lock.unlock()
        }
        ConversionEngines.changed()
    }

    private fun stop(status: Status) {
        lock.lock()
        try {
            if (started) {
                started = false
                ChewingNative.nativeStop()
                generation++
            }
        } finally {
            lock.unlock()
        }
        mutableStatus.value = status
        ConversionEngines.changed()
    }

    override fun convert(reading: String, limit: Int): Menu? = withLock {
        val info = IntArray(2 + 2 * limit)
        ChewingNative.nativeConvert(reading, limit, info)?.let { unpack(it, info, reading.length) }
    }

    override fun learnPhrase(reading: String, consumed: Int, phrase: String) =
        learnt { ChewingNative.nativeLearnPhrase(reading, consumed, phrase) }

    override fun learnSentence(reading: String, sentence: Int) =
        learnt { ChewingNative.nativeLearnSentence(reading, sentence) }

    /** Forgets every phrase libchewing learned, on disk too. */
    fun clearHistory(context: Context) {
        val dir = context.applicationContext.filesDir
        scope.launch {
            lock.lock()
            try {
                if (started) ChewingNative.nativeClearLearned() else userDir(dir).deleteRecursively()
                generation++
            } finally {
                lock.unlock()
            }
        }
    }

    /** Bytes of learned phrases on disk, for the storage screen. */
    fun historySize(filesDir: File): Long =
        userDir(filesDir).walkBottomUp().filter(File::isFile).sumOf(File::length)

    /**
     * The learned phrases as they are on disk, for the settings backup;
     * null with none, or past [EXPORT_MAX_BYTES]. Written out first when
     * libchewing is open, so the last few picks are in it.
     */
    fun exportLearned(filesDir: File): ByteArray? {
        if (started && lock.tryLock()) {
            try {
                if (started) ChewingNative.nativeSync()
            } finally {
                lock.unlock()
            }
        }
        val file = userFile(filesDir)
        if (!file.isFile || file.length() == 0L || file.length() > EXPORT_MAX_BYTES) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /**
     * Puts backed-up learned phrases in place. An open libchewing is closed
     * first (it would write its own copy back over them) and opened again
     * on them. Blocks for that, so off the main thread.
     */
    fun restoreLearned(filesDir: File, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > EXPORT_MAX_BYTES) return
        lock.lock()
        val wasStarted = started
        try {
            if (started) {
                started = false
                ChewingNative.nativeStop()
                generation++
            }
            val file = userFile(filesDir).apply { parentFile?.mkdirs() }
            val part = File(file.path + ".part")
            part.writeBytes(bytes)
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
        } finally {
            lock.unlock()
        }
        if (wasStarted) {
            ConversionEngines.changed()
            startJob = scope.launch { start() }
        }
    }

    private fun learnt(commit: () -> Boolean) {
        if (withLock(commit) != true) return
        generation++
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(SYNC_DELAY_MS)
            lock.lock()
            try {
                if (started) ChewingNative.nativeSync()
            } finally {
                lock.unlock()
            }
        }
    }

    private inline fun <T> withLock(block: () -> T): T? {
        if (!started || !lock.tryLock()) return null
        try {
            if (!started) return null
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** [strings] and [info] as [ChewingNative.nativeConvert] fills them, for a reading of [length] characters. */
    internal fun unpack(strings: Array<String>, info: IntArray, length: Int): Menu {
        val count = info[0].coerceIn(0, (strings.size - 1).coerceAtLeast(0)).coerceAtMost((info.size - 2) / 2)
        return Menu(
            texts = strings.take(count),
            consumed = IntArray(count) { info[2 + 2 * it].coerceIn(1, length.coerceAtLeast(1)) },
            sentence = IntArray(count) { info[3 + 2 * it] },
            preedit = strings.getOrElse(count) { "" },
            tail = info[1].coerceIn(0, length),
        )
    }
}
