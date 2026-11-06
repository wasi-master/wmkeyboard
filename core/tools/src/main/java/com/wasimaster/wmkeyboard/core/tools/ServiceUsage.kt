package com.wasimaster.wmkeyboard.core.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * What the paid services cost so far (#1135): Brave Search, Tavily and the
 * AI providers.
 *
 * Two kinds of number, never mixed: what this phone counted ([Record]'s
 * requests, tokens and credits this month), and what the service itself
 * last said ([Reported]): a quota header, an account endpoint's balance.
 * A service that reports nothing still gets the first, which is the only
 * honest number there is for it.
 *
 * Keyed by provider type id (`brave`, `tavily`, `anthropic`…), one record
 * for a kind however many keys it has. Kept in `filesDir/usage/services.json`
 * once [attach] names the folder; before that (and in unit tests) only in
 * memory.
 */
object ServiceUsage {

    @Serializable
    data class Record(
        /** The calendar month the counters are for, `2026-11`. A new month starts them again. */
        val month: String = "",
        val requests: Long = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        /** Credits as the service prices them: Tavily's one per basic search, two per advanced. */
        val credits: Long = 0,
        val reported: Reported? = null,
    )

    /** What the service said about its own budget, and when. */
    @Serializable
    data class Reported(
        val unit: Measure,
        /** Left to spend, in [unit]. Null when only [used] is known. */
        val remaining: Double? = null,
        /** The whole allowance, when the service named one. */
        val limit: Double? = null,
        /** Spent so far, when that is what the service reports instead. */
        val used: Double? = null,
        /** For [Measure.MONEY]: the currency code the service gave. */
        val currency: String = "",
        /** The window [limit] covers. */
        val window: Window = Window.UNKNOWN,
        /** When the allowance starts again, 0 when not said. */
        val resetAtMs: Long = 0,
        /** A plan's name, where the service gave one. */
        val plan: String = "",
        val atMs: Long = 0,
    )

    @Serializable
    enum class Measure { REQUESTS, TOKENS, CREDITS, MONEY }

    @Serializable
    enum class Window { MINUTE, MONTH, PLAN, UNKNOWN }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = MapSerializer(String.serializer(), Record.serializer())

    private val records = HashMap<String, Record>()
    private var file: File? = null
    private var loaded = false

    private val _version = MutableStateFlow(0)

    /** Bumps on every change, for a screen to re-read. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /**
     * The record a provider row counts under: its kind, plus the host of
     * the address it was given, if any. Two OpenAI-compatible services, or
     * a Privatemode proxy and the phone's own encryption, count apart.
     */
    fun key(typeId: String, url: String = ""): String {
        val address = url.trim()
        if (address.isEmpty()) return typeId
        val host = runCatching { java.net.URI(if ("://" in address) address else "https://$address").host }
            .getOrNull()?.lowercase()?.ifBlank { null } ?: address.lowercase()
        return "$typeId@$host"
    }

    /** Where to keep the counts: the app's files folder. Reads nothing until first used. */
    @Synchronized
    fun attach(filesDir: File) {
        file = File(filesDir, "usage/services.json")
        loaded = false
    }

    /** One more request to [service], with the tokens or credits it cost when known. */
    @Synchronized
    fun record(
        service: String,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        credits: Long = 0,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (service.isBlank()) return
        val current = currentLocked(service, nowMs, zone)
        records[service] = current.copy(
            requests = current.requests + 1,
            inputTokens = current.inputTokens + inputTokens.coerceAtLeast(0),
            outputTokens = current.outputTokens + outputTokens.coerceAtLeast(0),
            credits = current.credits + credits.coerceAtLeast(0),
        )
        saveLocked()
    }

    /** What the service said about its budget; replaces what it said before. */
    @Synchronized
    fun report(service: String, reported: Reported, zone: ZoneId = ZoneId.systemDefault()) {
        if (service.isBlank()) return
        records[service] = currentLocked(service, reported.atMs, zone).copy(reported = reported)
        saveLocked()
    }

    /** [service]'s record, with a past month's counters already started again. */
    @Synchronized
    fun get(
        service: String,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Record = currentLocked(service, nowMs, zone)

    /**
     * What is left of [service]'s monthly or plan allowance, as the service
     * last said it within the last month, for a panel to show beside its
     * name. Null when it never said, or said only a per-minute limit.
     */
    fun allowanceLeft(service: String, nowMs: Long = System.currentTimeMillis()): Long? {
        val reported = get(service, nowMs).reported ?: return null
        if (reported.unit != Measure.CREDITS && reported.unit != Measure.REQUESTS) return null
        if (reported.window != Window.MONTH && reported.window != Window.PLAN) return null
        if (nowMs - reported.atMs > STALE_AFTER_MS) return null
        return reported.remaining?.toLong()
    }

    private const val STALE_AFTER_MS = 31L * 24 * 3_600_000

    /** Forgets [service]'s counts and what it reported. */
    @Synchronized
    fun reset(service: String) {
        loadLocked()
        if (records.remove(service) != null) saveLocked()
    }

    /** For tests: forget everything and stop writing. */
    @Synchronized
    internal fun clearForTest() {
        records.clear()
        file = null
        loaded = true
    }

    private fun currentLocked(service: String, nowMs: Long, zone: ZoneId): Record {
        loadLocked()
        val month = monthOf(nowMs, zone)
        val stored = records[service] ?: return Record(month = month)
        return if (stored.month == month) stored else Record(month = month, reported = stored.reported)
    }

    internal fun monthOf(nowMs: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(nowMs).atZone(zone)
        return "%04d-%02d".format(date.year, date.monthValue)
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val text = file?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } ?: return
        runCatching { json.decodeFromString(serializer, text) }.getOrNull()?.let { records.putAll(it) }
    }

    private fun saveLocked() {
        _version.value++
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(json.encodeToString(serializer, records))
            temp.renameTo(target)
        }
    }

    // ---- reading what the services report ----

    /**
     * Brave Search's quota headers: each holds a comma-separated list, one
     * entry per window (`1, 15000` for a second and a month), so the last,
     * longest one is the month's. `X-RateLimit-Reset` counts seconds to go.
     */
    fun braveHeaders(headers: Map<String, String>, nowMs: Long): Reported? {
        val lookup = headers.mapKeys { it.key.lowercase() }
        val remaining = lookup["x-ratelimit-remaining"]?.lastNumber() ?: return null
        val limit = lookup["x-ratelimit-limit"]?.lastNumber()
        val reset = lookup["x-ratelimit-reset"]?.lastNumber()
        return Reported(
            unit = Measure.REQUESTS,
            remaining = remaining,
            limit = limit,
            window = Window.MONTH,
            resetAtMs = reset?.let { nowMs + (it * 1000).toLong() } ?: 0,
            atMs = nowMs,
        )
    }

    /** The header names [braveHeaders] reads, for a request to keep. */
    val BRAVE_HEADERS = setOf("X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset")

    /**
     * An AI service's rate-limit headers: Anthropic's
     * `anthropic-ratelimit-tokens-*` (reset as a timestamp), or the
     * `x-ratelimit-*-tokens` OpenAI, xAI, Groq and others send (reset as a
     * duration like `6m0s`). These are a per-minute allowance, not a
     * balance, and are reported as one.
     */
    fun aiHeaders(headers: Map<String, String>, nowMs: Long): Reported? {
        val lookup = headers.mapKeys { it.key.lowercase() }
        lookup["anthropic-ratelimit-tokens-remaining"]?.toDoubleOrNull()?.let { remaining ->
            return Reported(
                unit = Measure.TOKENS,
                remaining = remaining,
                limit = lookup["anthropic-ratelimit-tokens-limit"]?.toDoubleOrNull(),
                window = Window.MINUTE,
                resetAtMs = lookup["anthropic-ratelimit-tokens-reset"]?.let(::parseInstant) ?: 0,
                atMs = nowMs,
            )
        }
        val remaining = lookup["x-ratelimit-remaining-tokens"]?.toDoubleOrNull() ?: return null
        return Reported(
            unit = Measure.TOKENS,
            remaining = remaining,
            limit = lookup["x-ratelimit-limit-tokens"]?.toDoubleOrNull(),
            window = Window.MINUTE,
            resetAtMs = lookup["x-ratelimit-reset-tokens"]?.let(::parseDuration)?.let { nowMs + it } ?: 0,
            atMs = nowMs,
        )
    }

    /** The header names [aiHeaders] reads. */
    val AI_HEADERS = setOf(
        "anthropic-ratelimit-tokens-remaining", "anthropic-ratelimit-tokens-limit", "anthropic-ratelimit-tokens-reset",
        "x-ratelimit-remaining-tokens", "x-ratelimit-limit-tokens", "x-ratelimit-reset-tokens",
    )

    private fun String.lastNumber(): Double? = split(',').lastOrNull()?.trim()?.toDoubleOrNull()

    private fun parseInstant(text: String): Long? = try {
        OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private val DURATION_PART = Regex("""(\d+(?:\.\d+)?)(ms|h|m|s)""")

    /** `6m0s`, `1.5s`, `20ms` → milliseconds; null for anything else. */
    internal fun parseDuration(text: String): Long? {
        val parts = DURATION_PART.findAll(text.trim()).toList()
        if (parts.isEmpty() || parts.joinToString("") { it.value } != text.trim()) return null
        return parts.sumOf { part ->
            val value = part.groupValues[1].toDouble()
            when (part.groupValues[2]) {
                "h" -> value * 3_600_000
                "m" -> value * 60_000
                "s" -> value * 1000
                else -> value
            }
        }.toLong()
    }
}
