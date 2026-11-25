package com.wasimaster.wmkeyboard.ime.ui.files

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.files.FileKind
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Link
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tag
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.files.FileAction
import com.wasimaster.wmkeyboard.ime.files.InfoUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.cardShape
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.codeFontFamily

/**
 * The Properties card, as the owner picked it: a file gets the detail list
 * (5A), every fact on its own line and the SHA-256 worked out on demand; a
 * folder gets tiles for its size and what it holds and a bar per kind of
 * what takes the space (5B), filling in while it is counted.
 */
@Composable
internal fun InfoFace(info: InfoUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(Modifier.fillMaxSize().background(kb.board)) {
        Row(Modifier.fillMaxWidth().background(kb.popup).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(icon = Icons.Outlined.ChevronLeft, description = stringResource(R.string.ime_files_preview_back), size = 34.dp) { act(FileAction.CloseInfo) }
            Text(
                stringResource(R.string.ime_files_info),
                color = kb.popupText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
        }
        if (info.entry.isDirectory) FolderInfo(info, act) else FileFacts(info, act)
    }
}

@Composable
private fun FileFacts(info: InfoUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val entry = info.entry
    val facts = info.facts
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.fillMaxWidth().background(kb.popup.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KindTile(entry, if (info.kind == FileKind.IMAGE || info.kind == FileKind.VIDEO) 96.dp else 56.dp, picked = false, thumbnails = true, corner = 14.dp)
            Spacer(Modifier.height(8.dp))
            Text(entry.name, color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 3)
            Text(
                stringResource(kindName(info.kind)) + " · " + bytes(context, entry.size),
                color = kb.secondaryText,
                fontSize = 12.sp,
            )
        }
        Fact(stringResource(R.string.ime_files_info_location), info.location) {
            Icon(
                Icons.Outlined.Link,
                contentDescription = stringResource(R.string.ime_files_copy_path),
                tint = kb.accent,
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(50)).clickable { act(FileAction.CopyPath) }.padding(6.dp),
            )
        }
        if (entry.modifiedMs > 0) Fact(stringResource(R.string.ime_files_info_modified), when_(context, entry.modifiedMs))
        Fact(stringResource(R.string.ime_files_info_size), stringResource(R.string.ime_files_info_bytes, "%,d".format(entry.size)))
        val w = facts.width
        val h = facts.height
        if (w != null && h != null) {
            val mp = w.toLong() * h / 1_000_000.0
            Fact(
                stringResource(R.string.ime_files_info_dimensions),
                "$w × $h" + if (info.kind == FileKind.IMAGE && mp >= 0.1) " (%.1f MP)".format(mp) else "",
            )
        }
        facts.durationMs?.let { Fact(stringResource(R.string.ime_files_info_length), DateUtils.formatElapsedTime(it / 1000)) }
        facts.camera?.let { Fact(stringResource(R.string.ime_files_info_camera), listOfNotNull(it, facts.exposure).joinToString(" · ")) }
        facts.takenMs?.let { Fact(stringResource(R.string.ime_files_info_taken), when_(context, it)) }
        facts.artist?.let { Fact(stringResource(R.string.ime_files_info_artist), it) }
        facts.album?.let { Fact(stringResource(R.string.ime_files_info_album), it) }
        facts.pages?.let { Fact(stringResource(R.string.ime_files_info_pages), it.toString()) }
        facts.appPackage?.let { Fact(stringResource(R.string.ime_files_info_package), it) }
        facts.appVersion?.let { Fact(stringResource(R.string.ime_files_app_version), it) }
        if (entry.mime.isNotEmpty()) Fact(stringResource(R.string.ime_files_info_type), entry.mime)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Tag, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            val hash = info.hash
            when {
                info.hashing -> {
                    Text("SHA-256", color = kb.suggestionText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    CircularProgressIndicator(Modifier.size(16.dp), color = kb.secondaryText, strokeWidth = 2.dp)
                }
                hash == null -> {
                    Text("SHA-256", color = kb.suggestionText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    ToolPanelChip(stringResource(R.string.ime_files_info_hash)) { act(FileAction.ComputeHash) }
                }
                hash.isEmpty() -> Text(stringResource(R.string.ime_files_info_hash_failed), color = kb.secondaryText, fontSize = 12.sp)
                else -> Column(Modifier.weight(1f)) {
                    Text("SHA-256", color = kb.secondaryText, fontSize = 11.sp)
                    Text(hash, color = kb.suggestionText, fontSize = 11.sp, fontFamily = codeFontFamily())
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String, trailing: (@Composable () -> Unit)? = null) {
    val kb = LocalKbTheme.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = kb.secondaryText, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Text(value, color = kb.suggestionText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

private fun when_(context: android.content.Context, ms: Long): String =
    DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)

@Composable
private fun FolderInfo(info: InfoUi, act: (FileAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val tally = info.tally
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                KindTile(info.entry, 48.dp, picked = false, thumbnails = false, corner = 12.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(info.entry.name, color = kb.suggestionText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text(info.location, color = kb.secondaryText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    stringResource(R.string.ime_files_info_size),
                    tally?.let { bytes(context, it.bytes) } ?: "—",
                    stringResource(if (info.counting) R.string.ime_files_info_counting else R.string.ime_files_info_in_all),
                    Modifier.weight(1f),
                )
                StatTile(
                    stringResource(R.string.ime_files_info_inside),
                    "%,d".format(tally?.files ?: 0),
                    pluralStringResource(R.plurals.ime_files_files_word, tally?.files ?: 0),
                    Modifier.weight(1f),
                )
                StatTile(
                    stringResource(R.string.ime_files_info_folders),
                    "%,d".format(tally?.folders ?: 0),
                    stringResource(R.string.ime_files_info_below),
                    Modifier.weight(1f),
                )
            }
            if (info.counting) {
                androidx.compose.material3.LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp).height(3.dp),
                    color = kb.accent,
                    trackColor = kb.chip,
                )
            }
            val byKind = tally?.byKind.orEmpty().entries.sortedByDescending { it.value }.take(BREAKDOWN_ROWS)
            if (byKind.isNotEmpty() && (tally?.bytes ?: 0) > 0) {
                SectionLabel(stringResource(R.string.ime_files_info_space))
                byKind.forEach { (kind, size) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(kindColour(kind).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(kindIcon(kind), contentDescription = null, tint = kindColour(kind), modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(stringResource(kindName(kind)), color = kb.suggestionText, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                                Text(bytes(context, size), color = kb.secondaryText, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            MeterBar(size.toFloat() / tally!!.bytes, kindColour(kind), Modifier.fillMaxWidth(), height = 4.dp)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { act(FileAction.InfoDetails) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.ime_files_info_details), color = kb.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Icon(if (info.details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
            }
            if (info.details) {
                Fact(stringResource(R.string.ime_files_info_location), info.location)
                if (info.entry.modifiedMs > 0) Fact(stringResource(R.string.ime_files_info_modified), when_(context, info.entry.modifiedMs))
                tally?.let { Fact(stringResource(R.string.ime_files_info_size), stringResource(R.string.ime_files_info_bytes, "%,d".format(it.bytes))) }
            }
        }
        Row(Modifier.fillMaxWidth().background(kb.popup).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(R.string.ime_files_copy_path), modifier = Modifier.weight(1f), icon = Icons.Outlined.Link) { act(FileAction.CopyPath) }
            PillButton(
                stringResource(if (info.pinned) R.string.ime_files_unpin_desc else R.string.ime_files_pin_desc),
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.PushPin,
                primary = !info.pinned,
            ) { act(FileAction.TogglePin) }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    Column(modifier.clip(shape).background(kb.chip).padding(12.dp)) {
        Text(label, color = kb.secondaryText, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(value, color = kb.chipText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Text(sub, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
    }
}

private const val BREAKDOWN_ROWS = 6
