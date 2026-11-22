package com.wasimaster.wmkeyboard.app

import android.os.StatFs
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CloudDownload
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCustomModels
import com.wasimaster.wmkeyboard.core.voice.executorch.HuggingFaceModels
import com.wasimaster.wmkeyboard.core.voice.executorch.HuggingFaceModels.Found
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCustomModels
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperDownloadManager
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A speech model brought straight from a Hugging Face repository (#1437), for
 * the engines that take models of the user's own: Qwen3-ASR, Sherpa and
 * Whisper's whisper.cpp. The screen asks for the repository, lists it, says
 * what it found, and hands the download to [HuggingFaceImports], which
 * outlives the screen; the importers that take picked files then check and
 * install what came.
 */
internal enum class HfTarget {
    QWEN3, SHERPA, WHISPER;

    fun accepts(found: Found): Boolean = when (this) {
        QWEN3 -> found is Found.Qwen3
        SHERPA -> found is Found.Sherpa
        WHISPER -> found is Found.Whisper
    }
}

/** The one download of a repository's model at a time, and what became of the last. */
internal object HuggingFaceImports {

    sealed interface State {
        data object Idle : State
        data class Downloading(val target: HfTarget, val label: String, val done: Long, val total: Long) : State
        data class Done(val target: HfTarget, val label: String) : State

        /** [arg] is the one argument [messageRes] takes, or "" for none. */
        data class Failed(val target: HfTarget, @StringRes val messageRes: Int, val arg: String = "") : State
    }

    private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    /** Where a repository's files wait until they are installed: under filesDir, so the install can move them. */
    private fun staging(filesDir: File, repo: String): File =
        File(filesDir, "hf-import/" + repo.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /**
     * Downloads [files] of [listing] and installs them for [target] as [name]
     * for [languages] (Sherpa and Qwen3-ASR; Whisper reads its languages from
     * the model). [token] is the user's Hugging Face token, or blank.
     */
    @Suppress("detekt.LongParameterList")
    fun start(
        filesDir: File,
        target: HfTarget,
        listing: HuggingFaceModels.Listing,
        files: List<HuggingFaceModels.RepoFile>,
        name: String,
        languages: Set<String>,
        token: String,
    ) {
        if (isBusy || files.isEmpty()) return
        val total = files.sumOf { it.bytes }
        _state.value = State.Downloading(target, name, 0, total)
        job = scope.launch {
            val dir = staging(filesDir, listing.repo)
            try {
                dir.mkdirs()
                if (StatFs(filesDir.path).availableBytes < total + SPACE_MARGIN_BYTES) {
                    _state.value = State.Failed(
                        target, R.string.voice_hf_import_space, formatBytes(total + SPACE_MARGIN_BYTES),
                    )
                    return@launch
                }
                val fetched = HuggingFaceModels.download(dir, listing, files, token) { done, all ->
                    _state.value = State.Downloading(target, name, done, all)
                }
                _state.value = install(filesDir, target, fetched, name, languages)
                dir.deleteRecursively()
            } catch (e: CancellationException) {
                // What came stays for the next try, which resumes it.
                _state.value = State.Idle
                throw e
            } catch (e: DownloadException.Http) {
                _state.value = State.Failed(target, R.string.voice_hf_import_http, e.status.toString())
            } catch (_: DownloadException.Blocked) {
                _state.value = State.Failed(target, CommonR.string.common_error_download_held)
            } catch (_: DownloadException) {
                dir.deleteRecursively()
                _state.value = State.Failed(target, R.string.voice_hf_import_mismatch)
            } catch (_: IOException) {
                _state.value = State.Failed(target, CommonR.string.common_error_network)
            } catch (_: Exception) {
                // An installer that refused (Whisper's, while a download of its own runs).
                _state.value = State.Failed(target, R.string.voice_sherpa_custom_failed)
            }
        }
    }

    private fun install(filesDir: File, target: HfTarget, files: List<File>, name: String, languages: Set<String>): State =
        when (target) {
            HfTarget.WHISPER -> {
                val file = files.single()
                val header = WhisperCustomModels.header(file)
                if (header == null) {
                    State.Failed(target, R.string.voice_hf_import_not_whisper)
                } else {
                    val model = WhisperDownloadManager.installCustom(filesDir, file, name, header)
                    State.Done(target, model.displayName)
                }
            }
            else -> {
                val messageRes = customModelProblem(EtCustomModels.install(filesDir, files, name, languages, move = true))
                if (messageRes == null) State.Done(target, name) else State.Failed(target, messageRes)
            }
        }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (!isBusy) _state.value = State.Idle
    }
}

/**
 * The row that opens the import, with the download's progress or outcome
 * under it while it concerns [target]. Shown only where the app may connect.
 * [onInstalled] runs once a model of [target]'s is in place.
 */
@Composable
internal fun HuggingFaceImportRow(target: HfTarget, settings: LiveSettings, onInstalled: () -> Unit = {}) {
    if (!InternetPermission.granted) return
    var open by remember { mutableStateOf(false) }
    val state by HuggingFaceImports.state.collectAsState()
    val downloadingHere = (state as? HuggingFaceImports.State.Downloading)?.target == target
    val cancelLabel = stringResource(R.string.models_cancel_download_desc)
    val cancel: @Composable () -> Unit = {
        IconButton(onClick = { HuggingFaceImports.cancel() }) {
            Icon(Icons.Outlined.Close, contentDescription = cancelLabel)
        }
    }
    Column {
        WmRow(
            title = stringResource(R.string.voice_hf_import_title),
            subtitle = stringResource(R.string.voice_hf_import_subtitle),
            leading = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
            // Kept enabled while downloading, so its cancel button can be pressed.
            onClick = { if (!HuggingFaceImports.isBusy) open = true },
            trailing = if (downloadingHere) cancel else null,
        )
        when (val s = state) {
            is HuggingFaceImports.State.Downloading -> if (s.target == target) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(
                            R.string.models_download_progress,
                            formatBytes(s.done),
                            formatBytes(s.total),
                            if (s.total > 0) s.done * 100 / s.total else 0L,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            is HuggingFaceImports.State.Done -> if (s.target == target) {
                // Once per install: the model list above picks the new model up.
                LaunchedEffect(s) { onInstalled() }
                CaptionText(stringResource(R.string.voice_hf_import_done, s.label))
            }
            is HuggingFaceImports.State.Failed -> if (s.target == target) {
                CaptionText(
                    if (s.arg.isEmpty()) stringResource(s.messageRes) else stringResource(s.messageRes, s.arg),
                    error = true,
                )
            }
            HuggingFaceImports.State.Idle -> Unit
        }
    }
    if (open) {
        HuggingFaceImportDialog(target, settings, onDismiss = { open = false })
    }
}

/** What the dialog is showing. */
private sealed interface Step {
    data object Enter : Step
    data object Listing : Step
    data class Ready(val listing: HuggingFaceModels.Listing, val found: HuggingFaceModels.Found) : Step

    /** The repository holds no model for this engine; [otherRes] names the engine that would take what it does hold, if any. */
    data class NoModel(@StringRes val otherRes: Int?) : Step
    data class Error(@StringRes val messageRes: Int, val arg: String = "") : Step
}

@Composable
@Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
private fun HuggingFaceImportDialog(target: HfTarget, settings: LiveSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var repo by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<Step>(Step.Enter) }
    var name by remember { mutableStateOf("") }
    var languages by remember {
        mutableStateOf(settings.value.enabledLanguages.firstOrNull()?.id.orEmpty().substringBefore('_').substringBefore('-'))
    }
    var whisperFile by remember { mutableStateOf<HuggingFaceModels.RepoFile?>(null) }
    var meteredAsk by remember { mutableStateOf<(() -> Unit)?>(null) }
    var meteredBlocked by remember { mutableStateOf(false) }
    val token = settings.value.ai.hfToken

    fun look() {
        val ref = HuggingFaceModels.parse(repo)
        if (ref == null) {
            step = Step.Error(R.string.voice_hf_import_bad_repo)
            return
        }
        step = Step.Listing
        scope.launch {
            step = try {
                val listing = HuggingFaceModels.list(ref, token)
                val all = HuggingFaceModels.find(listing, ref.folder)
                val mine = all.firstOrNull(target::accepts)
                if (mine != null) {
                    if (name.isBlank()) name = ref.repo.substringAfter('/')
                    whisperFile = (mine as? HuggingFaceModels.Found.Whisper)?.files?.firstOrNull()
                    Step.Ready(listing, mine)
                } else {
                    Step.NoModel(
                        when (all.firstOrNull()) {
                            is HuggingFaceModels.Found.Qwen3 -> R.string.voice_engine_qwen3
                            is HuggingFaceModels.Found.Sherpa -> R.string.voice_engine_sherpa
                            is HuggingFaceModels.Found.Whisper -> R.string.voice_engine_whisper
                            null -> null
                        },
                    )
                }
            } catch (e: DownloadException.Http) {
                when (e.status) {
                    401, 403 -> Step.Error(R.string.voice_hf_import_gated)
                    404 -> Step.Error(R.string.voice_hf_import_not_found)
                    else -> Step.Error(R.string.voice_hf_import_http, e.status.toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Step.Error(CommonR.string.common_error_network)
            }
        }
    }

    fun begin(found: Step.Ready) {
        val files = when (val f = found.found) {
            is HuggingFaceModels.Found.Whisper -> listOfNotNull(whisperFile)
            else -> f.files
        }
        val chosenLanguages = languages.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val go = {
            HuggingFaceImports.start(context.filesDir, target, found.listing, files, name.trim(), chosenLanguages, token)
            onDismiss()
        }
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> if (isMeteredNow(context)) meteredAsk = go else go()
            MeteredDecision.ASK -> meteredAsk = go
            MeteredDecision.BLOCKED -> meteredBlocked = true
        }
    }

    val current = step
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_hf_import_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = repo,
                    onValueChange = {
                        repo = it
                        if (step !is Step.Listing) step = Step.Enter
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.voice_hf_import_repo)) },
                    placeholder = { Text(stringResource(hfPlaceholder(target))) },
                    modifier = Modifier.fillMaxWidth(),
                )
                when (current) {
                    Step.Enter -> CaptionText(stringResource(R.string.voice_hf_import_hint))
                    Step.Listing -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.voice_hf_import_listing))
                    }
                    is Step.Error -> CaptionText(
                        if (current.arg.isEmpty()) stringResource(current.messageRes)
                        else stringResource(current.messageRes, current.arg),
                        error = true,
                    )
                    is Step.NoModel -> CaptionText(
                        current.otherRes?.let { stringResource(R.string.voice_hf_import_other_engine, stringResource(it)) }
                            ?: stringResource(R.string.voice_hf_import_nothing),
                        error = true,
                    )
                    is Step.Ready -> {
                        val found = current.found
                        if (found is HuggingFaceModels.Found.Whisper) {
                            Text(
                                stringResource(R.string.voice_hf_import_pick_whisper),
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            for (file in found.files) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable { whisperFile = file },
                                ) {
                                    RadioButton(selected = whisperFile == file, onClick = { whisperFile = file })
                                    // A file's own name, not translated.
                                    Text("${file.name} · ${formatBytes(file.bytes)}")
                                }
                            }
                        } else {
                            Text(
                                stringResource(
                                    if (found is HuggingFaceModels.Found.Qwen3) R.string.voice_hf_import_found_qwen3
                                    else R.string.voice_hf_import_found_sherpa,
                                    found.files.size,
                                    formatBytes(found.bytes),
                                ),
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            OutlinedTextField(
                                value = languages,
                                onValueChange = { languages = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.voice_sherpa_custom_languages)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.voice_sherpa_custom_name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (current) {
                is Step.Ready -> TextButton(
                    enabled = name.isNotBlank() && !HuggingFaceImports.isBusy &&
                        (current.found is HuggingFaceModels.Found.Whisper && whisperFile != null ||
                            current.found !is HuggingFaceModels.Found.Whisper && languages.isNotBlank()),
                    onClick = { begin(current) },
                ) { Text(stringResource(CommonR.string.common_download)) }
                else -> TextButton(
                    enabled = repo.isNotBlank() && current != Step.Listing,
                    onClick = { look() },
                ) { Text(stringResource(R.string.voice_hf_import_look)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
    meteredAsk?.let { go ->
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                meteredAsk = null
                go()
            },
            onDismiss = { meteredAsk = null },
        )
    }
    if (meteredBlocked) MeteredBlockedDialog(onDismiss = { meteredBlocked = false })
}

/** An example repository for [target], shown in the empty field. */
@StringRes
private fun hfPlaceholder(target: HfTarget): Int = when (target) {
    HfTarget.QWEN3 -> R.string.voice_hf_import_example_qwen3
    HfTarget.SHERPA -> R.string.voice_hf_import_example_sherpa
    HfTarget.WHISPER -> R.string.voice_hf_import_example_whisper
}

/** The words for an import that did not install, or null for one that did. */
@StringRes
internal fun customModelProblem(outcome: EtCustomModels.Result?): Int? = when (outcome) {
    EtCustomModels.Result.INSTALLED -> null
    EtCustomModels.Result.NOT_TRANSDUCER -> R.string.voice_sherpa_custom_not_transducer
    EtCustomModels.Result.NOT_STREAMING -> R.string.voice_sherpa_custom_not_streaming
    EtCustomModels.Result.NEEDS_VAD -> R.string.voice_sherpa_custom_needs_vad
    EtCustomModels.Result.QWEN3_INCOMPLETE -> R.string.voice_qwen3_custom_incomplete
    EtCustomModels.Result.QWEN3_UNREADABLE -> R.string.voice_qwen3_custom_unreadable
    EtCustomModels.Result.NEEDS_RUNTIME -> R.string.voice_qwen3_custom_needs_runtime
    null -> R.string.voice_sherpa_custom_failed
}
