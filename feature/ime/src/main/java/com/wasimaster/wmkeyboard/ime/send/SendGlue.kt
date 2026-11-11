package com.wasimaster.wmkeyboard.ime.send

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.core.send.SendSink
import com.wasimaster.wmkeyboard.core.send.SendSinkFile
import com.wasimaster.wmkeyboard.ime.ClipboardWriteGate
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeReceivedFiles
import java.io.OutputStream

/**
 * Where a received file is written: Downloads/WM Keyboard, the same place a
 * computer's files land, through the KDE Connect tool's sink. The protocol
 * module only knows a stream and a "done".
 */
internal class SendFileSink(context: Context) : SendSink {
    private val files = KdeReceivedFiles(context)

    override fun create(name: String, size: Long, type: String): SendSinkFile? {
        val target = files.create(FOLDER_LABEL, withExtension(name, type), size) ?: return null
        return object : SendSinkFile {
            override val stream: OutputStream = target.stream
            override fun finish(success: Boolean): String? = target.finish(success)
        }
    }

    /** A name with no extension gets one from its type, so the file opens in something. */
    private fun withExtension(name: String, type: String): String {
        if (name.contains('.') && !name.endsWith('.')) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: return name
        return "$name.$ext"
    }

    private companion object {
        const val FOLDER_LABEL = "Send"
    }
}

/**
 * The buttons on the tool's notifications: copy the link an upload ended
 * with, open the file a download ended with. Not exported.
 */
class SendActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_COPY_LINK -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return
                ClipboardWriteGate.writeText(context, url, "Send link")
                WmNotifications.cancel(context, NotificationIds.SEND_UPLOAD)
            }
            ACTION_OPEN_FILE -> {
                val location = intent.getStringExtra(EXTRA_LOCATION) ?: return
                val type = intent.getStringExtra(EXTRA_TYPE) ?: "*/*"
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.parse(location), type)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                WmNotifications.cancel(context, NotificationIds.SEND_RECEIVE)
            }
        }
    }

    companion object {
        const val ACTION_COPY_LINK = "com.wasimaster.wmkeyboard.send.COPY_LINK"
        const val ACTION_OPEN_FILE = "com.wasimaster.wmkeyboard.send.OPEN_FILE"
        const val EXTRA_URL = "url"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_TYPE = "type"
    }
}

/**
 * "Send with WM Keyboard" in other apps' share sheets: an `activity-alias`
 * disabled in the manifest and switched on here while the tool is on and the
 * setting says so, so someone who never uses the tool never sees the entry.
 * The same dance as the KDE Connect tool's "Send to computer".
 */
object SendShareAlias {
    /** The manifest's alias; never rename it, launchers and share targets remember component names. */
    const val ALIAS = "com.wasimaster.wmkeyboard.app.SendShareAlias"


    /** The settings app's screens for the tool. */
    const val HISTORY_ROUTE = "send/history"
    const val INSTANCES_ROUTE = "send/instances"

    /** The documentation site, whose `/open/#` page hands a `wmkeyboard://` link to the app from a camera scan. */
    const val DOCS_BASE = "https://wmkeyboard.pages.dev"

    private var lastState: Int? = null

    fun sync(context: Context, wanted: Boolean) {
        val desired = if (wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        if (lastState == desired) return
        val pm = context.packageManager
        val component = ComponentName(context.packageName, ALIAS)
        runCatching {
            if (pm.getComponentEnabledSetting(component) != desired) {
                pm.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
            }
            lastState = desired
        }
    }
}
