package com.wasimaster.wmkeyboard.core.netlog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.net.UnknownHostException

/**
 * Whether this build holds `android.permission.INTERNET` (#292).
 *
 * The released APKs do. A build made with `-Pwmkb.noInternet=true` does not,
 * and neither does a fork that strips the permission some other way. Such a
 * build must still type, predict, learn and do everything else that runs on
 * the device; only the features that fetch something stop, the same way they
 * stop when the phone is offline.
 *
 * Without the permission the platform does not fail politely. A socket fails
 * with `EACCES`, which is an ordinary `IOException`, but a host name lookup
 * throws `SecurityException`, which is not, and would crash every caller that
 * is ready for "offline" and nothing else. So [NetLog] refuses a request before
 * it starts, with [NoInternetPermissionException], and the OkHttp clients do
 * the same through `InternetGate`.
 *
 * Two different phones end up here, and they need different advice (#1114).
 * The offline build does not declare the permission, so it can never have it.
 * A standard build declares it, but some systems let the user take it away
 * per app (GrapheneOS calls it the Network permission). [declared] tells the
 * two apart, so only the first is ever called the offline build.
 *
 * Whether the build declares it is fixed, so it is read once, from
 * [NetLog.attach]. Whether a declared permission is granted can change while
 * the process lives (switching it on does not restart the app), so while it
 * is missing every read asks the system again; once granted it stays granted,
 * because Android kills the process when a permission is taken away.
 */
object InternetPermission {

    @Volatile
    private var app: Context? = null

    /** True until [attach] reads otherwise, so JVM tests that never attach are unaffected. */
    @Volatile
    private var grantedNow: Boolean = true

    /**
     * Whether this build's manifest asks for the permission at all. False only
     * on the offline build (or a fork that strips it the same way).
     */
    @Volatile
    var declared: Boolean = true
        private set

    /** Whether this process may open a connection now. */
    val granted: Boolean
        get() = grantedNow || recheck()

    /** The build asks for the permission, and the system has it switched off. */
    val revoked: Boolean
        get() = declared && !granted

    fun attach(context: Context) {
        app = context
        grantedNow = read(context)
        if (!grantedNow) declared = declaredIn(context)
    }

    /** Throws [NoInternetPermissionException] when this build has no internet permission. */
    fun check() {
        if (!granted) throw NoInternetPermissionException()
    }

    private fun recheck(): Boolean {
        if (!declared) return false
        val context = app ?: return false
        return read(context).also { if (it) grantedNow = true }
    }

    private fun read(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED

    /**
     * Read only when the permission is missing, which is the rare case, so
     * every other process start skips the package lookup. A lookup that fails
     * answers "declared": calling a standard build the offline one is the
     * mistake #1114 was about, and the other way round only loses the advice
     * to install the standard build.
     */
    private fun declaredIn(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions.orEmpty().contains(Manifest.permission.INTERNET)
    }.getOrDefault(true)
}

/**
 * A request refused because this build has no internet permission.
 *
 * An [UnknownHostException] on purpose: every caller already treats that one
 * as "offline" and shows its offline state, which is the right answer here too.
 * The error mappers that can say something more exact check for this type first.
 */
class NoInternetPermissionException : UnknownHostException("This app has no internet permission")
