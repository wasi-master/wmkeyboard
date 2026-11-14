package com.wasimaster.wmkeyboard.core.voice.asr4all

import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import com.wasimaster.wmkeyboard.core.voice.executorch.EtModel
import com.wasimaster.wmkeyboard.core.voice.executorch.EtNative
import com.wasimaster.wmkeyboard.core.voice.executorch.EtStore
import java.io.File

/**
 * Offline asr4all dictation: a streaming English recogniser that transcribes
 * while the user speaks, with punctuation and capitals from its own second
 * pass. Run by ExecuTorch through [EtNative], whose one thread every call here
 * must be on ([EtNative.dispatcher]). The program stays open between
 * dictations, since loading one takes about as long as a short phrase does to
 * say, until another engine's model takes its place.
 */
object Asr4AllEngine {

    const val PROGRAM = "asr_encoder.pte"
    const val METADATA = "metadata.json"

    private var metadata: Asr4AllMetadata? = null
    private var metadataPath: String? = null

    /** Opens [model] if it is not the one already open. On [EtNative.dispatcher] only. Throws when it will not load. */
    fun warm(filesDir: File, model: EtModel) {
        newStream(filesDir, model)
    }

    /**
     * A fresh utterance on [model], opening it first if need be, at [tier]
     * (#1322) when the model has it and at its default otherwise. On
     * [EtNative.dispatcher] only.
     */
    fun newStream(filesDir: File, model: EtModel, tier: String = ""): Asr4AllStream {
        require(model.engine == EtEngine.ASR4ALL)
        val program = EtNative.programs(listOf(EtStore.file(filesDir, model, PROGRAM))).single()
        val metaFile = EtStore.file(filesDir, model, METADATA)
        val meta = metadata?.takeIf { metadataPath == metaFile.path }
            ?: Asr4AllMetadata.parse(metaFile.readText()).also {
                metadata = it
                metadataPath = metaFile.path
            }
        val picked = tier.takeIf { name -> name.isNotEmpty() && meta.tiers.any { it.name == name } } ?: meta.defaultTier
        return Asr4AllStream(program, meta, tierName = picked)
    }

    /**
     * The tiers the asr4all bundles export (#1322), lowest latency first:
     * commit frames and lookahead frames at 25 a second. See the models'
     * README; [newStream] falls back to a model's default for one it lacks.
     */
    val TIERS = listOf("stream_c16r4", "stream_c32r8", "stream_c64r16", "stream_c128r24", "stream_c256r32", "stream_c512r8")
}
