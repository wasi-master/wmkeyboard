package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmEngine
import com.wasimaster.wmkeyboard.core.localllm.LocalLlmStore
import com.wasimaster.wmkeyboard.core.localllm.VisionUnsupportedException
import com.wasimaster.wmkeyboard.core.net.NetworkWatcher
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.DataSaverStatus
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.stickers.CustomSticker
import com.wasimaster.wmkeyboard.core.stickers.StickerKeywords
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.StickerKeywordSuggestions
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.ime.R as ImeR

/** One sticker the sheet suggests keywords for, with the pack it sits in. */
internal data class StickerKeywordTarget(val packId: String, val packName: String, val sticker: CustomSticker)

/** Where one sticker's suggestions stand. */
private sealed interface Suggesting {
    data object Waiting : Suggesting
    data object Asking : Suggesting
    data class Done(val suggestions: List<String>, val sawPicture: Boolean) : Suggesting
    data class Failed(val message: String) : Suggesting
}

/**
 * The AI keyword review (#1158): the AI tool's provider in use is asked
 * about each sticker in [targets], one at a time, with its picture where
 * the provider can see one. Its suggestions come back as chips the user
 * ticks, beside a field for their own; only what is ticked or typed is
 * saved, onto the keywords the sticker had. Nothing is asked until Start.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun StickerKeywordSheet(
    targets: List<StickerKeywordTarget>,
    onDismiss: () -> Unit,
    onSaved: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StickerPackStore.get(context) }
    val loader = rememberMediaImageLoader()
    var settings by remember { mutableStateOf<KeyboardSettings?>(null) }
    LaunchedEffect(Unit) { settings = SettingsRepository(context.applicationContext).settings.first() }

    var instruction by remember { mutableStateOf("") }
    val states = remember { mutableStateMapOf<String, Suggesting>() }
    val chosen = remember { mutableStateMapOf<String, Set<String>>() }
    val own = remember { mutableStateMapOf<String, String>() }
    var job by remember { mutableStateOf<Job?>(null) }
    val running = job?.isActive == true

    ModalBottomSheet(onDismissRequest = {
        job?.cancel()
        onDismiss()
    }) {
        val current = settings
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.import_sticker_ai_title), style = MaterialTheme.typography.titleLarge)
            val ready = current != null && aiReady(context, current)
            Text(
                when {
                    current == null -> ""
                    !ready -> stringResource(R.string.import_sticker_ai_not_set_up)
                    else -> stringResource(
                        if (seesPictures(current)) R.string.import_sticker_ai_cost_pictures else R.string.import_sticker_ai_cost_words,
                        stringResource(current.ai.provider.labelRes),
                        targets.size,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                label = { Text(stringResource(R.string.import_sticker_ai_instruction_label)) },
                supportingText = { Text(stringResource(R.string.import_sticker_ai_instruction_hint)) },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                if (running) {
                    TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.import_sticker_ai_stop)) }
                } else {
                    Button(
                        enabled = ready,
                        onClick = {
                            val live = current ?: return@Button
                            job = scope.launch {
                                for (target in targets) {
                                    if (!isActive) break
                                    if (states[target.sticker.id] is Suggesting.Done) continue
                                    states[target.sticker.id] = Suggesting.Asking
                                    states[target.sticker.id] = withContext(Dispatchers.IO) {
                                        try {
                                            suggestFor(context, live, store, target, instruction)
                                        } catch (e: IOException) {
                                            Suggesting.Failed(ToolHttp.friendlyMessage(context, e))
                                        }
                                    }
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.import_sticker_ai_start)) }
                }
                val picks = targets.count { !chosen[it.sticker.id].isNullOrEmpty() || own[it.sticker.id].orEmpty().isNotBlank() }
                TextButton(
                    enabled = picks > 0 && !running,
                    onClick = {
                        var saved = 0
                        for (target in targets) {
                            val ticked = chosen[target.sticker.id].orEmpty()
                            val typed = StickerKeywords.parse(own[target.sticker.id].orEmpty())
                            if (ticked.isEmpty() && typed.isEmpty()) continue
                            val keywords = StickerKeywordSuggestions.merged(target.sticker.keywords, ticked.toList(), typed)
                            store.updateSticker(target.packId, target.sticker.id, target.sticker.name, keywords)
                            saved++
                        }
                        onSaved(saved)
                    },
                ) { Text(pluralStringResource(R.plurals.import_sticker_ai_save, picks, picks)) }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(targets, key = { it.sticker.id }) { target ->
                    val id = target.sticker.id
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        AsyncImage(
                            model = store.fileFor(target.packId, target.sticker),
                            contentDescription = target.sticker.name.ifBlank { null },
                            imageLoader = loader,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(64.dp),
                        )
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(
                                target.sticker.name.ifBlank { target.packName },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (target.sticker.keywords.isNotEmpty()) {
                                Text(
                                    StickerKeywords.format(target.sticker.keywords),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when (val state = states[id] ?: Suggesting.Waiting) {
                                Suggesting.Waiting -> Unit
                                Suggesting.Asking -> CircularProgressIndicator(Modifier.padding(top = 6.dp).size(20.dp), strokeWidth = 2.dp)
                                is Suggesting.Failed -> Text(
                                    state.message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                is Suggesting.Done -> {
                                    if (state.suggestions.isEmpty()) {
                                        Text(stringResource(R.string.import_sticker_ai_nothing), style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (!state.sawPicture) {
                                        Text(
                                            stringResource(R.string.import_sticker_ai_no_picture),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        for (word in state.suggestions) {
                                            val on = word in chosen[id].orEmpty()
                                            FilterChip(
                                                selected = on,
                                                onClick = { chosen[id] = chosen[id].orEmpty().let { if (on) it - word else it + word } },
                                                label = { Text(word) },
                                            )
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = own[id].orEmpty(),
                                onValueChange = { own[id] = it },
                                label = { Text(stringResource(R.string.import_sticker_ai_own_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            TextButton(onClick = {
                job?.cancel()
                onDismiss()
            }, modifier = Modifier.padding(bottom = 16.dp)) { Text(stringResource(CommonR.string.common_cancel)) }
        }
    }
}

/** Whether the AI tool can answer at all: a provider set up, or a model on the phone. */
private fun aiReady(context: Context, settings: KeyboardSettings): Boolean =
    if (settings.ai.provider == AiProvider.ON_DEVICE) {
        BuildConfig.ENABLE_LOCAL_LLM && localModel(context, settings) != null
    } else {
        AiClient.isConfigured(settings.ai)
    }

/** Whether the provider in use is sent the pictures. The on-device model tries, and says when it cannot. */
private fun seesPictures(settings: KeyboardSettings): Boolean =
    settings.ai.provider == AiProvider.ON_DEVICE || AiClient.supportsImages(settings.ai.provider)

private fun localModel(context: Context, settings: KeyboardSettings): File? =
    LocalLlmStore.selectedModelFile(context.filesDir, settings.ai.localModelId)
        ?: LocalLlmStore.soleDownloadedId(context.filesDir)?.let { LocalLlmStore.selectedModelFile(context.filesDir, it) }

/** The language names the model writes keywords in: the keyboard's own languages, the first being the main one. */
private fun languageNames(settings: KeyboardSettings): List<String> =
    settings.enabledLanguages.map { it.englishName.ifBlank { it.id } }.distinct()

/**
 * Asks about one sticker. Blocking. Data saver is asked the way every
 * cloud AI request asks it; nothing here grants itself past a "no".
 */
private fun suggestFor(
    context: Context,
    settings: KeyboardSettings,
    store: StickerPackStore,
    target: StickerKeywordTarget,
    instruction: String,
): Suggesting {
    val picture = store.fileFor(target.packId, target.sticker)?.let(::stillPng)
    val base = StickerKeywordSuggestions.Request(
        packName = target.packName,
        title = target.sticker.name,
        existing = target.sticker.keywords,
        languages = languageNames(settings),
        withPicture = picture != null && seesPictures(settings),
        instruction = instruction,
    )
    if (settings.ai.provider == AiProvider.ON_DEVICE) {
        val model = localModel(context, settings) ?: throw IOException(context.getString(ImeR.string.ime_ai_error_model_missing))
        val ai = settings.ai
        val (answer, saw) = if (base.withPicture && picture != null) {
            try {
                LocalLlmEngine.generateWithImages(
                    context.applicationContext, model, ai.localBackend, ai.localContextTokens,
                    StickerKeywordSuggestions.systemPrompt(base), StickerKeywordSuggestions.userPrompt(base), listOf(picture),
                ) to true
            } catch (_: VisionUnsupportedException) {
                // A model without eyes still knows the words.
                val words = base.copy(withPicture = false)
                LocalLlmEngine.generate(
                    context.applicationContext, model, ai.localBackend, ai.localContextTokens,
                    StickerKeywordSuggestions.systemPrompt(words), StickerKeywordSuggestions.userPrompt(words),
                ) to false
            }
        } else {
            LocalLlmEngine.generate(
                context.applicationContext, model, ai.localBackend, ai.localContextTokens,
                StickerKeywordSuggestions.systemPrompt(base), StickerKeywordSuggestions.userPrompt(base),
            ) to false
        }
        return Suggesting.Done(StickerKeywordSuggestions.parse(answer, target.sticker.keywords), saw)
    }
    val network = NetworkWatcher(context.applicationContext).apply { refresh() }.state.value
    val decision = DataSaverStatus(
        active = settings.dataSaver.appliesTo(network),
        settings = settings.dataSaver,
        grants = emptySet(),
    ).decide(MeteredFeature.CLOUD_AI)
    if (decision != MeteredDecision.ALLOWED) throw IOException(context.getString(ImeR.string.ime_ai_error_metered_blocked))
    val images = if (base.withPicture && picture != null) {
        listOf(AiClient.AiImage("image/png", Base64.encodeToString(picture, Base64.NO_WRAP)))
    } else {
        emptyList()
    }
    val completion = AiClient.completeStreaming(
        config = AiClient.config(settings.ai),
        system = StickerKeywordSuggestions.systemPrompt(base),
        turns = listOf(AiClient.ChatTurn(AiClient.ChatRole.USER, StickerKeywordSuggestions.userPrompt(base), images = images)),
        maxTokens = AiClient.effectiveMaxTokens(settings.ai),
        onPhase = {},
        onPartial = {},
    )
    return Suggesting.Done(StickerKeywordSuggestions.parse(completion.text, target.sticker.keywords), images.isNotEmpty())
}

/** The sticker's first frame as a PNG no wider than 512, which every provider that reads pictures takes. */
private fun stillPng(file: File): ByteArray? = runCatching {
    val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
    val scale = MAX_PICTURE.toFloat() / maxOf(bitmap.width, bitmap.height)
    val sized = if (scale < 1f) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    } else {
        bitmap
    }
    ByteArrayOutputStream().use { out ->
        sized.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}.getOrNull()

private const val MAX_PICTURE = 512
