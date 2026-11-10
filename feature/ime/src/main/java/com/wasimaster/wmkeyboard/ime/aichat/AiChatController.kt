package com.wasimaster.wmkeyboard.ime.aichat

import android.annotation.SuppressLint
import android.content.Context
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.core.aichat.AiChatConversation
import com.wasimaster.wmkeyboard.core.aichat.AiChatFile
import com.wasimaster.wmkeyboard.core.aichat.AiChatMessage
import com.wasimaster.wmkeyboard.core.aichat.AiChatStore
import com.wasimaster.wmkeyboard.core.localllm.ChatReplay
import com.wasimaster.wmkeyboard.core.reply.ReplyShots
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmCatalog
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmEngine
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.localllm.VisionUnsupportedException
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiProviders
import com.wasimaster.wmkeyboard.core.settings.aiRows
import com.wasimaster.wmkeyboard.core.settings.aiThrough
import com.wasimaster.wmkeyboard.core.settings.AiSettings
import com.wasimaster.wmkeyboard.core.settings.DataSaverStatus
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.core.net.NetworkWatcher
import com.wasimaster.wmkeyboard.core.settings.homes
import com.wasimaster.wmkeyboard.core.settings.OcrEngine
import com.wasimaster.wmkeyboard.core.tools.OnlineOcr
import com.wasimaster.wmkeyboard.core.tools.AiAnswerFormat
import com.wasimaster.wmkeyboard.core.tools.AiCalendarArgs
import com.wasimaster.wmkeyboard.core.tools.AiChatMode
import com.wasimaster.wmkeyboard.core.tools.AiChatModes
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.AiFollowUps
import com.wasimaster.wmkeyboard.core.tools.AiResearch
import com.wasimaster.wmkeyboard.core.tools.AiResearchWeb
import com.wasimaster.wmkeyboard.core.tools.AiToolCall
import com.wasimaster.wmkeyboard.core.tools.ToolApiKeys
import com.wasimaster.wmkeyboard.core.tools.WebFetchClient
import com.wasimaster.wmkeyboard.core.tools.AiPhase
import com.wasimaster.wmkeyboard.core.tools.AiPrompts
import com.wasimaster.wmkeyboard.core.tools.AiThinking
import com.wasimaster.wmkeyboard.core.tools.AiToolActivity
import com.wasimaster.wmkeyboard.core.tools.AiToolApproval
import com.wasimaster.wmkeyboard.core.tools.AiToolApprovalGate
import com.wasimaster.wmkeyboard.core.tools.AiToolApprover
import com.wasimaster.wmkeyboard.core.tools.AiToolLoop
import com.wasimaster.wmkeyboard.core.tools.AiToolProtocol
import com.wasimaster.wmkeyboard.core.tools.AiTools
import com.wasimaster.wmkeyboard.core.tools.AiToolResult
import com.wasimaster.wmkeyboard.core.tools.AiToolRunner
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import com.wasimaster.wmkeyboard.ime.aimemory.AiMemoryRecall
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the AI chat's generations. An object with its own scope, like
 * LocalLlmDownloadManager, so a running answer survives every navigation and
 * the screen only ever renders state: the saved transcript from [store] plus
 * the one live [run].
 *
 * Two surfaces draw it: the chat screen in the settings app and the chat mode
 * of the keyboard's AI panel (#280). It lives in this module because both can
 * see it here, and the two share one process, so they share this object, its
 * store and its live run. That is the whole of how a chat begun on the keyboard
 * carries on in the app: there is nothing to hand over.
 *
 * The same stale-sequence pattern as the keyboard's AI panel: an on-device
 * generation is a blocking native call that outlives any cancellation, so a
 * bumped [runSeq] — not a cancelled job — is what retires it, and late
 * callbacks are dropped by comparing their captured sequence.
 */
object AiChatController {

    /**
     * The model the chat screen sends to: a row of the AI list, or one local
     * model. [rowKey] is the row's `ProviderEntry.key`; [name] the nickname
     * that tells two rows of one service apart, blank for the service's own.
     */
    data class ModelChoice(
        val provider: AiProvider,
        val localModelId: String = "",
        val rowKey: String = "",
        val name: String = "",
    ) {
        /**
         * What the store remembers as the last model. A service's own row
         * keeps the provider's name, as before the list, so the last chat's
         * model is still found after an update.
         */
        val key: String
            get() = when {
                provider == AiProvider.ON_DEVICE -> AiChatStore.ON_DEVICE_KEY_PREFIX + localModelId
                rowKey.isEmpty() || rowKey == AiProviders.type(provider).id -> provider.name
                else -> rowKey
            }
    }

    /**
     * Everything that can answer right now, in the order the pickers draw it:
     * the downloaded models when the AI list has the on-device row, then the
     * list's other rows.
     */
    fun choices(context: Context, settings: KeyboardSettings): List<ModelChoice> {
        val rows = settings.aiRows()
        val local = if (rows.any { it.typeId == ProviderTypes.ON_DEVICE_AI.id }) {
            downloadedLocalModels(context).map { ModelChoice(AiProvider.ON_DEVICE, it, rowKey = ProviderTypes.ON_DEVICE_AI.id) }
        } else {
            emptyList()
        }
        return local + rows.mapNotNull { row ->
            val provider = AiProviders.of(row.typeId)?.takeIf { it != AiProvider.ON_DEVICE } ?: return@mapNotNull null
            ModelChoice(provider, rowKey = row.key, name = row.customName)
        }
    }

    /** The model to preselect: last used if still available, else the first. */
    fun initialChoice(store: AiChatStore, available: List<ModelChoice>): ModelChoice? {
        val lastKey = store.lastModelKey()
        return available.firstOrNull { it.key == lastKey } ?: available.firstOrNull()
    }

    /** A local model's name as a picker shows it. */
    fun localModelLabel(id: String): String =
        LocalLlmCatalog.byId(id)?.displayName
            ?: id.removePrefix(LocalLlmStore.CUSTOM_PREFIX).substringBeforeLast('.')

    /** One in-flight generation, as the screen renders it. */
    data class ChatRun(
        val conversationId: Long,
        val seq: Int,
        /** The user message being answered. */
        val userText: String,
        /** Raw accumulated response, think block included. */
        val partial: String = "",
        val phase: AiPhase = AiPhase.PREPARING,
        /** Model streams reasoning with no opening tag (Qwen3 style). */
        val implicitThink: Boolean = false,
        val choice: ModelChoice,
        /**
         * The tools called so far, oldest first; the newest may still be
         * [AiToolActivity.running]. Lands in the stored answer at the end.
         */
        val tools: List<AiToolActivity> = emptyList(),
        /** What the message being answered asked for: a research run draws its plan, for one. */
        val mode: AiChatMode = AiChatMode.CHAT,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _run = MutableStateFlow<ChatRun?>(null)
    val run: StateFlow<ChatRun?> = _run

    /** Bumped after every store mutation so the screen re-reads [store]. */
    private val _storeVersion = MutableStateFlow(0L)
    val storeVersion: StateFlow<Long> = _storeVersion

    private var storeInstance: AiChatStore? = null
    private var runSeq = 0
    private var job: Job? = null

    /**
     * The conversation whose last send Data Saver held to ask first (#826),
     * so its Retry is the yes: that one retry goes over mobile data, as the
     * keyboard's AI actions do.
     */
    @Volatile
    private var meteredAsked: Long? = null

    /**
     * What Data Saver's Cloud AI policy says about sending to [choice] now
     * (#826). An on-device model costs nothing to reach and is never held.
     * One fresh reading of the network, since neither the chat screen nor the
     * Settings assistant keeps a watcher running.
     */
    fun cloudAiDecision(context: Context, settings: KeyboardSettings, choice: ModelChoice): MeteredDecision {
        if (choice.provider == AiProvider.ON_DEVICE) return MeteredDecision.ALLOWED
        val network = NetworkWatcher(context.applicationContext).apply { refresh() }.state.value
        return DataSaverStatus(active = settings.dataSaver.appliesTo(network), settings = settings.dataSaver)
            .decide(MeteredFeature.CLOUD_AI)
    }

    /** A send Data Saver held; its message is what the chat shows for it. */
    private class HeldByDataSaver(message: String) : java.io.IOException(message)

    // The one live on-device session and the inputs it was built for. A new
    // conversation, model or backend closes it and opens another; the session
    // itself survives engine eviction by replaying its transcript.
    // The session's context is the application one — chatSession() rebinds —
    // so this static hold keeps no Activity alive.
    @SuppressLint("StaticFieldLeak")
    private var session: LocalLlmEngine.ChatSession? = null
    private var sessionKey: String? = null

    /** Takes turns building [session]; never held by anything on the main thread. */
    private val sessionBuild = Any()

    @Synchronized
    fun store(context: Context): AiChatStore = storeInstance ?: AiChatStore(
        File(context.applicationContext.filesDir, AiChatStore.FILE_PATH),
    ).also { storeInstance = it }

    /**
     * Points the store at the user's "keep conversations" answer.
     *
     * Called from the screens that open the store rather than read inside it:
     * `:core:content` knows nothing about settings, and the value is a flow
     * while `store` is synchronous. The flag is only consulted when a chat is
     * saved, which happens on send — long after this has landed.
     */
    suspend fun applyPersistSetting(context: Context, keep: Boolean) {
        val target = store(context)
        if (target.persist == keep) return
        target.persist = keep
        // Switching it off has to take what is already on disk with it.
        if (!keep) withContext(Dispatchers.IO) { target.save() }
    }

    /** Public form of [touchStore], for settings screens that mutate the store. */
    fun bumpStoreVersion() {
        _storeVersion.value++
    }

    private fun touchStore() {
        _storeVersion.value++
    }

    val isGenerating: Boolean get() = _run.value != null

    /** Ids of local models that are on disk right now, catalog order. */
    fun downloadedLocalModels(context: Context): List<String> {
        if (!BuildConfig.ENABLE_LOCAL_LLM) return emptyList()
        val filesDir = context.applicationContext.filesDir
        val catalog = LocalLlmCatalog.models
            .filter { LocalLlmStore.isDownloaded(filesDir, it) }
            .map { it.id }
        val custom = LocalLlmStore.customModels(filesDir)
            .map { LocalLlmStore.CUSTOM_PREFIX + it.name }
        return catalog + custom
    }

    /**
     * Sends one user message in [conversationId] and streams the answer.
     * No-op while a generation is already running — the screen disables Send.
     */
    fun send(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        text: String,
        /** Text quoted along with the message: a selection, or a field's text. */
        attachment: String = "",
        /** Pictures sent with it (#698), by the names [AiChatImages.import] gave them. */
        images: List<String> = emptyList(),
        /** Sent from an incognito field: the AI memory is neither read nor changed. */
        incognito: Boolean = false,
        /** Files sent with it (#882), as [AiChatFiles.import] read or copied them. */
        files: List<AiChatFile> = emptyList(),
        /** What to do with the message beyond answering it: research it, sum it up, compare. */
        mode: AiChatMode = AiChatMode.CHAT,
        /** The shape the answer is asked to take; Auto leaves it to the mode. */
        format: AiAnswerFormat = AiAnswerFormat.AUTO,
    ) {
        if (isGenerating) return
        val appContext = context.applicationContext
        val store = store(appContext)
        val user = text.trim()
        if (user.isEmpty() && images.isEmpty() && files.isEmpty()) return

        val now = System.currentTimeMillis()
        val message = AiChatMessage(
            role = AiChatMessage.ROLE_USER,
            content = user,
            timestamp = now,
            attachment = attachment.trim(),
            images = images.take(AiChatStore.MAX_IMAGES),
            files = files.take(AiChatStore.MAX_FILES),
            mode = if (mode == AiChatMode.CHAT) "" else mode.id,
            format = if (format == AiAnswerFormat.AUTO) "" else format.id,
        )
        val updated = store.appendMessage(conversationId, message) ?: return
        store.setLastModelKey(choice.key)
        store.save()
        touchStore()
        launchGeneration(
            appContext, settings, conversationId, choice, promptAt(updated, updated.messages.lastIndex),
            incognito = incognito,
        )
    }

    /**
     * What the model reads for the message at [index]: its text, and its
     * pictures numbered across the whole conversation, so "picture 3" means
     * the same picture to every turn and every tool.
     */
    private fun promptAt(conversation: AiChatConversation, index: Int): String =
        conversation.messages[index].promptText(conversation.firstImageOf(index))

    /** Clears out the pictures and files nothing refers to any more. */
    private fun pruneImages(context: Context, store: AiChatStore) {
        runCatching { AiChatImages.prune(context, store.imageNames()) }
        runCatching { AiChatFiles.prune(context, store.fileNames()) }
    }

    /**
     * Reruns the user message whose answer failed. The failed bubble makes
     * way — the message it answers is already in the transcript, so unlike
     * [send] nothing is appended before generating.
     */
    fun retry(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        incognito: Boolean = false,
    ) {
        if (isGenerating) return
        val appContext = context.applicationContext
        val store = store(appContext)
        store.dropLastFailedMessage(conversationId)
        val conversation = store.get(conversationId) ?: return
        val lastUser = conversation.messages.indexOfLast { it.role == AiChatMessage.ROLE_USER }
        if (lastUser < 0) return
        store.save()
        touchStore()
        launchGeneration(
            appContext, settings, conversationId, choice, promptAt(conversation, lastUser),
            granted = meteredAsked == conversationId,
            incognito = incognito,
        )
    }

    /**
     * Writes the newest answer again. The old answer leaves the transcript
     * first, and the on-device session with it: its native cache still holds
     * the answer that is going, so the next send builds a session from the
     * transcript as it now stands.
     */
    fun regenerate(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        incognito: Boolean = false,
    ) {
        if (isGenerating) return
        val appContext = context.applicationContext
        val store = store(appContext)
        val conversation = store.get(conversationId) ?: return
        val messages = conversation.messages
        val lastUser = messages.indexOfLast { it.role == AiChatMessage.ROLE_USER }
        // Nothing after the question means there is no answer to write again.
        if (lastUser < 0 || lastUser == messages.lastIndex) return
        closeSessionFor(conversationId)
        store.dropFrom(conversationId, lastUser + 1)
        store.setLastModelKey(choice.key)
        store.save()
        touchStore()
        launchGeneration(appContext, settings, conversationId, choice, promptAt(conversation, lastUser), incognito = incognito)
    }

    /**
     * Takes the user's newest message back out of the transcript, with whatever
     * answered it, and returns it for the composer: edit, then send again.
     * Null when there is nothing to take back or an answer is still forming.
     */
    fun editLast(context: Context, conversationId: Long): AiChatMessage? {
        if (isGenerating) return null
        val store = store(context.applicationContext)
        val messages = store.get(conversationId)?.messages ?: return null
        val lastUser = messages.indexOfLast { it.role == AiChatMessage.ROLE_USER }
        if (lastUser < 0) return null
        closeSessionFor(conversationId)
        store.dropFrom(conversationId, lastUser)
        store.save()
        touchStore()
        // The pictures go back to the composer with the words, so they are
        // still wanted; the grace in [AiChatImages.prune] keeps them.
        return messages[lastUser]
    }

    fun deleteConversation(context: Context, conversationId: Long) {
        val store = store(context.applicationContext)
        closeSessionFor(conversationId)
        synchronized(memoryTails) { memoryTails.remove(conversationId) }
        store.delete(conversationId)
        store.save()
        pruneImages(context.applicationContext, store)
        touchStore()
    }

    /** Deletes several conversations at once (#882): the list's selection. */
    fun deleteConversations(context: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val store = store(context.applicationContext)
        for (id in ids) {
            closeSessionFor(id)
            synchronized(memoryTails) { memoryTails.remove(id) }
            store.delete(id)
        }
        store.save()
        pruneImages(context.applicationContext, store)
        touchStore()
    }

    /** The user's own name for a conversation (#882); false when it is gone or [title] is blank. */
    fun renameConversation(context: Context, conversationId: Long, title: String): Boolean {
        val store = store(context.applicationContext)
        if (!store.rename(conversationId, title, byUser = true)) return false
        store.save()
        touchStore()
        return true
    }

    private fun launchGeneration(
        appContext: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        user: String,
        /** The user said yes to Data Saver's question; see [meteredAsked]. */
        granted: Boolean = false,
        incognito: Boolean = false,
    ) {
        // A private chat (begun in incognito) or a send from an incognito
        // field neither reads nor changes the AI memory.
        val privateChat = incognito || store(appContext).isEphemeral(conversationId)
        val asked = store(appContext).get(conversationId)?.messages
            ?.lastOrNull { it.role == AiChatMessage.ROLE_USER }
        val mode = asked?.chatMode ?: AiChatMode.CHAT
        // A research run and a summary of links go to the web as well as the
        // model, so they need both of Data Saver's answers.
        val links = if (mode == AiChatMode.SUMMARIZE) AiChatModes.links(asked?.content + "\n" + asked?.attachment) else emptyList()
        val usesWeb = mode == AiChatMode.RESEARCH || links.isNotEmpty()
        // One gate for every way in, the keyboard's chat and the app's (#826).
        val decision = when {
            granted -> MeteredDecision.ALLOWED
            usesWeb -> stricter(cloudAiDecision(appContext, settings, choice), webDecision(appContext, settings))
            else -> cloudAiDecision(appContext, settings, choice)
        }
        meteredAsked = null
        val seq = ++runSeq
        val implicitThink =
            choice.provider == AiProvider.ON_DEVICE && isReasoningModel(choice.localModelId)
        _run.value = ChatRun(
            conversationId = conversationId,
            seq = seq,
            userText = user,
            implicitThink = implicitThink,
            choice = choice,
            mode = mode,
        )

        job = scope.launch {
            val result = if (decision != MeteredDecision.ALLOWED) {
                if (decision == MeteredDecision.ASK) meteredAsked = conversationId
                Result.failure(
                    HeldByDataSaver(
                        appContext.getString(
                            if (decision == MeteredDecision.ASK) {
                                R.string.ime_ai_error_metered_ask
                            } else {
                                R.string.ime_ai_error_metered_blocked
                            },
                        ),
                    ),
                )
            } else runCatching {
                // A summary reads its links first, so every model gets the
                // pages, the ones with no tools at all included.
                val pages = if (links.isEmpty()) "" else readLinks(links, seq, choice.provider == AiProvider.ON_DEVICE)
                when {
                    mode == AiChatMode.RESEARCH -> runResearch(appContext, settings, conversationId, choice, seq)
                    choice.provider == AiProvider.ON_DEVICE ->
                        runOnDevice(appContext, settings, conversationId, choice, joined(user, pages), seq, privateChat)
                    else -> runRemote(appContext, settings, conversationId, choice, user, seq, privateChat, pages)
                }
            }
            finishRun(appContext, conversationId, choice, user, seq, result)
            if (result.isSuccess && seq == runSeq) autoTitle(appContext, settings, conversationId, choice)
        }
    }

    /**
     * Names a chat from its first question and answer (#882), with the model
     * that answered, in the language the user asked in: what "Auto-rename
     * chats" does. Once only, after the first answer, and never over a name
     * the user gave. A failure leaves the first line of the question, which
     * the store titled the chat with already. Never held for Data Saver's
     * question: a title is not worth asking about, so a send it would hold is
     * simply not made.
     */
    private fun autoTitle(context: Context, settings: KeyboardSettings, conversationId: Long, choice: ModelChoice) {
        if (!settings.ai.chatAutoRename) return
        val conversation = store(context).get(conversationId) ?: return
        if (conversation.titleByUser) return
        val kept = conversation.messages.filter { !it.failed }
        if (kept.size != 2 || kept[0].role != AiChatMessage.ROLE_USER || kept[1].role != AiChatMessage.ROLE_ASSISTANT) return
        if (kept[1].content.isBlank()) return
        if (cloudAiDecision(context, settings, choice) != MeteredDecision.ALLOWED) return
        val exchange = "User:\n" + kept[0].content.take(TITLE_INPUT_CHARS) +
            "\n\nAssistant:\n" + kept[1].content.take(TITLE_INPUT_CHARS)
        scope.launch {
            val raw = runCatching {
                if (choice.provider == AiProvider.ON_DEVICE) {
                    // One model on the phone: a message sent meanwhile goes
                    // first, and the title is not worth making it wait.
                    if (isGenerating) return@runCatching ""
                    val modelFile = LocalLlmStore.selectedModelFile(context.filesDir, choice.localModelId)
                        ?: return@runCatching ""
                    LocalLlmEngine.generate(
                        context, modelFile, settings.ai.localBackend, settings.ai.localContextTokens,
                        AiPrompts.chatTitlePrompt(), exchange,
                    )
                } else {
                    val chosen = settings.aiThrough(choice.rowKey) ?: settings.ai.copy(provider = choice.provider)
                    val config = AiClient.config(chosen).copy(netSource = NetSource.AI_CHAT, reasoning = false)
                    AiClient.completeStreaming(
                        config, AiPrompts.chatTitlePrompt(), exchange, TITLE_MAX_TOKENS,
                        onPhase = {}, onPartial = {},
                    ).text
                }
            }.getOrNull() ?: return@launch
            val title = cleanTitle(raw)
            if (title.isEmpty()) return@launch
            val store = store(context)
            // The user may have named it while the title was being written.
            if (store.get(conversationId)?.titleByUser != false) return@launch
            if (store.rename(conversationId, title, byUser = false)) {
                store.save()
                touchStore()
            }
        }
    }

    /** A model's title as a chat's name: no reasoning, no quotes, one line, no full stop. */
    internal fun cleanTitle(raw: String): String {
        val line = AiThinking.parts(raw).output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return line
            .removePrefix("Title:").removePrefix("title:").trim()
            .trim('"', '\'', '“', '”', '«', '»', '*', '#', '`', ' ')
            .trimEnd('.', '。', '।')
            .trim()
            .take(AiChatStore.MAX_RENAME)
    }

    /**
     * Declines a `rename_chat` question still showing (#882): the keyboard's
     * chat calls this as the keyboard closes, since a name nobody saw
     * accepted is not one to keep. Other questions are left waiting.
     */
    fun declineRenameAsk() {
        val asking = _run.value?.tools?.lastOrNull { it.awaitingApproval } ?: return
        if (asking.tool == AiTools.RENAME_CHAT && !asking.isMcp) toolGate.answer(AiToolApproval.DENY)
    }

    /**
     * Retires the current generation. The remote stream drops its socket via
     * `isActive`; an on-device one stops at its next step (both runtimes),
     * and [finishRun] mirrors what it wrote into the session so a later
     * replay matches what the model remembers. Either way the transcript
     * keeps the partial, marked stopped.
     */
    fun stop(context: Context) {
        val current = _run.value ?: return
        runSeq++
        if (current.choice.provider == AiProvider.ON_DEVICE) LocalLlmEngine.stop()
        val store = store(context.applicationContext)
        val parts = AiThinking.parts(current.partial, current.implicitThink)
        store.appendMessage(
            current.conversationId,
            AiChatMessage(
                role = AiChatMessage.ROLE_ASSISTANT,
                content = AiFollowUps.split(parts.output).first.trim(),
                reasoning = parts.reasoning,
                timestamp = System.currentTimeMillis(),
                provider = current.choice.provider.name,
                model = modelName(current.choice),
                stopped = true,
                toolUses = AiToolActivity.settle(
                    current.tools,
                    context.getString(R.string.ime_ai_chat_stopped),
                ),
            ),
        )
        store.save()
        touchStore()
        _run.value = null
    }

    /** Called when the chat screen for [conversationId] goes away for good. */
    fun closeSessionFor(conversationId: Long) {
        synchronized(this) {
            if (sessionKey?.startsWith("$conversationId|") == true) {
                session?.close()
                session = null
                sessionKey = null
            }
        }
    }

    private fun runOnDevice(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        user: String,
        seq: Int,
        privateChat: Boolean,
    ): String {
        val modelFile = LocalLlmStore.selectedModelFile(context.filesDir, choice.localModelId)
            ?: throw java.io.IOException(
                context.getString(R.string.ime_ai_chat_error_no_model),
            )
        val memory = AiMemoryRecall.access(context, settings, onDevice = true, incognito = privateChat, conversationId = conversationId)
        val runner = AiToolRunner(
            context, settings, toolApprover(seq), toolHost(context, settings, conversationId, choice), memory,
        )
        val tools = runner.tools()
        // A local model has no function calling, so the sentinel protocol has
        // to be part of the session's own system prompt — the session is built
        // once and keeps it, which is why it is woven in here rather than per
        // round. The one cost: the last round is still told about tools, so a
        // model that will not stop calling them answers with nothing. The
        // round limit is what keeps that rare.
        val memoryPart = memoryTail(
            context, settings, conversationId,
            onDevice = true, privateChat = privateChat, user = user, withTools = memory != null,
        )
        val sessionSystem = AiPrompts.chatPrompt() + memoryPart + AiToolProtocol.instructions(tools)
        val chat = obtainSession(context, settings.ai, conversationId, choice, modelFile, sessionSystem)
        // A model that can see (a vision GGUF with its projector) is sent the
        // newest message's pictures with it, once, on the first round; the
        // older ones it reads through the tools, like any on-device model.
        // One that turns out not to see gets the words alone.
        var picturesPending = if (LocalLlmCatalog.canSee(choice.localModelId) != false) {
            newestPictureBytes(context, conversationId)
        } else {
            emptyList()
        }
        var lastPartialAt = 0L
        val noThink = !settings.ai.chatReasoning && isReasoningModel(choice.localModelId)
        return AiToolLoop.run(
            // The round below ignores this: the session was built with its own
            // prompt above and keeps it. Passing the bare one here is what
            // stops the loop appending the protocol to it a second time.
            system = AiPrompts.chatPrompt(),
            turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, user)),
            tools = tools,
            native = false,
            executor = runner::run,
            maxRounds = settings.ai.toolMaxRounds,
            onPhase = { phase -> postPhase(seq, phase) },
            onPartial = { partial ->
                val now = System.currentTimeMillis()
                if (seq == runSeq && now - lastPartialAt >= AI_PARTIAL_INTERVAL_MS) {
                    lastPartialAt = now
                    postPartial(seq, partial)
                }
            },
            onToolCall = { call -> postToolStarted(seq, runner.activityFor(call)) },
            onToolResult = { result -> postToolResult(seq, result) },
            isActive = { seq == runSeq },
        ) { _, roundTurns, _, roundPartial ->
            // The session remembers every earlier round itself, so only the
            // newest message is sent: the user's on round one, the tool
            // results on the rounds after it. With the Reasoning switch off a
            // Qwen-style model is asked not to think, by the soft switch its
            // chat template reads off the end of the user's turn (#882).
            val last = roundTurns.last()
            val text = if (noThink && last.role == AiClient.ChatRole.USER) last.text + " /no_think" else last.text
            val pictures = picturesPending
            picturesPending = emptyList()
            val answer = if (pictures.isEmpty()) {
                chat.sendMessage(text, roundPartial)
            } else {
                try {
                    chat.sendMessage(text, pictures, roundPartial)
                } catch (_: VisionUnsupportedException) {
                    chat.sendMessage(text, roundPartial)
                }
            }
            AiClient.Completion(answer)
        }.text
    }

    /** The newest user message's pictures as their files' bytes; one that is gone is left out. */
    private fun newestPictureBytes(context: Context, conversationId: Long): List<ByteArray> {
        val newest = store(context).get(conversationId)?.messages
            ?.lastOrNull { it.role == AiChatMessage.ROLE_USER && !it.failed } ?: return emptyList()
        return newest.images.mapNotNull { name ->
            AiChatImages.file(context, name)?.let { runCatching { it.readBytes() }.getOrNull() }
        }
    }

    /**
     * The live session for this conversation and model, replacing one built
     * for anything else. A replaced or resumed session is seeded with the
     * saved transcript so the model keeps the context.
     */
    @Suppress("UnusedParameter")
    private fun obtainSession(
        context: Context,
        settings: AiSettings,
        conversationId: Long,
        choice: ModelChoice,
        modelFile: File,
        system: String,
    ): LocalLlmEngine.ChatSession = synchronized(sessionBuild) {
        // The system prompt is part of the key: turning a tool on changes it,
        // and a session built before that would never hear about the tool.
        val key = "$conversationId|${modelFile.path}|${settings.localBackend}|" +
            "${settings.localContextTokens}|${system.hashCode()}"
        synchronized(this) {
            session?.takeIf { sessionKey == key }?.let { return it }
            session?.close()
            session = null
            sessionKey = null
        }
        // Built and seeded outside the controller's own lock: seeding waits
        // for the runtime while a generation (even a stopped one) still holds
        // it, and the chat header reads the store under that lock on the main
        // thread, which was an "input dispatching timed out" ANR.
        val fresh = LocalLlmEngine.chatSession(
            context,
            modelFile,
            settings.localBackend,
            settings.localContextTokens,
            system,
        )
        fresh.seed(transcriptOf(context, conversationId))
        synchronized(this) {
            session = fresh
            sessionKey = key
        }
        fresh
    }

    /**
     * The saved conversation as replay turns: failed turns dropped, and the
     * trailing user message too — that is the message about to be *sent*, so
     * seeding it as well would put it in front of the model twice.
     */
    private fun transcriptOf(context: Context, conversationId: Long): List<ChatReplay.Turn> {
        val conversation = store(context).get(conversationId) ?: return emptyList()
        val messages = conversation.messages.withIndex().filter { !it.value.failed }
        val settled = if (messages.lastOrNull()?.value?.role == AiChatMessage.ROLE_USER) {
            messages.dropLast(1)
        } else {
            messages
        }
        return settled.map { (index, message) ->
            ChatReplay.Turn(fromUser = message.role == AiChatMessage.ROLE_USER, text = promptAt(conversation, index))
        }
    }

    @Suppress("UnusedParameter")
    private fun runRemote(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        user: String,
        seq: Int,
        privateChat: Boolean,
        /** Pages a summary read, added under the newest message for this answer only. */
        pages: String = "",
    ): String {
        // The picker's row stands in for the one in use, so chatting with a
        // different model never rewrites the keyboard's own selection.
        val chosen = settings.aiThrough(choice.rowKey) ?: settings.ai.copy(provider = choice.provider)
        // One session per conversation, stable across its messages, so a
        // gateway that keys its prompt cache on a {session} header (#648)
        // sees one conversation rather than a new one for every reply.
        val config = AiClient.config(chosen).copy(
            netSource = NetSource.AI_CHAT,
            sessionId = UUID.nameUUIDFromBytes("wmkb-chat-$conversationId".toByteArray()).toString(),
            // The message box's Reasoning switch (#882); a provider with no
            // field for it is sent nothing.
            reasoning = settings.ai.chatReasoning,
        )
        val conversation = store(context).get(conversationId)
        val kept = conversation?.messages.orEmpty().withIndex().filter { !it.value.failed }
        // Pictures travel with the newest question only, and only to a
        // provider with somewhere to put them (#698). Older ones stay marks:
        // sending every picture again with every turn would cost the user
        // for pictures the model has already answered about.
        val newestUser = kept.lastOrNull { it.value.role == AiChatMessage.ROLE_USER }?.index
        val sendsPictures = AiClient.supportsImages(choice.provider)
        val turns = kept.map { (index, message) ->
            AiClient.ChatTurn(
                if (message.role == AiChatMessage.ROLE_USER) {
                    AiClient.ChatRole.USER
                } else {
                    AiClient.ChatRole.ASSISTANT
                },
                if (index == newestUser) {
                    joined(promptAt(requireNotNull(conversation), index), pages)
                } else {
                    promptAt(requireNotNull(conversation), index)
                },
                images = if (index == newestUser) {
                    (if (sendsPictures) pictures(context, message.images) else emptyList()) +
                        media(context, choice.provider, message.files)
                } else {
                    emptyList()
                },
            )
        }
        var lastPartialAt = 0L
        // A cloud model gets memory only once the user has allowed it.
        val memory = AiMemoryRecall.access(context, settings, onDevice = false, incognito = privateChat, conversationId = conversationId)
        val runner = AiToolRunner(
            context, settings, toolApprover(seq), toolHost(context, settings, conversationId, choice), memory,
        )
        val memoryPart = memoryTail(
            context, settings, conversationId,
            onDevice = false, privateChat = privateChat, user = user, withTools = memory != null,
        )
        val completion = AiClient.completeWithTools(
            config = config,
            system = AiPrompts.chatPrompt() + memoryPart + if (settings.ai.chatFollowUps) AiFollowUps.RULE else "",
            turns = turns,
            maxTokens = AiClient.effectiveMaxTokens(chosen),
            tools = runner.tools(),
            executor = runner::run,
            maxRounds = settings.ai.toolMaxRounds,
            onPhase = { phase -> postPhase(seq, phase) },
            onPartial = { partial ->
                val now = System.currentTimeMillis()
                if (seq == runSeq && now - lastPartialAt >= AI_PARTIAL_INTERVAL_MS) {
                    lastPartialAt = now
                    postPartial(seq, partial)
                }
            },
            onToolCall = { call -> postToolStarted(seq, runner.activityFor(call)) },
            onToolResult = { result -> postToolResult(seq, result) },
            isActive = { seq == runSeq },
        )
        return completion.text
    }

    /**
     * The memories one conversation is handed, worked out on its first
     * message and kept for the rest of it, by conversation; see [memoryTail].
     */
    private val memoryTails = HashMap<Long, Pair<String, String>>()

    /**
     * What a chat's system prompt ends with about the AI memory: the facts
     * picked for it, and the rules for saving when it may save.
     *
     * Picked once per conversation, on the message that first asks, and kept:
     * an on-device session is keyed by its system prompt, so a list that
     * moved with every message would throw the session's cache away every
     * turn, and a service's prompt cache with it. A fact saved during the
     * conversation is in the conversation already. Editing the memory on its
     * settings screen drops every kept list ([forgetMemoryTails]).
     */
    private fun memoryTail(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        onDevice: Boolean,
        privateChat: Boolean,
        user: String,
        withTools: Boolean,
    ): String {
        if (!AiMemoryRecall.allowed(settings, onDevice, privateChat)) return ""
        val shape = "$onDevice|$withTools"
        synchronized(memoryTails) {
            memoryTails[conversationId]?.takeIf { it.first == shape }?.let { return it.second }
        }
        val recall = AiMemoryRecall.recall(context, settings, onDevice, privateChat, user)
        val tail = AiMemoryRecall.promptTail(recall, forChat = true, withTools = withTools)
        synchronized(memoryTails) { memoryTails[conversationId] = shape to tail }
        return tail
    }

    /**
     * Drops the memory lists kept for open conversations, so each one's next
     * message picks again: called when the user changed the memory on its
     * settings screen.
     */
    fun forgetMemoryTails() {
        synchronized(memoryTails) { memoryTails.clear() }
    }

    /**
     * Takes back what the `remember` call at [useIndex] of the message at
     * [messageIndex] saved: deletes a memory it added, or puts back the
     * wording of one it changed. The line under the answer then says it was
     * taken back. Nothing happens when the memory has since been deleted or
     * the store cannot be read.
     */
    fun undoMemory(context: Context, conversationId: Long, messageIndex: Int, useIndex: Int) {
        val appContext = context.applicationContext
        val store = store(appContext)
        val message = store.get(conversationId)?.messages?.getOrNull(messageIndex) ?: return
        val use = message.toolUses.getOrNull(useIndex) ?: return
        val note = use.memory?.takeIf { !it.undone } ?: return
        val memories = AiMemoryRecall.store(appContext) ?: return
        if (note.added) {
            memories.forget(note.id)
        } else {
            memories.get(note.id)?.let { memories.restore(it.copy(text = note.before, category = note.beforeCategory)) }
        }
        memories.save()
        val uses = message.toolUses.toMutableList().also { it[useIndex] = use.copy(memory = note.copy(undone = true)) }
        store.replaceMessage(conversationId, messageIndex, message.copy(toolUses = uses))
        store.save()
        touchStore()
    }

    /** [names] read back and encoded for a request; one that is gone is left out. */
    private fun pictures(context: Context, names: List<String>): List<AiClient.AiImage> = names.mapNotNull { name ->
        AiChatImages.file(context, name)?.let { ReplyShots.encode(it) }?.let { AiClient.AiImage(it.mimeType, it.base64) }
    }

    /**
     * The recordings, videos and PDFs of the newest message, for the one
     * service whose request takes any file inline (#882): Gemini. Every other
     * model reads the mark [AiChatFile.promptText] leaves in the message.
     */
    private fun media(context: Context, provider: AiProvider, files: List<AiChatFile>): List<AiClient.AiImage> {
        if (provider != AiProvider.GEMINI) return emptyList()
        return files.filter { !it.isText }.mapNotNull { file ->
            val bytes = AiChatFiles.file(context, file.stored)?.let { runCatching { it.readBytes() }.getOrNull() }
                ?: return@mapNotNull null
            AiClient.AiImage(file.mime, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
    }

    /** Why Deep research cannot run with [choice], as a string resource; null when it can. */
    fun researchBlock(settings: KeyboardSettings, choice: ModelChoice?): Int? = when {
        choice == null -> null
        // The research loop asks for plans, notes and a long report, all of
        // which want a tool-calling model with a big context: a model on the
        // phone has neither, and Brave's Answers takes one message only.
        !AiClient.supportsNativeTools(choice.provider) -> R.string.ime_ai_research_needs_model
        ToolApiKeys.searchBackend(settings) == null -> R.string.ime_ai_research_needs_search
        else -> null
    }

    /** A Deep research run that cannot start; its message is what the chat shows. */
    private class ResearchRefusedException(val messageRes: Int) : java.io.IOException()

    /** What Data Saver says about the web searches and pages a run would fetch now. */
    private fun webDecision(context: Context, settings: KeyboardSettings): MeteredDecision {
        val network = NetworkWatcher(context.applicationContext).apply { refresh() }.state.value
        return DataSaverStatus(active = settings.dataSaver.appliesTo(network), settings = settings.dataSaver)
            .decide(MeteredFeature.WEB_SEARCH)
    }

    /** The stricter of two answers: blocked over asked over allowed. */
    private fun stricter(a: MeteredDecision, b: MeteredDecision): MeteredDecision = when {
        a == MeteredDecision.BLOCKED || b == MeteredDecision.BLOCKED -> MeteredDecision.BLOCKED
        a == MeteredDecision.ASK || b == MeteredDecision.ASK -> MeteredDecision.ASK
        else -> MeteredDecision.ALLOWED
    }

    private fun joined(text: String, more: String): String = if (more.isEmpty()) text else "$text\n\n$more"

    /**
     * A summary's links read as text, each a step on the chain like a page
     * the model fetched itself; one that cannot be read is left out, and the
     * model is told so by its absence. An on-device model gets less of each.
     */
    private fun readLinks(links: List<String>, seq: Int, onDevice: Boolean): String {
        val pages = ArrayList<Pair<String, String>>()
        for (url in links) {
            if (seq != runSeq) break
            val call = AiToolCall("", AiTools.WEB_FETCH, kotlinx.serialization.json.buildJsonObject {
                put("url", kotlinx.serialization.json.JsonPrimitive(url))
            }.toString())
            postToolStarted(seq, AiToolActivity.started(call))
            val page = runCatching { WebFetchClient.fetch(url, if (onDevice) ON_DEVICE_PAGE_CHARS else WebFetchClient.MAX_CHARS) }
            postToolResult(
                seq,
                page.fold(
                    onSuccess = { AiToolResult(call, it.text, sources = listOf(com.wasimaster.wmkeyboard.core.tools.AiToolSource(it.title.trim(), url))) },
                    onFailure = { AiToolResult(call, "", error = it.message.orEmpty().ifEmpty { "Could not read the page" }) },
                ),
            )
            page.getOrNull()?.takeIf { it.text.isNotBlank() }?.let { pages += url to it.text }
        }
        return AiChatModes.pagesBlock(pages)
    }

    /**
     * Deep research ([AiResearch]) for the newest question: its plan, each
     * search and each page read go onto the run's chain of steps as they
     * happen, the plan first and ticked as it goes, and the report streams
     * in as the answer. The same model writes every step.
     */
    private fun runResearch(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
        seq: Int,
    ): String {
        researchBlock(settings, choice)?.let { throw ResearchRefusedException(it) }
        val conversation = store(context).get(conversationId) ?: return ""
        val kept = conversation.messages.filter { !it.failed }
        val asked = kept.lastOrNull { it.role == AiChatMessage.ROLE_USER } ?: return ""
        // The question as written, its quoted text and files with it, but
        // without the format's words: the report has its own instructions.
        val question = buildString {
            val earlier = kept.dropLast(1).takeLast(RESEARCH_CONTEXT_TURNS)
            if (earlier.isNotEmpty()) {
                append("Earlier in this chat, for context:\n")
                earlier.forEach { append(if (it.role == AiChatMessage.ROLE_USER) "User: " else "Assistant: ").append(it.content.take(RESEARCH_CONTEXT_CHARS)).append('\n') }
                append("\nThe question to research:\n")
            }
            append(asked.copy(mode = "", format = "").promptText())
        }
        val chosen = settings.aiThrough(choice.rowKey) ?: settings.ai.copy(provider = choice.provider)
        val config = AiClient.config(chosen).copy(
            netSource = NetSource.AI_CHAT,
            sessionId = UUID.nameUUIDFromBytes("wmkb-chat-$conversationId".toByteArray()).toString(),
            reasoning = settings.ai.chatReasoning,
        )
        val maxTokens = AiClient.effectiveMaxTokens(chosen)
        var lastPartialAt = 0L
        val model = AiResearch.Model { system, user, stream, onPartial ->
            AiClient.completeStreaming(
                // The plan and the look-again are quick judgements; only the
                // report is worth the model's reasoning.
                if (stream) config else config.copy(reasoning = false),
                if (stream && settings.ai.chatFollowUps) system + AiFollowUps.RULE else system,
                user,
                maxTokens,
                onPhase = { phase -> if (stream) postPhase(seq, phase) },
                onPartial = onPartial,
                isActive = { seq == runSeq },
            ).text
        }
        val network = NetworkWatcher(context.applicationContext).apply { refresh() }.state.value
        val limits = AiResearch.limits(settings.ai.research.depth, dataSaver = settings.dataSaver.appliesTo(network))
        val approver = if (settings.ai.research.reviewPlan) toolApprover(seq) else null
        val listener = object : AiResearch.Listener {
            override fun started(step: AiToolActivity) = postToolStarted(seq, step)
            override fun finished(step: AiToolActivity) = postToolShown(seq, step)
            override fun planChanged(step: AiToolActivity) = postPlan(seq, step)
            override fun approve(step: AiToolActivity): Boolean =
                approver == null || approver.approve(step).let { it == AiToolApproval.ONCE || it == AiToolApproval.ALWAYS }
            override fun partial(text: String) {
                val now = System.currentTimeMillis()
                if (seq == runSeq && now - lastPartialAt >= AI_PARTIAL_INTERVAL_MS) {
                    lastPartialAt = now
                    postPartial(seq, text)
                }
            }
        }
        val format = AiChatModes.effectiveFormat(AiChatMode.RESEARCH, asked.answerFormat)
        return AiResearch(limits, model, AiResearchWeb(settings), listener) { seq == runSeq }
            .run(question, format, AiCalendarArgs.today())
    }

    /** The research plan, always the run's first step, with an item ticked or added. */
    private fun postPlan(seq: Int, plan: AiToolActivity) {
        _run.update { run ->
            if (run == null || run.seq != seq || seq != runSeq || run.tools.isEmpty()) run
            else run.copy(tools = listOf(plan) + run.tools.drop(1))
        }
    }


    private fun postPhase(seq: Int, phase: AiPhase) {
        if (seq != runSeq) return
        _run.value = _run.value?.takeIf { it.seq == seq }?.copy(phase = phase)
    }

    private fun postPartial(seq: Int, partial: String) {
        _run.value = _run.value?.takeIf { it.seq == seq && seq == runSeq }?.copy(partial = partial)
    }

    /** A tool call has begun: the chat says what it is doing until it ends. */
    private fun postToolStarted(seq: Int, started: AiToolActivity) {
        _run.update { run ->
            if (run == null || run.seq != seq || seq != runSeq) run
            else run.copy(tools = run.tools + started)
        }
    }

    /** The running call's line changed: it is asking to be allowed, or is done asking. */
    private fun postToolShown(seq: Int, shown: AiToolActivity) {
        _run.update { run ->
            if (run == null || run.seq != seq || seq != runSeq) run
            else run.copy(tools = AiToolActivity.replaceRunning(run.tools, shown))
        }
    }

    /**
     * Where a chat's MCP call waits while the chat asks the user to allow it
     * (#573). One for both chats, the keyboard's and the app's, since they
     * share this controller and its one run.
     */
    private val toolGate = AiToolApprovalGate()

    private fun toolApprover(seq: Int): AiToolApprover = toolGate.approver(
        isActive = { seq == runSeq },
        show = { ask -> postToolShown(seq, ask) },
        shown = { done -> postToolShown(seq, done) },
    )

    /**
     * What the keyboard module lends the chat's tools (#698), with the
     * conversation's pictures. A remote provider that takes pictures sees
     * them; an on-device chat reads them through the tools, though a vision
     * GGUF model is also sent the newest message's own ([runOnDevice]).
     */
    private fun toolHost(
        context: Context,
        settings: KeyboardSettings,
        conversationId: Long,
        choice: ModelChoice,
    ): KeyboardAiToolHost = KeyboardAiToolHost(
        context,
        hints = settings.enabledLanguages.map { it.id },
        kdeConnect = settings.kdeConnect,
        homePrefs = settings.homeAssistant,
        homes = settings.homes(),
        images = store(context).get(conversationId)?.images.orEmpty(),
        seesImages = choice.provider != AiProvider.ON_DEVICE && AiClient.supportsImages(choice.provider),
        scanner = settings.scanner,
        ocrReaders = if (settings.scanner.ocrEngine == OcrEngine.ONLINE) OnlineOcr.rows(settings) else emptyList(),
        semantic = settings.semanticSearch,
        // The user accepted it on the card, so it counts as their name. Only
        // with Auto-rename chats on, which the tool belongs to (#882).
        renamer = { title: String ->
            val store = store(context)
            if (store.rename(conversationId, title, byUser = true)) {
                store.save()
                touchStore()
                store.get(conversationId)?.title
            } else {
                null
            }
        }.takeIf { settings.ai.chatAutoRename },
    )

    /**
     * The user's answer to the call asking now. False when none is, which
     * the keyboard uses to tell its own panel's question from the chat's.
     */
    fun answerTool(choice: AiToolApproval): Boolean = toolGate.answer(choice)

    private fun postToolResult(seq: Int, result: AiToolResult) {
        _run.update { run ->
            if (run == null || run.seq != seq || seq != runSeq) run
            else run.copy(tools = AiToolActivity.record(run.tools, result))
        }
    }

    /**
     * Lands the finished (or failed) generation in the store — unless the run
     * was retired by Stop, in which case only the on-device session's replay
     * mirror still wants the full answer.
     */
    private fun finishRun(
        context: Context,
        conversationId: Long,
        choice: ModelChoice,
        user: String,
        seq: Int,
        result: Result<String>,
    ) {
        val raw = result.getOrNull()
        if (seq != runSeq) {
            // Retired by Stop (or a newer send). Keep the session's mirror
            // faithful to what the KV cache holds of the answer.
            if (raw != null && choice.provider == AiProvider.ON_DEVICE) {
                synchronized(this) { session }
                    ?.takeIf { sessionKey?.startsWith("$conversationId|") == true }
                    ?.recordStoppedTurn(user, raw)
            }
            return
        }
        val store = store(context)
        // Kept with the answer — or with the failure, since a run that died
        // after searching still searched.
        val toolUses = AiToolActivity.settle(
            _run.value?.takeIf { it.seq == seq }?.tools.orEmpty(),
            context.getString(R.string.ime_ai_chat_error_generic),
        )
        val message = if (raw != null) {
            // Every round's reasoning, kept apart for the folded step (#882).
            // A finished answer with no marks at all did not reason this
            // time, whatever the model's name says, so implicit is off here.
            val parts = AiThinking.parts(raw)
            // The suggested questions come out of the answer, to sit under it.
            val (said, followUps) = AiFollowUps.split(parts.output)
            val answer = said.trim().ifBlank { if (parts.reasoning.isEmpty()) raw.trim() else "" }
            AiChatMessage(
                role = AiChatMessage.ROLE_ASSISTANT,
                content = answer,
                reasoning = parts.reasoning,
                followUps = followUps,
                // Reasoning and no answer: cut off at the ceiling while it
                // thought. A failed turn, so it offers Retry rather than
                // standing there blank under its Reasoning step.
                error = if (answer.isEmpty()) context.getString(R.string.ime_ai_chat_error_only_reasoning) else "",
                timestamp = System.currentTimeMillis(),
                provider = choice.provider.name,
                model = modelName(choice),
                toolUses = toolUses,
            )
        } else {
            val failure = result.exceptionOrNull()
            AiChatMessage(
                role = AiChatMessage.ROLE_ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                provider = choice.provider.name,
                model = modelName(choice),
                error = errorText(context, failure),
                toolUses = toolUses,
            )
        }
        store.appendMessage(conversationId, message)
        store.save()
        touchStore()
        if (_run.value?.seq == seq) _run.value = null
    }

    private fun errorText(context: Context, t: Throwable?): String {
        val message = t?.message
        return when {
            t is ToolHttpException -> ToolHttp.friendlyMessage(context, t)
            t is ResearchRefusedException -> context.getString(t.messageRes)
            t is AiResearch.DeclinedException -> context.getString(R.string.ime_ai_research_declined)
            t is AiResearch.NothingFoundException -> context.getString(R.string.ime_ai_research_nothing_found) +
                t.lastError.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            !message.isNullOrBlank() -> message
            else -> context.getString(R.string.ime_ai_chat_error_generic)
        }
    }

    private fun modelName(choice: ModelChoice): String =
        if (choice.provider == AiProvider.ON_DEVICE) choice.localModelId else ""

    /**
     * Whether the message box's Reasoning switch does anything for [choice]
     * (#882): a cloud model whose request has a field for it, or an
     * on-device model that reasons, which is asked not to by `/no_think`.
     * Where it does nothing the switch is not shown.
     */
    fun reasoningSwitch(settings: KeyboardSettings, choice: ModelChoice?): Boolean = when {
        choice == null -> false
        choice.provider == AiProvider.ON_DEVICE -> isReasoningModel(choice.localModelId)
        else -> {
            val chosen = settings.aiThrough(choice.rowKey) ?: settings.ai.copy(provider = choice.provider)
            AiClient.controlsReasoning(chosen)
        }
    }

    /** Same sniff as the keyboard: catalog flag, else the model's name. */
    private fun isReasoningModel(modelId: String?): Boolean {
        if (modelId.isNullOrEmpty()) return false
        LocalLlmCatalog.byId(modelId)?.let { return it.reasoning }
        val name = modelId.removePrefix(LocalLlmStore.CUSTOM_PREFIX).lowercase()
        return "qwen3" in name || "deepseek" in name
    }

    private const val AI_PARTIAL_INTERVAL_MS = 120L

    /** Characters of each linked page a summary hands an on-device model, whose context is small. */
    private const val ON_DEVICE_PAGE_CHARS = 2_500

    /** Earlier messages a research question carries for context, and how much of each. */
    private const val RESEARCH_CONTEXT_TURNS = 4
    private const val RESEARCH_CONTEXT_CHARS = 600

    /** How much of the first question and answer a title is written from. */
    private const val TITLE_INPUT_CHARS = 1500

    /** A title is a few words; this is room for those and a reasoning model's preamble. */
    private const val TITLE_MAX_TOKENS = 256
}
