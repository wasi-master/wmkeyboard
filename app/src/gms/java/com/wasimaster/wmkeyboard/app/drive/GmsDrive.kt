package com.wasimaster.wmkeyboard.app.drive

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.common.api.ApiException
import com.wasimaster.wmkeyboard.core.settings.sink.BackupLog
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.DriveSink
import com.wasimaster.wmkeyboard.core.settings.sink.DriveTokenProvider
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/**
 * The Drive side of a build that has Google Play services compiled in.
 *
 * Same package and same signatures as `src/nogms/java`, exactly one of which is
 * on the source path. This file is the *entire* proprietary surface of the
 * Drive backup destination: everything else about it, including all four REST
 * calls, is ordinary HTTP in `:core:settings` and builds on F-Droid too.
 *
 * `AuthorizationClient` rather than the deprecated `GoogleSignIn`. It also fits
 * better: this app wants one narrow scope and does not want to know who the
 * user is. The only scopes ever requested are [DriveSink.SCOPE], the app's
 * own hidden folder, and [DriveSink.SCOPE_FILE], the files the app made
 * itself. Neither grants sight of anything else in the account. Each location
 * asks for the one its space needs, and only that one.
 */

private fun request(scope: String): AuthorizationRequest = AuthorizationRequest.builder()
    .setRequestedScopes(listOf(Scope(scope)))
    .build()

/** Suspends on a [Task] without pulling in kotlinx-coroutines-play-services. */
private suspend fun <T> Task<T>.awaitResult(): Result<T> = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(Result.success(it)) }
    addOnFailureListener { cont.resume(Result.failure(it)) }
    addOnCanceledListener { cont.resume(Result.failure(CancellationException("Task cancelled"))) }
}

/** ApiException carries the status that says why (10 = DEVELOPER_ERROR: no OAuth client for this package + SHA-1). */
private fun describe(failure: Throwable): String =
    (failure as? ApiException)?.let { "ApiException status=${it.statusCode} ${it.message}" } ?: failure.toString()

/**
 * The token the background job uses.
 *
 * Silent by contract. `authorize` on an already-granted scope answers straight
 * away with a token; when it answers with a resolution instead, that means
 * Google wants to show the user something, and a job running while nobody is
 * looking cannot. Answering null there is what turns into "authorize this
 * again" on the settings screen rather than a dialog nobody sees.
 */
private class GmsDriveTokenProvider(context: Context) : DriveTokenProvider {

    private val appContext = context.applicationContext

    override suspend fun accessToken(scope: String): String? {
        // A failed call is not a refusal. Refusal comes back as a *successful*
        // answer carrying a resolution; a failure is Play services unable to ask
        // at all, most often because the phone is offline, and saying "authorize
        // again" for that sends the user to fix something that is not broken.
        val result = Identity.getAuthorizationClient(appContext)
            .authorize(request(scope))
            .awaitResult()
            .getOrElse {
                BackupLog.w("drive authorize failed: ${describe(it)}", it)
                throw BackupSinkException(SinkError.IO, it)
            }
        BackupLog.d("drive token: resolution=${result.hasResolution()} token=${result.accessToken != null} scopes=${result.grantedScopes}")
        return if (result.hasResolution()) null else result.accessToken
    }
}

private object GmsDriveAuthorizer : DriveAuthorizer {

    // Sign-in is Play services', so Google's libraries switched off leave
    // the Drive destination out, as F-Droid's build does.
    override val available: Boolean get() = GoogleLibraries.usable

    override suspend fun authorized(context: Context, scope: String): Boolean {
        val result = Identity.getAuthorizationClient(context.applicationContext)
            .authorize(request(scope))
            .awaitResult()
            .onFailure { BackupLog.w("drive authorized? failed: ${describe(it)}", it) }
            .getOrNull()
            ?: return false
        BackupLog.d("drive authorized? resolution=${result.hasResolution()} token=${result.accessToken != null}")
        return !result.hasResolution() && result.accessToken != null
    }

    override suspend fun authorize(
        activity: Activity,
        scope: String,
        onConsent: (IntentSender) -> Unit,
    ): Boolean {
        val result: AuthorizationResult = Identity.getAuthorizationClient(activity)
            .authorize(request(scope))
            .awaitResult()
            .onFailure { BackupLog.w("drive authorize (ui) failed: ${describe(it)}", it) }
            .getOrNull()
            ?: return false
        BackupLog.d("drive authorize (ui) resolution=${result.hasResolution()} token=${result.accessToken != null}")
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) {
            onConsent(pending.intentSender)
            // Not granted yet. The caller launches the consent screen and asks
            // again when it comes back, which is the only way to know.
            return false
        }
        return result.accessToken != null
    }
}

fun driveAuthorizer(): DriveAuthorizer = GmsDriveAuthorizer

fun driveTokenProvider(context: Context): DriveTokenProvider? =
    if (GoogleLibraries.usable) GmsDriveTokenProvider(context) else null
