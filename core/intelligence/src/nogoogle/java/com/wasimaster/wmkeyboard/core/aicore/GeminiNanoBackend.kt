package com.wasimaster.wmkeyboard.core.aicore

/**
 * F-Droid stand-in: the build carries no ML Kit, so there is no way to reach
 * AICore and [GeminiNano] never offers Gemini Nano. Same shape as src/google's.
 */
internal object GeminiNanoBackend {

    const val COMPILED = false

    suspend fun status(): GeminiNano.Status = GeminiNano.Status.UNAVAILABLE

    suspend fun tokenLimit(): Int = throw unavailable()

    suspend fun countTokens(prompt: String): Int = throw unavailable()

    suspend fun download(onProgress: (done: Long, total: Long) -> Unit): Unit = throw unavailable()

    suspend fun generate(prompt: String, onText: (String) -> Unit): String = throw unavailable()

    private fun unavailable() = GeminiNanoException(GeminiNanoException.NOT_AVAILABLE, null, "No AICore client in this build")
}
