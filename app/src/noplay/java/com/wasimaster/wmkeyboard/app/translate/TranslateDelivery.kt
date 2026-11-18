package com.wasimaster.wmkeyboard.app.translate

import android.app.Application
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.translate.TranslateModule
import com.wasimaster.wmkeyboard.core.translate.TranslateModuleGate
import com.wasimaster.wmkeyboard.core.translate.TranslateModuleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The [installTranslateDelivery] of every channel that is not Play. These
 * builds compile the ML Kit bridge into the base APK; with native delivery
 * only its two libraries (`libtranslate_jni.so`, `liblanguage_id_l2c_jni.so`)
 * are fetched on demand, behind the `translate` gate [NativeDelivery]
 * installed, whose state this mirrors in the translate panel's own terms.
 * Without native delivery the default always-installed gate is the truth.
 */
fun installTranslateDelivery(app: Application) {
    val gate = NativeDelivery.gate("translate") ?: return
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    TranslateModule.gate = object : TranslateModuleGate {
        override val state: StateFlow<TranslateModuleState> = gate.state
            .map(::translateState)
            .stateIn(scope, SharingStarted.Eagerly, translateState(gate.state.value))

        override fun requestInstall() = gate.requestInstall()
    }
}

private fun translateState(state: ModuleState): TranslateModuleState = when (state) {
    ModuleState.Installed -> TranslateModuleState.Installed
    is ModuleState.Installing -> TranslateModuleState.Installing(state.bytes, state.totalBytes)
    is ModuleState.Missing -> TranslateModuleState.Missing(state.failed, cause = state)
}
