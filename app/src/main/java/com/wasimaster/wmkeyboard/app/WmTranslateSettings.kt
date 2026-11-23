package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.ime.wmtranslate.TranslateShare
import com.wasimaster.wmkeyboard.ime.wmtranslate.TranslateWire
import com.wasimaster.wmkeyboard.ime.wmtranslate.WmTranslateBridge

/** The Translate tool's WM Translate group: whether the app may take this tool's setup. */
@Composable
internal fun WmTranslateLinkGroup() {
    val context = LocalContext.current
    val installed = remember { WmTranslateBridge.installed(context) }
    var mode by remember { mutableStateOf(TranslateShare.mode(context)) }
    SettingsGroup(stringResource(R.string.tooldetail_wmtranslate_group)) {
        if (!installed) {
            item { StateBanner(stringResource(R.string.tooldetail_wmtranslate_missing)) }
        } else {
            item {
                ChoiceSetting(
                    R.string.tooldetail_wmtranslate_share_title,
                    subtitle = stringResource(R.string.tooldetail_wmtranslate_share_subtitle),
                    info = stringResource(R.string.tooldetail_wmtranslate_share_info),
                    options = listOf(
                        TranslateWire.SHARE_NONE to stringResource(R.string.tooldetail_wmtranslate_share_off),
                        TranslateWire.SHARE_ONCE to stringResource(R.string.tooldetail_wmtranslate_share_once),
                        TranslateWire.SHARE_FOLLOW to stringResource(R.string.tooldetail_wmtranslate_share_follow),
                    ),
                    selected = mode,
                    default = TranslateWire.SHARE_NONE,
                ) { next ->
                    TranslateShare.set(context, next)
                    mode = next
                }
            }
        }
    }
}
