package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import com.wasimaster.wmkeyboard.core.settings.fiatRateProviders
import com.wasimaster.wmkeyboard.core.settings.cryptoRateProviders
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.settings.dictionaryRow
import android.content.Intent
import android.os.UserManager
import android.provider.CalendarContract
import com.wasimaster.wmkeyboard.core.clock.Alarm
import com.wasimaster.wmkeyboard.core.clock.AlarmMath
import com.wasimaster.wmkeyboard.core.clock.ClockActions
import com.wasimaster.wmkeyboard.core.clock.ClockEngine
import com.wasimaster.wmkeyboard.core.location.LocationAccess
import com.wasimaster.wmkeyboard.core.location.LocationFix
import com.wasimaster.wmkeyboard.core.location.LocationFixer
import com.wasimaster.wmkeyboard.core.location.LocationGrant
import com.wasimaster.wmkeyboard.core.location.ReverseGeocoder
import com.wasimaster.wmkeyboard.core.media.MediaControlManager
import com.wasimaster.wmkeyboard.core.media.MediaSnapshot
import com.wasimaster.wmkeyboard.core.notes.NoteStore
import com.wasimaster.wmkeyboard.core.aimemory.AiMemoryOutcome
import com.wasimaster.wmkeyboard.core.aimemory.AiMemoryText
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.PhotoSearchEngine
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.TranslateEngine
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.core.vocab.VocabIndexCache
import com.wasimaster.wmkeyboard.core.vocab.VocabLanguages
import com.wasimaster.wmkeyboard.tools.feature.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Runs the tools a model asked for (#470), and turns what happened — including
 * what went wrong — into text the model can read.
 *
 * Nothing here ever throws. A tool that fails is not an error in the run: the
 * model asked a question, the answer is "that did not work", and a model told
 * so can apologise, try a different query, or answer from what it knows. A
 * thrown exception would instead kill the whole generation and show the user a
 * network error for a page they never asked for.
 *
 * Both surfaces share this: the AI panel's actions and the AI chat.
 *
 * The tools of the user's MCP servers (#573) run here too, and so do the
 * keyboard's own tools beyond search and fetch (#698). Both carry a Run / Ask
 * / Off policy. A tool on "ask" goes through [approver] first; a surface that
 * passes none cannot ask, and such a call is declined rather than run unseen.
 * What only the keyboard module can do comes in through [host].
 */
class AiToolRunner(
    private val context: Context,
    private val settings: KeyboardSettings,
    private val approver: AiToolApprover? = null,
    private val host: AiToolHost? = null,
    /**
     * The AI memory this run may change, through `remember` and `forget`.
     * Null leaves both tools out: a run that may not change memory (an AI
     * action, an incognito chat, a cloud model the user has not allowed)
     * is never offered them. See [AiMemoryAccess].
     */
    private val memory: AiMemoryAccess? = null,
) {

    /** One MCP tool offered in this run, under the name the model sees. */
    private class McpOffer(
        val spec: AiToolSpec,
        val server: McpServerSpec,
        val entry: McpCatalogEntry,
        val tool: McpTool,
        val policy: McpToolPolicy,
    )

    /**
     * The MCP tools of this run, by the name the model sees. Worked out once:
     * [tools] and [run] must agree on every name, and the catalogue is read
     * from a file.
     */
    private val mcp: Map<String, McpOffer> by lazy { mcpOffers() }

    /**
     * The keyboard's own tools this run offers, by name. A tool turned off, or
     * one whose service is not set up, is not here, so a model that names it
     * anyway is told there is no such tool.
     */
    private val builtIns: Map<String, AiBuiltInTool> by lazy {
        AiTools.builtIns
            .filter { policyOf(it) != McpToolPolicy.OFF && available(it) }
            .associateBy { it.name }
    }

    /** Tools the user allowed for good during this run; later calls skip the question. */
    private val allowedNow = HashSet<String>()

    /**
     * The tools one run may offer, from the settings and from what is
     * actually set up. See [enabled].
     */
    fun tools(): List<AiToolSpec> = AiTools.enabled(
        search = settings.ai.toolWebSearch && ToolApiKeys.searchBackend(settings) != null,
        fetch = settings.ai.toolWebFetch,
    ) + builtIns.values.map { specOf(it) } + memoryTools() + renameTools() + mcp.values.map { it.spec }

    /** `rename_chat`, in a chat that lends one to rename (#882). */
    private fun renameTools(): List<AiToolSpec> =
        if (host?.canRenameChat == true) listOf(AiTools.renameChat) else emptyList()

    /** `remember` and `forget`, when this run may change the memory. */
    private fun memoryTools(): List<AiToolSpec> =
        if (memory == null) emptyList() else listOf(AiTools.remember, AiTools.forget)

    /**
     * [tool]'s spec as this run offers it: a dated tool's description ends
     * with today's date, since a model has no clock.
     */
    private fun specOf(tool: AiBuiltInTool): AiToolSpec =
        if (tool.name in AiTools.DATED) {
            tool.spec.copy(description = tool.spec.description + " " + AiCalendarArgs.today())
        } else {
            tool.spec
        }

    /**
     * How a call is shown while it runs. An MCP call is named by its tool's
     * title and its server, with its arguments in a short line; ours by what
     * [AiToolActivity.started] reads out of the call.
     */
    fun activityFor(call: AiToolCall): AiToolActivity {
        val offer = mcp[call.name] ?: return AiToolActivity.started(call)
        return AiToolActivity(
            tool = offer.tool.label,
            target = McpProtocol.preview(call.arguments),
            running = true,
            server = offer.server.label,
        )
    }

    private fun policyOf(tool: AiBuiltInTool): McpToolPolicy = AiTools.policyOf(tool, settings.ai.toolPolicies)

    /**
     * Whether what [tool] needs is there. A tool that could only ever answer
     * "not set up" still costs its description in every prompt, and a small
     * model handed one tends to keep trying it.
     */
    private fun available(tool: AiBuiltInTool): Boolean = when (tool.name) {
        AiTools.IMAGE_SEARCH -> ToolApiKeys.searchBackend(settings) != null
        // A build made without location has no permission to ask for.
        AiTools.CURRENT_LOCATION -> LocationAccess.declared(context)
        AiTools.OPEN_APP -> host?.canOpenApps == true
        AiTools.SEND_TO_PC -> host?.canSendToPc == true && settings.kdeConnect.enabled
        AiTools.HOME_ASSISTANT, AiTools.HOME_ASSISTANT_STATES -> host?.canControlHome == true
        AiTools.SEARCH_CLIPBOARD -> host?.canSearchByMeaning == true
        // Only with a picture in the chat, and the reader only for a model
        // that cannot look at the picture itself.
        AiTools.READ_IMAGE_TEXT -> host.let { it != null && it.imageCount > 0 && it.canReadImageText && !it.modelSeesImages }
        // The same, with a model that sees picked under "Describe pictures with".
        AiTools.DESCRIBE_IMAGE -> host.let { it != null && it.imageCount > 0 && it.canDescribeImage && !it.modelSeesImages }
        AiTools.REVERSE_IMAGE_SEARCH -> (host?.imageCount ?: 0) > 0
        else -> true
    }

    private fun mcpOffers(): Map<String, McpOffer> {
        val servers = settings.ai.mcpServers.filter { it.enabled }
        if (servers.isEmpty()) return emptyMap()
        val taken = HashSet<String>(AiTools.reservedNames)
        val out = LinkedHashMap<String, McpOffer>()
        for (server in servers) {
            // A server never connected, or connected at another address, has
            // nothing to offer until the user presses Connect.
            val entry = McpCatalogStore.entryFor(context, server) ?: continue
            for (tool in entry.tools) {
                val policy = server.policyFor(tool)
                if (policy == McpToolPolicy.OFF) continue
                val name = McpProtocol.exposedName(server.label, tool.name, taken)
                taken += name
                out[name] = McpOffer(McpProtocol.spec(name, server.label, tool), server, entry, tool, policy)
            }
        }
        return out
    }

    /**
     * Runs one call. Blocking; call on an IO dispatcher.
     *
     * The tools the model may call are decided by [AiTools.enabled] before the
     * request is built, so an unknown name here means the model invented one —
     * answered rather than ignored, because a model that gets silence repeats
     * itself until the round limit.
     */
    fun run(call: AiToolCall): AiToolResult {
        val offer = mcp[call.name]
        val builtIn = builtIns[call.name]
        val display = if (offer != null) activityFor(call) else null
        return runCatching {
            when {
                offer != null -> runMcp(call, offer, display)
                builtIn != null -> gate(call, builtIn.name, policyOf(builtIn), null) {
                    setAiToolPolicy(builtIn.name, McpToolPolicy.ALLOW)
                } ?: runBuiltIn(call)
                call.name == AiTools.WEB_SEARCH -> search(call)
                call.name == AiTools.WEB_FETCH -> fetch(call)
                // Never asked about first: the save shows in the chat with an
                // Undo, which is the check, and asking before every fact would
                // teach the user to press Allow without reading.
                call.name == AiTools.REMEMBER && memory != null -> remember(call, memory)
                call.name == AiTools.FORGET && memory != null -> forget(call, memory)
                call.name == AiTools.RENAME_CHAT && host?.canRenameChat == true -> renameChat(call, host)
                else -> refused(
                    call,
                    context.getString(R.string.ftools_ai_tool_unknown, call.name),
                    context.getString(R.string.ftools_ai_tool_reason_unknown),
                )
            }
        }.getOrElse { failure ->
            // Everything else becomes an answer the model can act on, but a
            // cancelled run is not a failed tool: swallowing it here would
            // keep a retired generation going round the loop.
            if (failure is CancellationException) throw failure
            val reason = McpClient.message(context, failure)
            refused(call, context.getString(R.string.ftools_ai_tool_failed, reason), reason, display)
        }
    }

    /**
     * Asks the user about [call] when its [policy] says to, and returns the
     * result to hand back instead of running it — or null when it may run.
     *
     * [key] names the tool for "allow always": for the rest of this run at
     * once, and through [remember] for every run after it.
     */
    private fun gate(
        call: AiToolCall,
        key: String,
        policy: McpToolPolicy,
        display: AiToolActivity?,
        remember: suspend SettingsRepository.() -> Unit,
    ): AiToolResult? {
        if (policy != McpToolPolicy.ASK || key in allowedNow) return null
        val ask = (display ?: activityFor(call)).copy(
            awaitingApproval = true,
            details = McpProtocol.pretty(call.arguments),
        )
        return when (approver?.approve(ask) ?: AiToolApproval.DENY) {
            AiToolApproval.ONCE -> null
            AiToolApproval.ALWAYS -> {
                allowedNow += key
                // Blocking is fine: this thread is the tool loop's, and it
                // waits for the call anyway.
                runCatching { runBlocking { SettingsRepository(context).remember() } }
                null
            }
            AiToolApproval.DENY -> refused(
                call,
                context.getString(R.string.ftools_mcp_declined),
                context.getString(R.string.ftools_mcp_reason_declined),
                display,
            )
            AiToolApproval.CANCELLED -> refused(
                call,
                context.getString(R.string.ftools_mcp_stopped),
                context.getString(R.string.ftools_mcp_reason_stopped),
                display,
            )
        }
    }

    /**
     * One MCP call: asked about first when its tool's policy says so, then
     * sent to its server, and its answer cut to what a prompt can hold.
     */
    private fun runMcp(call: AiToolCall, offer: McpOffer, display: AiToolActivity?): AiToolResult {
        val key = offer.server.id + "\u0000" + offer.tool.name
        gate(call, key, offer.policy, display) {
            setMcpToolPolicy(offer.server.id, offer.tool.name, McpToolPolicy.ALLOW)
        }?.let { return it }
        val headers = settings.ai.mcpHeaders[offer.server.id].orEmpty()
        val outcome = try {
            McpClient.call(
                offer.server, headers, offer.entry, offer.tool.name, call.arguments,
                auth = settings.ai.mcpAuth[offer.server.id],
                onAuth = { fresh ->
                    // A refreshed token is kept at once: the old refresh
                    // token may already be spent.
                    runCatching { runBlocking { SettingsRepository(context).setMcpAuth(offer.server.id, fresh) } }
                },
                input = inputHandler(call, offer),
            )
        } catch (deferred: McpDeferredInput) {
            // Not a failure: the server asked something only the user can
            // answer, and the model is told to ask it.
            return AiToolResult(call, deferred.question, display = display)
        }
        val content = buildString {
            append(outcome.text.ifBlank { context.getString(R.string.ftools_mcp_empty) })
            if (outcome.truncated) append("\n\n").append(context.getString(R.string.ftools_mcp_truncated))
            if (outcome.omitted.isNotEmpty()) {
                append("\n\n").append(context.getString(R.string.ftools_mcp_omitted, outcome.omitted.joinToString(", ")))
            }
        }
        if (outcome.isError) {
            return refused(
                call,
                context.getString(R.string.ftools_mcp_tool_error, content),
                context.getString(R.string.ftools_mcp_reason_tool_error),
                display,
            )
        }
        return AiToolResult(call, content, display = display)
    }

    /**
     * What this run answers when [offer]'s server asks for something in the
     * middle of [call] (MRTR, or an old server's request):
     *
     * - **Roots**: none. A phone shares no folders with a server.
     * - **A page to open** (URL elicitation): asked about on the same card as
     *   a call, then opened in the browser.
     * - **A yes or no** (a question with no fields): asked about on the card.
     * - **A form**: handed to the model, which asks the user in its answer
     *   and calls the tool again with what they said. The second call is
     *   asked about like any other, so the user sees what goes back.
     * - **A completion from the user's AI** (sampling): asked about on the
     *   card, showing what the server wrote, then run on the AI set up here.
     */
    private fun inputHandler(call: AiToolCall, offer: McpOffer) = McpInputHandler { request, canDefer ->
        when (request.method) {
            "roots/list" -> McpInputAnswer.Answer(McpProtocol.rootsResult())
            "elicitation/create" -> elicit(call, offer, McpProtocol.elicitation(request.params), request.key, canDefer)
            "sampling/createMessage" -> sample(offer, request.params)
            else -> McpInputAnswer.Refuse
        }
    }

    private fun askOnCard(offer: McpOffer, title: String, details: String): AiToolApproval =
        approver?.approve(
            AiToolActivity(
                tool = title,
                server = offer.server.label,
                running = true,
                awaitingApproval = true,
                details = details,
            ),
        ) ?: AiToolApproval.DENY

    private fun elicitAction(approval: AiToolApproval): String = when (approval) {
        AiToolApproval.ONCE, AiToolApproval.ALWAYS -> "accept"
        AiToolApproval.DENY -> "decline"
        AiToolApproval.CANCELLED -> "cancel"
    }

    private fun elicit(
        call: AiToolCall,
        offer: McpOffer,
        asked: McpProtocol.Elicitation,
        key: String,
        canDefer: Boolean,
    ): McpInputAnswer {
        val url = asked.url
        if (url != null) {
            val details = listOf(asked.message, url).filter { it.isNotBlank() }.joinToString("\n\n")
            val action = elicitAction(askOnCard(offer, context.getString(R.string.ftools_mcp_open_page_title), details))
            if (action == "accept") {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            return McpInputAnswer.Answer(McpProtocol.elicitResult(action))
        }
        if (asked.fields.isEmpty()) {
            val action = elicitAction(askOnCard(offer, context.getString(R.string.ftools_mcp_confirm_title), asked.message))
            return McpInputAnswer.Answer(McpProtocol.elicitResult(action, JsonObject(emptyMap())))
        }
        if (!canDefer) return McpInputAnswer.Answer(McpProtocol.elicitResult("decline"))
        val fields = asked.fields.joinToString("\n") { field ->
            buildString {
                append("- ").append(field.name)
                if (field.title.isNotBlank() && field.title != field.name) append(" (").append(field.title).append(')')
                append(": ").append(field.type)
                if (field.options.isNotEmpty()) append(", one of ").append(field.options.joinToString(", "))
                if (field.required) append(", required")
                if (field.description.isNotBlank()) append(". ").append(field.description)
            }
        }
        val template = buildJsonObject {
            put(key, buildJsonObject { asked.fields.forEach { put(it.name, "…") } })
        }.toString()
        val question = listOf(asked.message, fields).filter { it.isNotBlank() }.joinToString("\n")
        return McpInputAnswer.Defer(context.getString(R.string.ftools_mcp_input_question, call.name, question, template))
    }

    /** A completion the server asked the user's own AI for, after the user allowed it. */
    private fun sample(offer: McpOffer, params: JsonObject): McpInputAnswer {
        val ai = settings.ai
        if (ai.provider == AiProvider.ON_DEVICE || !AiClient.isConfigured(ai)) return McpInputAnswer.Refuse
        val (system, turns) = McpProtocol.samplingTurns(params)
        if (turns.isEmpty()) return McpInputAnswer.Refuse
        val shown = buildString {
            if (system.isNotBlank()) append(system).append("\n\n")
            append(turns.joinToString("\n\n") { it.second })
        }
        when (askOnCard(offer, context.getString(R.string.ftools_mcp_sampling_title), shown)) {
            AiToolApproval.ONCE, AiToolApproval.ALWAYS -> Unit
            else -> return McpInputAnswer.Refuse
        }
        val config = AiClient.config(ai)
        val reply = AiClient.completeStreaming(
            config,
            system,
            turns.map { (user, text) ->
                AiClient.ChatTurn(if (user) AiClient.ChatRole.USER else AiClient.ChatRole.ASSISTANT, text)
            },
            McpProtocol.samplingMaxTokens(params) ?: AiClient.effectiveMaxTokens(ai),
            onPhase = {},
            onPartial = {},
        )
        return McpInputAnswer.Answer(McpProtocol.samplingResult(AiThinking.stripped(reply.text), config.model))
    }

    /**
     * A new name for the chat (#882). Always asked about, whatever any
     * policy says, and with only Accept and Decline: the name is the user's
     * to agree to each time, so there is no "always". Closing the keyboard
     * while it asks is a Decline.
     */
    private fun renameChat(call: AiToolCall, host: AiToolHost): AiToolResult {
        val title = call.argument("title")?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            ?: return missing(call, "title")
        val ask = activityFor(call).copy(awaitingApproval = true, details = title)
        return when (approver?.approve(ask) ?: AiToolApproval.DENY) {
            AiToolApproval.ONCE, AiToolApproval.ALWAYS -> host.renameChat(title).fold(
                onSuccess = { name -> AiToolResult(call, "Renamed the chat to \"$name\".") },
                onFailure = { refused(call, context.getString(R.string.ftools_ai_tool_failed, it.message.orEmpty()), it.message.orEmpty()) },
            )
            AiToolApproval.DENY -> refused(
                call,
                context.getString(R.string.ftools_mcp_declined),
                context.getString(R.string.ftools_mcp_reason_declined),
            )
            AiToolApproval.CANCELLED -> refused(
                call,
                context.getString(R.string.ftools_mcp_stopped),
                context.getString(R.string.ftools_mcp_reason_stopped),
            )
        }
    }

    /** One of the keyboard's own tools, already allowed to run. */
    private fun runBuiltIn(call: AiToolCall): AiToolResult = when (call.name) {
        AiTools.WM_DOCS -> wmDocs(call)
        AiTools.IMAGE_SEARCH -> imageSearch(call)
        AiTools.WEATHER -> weather(call)
        AiTools.WIKIPEDIA -> wikipedia(call)
        AiTools.DEFINE_WORD -> defineWord(call)
        AiTools.TRANSLATE -> translate(call)
        AiTools.CONVERT_CURRENCY -> convertCurrency(call)
        AiTools.CONVERT_UNITS -> convertUnits(call)
        AiTools.CALCULATE -> calculate(call)
        AiTools.CALENDAR_EVENTS -> calendarEvents(call)
        AiTools.SEARCH_NOTES -> searchNotes(call)
        AiTools.SEARCH_SNIPPETS -> searchSnippets(call)
        AiTools.SEARCH_CLIPBOARD -> searchClipboard(call)
        AiTools.CURRENT_LOCATION -> currentLocation(call)
        AiTools.ADD_CALENDAR_EVENT -> addCalendarEvent(call)
        AiTools.ADD_NOTE -> addNote(call)
        AiTools.SET_ALARM -> setAlarm(call)
        AiTools.START_TIMER -> startTimer(call)
        AiTools.OPEN_APP -> openApp(call)
        AiTools.MEDIA_CONTROL -> mediaControl(call)
        AiTools.SEND_TO_PC -> sendToPc(call)
        AiTools.HOME_ASSISTANT -> homeAssistant(call)
        AiTools.HOME_ASSISTANT_STATES -> homeAssistantStates(call)
        AiTools.READ_IMAGE_TEXT -> readImageText(call)
        AiTools.DESCRIBE_IMAGE -> describeImage(call)
        AiTools.REVERSE_IMAGE_SEARCH -> reverseImageSearch(call)
        else -> refused(
            call,
            context.getString(R.string.ftools_ai_tool_unknown, call.name),
            context.getString(R.string.ftools_ai_tool_reason_unknown),
        )
    }

    /**
     * A call that did not work: [content] tells the model what to do about it,
     * [reason] tells the user what happened. Two texts because they are for
     * two readers — "answer from what you already know" is not something to
     * show a person.
     */
    private fun refused(call: AiToolCall, content: String, reason: String, display: AiToolActivity? = null) =
        AiToolResult(call, content, error = reason, display = display)

    private fun missing(call: AiToolCall, argument: String) = refused(
        call,
        context.getString(R.string.ftools_ai_tool_missing_argument, argument),
        context.getString(R.string.ftools_ai_tool_reason_missing, argument),
    )

    private fun notFound(call: AiToolCall, what: String) = refused(
        call,
        context.getString(R.string.ftools_ai_tool_not_found, what),
        context.getString(R.string.ftools_ai_tool_reason_not_found),
    )

    private fun search(call: AiToolCall): AiToolResult {
        val query = call.argument("query") ?: return missing(call, "query")
        if (ToolApiKeys.searchBackend(settings) == null) {
            return refused(
                call,
                context.getString(R.string.ftools_ai_tool_no_search),
                context.getString(R.string.ftools_ai_tool_reason_no_search),
            )
        }
        val page = SearchProviders.web(
            settings,
            query,
            SEARCH_RESULTS,
            settings.webSearch.safe,
            // Asked for whether or not the panel shows answer boxes: this one
            // is read by a model, not drawn, and it is the cheapest summary of
            // the page Tavily will ever give us.
            answer = true,
            source = NetSource.AI_TOOLS,
        )
        return AiToolResult(
            call = call,
            content = formatSearch(page, context.getString(R.string.ftools_ai_tool_no_results)),
            sources = page.results.map { AiToolSource(it.title.trim(), it.url) },
        )
    }

    private fun fetch(call: AiToolCall): AiToolResult {
        val url = call.argument("url") ?: return missing(call, "url")
        // Checked here as well as in the client, so the user is told plainly
        // rather than shown the instruction the model gets.
        val target = WebFetchClient.normalize(url) ?: return refused(
            call,
            context.getString(R.string.ftools_web_fetch_bad_url),
            context.getString(R.string.ftools_ai_tool_reason_bad_url),
        )
        val page = WebFetchClient.fetch(target)
        return AiToolResult(
            call = call,
            content = formatPage(page, url, context.getString(R.string.ftools_ai_tool_truncated)),
            sources = listOf(AiToolSource(page.title.trim(), target)),
        )
    }

    /**
     * The keyboard's own documentation, searched on its site. An on-device
     * model gets fewer sections: each is replayed into every later round, and
     * its context is the smallest.
     */
    private fun wmDocs(call: AiToolCall): AiToolResult {
        val query = call.argument("query") ?: return missing(call, "query")
        val limit = if (settings.ai.provider == AiProvider.ON_DEVICE) ON_DEVICE_DOCS_RESULTS else WmDocs.DEFAULT_RESULTS
        val hits = WmDocs.search(query, limit, NetSource.AI_TOOLS)
        return AiToolResult(
            call = call,
            content = WmDocs.format(query, hits),
            sources = hits.map { AiToolSource(it.label, it.url) },
        )
    }

    /** The search tool's own backend, asked for pictures instead of pages. */
    private fun imageSearch(call: AiToolCall): AiToolResult {
        val query = call.argument("query") ?: return missing(call, "query")
        if (ToolApiKeys.searchBackend(settings) == null) {
            return refused(
                call,
                context.getString(R.string.ftools_ai_tool_no_search),
                context.getString(R.string.ftools_ai_tool_reason_no_search),
            )
        }
        val images = SearchProviders.images(settings, query, SEARCH_RESULTS, settings.webSearch.safe)
        return AiToolResult(
            call = call,
            content = formatImages(images, context.getString(R.string.ftools_ai_tool_no_results)),
            sources = images.map { AiToolSource(it.title.trim(), it.contextUrl.ifBlank { it.imageUrl }) },
        )
    }

    /**
     * The weather tool's forecast for a named place, or for the place saved in
     * the weather tool when the model names none.
     */
    private fun weather(call: AiToolCall): AiToolResult {
        val asked = call.argument("place")
        val (place, latitude, longitude) = if (asked != null) {
            val found = WeatherClient.geocode(asked).firstOrNull() ?: return notFound(call, asked)
            Triple(listOf(found.name, found.region).filter { it.isNotBlank() }.joinToString(", "), found.latitude, found.longitude)
        } else {
            val saved = settings.weather
            val lat = saved.latitude
            val lon = saved.longitude
            if (lat == null || lon == null) {
                return refused(
                    call,
                    context.getString(R.string.ftools_ai_tool_weather_no_place),
                    context.getString(R.string.ftools_ai_tool_reason_no_place),
                )
            }
            Triple(saved.placeName, lat, lon)
        }
        val info = WeatherClient.fetch(latitude, longitude)
        return AiToolResult(
            call = call,
            content = formatWeather(place, info, settings.weather.fahrenheit) { code ->
                context.getString(WeatherClient.describeRes(code))
            },
        )
    }

    /** The best-matching article's summary, in the language the encyclopedia tool reads. */
    private fun wikipedia(call: AiToolCall): AiToolResult {
        val query = call.argument("query") ?: return missing(call, "query")
        val lang = settings.webSearch.wikiLanguage.ifBlank { "en" }
        val title = WikipediaClient.search(query, lang, limit = 1).firstOrNull()?.title
            ?: return notFound(call, query)
        val summary = WikipediaClient.summary(title, lang)
        return AiToolResult(
            call = call,
            content = buildString {
                append(summary.title).append('\n')
                if (summary.description.isNotBlank()) append(summary.description).append('\n')
                append(summary.url).append("\n\n")
                append(summary.extract.trim())
            }.trim(),
            sources = listOf(AiToolSource(summary.title, summary.url)),
        )
    }

    /**
     * The dictionary tool's lookup: its sources in the user's order, the
     * vocabulary packs among them when the device is unlocked to read them.
     */
    private fun defineWord(call: AiToolCall): AiToolResult {
        val word = call.argument("word") ?: return missing(call, "word")
        val sources = settings.resolveProviders(ProviderTool.DICTIONARY).mapNotNull { it.dictionaryRow() }
        if (sources.isEmpty()) {
            return refused(
                call,
                context.getString(R.string.ftools_ai_tool_no_dictionary),
                context.getString(R.string.ftools_ai_tool_reason_no_dictionary),
            )
        }
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
        val result = runBlocking {
            val index = if (sources.any { it.source == DictionarySource.VOCAB_PACKS } && unlocked) {
                val codes = VocabLanguages.wantedCodes(
                    settings.vocabulary.translationLangList,
                    settings.enabledLanguages.map { it.id },
                )
                VocabIndexCache.get(context.filesDir, codes, cards = false).takeUnless { it.isEmpty }
            } else {
                null
            }
            DictionaryLookup.resolveRows(word, sources, DictionaryLookup.fetcher({ index }))
        }
        return when (result) {
            is DictionaryLookup.Result.Found -> AiToolResult(call, formatDefinitions(result.entries))
            DictionaryLookup.Result.NotFound -> notFound(call, word)
            DictionaryLookup.Result.Failed, DictionaryLookup.Result.NotOnDevice -> refused(
                call,
                context.getString(R.string.ftools_ai_tool_failed, context.getString(R.string.ftools_ai_tool_reason_dictionary_offline)),
                context.getString(R.string.ftools_ai_tool_reason_dictionary_offline),
            )
        }
    }

    /**
     * The translate tool's engine as the user set it: on the phone, online, or
     * on the phone first. A surface with no on-device translator goes online.
     */
    private fun translate(call: AiToolCall): AiToolResult {
        val text = call.argument("text") ?: return missing(call, "text")
        val target = call.argument("to")?.let { asked ->
            languageCode(asked) ?: return unknownLanguage(call, asked)
        } ?: settings.translateTargetLang
        val source = call.argument("from")?.let { asked ->
            languageCode(asked) ?: return unknownLanguage(call, asked)
        } ?: TranslateClient.AUTO
        val clipped = text.take(TranslateClient.MAX_CHARS)
        // On the device only while the translate list keeps its on-device row on.
        val onDevice = host?.takeIf {
            it.canTranslateOnDevice && TranslateProviders.rows(settings, TranslateEngine.ON_DEVICE).isNotEmpty()
        }
        val engine = if (onDevice != null) settings.translate.engine else TranslateEngine.ONLINE
        val translation = when (engine) {
            TranslateEngine.ONLINE -> OnlineTranslate.translate(settings, clipped, target, source)
            TranslateEngine.ON_DEVICE -> requireNotNull(onDevice).translateOnDevice(clipped, target, source).getOrThrow()
            TranslateEngine.AUTO -> requireNotNull(onDevice).translateOnDevice(clipped, target, source)
                .getOrElse { OnlineTranslate.translate(settings, clipped, target, source) }
        }
        val from = translation.detectedSource.ifBlank { source.takeIf { it != TranslateClient.AUTO }.orEmpty() }
        return AiToolResult(
            call = call,
            content = buildString {
                append("Translated")
                if (from.isNotEmpty()) append(" from ").append(TranslateClient.languageName(from))
                append(" into ").append(TranslateClient.languageName(target)).append(":\n")
                append(translation.text)
            },
        )
    }

    /** A code the translator lists, from a code or a language's English name. */
    private fun languageCode(asked: String): String? =
        TranslateClient.pickerCode(asked)
            ?: TranslateClient.languages.firstOrNull { it.second.equals(asked, ignoreCase = true) }?.first

    private fun unknownLanguage(call: AiToolCall, asked: String) = refused(
        call,
        context.getString(R.string.ftools_ai_tool_unknown_language, asked),
        context.getString(R.string.ftools_ai_tool_reason_unknown_language, asked),
    )

    /** The currency tool's rate sources, with a coin fetched only when one is asked for. */
    private fun convertCurrency(call: AiToolCall): AiToolResult {
        val amount = call.number("amount") ?: return missing(call, "amount")
        val from = currencyCode(call.argument("from") ?: return missing(call, "from"))
        val to = currencyCode(call.argument("to") ?: return missing(call, "to"))
        val sources = settings.rateSources
        val fiat = fiatRates(settings.fiatRateProviders().ifEmpty { throw NoRateProviderException() }, settings.currencyCacheHours)
        val coins = setOf(from, to).filter { code ->
            code !in fiat.rates && CryptoCatalog.coins.any { it.code == code }
        }.toSet()
        val rates = if (coins.isEmpty() || !sources.cryptoEnabled) {
            fiat
        } else {
            // Units per US dollar, like the fiat table, so the two fold together.
            val raw = CurrencyClient.fetchCryptoRates(settings.cryptoRateProviders().ifEmpty { throw NoRateProviderException() }, coins)
            fiat.copy(rates = fiat.rates + raw.filterKeys { it !in fiat.rates }, crypto = raw.keys)
        }
        for (code in listOf(from, to)) {
            if (code !in rates.rates) {
                return refused(
                    call,
                    context.getString(R.string.ftools_ai_tool_unknown_currency, code),
                    context.getString(R.string.ftools_ai_tool_reason_unknown_currency, code),
                )
            }
        }
        val converted = CurrencyClient.convert(amount, from, to, rates) ?: return notFound(call, "$from → $to")
        val unit = CurrencyClient.convert(1.0, from, to, rates) ?: 0.0
        return AiToolResult(
            call = call,
            content = "${CalcEngine.format(amount, CURRENCY_DIGITS)} $from = " +
                "${CalcEngine.format(converted, CURRENCY_DIGITS)} $to\n" +
                "1 $from = ${CalcEngine.format(unit, RATE_DIGITS)} $to",
        )
    }

    /** A code the rates are keyed by, from a code or a currency's English name. */
    private fun currencyCode(asked: String): String {
        val upper = asked.trim().uppercase()
        if (upper.length == 3 || CryptoCatalog.coins.any { it.code == upper }) return upper
        return CurrencyClient.names.entries.firstOrNull { it.value.equals(asked.trim(), ignoreCase = true) }?.key ?: upper
    }

    /** The unit converter, reading units by every name the strip's chips understand. */
    private fun convertUnits(call: AiToolCall): AiToolResult {
        val value = call.number("value") ?: return missing(call, "value")
        val fromName = call.argument("from") ?: return missing(call, "from")
        val toName = call.argument("to") ?: return missing(call, "to")
        val from = SmartSuggest.unitNamed(fromName) ?: return unknownUnit(call, fromName)
        val to = SmartSuggest.unitNamed(toName) ?: return unknownUnit(call, toName)
        val category = UnitConvert.categories.firstOrNull { from in it.units }
        if (category == null || to !in category.units) {
            return refused(
                call,
                context.getString(R.string.ftools_ai_tool_units_mismatch, from.symbol, to.symbol),
                context.getString(R.string.ftools_ai_tool_reason_units_mismatch, from.symbol, to.symbol),
            )
        }
        val result = UnitConvert.convert(value, from, to)
        return AiToolResult(
            call = call,
            content = "${CalcEngine.format(value, UNIT_DIGITS)} ${from.symbol} = " +
                "${CalcEngine.format(result, UNIT_DIGITS)} ${to.symbol}",
        )
    }

    private fun unknownUnit(call: AiToolCall, asked: String) = refused(
        call,
        context.getString(R.string.ftools_ai_tool_unknown_unit, asked),
        context.getString(R.string.ftools_ai_tool_reason_unknown_unit, asked),
    )

    /** The calculator's own engine, with its degree and precision settings. */
    private fun calculate(call: AiToolCall): AiToolResult {
        val expression = call.argument("expression") ?: return missing(call, "expression")
        // "18% of 2450" is how people write it and how a model copies it;
        // the engine reads a percentage times a number.
        val written = expression.replace(PERCENT_OF, "% *")
        return try {
            val value = CalcEngine.evaluate(written, settings.calcDegrees)
            AiToolResult(call, "$expression = ${CalcEngine.format(value, settings.calcPrecision)}")
        } catch (failure: CalcEngine.CalcException) {
            val reason = if (failure.detail.isEmpty()) {
                context.getString(failure.messageRes)
            } else {
                context.getString(failure.messageRes, failure.detail)
            }
            refused(call, context.getString(R.string.ftools_ai_tool_calc_failed, reason), reason)
        }
    }

    /** A refusal whose two texts are a model string and a user string, each taking [args]. */
    private fun refusedWith(call: AiToolCall, model: Int, user: Int, vararg args: Any) = refused(
        call,
        context.getString(model, *args),
        context.getString(user, *args),
    )

    private fun badDate(call: AiToolCall, text: String) =
        refusedWith(call, R.string.ftools_ai_tool_bad_date, R.string.ftools_ai_tool_reason_bad_date, text)

    /** Whether files in credential-encrypted storage, like the notes, can be read yet. */
    private fun unlocked(): Boolean = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false

    /** The user's events from one date to another, at most a month of them. */
    private fun calendarEvents(call: AiToolCall): AiToolResult {
        val fromText = call.argument("from") ?: return missing(call, "from")
        val from = AiCalendarArgs.parse(fromText) ?: return badDate(call, fromText)
        val to = call.argument("to")?.let { AiCalendarArgs.parse(it) ?: return badDate(call, it) } ?: from
        if (!DeviceCalendar.canRead(context)) {
            return refusedWith(call, R.string.ftools_ai_tool_no_calendar_access, R.string.ftools_ai_tool_reason_no_calendar_access)
        }
        val start = AiCalendarArgs.dayRange(from).first
        val until = (AiCalendarArgs.dayRange(to).last + 1)
            .coerceIn(start + AiCalendarArgs.DAY_MS, start + MAX_CALENDAR_DAYS * AiCalendarArgs.DAY_MS)
        val events = DeviceCalendar.eventsBetween(context, start, until)
        return AiToolResult(call, formatEvents(events).ifEmpty { "No events." })
    }

    /**
     * Adds an event the way the user chose: the calendar app's editor filled
     * in for them to save, or straight into the calendar.
     */
    private fun addCalendarEvent(call: AiToolCall): AiToolResult {
        val title = call.argument("title") ?: return missing(call, "title")
        val startText = call.argument("start") ?: return missing(call, "start")
        val span = AiCalendarArgs.span(startText, call.argument("end")) ?: return badDate(call, startText)
        val location = call.argument("location")
        val notes = call.argument("notes")
        val summary = "$title, ${formatSpan(span.begin, span.end, span.allDay)}" +
            (location?.let { ", at $it" } ?: "")
        if (settings.ai.toolCalendarMode == AiCalendarMode.OPEN_APP) {
            val intent = Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, span.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, span.end)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, span.allDay)
                .putExtra(CalendarContract.Events.TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            notes?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
            if (runCatching { context.startActivity(intent) }.isFailure) {
                return refusedWith(call, R.string.ftools_ai_tool_no_calendar_app, R.string.ftools_ai_tool_reason_no_calendar_app)
            }
            return AiToolResult(
                call,
                "The calendar app is now open with this event filled in: $summary. " +
                    "It is not saved until the user presses Save there, so tell them to check it and save it.",
            )
        }
        if (!DeviceCalendar.canWrite(context)) {
            return refusedWith(call, R.string.ftools_ai_tool_no_calendar_write, R.string.ftools_ai_tool_reason_no_calendar_write)
        }
        val calendars = DeviceCalendar.writableCalendars(context)
        val target = calendars.firstOrNull { it.id == settings.ai.toolCalendarId } ?: calendars.firstOrNull()
            ?: return refusedWith(call, R.string.ftools_ai_tool_no_writable_calendar, R.string.ftools_ai_tool_reason_no_writable_calendar)
        DeviceCalendar.insertEvent(context, target.id, title, span.begin, span.end, span.allDay, location, notes)
            ?: return refusedWith(call, R.string.ftools_ai_tool_calendar_refused, R.string.ftools_ai_tool_reason_calendar_refused)
        return AiToolResult(call, "Added to the calendar \"${target.name.ifBlank { target.account }}\": $summary.")
    }

    /**
     * Notes matching the model's words, or the newest ones; a locked note's
     * text stays hidden. With search by meaning on, notes that mean what the
     * query does follow, minus the ones the words already found.
     */
    private fun searchNotes(call: AiToolCall): AiToolResult {
        if (!unlocked()) return refusedWith(call, R.string.ftools_ai_tool_locked, R.string.ftools_ai_tool_reason_locked)
        val query = call.argument("query").orEmpty()
        val store = NoteStore(File(context.filesDir, NoteStore.FILE_PATH))
        val found = store.search(query).take(MAX_NOTES)
        val byWords = found.map { it.id.toString() }.toSet()
        val byMeaning = host?.takeIf { query.isNotBlank() && it.canSearchByMeaning }
            ?.notesByMeaning(query)?.getOrNull().orEmpty()
            .filter { it.id !in byWords }
        if (found.isEmpty() && byMeaning.isEmpty()) return AiToolResult(call, "No notes found.")
        val words = found.joinToString("\n\n") { note ->
            val body = if (note.locked) "[locked: its text is hidden]" else note.body.take(NOTE_CHARS)
            note.displayTitle() + "\n" + body.trim()
        }
        val meaning = byMeaning.takeIf { it.isNotEmpty() }?.let { "Close in meaning:\n" + AiMeaningFormat.format(it) }
        return AiToolResult(call, listOf(words, meaning.orEmpty()).filter { it.isNotBlank() }.joinToString("\n\n"))
    }

    /**
     * Snippets matching the model's words, or the newest ones, each read as
     * written: a template is never expanded. With search by meaning on,
     * snippets that mean what the query does follow, minus the ones the
     * words already found. The store is opened like the notes: read, never
     * written.
     */
    private fun searchSnippets(call: AiToolCall): AiToolResult {
        if (!unlocked()) return refusedWith(call, R.string.ftools_ai_tool_locked, R.string.ftools_ai_tool_reason_locked)
        val query = call.argument("query").orEmpty()
        val snippets = SnippetStore(File(context.filesDir, SnippetStore.FILE_PATH)).items()
        val found = AiSnippetSearch.byWords(snippets, query)
        val byMeaning = host?.takeIf { query.isNotBlank() && it.canSearchByMeaning }
            ?.snippetsByMeaning(query)?.getOrNull().orEmpty()
        return AiToolResult(call, AiSnippetSearch.answer(found, byMeaning, snippets))
    }

    /**
     * Search by meaning over the clipboard history, through the keyboard
     * module's index. What the model reads is shaped by [AiMeaningFormat];
     * what may be found at all is the host's to decide.
     */
    private fun searchClipboard(call: AiToolCall): AiToolResult {
        val query = call.argument("query") ?: return missing(call, "query")
        if (!unlocked()) return refusedWith(call, R.string.ftools_ai_tool_locked, R.string.ftools_ai_tool_reason_locked)
        val hits = requireNotNull(host).searchClipboard(query).getOrThrow()
        return AiToolResult(call, AiMeaningFormat.format(hits))
    }

    /**
     * A new note, written straight to the notes file. The keyboard's notes
     * panel and the app each re-read the file when they open, which is the
     * store's contract for every writer.
     */
    /**
     * Keeps one fact in the AI memory, or tells the model why not. A fact
     * already kept is not kept again, and one that says more replaces the
     * old wording; see [AiMemoryStore.remember]. What changed rides on the
     * result as an [AiMemoryNote], which the chat draws with an Undo.
     */
    private fun remember(call: AiToolCall, access: AiMemoryAccess): AiToolResult {
        val fact = call.argument("fact") ?: return missing(call, "fact")
        val replaceId = call.argument("id")?.let { raw ->
            raw.toDoubleOrNull()?.toLong() ?: return refusedWith(
                call, R.string.ftools_ai_tool_memory_no_such, R.string.ftools_ai_tool_reason_memory_no_such, raw,
            )
        }
        val store = access.store
        val outcome = store.remember(
            text = fact,
            now = System.currentTimeMillis(),
            source = access.source,
            category = call.argument("category").orEmpty(),
            replaceId = replaceId,
        )
        val started = AiToolActivity.started(call)
        return when (outcome) {
            is AiMemoryOutcome.Added -> {
                store.save()
                AiToolResult(
                    call,
                    "Saved as memory ${outcome.memory.id}.",
                    display = started.copy(memory = AiMemoryNote(outcome.memory.id, outcome.memory.text)),
                )
            }
            is AiMemoryOutcome.Updated -> {
                store.save()
                AiToolResult(
                    call,
                    "Memory ${outcome.memory.id} now reads: ${outcome.memory.text}",
                    display = started.copy(
                        memory = AiMemoryNote(
                            id = outcome.memory.id,
                            text = outcome.memory.text,
                            added = false,
                            before = outcome.before.text,
                            beforeCategory = outcome.before.category,
                        ),
                    ),
                )
            }
            is AiMemoryOutcome.Duplicate ->
                AiToolResult(call, "Already saved as memory ${outcome.memory.id}: ${outcome.memory.text}. Nothing changed.")
            is AiMemoryOutcome.Refused -> memoryRefused(call, outcome.reason, replaceId?.toString().orEmpty())
        }
    }

    /**
     * Deletes the memory the model names by number, or the ones its words
     * pick out, up to [FORGET_AT_ONCE]. More matches than that are listed
     * back, so a vague "forget everything about work" cannot empty a list
     * the user built over months.
     */
    private fun forget(call: AiToolCall, access: AiMemoryAccess): AiToolResult {
        val store = access.store
        if (!store.readable) return memoryRefused(call, AiMemoryOutcome.Reason.UNREADABLE, "")
        val idText = call.argument("id")
        if (idText != null) {
            val id = idText.toDoubleOrNull()?.toLong()
            val gone = id?.let { store.forget(it) }
                ?: return memoryRefused(call, AiMemoryOutcome.Reason.NOT_FOUND, idText)
            store.save()
            return AiToolResult(call, "Deleted memory ${gone.id}: ${gone.text}")
        }
        val match = call.argument("match") ?: return missing(call, "match")
        val found = store.matching(match)
        if (found.isEmpty()) {
            return refusedWith(
                call, R.string.ftools_ai_tool_memory_none_match, R.string.ftools_ai_tool_reason_memory_none_match, match,
            )
        }
        if (found.size > FORGET_AT_ONCE) {
            val list = found.joinToString("\n") { "[${it.id}] ${it.text}" }
            return refused(
                call,
                context.getString(R.string.ftools_ai_tool_memory_many_match, found.size) + "\n" + list,
                context.getString(R.string.ftools_ai_tool_reason_memory_many_match),
            )
        }
        found.forEach { store.forget(it.id) }
        store.save()
        return AiToolResult(call, "Deleted:\n" + found.joinToString("\n") { "[${it.id}] ${it.text}" })
    }

    private fun memoryRefused(call: AiToolCall, reason: AiMemoryOutcome.Reason, id: String): AiToolResult = when (reason) {
        AiMemoryOutcome.Reason.SECRET ->
            refusedWith(call, R.string.ftools_ai_tool_memory_secret, R.string.ftools_ai_tool_reason_memory_secret)
        AiMemoryOutcome.Reason.TOO_LONG -> refused(
            call,
            context.getString(R.string.ftools_ai_tool_memory_too_long, AiMemoryText.MAX_CHARS),
            context.getString(R.string.ftools_ai_tool_reason_memory_too_long),
        )
        AiMemoryOutcome.Reason.EMPTY ->
            refusedWith(call, R.string.ftools_ai_tool_memory_empty, R.string.ftools_ai_tool_reason_memory_empty)
        AiMemoryOutcome.Reason.FULL ->
            refusedWith(call, R.string.ftools_ai_tool_memory_full, R.string.ftools_ai_tool_reason_memory_full)
        AiMemoryOutcome.Reason.NOT_FOUND -> refused(
            call,
            context.getString(R.string.ftools_ai_tool_memory_no_such, id),
            context.getString(R.string.ftools_ai_tool_reason_memory_no_such),
        )
        AiMemoryOutcome.Reason.UNREADABLE ->
            refusedWith(call, R.string.ftools_ai_tool_memory_unreadable, R.string.ftools_ai_tool_reason_memory_unreadable)
    }

    private fun addNote(call: AiToolCall): AiToolResult {
        val text = call.argument("text") ?: return missing(call, "text")
        if (!unlocked()) return refusedWith(call, R.string.ftools_ai_tool_locked, R.string.ftools_ai_tool_reason_locked)
        val store = NoteStore(File(context.filesDir, NoteStore.FILE_PATH))
        val note = store.add(body = text, title = call.argument("title").orEmpty())
        store.save()
        return AiToolResult(call, "Saved the note \"${note.displayTitle()}\".")
    }

    /**
     * One reading of where the phone is, as the location tool takes it:
     * Android's recent fix if there is one, else a fresh one, then rounded to
     * the precision the user shares at, and an address when they want one.
     */
    private fun currentLocation(call: AiToolCall): AiToolResult {
        if (LocationAccess.grant(context) == LocationGrant.NONE) {
            return refusedWith(call, R.string.ftools_ai_tool_no_location_access, R.string.ftools_ai_tool_reason_no_location_access)
        }
        if (!LocationAccess.servicesOn(context)) {
            return refusedWith(call, R.string.ftools_ai_tool_location_off, R.string.ftools_ai_tool_reason_location_off)
        }
        val fixer = LocationFixer(context)
        val fix = fixer.lastKnown(QUICK_FIX_MS)
            ?: runBlocking {
                var best: LocationFix? = null
                // A provider that threw is a reading that did not come; a
                // cancelled run still has to stop.
                runCancellable { fixer.fixes().collect { best = it } }
                best
            }
            ?: fixer.lastKnown(STALE_FIX_MS)
            ?: return refusedWith(call, R.string.ftools_ai_tool_no_fix, R.string.ftools_ai_tool_reason_no_fix)
        val precision = settings.location.precision
        val point = precision.apply(fix.point)
        val language = context.resources.configuration.locales[0]?.language?.ifBlank { null } ?: "en"
        val address = if (settings.location.lookupAddress) {
            runCatching { ReverseGeocoder.lookup(point, precision, language) }.getOrNull()
        } else {
            null
        }
        val digits = "%.${precision.decimals}f"
        return AiToolResult(
            call,
            buildString {
                append("Coordinates: ")
                append(String.format(Locale.ROOT, "$digits, $digits", point.latitude, point.longitude))
                fix.accuracyM?.let { append("\nAccurate to about ").append(it.toInt()).append(" m") }
                address?.let { append("\nAddress: ").append(it) }
            },
        )
    }

    /** An alarm in the keyboard's own clock, once or on repeat days. */
    private fun setAlarm(call: AiToolCall): AiToolResult {
        val timeText = call.argument("time") ?: return missing(call, "time")
        val (hour, minute) = AiClockArgs.time(timeText)
            ?: return refusedWith(call, R.string.ftools_ai_tool_bad_time, R.string.ftools_ai_tool_reason_bad_time, timeText)
        val daysText = call.argument("days")
        val days = AiClockArgs.days(daysText)
            ?: return refusedWith(call, R.string.ftools_ai_tool_bad_days, R.string.ftools_ai_tool_reason_bad_days, daysText.orEmpty())
        val label = call.argument("label").orEmpty()
        var added: Alarm? = null
        ClockEngine.update(context) { data ->
            val (alarm, next) = ClockActions.addAlarm(data, Alarm(id = 0, hour = hour, minute = minute, days = days, label = label))
            added = alarm
            next
        }
        val rings = added?.let { AlarmMath.nextFire(it, System.currentTimeMillis()) }
        return AiToolResult(
            call,
            buildString {
                append("Set an alarm for ").append(String.format(Locale.ROOT, "%02d:%02d", hour, minute))
                if (label.isNotEmpty()) append(", labelled \"").append(label).append('"')
                append(if (days == 0) ", ringing once." else ", repeating.")
                rings?.let { append(" It rings next at ").append(formatInstant(it)).append('.') }
                if (!ClockEngine.canScheduleExact(context)) {
                    append(" The phone does not let the keyboard set exact alarms, so it may ring a little late.")
                }
            },
        )
    }

    /** A countdown in the keyboard's own clock. */
    private fun startTimer(call: AiToolCall): AiToolResult {
        val durationText = call.argument("duration") ?: return missing(call, "duration")
        val ms = AiClockArgs.duration(durationText)
            ?: return refusedWith(call, R.string.ftools_ai_tool_bad_duration, R.string.ftools_ai_tool_reason_bad_duration, durationText)
        val label = call.argument("label").orEmpty()
        val now = System.currentTimeMillis()
        ClockEngine.update(context) { ClockActions.startTimer(it, label, ms, now).second }
        return AiToolResult(call, "Started a timer for $durationText. It ends at ${formatInstant(now + ms)}.")
    }

    private fun openApp(call: AiToolCall): AiToolResult {
        val name = call.argument("name") ?: return missing(call, "name")
        val opener = host ?: return refusedWith(call, R.string.ftools_ai_tool_no_app, R.string.ftools_ai_tool_reason_no_app, name)
        return opener.openApp(name).fold(
            onSuccess = { label -> AiToolResult(call, "Opened $label.") },
            onFailure = { failure ->
                if (failure is NoSuchElementException) {
                    refusedWith(call, R.string.ftools_ai_tool_no_app, R.string.ftools_ai_tool_reason_no_app, name)
                } else {
                    throw failure
                }
            },
        )
    }

    /**
     * Play, pause, skip, or say what is playing, through the session the
     * media tool controls. Bound for this one call and let go after it.
     */
    private fun mediaControl(call: AiToolCall): AiToolResult {
        val action = call.argument("action")?.lowercase(Locale.ROOT) ?: return missing(call, "action")
        if (action !in MEDIA_ACTIONS) {
            return refusedWith(
                call, R.string.ftools_ai_tool_bad_choice, R.string.ftools_ai_tool_reason_bad_choice,
                action, MEDIA_ACTIONS.joinToString(", "),
            )
        }
        val manager = MediaControlManager(context)
        if (!manager.hasAccess()) {
            return refusedWith(call, R.string.ftools_ai_tool_no_media_access, R.string.ftools_ai_tool_reason_no_media_access)
        }
        var snapshot: MediaSnapshot? = null
        manager.start { snapshot = it }
        try {
            val now = snapshot
                ?: return refusedWith(call, R.string.ftools_ai_tool_nothing_playing, R.string.ftools_ai_tool_reason_nothing_playing)
            val what = listOf(now.title, now.artist).filter { it.isNotBlank() }.joinToString(" by ")
                .ifEmpty { "something" } + (now.appLabel.takeIf { it.isNotBlank() }?.let { " in $it" } ?: "")
            val done = when (action) {
                "play" -> if (now.playing) "It was already playing: $what." else { manager.playPause(); "Playing $what." }
                "pause" -> if (!now.playing) "It was already paused: $what." else { manager.playPause(); "Paused $what." }
                "next" -> { manager.next(); "Skipped to the next track after $what." }
                "previous" -> { manager.previous(); "Went back to the track before $what." }
                else -> (if (now.playing) "Playing now: " else "Paused: ") + "$what."
            }
            return AiToolResult(call, done)
        } finally {
            manager.stop()
        }
    }

    private fun sendToPc(call: AiToolCall): AiToolResult {
        val text = call.argument("text") ?: return missing(call, "text")
        val sender = host ?: return refusedWith(call, R.string.ftools_ai_tool_pc_failed, R.string.ftools_ai_tool_reason_pc_failed, "")
        return sender.sendToPc(text).fold(
            onSuccess = { device -> AiToolResult(call, "Sent to $device.") },
            onFailure = { failure ->
                val why = failure.message.orEmpty()
                refusedWith(call, R.string.ftools_ai_tool_pc_failed, R.string.ftools_ai_tool_reason_pc_failed, why)
            },
        )
    }

    /**
     * One device and an action, run on whichever home has it; or one sentence
     * to a Home Assistant's Assist. Only Home Assistant understands sentences,
     * so a device and an action is what reaches Google Home and the rest.
     */
    private fun homeAssistant(call: AiToolCall): AiToolResult {
        val home = host ?: return refusedWith(call, R.string.ftools_ai_tool_home_failed, R.string.ftools_ai_tool_reason_home_failed, "")
        val entityId = call.argument("entity_id")
        val action = call.argument("action")
        if (entityId != null && action != null) {
            return home.homeAction(entityId, action, homeData(call)).fold(
                onSuccess = { AiToolResult(call, it) },
                onFailure = { failure ->
                    refusedWith(call, R.string.ftools_ai_tool_home_failed, R.string.ftools_ai_tool_reason_home_failed, failure.message.orEmpty())
                },
            )
        }
        val request = call.argument("request") ?: return missing(call, if (entityId != null) "action" else "entity_id")
        return home.homeControl(request).fold(
            onSuccess = { speech -> AiToolResult(call, speech.ifBlank { "Done." }) },
            onFailure = { failure ->
                refusedWith(call, R.string.ftools_ai_tool_home_failed, R.string.ftools_ai_tool_reason_home_failed, failure.message.orEmpty())
            },
        )
    }

    /** The call's `data`, whether the model sent an object or the object as a string. */
    private fun homeData(call: AiToolCall): JsonObject {
        val raw = runCatching { Json.parseToJsonElement(call.arguments).jsonObject["data"] }.getOrNull()
        return when (raw) {
            is JsonObject -> raw
            is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content).jsonObject }.getOrNull()
            else -> null
        } ?: JsonObject(emptyMap())
    }

    private fun homeAssistantStates(call: AiToolCall): AiToolResult {
        val home = host ?: return refusedWith(call, R.string.ftools_ai_tool_home_failed, R.string.ftools_ai_tool_reason_home_failed, "")
        return home.homeStates(call.argument("query")).fold(
            onSuccess = { AiToolResult(call, it) },
            onFailure = { failure ->
                refusedWith(call, R.string.ftools_ai_tool_home_failed, R.string.ftools_ai_tool_reason_home_failed, failure.message.orEmpty())
            },
        )
    }

    /**
     * The picture a call names, counted from 1. A model with one picture in
     * front of it often leaves the number out, which can only mean that one.
     */
    private fun pictureNumber(call: AiToolCall): Int? {
        val count = host?.imageCount ?: 0
        val asked = call.number("image")?.toInt() ?: return if (count == 1) 1 else null
        return asked.takeIf { it in 1..count }
    }

    private fun noPicture(call: AiToolCall) = refusedWith(
        call, R.string.ftools_ai_tool_no_picture, R.string.ftools_ai_tool_reason_no_picture,
        call.argument("image").orEmpty(), host?.imageCount ?: 0,
    )

    /** The text in one of the user's pictures, read on the phone or by the scan tool's online engine. */
    private fun readImageText(call: AiToolCall): AiToolResult {
        val number = pictureNumber(call) ?: return noPicture(call)
        val text = requireNotNull(host).readImageText(number).getOrThrow()
        return AiToolResult(call, if (text.isBlank()) "Picture $number has no text in it." else "The text in picture $number:\n$text")
    }

    /** One of the user's pictures as the model that sees describes it, with the question the call asked. */
    private fun describeImage(call: AiToolCall): AiToolResult {
        val number = pictureNumber(call) ?: return noPicture(call)
        val description = requireNotNull(host).describeImage(number, call.argument("question").orEmpty()).getOrThrow()
        return AiToolResult(call, description)
    }

    /**
     * Uploads one of the user's pictures to the camera tool's search site,
     * and hands back the results page. The page is for a browser: it runs
     * scripts and has nothing a fetch could read, so the model is told to
     * pass the link on.
     */
    private fun reverseImageSearch(call: AiToolCall): AiToolResult {
        val number = pictureNumber(call) ?: return noPicture(call)
        val jpeg = requireNotNull(host).imageJpeg(number) ?: return noPicture(call)
        val camera = settings.camera
        val (site, page) = when (camera.searchEngine) {
            PhotoSearchEngine.GOOGLE_LENS -> "Google Lens" to ReverseImageClient.googleLens(jpeg, Locale.getDefault().toLanguageTag())
            PhotoSearchEngine.BING -> "Bing" to ReverseImageClient.bing(jpeg)
            PhotoSearchEngine.YANDEX -> "Yandex" to ReverseImageClient.yandex(jpeg)
            PhotoSearchEngine.TINEYE -> "TinEye" to ReverseImageClient.tinEye(jpeg)
            PhotoSearchEngine.CUSTOM -> AiToolActivity.domainOf(camera.searchCustomUrl) to
                ReverseImageClient.custom(jpeg, camera.searchCustomUrl, camera.searchCustomField)
        }
        return AiToolResult(
            call = call,
            content = "Picture $number was searched on $site, which made a results page: $page\n" +
                "That page only works in a browser and you cannot read it, so give the user the link to open. " +
                "If you can see the picture, say what you can tell from it as well.",
            sources = listOf(AiToolSource(site, page)),
        )
    }

    companion object {

        /**
         * The tools one run may offer, from the settings and from what is
         * actually set up.
         *
         * Search drops out when no backend is configured rather than being
         * offered and failing: a tool the model can only ever be refused by
         * still costs its description in the prompt of every run, and a small
         * model handed one tends to keep trying it. For the same reason an
         * MCP server that was never connected offers nothing, and a tool the
         * user turned off is not in the list at all.
         */
        fun enabled(context: Context, settings: KeyboardSettings): List<AiToolSpec> =
            AiToolRunner(context, settings).tools()

        /**
         * Results per search handed to the model: fewer than the panel shows a
         * person. Every one of them is replayed into every later round of the
         * same run, so a page of twenty would crowd out the answer.
         */
        private const val SEARCH_RESULTS = 5

        /** Documentation sections for an on-device model; see [wmDocs]. */
        private const val ON_DEVICE_DOCS_RESULTS = 2

        private const val CURRENCY_DIGITS = 2
        private const val RATE_DIGITS = 6
        private const val UNIT_DIGITS = 6

        private val PERCENT_OF = Regex("""%\s*of\b""", RegexOption.IGNORE_CASE)

        /** The longest span the calendar tool lists in one call. */
        private const val MAX_CALENDAR_DAYS = 31

        /** Notes handed back per search, and how much of each. */
        private const val MAX_NOTES = 5

        /** Memories one `forget` by words may delete; past this it lists them instead. */
        private const val FORGET_AT_ONCE = 3
        private const val NOTE_CHARS = 1_500

        /** Android's own reading is used when it is this fresh; past it a new one is taken. */
        private const val QUICK_FIX_MS = 60_000L

        /** The oldest reading still worth giving when a new one did not come. */
        private const val STALE_FIX_MS = 15 * 60_000L

        private val MEDIA_ACTIONS = listOf("play", "pause", "next", "previous", "now_playing")

        /** An instant as the local date and time, "2026-10-17 17:00". */
        internal fun formatInstant(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = zone }.format(Date(millis))

        /**
         * A span as one phrase: "2026-10-17 17:00 to 18:00", or for all-day
         * "all day on 2026-10-17" (its ends are UTC midnights, the end one past).
         */
        internal fun formatSpan(begin: Long, end: Long, allDay: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
            if (allDay) {
                val utc = TimeZone.getTimeZone("UTC")
                val first = formatInstant(begin, utc).substringBefore(' ')
                val last = formatInstant(end - 1, utc).substringBefore(' ')
                return if (first == last) "all day on $first" else "all day from $first to $last"
            }
            val from = formatInstant(begin, zone)
            val to = formatInstant(end, zone)
            return if (from.substringBefore(' ') == to.substringBefore(' ')) {
                "$from to ${to.substringAfter(' ')}"
            } else {
                "$from to $to"
            }
        }

        /** Events as one line each, all-day first, the way the calendar tool lists a day. */
        internal fun formatEvents(events: List<DeviceCalendarEvent>, zone: TimeZone = TimeZone.getDefault()): String =
            events.joinToString("\n") { event ->
                buildString {
                    append(formatSpan(event.begin, event.end, event.allDay, zone)).append(": ").append(event.title)
                    event.location?.let { append(", at ").append(it) }
                }
            }

        /**
         * The fiat table, kept for as long as the currency tool keeps its own,
         * so a chat converting three prices in a row asks the source once.
         */
        @Volatile
        private var fiatCache: Pair<Long, CurrencyClient.Rates>? = null

        private fun fiatRates(providers: List<String>, cacheHours: Int): CurrencyClient.Rates {
            val now = System.currentTimeMillis()
            fiatCache?.let { (at, rates) ->
                if (now - at < cacheHours * 60L * 60L * 1000L) return rates
            }
            return CurrencyClient.fetchRates(providers).also { fiatCache = now to it }
        }

        /**
         * Search results as numbered blocks of title, address and snippet.
         *
         * The address is included on purpose, even though it costs tokens: it
         * is what lets the model follow up with `web_fetch`, and what lets it
         * cite a source instead of asserting one.
         */
        internal fun formatSearch(page: WebSearchPage, emptyText: String): String {
            if (page.results.isEmpty() && page.answer.isNullOrBlank()) return emptyText
            return buildString {
                page.answer?.takeIf { it.isNotBlank() }?.let {
                    append("Summary: ").append(it.trim()).append("\n\n")
                }
                page.results.forEachIndexed { index, result ->
                    if (index > 0) append("\n\n")
                    append(index + 1).append(". ").append(result.title.trim()).append('\n')
                    append(result.url).append('\n')
                    append(result.snippet.trim())
                }
            }.trim()
        }

        /** One fetched page, labelled so the model knows what it is reading. */
        internal fun formatPage(page: WebFetchClient.Page, url: String, truncatedNote: String): String =
            buildString {
                if (page.title.isNotBlank()) append(page.title).append('\n')
                append(url).append("\n\n")
                append(page.text)
                if (page.truncated) append("\n\n").append(truncatedNote)
            }.trim()

        /**
         * Image results as numbered blocks: the picture's own address, which a
         * model can show or link, and the page it sits on, which says what it is.
         */
        internal fun formatImages(images: List<ImageResult>, emptyText: String): String {
            if (images.isEmpty()) return emptyText
            return buildString {
                images.forEachIndexed { index, image ->
                    if (index > 0) append("\n\n")
                    append(index + 1).append(". ").append(image.title.trim().ifEmpty { "Image" }).append('\n')
                    append("Image: ").append(image.imageUrl)
                    if (image.contextUrl.isNotBlank()) append("\nPage: ").append(image.contextUrl)
                }
            }
        }

        /**
         * A forecast as a few labelled lines, in the unit the user reads the
         * weather tool in. [describe] turns a WMO code into words.
         */
        internal fun formatWeather(
            place: String,
            info: WeatherInfo,
            fahrenheit: Boolean,
            describe: (Int) -> String,
        ): String {
            val unit = if (fahrenheit) "°F" else "°C"
            fun temp(celsius: Double) = "${WeatherClient.toDisplay(celsius, fahrenheit)}$unit"
            return buildString {
                if (place.isNotBlank()) append("Place: ").append(place).append('\n')
                append("Now: ").append(temp(info.temperatureC))
                append(", feels like ").append(temp(info.feelsLikeC))
                append(", ").append(describe(info.weatherCode))
                append(", humidity ").append(info.humidityPercent).append('%')
                append(", wind ").append(WeatherClient.formatWind(info.windKmh, info.windDirectionDeg, fahrenheit))
                append(", precipitation ").append(CalcEngine.format(info.precipitationMm, 1)).append(" mm\n")
                append("Today: high ").append(temp(info.highC)).append(", low ").append(temp(info.lowC))
                append(", chance of rain ").append(info.precipProbabilityPercent).append('%')
                append(", UV index ").append(CalcEngine.format(info.uvIndexMax, 1))
                append(", sunrise ").append(info.sunrise.substringAfter('T'))
                append(", sunset ").append(info.sunset.substringAfter('T'))
                val high = info.tomorrowHighC
                val low = info.tomorrowLowC
                if (high != null && low != null) {
                    append("\nTomorrow: high ").append(temp(high)).append(", low ").append(temp(low))
                    info.tomorrowCode?.let { append(", ").append(describe(it)) }
                    info.tomorrowPrecipProbabilityPercent?.let { append(", chance of rain ").append(it).append('%') }
                }
            }
        }

        /** Dictionary entries as plain numbered senses, a few per part of speech. */
        internal fun formatDefinitions(entries: List<DictEntry>): String = buildString {
            for (entry in entries.take(MAX_ENTRIES)) {
                if (isNotEmpty()) append("\n\n")
                append(entry.word)
                if (entry.phonetic.isNotBlank()) append(' ').append(entry.phonetic)
                for (meaning in entry.meanings.take(MAX_MEANINGS)) {
                    append('\n').append(meaning.partOfSpeech.ifBlank { "-" }).append(':')
                    meaning.definitions.take(MAX_SENSES).forEachIndexed { index, sense ->
                        append('\n').append(index + 1).append(". ").append(sense.text.trim())
                        sense.example?.takeIf { it.isNotBlank() }?.let { append(" (e.g. \"").append(it.trim()).append("\")") }
                    }
                    if (meaning.synonyms.isNotEmpty()) {
                        append("\nSynonyms: ").append(meaning.synonyms.take(MAX_SYNONYMS).joinToString(", "))
                    }
                }
            }
        }

        private const val MAX_ENTRIES = 2
        private const val MAX_MEANINGS = 3
        private const val MAX_SENSES = 3
        private const val MAX_SYNONYMS = 6
    }
}
