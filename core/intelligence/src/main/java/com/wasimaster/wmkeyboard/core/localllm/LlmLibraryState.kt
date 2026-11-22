package com.wasimaster.wmkeyboard.core.localllm

import android.content.Context
import android.text.format.Formatter
import com.wasimaster.wmkeyboard.core.modules.ModuleFailure
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.intelligence.R
import java.io.IOException

/**
 * What a chat or an AI action says while the library behind the model
 * (llama.cpp's, or LiteRT-LM's) is not on the phone: the user-presentable
 * [IOException] both runtimes throw, after the caller has asked for the
 * library once more.
 *
 * On a build that downloads its own libraries (GitHub) a failed download used
 * to read "downloading, try again in a moment" on every try, because the
 * retry turned the failure back into a download before anything looked at it
 * (#1518). [before] is the state read before that retry, so a failure says
 * so, with the reason [ModuleFailure] finds, and where to follow the new try.
 */
internal object LlmLibraryState {

    /**
     * [before] and [after] are the gate's state either side of the retry,
     * [waiting] whether Play holds the download for the user's OK, and
     * [playError] Play's code for its last failed fetch.
     */
    fun notHere(context: Context, before: ModuleState?, after: ModuleState?, waiting: Boolean, playError: Int?): IOException {
        val text = when {
            waiting -> context.getString(R.string.core_intel_llm_module_confirm)
            playError != null -> context.getString(R.string.core_intel_llm_module_failed, playError)
            NativeDelivery.active && before is ModuleState.Missing && before.failed -> {
                val reason = ModuleFailure.message(before, R.string.core_intel_llm_library_failed_online)
                val said = if (reason.arg.isEmpty()) context.getString(reason.res) else context.getString(reason.res, reason.arg)
                said + " " + context.getString(R.string.core_intel_llm_library_failed_where)
            }
            after is ModuleState.Installing && after.totalBytes > 0L -> context.getString(
                R.string.core_intel_llm_module_downloading_sized,
                Formatter.formatShortFileSize(context, after.bytes),
                Formatter.formatShortFileSize(context, after.totalBytes),
            )
            else -> context.getString(R.string.core_intel_llm_module_downloading)
        }
        return IOException(text)
    }
}
