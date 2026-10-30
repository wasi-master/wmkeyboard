package com.wasimaster.wmkeyboard.ime.files

import android.content.Context
import android.content.Intent
import com.wasimaster.wmkeyboard.app.AiChatPickActivity
import com.wasimaster.wmkeyboard.app.GalleryPickActivity
import com.wasimaster.wmkeyboard.app.SendPickActivity
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub

/**
 * One place in the keyboard that wants a file: what it takes, how many, and
 * the invisible screen that gets it from Android's picker, which is still the
 * way in a Play build, with no grant, and for anything not on the phone.
 */
sealed interface FilePickRequest {
    /** The types it takes, wildcards allowed; empty takes anything. */
    val mimes: List<String>
    val limit: Int

    fun systemIntent(context: Context): Intent

    data class GalleryFiles(override val limit: Int) : FilePickRequest {
        override val mimes: List<String> get() = emptyList()
        override fun systemIntent(context: Context) = GalleryPickActivity.intent(context, files = true, limit = limit)
    }

    data class GalleryMedia(override val limit: Int) : FilePickRequest {
        override val mimes: List<String> get() = listOf("image/*", "video/*")
        override fun systemIntent(context: Context) = GalleryPickActivity.intent(context, files = false, limit = limit)
    }

    data object SendFiles : FilePickRequest {
        override val mimes: List<String> get() = emptyList()
        override val limit: Int get() = MANY
        override fun systemIntent(context: Context) = SendPickActivity.intent(context)
    }

    data class AiImages(val room: Int, val keep: Boolean) : FilePickRequest {
        override val mimes: List<String> get() = listOf("image/*")
        override val limit: Int get() = room
        override fun systemIntent(context: Context) =
            AiChatPickActivity.intent(context, AiChatPickActivity.MODE_IMAGES, room, keep)
    }

    data class AiFiles(val room: Int, val keep: Boolean) : FilePickRequest {
        override val mimes: List<String> get() = AiChatPickActivity.FILE_TYPES.toList()
        override val limit: Int get() = room
        override fun systemIntent(context: Context) =
            AiChatPickActivity.intent(context, AiChatPickActivity.MODE_FILES, room, keep)
    }

    data class KdeFiles(val deviceId: String) : FilePickRequest {
        override val mimes: List<String> get() = emptyList()
        override val limit: Int get() = MANY
        override fun systemIntent(context: Context): Intent =
            Intent().setClassName(context.packageName, KdeConnectHub.PICKER_ACTIVITY)
                .putExtra(KdeConnectHub.EXTRA_DEVICE, deviceId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    data object QrPicture : FilePickRequest {
        override val mimes: List<String> get() = listOf("image/*")
        override val limit: Int get() = 1
        override fun systemIntent(context: Context) = GalleryPickActivity.qrIntent(context)
    }

    data object OcrPicture : FilePickRequest {
        override val mimes: List<String> get() = listOf("image/*")
        override val limit: Int get() = 1
        override fun systemIntent(context: Context) = GalleryPickActivity.ocrIntent(context)
    }

    data object CameraPicture : FilePickRequest {
        override val mimes: List<String> get() = listOf("image/*")
        override val limit: Int get() = 1
        override fun systemIntent(context: Context) = GalleryPickActivity.cameraIntent(context)
    }

    companion object {
        private const val MANY = 100
    }
}

/**
 * Where a pick goes: the keyboard's own file manager, or Android's picker. The
 * tools ask here and do not need to know which. The service registers [panel]
 * for as long as it lives, and a panel that can answer does.
 */
object FilePicks {

    /** Opens the manager for a request, or answers false when it should not be the one to. */
    @Volatile
    var panel: ((FilePickRequest) -> Boolean)? = null

    /** Whether a pick made now would stay in the keyboard; a tool that must keep its state asks first. */
    @Volatile
    var panelReady: () -> Boolean = { false }

    fun open(context: Context, request: FilePickRequest) {
        if (panel?.invoke(request) == true) return
        context.startActivity(request.systemIntent(context))
    }

    /**
     * The whole rule. Not over the lock screen, whatever the setting says: a
     * phone's files are not for whoever is holding it. Without the grant the
     * panel still opens, to explain and to ask.
     */
    fun usesPanel(channelAllows: Boolean, settingOn: Boolean, userUnlocked: Boolean, deviceLocked: Boolean): Boolean =
        channelAllows && settingOn && userUnlocked && !deviceLocked
}
