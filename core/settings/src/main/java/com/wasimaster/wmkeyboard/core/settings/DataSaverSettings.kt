package com.wasimaster.wmkeyboard.core.settings

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.settings.R

/**
 * What arms data saving by itself, on top of the manual switch.
 *
 * The default is [METERED] rather than the system's own Data Saver, unlike
 * power saving's [PowerSavingTrigger.SYSTEM_SAVER]: a metered connection is a
 * fact about the network the user is on, not a preference they have to have
 * expressed somewhere else first, and the whole point of the feature is to
 * stop the keyboard spending a mobile allowance nobody offered it.
 */
enum class DataSaverTrigger(@StringRes val labelRes: Int) {
    /** Nothing but the manual switch. */
    OFF(R.string.core_settings_data_saver_trigger_off_label),

    /** The active network is metered — mobile data, or a metered hotspot. */
    METERED(R.string.core_settings_data_saver_trigger_metered_label),

    /** Metered *and* roaming: the strictest of the automatic triggers. */
    ROAMING(R.string.core_settings_data_saver_trigger_roaming_label),

    /** Android's own Data Saver is on. */
    SYSTEM_SAVER(R.string.core_settings_data_saver_trigger_system_label),

    /** Any of the three above. */
    EITHER(R.string.core_settings_data_saver_trigger_either_label),
}

/**
 * What one feature may do while data saving is in force.
 *
 * Three states rather than a switch because the honest answer differs by
 * feature and by person: a GIF grid is worth its megabytes to someone on an
 * unlimited plan, worth asking about on a countable one, and never worth it
 * while roaming. [ASK] is what makes the middle case expressible — the feature
 * still works, it just stops happening without the user saying so.
 */
enum class MeteredPolicy(@StringRes val labelRes: Int) {
    /** Data saving does not touch this feature. */
    ALLOW(R.string.core_settings_metered_policy_allow_label),

    /**
     * Hold the fetch and offer it: the panel says why it is empty and carries a
     * one-tap "use mobile data", which grants the feature for the rest of the
     * session (see `DataSaverStatus.grants`).
     */
    ASK(R.string.core_settings_metered_policy_ask_label),

    /** The feature is off for as long as data saving is in force. */
    BLOCK(R.string.core_settings_metered_policy_block_label),
}

/**
 * Whether this policy stops a fetch that nobody is watching.
 *
 * Background work has no one to ask — there is no panel on screen and a
 * notification for a link preview would be worse than the preview — so [ASK]
 * collapses to [BLOCK] there. The settings screen offers those rows only two
 * choices; this makes an [ASK] that arrives from an imported backup behave.
 */
val MeteredPolicy.stopsBackgroundWork: Boolean
    get() = this != MeteredPolicy.ALLOW

/** The choices a row is offered: everything, or the two that mean something. */
val backgroundPolicies: List<MeteredPolicy> =
    listOf(MeteredPolicy.ALLOW, MeteredPolicy.BLOCK)

/**
 * What the device's network situation is right now, as the keyboard sees it.
 *
 * A plain value for the same reason as [DevicePowerState]: the decision
 * ([DataSaverSettings.appliesTo]) stays a pure function of settings and state,
 * testable without a `Context` or a `ConnectivityManager`.
 * `core.net.NetworkWatcher` is what fills it in on device.
 *
 * [online] defaults true and [metered] defaults false so that a state built
 * before the first callback — unit tests, the first frame — never restricts
 * anything: an unknown network must not read as an expensive one.
 */
data class DeviceNetworkState(
    val online: Boolean = true,
    val metered: Boolean = false,
    val roaming: Boolean = false,
    val systemDataSaver: Boolean = false,
)

/**
 * Data saving: stop the keyboard spending a mobile allowance on things the
 * user did not ask for, and ask before it spends one on things they did.
 *
 * Grouped into its own class rather than sitting flat on [KeyboardSettings]
 * because that class's primary constructor is at the JVM's 255-argument
 * ceiling (see [ToolbarBehavior]). Each field still persists under its own
 * DataStore key via the matching setter.
 *
 * The split between the first block and the second is the whole design: the
 * background fetches are things the keyboard does on its own, so they are off
 * by default and get no [MeteredPolicy.ASK] (nobody is there to answer). The
 * rest are things the user opened a panel or pressed a button to start, so
 * they default to asking rather than to silently not working — a GIF grid that
 * comes up empty with no explanation reads as a bug.
 */
data class DataSaverSettings(
    /**
     * Turned on by hand, from settings. Persisted, so it holds on any network
     * until the user turns it off — the way to say "I am on a hotspot the
     * system thinks is free".
     */
    val manual: Boolean = false,
    /** What switches it on by itself. */
    val trigger: DataSaverTrigger = DataSaverTrigger.METERED,

    /**
     * Link previews: the page title and thumbnail fetched for a copied URL and
     * for a scanned QR code. One request per copy, none of it asked for.
     */
    val linkPreviews: MeteredPolicy = MeteredPolicy.BLOCK,
    /** The dictionary and Wikipedia look-up that runs when a word is selected. */
    val dictionaryLookup: MeteredPolicy = MeteredPolicy.BLOCK,
    /**
     * Refilling the rotating photo-background pool. Full-screen photos are the
     * largest automatic download the keyboard makes.
     */
    val photoBackgrounds: MeteredPolicy = MeteredPolicy.BLOCK,
    /** The forecast behind the weather chip, refreshed as you type about rain. */
    val weatherChip: MeteredPolicy = MeteredPolicy.BLOCK,
    /**
     * The Wiktionary recording behind the vocabulary card's speaker button.
     * Blocked by default rather than asked: a spoken word has a free fallback
     * (the platform synthesiser), so a notice over a 30 KB clip would be noise.
     */
    val vocabAudio: MeteredPolicy = MeteredPolicy.BLOCK,
    /**
     * Exchange-rate tables for the currency chip and converter. Allowed by
     * default: a few hundred bytes, cached for hours, and a converter showing
     * last week's rate is worse than the fetch.
     */
    val currencyRates: MeteredPolicy = MeteredPolicy.ALLOW,
    /** Re-reading addon repository indexes to find updates. */
    val addonRefresh: MeteredPolicy = MeteredPolicy.BLOCK,

    /** GIF and sticker search, including the trending grid a panel opens on. */
    val mediaSearch: MeteredPolicy = MeteredPolicy.ASK,
    /** Web and image search results, and the thumbnails an image grid loads. */
    val webSearch: MeteredPolicy = MeteredPolicy.ASK,
    /** Fetching the animated version of a held emoji to send as a GIF. */
    val animatedEmoji: MeteredPolicy = MeteredPolicy.ASK,
    /**
     * Downloads: language data, dictionaries, emoji keyword packs, addons and
     * the on-device speech and language models. The largest of these run to
     * hundreds of megabytes, which is why the default asks rather than blocks —
     * a download refused with no word said looks broken.
     */
    val downloads: MeteredPolicy = MeteredPolicy.ASK,
    /**
     * Cloud AI: chat, the AI actions, and translation through a paid API.
     * Small payloads, so this is about the bill and about roaming rather than
     * about bytes.
     */
    val cloudAi: MeteredPolicy = MeteredPolicy.ASK,
) {
    /** Whether data saving should be in force given the device's [state]. */
    fun appliesTo(state: DeviceNetworkState): Boolean {
        if (manual) return true
        return when (trigger) {
            DataSaverTrigger.OFF -> false
            DataSaverTrigger.METERED -> state.metered
            DataSaverTrigger.ROAMING -> state.roaming
            DataSaverTrigger.SYSTEM_SAVER -> state.systemDataSaver
            DataSaverTrigger.EITHER ->
                state.metered || state.roaming || state.systemDataSaver
        }
    }

    /** Whether anything is actually held back — a saver that restricts nothing is inert. */
    val restrictsAnything: Boolean
        get() = listOf(
            linkPreviews, dictionaryLookup, photoBackgrounds, weatherChip,
            currencyRates, addonRefresh, mediaSearch, webSearch, animatedEmoji,
            downloads, cloudAi,
            vocabAudio,
        ).any { it != MeteredPolicy.ALLOW }
}

/**
 * The features whose gate cannot be expressed as a settings field, because
 * they are moments rather than states: a search the user just ran, a download
 * they just started, a request about to go out.
 *
 * The [DataSaverSettings] fields that *are* settings-shaped — link previews,
 * the dictionary look-up, the photo pool, the weather chip — never appear here;
 * they are switched off in [onMeteredNetwork] instead, so their call sites stay
 * ignorant of data saving entirely.
 */
enum class MeteredFeature {
    MEDIA_SEARCH,
    WEB_SEARCH,
    ANIMATED_EMOJI,
    DOWNLOADS,
    CLOUD_AI,
    CURRENCY_RATES,
    ADDON_REFRESH,

    /** The online look-up that fills in a word added to a vocabulary list. */
    DICTIONARY_LOOKUP,

    /** The vocabulary card's Wiktionary recording. */
    VOCAB_AUDIO,
}

/** What may happen to one [MeteredFeature] right now. */
enum class MeteredDecision {
    /** Go ahead: data saving is off, the policy allows it, or it was granted. */
    ALLOWED,

    /** Hold, and offer it: the caller shows the notice with its "use data" action. */
    ASK,

    /** Do not go, and do not offer: the user has already answered for this one. */
    BLOCKED,
}

/**
 * Data saving as the running keyboard sees it: the decision, the settings it
 * came from, and what the user has already said yes to.
 *
 * [grants] is deliberately session state, never persisted, and dropped
 * whenever the network changes underneath it. A yes given on the train home is
 * an answer about that connection; carrying it into next month's roaming would
 * be answering a question the user was never asked.
 */
data class DataSaverStatus(
    val active: Boolean = false,
    val settings: DataSaverSettings = DataSaverSettings(),
    val grants: Set<MeteredFeature> = emptySet(),
) {
    fun policyFor(feature: MeteredFeature): MeteredPolicy = when (feature) {
        MeteredFeature.MEDIA_SEARCH -> settings.mediaSearch
        MeteredFeature.WEB_SEARCH -> settings.webSearch
        MeteredFeature.ANIMATED_EMOJI -> settings.animatedEmoji
        MeteredFeature.DOWNLOADS -> settings.downloads
        MeteredFeature.CLOUD_AI -> settings.cloudAi
        MeteredFeature.CURRENCY_RATES -> settings.currencyRates
        MeteredFeature.ADDON_REFRESH -> settings.addonRefresh
        MeteredFeature.DICTIONARY_LOOKUP -> settings.dictionaryLookup
        MeteredFeature.VOCAB_AUDIO -> settings.vocabAudio
    }

    fun decide(feature: MeteredFeature): MeteredDecision = when {
        !active -> MeteredDecision.ALLOWED
        feature in grants -> MeteredDecision.ALLOWED
        else -> when (policyFor(feature)) {
            MeteredPolicy.ALLOW -> MeteredDecision.ALLOWED
            MeteredPolicy.ASK -> MeteredDecision.ASK
            MeteredPolicy.BLOCK -> MeteredDecision.BLOCKED
        }
    }

    /** Shorthand for the call sites that only care whether they may go now. */
    fun allows(feature: MeteredFeature): Boolean =
        decide(feature) == MeteredDecision.ALLOWED

    /** The same status with [feature] granted for the rest of this session. */
    fun granting(feature: MeteredFeature): DataSaverStatus =
        copy(grants = grants + feature)
}

/**
 * The settings as they apply while data saving is in force.
 *
 * Written the same way as [underPowerSaving] and [restrictedToDirectBoot]: a
 * view applied on the way out of the repository, never persisted, so the
 * moment the phone is back on Wi-Fi every setting the user actually chose is
 * back without anything having been rewritten.
 *
 * Only the background fetches are here. Everything the user starts by hand is
 * decided at the moment they start it, through [DataSaverStatus], because
 * those need the third answer — asking — which a settings field cannot hold.
 */
fun KeyboardSettings.onMeteredNetwork(): KeyboardSettings {
    val ds = dataSaver
    return copy(
        clipboard = if (ds.linkPreviews.stopsBackgroundWork) {
            clipboard.copy(linkPreviews = false)
        } else {
            clipboard
        },
        qrScanLinkPreviews =
        if (ds.linkPreviews.stopsBackgroundWork) false else qrScanLinkPreviews,
        dictionaryAutoLookup =
        if (ds.dictionaryLookup.stopsBackgroundWork) false else dictionaryAutoLookup,
        // The photo pool already has its own metered switch, which the pool's
        // own `mayFetch` reads. Clearing it here means one decision reaches
        // both the rotation and the top-up, and the user's own choice comes
        // back untouched the moment the network is free again.
        photoBackground = if (ds.photoBackgrounds.stopsBackgroundWork) {
            photoBackground.copy(fetchOnMetered = false)
        } else {
            photoBackground
        },
        smartChips = if (ds.weatherChip.stopsBackgroundWork) {
            smartChips.copy(weather = false)
        } else {
            smartChips
        },
    )
}
