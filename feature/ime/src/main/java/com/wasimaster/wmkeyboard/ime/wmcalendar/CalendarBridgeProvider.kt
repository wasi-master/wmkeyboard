package com.wasimaster.wmkeyboard.ime.wmcalendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.wasimaster.wmkeyboard.ime.suite.SuiteTrust
import kotlinx.coroutines.runBlocking

/**
 * WM Calendar's way into the keyboard: `call("ai_config")`, for its quick add to use the AI the
 * person set up here, and `call("read_image")` with extras `uri` (a content URI WM Calendar has
 * granted read access to), for a screenshot or photo read into quick-add text by the keyboard's
 * text reader on the phone. Only the real WM Calendar is answered, by package name and signing
 * certificate; anyone else gets `state=error, error=untrusted`. Each needs the person's choice in
 * the Calendar tool's settings first; until then the answer is `state=off`. As WM Calendar's own
 * bridges do, it never throws: a failure is a state.
 */
class CalendarBridgeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return error("not_ready")
        if (!SuiteTrust.callerIs(context, WmCalendarLink.APP_PACKAGE)) return error("untrusted")
        if (method == WmCalendarLink.READ_IMAGE) return readImage(context, extras)
        if (method != WmCalendarLink.AI_CONFIG) return error("unknown_call")
        val mode = WmCalendarLink.share(context)
        if (mode == WmCalendarLink.SHARE_NONE) return reply("off")
        val values = WmCalendarLink.aiConfig() ?: return error("not_ready")
        // Once: the read that takes it turns sharing off again.
        if (mode == WmCalendarLink.SHARE_ONCE) WmCalendarLink.setShare(context, WmCalendarLink.SHARE_NONE)
        return reply("ok").apply { putString("values", values) }
    }

    /** WM Calendar calls from a background thread and waits; the read blocks this binder thread. */
    private fun readImage(context: android.content.Context, extras: Bundle?): Bundle {
        if (!WmCalendarLink.readsImages(context)) return reply("off")
        val uri = extras?.getString("uri")?.let(Uri::parse) ?: return error("failed")
        val settings = WmCalendarLink.settings() ?: return error("failed")
        val result = runCatching { runBlocking { ImageText.read(context, uri, settings) } }.getOrDefault(ImageText.Result.Failed)
        return when (result) {
            is ImageText.Result.Text -> reply("ok").apply { putString("text", result.text) }
            ImageText.Result.NoText -> error("no_text")
            ImageText.Result.Failed -> error("failed")
        }
    }

    private fun reply(state: String) = Bundle().apply {
        putString("state", state)
        putInt("version", VERSION)
    }

    private fun error(code: String) = reply("error").apply { putString("error", code) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val VERSION = 1
    }
}
