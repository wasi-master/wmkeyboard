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
 * The permission cannot change while the process lives, so a resource picked
 * here when a failure is recorded stays right for as long as it is shown.
 */
object OfflineBuild {

    /** Whether this is the offline build. */
    val active: Boolean get() = !InternetPermission.granted

    /** [online] for a service the app talks to (search, AI, a server, the updater). */
    @StringRes
    fun network(@StringRes online: Int): Int = if (active) R.string.common_error_no_internet_permission else online

    /** [online] for a file the app downloads: a model, a word list, a data pack. */
    @StringRes
    fun download(@StringRes online: Int): Int = if (active) R.string.common_offline_build_download else online

    /** [online] for a feature library, the native code a feature runs on. */
    @StringRes
    fun library(@StringRes online: Int): Int = if (active) R.string.common_offline_build_library else online
}
