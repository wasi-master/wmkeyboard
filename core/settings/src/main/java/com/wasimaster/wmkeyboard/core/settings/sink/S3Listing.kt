package com.wasimaster.wmkeyboard.core.settings.sink

import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Reads the XML an S3 `ListObjectsV2` answers with.
 *
 * Pure and apart from the sink, for the same reason [WebDavListing] is: this is
 * the fiddly part and the sink is the untestable part. The document is far more
 * uniform than WebDAV's, but the services still differ — MinIO omits
 * `KeyCount`, some return `NextContinuationToken` without `IsTruncated`, and
 * the timestamp has a variable number of decimal places.
 */
object S3Listing {

    data class Key(
        val key: String,
        val sizeBytes: Long,
        val modifiedAtMs: Long,
    )

    data class Page(
        val keys: List<Key>,
        /** Null when this was the last page. */
        val continuationToken: String?,
        /**
         * The `CommonPrefixes` of a listing asked with a delimiter: the
         * "folders" one level down, each ending in the delimiter. Empty for a
         * listing without one, which is all the backup asks for.
         */
        val prefixes: List<String> = emptyList(),
    )

    private val DOCTYPE = Regex("""<!DOCTYPE""", RegexOption.IGNORE_CASE)

    private const val MAX_SOURCE_BYTES = 4 * 1024 * 1024

    /** One page of keys, or an empty page when [xml] is not a listing at all. */
    fun parse(xml: String): Page {
        if (xml.length > MAX_SOURCE_BYTES) return Page(emptyList(), null)
        // Same XXE guard, and the same reason as WebDavListing: Android's SAX
        // does not recognise the feature that would do this properly.
        if (DOCTYPE.containsMatchIn(xml)) return Page(emptyList(), null)

        val handler = Handler()
        runCatching {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                disableQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
                disableQuietly("http://xml.org/sax/features/external-general-entities", false)
                disableQuietly("http://xml.org/sax/features/external-parameter-entities", false)
                runCatching { setXIncludeAware(false) }
            }
            factory.newSAXParser().parse(InputSource(StringReader(xml)), handler)
        }
        return Page(handler.keys, handler.token?.takeIf { it.isNotEmpty() }, handler.prefixes)
    }

    /**
     * ISO 8601 in UTC, for example `2026-08-07T14:02:33.000Z`.
     *
     * Not `java.time`, which is API 26 and this app supports 24 with no
     * desugaring. The fractional part is dropped first so a service that sends
     * a different number of decimals, or none, still parses.
     */
    fun parseIso8601(value: String): Long = runCatching {
        val seconds = value.substringBefore('.').trimEnd('Z')
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(seconds)
            ?.time
            ?: 0L
    }.getOrDefault(0L)

    private fun SAXParserFactory.disableQuietly(feature: String, value: Boolean) {
        runCatching { setFeature(feature, value) }
    }

    private fun local(qName: String): String = qName.substringAfterLast(':')

    private class Handler : DefaultHandler() {

        val keys = ArrayList<Key>()
        val prefixes = ArrayList<String>()
        var token: String? = null

        private var inContents = false

        // The request's own <Prefix> sits at the top level too; only the ones
        // inside <CommonPrefixes> are folders.
        private var inCommonPrefixes = false
        private var key: String? = null
        private var size = -1L
        private var modified = 0L
        private var text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String, at: Attributes?) {
            text = StringBuilder()
            if (local(qName) == "CommonPrefixes") inCommonPrefixes = true
            if (local(qName) == "Contents") {
                inContents = true
                key = null
                size = -1L
                modified = 0L
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            when (local(qName)) {
                "Key" -> if (inContents) key = value
                "Size" -> if (inContents) size = value.toLongOrNull() ?: -1L
                "LastModified" -> if (inContents) modified = parseIso8601(value)
                "NextContinuationToken" -> if (!inContents) token = value
                "Prefix" -> if (inCommonPrefixes && value.isNotEmpty()) prefixes += value
                "CommonPrefixes" -> inCommonPrefixes = false
                "Contents" -> {
                    key?.takeIf { it.isNotEmpty() }?.let {
                        keys += Key(key = it, sizeBytes = size, modifiedAtMs = modified)
                    }
                    inContents = false
                }
            }
            text = StringBuilder()
        }
    }
}
