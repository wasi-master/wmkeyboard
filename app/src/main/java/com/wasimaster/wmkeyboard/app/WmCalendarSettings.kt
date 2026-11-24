package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.tools.calendar.CalendarBackend
import com.wasimaster.wmkeyboard.ime.wmcalendar.WmCalendarLink

/**
 * The Calendar tool's WM Calendar group: which calendar the tool reads and adds to, whether
 * WM Calendar may take the keyboard's AI setup for its quick add, and whether it may have
 * pictures read into text by the keyboard's text reader.
 */
@Composable
internal fun WmCalendarLinkGroup() {
    val context = LocalContext.current
    val installed = remember { WmCalendarLink.installed(context) }
    var backend by remember { mutableStateOf(WmCalendarLink.backendChoice(context)) }
    var share by remember { mutableStateOf(WmCalendarLink.share(context)) }
    var images by remember { mutableStateOf(WmCalendarLink.readsImages(context)) }
    SettingsGroup(stringResource(R.string.tooldetail_wmcalendar_group)) {
        if (!installed) {
            item { StateBanner(stringResource(R.string.tooldetail_wmcalendar_missing)) }
        } else {
            item {
                ChoiceSetting(
                    R.string.tooldetail_wmcalendar_backend_title,
                    subtitle = stringResource(R.string.tooldetail_wmcalendar_backend_subtitle),
                    info = stringResource(R.string.tooldetail_wmcalendar_backend_info),
                    options = listOf(
                        CalendarBackend.DEVICE to stringResource(R.string.tooldetail_wmcalendar_backend_device),
                        CalendarBackend.WM_CALENDAR to stringResource(R.string.tooldetail_wmcalendar_backend_app),
                    ),
                    selected = backend,
                    default = CalendarBackend.DEVICE,
                ) { next ->
                    WmCalendarLink.setBackendChoice(context, next)
                    backend = next
                }
            }
            item {
                ChoiceSetting(
                    R.string.tooldetail_wmcalendar_share_title,
                    subtitle = stringResource(R.string.tooldetail_wmcalendar_share_subtitle),
                    info = stringResource(R.string.tooldetail_wmcalendar_share_info),
                    options = listOf(
                        WmCalendarLink.SHARE_NONE to stringResource(R.string.tooldetail_wmcalendar_share_off),
                        WmCalendarLink.SHARE_ONCE to stringResource(R.string.tooldetail_wmcalendar_share_once),
                        WmCalendarLink.SHARE_FOLLOW to stringResource(R.string.tooldetail_wmcalendar_share_follow),
                    ),
                    selected = share,
                    default = WmCalendarLink.SHARE_NONE,
                ) { next ->
                    WmCalendarLink.setShare(context, next)
                    share = next
                }
            }
            item {
                ToggleSetting(
                    R.string.tooldetail_wmcalendar_images_title,
                    stringResource(R.string.tooldetail_wmcalendar_images_subtitle),
                    images,
                    info = stringResource(R.string.tooldetail_wmcalendar_images_info),
                    default = false,
                ) { on ->
                    WmCalendarLink.setReadsImages(context, on)
                    images = on
                }
            }
        }
    }
}
