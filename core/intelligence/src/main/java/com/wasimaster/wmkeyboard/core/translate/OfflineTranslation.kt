package com.wasimaster.wmkeyboard.core.translate

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The on-device engines together, for the callers that do not care which one
 * answers: the panel's model offer, the settings screens, the AI's translate
 * tool. Three engines can sit on one phone:
 *
 *  - [Engine.ML_KIT], Google's, in builds that carry ML Kit;
 *  - [Engine.BERGAMOT], Mozilla's, free software, in every build that can
 *    fetch its models (Android 9 and later);
 *  - [Engine.NLLB], Meta's NLLB-200 on CTranslate2: one large model for 200
 *    languages, for the pairs neither of the others has (64-bit phones with
 *    the memory for it).
 *
 * Which one translates is the translate list's business (each is a row the
 * user orders, switches off or picks); this object only routes a model key or
 * an engine to the right one. Model keys do not collide: ML Kit's are
 * language codes (`fr`), Bergamot's carry [BergamotTranslator.KEY_PREFIX],
 * NLLB's [NllbTranslator.KEY_PREFIX].
 */
object OfflineTranslation {

    /** An on-device engine, by the id its translate-list row has. */
    enum class Engine(val id: String) {
        ML_KIT("mlkit"),
        BERGAMOT("bergamot"),
        NLLB("nllb"),
    }

    /** The engine whose translate-list row has [typeId], or null for an online row. */
    fun engineOf(typeId: String?): Engine? = Engine.entries.firstOrNull { it.id == typeId }

    /** Whether this build and phone have any on-device engine at all. */
    val available: Boolean
        get() = OnDeviceTranslator.offered || BergamotTranslator.available || NllbTranslator.available

    /** The engines this build and phone have, in the order the default list puts them. */
    val engines: List<Engine>
        get() = listOfNotNull(
            Engine.ML_KIT.takeIf { OnDeviceTranslator.offered },
            Engine.BERGAMOT.takeIf { BergamotTranslator.available },
            Engine.NLLB.takeIf { NllbTranslator.available },
        )

    /** Every engine's model states in one map. See the keys above. */
    val models: Flow<Map<String, OfflineModelState>> =
        combine(OnDeviceTranslator.models, BergamotTranslator.models, NllbTranslator.models) { mlKit, bergamot, nllb ->
            mlKit + bergamot + nllb
        }

    /** Every engine's map as it stands now. */
    val modelsNow: Map<String, OfflineModelState>
        get() = OnDeviceTranslator.models.value + BergamotTranslator.models.value + NllbTranslator.models.value

    suspend fun translate(
        engine: Engine,
        context: Context,
        text: String,
        target: String,
        sourceOverride: String = "",
        hints: List<String> = emptyList(),
    ): OfflineTranslateResult {
        // ML Kit's identifier, where the phone has it, beats a guess from the
        // script for the two engines that have none of their own.
        val identified: suspend () -> List<OfflineTranslatePlan.Candidate> = {
            if (sourceOverride.isBlank() && OnDeviceTranslator.AVAILABLE) {
                OnDeviceTranslator.identify(context, text)
            } else {
                emptyList()
            }
        }
        return when (engine) {
            Engine.ML_KIT -> OnDeviceTranslator.translate(context, text, target, sourceOverride, hints)
            Engine.BERGAMOT -> BergamotTranslator.translate(context, text, target, sourceOverride, hints, identified())
            Engine.NLLB -> NllbTranslator.translate(context, text, target, sourceOverride, hints, identified())
        }
    }

    /**
     * The first engine in [engines] order that translates, else the first
     * answer worth showing: a pair that only needs its models is the one
     * failure the user can fix, so it outranks the rest.
     */
    suspend fun translateAny(
        context: Context,
        text: String,
        target: String,
        sourceOverride: String = "",
        hints: List<String> = emptyList(),
        order: List<Engine> = engines,
    ): OfflineTranslateResult {
        var first: OfflineTranslateResult? = null
        var fixable: OfflineTranslateResult? = null
        for (engine in order) {
            val result = translate(engine, context, text, target, sourceOverride, hints)
            if (result is OfflineTranslateResult.Success) return result
            if (first == null) first = result
            if (fixable == null && result is OfflineTranslateResult.NeedsModels) fixable = result
        }
        return fixable ?: first
            ?: OfflineTranslateResult.Failed(UnsupportedOperationException("No on-device translator on this phone"))
    }

    /** Re-reads every engine's models. */
    suspend fun refresh(context: Context) {
        if (OnDeviceTranslator.AVAILABLE) OnDeviceTranslator.refresh(context)
        if (BergamotTranslator.available) BergamotTranslator.refresh(context)
        if (NllbTranslator.available) NllbTranslator.refresh(context)
    }

    /** Starts [key]'s download with its notification. [title] is the caller's words. */
    fun downloadNotified(context: Context, key: String, title: String) {
        when {
            BergamotTranslator.isBergamotKey(key) -> BergamotTranslator.downloadNotified(context, key, title)
            NllbTranslator.isNllbKey(key) -> NllbTranslator.downloadNotified(context, key, title)
            else -> OnDeviceTranslator.downloadNotified(context, key, title)
        }
    }

    fun cancelDownload(context: Context, key: String) {
        when {
            BergamotTranslator.isBergamotKey(key) -> BergamotTranslator.cancelDownload(context, key)
            NllbTranslator.isNllbKey(key) -> NllbTranslator.cancelDownload(key)
            else -> OnDeviceTranslator.cancelDownload(context, key)
        }
    }

    /**
     * Whether fetching any of [keys] needs a licence the user has not agreed
     * to yet: NLLB's weights, CC BY-NC 4.0 ([NllbTranslator.licenceAccepted]).
     * The caller shows it before starting the download.
     */
    fun needsLicence(keys: Collection<String>): Boolean =
        keys.any { NllbTranslator.isNllbKey(it) } && !NllbTranslator.licenceAccepted

    /** Lets every engine's loaded models go. */
    fun release() {
        OnDeviceTranslator.release()
        BergamotTranslator.release()
        NllbTranslator.release()
    }

    /**
     * What [key] is called in an offer or a notification: a language for
     * ML Kit, "French → English" for a Bergamot direction, [engineName] for
     * Bergamot's library, [NLLB_NAME] for the NLLB model and
     * [NLLB_ENGINE_NAME] for its library. [languageName] names a code.
     */
    fun label(key: String, languageName: (String) -> String, engineName: String): String {
        if (key == BergamotTranslator.ENGINE_KEY) return engineName
        if (key == NllbTranslator.MODEL_KEY) return NLLB_NAME
        if (key == NllbTranslator.ENGINE_KEY) return NLLB_ENGINE_NAME
        val direction = BergamotTranslator.directionOf(key) ?: return languageName(key)
        return "${languageName(direction.from)} → ${languageName(direction.to)}"
    }

    /** What fetching [key] costs, or ML Kit's round figure for one of its models. */
    fun downloadBytes(key: String): Long = when {
        BergamotTranslator.isBergamotKey(key) -> BergamotTranslator.downloadBytes(key)
        NllbTranslator.isNllbKey(key) -> NllbTranslator.downloadBytes(key)
        else -> OfflineTranslateLanguages.APPROX_MODEL_BYTES
    }

    /** The model's own name, the same in every language, so not a string resource. */
    const val NLLB_NAME = "NLLB-200"

    /** The library NLLB runs on, likewise a name. */
    const val NLLB_ENGINE_NAME = "CTranslate2"

    /**
     * Whether some engine in [engines] reads or writes the picker code [code]
     * on the device, for the panel's language menus.
     */
    fun supports(code: String, engines: Collection<Engine>): Boolean = engines.any { engine ->
        when (engine) {
            Engine.ML_KIT -> OfflineTranslateLanguages.modelCode(code) != null
            Engine.BERGAMOT -> BergamotLanguages.modelCode(code)?.let { BergamotLanguages.reads(it) || BergamotLanguages.writes(it) } == true
            Engine.NLLB -> NllbLanguages.modelCode(code) != null
        }
    }

    /**
     * Whether [code] needs no download on some engine in [engines]: English,
     * an ML Kit model on the phone, a Bergamot direction into or out of it,
     * or the NLLB model.
     */
    fun ready(code: String, engines: Collection<Engine>, models: Map<String, OfflineModelState>): Boolean =
        engines.any { engine -> state(code, engine, models) is OfflineModelState.Downloaded }

    /** Whether [code]'s model is on its way on some engine in [engines]. */
    fun coming(code: String, engines: Collection<Engine>, models: Map<String, OfflineModelState>): Boolean =
        engines.any { engine -> state(code, engine, models) is OfflineModelState.Downloading }

    private fun state(code: String, engine: Engine, models: Map<String, OfflineModelState>): OfflineModelState? =
        when (engine) {
            Engine.ML_KIT -> OfflineTranslateLanguages.modelCode(code)?.let { model ->
                if (model == OfflineTranslateLanguages.PIVOT) OfflineModelState.Downloaded else models[model]
            }
            Engine.BERGAMOT -> BergamotLanguages.modelCode(code)?.let { model ->
                if (model == BergamotLanguages.PIVOT) return@let OfflineModelState.Downloaded
                val states = listOfNotNull(
                    BergamotLanguages.direction(model, BergamotLanguages.PIVOT),
                    BergamotLanguages.direction(BergamotLanguages.PIVOT, model),
                ).map { models[BergamotTranslator.keyOf(it)] }
                states.firstOrNull { it is OfflineModelState.Downloaded }
                    ?: states.firstOrNull { it is OfflineModelState.Downloading }
            }
            // One model for every language: ready for one is ready for all.
            Engine.NLLB -> NllbLanguages.modelCode(code)?.let { models[NllbTranslator.MODEL_KEY] }
        }
}
