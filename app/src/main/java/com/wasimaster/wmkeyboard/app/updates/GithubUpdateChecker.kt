package com.wasimaster.wmkeyboard.app.updates

import kotlinx.serialization.json.Json

/** What one attempt at the release list came back with. */
internal sealed interface FetchResult {
    /** A body, and the ETag to send next time if there was one. */
    data class Fresh(val body: String, val etag: String?) : FetchResult

    /** The list has not changed since [ReleaseCache] last stored it. */
    data object NotModified : FetchResult

    /** GitHub is rate limiting. [untilMillis] is when it says that stops. */
    data class RateLimited(val untilMillis: Long) : FetchResult

    /** Anything else: no connection, a timeout, a status with nothing to say. */
    data object Failed : FetchResult
}

/** Where the release list comes from. An interface so the rules can be tested. */
internal interface ReleaseFetcher {
    fun fetch(etag: String?): FetchResult
}

/**
 * The last release list that arrived with a body.
 *
 * Not an optimisation. A 304 is the answer this app wants most of the time,
 * and a 304 has no body, so without somewhere to have kept the last one there
 * would be nothing to offer the user on any check after the first.
 */
internal interface ReleaseCache {
    fun read(): String?
    fun write(body: String)
    fun exists(): Boolean
    fun clear()
}

/** What a check concluded. */
internal sealed interface CheckOutcome {
    data object UpToDate : CheckOutcome
    data class Available(val candidate: UpdateCandidate) : CheckOutcome
    data class RateLimited(val untilMillis: Long) : CheckOutcome
    data object Failed : CheckOutcome
}

/**
 * The release check, with the network and the disk behind interfaces.
 *
 * Everything here is decisions: which release, whether it is newer, what state
 * that becomes. Nothing here touches Android, which is what lets it be tested
 * in every build channel, including the ones where the GitHub driver is not
 * even compiled.
 */
internal class GithubUpdateChecker(
    private val fetcher: ReleaseFetcher,
    private val cache: ReleaseCache,
) {

    /**
     * Asks GitHub, and says what this install should do about the answer.
     *
     * [onEtag] is called with the ETag of a body worth storing, so the caller
     * can keep it beside the cache it just wrote. It is a callback rather than
     * a field on the result because a 304 has to leave the old one in place.
     */
    @Suppress("ReturnCount", "LongParameterList")
    fun check(
        installedVersionCode: Int,
        flavor: String,
        supportedAbis: List<String>,
        includePrereleases: Boolean,
        storedEtag: String?,
        onEtag: (String?) -> Unit,
        languages: String = ReleaseAssets.LANGUAGES_EN,
        allowSameVersion: Boolean = false,
        onyxPen: Boolean = false,
    ): CheckOutcome {
        val sent = UpdateCheckGate.etagToSend(storedEtag, cache.exists())
        val body = when (val result = fetcher.fetch(sent)) {
            is FetchResult.Fresh -> {
                cache.write(result.body)
                onEtag(result.etag)
                result.body
            }
            // Only possible when an ETag was sent, which only happens when the
            // cache was there to describe. If it has gone since, the honest
            // move is to forget the ETag so the next check asks in full.
            FetchResult.NotModified -> cache.read() ?: run {
                onEtag(null)
                return CheckOutcome.Failed
            }
            is FetchResult.RateLimited -> return CheckOutcome.RateLimited(result.untilMillis)
            FetchResult.Failed -> return CheckOutcome.Failed
        }
        return decide(
            body, installedVersionCode, flavor, supportedAbis, includePrereleases,
            languages, allowSameVersion, onyxPen,
        )
    }

    /**
     * The same decision against whatever is already cached, with no request.
     *
     * Used on start-up, and when the pre-release setting changes: both want an
     * answer immediately and neither is a reason to spend a request.
     */
    fun fromCache(
        installedVersionCode: Int,
        flavor: String,
        supportedAbis: List<String>,
        includePrereleases: Boolean,
        languages: String = ReleaseAssets.LANGUAGES_EN,
        onyxPen: Boolean = false,
    ): CheckOutcome {
        val body = cache.read() ?: return CheckOutcome.Failed
        return decide(body, installedVersionCode, flavor, supportedAbis, includePrereleases, languages, false, onyxPen)
    }

    @Suppress("LongParameterList")
    private fun decide(
        body: String,
        installedVersionCode: Int,
        flavor: String,
        supportedAbis: List<String>,
        includePrereleases: Boolean,
        languages: String,
        allowSameVersion: Boolean,
        onyxPen: Boolean,
    ): CheckOutcome {
        // A body that will not decode is not a reason to keep answering from
        // it forever, so it goes.
        val releases = GithubReleaseCodec.decode(body) ?: run {
            cache.clear()
            return CheckOutcome.Failed
        }
        val candidate = ReleaseAssets.chooseCandidate(
            releases = releases,
            installedVersionCode = installedVersionCode,
            flavor = flavor,
            supportedAbis = supportedAbis,
            includePrereleases = includePrereleases,
            languages = languages,
            allowSameVersion = allowSameVersion,
            onyxPen = onyxPen,
        )
        return if (candidate == null) CheckOutcome.UpToDate else CheckOutcome.Available(candidate)
    }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** A candidate as stored in [UpdatePrefs.stagedCandidate]. */
        fun encode(candidate: UpdateCandidate): String = json.encodeToString(candidate)

        /** [encode] backwards, tolerant of a value written by an older version. */
        fun decodeCandidate(text: String?): UpdateCandidate? = text
            ?.let { runCatching { json.decodeFromString<UpdateCandidate>(it) }.getOrNull() }

        /**
         * The state an available candidate should put the UI into.
         *
         * [UpdatePolicy] does the deciding, with the two numbers Play would
         * have supplied filled in from what a GitHub release actually knows:
         * there is no publishing priority, so every release is an ordinary
         * one, and only the background flow exists, because nothing here can
         * take over the screen the way Play's blocking update does.
         *
         * [fileVerified] short-circuits all of it: a download that already
         * finished is past the point where any of this applies.
         */
        @Suppress("LongParameterList")
        fun offer(
            candidate: UpdateCandidate,
            snoozed: Boolean,
            autoPrompt: Boolean,
            allowPrompt: Boolean,
            userAsked: Boolean,
            now: Long,
            fileVerified: Boolean,
        ): UpdateState {
            if (fileVerified) return UpdateState.Downloaded
            val plan = UpdatePolicy.plan(
                priority = 0,
                stalenessDays = ReleaseAssets.stalenessDays(candidate.publishedAtMillis, now),
                immediateAllowed = false,
                flexibleAllowed = true,
                snoozed = snoozed,
                autoPrompt = autoPrompt,
            )
            return UpdateState.Available(
                versionCode = candidate.versionCode,
                sizeBytes = candidate.sizeBytes,
                immediate = false,
                dismissed = snoozed,
                versionName = candidate.versionName,
                releaseUrl = candidate.releaseUrl,
                // A check the user asked for never opens a dialog on its own:
                // they are looking at the row that is about to answer them.
                promptOpen = plan.automatic && allowPrompt && !userAsked && !snoozed,
            )
        }
    }
}
