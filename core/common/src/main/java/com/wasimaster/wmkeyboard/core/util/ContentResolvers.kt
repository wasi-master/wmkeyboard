package com.wasimaster.wmkeyboard.core.util

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [ContentResolver.openInputStream] with the null case turned into a typed,
 * described failure.
 *
 * The platform returns null when the provider is gone, the grant has lapsed, or
 * the document was deleted between the picker and the read — all routine on a
 * keyboard that imports themes, dictionaries and icon packs from other apps.
 * Every caller already runs inside a `runCatching`/`try` that shows an import
 * error, so the only thing `!!` bought was a NullPointerException with no URI
 * in it.
 */
fun ContentResolver.requireInputStream(uri: Uri): InputStream =
    openInputStream(uri) ?: throw IOException("Cannot open $uri for reading")

/**
 * [ContentResolver.openOutputStream] with the null case turned into a typed,
 * described failure, and with a mode that truncates.
 *
 * The default `"w"` does **not** imply `O_TRUNC` on every provider. Writing a
 * short document over a longer one then leaves the tail of the old one behind,
 * which for anything structured means a file that is the right size, has a
 * plausible name, and does not parse. `"wt"` is the mode that means what
 * everybody assumes `"w"` means.
 */
fun ContentResolver.requireOutputStream(uri: Uri, mode: String = "wt"): OutputStream =
    openOutputStream(uri, mode) ?: throw IOException("Cannot open $uri for writing")

/**
 * The most an imported document may be. Matches the dictionary importer's
 * own ceiling (`CustomDictionaries.MAX_BYTES`), which is the largest thing this
 * app ever asks a user to pick.
 */
const val MAX_IMPORT_BYTES = 32L * 1024 * 1024

/**
 * The most a backup of this app's own may be. A backup is the one document the
 * app both writes and reads, and its export has no ceiling, so reading one back
 * under [MAX_IMPORT_BYTES] refused files the app had made itself (#1071). Still
 * far below a video picked by mistake.
 */
const val MAX_BACKUP_BYTES = 256L * 1024 * 1024

/**
 * At most [maxBytes] of this stream, or null when it holds more.
 *
 * Counts as it reads rather than trusting a reported length: a content
 * provider is free to give no size at all, `available()` is not one, and for a
 * compressed stream the only honest number is the one that comes out.
 */
fun InputStream.readCapped(maxBytes: Long = MAX_IMPORT_BYTES): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(COPY_BUFFER)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * At most [maxBytes] of [uri], or null when it is larger than that.
 *
 * The importers used to `readBytes()` whatever the picker handed back, with no
 * ceiling at all. Picking the wrong file in the document picker could
 * therefore take the process down instead of showing an import error, and on a
 * keyboard that process is the keyboard.
 */
fun ContentResolver.readBytesCapped(uri: Uri, maxBytes: Long = MAX_IMPORT_BYTES): ByteArray? =
    requireInputStream(uri).use { it.readCapped(maxBytes) }

/**
 * [readBytesCapped] decoded as text.
 *
 * The cap matters twice over here: a Kotlin string is UTF-16, so an ASCII
 * document costs its bytes again on the way to a `String`.
 */
fun ContentResolver.readTextCapped(uri: Uri, maxBytes: Long = MAX_IMPORT_BYTES): String? =
    readBytesCapped(uri, maxBytes)?.decodeToString()

private const val COPY_BUFFER = 64 * 1024
