package com.wasimaster.wmkeyboard.core.translate.bridge

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslatePlan
import com.wasimaster.wmkeyboard.core.translate.TranslateRuntime
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Awaits a Play-services Task without the coroutines-play-services artifact. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { if (cont.isActive) cont.cancel() }
}

/**
 * The half of on-device translation that touches ML Kit's translate and
 * language-id libraries, and nothing else. Reached only by reflection (see
 * `TranslateModule.BRIDGE_CLASS`), because on Play it ships in the on-demand
 * `:feature:translate` split and the base APK has none of these imports.
 *
 * Deliberately thin. Every decision lives in `OnDeviceTranslator` on the other
 * side of [TranslateRuntime], where it is compiled once for every channel and
 * can be tested without a phone; what is here is the calls themselves.
 *
 * Public with a public no-arg constructor, both for `newInstance()`.
 */
class MlKitTranslateRuntime : TranslateRuntime {

    /**
     * Lazy for the reason the ink catalogue's is: `getInstance` throws when ML
     * Kit's context never came up, and that must surface as a failed call the
     * facade can report, not as a constructor that cannot run.
     */
    private val manager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }

    private var translator: Translator? = null
    private var translatorPair: Pair<String, String>? = null
    private var identifier: LanguageIdentifier? = null

    private fun remoteModel(code: String): TranslateRemoteModel =
        TranslateRemoteModel.Builder(code).build()

    override suspend fun downloadedModels(): Set<String> =
        manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }
            .toSet()

    override fun startDownload(code: String): TranslateRuntime.PendingDownload {
        val task = manager.download(remoteModel(code), DownloadConditions.Builder().build())
        return object : TranslateRuntime.PendingDownload {
            override val isComplete: Boolean get() = task.isComplete

            override suspend fun await() {
                task.await()
            }
        }
    }

    override suspend fun deleteModel(code: String) {
        manager.deleteDownloadedModel(remoteModel(code)).await()
    }

    override suspend fun identify(text: String): List<OfflineTranslatePlan.Candidate> {
        val client = identifier ?: LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(IDENTIFY_FLOOR).build(),
        ).also { identifier = it }
        return client.identifyPossibleLanguages(text).await()
            .map { OfflineTranslatePlan.Candidate(it.languageTag, it.confidence) }
    }

    override suspend fun translate(source: String, target: String, bodies: List<String>): List<String> {
        val engine = translatorFor(source, target)
        return bodies.map { body ->
            withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) { engine.translate(body).await() }
                ?: throw IOException("On-device translation timed out")
        }
    }

    /** One pair at a time: each holds tens of megabytes. */
    private fun translatorFor(source: String, target: String): Translator {
        translator?.takeIf { translatorPair == source to target }?.let { return it }
        closeTranslator()
        val created = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
        )
        translator = created
        translatorPair = source to target
        return created
    }

    override fun loadedPair(): Pair<String, String>? = translatorPair

    override val holdsModels: Boolean get() = translator != null || identifier != null

    override fun closeTranslator() {
        runCatching { translator?.close() }
        translator = null
        translatorPair = null
    }

    override fun release() {
        closeTranslator()
        runCatching { identifier?.close() }
        identifier = null
    }

    private companion object {
        /**
         * The identifier is asked for everything it considers possible,
         * however unlikely, because a weak candidate that agrees with the
         * keyboard's own language is worth more than a refusal. The confident
         * cut is made in `OfflineTranslatePlan.resolveSource`, not here.
         */
        const val IDENTIFY_FLOOR = 0.01f

        const val TRANSLATE_TIMEOUT_MS = 30_000L
    }
}
