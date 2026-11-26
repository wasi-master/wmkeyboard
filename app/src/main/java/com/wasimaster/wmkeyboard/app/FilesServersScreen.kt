package com.wasimaster.wmkeyboard.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository

/**
 * Servers and clouds (`files/servers`), the Files tool's page, which the
 * keyboard's Files tool opens too: the servers and cloud accounts
 * the Files tool browses, set up with the same editor as Backup's locations
 * and kept in the same list. One added here gets no automatic backups unless
 * it is ticked on Automatic backup; one set up for backups shows here too.
 */
@Composable
internal fun FilesServersScreen(repository: SettingsRepository, settings: LiveSettings) {
    var message by remember { mutableStateOf<String?>(null) }

    LocationsGroup(repository, settings.watch { it.autoBackup }, forFiles = true) { message = it }

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
}
