package com.wasimaster.wmkeyboard.core.clipboard

/**
 * Links written as the tail of the link before them (#612), the way Combine
 * joins a few of one site's links: the host is written once, and each link
 * after it only says what is different.
 *
 * - `youtube.com/watch?v=X; =Y` and `youtube.com/watch?v=X; Y`: a new value
 *   for the last query parameter, written with its `=` or, when it is shaped
 *   like the value it replaces (a video id), alone;
 * - `github.com/wasi-master/wmkeyboard; /wasi-master/wmkeyboard-data`: a path
 *   from the root, recognised by starting with the link's own first folder,
 *   or by any of its folders, from which point it carries on;
 * - `github.com/wasi-master/wmkeyboard/issues/612; /1312#issuecomment-1`: any
 *   other path takes the place of as many of the link's last folders as it has.
 *
 * Pure text in and out; [ClipEntities.extract] decides what counts as a link.
 */
object ClipLinkChain {

    /** Where [url] runs into a tail written after it (`…?v=X,=Y`, `…/b;/c`): the link before that. */
    fun head(url: String): String {
        val cut = RunOn.find(url)?.range?.first ?: return url
        return url.substring(0, cut)
    }

    /**
     * Every word of [text] that could be a tail, with where it is: the text
     * cut at spaces, commas and semicolons, a bracket or a colon around it
     * taken off. [ClipEntities] skips the ones inside a link it has found.
     */
    fun tokens(text: String): List<Triple<Int, Int, String>> = buildList {
        for (match in Token.findAll(text)) {
            var start = match.range.first
            var end = match.range.last + 1
            while (start < end && text[start] in Leading) start++
            while (end > start && text[end - 1] in Trailing) end--
            if (end - start >= 2) add(Triple(start, end, text.substring(start, end)))
        }
    }

    /** [token] read as a tail of [anchor], as a whole address; null when it is not one. */
    fun resolve(anchor: String, token: String): String? {
        val link = Parsed.of(anchor) ?: return null
        return when {
            token.startsWith("=") -> {
                val value = token.substring(1)
                if (!QueryValue.matches(value)) return null
                link.withQueryValue(value)
            }
            token.startsWith("/") -> {
                val rest = token.substring(1)
                val path = rest.substringBefore('?').substringBefore('#')
                if (path.isEmpty() || !PathChars.matches(path)) return null
                val tail = rest.substring(path.length)
                val segments = path.split('/')
                val named = segments.count { it.isNotEmpty() }
                if (named == 0) return null
                val at = link.segments.indexOf(segments.first())
                val kept = if (at >= 0) link.segments.take(at) else link.segments.dropLast(minOf(named, link.segments.size))
                link.base + "/" + (kept + segments).joinToString("/") + tail
            }
            isBareId(token) -> {
                val value = link.query.lastOrNull()?.substringAfter('=', "")
                when {
                    value != null && sameShape(value, token) -> link.withQueryValue(token)
                    link.segments.lastOrNull()?.let { sameShape(it, token) } == true ->
                        link.base + "/" + (link.segments.dropLast(1) + token).joinToString("/")
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Whether [token] reads as an id rather than a word: id characters only,
     * long enough, and a digit or a capital past the first letter in it, as
     * `A1bcdeFg2H3` has and `downloads` or `well-known` do not.
     */
    private fun isBareId(token: String): Boolean =
        token.length >= MIN_BARE_ID && BareId.matches(token) &&
            (token.any { it.isDigit() } || token.drop(1).any { it.isUpperCase() })

    /** A bare id stands for [value] only when it is as long and made of the same kind of characters. */
    private fun sameShape(value: String, token: String): Boolean =
        value.length == token.length && token.length >= MIN_BARE_ID && BareId.matches(value)

    /** A link cut into the parts a tail replaces. */
    private class Parsed(
        /** The scheme, if written, and the host with its port. */
        val base: String,
        /** The path's folders, without the empty one a trailing slash leaves. */
        val segments: List<String>,
        /** The query's `name=value` pairs, in order. */
        val query: List<String>,
    ) {
        /** This link with its last query parameter's value set to [value], fragment dropped. */
        fun withQueryValue(value: String): String? {
            val last = query.lastOrNull()?.takeIf { '=' in it } ?: return null
            val pairs = query.dropLast(1) + (last.substringBefore('=') + "=" + value)
            val path = if (segments.isEmpty()) "" else "/" + segments.joinToString("/")
            return base + path + "?" + pairs.joinToString("&")
        }

        companion object {
            fun of(url: String): Parsed? {
                val scheme = SchemePrefix.find(url)?.value.orEmpty()
                val rest = url.substring(scheme.length)
                val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
                if (hostEnd == 0) return null
                val base = scheme + rest.substring(0, hostEnd)
                val after = rest.substring(hostEnd).substringBefore('#')
                val path = after.substringBefore('?')
                val query = after.substringAfter('?', "")
                return Parsed(
                    base = base,
                    segments = path.split('/').filter { it.isNotEmpty() },
                    query = if (query.isEmpty()) emptyList() else query.split('&'),
                )
            }
        }
    }

    private val SchemePrefix = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** A comma or semicolon that starts a tail inside what looked like one link. */
    private val RunOn = Regex("""[,;](?=[=/])""")

    /** A run of anything but the separators tails are written between. */
    private val Token = Regex("""[^\s,;]+""")

    private const val Leading = "([{\"'<"
    private const val Trailing = ")]}\"'>.:!?"

    private val QueryValue = Regex("""[A-Za-z0-9_.~%-]{1,200}""")
    private val PathChars = Regex("""[A-Za-z0-9_.~%@:+!$&'()*=/-]+""")
    private val BareId = Regex("""[A-Za-z0-9_-]+""")

    /** The shortest bare id read as a tail: anything shorter is a word or a number. */
    private const val MIN_BARE_ID = 6
}
