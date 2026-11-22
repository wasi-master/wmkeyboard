package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.plugins.PluginManifest
import com.wasimaster.wmkeyboard.core.plugins.PluginPreviewSession
import com.wasimaster.wmkeyboard.core.plugins.PluginProviderRunner
import com.wasimaster.wmkeyboard.core.plugins.PluginTryRunner
import com.wasimaster.wmkeyboard.core.plugins.PluginWorkspace
import com.wasimaster.wmkeyboard.core.plugins.PythonTooling
import com.wasimaster.wmkeyboard.core.tools.PluginReplies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Something in plugin.json the Try pane can call: a provider, or a selection action. */
internal sealed interface TryTarget {
    val key: String

    data class Provider(val tool: String, val id: String, val name: String) : TryTarget {
        override val key: String get() = "provider:$tool/$id"
    }

    data class Action(val id: String, val name: String) : TryTarget {
        override val key: String get() = id
    }
}

/** What plugin.json offers to call, providers first. */
internal fun tryTargets(manifest: PluginManifest): List<TryTarget> =
    manifest.providers.map { TryTarget.Provider(it.tool, it.providerId, it.name.ifBlank { it.providerId }) } +
        manifest.actions.map { TryTarget.Action(it.id, it.name.ifBlank { it.id }) }

private val pretty = Json { prettyPrint = true }

/**
 * Calls the draft's providers and selection actions as the keyboard would:
 * a provider with the request its list sends, an action with selected text.
 * Shows what came back, whether the list can read it, and what was printed.
 */
@Composable
internal fun TryPane(
    draftId: String,
    manifest: PluginManifest,
    script: () -> String,
    workspace: PluginWorkspace,
    runner: PluginTryRunner,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targets = remember(manifest) { tryTargets(manifest) }
    if (targets.isEmpty()) {
        Text(
            stringResource(R.string.plugin_ide_try_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }
    var chosenKey by rememberSaveable(draftId) { mutableStateOf(targets.first().key) }
    val chosen = targets.firstOrNull { it.key == chosenKey } ?: targets.first()
    var input by rememberSaveable(chosen.key) { mutableStateOf(sampleInput(chosen)) }
    var outcome by remember(chosen.key) { mutableStateOf<PluginTryRunner.Outcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (target in targets) {
                FilterChip(
                    selected = target.key == chosen.key,
                    onClick = { chosenKey = target.key },
                    label = {
                        Text(
                            when (target) {
                                is TryTarget.Provider -> "${target.tool} · ${target.name}"
                                is TryTarget.Action -> target.name
                            },
                        )
                    },
                )
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = {
                Text(stringResource(if (chosen is TryTarget.Provider) R.string.plugin_ide_try_request_label else R.string.plugin_ide_try_text_label))
            },
            textStyle = TextStyle(fontFamily = CodeFontFamily, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            minLines = 2,
            maxLines = 8,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    val source = script()
                    val sent = input
                    if (chosen is TryTarget.Provider && runCatching { Json.parseToJsonElement(sent) as JsonObject }.isFailure) {
                        outcome = PluginTryRunner.Outcome(null, context.getString(R.string.plugin_ide_try_bad_json), null, emptyList(), 0)
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        outcome = withContext(Dispatchers.IO) {
                            runner.call(
                                plugin = PluginPreviewSession.draftPlugin(draftId, manifest),
                                script = source,
                                actionId = chosen.key,
                                input = sent,
                                storagePath = workspace.previewStorageFile(draftId)?.path,
                            )
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(R.string.plugin_ide_try_run_action)) }
            outcome?.let {
                Text(
                    stringResource(R.string.plugin_ide_try_took, it.millis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        outcome?.let { TryOutcome(context, chosen, input, it) }
    }
}

@Composable
private fun TryOutcome(context: Context, target: TryTarget, input: String, outcome: PluginTryRunner.Outcome) {
    val error = outcome.error
    if (error != null) {
        val words = when (error) {
            PluginTryRunner.NOT_READY -> stringResource(R.string.plugin_ide_try_not_ready)
            PluginTryRunner.TOO_LONG -> stringResource(R.string.plugin_ide_try_too_long)
            else -> error
        }
        Text(
            outcome.errorLine?.let { stringResource(R.string.plugin_ide_try_error_line, words, it) } ?: words,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    } else {
        val (shown, verdict) = when (target) {
            is TryTarget.Provider -> {
                val reply = providerReply(outcome.value)
                pretty.encodeToString(JsonElement.serializer(), reply) to providerVerdict(context, target.tool, reply, input)
            }
            is TryTarget.Action -> (outcome.value ?: "None") to null
        }
        verdict?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        }
        Text(
            stringResource(R.string.plugin_ide_try_reply_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(shown, fontFamily = CodeFontFamily, fontSize = 12.sp)
    }
    if (outcome.log.isNotEmpty()) {
        Text(
            stringResource(R.string.plugin_ide_console_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(outcome.log.joinToString("\n"), fontFamily = CodeFontFamily, fontSize = 12.sp)
    }
}

/** What to try [target] with first: its list's sample request, or a line of text. */
private fun sampleInput(target: TryTarget): String = when (target) {
    is TryTarget.Provider -> PythonTooling.providerTools.firstOrNull { it.tool == target.tool }?.sample ?: "{}"
    is TryTarget.Action -> "Hello, world"
}

/** The reply inside the prelude's `{"value": …}` envelope. */
private fun providerReply(value: String?): JsonElement {
    if (value == null) return JsonNull
    if (value.length > PluginProviderRunner.MAX_REPLY) return JsonPrimitive("…")
    val envelope = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull() ?: return JsonPrimitive(value)
    return envelope["value"] ?: JsonNull
}

/**
 * Whether the list [tool] can use [reply], read by the same code the list
 * reads a plugin's reply with: how many results, entries or pictures it found.
 */
internal fun providerVerdict(context: Context, tool: String, reply: JsonElement, request: String): String {
    if (reply is JsonNull) return context.getString(R.string.plugin_ide_try_nothing)
    val asked = runCatching { Json.parseToJsonElement(request) as? JsonObject }.getOrNull()
    fun field(name: String): String = (asked?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
    val found: Int? = runCatching {
        when (tool) {
            "search" -> if (field("kind") == "images") PluginReplies.images(reply).size else PluginReplies.webPage(reply, "").results.size
            "translate" -> if (PluginReplies.translation(reply) != null) 1 else 0
            "dictionary" -> PluginReplies.dictionary(reply, field("word")).size
            "synonyms" -> PluginReplies.synonymGroups(reply).sumOf { it.words.size }
            "vocab_autofill" -> if (PluginReplies.vocabWord(reply, field("word")) != null) 1 else 0
            "currency_fiat" -> if (PluginReplies.fiatRates(reply) != null) 1 else 0
            "currency_crypto" -> {
                val codes = (asked?.get("codes") as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
                PluginReplies.cryptoRates(reply, codes).size
            }
            "gif", "sticker" -> PluginReplies.media(reply, "try").size
            "ocr", "voice" -> if (PluginReplies.text(reply).isNullOrEmpty()) 0 else 1
            else -> null
        }
    }.getOrNull()
    return when (found) {
        null -> context.getString(R.string.plugin_ide_try_returned)
        0 -> context.getString(R.string.plugin_ide_try_unread)
        else -> context.resources.getQuantityString(R.plurals.plugin_ide_try_read, found, found)
    }
}
