package com.wasimaster.wmkeyboard.app

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.transliteration.Lipi
import com.wasimaster.wmkeyboard.core.transliteration.LipiScheme

/**
 * vidyut-lipi's library (the `lipi` gate): where it stands, with Download
 * while it is missing. Shown beside both of the settings that need it, Convert
 * script's on Selection actions and Sanskrit's Write in another script.
 */
@Composable
internal fun LipiLibraryRow() {
    val module by Lipi.moduleState.collectAsState()
    WmRow(
        title = stringResource(R.string.lipi_library_title),
        subtitle = stringResource(
            when (module) {
                ModuleState.Installed -> R.string.lipi_library_ready
                is ModuleState.Installing -> R.string.lipi_library_installing
                is ModuleState.Missing -> R.string.lipi_library_missing
            },
        ),
        trailing = {
            if (module is ModuleState.Missing && InternetPermission.granted) {
                TextButton(onClick = { Lipi.requestModule() }) {
                    Text(stringResource(CommonR.string.common_download))
                }
            }
        },
    )
}

/** The romanisations plain letters can be read as, by their stored name, for the choice on Selection actions. */
internal val LipiPlainLetterOptions: List<Pair<String, String>> =
    LipiScheme.asciiInputs.map { it.native to it.label }
