package com.wasimaster.wmkeyboard.ime.dynstickers

import android.content.Context
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.DynStickerFeed
import com.wasimaster.wmkeyboard.core.stickers.dynamic.DynStickerStore
import com.wasimaster.wmkeyboard.core.stickers.dynamic.PackSource
import com.wasimaster.wmkeyboard.core.stickers.dynamic.TemplateFeed
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Dynamic sticker feeds: a link the user added that lists template packs to
 * download. Either our own index (`wmkeyboard-dynstickers-feed`, a pack per
 * entry) or the zip-set manifest some template sets ship (`base_download_url`
 * and `packs[]`, whose zips together make one pack: the documents in one, the
 * fonts in another).
 *
 * A refresh reads the index, skips the downloads when its version has not
 * moved, and otherwise fetches every file (checked against the checksum the
 * index gives, when it gives one) and installs them over the packs the feed
 * installed last time. Packs it no longer lists go.
 */
object DynStickerFeeds {

    /** A fresh id for a feed being added. */
    fun newId(): String = "f" + UUID.randomUUID().toString().replace("-", "").take(ID_CHARS)

    /**
     * Refreshes [feed] into [store]; [force] downloads even an unchanged
     * version. Returns the feed as it should now be stored: its name, version,
     * time and packs, or its error. Blocking network and disk; call off the
     * main thread.
     */
    suspend fun refresh(context: Context, store: DynStickerStore, feed: DynStickerFeed, force: Boolean = false): DynStickerFeed =
        withContext(Dispatchers.IO) {
            runCatching { refreshOrThrow(context, store, feed, force) }
                .getOrElse { feed.copy(error = it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName) }
        }

    private suspend fun refreshOrThrow(context: Context, store: DynStickerStore, feed: DynStickerFeed, force: Boolean): DynStickerFeed {
        val url = feed.url.trim()
        require(url.startsWith("https://", ignoreCase = true)) { "Only https:// links can be feeds" }
        val text = ToolHttp.get(url, timeoutMs = INDEX_TIMEOUT_MS, source = NetSource.DYNAMIC_STICKERS, route = NetLog.pathOf(url))
        val index = TemplateFeed.parse(text, url) ?: error("Not a template feed")
        require(index.downloads.size <= MAX_DOWNLOADS) { "The feed lists too many files" }
        val version = index.version.ifBlank { Integer.toHexString(text.hashCode()) }
        val installed = store.packsOfFeed(feed.id)
        val name = index.name.ifBlank { feed.name.ifBlank { hostOf(url) } }
        if (!force && version == feed.version && installed.isNotEmpty()) {
            return feed.copy(name = name, refreshedAt = System.currentTimeMillis(), error = null)
        }
        val dir = File(context.cacheDir, "dynstickers/feeds/${feed.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val files = index.downloads.mapIndexed { i, download -> fetch(download, File(dir, "d$i.zip")) }
            val packIds = ArrayList<String>()
            if (index.assembled) {
                // One pack out of every zip: replace the one this feed made before.
                val inputs = files.map { FileInputStream(it) as InputStream }
                val result = try {
                    store.importArchives(inputs, PackSource.FEED, name, feedId = feed.id, sourceUrl = url, replaceId = installed.firstOrNull()?.id)
                } finally {
                    inputs.forEach { runCatching { it.close() } }
                }
                packIds += imported(result)
            } else {
                index.downloads.zip(files).forEach { (download, file) ->
                    val previous = installed.firstOrNull { it.sourceUrl == download.url }
                    val result = FileInputStream(file).use { input ->
                        store.importArchive(
                            input, PackSource.FEED, download.name.ifBlank { name },
                            feedId = feed.id, sourceUrl = download.url, replaceId = previous?.id,
                        )
                    }
                    packIds += imported(result)
                }
            }
            // What the feed stopped listing leaves with it.
            installed.filter { it.id !in packIds }.forEach { store.deletePack(it.id) }
            return feed.copy(
                name = name,
                version = version,
                refreshedAt = System.currentTimeMillis(),
                error = null,
                packIds = packIds,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun imported(result: DynStickerStore.ImportResult): List<String> = when (result) {
        is DynStickerStore.ImportResult.Imported -> listOf(result.pack.id)
        DynStickerStore.ImportResult.NoTemplates -> error("A file in the feed has no templates in it")
        DynStickerStore.ImportResult.TooMany -> error("The feed has more templates than the keyboard keeps")
        DynStickerStore.ImportResult.Failed -> error("The templates could not be saved")
    }

    /** Downloads [download] to [target] and checks it against its checksum, when the feed gave one. */
    private suspend fun fetch(download: TemplateFeed.Download, target: File): File {
        val part = File(target.parentFile, target.name + ".part")
        FileDownloader.fetch(
            download.url,
            part,
            size = download.size.takeIf { it > 0 },
            source = NetSource.DYNAMIC_STICKERS,
            maxBytes = MAX_FILE_BYTES,
        )
        val expected = download.checksum?.lowercase()
        val algorithm = download.algorithm
        if (expected != null && algorithm != null) {
            val actual = digest(part, algorithm)
            if (actual != expected) {
                FileDownloader.discard(part)
                error("${download.name.ifBlank { "A file" }} did not match the feed's checksum")
            }
        }
        if (!part.renameTo(target)) error("Could not save ${download.name}")
        return target
    }

    private fun digest(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.").orEmpty().ifBlank { url }

    /** Feeds a week or more old, for the weekly refresh. */
    fun stale(feeds: List<DynStickerFeed>, now: Long): List<DynStickerFeed> =
        feeds.filter { it.enabled && now - it.refreshedAt >= WEEK_MS }

    private const val ID_CHARS = 12
    private const val INDEX_TIMEOUT_MS = 20_000
    private const val MAX_DOWNLOADS = 40
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val BUFFER = 64 * 1024
    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
}
