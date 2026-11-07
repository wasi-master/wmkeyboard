package com.wasimaster.wmkeyboard.app.storage

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R

/**
 * What a downloaded feature library is for, and the settings of the feature
 * that fetched it: Storage's Feature libraries list would otherwise name a file
 * and a gate id, and leave the user to guess whether it can go.
 *
 * Every gate in `native/gates.json` that delivers a library needs an entry here
 * ([FeatureLibraryInfoTest] holds the two together).
 */
internal class FeatureLibraryInfo(@StringRes val description: Int, val route: String) {

    companion object {
        private const val VOICE = "voice"
        private const val AI = "ai"
        private const val LANGUAGES = "languages"
        private const val TRANSLATE = "tool/TRANSLATE"
        private const val SCANNER = "tool/OCR"
        private const val GRAMMAR = "tool/GRAMMAR"

        /** Gates by id. Each library in a gate does the gate's one job, except [byLibrary]'s. */
        val byGate: Map<String, FeatureLibraryInfo> = mapOf(
            "llm" to FeatureLibraryInfo(R.string.storage_lib_llm, AI),
            "translate" to FeatureLibraryInfo(R.string.storage_lib_translate, TRANSLATE),
            "litert" to FeatureLibraryInfo(R.string.storage_lib_litert, VOICE),
            "handwriting" to FeatureLibraryInfo(R.string.storage_lib_handwriting, "tool/HANDWRITING"),
            "speech" to FeatureLibraryInfo(R.string.storage_lib_speech, VOICE),
            "opus" to FeatureLibraryInfo(R.string.storage_lib_opus, VOICE),
            "executorch" to FeatureLibraryInfo(R.string.storage_lib_executorch, VOICE),
            "executorchqnn" to FeatureLibraryInfo(R.string.storage_lib_executorch, VOICE),
            "onnx" to FeatureLibraryInfo(R.string.storage_lib_onnx, VOICE),
            "vosk" to FeatureLibraryInfo(R.string.storage_lib_vosk, VOICE),
            "kiwi" to FeatureLibraryInfo(R.string.storage_lib_kiwi, LANGUAGES),
            "webrtc" to FeatureLibraryInfo(R.string.storage_lib_webrtc, "tool/SEND_FILES"),
            "grammar" to FeatureLibraryInfo(R.string.storage_lib_grammar, GRAMMAR),
            "divvungrammar" to FeatureLibraryInfo(R.string.storage_lib_grammar, GRAMMAR),
            "spell" to FeatureLibraryInfo(R.string.storage_lib_spell, GRAMMAR),
            "ocr" to FeatureLibraryInfo(R.string.storage_lib_ocr, SCANNER),
            "rime" to FeatureLibraryInfo(R.string.storage_lib_rime, LANGUAGES),
            "mozc" to FeatureLibraryInfo(R.string.storage_lib_mozc, LANGUAGES),
            "bergamot" to FeatureLibraryInfo(R.string.storage_lib_bergamot, TRANSLATE),
            "langid" to FeatureLibraryInfo(R.string.storage_lib_langid, TRANSLATE),
            "ct2" to FeatureLibraryInfo(R.string.storage_lib_ct2, TRANSLATE),
            "zinnia" to FeatureLibraryInfo(R.string.storage_lib_zinnia, "tool/HANDWRITING"),
            "llama" to FeatureLibraryInfo(R.string.storage_lib_llama, AI),
            "llamagpu" to FeatureLibraryInfo(R.string.storage_lib_llama, AI),
            "llamavulkan" to FeatureLibraryInfo(R.string.storage_lib_llama, AI),
            "whispervulkan" to FeatureLibraryInfo(R.string.storage_lib_speech, VOICE),
            "docscan" to FeatureLibraryInfo(R.string.storage_lib_docscan, "tool/DOC_SCAN"),
            "zxing" to FeatureLibraryInfo(R.string.storage_lib_zxing, "tool/QR_SCAN"),
            "imagegen" to FeatureLibraryInfo(R.string.storage_lib_imagegen, "sticker_generator"),
            "npu" to FeatureLibraryInfo(R.string.storage_lib_npu, AI),
            "scanner" to FeatureLibraryInfo(R.string.storage_lib_scanner, SCANNER),
        )

        /** Libraries that share a gate's download but serve another feature. */
        val byLibrary: Map<String, FeatureLibraryInfo> = mapOf(
            "libwmxlit.so" to FeatureLibraryInfo(R.string.storage_lib_xlit, LANGUAGES),
            "libwmnikud.so" to FeatureLibraryInfo(R.string.storage_lib_nikud, LANGUAGES),
        )

        /**
         * The entry for one library, or null for a gate nobody described. The
         * NPU's chip-specific libraries are pinned under gates of their own,
         * each named after the NPU's.
         */
        fun of(library: String, gate: String): FeatureLibraryInfo? =
            byLibrary[library] ?: byGate[gate] ?: byGate["npu"].takeIf { gate.startsWith("npu") }
    }
}
