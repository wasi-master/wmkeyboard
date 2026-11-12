package com.wasimaster.wmkeyboard.core.tools

/**
 * Drives one AI run that may call tools, round after round, until the model
 * stops asking for them and writes an answer (#470).
 *
 * Generic over how a round is actually produced, because the two ways differ
 * completely below this line: a cloud or self-hosted provider streams over
 * HTTP through [AiClient], and an on-device model is a blocking native call
 * with no HTTP anywhere near it. Both come back as a [AiClient.Completion],
 * so both can be driven by the same loop — which matters, since the rules
 * about rounds, about what the user is shown while a tool runs, and about
 * where the sentinel fallback applies should not be written down twice.
 */
object AiToolLoop {

    /** One round of generation, however it is produced. */
    fun interface Round {
        /**
         * @param tools what the model may call this round. Empty on the final
         *   round, which is what forces an answer instead of another call.
         * @param onPartial the round's own text so far, not the whole run's.
         */
        fun run(
            system: String,
            turns: List<AiClient.ChatTurn>,
            tools: List<AiToolSpec>,
            onPartial: (String) -> Unit,
        ): AiClient.Completion
    }

    /** Runs one call the model asked for. Must not throw; see `AiToolRunner`. */
    fun interface Executor {
        fun run(call: AiToolCall): AiToolResult
    }

    /**
     * Runs [turns] to an answer, calling tools on the way.
     *
     * [native] says whether the round's provider has real function calling. It
     * does not change the loop, only where the calls are read from: the
     * provider's own fields, or [AiToolProtocol]'s sentinel pulled back out of
     * the text. A model on the sentinel path is also told the protocol, which
     * is why [system] is rewritten for it.
     *
     * [maxRounds] bounds how many times tools may be called before the model
     * has to answer with what it has. A model that keeps asking is not
     * stopped with an error: the last round is simply run with no tools
     * offered, so the user gets an answer rather than a failure.
     *
     * [onToolCall] fires as a call starts and [onToolResult] as it ends, both
     * on the calling thread, so a surface can say what is running while it
     * runs and keep a record of what ran.
     */
    @Suppress("LongParameterList")
    fun run(
        system: String,
        turns: List<AiClient.ChatTurn>,
        tools: List<AiToolSpec>,
        native: Boolean,
        executor: Executor,
        maxRounds: Int = AiTools.DEFAULT_MAX_ROUNDS,
        onPhase: (AiPhase) -> Unit = {},
        onPartial: (String) -> Unit = {},
        onToolCall: (AiToolCall) -> Unit = {},
        onToolResult: (AiToolResult) -> Unit = {},
        isActive: () -> Boolean = { true },
        round: Round,
    ): AiClient.Completion {
        if (tools.isEmpty()) {
            return round.run(system, turns, emptyList(), onPartial)
        }
        val prompt = if (native) system else system + AiToolProtocol.instructions(tools)
        val conversation = ArrayList(turns)
        // Text from rounds already finished. A model that says "let me look
        // that up" and then searches has written something the user should
        // keep seeing while the search runs.
        val answered = StringBuilder()
        var used = 0
        while (true) {
            val offered = if (used < maxRounds) tools else emptyList()
            val prefix = answered.toString()
            // What a partial of this round is appended to. The separator
            // belongs here and not in [answered], so that the text streamed
            // mid-run and the text returned at the end are the same string.
            val streamed = if (prefix.isEmpty()) "" else prefix + "\n\n"
            val completion = round.run(
                // The final round is not told about tools at all, so a model
                // on the sentinel path cannot spend it writing another one.
                if (offered.isEmpty() && !native) system else prompt,
                conversation,
                offered,
            ) { partial -> onPartial(streamed + visible(partial, native, tools)) }

            val turn = readTurn(completion, native, tools, used)
            val finished = completion.copy(text = join(prefix, turn.text), toolCalls = emptyList())
            if (!isActive()) return finished
            if (offered.isEmpty()) {
                // The last round offered nothing, yet a model can still write
                // a call into it: an on-device chat's session keeps telling it
                // about tools. It cannot run, so it is recorded as not run
                // rather than vanishing from an answer that may read as if it
                // had (#1281).
                for (call in turn.calls + turn.unreadable.map(::unreadableCall)) {
                    onToolCall(call)
                    onToolResult(AiToolResult(call, "", error = NOT_RUN_OUT_OF_ROUNDS))
                }
                return finished
            }
            if (turn.calls.isEmpty() && turn.unreadable.isEmpty()) return finished
            append(answered, turn.text)

            onPhase(AiPhase.USING_TOOL)
            // Only a tool this round offered runs (#825). A model can name any
            // tool it has heard of, in a sentinel above all, and an executor
            // that also knows a tool left out on purpose (the one that needs
            // the network, for an on-device run) would otherwise run it.
            val names = offered.mapTo(HashSet()) { it.name }
            val results = turn.calls.map { call ->
                onToolCall(call)
                val result = if (call.name in names) {
                    executor.run(call)
                } else {
                    AiToolResult(call, "", error = "No tool named ${call.name}.")
                }
                result.also(onToolResult)
            } + turn.unreadable.map { bad ->
                // A call that was plainly meant but would not read (#1281).
                // The model is told it did not run and how to write it, and
                // gets another round to try; the user sees a step that says
                // nothing ran, instead of an answer claiming it did.
                val call = unreadableCall(bad)
                onToolCall(call)
                AiToolResult(call, AiToolProtocol.UNREADABLE_REPLY, error = NOT_RUN_UNREADABLE).also(onToolResult)
            }
            record(conversation, completion, turn, results, native)
            used++
        }
    }

    /** What one round asked for, and what of its text the user may see. */
    private class Turn(
        val text: String,
        val calls: List<AiToolCall>,
        val unreadable: List<AiToolProtocol.Unreadable>,
        /** The calls were read out of the text rather than from the provider's own fields. */
        val fromText: Boolean,
    )

    /**
     * The round's calls. On the sentinel path they are read out of the text,
     * leniently: see [AiToolProtocol]. On the native path they are the
     * provider's own, unless it returned none and the model wrote a call to
     * an offered tool into the text instead, which small models behind
     * Ollama, LM Studio and other OpenAI-compatible servers do often enough
     * (#1281). Those are given ids of their own so the replay can answer them.
     */
    private fun readTurn(completion: AiClient.Completion, native: Boolean, tools: List<AiToolSpec>, round: Int): Turn {
        if (!native) {
            val reading = AiToolProtocol.read(completion.text, tools)
            return Turn(reading.text, reading.calls, reading.unreadable, fromText = true)
        }
        if (completion.toolCalls.isNotEmpty() || tools.isEmpty()) {
            return Turn(completion.text, completion.toolCalls, emptyList(), fromText = false)
        }
        val names = tools.mapTo(HashSet()) { it.name }
        val reading = AiToolProtocol.read(completion.text, tools)
        val calls = reading.calls.filter { it.name in names }
        if (calls.isEmpty()) return Turn(completion.text, emptyList(), emptyList(), fromText = false)
        return Turn(
            reading.text,
            calls.mapIndexed { i, call -> call.copy(id = "text_call_${round}_$i") },
            emptyList(),
            fromText = true,
        )
    }

    /** A call that would not read, as the record and the model's reply carry it. */
    private fun unreadableCall(bad: AiToolProtocol.Unreadable): AiToolCall =
        AiToolCall(id = "", name = bad.name.ifEmpty { UNREADABLE_NAME }, arguments = bad.text)

    /** The name a call that would not read is shown under when even its tool is unclear. */
    private const val UNREADABLE_NAME = "tool_call"

    /** Why a call written in a form the reader could not make out did not run. */
    const val NOT_RUN_UNREADABLE = "Not run: the model wrote this call in a form the keyboard could not read."

    /** Why a call written on the last round, which offers no tools, did not run. */
    const val NOT_RUN_OUT_OF_ROUNDS = "Not run: the model had already used all its lookups for this answer."

    /**
     * What may be shown of a partial. On the sentinel path a half-written
     * `<tool_c` must be held back rather than flashed into the panel, which is
     * exactly what a filter fed but not flushed does.
     */
    private fun visible(partial: String, native: Boolean, tools: List<AiToolSpec>): String =
        if (native) partial else AiToolProtocol.Filter(tools).feed(partial)

    /** Adds this round's request and its answers to the conversation. */
    private fun record(
        conversation: MutableList<AiClient.ChatTurn>,
        completion: AiClient.Completion,
        turn: Turn,
        results: List<AiToolResult>,
        native: Boolean,
    ) {
        if (native) {
            // The replay carries what some providers refuse to do without —
            // Gemini's thought signatures, DeepSeek's reasoning — so the turn
            // goes back as the provider wrote it, not as rebuilt from text.
            // Calls read out of the text have no provider shape to replay,
            // so that turn is rebuilt, with the call text taken out.
            conversation.add(
                AiClient.ChatTurn(
                    AiClient.ChatRole.ASSISTANT,
                    if (turn.fromText) turn.text else completion.text,
                    toolCalls = turn.calls,
                    replay = if (turn.fromText) null else completion.replay,
                ),
            )
            for (result in results) {
                conversation.add(
                    AiClient.ChatTurn(AiClient.ChatRole.TOOL, result.content, toolResult = result),
                )
            }
            return
        }
        // The sentinel path has no tool role to use, so the exchange is two
        // ordinary messages: the model's own words played back verbatim —
        // sentinel included, so it can see what it asked for — and the answers
        // as the user message the protocol told it to expect.
        conversation.add(AiClient.ChatTurn(AiClient.ChatRole.ASSISTANT, completion.text))
        conversation.add(
            AiClient.ChatTurn(
                AiClient.ChatRole.USER,
                results.joinToString("\n\n") { AiToolProtocol.resultMessage(it) },
            ),
        )
    }

    private fun append(buffer: StringBuilder, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (buffer.isNotEmpty()) buffer.append("\n\n")
        buffer.append(trimmed)
    }

    private fun join(prefix: String, text: String): String {
        if (prefix.isBlank()) return text
        if (text.isBlank()) return prefix
        return prefix.trimEnd() + "\n\n" + text.trimStart()
    }
}
