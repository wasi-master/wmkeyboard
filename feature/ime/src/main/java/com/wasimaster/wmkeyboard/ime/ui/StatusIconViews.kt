package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Alarm
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cake
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Celebration
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Checklist
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HourglassBottom
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Psychology
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timer
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.StatusAction
import com.wasimaster.wmkeyboard.ime.StatusActions
import com.wasimaster.wmkeyboard.ime.StatusItem
import com.wasimaster.wmkeyboard.ime.StatusKind

/**
 * The toolbar's status icons (#1453), at its far end beside the power-saving
 * badge: small, in the badge's colour, each a tap from what it is about. An
 * alarm's or a special day's icon brings its chip back to the strip; the
 * rest open their tool.
 */
@Composable
internal fun StatusToolbarIcons(icons: List<StatusItem>) {
    for (item in icons) {
        Icon(
            statusIcon(item),
            contentDescription = statusText(item),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable { StatusActions.handler(item, StatusAction.ICON) }
                .padding(start = 4.dp, top = 4.dp, end = 6.dp, bottom = 4.dp)
                .size(16.dp),
        )
    }
}

/**
 * The alarm or special-day chip on the idle strip (#1453), in the due task
 * chip's dashed outline: its words, which open where it lives, Stop on an
 * alarm, and a close that leaves the icon on the toolbar.
 */
@Composable
internal fun StatusSuggestionChip(item: StatusItem, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val tint = kb.accent
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(50))
            .dashedOutline(tint.copy(alpha = 0.6f), 50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable { StatusActions.handler(item, StatusAction.OPEN) }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(statusIcon(item), contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Text(
                text = statusText(item),
                color = kb.suggestionText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.kind == StatusKind.ALARM) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .clickable { StatusActions.handler(item, StatusAction.STOP) }
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.ime_status_alarm_stop),
                    color = tint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(CircleShape)
                .clickable { StatusActions.handler(item, StatusAction.CLOSE) }
                .padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_status_chip_close_desc),
                tint = tint.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun statusIcon(item: StatusItem): ImageVector = when (item.kind) {
    StatusKind.ALARM -> Icons.Outlined.Alarm
    StatusKind.SPECIAL_DAY -> if (item.fromNote) Icons.Outlined.Cake else Icons.Outlined.Celebration
    StatusKind.TIMER -> Icons.Outlined.HourglassBottom
    StatusKind.POMODORO -> Icons.Outlined.Psychology
    StatusKind.STOPWATCH -> Icons.Outlined.Timer
    StatusKind.TODO -> Icons.Outlined.Checklist
}

/** The chip's words, which are also the icon's description. */
@Composable
private fun statusText(item: StatusItem): String = when (item.kind) {
    StatusKind.ALARM -> {
        val context = LocalContext.current
        val time = remember(item.at) { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(item.at)) }
        if (item.title.isBlank()) {
            stringResource(R.string.ime_status_alarm_chip, time)
        } else {
            stringResource(R.string.ime_status_alarm_chip_label, item.title, time)
        }
    }
    StatusKind.SPECIAL_DAY -> stringResource(R.string.ime_status_special_day_chip, item.title)
    StatusKind.TIMER -> stringResource(R.string.ime_status_timer_desc)
    StatusKind.POMODORO -> stringResource(R.string.ime_status_pomodoro_desc)
    StatusKind.STOPWATCH -> stringResource(R.string.ime_status_stopwatch_desc)
    StatusKind.TODO -> stringResource(R.string.ime_status_todo_desc, item.title)
}
