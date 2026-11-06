package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The services that will say what is left on an account when asked
 * (#1135), asked only when the user presses Check on a provider's screen:
 * Tavily's `/usage`, DeepSeek's `/user/balance` and OpenRouter's `/key`.
 * Brave and the AI services' rate limits come in the headers of ordinary
 * requests instead ([ServiceUsage.braveHeaders], [ServiceUsage.aiHeaders]);
 * the rest report nothing at all.
 */
object UsageChecks {

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether a row of [typeId] at [url] has an account to ask. */
    fun canCheck(typeId: String, url: String): Boolean = when (typeId) {
        TAVILY, DEEPSEEK -> true
        OPENAI_COMPATIBLE -> isOpenRouter(url)
        else -> false
    }

    /**
     * Asks and files the answer under [usageKey]. Blocking; throws when the
     * service cannot be reached or refuses the key. Null for a kind with no
     * account endpoint.
     */
    fun check(typeId: String, url: String, apiKey: String, usageKey: String, nowMs: Long = System.currentTimeMillis()): ServiceUsage.Reported? {
        val reported = when (typeId) {
            TAVILY -> parseTavily(
                ToolHttp.get(
                    ServiceEndpoints.base(ServiceEndpoint.TAVILY) + "/usage",
                    headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
                    source = NetSource.WEB_SEARCH,
                    route = "/usage",
                ),
                nowMs,
            )
            DEEPSEEK -> parseDeepSeek(
                ToolHttp.get(
                    ServiceEndpoints.base(ServiceEndpoint.DEEPSEEK) + "/user/balance",
                    headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
                    source = NetSource.AI,
                    route = "/user/balance",
                ),
                nowMs,
            )
            OPENAI_COMPATIBLE -> if (isOpenRouter(url)) {
                parseOpenRouter(
                    ToolHttp.get(
                        "https://openrouter.ai/api/v1/key",
                        headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
                        source = NetSource.AI,
                        route = "/api/v1/key",
                    ),
                    nowMs,
                )
            } else {
                null
            }
            else -> null
        } ?: return null
        ServiceUsage.report(usageKey, reported)
        return reported
    }

    /**
     * The key's own limit when it has one, else the account's plan. Plan
     * credits and pay-as-you-go credits are separate allowances; the plan's
     * is the one that resets each month.
     */
    internal fun parseTavily(body: String, nowMs: Long): ServiceUsage.Reported? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val key = root["key"] as? JsonObject
        val account = root["account"] as? JsonObject
        val keyLimit = key?.number("limit")
        return if (keyLimit != null && keyLimit > 0) {
            val used = key.number("usage") ?: 0.0
            ServiceUsage.Reported(
                unit = ServiceUsage.Measure.CREDITS, remaining = (keyLimit - used).coerceAtLeast(0.0),
                limit = keyLimit, used = used, window = ServiceUsage.Window.PLAN,
                plan = account?.text("current_plan").orEmpty(), atMs = nowMs,
            )
        } else {
            val limit = account?.number("plan_limit") ?: return null
            val used = account.number("plan_usage") ?: 0.0
            ServiceUsage.Reported(
                unit = ServiceUsage.Measure.CREDITS, remaining = (limit - used).coerceAtLeast(0.0),
                limit = limit, used = used, window = ServiceUsage.Window.MONTH,
                plan = account.text("current_plan"), atMs = nowMs,
            )
        }
    }

    /** `{"is_available":true,"balance_infos":[{"currency":"USD","total_balance":"110.00",…}]}`. */
    internal fun parseDeepSeek(body: String, nowMs: Long): ServiceUsage.Reported? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val first = (root["balance_infos"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val balance = first.number("total_balance") ?: return null
        return ServiceUsage.Reported(
            unit = ServiceUsage.Measure.MONEY, remaining = balance,
            currency = first.text("currency"), atMs = nowMs,
        )
    }

    /** `{"data":{"usage":1.2,"limit":10,"limit_remaining":8.8,…}}`, in US dollars; a key with no limit has null. */
    internal fun parseOpenRouter(body: String, nowMs: Long): ServiceUsage.Reported? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val data = root["data"] as? JsonObject ?: return null
        return ServiceUsage.Reported(
            unit = ServiceUsage.Measure.MONEY,
            remaining = data.number("limit_remaining"),
            limit = data.number("limit"),
            used = data.number("usage"),
            currency = "USD",
            atMs = nowMs,
        )
    }

    private fun isOpenRouter(url: String): Boolean =
        runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase()?.let { it == "openrouter.ai" || it.endsWith(".openrouter.ai") } == true

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private const val TAVILY = "tavily"
    private const val DEEPSEEK = "deepseek"
    private const val OPENAI_COMPATIBLE = "openai_compatible"
}
