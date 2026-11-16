package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One step the AI proposes for a to-do task (#1388), and how long it thinks it takes. */
data class TodoAiStep(val text: String, val minutes: Int?)

/**
 * What one answer about a to-do task proposes (#1388): the words to show,
 * a clearer wording for the task, the steps to split it into, and when the
 * user said they want to start. Nothing here changes the list: the panel
 * shows it, and each part lands only when the user presses its button.
 */
data class TodoAiPlan(
    /** The answer as the user reads it, with the JSON taken out. */
    val reply: String,
    val rewrite: String? = null,
    val steps: List<TodoAiStep> = emptyList(),
    /** `YYYY-MM-DDTHH:MM` or `YYYY-MM-DD`, as the model wrote it. */
    val start: String? = null,
) {
    /** Whether there is anything to add or change. */
    val proposes: Boolean get() = rewrite != null || steps.isNotEmpty()

    /** Every step's minutes, a step with none counting [TodoAiPlans.DEFAULT_STEP_MINUTES]. */
    val totalMinutes: Int get() = steps.sumOf { it.minutes ?: TodoAiPlans.DEFAULT_STEP_MINUTES }
}

/**
 * The To-do tool's task helper (#1388): the instructions a model gets for one
 * task, and the reading of what it answers.
 *
 * The model is asked to end any proposal with one JSON object. It may be
 * fenced or bare, and a small on-device model's JSON is often not quite JSON,
 * so it is read with [AiLenientJson]. An answer with no readable object is
 * still an answer: its words show, and it proposes nothing.
 */
object TodoAiPlans {

    /** The most steps one proposal adds. */
    const val MAX_STEPS = 12

    /** What a step with no estimate is booked for on the calendar. */
    const val DEFAULT_STEP_MINUTES = 25

    /** The longest estimate kept for one step: a day's work. */
    private const val MAX_STEP_MINUTES = 600

    /** The longest a step's or a task's words are kept; the list's own limit. */
    private const val MAX_TEXT = 500

    /**
     * The system prompt for a conversation about [task] alone. [steps] are
     * the steps it has now, [today] the date as `YYYY-MM-DD, Weekday`, so
     * "tomorrow at nine" means something.
     */
    fun system(task: String, steps: List<String>, today: String): String = buildString {
        append("You help the user with one task from their to-do list, and only this one: \"")
        append(task.trim()).append("\".\n")
        if (steps.isNotEmpty()) {
            append("It already has these steps:\n")
            for (step in steps) append("- ").append(step.trim()).append('\n')
        }
        append("Today is ").append(today).append(".\n")
        append(
            "Talk with the user about it, briefly, in the language they write in. " +
                "You can split it into small, concrete steps that each take one sitting, " +
                "rewrite it so it is clear and doable, and estimate how many minutes each step takes. " +
                "Ask one short question if you need to know more.\n" +
                "You cannot change the list yourself. The user sees what you propose and adds it with a button, " +
                "so never say you added, saved or scheduled anything.\n" +
                "Whenever you propose steps or a new wording, end your reply with one JSON object in a ```json block:\n" +
                "```json\n" +
                "{\"rewrite\": \"the task, reworded\", \"steps\": [{\"text\": \"a step\", \"minutes\": 15}], " +
                "\"start\": \"YYYY-MM-DDTHH:MM\"}\n" +
                "```\n" +
                "Leave out any field you are not proposing. Give \"start\" only when the user said when they want to begin. " +
                "Steps are new ones to add, not the ones the task has already. " +
                "Never mention, change or plan any other task.",
        )
    }

    /** Today as [system] wants it, in [zone]. */
    fun today(now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone, Locale.ROOT).apply { timeInMillis = now }
        val day = c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.ENGLISH).orEmpty()
        return String.format(
            Locale.ROOT, "%04d-%02d-%02d, %s",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), day,
        )
    }

    /**
     * What a model's [raw] answer says and proposes. Its reasoning is dropped;
     * a rewrite equal to [task] is no rewrite.
     */
    fun parse(raw: String, task: String): TodoAiPlan {
        val text = AiThinking.stripped(raw)
        val found = lastObject(text)
        val json = found?.let { AiLenientJson.parse(it.json) } as? JsonObject
        val reply = (if (found != null && json != null) text.removeRange(found.range) else text).trim()
        if (json == null) return TodoAiPlan(reply)
        val rewrite = json.string("rewrite")?.let(::clean)?.takeIf { !it.equals(task.trim(), ignoreCase = true) }
        val steps = (json["steps"] as? JsonArray).orEmpty().mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.let(::clean)?.let { TodoAiStep(it, null) }
                is JsonObject -> {
                    val words = (element.string("text") ?: element.string("title") ?: element.string("step"))
                        ?.let(::clean) ?: return@mapNotNull null
                    val minutes = ((element["minutes"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() })
                        ?.toInt()?.takeIf { it > 0 }?.coerceAtMost(MAX_STEP_MINUTES)
                    TodoAiStep(words, minutes)
                }
                else -> null
            }
        }.distinctBy { it.text.lowercase() }.take(MAX_STEPS)
        val start = json.string("start")?.takeIf { AiCalendarArgs.parse(it) != null }
        return TodoAiPlan(reply, rewrite, steps, start)
    }

    /**
     * [partial] as it streams, without the JSON being written at its end:
     * the words up to a ```json fence, or up to a line that opens an object.
     */
    fun visible(partial: String): String {
        val text = AiThinking.stripped(partial)
        val fence = text.indexOf("```")
        val bare = Regex("(^|\\n)\\s*\\{").find(text)?.range?.first
        val cut = listOfNotNull(fence.takeIf { it >= 0 }, bare).minOrNull() ?: return text
        return text.substring(0, cut).trim()
    }

    /**
     * When each step runs, back to back from [start], as (begin, end) pairs:
     * the calendar's events for a planned task.
     */
    fun schedule(steps: List<TodoAiStep>, start: Long): List<Pair<Long, Long>> {
        var at = start
        return steps.map { step ->
            val end = at + (step.minutes ?: DEFAULT_STEP_MINUTES) * MINUTE_MS
            (at to end).also { at = end }
        }
    }

    /** The next quarter hour after [now]: where a plan with no start of its own begins. */
    fun nextQuarter(now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val quarter = 15 * MINUTE_MS
        val offset = zone.getOffset(now)
        val local = now + offset
        return (local / quarter + 1) * quarter - offset
    }

    /** [millis] as `YYYY-MM-DDTHH:MM` in [zone], the form the calendar tool takes. */
    fun moment(millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone, Locale.ROOT).apply { timeInMillis = millis }
        return String.format(
            Locale.ROOT, "%04d-%02d-%02dT%02d:%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
        )
    }

    private const val MINUTE_MS = 60_000L

    private fun clean(text: String): String? =
        text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().trimStart('-', '*', '•', ' ')
            .take(MAX_TEXT).takeIf { it.isNotBlank() }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private class Found(val json: String, val range: IntRange)

    /**
     * The last JSON object in [text] that names a proposal: a ```json fence's
     * body (the fence goes with it), else the last balanced `{…}` holding
     * "steps" or "rewrite".
     */
    private fun lastObject(text: String): Found? {
        val fence = Regex("```(?:json|JSON)?\\s*\\n?([\\s\\S]*?)```").findAll(text).lastOrNull { match ->
            match.groupValues[1].contains('{')
        }
        if (fence != null) return Found(fence.groupValues[1].trim(), fence.range)
        var end = text.lastIndexOf('}')
        while (end >= 0) {
            val start = openingBrace(text, end)
            if (start != null) {
                val body = text.substring(start, end + 1)
                if (body.contains("\"steps\"") || body.contains("\"rewrite\"")) return Found(body, start..end)
            }
            end = text.lastIndexOf('}', end - 1)
        }
        return null
    }

    /** Where the `{` that [close] closes opens, skipping braces inside strings. */
    private fun openingBrace(text: String, close: Int): Int? {
        var depth = 0
        var i = close
        var inString = false
        while (i >= 0) {
            val c = text[i]
            if (c == '"' && (i == 0 || text[i - 1] != '\\')) inString = !inString
            if (!inString) {
                if (c == '}') depth++
                if (c == '{') {
                    depth--
                    if (depth == 0) return i
                }
            }
            i--
        }
        return null
    }
}
