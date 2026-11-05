package com.wasimaster.wmkeyboard.core.netlog

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R

/**
 * What the offline build (no internet permission, see [InternetPermission])
 * says when it turns something down.
 *
 * Every failure a connection could cause has its own sentence, usually "check
 * the connection and try again". On the offline build that advice is wrong:
 * no connection will ever come, and retrying only fails again. Each helper
 * takes the sentence a build with internet access shows and, on the offline
 * build, swaps in one that says this is the offline build and how to get the
 * thing anyway: import the file, or install the standard build over it.
 *
 * A standard build whose permission the system has switched off is not the
 * offline build, and telling its user to install the standard build sends
 * them hunting for an APK they already have (#1114). There every helper
 * answers [R.string.common_error_network_permission_off] instead, which says
 * where to switch it back on.
 */
object OfflineBuild {

    /**
     * Whether this app cannot connect at all: the offline build, or a standard
     * build whose permission the system switched off. The helpers below say
     * which of the two it is.
     */
    val active: Boolean get() = !InternetPermission.granted

    /** [online] for a service the app talks to (search, AI, a server, the updater). */
    @StringRes
    fun network(@StringRes online: Int): Int = refusal(R.string.common_error_no_internet_permission) ?: online

    /** [online] for a file the app downloads: a model, a word list, a data pack. */
    @StringRes
    fun download(@StringRes online: Int): Int = refusal(R.string.common_offline_build_download) ?: online

    /** [online] for a feature library, the native code a feature runs on. */
    @StringRes
    fun library(@StringRes online: Int): Int = refusal(R.string.common_offline_build_library) ?: online

    /**
     * What a request refused for want of the permission says: [offline] on the
     * offline build, the switch-it-back-on sentence where the system took the
     * permission away. For the places that already know the request was
     * refused, such as a [NoInternetPermissionException] arriving.
     */
    @StringRes
    fun refused(@StringRes offline: Int = R.string.common_error_no_internet_permission): Int =
        if (InternetPermission.revoked) R.string.common_error_network_permission_off else offline

    @StringRes
    private fun refusal(@StringRes offline: Int): Int? = when {
        InternetPermission.granted -> null
        InternetPermission.revoked -> R.string.common_error_network_permission_off
        else -> offline
    }
}
