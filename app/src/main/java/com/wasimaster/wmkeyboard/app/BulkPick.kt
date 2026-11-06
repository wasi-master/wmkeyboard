package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileUpload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The dock while rows of a list are picked: how many, every row at once,
 * export, delete, and whatever a list adds of its own in [extra]. Close (or
 * Back, which the list handles) stops picking.
 *
 * The bar the theme gallery got first, for every list of things the user
 * makes or imports: layouts, icon packs, sticker packs.
 */
@Composable
internal fun PickBar(
    count: Int,
    allPicked: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onExport: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    extra: @Composable RowScope.() -> Unit = {},
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(CommonR.string.common_cancel))
            }
            Text(
                pluralStringResource(R.plurals.pick_count, count, count),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            extra()
            IconButton(onClick = onSelectAll, enabled = !allPicked) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(CommonR.string.common_select_all))
            }
            if (onExport != null) {
                IconButton(onClick = onExport) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(R.string.pick_export_action))
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(CommonR.string.common_delete))
                }
            }
        }
    }
}

/**
 * The one message an import of several files ends on: how many came in, then
 * the names of the files (or packs inside a zip) that did not.
 */
internal fun bulkImportSummary(context: Context, imported: Int, failed: List<String>): String {
    val head = context.resources.getQuantityString(R.plurals.pick_import_summary, imported, imported)
    if (failed.isEmpty()) return head
    return head + "\n\n" + context.getString(R.string.pick_import_failed_files, failed.joinToString(", "))
}

/** [picked] with [id] flipped: the long press and, while picking, the tap. */
internal fun <K> Set<K>.toggled(id: K): Set<K> = if (id in this) this - id else this + id

/**
 * Several pack files in one zip, for exporting picked icon or sticker packs at
 * once. Each pack is written exactly as its own export writes it, as an entry
 * of its own, so a pack taken out of the zip by hand imports on its own too.
 * Import knows a bundle by its first entry's name ([isBundle]): a pack file
 * starts with its manifest, never with another pack.
 */
internal object PackBundle {
    const val MIME_TYPE = "application/zip"

    /** Every entry is a whole pack, so this is per entry, not for the zip. */
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** `wmkeyboard-<kind>-<date>.zip`. */
    fun fileName(kind: String, now: Long = System.currentTimeMillis()): String =
        "wmkeyboard-$kind-${SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(now))}.zip"

    /**
     * Writes one entry per pack in [packs]: (file name, writer). A pack's writer
     * closes the stream it is handed, as every pack file writer here does, so
     * each is written to memory first and copied in.
     */
    fun write(out: OutputStream, packs: List<Pair<String, (OutputStream) -> Unit>>) {
        val used = HashSet<String>()
        ZipOutputStream(out.buffered()).use { zip ->
            for ((name, writePack) in packs) {
                val bytes = ByteArrayOutputStream().also(writePack).toByteArray()
                zip.putNextEntry(ZipEntry(uniqueName(name, used)))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** True when the zip at [uri] is a bundle of `.[extension]` files. Blocking. */
    fun isBundle(context: Context, uri: Uri, extension: String): Boolean = runCatching {
        context.contentResolver.requireInputStream(uri).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                zip.nextEntry?.name?.endsWith(".$extension", ignoreCase = true) == true
            }
        }
    }.getOrDefault(false)

    /**
     * Hands every `.[extension]` entry of the bundle at [uri] to [each], by its
     * name, as a stream over the entry alone. Other entries are skipped, and one
     * past the size cap is reported as unreadable. Blocking.
     */
    fun forEachPack(
        context: Context,
        uri: Uri,
        extension: String,
        each: (name: String, input: InputStream?) -> Unit,
    ) {
        context.contentResolver.requireInputStream(uri).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !entry.name.endsWith(".$extension", ignoreCase = true)) continue
                    val name = entry.name.substringAfterLast('/')
                    val bytes = readCapped(zip)
                    each(name, bytes?.inputStream())
                }
            }
        }
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        val clean = name.replace('/', '_').replace('\\', '_').ifBlank { "pack" }
        if (used.add(clean)) return clean
        val dot = clean.indexOf('.').takeIf { it > 0 } ?: clean.length
        var n = 2
        while (true) {
            val candidate = clean.substring(0, dot) + " ($n)" + clean.substring(dot)
            if (used.add(candidate)) return candidate
            n++
        }
    }
}
