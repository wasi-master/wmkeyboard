package com.wasimaster.wmkeyboard.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import java.util.UUID

/**
 * The one way the keyboard writes the system clipboard (#1215, from saen1a's
 * prototype).
 *
 * Panels, tools, the copy listener's tidied write-back and Undo all go
 * through here, so the two things every write may need are done in one place:
 *
 * - **The sensitive flag.** Android 13's `ClipDescription.EXTRA_IS_SENSITIVE`
 *   tells the system's copy preview, and every clipboard manager that reads
 *   it, that the clip is a secret. The key predates the constant, so it is
 *   written as a literal on every release. A caller says whether its text is
 *   one; the password generator always does.
 * - **The self-write token.** A write the copy listener must let go by (a
 *   write-back, an Undo) carries a one-shot token from [SelfWriteLedger], and
 *   the listener asks [consumeSelfWrite] instead of comparing text.
 *
 * Every write is also stamped as the keyboard's own ([isOwnWrite]), so the
 * listener can tell a secret this keyboard copied from one a password manager
 * copied: only the second is the manager's to wipe (see the listener).
 *
 * A write never throws: the clipboard service can refuse one (a background
 * restriction, a dead binder) and a copy button is not worth a crash.
 */
object ClipboardWriteGate {

    /**
     * `ClipDescription.EXTRA_IS_SENSITIVE`, spelled out so it can be written
     * and read on every API level rather than only where the constant exists.
     */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /** A token in the description's extras; private to this keyboard. */
    private const val SELF_WRITE_TOKEN_KEY = "com.wasimaster.wmkeyboard.SELF_WRITE_TOKEN"

    /** Marks a clip this keyboard wrote, whether or not the listener should skip it. */
    private const val OWN_WRITE_KEY = "com.wasimaster.wmkeyboard.OWN_WRITE"

    /** Writes [text], marked sensitive when [sensitive]; [selfWrite] makes the listener skip it once. */
    fun writeText(
        context: Context,
        text: CharSequence,
        label: CharSequence? = "",
        sensitive: Boolean = false,
        selfWrite: Boolean = false,
    ): Boolean = writeClip(context, ClipData.newPlainText(label, text), sensitive, selfWrite)

    /** Writes [uri] as [mimeType] (blank: asked of the resolver), marked sensitive when [sensitive]. */
    fun writeUri(
        context: Context,
        uri: Uri,
        label: CharSequence = "",
        mimeType: String = "",
        sensitive: Boolean = false,
    ): Boolean {
        val clip = runCatching {
            if (mimeType.isBlank()) {
                ClipData.newUri(context.contentResolver, label, uri)
            } else {
                ClipData(ClipDescription(label, arrayOf(mimeType)), ClipData.Item(uri))
            }
        }.getOrNull() ?: return false
        return writeClip(context, clip, sensitive)
    }

    /** Writes [clip] as it is, plus the marks above. */
    fun writeClip(
        context: Context,
        clip: ClipData,
        sensitive: Boolean = false,
        selfWrite: Boolean = false,
    ): Boolean = runCatching {
        stamp(clip, sensitive, selfWrite)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }.isSuccess

    /** Empties the system clipboard, with the stand-in older releases need. */
    fun clear(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }.isSuccess

    /** Whether [clip] is a [selfWrite] this gate made and the listener has not yet let go by. */
    fun consumeSelfWrite(clip: ClipData?): Boolean =
        SelfWriteLedger.consume(runCatching { clip?.description?.extras?.getString(SELF_WRITE_TOKEN_KEY) }.getOrNull())

    /** Whether this keyboard wrote [clip]. */
    fun isOwnWrite(clip: ClipData?): Boolean =
        runCatching { clip?.description?.extras?.getBoolean(OWN_WRITE_KEY, false) == true }.getOrDefault(false)

    /** Whether the app that wrote [clip] marked it as a secret. */
    fun isMarkedSensitive(clip: ClipData?): Boolean =
        runCatching { clip?.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true }.getOrDefault(false)

    /** Adds the marks to [clip]'s extras, keeping whatever extras it already had. */
    internal fun stamp(clip: ClipData, sensitive: Boolean, selfWrite: Boolean) {
        val description = clip.description ?: return
        val extras = description.extras ?: PersistableBundle()
        extras.putBoolean(OWN_WRITE_KEY, true)
        if (sensitive) extras.putBoolean(EXTRA_IS_SENSITIVE, true)
        if (selfWrite) {
            val token = UUID.randomUUID().toString()
            SelfWriteLedger.record(token)
            extras.putString(SELF_WRITE_TOKEN_KEY, token)
        }
        description.extras = extras
    }
}
