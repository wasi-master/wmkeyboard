package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.addons.AddonStore
import com.wasimaster.wmkeyboard.core.addons.ImportLink
import com.wasimaster.wmkeyboard.core.addons.LinkImport
import com.wasimaster.wmkeyboard.core.addons.SignalStickerDownloads
import com.wasimaster.wmkeyboard.core.addons.TelegramStickerDownloads
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Importing from a link rather than from a file.
 *
 * A theme, a layout, a pack or an add-on repository is nearly always published
 * as a *page*: a file in a repository, an asset on a release, a gist, a build
 * artifact. Sharing one of those into the keyboard used to do nothing, because
 * the only way in was a file the sender had already downloaded. This is the
 * other door: share the address, and the app works out what is behind it,
 * fetches it, and hands it to the file importer that already knows every
 * format ([WMFileTypes]).
 *
 * The address is untrusted input, exactly like the one a `wmkeyboard://repo`
 * link carries, so the rule that governs those governs this: **a link never
 * imports anything on its own.** The dialog below names the host that will be
 * asked and what will come back, the download starts on a press, and what
 * arrives goes through the same confirm dialog a file opened from the Files app
 * does. Data saving is asked before the transfer, not after.
 *
 * The parts: [ImportLink] reads the address, `LinkImport` asks the network what
 * the address holds, and this draws the two questions that sit around them.
 */
class ImportLinkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = intent?.let(::sharedLink)
        if (shared.isNullOrBlank()) {
            finish()
            return
        }
        val repository = SettingsRepository(applicationContext)
        // A layout that arrives this way resolves against the asset layouts,
        // the same way one opened from a file manager does.
        AssetLayouts.load(applicationContext.assets)
        setContent {
            val stored = repository.settings
                .collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            AppTheme(settings) {
                ImportLinkFlow(repository, settings, shared) { finish() }
            }
        }
    }
}

/**
 * The text a link intent carries.
 *
 * A share sheet sends `EXTRA_TEXT`, which holds whatever the sending app put
 * around the address as well as the address itself. A `wmkeyboard://import`
 * link carries it as the `url` parameter. An `https` address opened directly
 * is its own answer.
 */
private fun sharedLink(intent: Intent): String? = when {
    intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
    intent.data?.scheme.equals(AddonDeepLink.SCHEME, ignoreCase = true) ->
        intent.data?.getQueryParameter("url")
    else -> intent.data?.toString()
}

/** Where the flow has got to. */
private sealed interface LinkStage {

    /** Nothing has been fetched yet. The question that guards every request. */
    data class Confirm(val target: ImportLink.Target) : LinkStage

    /** A request is in flight. [bytes] is set once one is a transfer. */
    data class Working(val bytes: Long = 0L, val total: Long = 0L) : LinkStage

    /** Several files behind one link. [exact] is false when none is ours. */
    data class Pick(val candidates: List<LinkImport.Candidate>, val exact: Boolean) : LinkStage

    /** A downloaded archive that is none of our own, holding files that are. */
    data class Archive(val file: File, val entries: List<String>) : LinkStage

    /** An add-on repository, which is a different flow entirely. */
    data class Repository(val manifestUrl: String, val name: String) : LinkStage

    /** Downloaded and ready for the file importer's own dialog. */
    data class Ready(val uri: Uri) : LinkStage

    data class Failed(val text: String) : LinkStage
}

@Composable
internal fun ImportLinkFlow(
    repository: SettingsRepository,
    settings: LiveSettings,
    shared: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AddonStore.get(context) }

    val notALink = stringResource(R.string.import_link_error_not_a_link)
    val insecure = stringResource(R.string.import_link_error_insecure)
    val nothing = stringResource(R.string.import_link_error_nothing)
    val unreachable = stringResource(OfflineBuild.network(R.string.import_link_error_network))
    val failedDownload = stringResource(OfflineBuild.network(R.string.import_link_error_download))

    val target = remember(shared) { ImportLink.resolve(shared) }
    var stage by remember {
        mutableStateOf<LinkStage>(
            when (target) {
                is ImportLink.Target.None -> LinkStage.Failed(notALink)
                is ImportLink.Target.Insecure -> LinkStage.Failed(insecure)
                else -> LinkStage.Confirm(target)
            },
        )
    }
    var askMetered by remember { mutableStateOf(false) }
    var blockedMetered by remember { mutableStateOf(false) }

    /** Downloads [candidate], then works out what came back. */
    fun fetch(candidate: LinkImport.Candidate) {
        stage = LinkStage.Working()
        scope.launch {
            val token = withContext(Dispatchers.IO) { store.forgeToken() }
            val file = withContext(Dispatchers.IO) {
                val destination = File(linkCacheDir(context), safeFileName(candidate.name))
                val ok = LinkImport.download(candidate, destination, token) { done, total ->
                    stage = LinkStage.Working(done, total)
                }
                destination.takeIf { ok }
            }
            if (file == null) {
                stage = LinkStage.Failed(failedDownload)
                return@launch
            }
            // One of ours, one way or another: hand it straight over. An
            // archive that is nobody's format may still be a wrapper around
            // one, which is what every Actions artifact is.
            val known = withContext(Dispatchers.IO) {
                WMFileTypes.identify(context, Uri.fromFile(file), file.name)
            }
            if (known !is WMFileTypes.Opened.Unrecognized) {
                stage = LinkStage.Ready(Uri.fromFile(file))
                return@launch
            }
            val entries = withContext(Dispatchers.IO) { importableEntries(file) }
            stage = when {
                entries.isEmpty() -> LinkStage.Failed(nothing)
                entries.size == 1 -> {
                    val extracted = withContext(Dispatchers.IO) { extract(context, file, entries.single()) }
                    if (extracted == null) {
                        LinkStage.Failed(failedDownload)
                    } else {
                        LinkStage.Ready(Uri.fromFile(extracted))
                    }
                }
                else -> LinkStage.Archive(file, entries)
            }
        }
    }

    /** Asks the network what the link holds, then either fetches or asks. */
    fun lookUp() {
        stage = LinkStage.Working()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                LinkImport.resolve(
                    shared = shared,
                    importable = importableExtensions(),
                    token = store.forgeToken(),
                    cacheDir = linkCacheDir(context),
                )
            }
            when (outcome) {
                is LinkImport.Outcome.One -> fetch(outcome.candidate)
                is LinkImport.Outcome.Several -> stage = LinkStage.Pick(outcome.candidates, outcome.exact)
                is LinkImport.Outcome.Repository ->
                    stage = LinkStage.Repository(outcome.manifestUrl, outcome.name)
                is LinkImport.Outcome.Failed -> stage = LinkStage.Failed(
                    when (outcome.reason) {
                        LinkImport.Failure.NOT_A_LINK -> notALink
                        LinkImport.Failure.INSECURE -> insecure
                        LinkImport.Failure.NOTHING_FOUND -> nothing
                        LinkImport.Failure.NETWORK -> unreachable
                    },
                )
            }
        }
    }

    /** The one gate every request goes through: data saving, then the fetch. */
    fun start(run: () -> Unit) {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> run()
            MeteredDecision.ASK -> askMetered = true
            MeteredDecision.BLOCKED -> blockedMetered = true
        }
    }

    if (blockedMetered) {
        MeteredBlockedDialog {
            blockedMetered = false
            onClose()
        }
        return
    }
    if (askMetered) {
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                askMetered = false
                lookUp()
            },
            onDismiss = {
                askMetered = false
                onClose()
            },
        )
        return
    }

    when (val current = stage) {
        is LinkStage.Failed -> MessageDialog(current.text, onClose)

        is LinkStage.Confirm -> LinkConfirmDialog(
            target = current.target,
            hasToken = remember(store) { store.forgeToken().isNotBlank() },
            onConfirm = {
                // A pack is looked at before it is added, and the screen
                // that shows it asks about data saving itself.
                when (val target = current.target) {
                    is ImportLink.Target.SignalStickers -> {
                        context.startActivity(signalPackIntent(context, target.packId, target.packKey))
                        onClose()
                    }
                    is ImportLink.Target.TelegramStickers -> {
                        context.startActivity(telegramSetIntent(context, target.setName))
                        onClose()
                    }
                    else -> start(::lookUp)
                }
            },
            onDismiss = onClose,
        )

        is LinkStage.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.import_link_progress_title)) },
            text = { WorkingBody(current) },
            confirmButton = {},
        )

        is LinkStage.Pick -> PickDialog(
            title = stringResource(R.string.import_link_pick_title),
            body = if (current.exact) null else stringResource(R.string.import_link_pick_other_body),
            names = current.candidates.map { candidate ->
                if (candidate.bytes > 0L) {
                    "${candidate.name} (${formatBytes(candidate.bytes)})"
                } else {
                    candidate.name
                }
            },
            onPick = { index -> fetch(current.candidates[index]) },
            onDismiss = onClose,
        )

        is LinkStage.Archive -> PickDialog(
            title = stringResource(R.string.import_link_archive_title),
            body = stringResource(R.string.import_link_archive_body),
            names = current.entries.map { it.substringAfterLast('/') },
            onPick = { index ->
                val entry = current.entries[index]
                stage = LinkStage.Working()
                scope.launch {
                    val extracted = withContext(Dispatchers.IO) { extract(context, current.file, entry) }
                    stage = if (extracted == null) {
                        LinkStage.Failed(failedDownload)
                    } else {
                        LinkStage.Ready(Uri.fromFile(extracted))
                    }
                }
            },
            onDismiss = onClose,
        )

        is LinkStage.Repository -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.import_link_repo_title)) },
            text = { Text(stringResource(R.string.import_link_repo_body, current.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(repoLinkFor(current.manifestUrl)))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        onClose()
                    },
                ) { Text(stringResource(R.string.import_link_repo_action)) }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )

        // From here the link is a file like any other, and the dialog that
        // knows every format takes over.
        is LinkStage.Ready -> ImportFileDialog(repository, current.uri, onClose = onClose)
    }
}

/**
 * The confirm that stands between a shared address and the first request.
 *
 * [hasToken] changes only what an artifact link says about itself: with a
 * token the file comes from GitHub, without one it comes through nightly.link,
 * and which of the two it is belongs in the dialog rather than in a surprise
 * afterwards.
 */
@Composable
private fun LinkConfirmDialog(
    target: ImportLink.Target,
    hasToken: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val body = when (target) {
        is ImportLink.Target.File -> stringResource(
            R.string.import_link_confirm_file,
            target.name,
            linkHostOf(target.url),
        )
        is ImportLink.Target.Listing -> stringResource(
            R.string.import_link_confirm_lookup,
            linkHostOf(target.manifestProbe ?: target.apiUrl),
        )
        is ImportLink.Target.Artifact -> stringResource(R.string.import_link_confirm_artifact)
        is ImportLink.Target.SignalStickers -> stringResource(
            R.string.import_link_confirm_signal,
            linkHostOf(SignalStickerDownloads.manifestUrl(target.packId)),
        )
        is ImportLink.Target.TelegramStickers -> stringResource(
            R.string.import_link_confirm_telegram,
            linkHostOf(TelegramStickerDownloads.hostUrl()),
        )
        else -> ""
    }
    // nightly.link is a service this app does not run, and an artifact cannot
    // be fetched without it unless there is a token. Naming it is the point.
    val extra = (target as? ImportLink.Target.Artifact)?.let { artifact ->
        if (hasToken) {
            stringResource(R.string.import_link_confirm_artifact_token)
        } else {
            stringResource(
                R.string.import_link_confirm_artifact_nightly,
                linkHostOf(ImportLink.nightlyLinkUrl(artifact)),
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_link_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(body)
                if (extra != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(extra, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        when (target) {
                            is ImportLink.Target.SignalStickers -> R.string.import_signal_open_pack
                            is ImportLink.Target.TelegramStickers -> R.string.import_telegram_open_set
                            else -> CommonR.string.common_download
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/** One of several files behind a link, or inside an archive. */
@Composable
private fun PickDialog(
    title: String,
    body: String?,
    names: List<String>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                names.forEachIndexed { index, name ->
                    StaggerIn(index) {
                        TextButton(onClick = { onPick(index) }, modifier = Modifier.fillMaxWidth()) {
                            Text(name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * The wait while a link is looked up or fetched: a spinner until the transfer
 * knows its size, then the bytes and a bar.
 *
 * The spinner hands over to the bar rather than being swapped out in a frame,
 * and the bar glides between the download's reports instead of stepping. The
 * box keeps the bigger of the two sizes until the swap is done: it is a
 * dialog's, and resizing a window every frame costs more than one step.
 */
@Composable
private fun WorkingBody(stage: LinkStage.Working) {
    val reduceMotion = LocalReduceMotion.current
    val fraction = if (stage.total > 0L) (stage.bytes.toFloat() / stage.total.toFloat()).coerceIn(0f, 1f) else 0f
    val shown by animateFloatAsState(
        targetValue = fraction,
        animationSpec = if (reduceMotion) snap<Float>() else tween<Float>(durationMillis = LINK_PROGRESS_GLIDE_MS),
        label = "linkProgress",
    )
    AnimatedContent(
        targetState = stage.total > 0L,
        transitionSpec = { stateSwapTransform(reduceMotion, animateSize = false) },
        label = "linkWorking",
    ) { sized ->
        if (sized) {
            Column {
                Text(
                    stringResource(
                        R.string.import_link_progress_bytes,
                        formatBytes(stage.bytes),
                        formatBytes(stage.total),
                    ),
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun MessageDialog(text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_ok)) }
        },
    )
}

// ---- plumbing ----------------------------------------------------------

/** Opens the preview of a Signal pack in the settings app. Nothing is fetched until it is on screen. */
internal fun signalPackIntent(context: android.content.Context, packId: String, packKey: String): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse(AddonDeepLink.signalPackLink(packId, packKey)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Opens the preview of a Telegram set in the settings app. Nothing is fetched until it is on screen. */
internal fun telegramSetIntent(context: android.content.Context, setName: String): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse(AddonDeepLink.telegramSetLink(setName)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** The `wmkeyboard://repo` link that opens the add-repository dialog. */
private fun repoLinkFor(manifestUrl: String): String =
    "${AddonDeepLink.SCHEME}://repo?url=" + java.net.URLEncoder.encode(manifestUrl, "UTF-8")

private fun linkHostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { url }

/**
 * What a listing is filtered by: everything the importer opens, plus plain
 * ZIPs, which are never one of ours but are how an artifact and most bundles
 * arrive. [importableEntries] is what looks inside one.
 */
internal fun importableExtensions(): Set<String> = WMFileTypes.EXTENSIONS.toSet() + "zip"

/**
 * Downloads live in their own cache folder, emptied on the way in: a link
 * import is a one-shot, and leaving a 60 MB pack behind for a dialog that was
 * dismissed is somebody's storage.
 */
private fun linkCacheDir(context: android.content.Context): File =
    File(context.cacheDir, "link-import").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }

/**
 * A file name that cannot escape the cache folder. A listing's names come off
 * the network, and one of them saying `../../databases/x` would otherwise be
 * taken at its word.
 */
private fun safeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
        .filter { it.isLetterOrDigit() || it in "._- " }
        .trim()
        .takeIf { it.isNotBlank() && it != "." && it != ".." }
        ?: ImportLink.FALLBACK_NAME
    return base.take(MAX_NAME_CHARS)
}

/** Whether [file] starts with a ZIP local file header. */
private fun isArchive(file: File): Boolean = runCatching {
    file.inputStream().use { input ->
        val head = ByteArray(4)
        input.read(head) == 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
    }
}.getOrDefault(false)

/**
 * The importable files inside an archive that is none of our own formats.
 *
 * Every Actions artifact is a ZIP around whatever the workflow uploaded, and a
 * theme collection is usually shared the same way. Only names are read here;
 * nothing is written until one is chosen.
 */
private fun importableEntries(file: File): List<String> {
    if (!isArchive(file)) return emptyList()
    val wanted = WMFileTypes.EXTENSIONS
    return runCatching {
        file.inputStream().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                buildList {
                    var scanned = 0
                    while (scanned++ < MAX_ARCHIVE_ENTRIES) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        if (wanted.any { entry.name.endsWith(".$it", ignoreCase = true) }) {
                            add(entry.name)
                        }
                    }
                }
            }
        }
    }.getOrDefault(emptyList())
}

/**
 * Writes one entry out of [archive] into the cache, under its own base name.
 *
 * The name is prefixed rather than used bare: the archive being read sits in
 * the same folder, and an entry named after it would be the file this is
 * reading from. The prefix goes in front, so the compound extension the
 * importer reads the format from is untouched.
 */
private fun extract(context: android.content.Context, archive: File, entry: String): File? {
    val target = File(File(context.cacheDir, "link-import"), "inner_" + safeFileName(entry))
    val copied = runCatching {
        archive.inputStream().use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var found = false
                while (!found) {
                    val next = zip.nextEntry ?: break
                    if (next.name != entry) continue
                    target.outputStream().use { out ->
                        var written = 0L
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > LinkImport.MAX_BYTES) return@use
                            out.write(buffer, 0, read)
                        }
                        found = true
                    }
                }
                found
            }
        }
    }.getOrDefault(false)
    if (!copied) target.delete()
    return target.takeIf { copied && it.length() > 0 }
}

private const val MAX_NAME_CHARS = 120
private const val LINK_PROGRESS_GLIDE_MS = 200
private const val MAX_ARCHIVE_ENTRIES = 2_000
private const val BUFFER_BYTES = 64 * 1024

// ---- the rows on the Addons screen -------------------------------------

/**
 * "Import from a link", and the optional token underneath it.
 *
 * On the Addons screen because that is where everything that arrives from
 * somebody else's repository already lives, and because the token is only ever
 * about fetching from one.
 *
 * The field is deliberately empty rather than filled from the clipboard. This
 * app is a keyboard: reading the clipboard to be helpful is the one thing it
 * must not do on its own, and on Android 12 and later the read announces
 * itself anyway.
 */
@Composable
internal fun LinkImportGroup(store: AddonStore) {
    val context = LocalContext.current
    val revision by store.revision.collectAsStateWithLifecycle()
    val token = remember(revision) { store.forgeToken() }
    var showLink by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }

    SettingsGroup(null) {
        item {
            NavRow(
                title = R.string.import_link_row_title,
                subtitle = stringResource(R.string.import_link_row_subtitle),
                onClick = { showLink = true },
            )
        }
        item {
            NavRow(
                title = R.string.import_link_token_title,
                subtitle = stringResource(
                    if (token.isBlank()) R.string.import_link_token_unset else R.string.import_link_token_set,
                ),
                onClick = { showToken = true },
            )
        }
    }

    if (showLink) {
        LinkFieldDialog(
            onDismiss = { showLink = false },
            onImport = { text ->
                showLink = false
                context.startActivity(
                    Intent(context, ImportLinkActivity::class.java)
                        .setAction(Intent.ACTION_SEND)
                        .putExtra(Intent.EXTRA_TEXT, text),
                )
            },
        )
    }

    if (showToken) {
        TokenDialog(
            initial = token,
            onDismiss = { showToken = false },
            onSave = { value ->
                showToken = false
                store.setForgeToken(value)
            },
        )
    }
}

@Composable
private fun LinkFieldDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    // The same courtesy the add-repository dialog pays: the address that will
    // be asked is shown before it is asked, so a lookalike host is visible.
    val target = remember(text) { ImportLink.resolve(text) }
    val usable = target !is ImportLink.Target.None && target !is ImportLink.Target.Insecure

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_link_row_title)) },
        text = {
            Column {
                Text(stringResource(R.string.import_link_row_subtitle))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.import_link_field_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text.isNotBlank() && target is ImportLink.Target.Insecure) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.import_link_error_insecure),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = usable) {
                Text(stringResource(CommonR.string.common_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun TokenDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_link_token_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.import_link_token_body))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.import_link_token_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { WmPassKeyButton("GitHub", text) { text = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
