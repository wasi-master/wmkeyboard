package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.addons.AddonPreviewContent
import com.wasimaster.wmkeyboard.core.addons.AddonPreviewReader
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.snippets.GboardDictionary
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.theme.FlexResult
import com.wasimaster.wmkeyboard.core.theme.GboardResult
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.withExtractedImages
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.vocab.VocabPack
import com.wasimaster.wmkeyboard.ime.ui.rememberMediaImageLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the file being offered actually contains, drawn above the confirm
 * dialog's wording.
 *
 * The dialog used to say "Import theme Dusk?" and leave the rest to trust: a
 * theme is a hundred colours, a layout is a grid, a sticker pack is pictures,
 * and none of that survives being described in a sentence. So the formats whose
 * whole question is "what is it" get shown rather than summarised.
 *
 * Two sources, and both already existed. Themes and layouts are drawn by the
 * miniatures the onboarding wizard and the theme gallery use — the addon store
 * has no preview for either, because a repository ships screenshots and a file
 * on disk does not. Sticker packs and sound packs go through
 * [AddonPreviewReader], which already knows how to unpack an archive safely,
 * under a cap, without installing it.
 *
 * Nothing here decides anything. A format with nothing worth showing draws
 * nothing and the dialog reads exactly as it did before.
 */
@Composable
internal fun ImportFilePreview(state: WMFileTypes.Opened, uri: Uri) {
    when (state) {
        is WMFileTypes.Opened.Theme -> ThemeFilePreview(state.theme)

        is WMFileTypes.Opened.FlorisTheme -> {
            val converted = state.result as? FlexResult.Converted
            // The first look only. An extension with a day and a night theme
            // becomes one entry with two looks, and the dialog above already
            // says how many came across.
            converted?.themes?.firstOrNull()?.let { FlorisFilePreview(it) }
        }

        is WMFileTypes.Opened.FutoThemeFile -> FlorisFilePreview(state.result.converted)

        // The first look of the first theme, drawn by the same miniature: a
        // pack's dialog already says how many themes it holds.
        is WMFileTypes.Opened.GboardThemeFile ->
            (state.result as? GboardResult.Converted)?.themes?.firstOrNull()
                ?.looks?.firstOrNull()?.let { FlorisFilePreview(it.converted) }

        is WMFileTypes.Opened.Layout -> LayoutFilePreview(state.layout.layout)

        is WMFileTypes.Opened.FutoLayout -> LayoutFilePreview(state.converted.layout)

        is WMFileTypes.Opened.KeysCafeLayout -> LayoutFilePreview(state.converted.layout)
        is WMFileTypes.Opened.UnexpectedLayout -> LayoutFilePreview(state.converted.layout)

        // The first variant: the dialog lists the rest by name.
        is WMFileTypes.Opened.DesktopLayoutFile -> state.layouts.firstOrNull()?.let { LayoutFilePreview(it.layout) }

        // The first layout, or the theme when the text holds no layout: a
        // settings export's dialog already says how many of each it holds.
        is WMFileTypes.Opened.MultilingFile -> {
            val first = state.layouts.firstOrNull()
            if (first != null) LayoutFilePreview(first.layout) else state.theme?.let { ThemeFilePreview(it.theme) }
        }

        is WMFileTypes.Opened.KeymanPackageFile ->
            keymanLayoutOf(state.contents)?.let { LayoutFilePreview(it.layout) }

        is WMFileTypes.Opened.Snippets -> SnippetsFilePreview(state.snippets.snippets)

        is WMFileTypes.Opened.ForeignSnippets -> SnippetsFilePreview(state.parsed.snippets)

        is WMFileTypes.Opened.GboardDictionaryFile -> GboardDictionaryPreview(state.contents)

        is WMFileTypes.Opened.Vocabulary -> VocabularyFilePreview(state.pack)

        WMFileTypes.Opened.Stickers -> StickersFilePreview(uri)

        // The same strip: the reader takes any picture in the archive, and a
        // WhatsApp pack is pictures with two text files beside them.
        is WMFileTypes.Opened.WhatsAppStickers -> StickersFilePreview(uri)

        WMFileTypes.Opened.SoundPack -> SoundPackFilePreview(uri)

        // Everything else says what it is in words and has nothing to add: a
        // backup is a list of sections, a plugin is its permissions, and an
        // unreadable file has nothing at all.
        else -> Unit
    }
}

/**
 * The theme as a miniature keyboard.
 *
 * Drawn from the raw spec first so the colours appear on the first frame, then
 * again from a copy whose photo has been written out. A shared theme carries
 * its background as base64 inside the JSON and `backgroundImage` is empty until
 * something extracts it, so without the second pass a photo theme would preview
 * as the flat colour underneath the photo — which is the one thing the user is
 * looking at the preview to avoid.
 */
@Composable
private fun ThemeFilePreview(theme: ThemeSpec) {
    val context = LocalContext.current
    val shown by produceState(theme, theme) {
        value = withContext(Dispatchers.IO) {
            runCatching { theme.withExtractedImages(previewCacheDir(context)) }.getOrDefault(theme)
        }
    }
    PreviewFrame { ThemePreview(shown, animatedBadge = false) }
}

/**
 * A converted FlorisBoard theme as a miniature.
 *
 * Its images arrive as raw bytes rather than inside the spec, so there is
 * nothing to draw until they are written out; the frame stays empty for that
 * one pass rather than showing a version of the theme with its background
 * missing.
 */
@Composable
private fun FlorisFilePreview(converted: com.wasimaster.wmkeyboard.core.theme.ConvertedTheme) {
    val context = LocalContext.current
    val shown by produceState<ThemeSpec?>(null, converted) {
        value = withContext(Dispatchers.IO) {
            runCatching { converted.stored("preview", previewCacheDir(context)) }.getOrNull()
        }
    }
    // Fades in once the images are out, rather than popping into the dialog.
    shown?.let { StaggerIn(0) { PreviewFrame { ThemePreview(it, animatedBadge = false) } } }
}

/**
 * The layout's own letter grid, key for key.
 *
 * The same miniature the language wizard draws, and for the same reason: a
 * phonetic grid and a native one are both "a layout called X" in words and
 * nothing alike on screen.
 */
@Composable
private fun LayoutFilePreview(spec: LayoutSpec) {
    PreviewFrame { MiniLayoutPreview(spec) }
}

/** The first few snippets: what each is called, and what it types. */
@Composable
private fun SnippetsFilePreview(snippets: List<Snippet>) {
    val shown = snippets.take(MAX_ROWS)
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = PREVIEW_GAP)) {
        for ((index, snippet) in shown.withIndex()) {
            StaggerIn(index) {
                PreviewLine(
                    title = snippet.label.ifBlank { snippet.trigger.orEmpty() },
                    detail = snippet.text,
                )
            }
        }
        MoreLine(snippets.size - shown.size)
    }
}

/**
 * The first few entries of a Gboard dictionary: a shortcut over the word it
 * offers, or a plain word on its own.
 */
@Composable
private fun GboardDictionaryPreview(contents: GboardDictionary.Contents) {
    val shown = contents.entries.take(MAX_ROWS)
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = PREVIEW_GAP)) {
        for ((index, entry) in shown.withIndex()) {
            StaggerIn(index) {
                if (entry.shortcut.isEmpty()) {
                    PreviewLine(title = entry.word, detail = "")
                } else {
                    PreviewLine(title = entry.shortcut, detail = entry.word)
                }
            }
        }
        MoreLine(contents.entries.size - shown.size)
    }
}

/** The first few words, each with its first definition. */
@Composable
private fun VocabularyFilePreview(pack: VocabPack) {
    val shown = pack.words.take(MAX_ROWS)
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = PREVIEW_GAP)) {
        for ((index, word) in shown.withIndex()) {
            StaggerIn(index) { PreviewLine(title = word.word, detail = word.definition) }
        }
        MoreLine(pack.words.size - shown.size)
    }
}

/** The pack's images, as many as fit across. */
@Composable
private fun StickersFilePreview(uri: Uri) {
    val content = rememberArchivePreview(uri) { AddonPreviewReader.readStickers(it) }
    val stickers = content as? AddonPreviewContent.Stickers ?: return
    val loader = rememberMediaImageLoader()
    Column(Modifier.fillMaxWidth().padding(bottom = PREVIEW_GAP)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((index, image) in stickers.images.take(MAX_STICKERS).withIndex()) {
                StaggerIn(index) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        imageLoader = loader,
                        modifier = Modifier.size(STICKER_SIZE).clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
        CountingLine(stickers.total, R.plurals.addon_preview_sticker_count)
    }
}

/**
 * One tap per recording rather than a single Play.
 *
 * A pack is worth having for how much it varies, and a button that plays one of
 * eight makes that a guessing game — the same reasoning the addon store's own
 * preview follows.
 */
@Composable
private fun SoundPackFilePreview(uri: Uri) {
    val content = rememberArchivePreview(uri) { AddonPreviewReader.readSoundPack(it) }
    val pack = content as? AddonPreviewContent.SoundPack ?: return
    Column(Modifier.fillMaxWidth().padding(bottom = PREVIEW_GAP)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((index, variant) in pack.variants.withIndex()) {
                StaggerIn(index) { PlayChip(index + 1) { AddonSoundPreview.play(variant) } }
            }
        }
        CountingLine(pack.totalVariants, R.plurals.addon_preview_pack_variant_count)
    }
}

@Composable
private fun PlayChip(number: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Outlined.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A drawn preview, kept to the dialog's width and rounded like the real thing. */
@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = PREVIEW_GAP)
            .clip(RoundedCornerShape(10.dp)),
    ) { content() }
}

/** One entry of a list preview: what it is called, and what it holds. */
@Composable
private fun PreviewLine(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How much of the file the rows above are not showing. */
@Composable
private fun MoreLine(remaining: Int) {
    if (remaining <= 0) return
    CountingLine(remaining, R.plurals.import_preview_more)
}

/** A line under a preview saying how many, the figure counting up to itself as it arrives. */
@Composable
private fun CountingLine(count: Int, @PluralsRes plural: Int) {
    CountingText(
        target = count.toLong(),
        format = { shown -> pluralStringResource(plural, shown.toInt(), shown.toInt()) },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Copies the archive out of its content URI and reads it with [read].
 *
 * A provider's stream can only be read forwards once, and both archive readers
 * need several passes, so the bytes have to land somewhere first. The copy is
 * capped: a preview is not worth filling the cache for, and the file on the
 * other end was written by a stranger.
 */
@Composable
private fun rememberArchivePreview(
    uri: Uri,
    read: (File) -> AddonPreviewContent,
): AddonPreviewContent? {
    val context = LocalContext.current
    val reader = remember(uri) { read }
    val content by produceState<AddonPreviewContent?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val payload = copyForPreview(context, uri) ?: return@runCatching null
                reader(payload)
            }.getOrNull()
        }
    }
    return content
}

/** The copy itself, or null when the file is unreadable or past the cap. */
private fun copyForPreview(context: Context, uri: Uri): File? = runCatching {
    val target = File(previewCacheDir(context), "payload")
    var written = 0L
    context.contentResolver.requireInputStream(uri).use { input ->
        target.outputStream().buffered().use { sink ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                written += read
                if (written > MAX_PREVIEW_BYTES) return null
                sink.write(buffer, 0, read)
            }
        }
    }
    if (written > 0) target else null
}.getOrNull()

/**
 * Where a preview unpacks to.
 *
 * Emptied each time it is asked for. Everything in it belongs to the dialog on
 * screen, the dialog is gone the moment the import runs or is cancelled, and
 * nothing that lands here is ever read again.
 */
private fun previewCacheDir(context: Context): File =
    File(context.cacheDir, "import_preview").apply {
        deleteRecursively()
        mkdirs()
    }

/** Between a preview and the wording it illustrates. */
private val PREVIEW_GAP = 12.dp

/** Enough rows to judge a pack; the dialog is not a list screen. */
private const val MAX_ROWS = 3

/** As many as fit across a dialog without turning it into a gallery. */
private const val MAX_STICKERS = 12

private val STICKER_SIZE = 56.dp

/** A preview is not worth copying half a gigabyte for. */
private const val MAX_PREVIEW_BYTES = 64L * 1024 * 1024
