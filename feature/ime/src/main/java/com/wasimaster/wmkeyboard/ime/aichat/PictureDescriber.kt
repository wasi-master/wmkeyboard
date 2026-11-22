package com.wasimaster.wmkeyboard.ime.aichat

import android.content.Context
import android.util.Base64
import com.wasimaster.wmkeyboard.core.localllm.GgufFile
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmEngine
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.aiThrough
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.core.tools.AiVision
import com.wasimaster.wmkeyboard.ime.R
import java.io.IOException

/**
 * A model that sees, looking at a picture for one that cannot: the
 * "Describe pictures with" setting ([com.wasimaster.wmkeyboard.core.settings.AiSettings.pictureDescriber]).
 *
 * The describer is one of the chat's model choices: a downloaded on-device
 * model the catalog says sees (or an imported GGUF with its projector), or a
 * row of the AI providers list whose model is not known to be blind. Its
 * answer is text, which any model can read.
 */
object PictureDescriber {

    /** The longest description asked of a cloud model: a few paragraphs and the picture's text. */
    private const val MAX_TOKENS = 1_200

    /**
     * Whether [choice] is known to read pictures: what the pickers mark.
     * A cloud model nothing knows about is not marked, though it is still
     * sent pictures ([AiVision.sees]).
     */
    fun seesPictures(context: Context, settings: KeyboardSettings, choice: AiChatController.ModelChoice): Boolean =
        when (choice.provider) {
            AiProvider.ON_DEVICE -> localSees(context, choice.localModelId)
            else -> settings.aiThrough(choice.rowKey)?.let { AiVision.knows(AiClient.config(it)) == true } == true
        }

    /** An on-device model that reads pictures: a catalog row that does, or an imported GGUF with its projector beside it. */
    fun localSees(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        LocalLlmCatalog.canSee(id)?.let { return it }
        val file = LocalLlmStore.selectedModelFile(context.filesDir, id) ?: return false
        return file.extension.equals("gguf", ignoreCase = true) && GgufFile.projectorFor(file) != null
    }

    /**
     * The choices that can describe: the on-device models that see, and the
     * cloud rows whose model may (unknown counts, since the user picked it).
     */
    fun candidates(context: Context, settings: KeyboardSettings): List<AiChatController.ModelChoice> =
        AiChatController.choices(context, settings).filter { choice ->
            when (choice.provider) {
                AiProvider.ON_DEVICE -> localSees(context, choice.localModelId)
                else -> settings.aiThrough(choice.rowKey)?.let { AiVision.sees(AiClient.config(it)) } == true
            }
        }

    /** The describer the user picked, while it can still describe; null for none. */
    fun chosen(context: Context, settings: KeyboardSettings): AiChatController.ModelChoice? {
        val key = settings.ai.pictureDescriber.takeIf { it.isNotBlank() } ?: return null
        return candidates(context, settings).firstOrNull { it.key == key }
    }

    /** The describer's name, as the model in use is told who looked. */
    fun label(context: Context, choice: AiChatController.ModelChoice): String =
        if (choice.provider == AiProvider.ON_DEVICE) {
            AiChatController.localModelLabel(choice.localModelId)
        } else {
            choice.name.ifEmpty { context.getString(choice.provider.labelRes) }
        }

    /**
     * [jpeg] put to [choice] with [system] and [user], and its answer, the
     * reasoning left out. Blocking: call off the main thread. Throws an
     * [IOException] whose message can be shown as it is.
     */
    fun look(
        context: Context,
        settings: KeyboardSettings,
        choice: AiChatController.ModelChoice,
        jpeg: ByteArray,
        system: String,
        user: String,
    ): String {
        val app = context.applicationContext
        val raw = if (choice.provider == AiProvider.ON_DEVICE) {
            val modelFile = LocalLlmStore.selectedModelFile(app.filesDir, choice.localModelId)
                ?: throw IOException(app.getString(R.string.ime_ai_chat_error_no_model))
            LocalLlmEngine.generateWithImages(
                context = app,
                modelFile = modelFile,
                backend = settings.ai.localBackend,
                contextTokens = settings.ai.localContextTokens,
                system = system,
                user = user,
                images = listOf(jpeg),
            )
        } else {
            val decision = AiChatController.cloudAiDecision(app, settings, choice)
            if (decision != MeteredDecision.ALLOWED) {
                throw IOException(
                    app.getString(
                        if (decision == MeteredDecision.ASK) R.string.ime_ai_error_metered_ask else R.string.ime_ai_error_metered_blocked,
                    ),
                )
            }
            val chosen = settings.aiThrough(choice.rowKey) ?: throw IOException(app.getString(R.string.ime_ai_chat_error_no_model))
            val config = AiClient.config(chosen).copy(netSource = NetSource.AI_CHAT, reasoning = false)
            val image = AiClient.AiImage("image/jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            AiClient.completeStreaming(
                config = config,
                system = system,
                turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, user, images = listOf(image))),
                maxTokens = MAX_TOKENS,
                onPhase = {},
                onPartial = {},
            ).text
        }
        return AiThinking.parts(raw).output.trim()
    }
}
