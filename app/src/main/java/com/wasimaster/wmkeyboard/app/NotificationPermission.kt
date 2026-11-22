package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.wasimaster.wmkeyboard.core.notify.BackgroundTasks
import com.wasimaster.wmkeyboard.core.notify.DownloadNotifications
import com.wasimaster.wmkeyboard.core.notify.DownloadProgress
import com.wasimaster.wmkeyboard.core.notify.NotificationSwitches
import com.wasimaster.wmkeyboard.core.notify.TaskState
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import kotlinx.coroutines.flow.Flow

/**
 * Asking Android to let this app post notifications, at the one moment the
 * answer is obviously worth something.
 *
 * The permission is never asked for on launch. It is asked for the first time
 * the app is about to do something whose result the user would want told
 * about — a download starting, the shade controls being switched on — because
 * a permission dialog with a reason behind it is one people can answer, and a
 * cold one on first run is one they dismiss. Asked once and never again: see
 * [NotificationSwitches.permissionOffered].
 */
@Composable
internal fun rememberNotificationPermissionRequest(onGranted: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val request = rememberDisclosedPermissionRequest(
        PermissionDisclosures.NOTIFICATIONS,
        onGranted = onGranted,
    )
    return {
        NotificationSwitches.markPermissionOffered(context)
        request()
    }
}

/**
 * What a download tells the shade about itself: its key, its name, and the
 * progress to follow. See [rememberDownloadNotifier].
 */
internal typealias DownloadNotifier = (String, String, Flow<DownloadProgress>) -> Unit

/**
 * Starting a download and letting the shade follow it, as one act.
 *
 * Every download in this app is started from a screen and finishes somewhere
 * else — after the screen is gone, often after the app is. The returned
 * function is what a download button calls instead of the manager's `start`
 * alone: it hands the manager's own progress to the shade, and the first time
 * it runs it offers the notification permission.
 *
 * [key] must be the same string the manager keys its state by, so a second
 * press on the same download updates one notification rather than opening a
 * second.
 */
@Composable
internal fun rememberDownloadNotifier(): DownloadNotifier {
    val context = LocalContext.current
    val offerPermission = rememberNotificationPermissionOffer()
    return { key, title, progress ->
        DownloadNotifications.watch(context, key, title, progress)
        offerPermission()
    }
}

/**
 * The same, for a download that reports through callbacks rather than a flow.
 *
 * Handwriting models, the system's offline voice model and Keyman rule files
 * are suspend calls with a progress lambda; the handle is what they push into.
 * See [DownloadNotifications.start].
 */
@Composable
internal fun rememberDownloadStarter(): (String, String) -> DownloadNotifications.Handle {
    val context = LocalContext.current
    val offerPermission = rememberNotificationPermissionOffer()
    return { key, title ->
        val handle = DownloadNotifications.start(context, key, title)
        offerPermission()
        handle
    }
}

/**
 * The once-ever ask both of the above make when a download would have shown
 * something. Internal for the screens whose downloads post through a manager
 * of their own and so have no handle to be given.
 */
@Composable
internal fun rememberNotificationPermissionOffer(): () -> Unit {
    val context = LocalContext.current
    val ask = rememberNotificationPermissionRequest()
    return {
        // Only ever once, and only where it would have shown something: an app
        // that cannot post is an app with nothing to ask about yet.
        if (!NotificationSwitches.permissionOffered(context) && !WmNotifications.canPost(context)) {
            ask()
        }
    }
}

/**
 * Starting a long job on [BackgroundTasks] from a screen (#1522), so it keeps
 * running when the screen goes and the shade shows how far along it is. The
 * first time, it offers the notification permission, as a download does.
 * Returns false when a job under the same key is already running.
 */
@Composable
internal fun rememberTaskStarter(): (
    key: String,
    title: String,
    failure: String,
    work: suspend (BackgroundTasks.Progress) -> BackgroundTasks.Outcome,
) -> Boolean {
    val context = LocalContext.current
    val offerPermission = rememberNotificationPermissionOffer()
    return { key, title, failure, work ->
        val started = BackgroundTasks.start(context, key, title, failure, work)
        if (started) offerPermission()
        started
    }
}

/** The job under [key] as it stands, or null when there is none to show. */
@Composable
internal fun rememberTaskState(key: String): TaskState? {
    val all by BackgroundTasks.all.collectAsState()
    return all[key]
}
