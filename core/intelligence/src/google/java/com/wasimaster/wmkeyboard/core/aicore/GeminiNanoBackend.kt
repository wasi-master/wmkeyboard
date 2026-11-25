package com.wasimaster.wmkeyboard.core.aicore

import android.os.Build
import androidx.annotation.RequiresApi
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach

/**
 * ML Kit's Prompt API, which asks Gemini Nano through Android AICore. Only
 * [GeminiNano] calls in, and only on Android 8 or later: the AARs are
 * minSdk 26. Every ML Kit failure leaves here as a [GeminiNanoException]
 * with AICore's error code, so nothing above this file names an ML Kit type.
 * Classes and codes read from the 1.0.0-beta5 AARs.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal object GeminiNanoBackend {

    /** This build carries ML Kit's Prompt API. */
    const val COMPILED = true

    private var client: GenerativeModel? = null

    /** One client for the process: it is a handle on AICore's service, not a model in memory. */
    @Synchronized
    private fun model(): GenerativeModel = client ?: Generation.getClient().also { client = it }

    /** AICore's answer for the Prompt API, as [GeminiNano.Status]. */
    suspend fun status(): GeminiNano.Status = wrapped {
        when (model().checkStatus()) {
            FeatureStatus.AVAILABLE -> GeminiNano.Status.AVAILABLE
            FeatureStatus.DOWNLOADING -> GeminiNano.Status.DOWNLOADING
            FeatureStatus.DOWNLOADABLE -> GeminiNano.Status.DOWNLOADABLE
            else -> GeminiNano.Status.UNAVAILABLE
        }
    }

    /** How many tokens one request may carry in. */
    suspend fun tokenLimit(): Int = wrapped { model().getTokenLimit() }

    /** The tokens [prompt] takes as one request. */
    suspend fun countTokens(prompt: String): Int =
        wrapped { model().countTokens(generateContentRequest(TextPart(prompt)) {}).totalTokens }

    /**
     * Fetches the model through AICore, telling [onProgress] the bytes so far
     * and the bytes in all (0 until AICore says). Returns once it is there.
     */
    suspend fun download(onProgress: (done: Long, total: Long) -> Unit) = wrapped {
        var total = 0L
        val end = model().download()
            .onEach { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        total = status.bytesToDownload
                        onProgress(0L, total)
                    }
                    is DownloadStatus.DownloadProgress -> onProgress(status.totalBytesDownloaded, total)
                    else -> Unit
                }
            }
            // A flow that ends without saying either way had nothing to fetch.
            .firstOrNull { it is DownloadStatus.DownloadCompleted || it is DownloadStatus.DownloadFailed }
        if (end is DownloadStatus.DownloadFailed) throw end.e
    }

    /**
     * Streams one answer to [prompt]: [onText] gets each new piece as AICore
     * sends it. Returns the whole answer.
     */
    suspend fun generate(prompt: String, onText: (String) -> Unit): String = wrapped {
        val streamed = StringBuilder()
        val response = model().generateContent(
            generateContentRequest(TextPart(prompt)) {},
            object : StreamingCallback {
                override fun onNewText(text: String) {
                    streamed.append(text)
                    onText(text)
                }
            },
        )
        response.candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() } ?: streamed.toString()
    }

    private inline fun <T> wrapped(block: () -> T): T = try {
        block()
    } catch (e: GenAiException) {
        throw GeminiNanoException(e.errorCode, e.retryDelay?.toMillis(), e.message.orEmpty(), e)
    }
}
