package com.wasimaster.wmkeyboard.core.tools

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.netlog.NoInternetPermissionException
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.tools.feature.R
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A line about a photo request, carried as a resource id and put into words
 * where it is drawn — the same shape `AddonText` uses, and for the same reason:
 * the picker screens live in :app, a request outlives the screen that asked for
 * it, and text resolved early would still be in the old language after the user
 * changes it.
 *
 * The IME's own panels take the opposite convention (a resolved `String`),
 * which is why the rotation side turns these into words itself.
 */
sealed interface PhotoText {

    /** A line this build wrote. [arg1] fills `%1$s`. */
    data class Resource(@get:StringRes val textRes: Int, val arg1: Any? = null) : PhotoText

    /** The provider's own words, already in the provider's language. */
    data class Literal(val text: String) : PhotoText
}

/** Puts a [PhotoText] into words. Call this where the text is drawn. */
fun PhotoText.resolve(context: Context): String = when (this) {
    is PhotoText.Literal -> text
    is PhotoText.Resource -> if (arg1 == null) context.getString(textRes) else context.getString(textRes, arg1)
}

/**
 * Why a photo request failed, in the terms the picker has to act on. The split
 * that earns its keep is [QuotaSpent] against [KeyRejected]: they arrive as the
 * same HTTP status from Unsplash, and they need opposite advice.
 */
sealed interface PhotoFailure {

    /** No provider has a usable key. Nothing was requested. */
    data object NoKey : PhotoFailure

    /** The key was refused. Replacing it is the fix. */
    data class KeyRejected(val source: PhotoSource) : PhotoFailure

    /**
     * The budget for this provider is used up. Replacing the key is **not** the
     * fix; waiting is, or adding a key of one's own.
     */
    data class QuotaSpent(val source: PhotoSource, val resetAtMs: Long) : PhotoFailure

    /** Offline, timed out, or the provider is unreachable. */
    data class Offline(val text: PhotoText) : PhotoFailure

    /** Anything else, with the provider's own words when it sent any. */
    data class Other(val text: PhotoText) : PhotoFailure
}

/** The failure a thrown exception amounts to, for [source]. */
fun photoFailureOf(source: PhotoSource, error: Throwable, nowMs: Long): PhotoFailure = when (error) {
    is ToolHttpException -> when {
        // Checked before the status arm below, because for Unsplash this *is*
        // a 403 and the two need opposite advice.
        PhotoRateLimit.isQuotaFailure(source, error) ->
            PhotoFailure.QuotaSpent(source, PhotoRateLimit.resetAt(source, nowMs))
        error.status == HTTP_UNAUTHORIZED || error.status == HTTP_FORBIDDEN ->
            PhotoFailure.KeyRejected(source)
        else -> PhotoFailure.Other(error.words())
    }
    is NoInternetPermissionException ->
        PhotoFailure.Offline(PhotoText.Resource(OfflineBuild.refused()))
    is UnknownHostException, is ConnectException ->
        PhotoFailure.Offline(PhotoText.Resource(R.string.ftools_photo_error_offline))
    is SocketTimeoutException ->
        PhotoFailure.Offline(PhotoText.Resource(R.string.ftools_photo_error_timeout))
    else -> PhotoFailure.Other(
        error.message?.takeIf { it.isNotBlank() }?.let { PhotoText.Literal(it) }
            ?: PhotoText.Resource(R.string.ftools_photo_error_failed),
    )
}

/** The provider's own words when it sent any, else ours. */
private fun ToolHttpException.words(): PhotoText =
    apiMessage?.takeIf { it.isNotBlank() }?.let { PhotoText.Literal(it) }
        ?: PhotoText.Resource(R.string.ftools_photo_error_failed)

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
