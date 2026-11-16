package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DataUsage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QueryStats
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.RestartAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.PluginSymbols
import com.wasimaster.wmkeyboard.core.providers.ProviderEnvironment
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.resolveProviders
import com.wasimaster.wmkeyboard.core.tools.ServiceUsage
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.UsageChecks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * Where [row]'s usage is counted (#1135), or null for a kind that costs
 * nothing to ask: everything but the search services that bill per search
 * and the AI services, the phone's own model aside.
 */
internal fun usageKeyOf(row: ResolvedProvider, tool: ProviderTool): String? = when {
    row.typeId == ProviderTypes.BRAVE.id -> "brave"
    row.typeId == ProviderTypes.TAVILY.id -> "tavily"
    // DeepL's character allowance (#1382); a proxy of the user's own has none to report.
    row.typeId == ProviderTypes.DEEPL.id && row.apiKey.isNotBlank() -> "deepl"
    tool == ProviderTool.AI && row.typeId != ProviderTypes.ON_DEVICE_AI.id -> ServiceUsage.key(row.typeId, row.url)
    else -> null
}

/**
 * The provider screen's Usage group (#1135): what this phone counted this
 * month, what the service last said about its own budget, and, for the
 * services with an account endpoint, a button that asks.
 */
@Composable
internal fun ProviderUsageGroup(row: ResolvedProvider, tool: ProviderTool, onNavigate: (String) -> Unit = {}) {
    val key = usageKeyOf(row, tool) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by ServiceUsage.version.collectAsState()
    val record = remember(version, key) { ServiceUsage.get(key) }
    // Brave's prepaid plans report no balance (#1385): the free credit, less this phone's count.
    val estimate = remember(version, key) { ServiceUsage.estimatedFreeLeft(key) }
    var checking by remember { mutableStateOf(false) }
    val canCheck = UsageChecks.canCheck(row.typeId, row.url) && row.apiKey.isNotBlank()
    SettingsGroup(title = stringResource(R.string.providers_usage_title)) {
        item {
            WmRow(
                title = stringResource(R.string.providers_usage_month_title),
                subtitle = countedLine(context, record),
                icon = Icons.Outlined.DataUsage,
            )
        }
        item(visible = record.reported != null) {
            record.reported?.let { reported ->
                WmRow(
                    title = stringResource(R.string.providers_usage_reported_title),
                    subtitle = reportedLine(context, reported) +
                        // The build's own Brave key is shared by everyone who has the app.
                        if (row.typeId == ProviderTypes.BRAVE.id && row.credential?.apiKey.isNullOrBlank()) {
                            "\n" + context.getString(R.string.providers_usage_shared_key)
                        } else {
                            ""
                        },
                    icon = Icons.Outlined.QueryStats,
                )
            }
        }
        item(visible = estimate != null) {
            estimate?.let {
                WmRow(
                    title = stringResource(R.string.providers_usage_estimate_title),
                    subtitle = estimateLine(context, key, it),
                    icon = PluginSymbols.Savings,
                )
            }
        }
        if (canCheck) {
            item {
                WmRow(
                    title = stringResource(R.string.providers_usage_check_title),
                    subtitle = stringResource(
                        if (checking) R.string.providers_usage_checking else R.string.providers_usage_check_subtitle,
                    ),
                    icon = Icons.Outlined.Refresh,
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val failure = withContext(Dispatchers.IO) {
                                runCatching { UsageChecks.check(row.typeId, row.url, row.apiKey, key) }.exceptionOrNull()
                            }
                            checking = false
                            if (failure != null) {
                                Toast.makeText(context, ToolHttp.friendlyMessage(context, failure), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            }
        }
        item(visible = record.requests > 0 || record.reported != null) {
            WmRow(
                title = stringResource(R.string.providers_usage_reset_title),
                subtitle = stringResource(R.string.providers_usage_reset_subtitle),
                icon = Icons.Outlined.RestartAlt,
                onClick = { ServiceUsage.reset(key) },
            )
        }
        // Every paid or limited service on one screen (#1382).
        item {
            NavRow(
                title = stringResource(R.string.providers_credits_title),
                subtitle = stringResource(R.string.providers_credits_link_subtitle),
                icon = PluginSymbols.Savings,
            ) { onNavigate(PROVIDER_CREDITS_ROUTE) }
        }
    }
}

/** "About 840 of 1,000 free searches left this month, by this phone's count". */
internal fun estimateLine(context: Context, key: String, left: Long): String {
    val number = NumberFormat.getIntegerInstance()
    val free = ServiceUsage.FREE_MONTHLY[key] ?: left
    return context.getString(R.string.providers_usage_estimate, number.format(left), number.format(free))
}

/** The overview of every paid or limited service (#1382), from the keyboard's credit readout too. */
internal const val PROVIDER_CREDITS_ROUTE = "provider_credits"

/** One service on the overview: where it is counted, and the list row it is set up on. */
private data class CreditRow(val key: String, val tool: ProviderTool, val row: ResolvedProvider)

/**
 * Every row, on every tool's list, that is switched on and costs something
 * to ask, once per usage key: a key shared by two tools (Brave for search
 * and for Answers) is one balance.
 */
private fun creditRows(settings: KeyboardSettings): List<CreditRow> {
    val env = ProviderEnvironment.current()
    val seen = HashSet<String>()
    return ProviderTool.entries.flatMap { tool ->
        settings.resolveProviders(tool, env, all = true)
            .filter { it.entry.enabled && it.type != null }
            .mapNotNull { row -> usageKeyOf(row, tool)?.let { CreditRow(it, tool, row) } }
    }.filter { seen.add(it.key) }
}

/**
 * The overview (#1382): for each paid or limited service, what it last said
 * is left, the phone's estimate where it says nothing, and this month's
 * count, grouped by the tool it is set up on. A tap opens the provider's own
 * screen; Check all asks every service that has an account endpoint.
 */
@Composable
internal fun ProviderCreditsScreen(settings: LiveSettings, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by ServiceUsage.version.collectAsState()
    val rows = settings.watch { creditRows(it) }
    var checking by remember { mutableStateOf(false) }
    if (rows.isEmpty()) {
        StateBanner(stringResource(R.string.providers_credits_empty))
        return
    }
    val checkable = rows.filter { UsageChecks.canCheck(it.row.typeId, it.row.url) && it.row.apiKey.isNotBlank() }
    if (checkable.isNotEmpty()) {
        SettingsGroup {
            item {
                WmRow(
                    title = stringResource(R.string.providers_credits_check_all_title),
                    subtitle = stringResource(
                        if (checking) R.string.providers_usage_checking else R.string.providers_credits_check_all_subtitle,
                    ),
                    icon = Icons.Outlined.Refresh,
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val failures = withContext(Dispatchers.IO) {
                                checkable.mapNotNull { credit ->
                                    runCatching { UsageChecks.check(credit.row.typeId, credit.row.url, credit.row.apiKey, credit.key) }
                                        .exceptionOrNull()?.let { providerTitle(context, credit.row) to it }
                                }
                            }
                            checking = false
                            failures.firstOrNull()?.let { (name, failure) ->
                                Toast.makeText(context, "$name: " + ToolHttp.friendlyMessage(context, failure), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            }
        }
    }
    for ((tool, group) in rows.groupBy { it.tool }) {
        SettingsGroup(title = stringResource(tool.labelRes)) {
            for (credit in group) {
                item {
                    val record = remember(version, credit.key) { ServiceUsage.get(credit.key) }
                    val estimate = remember(version, credit.key) { ServiceUsage.estimatedFreeLeft(credit.key) }
                    val lines = listOfNotNull(
                        record.reported?.let { reportedLine(context, it) },
                        estimate?.let { estimateLine(context, credit.key, it) },
                        context.getString(R.string.providers_credits_counted, countedLine(context, record)),
                        if (credit.row.usesBuiltInKey) context.getString(R.string.providers_usage_shared_key) else null,
                    )
                    NavRow(
                        title = providerTitle(context, credit.row),
                        subtitle = lines.joinToString("\n"),
                        icon = Icons.Outlined.DataUsage,
                    ) { onNavigate(providerRoute(credit.tool, credit.row.key)) }
                }
            }
        }
    }
}

/** "12 requests · 4,210 tokens in, 1,980 out", or that nothing was sent yet. */
internal fun countedLine(context: Context, record: ServiceUsage.Record): String {
    if (record.requests == 0L) return context.getString(R.string.providers_usage_none)
    val number = NumberFormat.getIntegerInstance()
    val parts = buildList {
        add(context.resources.getQuantityString(R.plurals.providers_usage_requests, record.requests.toInt(), number.format(record.requests)))
        if (record.credits > 0) {
            add(context.resources.getQuantityString(R.plurals.providers_usage_credits, record.credits.toInt(), number.format(record.credits)))
        }
        if (record.inputTokens > 0 || record.outputTokens > 0) {
            add(context.getString(R.string.providers_usage_tokens, number.format(record.inputTokens), number.format(record.outputTokens)))
        }
    }
    return parts.joinToString(" · ")
}

/** "812 of 1,000 credits left this month · checked 5 minutes ago". */
internal fun reportedLine(context: Context, reported: ServiceUsage.Reported): String {
    fun amount(value: Double): String = when (reported.unit) {
        ServiceUsage.Measure.MONEY -> NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(value).let { if (reported.currency.isBlank()) it else "${reported.currency} $it" }
        else -> NumberFormat.getIntegerInstance().format(value.toLong())
    }
    // A balance is its own unit: "USD 8.80 left", not "8.80 balance left".
    val unit = when (reported.unit) {
        ServiceUsage.Measure.REQUESTS -> context.getString(R.string.providers_usage_unit_requests)
        ServiceUsage.Measure.TOKENS -> context.getString(R.string.providers_usage_unit_tokens)
        ServiceUsage.Measure.CREDITS -> context.getString(R.string.providers_usage_unit_credits)
        ServiceUsage.Measure.CHARACTERS -> context.getString(R.string.providers_usage_unit_characters)
        ServiceUsage.Measure.MONEY -> ""
    }
    val remaining = reported.remaining
    val limit = reported.limit
    val used = reported.used
    val main = when {
        remaining != null && limit != null -> context.getString(R.string.providers_usage_left_of, amount(remaining), amount(limit), unit)
        remaining != null -> context.getString(R.string.providers_usage_left, amount(remaining), unit)
        used != null -> context.getString(R.string.providers_usage_used, amount(used), unit)
        // Brave's prepaid plans: no monthly cap, and the balance is not in the headers (#1385).
        reported.uncapped -> return listOfNotNull(
            context.getString(R.string.providers_usage_uncapped),
            context.getString(
                R.string.providers_usage_seen,
                DateUtils.getRelativeTimeSpanString(reported.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
            ),
        ).joinToString(" · ")
        else -> context.getString(R.string.providers_usage_none)
    }.replace("  ", " ")
    val window = when (reported.window) {
        ServiceUsage.Window.MINUTE -> context.getString(R.string.providers_usage_window_minute)
        ServiceUsage.Window.MONTH -> context.getString(R.string.providers_usage_window_month)
        ServiceUsage.Window.PLAN -> reported.plan.takeIf { it.isNotBlank() }
            ?.let { context.getString(R.string.providers_usage_window_plan_named, it) }
            ?: context.getString(R.string.providers_usage_window_plan)
        ServiceUsage.Window.UNKNOWN -> null
    }
    val reset = reported.resetAtMs.takeIf { it > System.currentTimeMillis() }?.let {
        context.getString(R.string.providers_usage_resets, DateUtils.getRelativeTimeSpanString(it).toString())
    }
    val seen = context.getString(
        R.string.providers_usage_seen,
        DateUtils.getRelativeTimeSpanString(reported.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
    )
    return listOfNotNull(listOfNotNull(main, window).joinToString(" "), reset, seen).joinToString(" · ")
}
