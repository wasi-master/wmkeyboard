package com.wasimaster.wmkeyboard.app.llm

import android.app.Application
import android.content.Context
import com.wasimaster.wmkeyboard.core.localllm.LlmModule
import com.wasimaster.wmkeyboard.core.localllm.LlmModuleGate
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery

/**
 * The [installLlmDelivery] of every channel that is not Play. These builds
 * compile the LiteRT-LM bridge into the base APK, so there is no module to
 * install; with native delivery only its library, `liblitertlm_jni.so`, is
 * fetched on demand, behind the `llm` gate [NativeDelivery] installed (run
 * after it, from WMApplication). Without native delivery the default
 * always-installed gate in LlmModule is the truth and this changes nothing.
 */
fun installLlmDelivery(app: Application) {
    val gate = NativeDelivery.gate("llm") ?: return
    LlmModule.gate = object : LlmModuleGate {
        override fun installed() = gate.installed

        override fun requestInstall() = gate.requestInstall()

        override fun missingConsent(): String? = (gate.state.value as? ModuleState.Missing)?.consent
    }
}

@Suppress("UNUSED_PARAMETER")
fun llmSplitCompat(context: Context) = Unit
