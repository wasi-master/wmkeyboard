package com.wasimaster.wmkeyboard.ime.ui.files

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.core.files.DocFiles
import com.wasimaster.wmkeyboard.core.files.FileCategory
import com.wasimaster.wmkeyboard.core.files.FileEntry
import com.wasimaster.wmkeyboard.core.files.FileKind
import com.wasimaster.wmkeyboard.core.files.FileKinds
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Android
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoStories
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Code
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Description
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FolderZip
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FontDownload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Image
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Movie
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MusicNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Notes
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PictureAsPdf
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Storage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TableRows
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewCarousel
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.gallery.MediaThumb
import com.wasimaster.wmkeyboard.ime.ui.gallery.rememberGalleryImageLoader

/*
 * How a file looks in the manager: one icon and one colour per kind, the
 * colours told apart by lightness as well as hue, a badge with the
 * extension, and the picture itself for photos and videos.
 */

internal fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Outlined.Folder
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.VIDEO -> Icons.Outlined.Movie
    FileKind.AUDIO -> Icons.Outlined.MusicNote
    FileKind.PDF -> Icons.Outlined.PictureAsPdf
    FileKind.DOC -> Icons.Outlined.Description
    FileKind.SHEET -> Icons.Outlined.TableRows
    FileKind.SLIDES -> Icons.Outlined.ViewCarousel
    FileKind.TEXT -> Icons.Outlined.Notes
    FileKind.CODE -> Icons.Outlined.Code
    FileKind.ARCHIVE -> Icons.Outlined.FolderZip
    FileKind.APK -> Icons.Outlined.Android
    FileKind.EBOOK -> Icons.Outlined.AutoStories
    FileKind.FONT -> Icons.Outlined.FontDownload
    FileKind.OTHER -> Icons.Outlined.Description
}

/** The colour of a kind; a folder takes the theme's accent, so it follows the keyboard. */
@Composable
internal fun kindColour(kind: FileKind): Color = when (kind) {
    FileKind.FOLDER -> LocalKbTheme.current.accent
    FileKind.IMAGE -> Color(0xFFF59E0B)
    FileKind.VIDEO -> Color(0xFFEC4899)
    FileKind.AUDIO -> Color(0xFF8B5CF6)
    FileKind.PDF -> Color(0xFFDC2626)
    FileKind.DOC -> Color(0xFF3B82F6)
    FileKind.SHEET -> Color(0xFF16A34A)
    FileKind.SLIDES -> Color(0xFFEA580C)
    FileKind.TEXT -> Color(0xFF64748B)
    FileKind.CODE -> Color(0xFF0EA5E9)
    FileKind.ARCHIVE -> Color(0xFFA16207)
    FileKind.APK -> Color(0xFF14B8A6)
    FileKind.EBOOK -> Color(0xFF65A30D)
    FileKind.FONT -> Color(0xFF6366F1)
    FileKind.OTHER -> Color(0xFF78716C)
}

internal fun categoryLabel(category: FileCategory): Int = when (category) {
    FileCategory.IMAGES -> R.string.ime_files_cat_images
    FileCategory.VIDEOS -> R.string.ime_files_cat_videos
    FileCategory.AUDIO -> R.string.ime_files_cat_audio
    FileCategory.DOCUMENTS -> R.string.ime_files_cat_documents
    FileCategory.APKS -> R.string.ime_files_cat_apks
    FileCategory.ARCHIVES -> R.string.ime_files_cat_archives
    FileCategory.LARGE -> R.string.ime_files_cat_large
}

internal fun categoryIcon(category: FileCategory): ImageVector = when (category) {
    FileCategory.IMAGES -> Icons.Outlined.Image
    FileCategory.VIDEOS -> Icons.Outlined.Movie
    FileCategory.AUDIO -> Icons.Outlined.MusicNote
    FileCategory.DOCUMENTS -> Icons.Outlined.Description
    FileCategory.APKS -> Icons.Outlined.Android
    FileCategory.ARCHIVES -> Icons.Outlined.FolderZip
    FileCategory.LARGE -> Icons.Outlined.Storage
}

@Composable
internal fun categoryColour(category: FileCategory): Color = when (category) {
    FileCategory.IMAGES -> kindColour(FileKind.IMAGE)
    FileCategory.VIDEOS -> kindColour(FileKind.VIDEO)
    FileCategory.AUDIO -> kindColour(FileKind.AUDIO)
    FileCategory.DOCUMENTS -> kindColour(FileKind.DOC)
    FileCategory.APKS -> kindColour(FileKind.APK)
    FileCategory.ARCHIVES -> kindColour(FileKind.ARCHIVE)
    FileCategory.LARGE -> kindColour(FileKind.PDF)
}

internal val DownloadsIcon: ImageVector get() = Icons.Outlined.Download

/**
 * A file's face: its own thumbnail for a photo or a video (when
 * [thumbnails] is on), else its kind's icon on a tint of its colour with the
 * extension as a badge. [picked] draws a tick in the accent instead.
 */
@Composable
internal fun KindTile(
    entry: FileEntry,
    side: Dp,
    picked: Boolean,
    thumbnails: Boolean,
    modifier: Modifier = Modifier,
    corner: Dp = 9.dp,
    iconScale: Float = 0.56f,
    badge: Boolean = true,
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val kind = FileKinds.of(entry)
    val colour = kindColour(kind)
    val shape = RoundedCornerShape(corner)
    Box(modifier.size(side), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(shape).background(if (picked) kb.accent else colour.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            val media = kind == FileKind.IMAGE || kind == FileKind.VIDEO
            when {
                picked -> Icon(Icons.Outlined.Check, contentDescription = null, tint = com.wasimaster.wmkeyboard.ime.ui.maxContrastOn(kb.accent), modifier = Modifier.size(side * 0.5f))
                media && thumbnails -> {
                    val thumb = remember(entry.path, entry.modifiedMs) {
                        MediaThumb(DocFiles.uriOf(context, entry.path), kind == FileKind.VIDEO, THUMB_PX, entry.modifiedMs / 1000)
                    }
                    AsyncImage(
                        model = thumb,
                        contentDescription = null,
                        imageLoader = rememberGalleryImageLoader(),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> Icon(kindIcon(kind), contentDescription = null, tint = colour, modifier = Modifier.size(side * iconScale))
            }
        }
        val label = if (badge && !picked && kind != FileKind.FOLDER) FileKinds.badgeOf(entry.name) else ""
        if (label.isNotEmpty() && side >= 30.dp && !(thumbnails && (kind == FileKind.IMAGE || kind == FileKind.VIDEO))) {
            Text(
                label,
                color = Color(0xFF15161A),
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.5.dp, kb.board, RoundedCornerShape(4.dp))
                    .background(colour)
                    .padding(horizontal = 3.dp, vertical = 0.5.dp),
            )
        }
    }
}

/** A row's second line: size and date for a file, the date for a folder. */
internal fun entryDetail(context: Context, entry: FileEntry): String {
    val date = if (entry.modifiedMs > 0) {
        DateUtils.formatDateTime(context, entry.modifiedMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)
    } else {
        ""
    }
    return when {
        entry.isDirectory -> date
        date.isEmpty() -> Formatter.formatShortFileSize(context, entry.size)
        else -> Formatter.formatShortFileSize(context, entry.size) + " · " + date
    }
}

internal fun bytes(context: Context, value: Long): String = Formatter.formatShortFileSize(context, value)

/** A plain bar: the track in the chip colour, the fill in [colour]. */
@Composable
internal fun MeterBar(fraction: Float, colour: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(height)
    Box(modifier.height(height).clip(shape).background(kb.chip)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(shape)
                .background(colour),
        )
    }
}

internal const val THUMB_PX = 192
