package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.endpoints.RepoLocation
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo

/**
 * Where the free-software services live, for the build that uses them.
 *
 * The F-Droid edition talks to LibreTranslate, SearXNG and a MediaWiki instead
 * of Google Translate, Brave, GIPHY, Unsplash and Pexels. Every one of the
 * replacements is free software that anybody can run, and that is the point:
 * F-Droid's anti-feature for network services is waived when "there is a simple
 * configuration option that allows pointing the app to a running instance of an
 * alternative, publicly available, self-hostable server solution". These fields
 * are that option, so all three are settable — including the one with a working
 * default, because a default nobody can change is exactly the tethering the
 * rule is about.
 *
 * One nested bag rather than four flat fields on [KeyboardSettings]: that class
 * generates a `copy$default` whose argument count is capped at 245 by the JVM,
 * and it sits close enough to the ceiling that four more would be reckless. A
 * fresh nested family costs one slot however many settings go inside it.
 *
 * The other channels never read any of this — they keep the keyed providers,
 * and the settings screens hide the whole section.
 */
data class SelfHostedSettings(
    /**
     * Base URL of a LibreTranslate server, or blank.
     *
     * No default, deliberately. Every public instance either rate-limits
     * anonymous callers hard or requires a key, so a baked-in host would work
     * for the first few people to use it and fail for everyone after, with
     * nothing on screen to explain why.
     */
    val libreTranslateUrl: String = "",

    /** Optional: a self-hosted instance usually needs none, a public one does. */
    val libreTranslateApiKey: String = "",

    /**
     * Base URL of a SearXNG instance, or blank.
     *
     * No default for a sharper reason than LibreTranslate's: SearXNG ships with
     * `search.formats: [html]`, so an instance that has not opted into JSON
     * answers this app with 403. Most public ones have not, which makes a
     * default actively misleading.
     */
    val searxUrl: String = "",

    /**
     * Base URL of a MediaWiki `api.php` for photos and GIFs, or blank for
     * Wikimedia Commons.
     *
     * This one *does* default, because Commons needs no key, serves freely
     * licensed media and is the reason the tool can exist on this channel at
     * all. It is still overridable so that pointing at your own wiki is a
     * setting rather than a fork.
     */
    val commonsUrl: String = "",

    /**
     * Base addresses for every other service, keyed by [ServiceEndpoint.id].
     * A missing or unusable entry means the service's own default; see
     * [ServiceEndpoints.resolveBase].
     */
    val endpoints: Map<String, String> = emptyMap(),

    /** Git locations for the downloaded data, keyed by [ServiceRepo.id]. Missing means the default. */
    val repos: Map<String, RepoLocation> = emptyMap(),

    /**
     * A GitHub proxy for downloads, or blank. Unlike the rest of this class it
     * is read on every build: GitHub is where the GitHub and Play builds'
     * downloads come from too, and it is unreachable from some countries
     * (#1045). See [com.wasimaster.wmkeyboard.core.download.DataMirror.proxy].
     */
    val downloadMirror: String = "",
)
