package com.wasimaster.wmkeyboard.core.aicore

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/**
 * A failure from AICore, with its error code ([GeminiNano] names the ones it
 * acts on) and the wait it asked for, if any. The message is the one the
 * chat shows once [GeminiNano] has put it in words.
 */
class GeminiNanoException(
    val code: Int,
    val retryDelayMs: Long?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    companion object {
        // ML Kit's GenAiException.ErrorCode values, read from the 1.0.0-beta5
        // AAR; copied because src/main cannot see ML Kit.
        const val CANCELLED = 7
        const val NOT_AVAILABLE = 8
        const val BUSY = 9
        const val REQUEST_TOO_LARGE = 12
        const val NOT_SUPPORTED = 16
        const val PER_APP_BATTERY_USE_QUOTA_EXCEEDED = 27
        const val BACKGROUND_USE_BLOCKED = 30
        const val NOT_ENOUGH_DISK_SPACE = 501
        const val NEEDS_SYSTEM_UPDATE = 604
        const val AICORE_INCOMPATIBLE = -101
    }
}

/**
 * Gemini Nano, Google's model that Android AICore keeps on some phones
 * (recent Pixels and Galaxy flagships), asked through ML Kit's Prompt API.
 *
 * Only for the app's own screens: the AI chat and the Settings assistant.
 * AICore answers an app only while one of its activities is in front, and
 * refuses everything else with BACKGROUND_USE_BLOCKED (30). The keyboard runs
 * over other apps, so it is never in front, which a test on a Galaxy S25
 * Ultra (2026-10-20) confirmed: the same call from the keyboard's process
 * worked with our own screen on top and failed in 13 ms over any other app.
 * So the keyboard's panels never offer it.
 *
 * Offered only where the build has Google's SDKs (not F-Droid's), the build
 * declares the internet permission (not the offline build: AICore fetches the
 * model itself, but that build promises to reach nothing), Android is 8 or
 * later (ML Kit's floor) and AICore says the Prompt API is there or can be
 * downloaded. [refresh] asks AICore; the app's AI screens call it as they open.
 */
object GeminiNano {

    /** What the chat stores as the model's id, and the pickers' key for it. */
    const val MODEL_ID = "aicore:gemini-nano"

    /** The model's name, which is Google's and is not translated. */
    const val LABEL = "Gemini Nano"

    enum class Status { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

    /** One turn of a conversation, oldest first. */
    data class Turn(val fromUser: Boolean, val text: String)

    private val _status = MutableStateFlow(Status.UNKNOWN)

    /** AICore's last answer; [Status.UNKNOWN] until [refresh] has asked. */
    val status: StateFlow<Status> = _status

    /** The build could offer it, before asking the phone. */
    val buildOffers: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && BuildConfig.GOOGLE_SDKS &&
            GoogleLibraries.usable && GeminiNanoBackend.COMPILED && InternetPermission.declared

    /** Worth a row in the app's model pickers: here, or AICore can fetch it. */
    val offered: Boolean
        get() = when (_status.value) {
            Status.AVAILABLE, Status.DOWNLOADABLE, Status.DOWNLOADING -> true
            Status.UNKNOWN, Status.UNAVAILABLE -> false
        }

    /** The request size AICore last gave; the documented 4,000 until asked. */
    @Volatile
    var tokenLimit: Int = DEFAULT_TOKEN_LIMIT
        private set

    /**
     * Asks AICore whether the Prompt API is on this phone, and remembers the
     * answer in [status]. Any failure counts as not here: a phone without
     * AICore says so by throwing.
     */
    @Suppress("UnusedParameter")
    suspend fun refresh(context: Context): Status {
        if (!buildOffers || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            _status.value = Status.UNAVAILABLE
            return Status.UNAVAILABLE
        }
        val answer = withContext(Dispatchers.IO) {
            try {
                GeminiNanoBackend.status()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Status.UNAVAILABLE
            } catch (e: LinkageError) {
                // A phone whose Play services lack a class ML Kit expects.
                Status.UNAVAILABLE
            }
        }
        _status.value = answer
        return answer
    }

    /**
     * Makes sure the model is on the phone, downloading it through AICore
     * when it is not, with [onProgress] told the bytes so far and in all
     * (0 while AICore has not said). Blocking; call off the main thread.
     * Throws a [GeminiNanoException] whose message the chat can show.
     */
    fun prepareBlocking(
        context: Context,
        isActive: () -> Boolean = { true },
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !buildOffers) throw notHere(app)
        runBlocking {
            watched(isActive) {
                val now = try {
                    GeminiNanoBackend.status()
                } catch (e: GeminiNanoException) {
                    throw explained(app, e)
                }
                _status.value = now
                when (now) {
                    Status.AVAILABLE -> Unit
                    Status.DOWNLOADABLE, Status.DOWNLOADING -> {
                        try {
                            GeminiNanoBackend.download(onProgress)
                        } catch (e: GeminiNanoException) {
                            throw explained(app, e)
                        }
                        _status.value = Status.AVAILABLE
                    }
                    Status.UNKNOWN, Status.UNAVAILABLE -> throw notHere(app)
                }
            }
        }
    }

    /**
     * One answer to [turns] under [system], streamed to [onPartial] as the
     * whole answer so far. The conversation is laid out as one prompt (the
     * Prompt API takes one), cut from its oldest turns to fit AICore's token
     * limit. AICore's BUSY, its short-term quota, is waited out a few times
     * for as long as it asks. Blocking; call off the main thread. Stops when
     * [isActive] turns false, throwing a CancellationException.
     */
    fun generateBlocking(
        context: Context,
        system: String,
        turns: List<Turn>,
        onPartial: ((String) -> Unit)? = null,
        isActive: () -> Boolean = { true },
    ): String {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !buildOffers) throw notHere(app)
        if (turns.isEmpty()) return ""
        return runBlocking {
            watched(isActive) { asked(app, fitted(system, turns), onPartial) }
        }
    }

    /**
     * One request, asked again while AICore answers BUSY before anything came
     * back, waiting as long as it asks (or longer each time when it does not
     * say). A refusal after text arrived is not retried: the answer would be
     * written twice.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun asked(app: Context, prompt: String, onPartial: ((String) -> Unit)?): String {
        var attempt = 0
        while (true) {
            val streamed = StringBuilder()
            try {
                return GeminiNanoBackend.generate(prompt) { piece ->
                    streamed.append(piece)
                    onPartial?.invoke(streamed.toString())
                }
            } catch (e: GeminiNanoException) {
                if (e.code != GeminiNanoException.BUSY || streamed.isNotEmpty() || attempt >= BUSY_RETRIES) {
                    throw explained(app, e)
                }
                delay(e.retryDelayMs?.coerceIn(BUSY_MIN_WAIT_MS, BUSY_MAX_WAIT_MS) ?: (BUSY_MIN_WAIT_MS shl attempt))
                attempt++
            }
        }
    }

    /** [prompt] cut to AICore's token limit, measured once with AICore's own count where it gives one. */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun fitted(system: String, turns: List<Turn>): String {
        val limit = try {
            GeminiNanoBackend.tokenLimit().takeIf { it > 0 } ?: DEFAULT_TOKEN_LIMIT
        } catch (e: GeminiNanoException) {
            DEFAULT_TOKEN_LIMIT
        }
        tokenLimit = limit
        val budget = (limit - TOKEN_MARGIN).coerceAtLeast(limit / 2)
        val whole = fold(system, turns)
        val counted = try {
            GeminiNanoBackend.countTokens(whole).takeIf { it > 0 }
        } catch (e: GeminiNanoException) {
            null
        }
        if (counted != null && counted <= budget) return whole
        // Characters per token for this text, from AICore's count when it
        // gave one, else a guess that errs short.
        val perToken = if (counted != null) whole.length.toDouble() / counted else GUESSED_CHARS_PER_TOKEN
        return fit(system, turns, (budget * perToken * FIT_SLACK).toInt())
    }

    /** The conversation as one prompt: [system], what was said, then the message to answer. */
    internal fun fold(system: String, turns: List<Turn>): String = buildString {
        if (system.isNotBlank()) append(system.trim()).append("\n\n")
        val earlier = turns.dropLast(1)
        if (earlier.isNotEmpty()) {
            append("Conversation so far:\n\n")
            for (turn in earlier) {
                append(if (turn.fromUser) "User: " else "Assistant: ")
                append(turn.text).append("\n\n")
            }
            append("Reply to this latest message:\n\n")
        }
        append(turns.lastOrNull()?.text.orEmpty())
    }

    /**
     * [fold] in at most [maxChars]: the oldest turns go first, then the
     * start of the newest message, whose end is what is being asked.
     */
    internal fun fit(system: String, turns: List<Turn>, maxChars: Int): String {
        if (turns.isEmpty()) return fold(system, turns).take(maxChars)
        var kept = turns
        var text = fold(system, kept)
        while (text.length > maxChars && kept.size > 1) {
            kept = kept.drop(1)
            text = fold(system, kept)
        }
        if (text.length <= maxChars) return text
        val last = kept.last()
        val over = text.length - maxChars
        text = fold(system, listOf(last.copy(text = last.text.takeLast((last.text.length - over).coerceAtLeast(0)))))
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    /**
     * Runs [block], cancelling it within a tenth of a second of [isActive]
     * turning false: the chat retires a run by its number, not by cancelling
     * the thread it blocks.
     */
    private suspend fun <T> watched(isActive: () -> Boolean, block: suspend () -> T): T = coroutineScope {
        val work = async { block() }
        launch {
            while (work.isActive) {
                if (!isActive()) work.cancel()
                delay(WATCH_MS)
            }
        }
        work.await()
    }

    private fun notHere(context: Context) =
        GeminiNanoException(GeminiNanoException.NOT_AVAILABLE, null, context.getString(R.string.core_intel_nano_error_unavailable))

    /** [e] with the words the chat shows for its code. */
    internal fun explained(context: Context, e: GeminiNanoException): GeminiNanoException {
        val text = when (e.code) {
            GeminiNanoException.BUSY -> context.getString(R.string.core_intel_nano_error_busy)
            GeminiNanoException.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> context.getString(R.string.core_intel_nano_error_quota)
            GeminiNanoException.BACKGROUND_USE_BLOCKED -> context.getString(R.string.core_intel_nano_error_background)
            GeminiNanoException.NEEDS_SYSTEM_UPDATE -> context.getString(R.string.core_intel_nano_error_update)
            GeminiNanoException.NOT_ENOUGH_DISK_SPACE -> context.getString(R.string.core_intel_nano_error_space)
            GeminiNanoException.REQUEST_TOO_LARGE -> context.getString(R.string.core_intel_nano_error_too_large)
            GeminiNanoException.NOT_AVAILABLE,
            GeminiNanoException.NOT_SUPPORTED,
            GeminiNanoException.AICORE_INCOMPATIBLE,
            -> context.getString(R.string.core_intel_nano_error_unavailable)
            else -> context.getString(R.string.core_intel_nano_error_other, e.code)
        }
        return GeminiNanoException(e.code, e.retryDelayMs, text, e)
    }

    /** What the Prompt API documents for one request: under 4,000 tokens. */
    private const val DEFAULT_TOKEN_LIMIT = 4_000

    /** Room kept under the limit for the count being an estimate. */
    private const val TOKEN_MARGIN = 200

    private const val GUESSED_CHARS_PER_TOKEN = 3.0
    private const val FIT_SLACK = 0.95

    /** BUSY is AICore's short-term quota; it is asked again this many times. */
    private const val BUSY_RETRIES = 3
    private const val BUSY_MIN_WAIT_MS = 1_000L
    private const val BUSY_MAX_WAIT_MS = 10_000L

    private const val WATCH_MS = 100L
}
