package com.wasimaster.wmkeyboard.ime.wmtranslate

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.wasimaster.wmkeyboard.ime.suite.SuiteTrust

/**
 * WM Translate's way into the keyboard: whether the translate setup is shared, and the setup
 * itself while it is. Only the real WM Translate is answered, by package name and signing
 * certificate; anyone else gets a SecurityException. Keys leave only after the person chose
 * to share in the Translate tool's settings.
 */
class TranslateBridgeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return reply(TranslateWire.BOOT)
        if (!SuiteTrust.callerIs(context, TranslateWire.APP_PACKAGE)) throw SecurityException("Only WM Translate may ask.")
        val mode = TranslateShare.mode(context)
        return when (method) {
            TranslateWire.STATUS -> reply(if (mode == TranslateWire.SHARE_NONE) TranslateWire.OFF else TranslateWire.OK).apply {
                putString(TranslateWire.SHARE, mode)
            }
            TranslateWire.CONFIG -> {
                if (mode == TranslateWire.SHARE_NONE) return reply(TranslateWire.OFF).apply { putString(TranslateWire.CODE, TranslateWire.NOT_SHARED) }
                val values = WmTranslateBridge.config() ?: return reply(TranslateWire.BOOT)
                // Copy once: the read that takes it turns sharing off again.
                if (mode == TranslateWire.SHARE_ONCE) TranslateShare.set(context, TranslateWire.SHARE_NONE)
                reply(TranslateWire.OK).apply { putString(TranslateWire.VALUES, values) }
            }
            else -> reply(TranslateWire.OK).apply { putString(TranslateWire.CODE, TranslateWire.UNKNOWN_CALL); putString(TranslateWire.ERROR, "Unknown call") }
        }
    }

    private fun reply(state: String) = Bundle().apply { putString(TranslateWire.STATE, state); putInt(TranslateWire.V, TranslateWire.VERSION) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
