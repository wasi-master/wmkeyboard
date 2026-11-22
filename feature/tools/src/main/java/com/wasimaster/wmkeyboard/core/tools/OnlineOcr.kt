package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.providers.PluginProviders
import com.wasimaster.wmkeyboard.core.providers.ProviderRunner
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import java.io.File

/**
 * The OCR tool's online readers in the user's order: a vision model (#469)
 * and Rens servers (#727). Shared by the OCR panel and the AI's read-text
 * tool. [pick] is the panel's choice in pick mode; null walks the whole list.
 *
 * Blocking; call on an IO dispatcher. Throws the last reader's failure, or
 * [NoReaderException] when no reader is on.
 */
object OnlineOcr {

    /** The online rows alone: the list also holds the engines on the phone, which the OCR panel runs itself. */
    fun rows(settings: KeyboardSettings, pick: String? = null): List<ResolvedProvider> =
        settings.resolveProviders(ProviderTool.OCR, pick = pick).filter { it.typeId !in ProviderTypes.OCR_ENGINES }

    fun read(settings: KeyboardSettings, jpeg: ByteArray, pick: String? = null): String =
        read(rows(settings, pick), jpeg)

    fun read(rows: List<ResolvedProvider>, jpeg: ByteArray): String {
        if (rows.isEmpty()) throw NoReaderException()
        val outcome = ProviderRunner.fallback(rows, key = { it.key }) { row ->
            when (row.typeId) {
                ProviderTypes.VISION_OCR.id -> VisionOcrClient.read(row.url, row.model, row.apiKey, jpeg)
                ProviderTypes.RENS.id -> RensClient.readText(jpeg, row.url)
                ProviderTypes.PLUGIN.id ->
                    PluginReplies.text(PluginProviders.call(row.key, PluginReplies.imageRequest(jpeg))) ?: throw NoReaderException()
                else -> throw NoReaderException()
            }
        }
        return when (outcome) {
            is ProviderRunner.Outcome.Found -> outcome.value
            ProviderRunner.Outcome.NotFound -> throw NoReaderException()
            is ProviderRunner.Outcome.Failed -> throw outcome.error ?: NoReaderException()
        }
    }

    /** No online reader is on, or the one that is cannot be asked. */
    class NoReaderException : IllegalStateException("No online text reader is on")
}

/**
 * The camera tool's Translate button (#727): the photo's text translated in
 * place, as a new picture written to [out]. Rens is the one kind today.
 */
object PhotoTranslate {

    fun rows(settings: KeyboardSettings, pick: String? = null): List<ResolvedProvider> =
        settings.resolveProviders(ProviderTool.PHOTO_TRANSLATE, pick = pick)

    fun translate(rows: List<ResolvedProvider>, jpeg: ByteArray, target: String, out: File) {
        if (rows.isEmpty()) throw OnlineOcr.NoReaderException()
        val outcome = ProviderRunner.fallback(rows, key = { it.key }) { row ->
            when (row.typeId) {
                ProviderTypes.RENS.id -> RensClient.translate(jpeg, row.url, target, out)
                else -> throw OnlineOcr.NoReaderException()
            }
        }
        if (outcome is ProviderRunner.Outcome.Failed) throw outcome.error ?: OnlineOcr.NoReaderException()
        if (outcome !is ProviderRunner.Outcome.Found) throw OnlineOcr.NoReaderException()
    }
}
