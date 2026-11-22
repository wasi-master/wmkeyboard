package com.wasimaster.wmkeyboard.core.selection

/**
 * Optional changes Remove trackers makes to a link on top of taking its
 * trackers off, each switched on by the user (the ideas are Link Cleaner's,
 * linkcleaner.app). None of them is about privacy alone, which is why none
 * is on by default: each changes the address in a way somebody might not
 * want.
 */
enum class LinkRewrite {
    /**
     * On pages whose address is fully known (an Amazon, eBay, Walmart or
     * Best Buy product, a YouTube video, a Reddit thread; posts and TikTok
     * videos get this without asking)
     * every parameter the page does not need goes, tracker or not, and a
     * product's path is cut to its number. Shorter and cleaner, but a
     * parameter nobody listed (a seller, a size) can go with the rest.
     */
    KNOWN_PAGES,

    /**
     * Every query parameter goes on every site, except the few that say which
     * page it is (`q`, `id`, `v`, `page` and the ones a known page needs), as
     * Link Cleaner does. Includes [KNOWN_PAGES]. The shortest links, at the
     * price of any site that needs a parameter nobody listed.
     */
    STRICT,

    /** `youtube.com/watch?v=abc&t=42` becomes `youtu.be/abc?t=42`. */
    YOUTUBE_SHORT,

    /** `youtube.com/shorts/abc` becomes `youtube.com/watch?v=abc`, the ordinary player. */
    YOUTUBE_SHORTS_AS_VIDEOS,

    /** `music.youtube.com/watch?v=abc` becomes `www.youtube.com/watch?v=abc`. */
    YOUTUBE_MUSIC_AS_YOUTUBE,

    /**
     * X and Twitter links go through FxTwitter (`fxtwitter.com`, `fixupx.com`),
     * which gives a post a full preview in Discord, Telegram and the like.
     */
    TWITTER_EMBEDS,

    /** Bluesky posts go through FxBluesky (`fxbsky.app`), for the same previews. */
    BLUESKY_EMBEDS,

    /**
     * Letters written as `%D0%9C…` are shown as the letters they are, so
     * `wiki/%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0` reads `wiki/Москва`. Only
     * letters, marks and digits are decoded: a space, an `&`, a `/` or an
     * invisible character stays written in `%`, so the link opens the same page
     * and cannot hide anything.
     */
    READABLE,
    ;

    companion object {
        /** The stored form: names joined by commas, unknown names dropped. */
        fun decode(raw: String): Set<LinkRewrite> = raw.split(',')
            .mapNotNullTo(mutableSetOf()) { name -> entries.firstOrNull { it.name == name.trim() } }

        fun encode(rewrites: Set<LinkRewrite>): String = entries.filter { it in rewrites }.joinToString(",") { it.name }
    }
}

/**
 * How Remove trackers cleans a link: the user's own tracking parameter names
 * on top of the built-in ones ([extra], a name ending in `*` is a family),
 * and the rewrites they switched on.
 */
data class LinkCleaning(
    val extra: Collection<String> = emptyList(),
    val rewrites: Set<LinkRewrite> = emptySet(),
)

/**
 * A link cut into its pieces as written, so a rewrite can change one and put
 * the rest back exactly: [scheme] is `https://` or empty for a bare domain,
 * [query] and [fragment] are null when the link has no `?` or `#` at all.
 */
internal data class UrlParts(
    val scheme: String,
    val authority: String,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    /** The host, lower case, without the user name or the port. */
    val host: String get() = authority.substringAfterLast('@').substringBefore(':').lowercase()

    override fun toString(): String = buildString {
        append(scheme).append(authority).append(path)
        if (query != null) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    }

    companion object {
        fun of(url: String): UrlParts {
            // A scheme only counts before the first `/`, `?` or `#`: in
            // `example.com/go?u=https://…` the `://` is the query's.
            val sep = url.indexOf("://")
            val firstDelimiter = url.indexOfAny(charArrayOf('/', '?', '#'))
            val schemeEnd = if (sep > 0 && (firstDelimiter < 0 || sep <= firstDelimiter)) sep + 3 else 0
            val hostEnd = url.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd).let { if (it < 0) url.length else it }
            val rest = url.substring(hostEnd)
            val hash = rest.indexOf('#')
            val beforeHash = if (hash < 0) rest else rest.substring(0, hash)
            val q = beforeHash.indexOf('?')
            return UrlParts(
                scheme = url.substring(0, schemeEnd),
                authority = url.substring(schemeEnd, hostEnd),
                path = if (q < 0) beforeHash else beforeHash.substring(0, q),
                query = if (q < 0) null else beforeHash.substring(q + 1),
                fragment = if (hash < 0) null else rest.substring(hash + 1),
            )
        }
    }
}
