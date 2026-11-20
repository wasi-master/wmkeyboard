package com.wasimaster.wmkeyboard.core.modules

import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.common.R
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild

/**
 * Why a download of a feature library failed, when the connection was not
 * the reason. Set by [DownloadedModuleGate]; [NotInBuild] carries
 * [NOT_IN_BUILD].
 */
enum class LibraryFault {
    /** The server has no such file (HTTP 404 or 410). */
    NOT_ON_SERVER,

    /** The server answered with an error of its own (another 4xx or a 5xx). */
    SERVER_REFUSED,

    /** Bytes arrived but were not the pinned file: wrong size or checksum. */
    WRONG_FILE,

    /** The phone ran out of space while saving it. */
    NO_SPACE,

    /** Data saving held the download back. */
    DATA_SAVING,

    /** This build does not carry the library and cannot fetch it. */
    NOT_IN_BUILD,
}

/**
 * What a feature says when its library did not arrive.
 *
 * Every feature used to answer a failed library with its own "did not
 * download, check the connection" sentence, whatever happened. A Play
 * download waiting for the user's OK, a Play install Play will not serve
 * modules to, a full phone, or a file the server did not have all read the
 * same, and retrying never helped. [message] keeps the feature's sentence
 * for a failure the connection explains and says the real reason otherwise.
 */
object ModuleFailure {

    /** A string resource and the one argument it takes, "" when it takes none. */
    data class Message(@StringRes val res: Int, val arg: String = "")

    // SplitInstallErrorCode values, copied so this module needs no Play library.
    private const val PLAY_API_NOT_AVAILABLE = -5
    private const val PLAY_MODULE_UNAVAILABLE = -2
    private const val PLAY_NETWORK_ERROR = -6
    private const val PLAY_ACCESS_DENIED = -7
    private const val PLAY_INSUFFICIENT_STORAGE = -10
    private const val PLAY_STORE_NOT_FOUND = -14

    /** Play does not count this install as owned: sideloaded, or a sandboxed Play such as GrapheneOS's (#1455). */
    private const val PLAY_APP_NOT_OWNED = -15

    /**
     * The message for [state], the gate's state after its fetch failed.
     * [online] is the feature's own "did not download, check the connection"
     * sentence, used when the connection is the likely reason.
     */
    fun message(state: ModuleState?, @StringRes online: Int): Message {
        if (!InternetPermission.granted) return Message(OfflineBuild.library(online))
        val missing = state as? ModuleState.Missing ?: return Message(online)
        if (missing.consent == NativeConsent.DOWNLOADS) return Message(R.string.common_module_failed_opt_in)
        if (missing.consent != null && missing.consent != NativeConsent.IMPORT) {
            return Message(R.string.common_module_failed_licence)
        }
        if (missing.confirm) return Message(R.string.common_module_failed_play_confirm)
        missing.error?.let { code ->
            return when (code) {
                PLAY_NETWORK_ERROR -> Message(online)
                PLAY_INSUFFICIENT_STORAGE -> Message(R.string.common_module_failed_no_space)
                PLAY_MODULE_UNAVAILABLE, PLAY_ACCESS_DENIED, PLAY_API_NOT_AVAILABLE, PLAY_STORE_NOT_FOUND,
                PLAY_APP_NOT_OWNED,
                ->
                    Message(R.string.common_module_failed_play_unavailable, code.toString())
                else -> Message(R.string.common_module_failed_play, code.toString())
            }
        }
        val code = missing.faultCode?.toString().orEmpty()
        return when (missing.fault) {
            null -> Message(online)
            LibraryFault.NOT_ON_SERVER -> Message(R.string.common_module_failed_not_on_server, code)
            LibraryFault.SERVER_REFUSED -> Message(R.string.common_module_failed_server, code)
            LibraryFault.WRONG_FILE -> Message(R.string.common_module_failed_wrong_file)
            LibraryFault.NO_SPACE -> Message(R.string.common_module_failed_no_space)
            LibraryFault.DATA_SAVING -> Message(R.string.common_module_failed_data_saving)
            LibraryFault.NOT_IN_BUILD -> Message(R.string.common_module_failed_not_in_build)
        }
    }

    private val HTTP_STATUS = Regex("""HTTP (\d{3})""")

    /** The fault [t] stands for, and its HTTP status if any; null for a connection failure. */
    fun faultOf(t: Throwable): Pair<LibraryFault, Int?>? {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is ErrnoException && cause.errno == OsConstants.ENOSPC) return LibraryFault.NO_SPACE to null
            cause = cause.cause.takeIf { it !== cause }
        }
        when (t) {
            is DownloadException.Http -> return httpFault(t.status)
            is DownloadException.Mismatch, is DownloadException.WrongSize -> return LibraryFault.WRONG_FILE to null
            is DownloadException.Blocked -> return LibraryFault.DATA_SAVING to null
            else -> Unit
        }
        val text = t.message.orEmpty()
        if ("No space left" in text) return LibraryFault.NO_SPACE to null
        // NativeDownloader's own sentences for a Maven entry or an install that did not match.
        if ("pinned" in text || "checksum" in text || " is not in " in text) return LibraryFault.WRONG_FILE to null
        HTTP_STATUS.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return httpFault(it) }
        return null
    }

    private fun httpFault(status: Int): Pair<LibraryFault, Int>? = when (status) {
        404, 410 -> LibraryFault.NOT_ON_SERVER to status
        // A proxy or captive portal asking to sign in: the connection's to fix.
        407, 408 -> null
        in 400..599 -> LibraryFault.SERVER_REFUSED to status
        else -> null
    }
}
