package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.notify.TaskState

/**
 * A row standing in for the button of a job that is running (#1522): what it
 * is, how far along in words, and a bar. An endless bar while the size is not
 * known yet.
 */
@Composable
internal fun TaskProgressRow(
    title: String,
    task: TaskState.Running,
    icon: ImageVector? = null,
    accent: androidx.compose.ui.graphics.Color? = null,
) {
    WmRow(
        title = title,
        subtitle = task.text,
        icon = icon,
        accent = accent,
        enabled = false,
        supporting = {
            val modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
            if (task.total > 0L) {
                LinearProgressIndicator(
                    progress = { (task.done.toFloat() / task.total).coerceIn(0f, 1f) },
                    modifier = modifier,
                )
            } else {
                LinearProgressIndicator(modifier = modifier)
            }
        },
    )
}
