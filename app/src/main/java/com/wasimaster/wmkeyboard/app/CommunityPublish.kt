package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.addons.CommunitySubmission
import com.wasimaster.wmkeyboard.core.icons.IconPack
import com.wasimaster.wmkeyboard.core.icons.IconPackFile
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.share.RemixCredit
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetFile
import com.wasimaster.wmkeyboard.core.snippets.SnippetFolder
import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.withEmbeddedImages
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens the publish dialog for one custom theme. */
@Composable
internal fun PublishThemeDialog(theme: ThemeSpec, onDismiss: () -> Unit) {
    // The credit goes in its own form field, and the installer writes the
    // community repository's own credit on the way back in.
    val spec = theme.copy(basedOn = null)
    CommunityPublishDialog(
        kind = CommunitySubmission.Kind.THEME,
        defaultName = theme.name,
        credit = theme.basedOn,
        payload = { shrink ->
            val embedded = spec.withEmbeddedImages()
            ThemeCodec.encode(if (shrink) embedded.withSmallerBackgrounds() else embedded).toByteArray()
        },
        canShrink = true,
        onDismiss = onDismiss,
    )
}

/** Opens the publish dialog for one custom layout. */
@Composable
internal fun PublishLayoutDialog(layout: LayoutSpec, onDismiss: () -> Unit) {
    CommunityPublishDialog(
        kind = CommunitySubmission.Kind.LAYOUT,
        defaultName = layout.name,
        credit = layout.basedOn,
        // The asset form leaves defaulted fields out, which is most of a
        // layout: the difference between pasting it and fitting it in a link.
        payload = { LayoutFile.encodeAsset(layout.copy(basedOn = null)).toByteArray() },
        canShrink = false,
        onDismiss = onDismiss,
    )
}

/** Opens the publish dialog for one icon pack: the same file Export writes. */
@Composable
internal fun PublishIconPackDialog(pack: IconPack, onDismiss: () -> Unit) {
    val context = LocalContext.current
    CommunityPublishDialog(
        kind = CommunitySubmission.Kind.ICON_PACK,
        defaultName = pack.name,
        credit = null,
        payload = {
            val store = IconPackStore.get(context)
            ByteArrayOutputStream().also { out ->
                IconPackFile.write(out, pack, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME) { store.fileFor(pack.id, it) }
            }.toByteArray()
        },
        canShrink = false,
        onDismiss = onDismiss,
    )
}

/** Opens the publish dialog for one sticker pack: the same file Export writes. */
@Composable
internal fun PublishStickerPackDialog(pack: StickerPack, onDismiss: () -> Unit) {
    val context = LocalContext.current
    CommunityPublishDialog(
        kind = CommunitySubmission.Kind.STICKERS,
        defaultName = pack.name,
        credit = null,
        payload = {
            val store = StickerPackStore.get(context)
            ByteArrayOutputStream().also { out ->
                StickerPackFile.write(out, pack, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME) { store.fileFor(pack.id, it) }
            }.toByteArray()
        },
        canShrink = false,
        onDismiss = onDismiss,
    )
}

/**
 * Opens the publish dialog for the user's snippets. Snippets are the one kind
 * that holds personal text (an address, a phone number), so the dialog asks
 * which folder goes rather than sending the whole library, and says to check.
 */
@Composable
internal fun PublishSnippetsDialog(snippets: List<Snippet>, folders: List<SnippetFolder>, onDismiss: () -> Unit) {
    // Only folders something is filed in; null is every snippet.
    val filled = folders.filter { folder -> snippets.any { it.folderId == folder.id } }
    var folderId by rememberSaveable { mutableStateOf<Long?>(filled.firstOrNull()?.id) }
    val chosen = folderId?.let { id -> snippets.filter { it.folderId == id } } ?: snippets
    CommunityPublishDialog(
        kind = CommunitySubmission.Kind.SNIPPETS,
        defaultName = filled.firstOrNull { it.id == folderId }?.name.orEmpty(),
        credit = null,
        payload = {
            SnippetFile.encode(
                chosen,
                appVersion = BuildConfig.VERSION_CODE,
                appVersionName = BuildConfig.VERSION_NAME,
                folders = folders,
            ).toByteArray()
        },
        canShrink = false,
        onDismiss = onDismiss,
        extra = {
            if (filled.isNotEmpty()) {
                Text(
                    stringResource(R.string.community_publish_snippets_folder_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (folder in filled) {
                        FilterChip(
                            selected = folderId == folder.id,
                            onClick = { folderId = folder.id },
                            label = { Text(folder.name) },
                        )
                    }
                    FilterChip(
                        selected = folderId == null,
                        onClick = { folderId = null },
                        label = { Text(stringResource(R.string.community_publish_snippets_all)) },
                    )
                }
            }
            Text(
                pluralStringResource(R.plurals.community_publish_snippets_check, chosen.size, chosen.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private enum class PublishStep { FORM, BUSY, PASTE, ATTACH, SAVED }

/**
 * Asks for what the community repository's issue form needs, then sends the
 * user to GitHub the shortest way the payload allows; see
 * [CommunitySubmission] for the three ways.
 *
 * Nothing is sent from here. The issue is filed by the user, on GitHub, under
 * their own account, which is also what lets the repository's bot tell their
 * later updates from someone else's.
 */
@Composable
private fun CommunityPublishDialog(
    kind: CommunitySubmission.Kind,
    defaultName: String,
    credit: RemixCredit?,
    /** The payload file; with `true`, with its pictures made smaller. Called off the main thread. */
    payload: (shrink: Boolean) -> ByteArray,
    canShrink: Boolean,
    onDismiss: () -> Unit,
    /** Anything the kind asks beyond the shared fields, under the intro. */
    extra: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kindLabel = stringResource(
        when (kind) {
            CommunitySubmission.Kind.THEME -> R.string.community_publish_kind_theme
            CommunitySubmission.Kind.LAYOUT -> R.string.community_publish_kind_layout
            CommunitySubmission.Kind.ICON_PACK -> R.string.community_publish_kind_icon_pack
            CommunitySubmission.Kind.STICKERS -> R.string.community_publish_kind_stickers
            CommunitySubmission.Kind.SNIPPETS -> R.string.community_publish_kind_snippets
        },
    )

    var name by rememberSaveable(defaultName) { mutableStateOf(defaultName) }
    var description by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var license by rememberSaveable { mutableStateOf(CommunitySubmission.LICENSES.first()) }
    var updates by rememberSaveable { mutableStateOf("") }
    // GitHub's issue form keeps every box the link fills in as it was: typing
    // there snaps it back. Off sends description and tags in the link (one
    // tap to submit); on leaves those boxes empty, so they can be typed and
    // edited on GitHub.
    var typeOnGitHub by rememberSaveable { mutableStateOf(false) }
    var step by remember { mutableStateOf(PublishStep.FORM) }
    var error by remember { mutableStateOf<String?>(null) }
    var plan by remember { mutableStateOf<CommunitySubmission.Plan?>(null) }
    var shrunk by remember { mutableStateOf(false) }
    // The file waiting to be written once the user picks where it goes.
    var attachment by remember { mutableStateOf<ByteArray?>(null) }
    var attachmentName by remember { mutableStateOf("") }

    fun fields() = CommunitySubmission.Fields(
        name = name.trim(),
        description = if (typeOnGitHub) "" else description,
        tags = if (typeOnGitHub) emptyList() else tags.split(','),
        license = license,
        updates = updates,
        basedOn = credit?.key.orEmpty(),
    )

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    fun prepare(shrink: Boolean) {
        step = PublishStep.BUSY
        scope.launch {
            val file = withContext(Dispatchers.Default) { runCatching { payload(shrink) }.getOrNull() }
            if (file == null) {
                error = context.getString(R.string.community_publish_failed, kindLabel)
                step = PublishStep.FORM
                return@launch
            }
            // Past this the repository cannot take it at all: for stickers,
            // what one GitHub attachment carries.
            if (file.size > kind.maxBytes) {
                error = context.getString(
                    R.string.community_publish_too_big,
                    kindLabel,
                    android.text.format.Formatter.formatShortFileSize(context, kind.maxBytes),
                )
                step = PublishStep.FORM
                return@launch
            }
            val made = withContext(Dispatchers.Default) { CommunitySubmission.plan(kind, fields(), file) }
            plan = made
            when (made.tier) {
                CommunitySubmission.Tier.LINK -> {
                    open(made.url)
                    onDismiss()
                }
                CommunitySubmission.Tier.PASTE -> {
                    copyPlain(context, made.encoded)
                    step = PublishStep.PASTE
                }
                CommunitySubmission.Tier.ATTACH -> {
                    attachment = CommunitySubmission.attachment(kind, file)
                    attachmentName = CommunitySubmission.attachmentName(kind, name)
                    step = PublishStep.ATTACH
                }
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val bytes = attachment
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { it.write(bytes) }
                }.isSuccess
            }
            if (ok) step = PublishStep.SAVED
        }
    }

    when (step) {
        PublishStep.FORM, PublishStep.BUSY -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.community_publish_title)) },
            text = {
                // Column, not LazyColumn: a lazy list in a dialog's text slot
                // lays out at full height and never scrolls.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.community_publish_intro, kindLabel),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    extra()
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.community_publish_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!typeOnGitHub) {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(stringResource(R.string.community_publish_description_label)) },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = tags,
                            onValueChange = { tags = it },
                            label = { Text(stringResource(R.string.community_publish_tags_label)) },
                            supportingText = { Text(stringResource(R.string.community_publish_tags_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        stringResource(R.string.community_publish_locked_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = typeOnGitHub, role = Role.Checkbox, onValueChange = { typeOnGitHub = it }),
                    ) {
                        Checkbox(checked = typeOnGitHub, onCheckedChange = null)
                        Text(
                            stringResource(R.string.community_publish_type_on_github),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        stringResource(R.string.community_publish_license_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in CommunitySubmission.LICENSES) {
                            FilterChip(
                                selected = license == option,
                                onClick = { license = option },
                                label = { Text(option) },
                            )
                        }
                    }
                    Text(
                        stringResource(licenseExplanation(license)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = updates,
                        onValueChange = { updates = it },
                        label = { Text(stringResource(R.string.community_publish_updates_label)) },
                        supportingText = { Text(stringResource(R.string.community_publish_updates_hint, kindLabel)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (credit != null) {
                        Text(
                            if (credit.author.isNotBlank()) {
                                stringResource(R.string.community_publish_remix, credit.name, credit.author)
                            } else {
                                stringResource(R.string.community_publish_remix_no_author, credit.name.ifBlank { credit.key })
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { error = null; prepare(shrink = false) },
                    enabled = step == PublishStep.FORM && name.isNotBlank(),
                ) {
                    Text(
                        stringResource(
                            if (step == PublishStep.BUSY) R.string.community_publish_preparing else R.string.community_publish_open,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )

        PublishStep.PASTE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.community_publish_paste_title)) },
            text = { Text(stringResource(R.string.community_publish_paste_body, kindLabel)) },
            confirmButton = {
                TextButton(onClick = { plan?.let { open(it.url) }; onDismiss() }) {
                    Text(stringResource(R.string.community_publish_open))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )

        PublishStep.ATTACH -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.community_publish_attach_title)) },
            text = {
                Text(
                    when {
                        canShrink && !shrunk -> stringResource(R.string.community_publish_attach_body)
                        kind == CommunitySubmission.Kind.LAYOUT -> stringResource(R.string.community_publish_attach_body_layout)
                        else -> stringResource(R.string.community_publish_attach_body_pack, kindLabel)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { saveLauncher.launch(attachmentName) }) {
                    Text(stringResource(R.string.community_publish_save))
                }
            },
            dismissButton = {
                // Smaller pictures usually bring a photo theme down to a paste;
                // offered once, since a second pass would only cost quality.
                if (canShrink && !shrunk) {
                    TextButton(onClick = { shrunk = true; prepare(shrink = true) }) {
                        Text(stringResource(R.string.community_publish_shrink))
                    }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
                }
            },
        )

        PublishStep.SAVED -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.community_publish_saved_title)) },
            text = { Text(stringResource(R.string.community_publish_saved_body, attachmentName)) },
            confirmButton = {
                TextButton(onClick = { plan?.let { open(it.url) }; onDismiss() }) {
                    Text(stringResource(R.string.community_publish_open))
                }
            },
        )
    }
}

private fun licenseExplanation(license: String): Int = when (license) {
    "CC-BY-4.0" -> R.string.community_publish_license_cc_by
    "CC-BY-SA-4.0" -> R.string.community_publish_license_cc_by_sa
    else -> R.string.community_publish_license_cc0
}

private fun copyPlain(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("", text))
}

/** Widest a shared background is kept: a phone keyboard is drawn at about this. */
private const val SHARE_BACKGROUND_PX = 1440

private const val SHARE_JPEG_QUALITY = 80

/**
 * The embedded backgrounds re-encoded as JPEG at keyboard width, each kept
 * only when that made it smaller. Animated GIFs are left alone: a still frame
 * would quietly stop the theme moving.
 */
private fun ThemeSpec.withSmallerBackgrounds(): ThemeSpec = copy(
    backgroundImageBase64 = backgroundImageBase64?.let(::smallerImage),
    backgroundImageLandscapeBase64 = backgroundImageLandscapeBase64?.let(::smallerImage),
    variants = variants.map { it.withSmallerBackgrounds() },
)

private fun smallerImage(base64: String): String {
    val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return base64
    if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "GIF8") return base64
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return base64
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= SHARE_BACKGROUND_PX) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return base64
    // JPEG has no transparency: a cut-out background would come back boxed in black.
    if (decoded.hasAlpha()) return base64
    val scaled = if (decoded.width > SHARE_BACKGROUND_PX) {
        Bitmap.createScaledBitmap(decoded, SHARE_BACKGROUND_PX, decoded.height * SHARE_BACKGROUND_PX / decoded.width, true)
    } else {
        decoded
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, SHARE_JPEG_QUALITY, out)
    return if (out.size() < bytes.size) Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) else base64
}
