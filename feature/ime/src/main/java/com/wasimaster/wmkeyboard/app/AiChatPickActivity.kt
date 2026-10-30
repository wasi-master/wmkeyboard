package com.wasimaster.wmkeyboard.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.wasimaster.wmkeyboard.core.aichat.AiChatFile
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.aichat.AiChatFiles
import com.wasimaster.wmkeyboard.ime.aichat.AiChatImages
import java.io.File

/**
 * Invisible trampoline for the keyboard chat's "+" menu (#882): Image, File
 * and Photo are each an activity-result flow, which an input method cannot
 * run. The [GalleryPickActivity] shape, with a queue of its own.
 *
 * Unlike that one, the picks are copied in here, before this screen goes: a
 * document picker's read grant is revoked with the activity that asked
 * (#808), and a photo is not worth the risk either. The copies are the chat's
 * own pictures and files, named as a message carries them, so all the
 * keyboard does when the field comes back is add the names to its draft.
 */
class AiChatPickActivity : ComponentActivity() {

    /** What one trip to a picker produced, and why a pick did not arrive when one did not. */
    data class Picks(
        val images: List<String> = emptyList(),
        val files: List<AiChatFile> = emptyList(),
        /** A string resource, or 0 when everything picked arrived. */
        val notice: Int = 0,
    )

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ROOM = "room"
        private const val EXTRA_KEEP = "keep"
        private const val CAMERA_DIR = "ai_chat_camera"

        const val MODE_IMAGES = 0
        const val MODE_FILES = 1
        const val MODE_PHOTO = 2

        @Volatile
        private var pending: Picks? = null

        /** Hands over (and clears) what the last trip produced. */
        fun consume(): Picks? = synchronized(this) { pending.also { pending = null } }

        fun hasPending(): Boolean = pending != null

        /** Parks [picks] for the keyboard; its own file manager comes this way too. */
        internal fun offer(picks: Picks) = synchronized(this) { pending = picks }

        /**
         * Copies [uris] into the chat's own pictures or ([asFiles]) files, up
         * to [room] of them. Blocking: off the main thread.
         */
        internal fun importPicks(context: Context, uris: List<Uri>, asFiles: Boolean, room: Int, keep: Boolean): Picks {
            val images = ArrayList<String>()
            val files = ArrayList<AiChatFile>()
            var notice = 0
            for (uri in uris.take(room)) {
                val imported = if (asFiles) AiChatFiles.import(context, uri, keep) else AiChatFiles.Imported.Picture
                when (imported) {
                    AiChatFiles.Imported.Picture ->
                        AiChatImages.import(context, uri, keep)?.let(images::add)
                            ?: run { notice = R.string.ime_ai_chat_picture_unreadable }
                    is AiChatFiles.Imported.Done -> files += imported.file
                    AiChatFiles.Imported.TooBig -> notice = R.string.ime_ai_chat_file_too_big
                    AiChatFiles.Imported.Unreadable -> notice = R.string.ime_ai_chat_file_unreadable
                }
            }
            if (uris.size > room) notice = R.string.ime_ai_chat_attach_full
            return Picks(images, files, notice)
        }

        /** [room] is how many more the draft can take; [keep] says the chats are saved, so the copies are too. */
        fun intent(context: Context, mode: Int, room: Int, keep: Boolean): Intent =
            Intent(context, AiChatPickActivity::class.java)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_ROOM, room)
                .putExtra(EXTRA_KEEP, keep)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * The documents the File button offers: text, pictures, video and
         * audio, as #882 lists them, and PDF. A picker that cannot filter by
         * several types shows them all, and [AiChatFiles] sorts out the rest.
         */
        val FILE_TYPES = arrayOf("text/*", "image/*", "video/*", "audio/*", "application/pdf", "application/json")
    }

    private val room: Int get() = intent.getIntExtra(EXTRA_ROOM, 1).coerceAtLeast(1)
    private val keep: Boolean get() = intent.getBooleanExtra(EXTRA_KEEP, true)

    private val documents = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { deliver(it) }

    private val photo = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val shot = cameraFile()
        deliver(if (taken && shot.length() > 0) listOf(Uri.fromFile(shot)) else emptyList()) { runCatching { shot.delete() } }
    }

    /**
     * The app declares the camera permission for its own camera tool, and
     * Android then refuses the capture intent to an app that declares it
     * without holding it. So Photo asks first, as the camera tool does.
     */
    private val cameraGrant = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            offer(Picks(notice = R.string.ime_ai_chat_photo_no_permission))
            finish()
        }
    }

    private var multiple: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>? = null
    private val single = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { deliver(listOfNotNull(it)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Registered before STARTED, with the cap the intent asks for.
        if (room > 1) {
            multiple = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(room)) { deliver(it) }
        }
        super.onCreate(savedInstanceState)
        // A restarted activity is already waiting on the picker it started.
        if (savedInstanceState != null) return
        val launched = runCatching {
            when (intent.getIntExtra(EXTRA_MODE, MODE_IMAGES)) {
                MODE_FILES -> documents.launch(FILE_TYPES)
                MODE_PHOTO -> if (
                    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                ) {
                    launchCamera()
                } else {
                    cameraGrant.launch(Manifest.permission.CAMERA)
                }
                else -> {
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    multiple?.launch(request) ?: single.launch(request)
                }
            }
        }.isSuccess
        if (!launched) {
            offer(Picks(notice = R.string.ime_ai_chat_attach_no_picker))
            finish()
        }
    }

    private fun launchCamera() {
        val shot = cameraFile().apply { parentFile?.mkdirs(); delete() }
        val started = runCatching {
            photo.launch(FileProvider.getUriForFile(this, "$packageName.clipboard", shot))
        }.isSuccess
        if (!started) {
            offer(Picks(notice = R.string.ime_ai_chat_attach_no_picker))
            finish()
        }
    }

    private fun cameraFile(): File = File(File(cacheDir, CAMERA_DIR), "photo.jpg")

    /** Copies [uris] in off the main thread, parks the names, and goes. */
    private fun deliver(uris: List<Uri>, after: () -> Unit = {}) {
        if (uris.isEmpty()) {
            after()
            finish()
            return
        }
        val app = applicationContext
        val keepCopies = keep
        val cap = room
        val asFiles = intent.getIntExtra(EXTRA_MODE, MODE_IMAGES) == MODE_FILES
        Thread {
            offer(importPicks(app, uris, asFiles, cap, keepCopies))
            after()
            runOnUiThread { finish() }
        }.start()
    }
}
