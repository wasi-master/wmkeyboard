package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.vocab.VocabPackFile
import com.wasimaster.wmkeyboard.addons.feature.R
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestResult
import com.wasimaster.wmkeyboard.core.plugins.PluginPermission
import com.wasimaster.wmkeyboard.core.snippets.SnippetPayload
import com.wasimaster.wmkeyboard.core.feedback.SoundPackFile
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * What an addon's payload actually contains, read off a downloaded file without
 * installing anything.
 *
 * Screenshots answer "what does this look like"; they can't answer "which words
 * are in this dictionary" or "what does this sound like", which for four of the
 * types is the entire question. So those four get read and summarised here, and
 * the detail page shows the result.
 *
 * Everything is capped: this runs on a phone, against a file a stranger wrote.
 */
sealed interface AddonPreviewContent {

    /** The snippets in a pack, in file order. */
    data class Snippets(
        val entries: List<Entry>,
        val total: Int,
        /**
         * What converting the pack cost, for a pack that had to be converted.
         *
         * Always empty for this app's own format, which needs no conversion. An
         * Espanso pack can lose things on the way in, and reading that after
         * installing is worse than being told and deciding, so it belongs in the
         * preview rather than in the result.
         */
        val notes: List<AddonText> = emptyList(),
    ) : AddonPreviewContent {
        data class Entry(
            val label: String,
            val text: String,
            val trigger: String,
            /** The trigger offers itself on the strip rather than rewriting. */
            val confirm: Boolean = false,
        )
    }

    /** The head of a vocabulary pack: its name and a few words with their first definition. */
    data class Vocabulary(
        val name: String,
        val samples: List<Sample>,
        val total: Int,
    ) : AddonPreviewContent {
        data class Sample(val word: String, val pos: String, val definition: String)
    }

    /** A word list, plus how many lines it actually has. */
    data class Dictionary(
        /**
         * Every word the reader collected, up to [AddonPreviewReader.MAX_WORDS].
         * The panel shows the first few and the dialog shows the lot, so this is
         * deliberately far longer than fits on a screen.
         */
        val words: List<String>,
        val total: Int,
        /** True when [total] is a floor rather than the real count — the file was long. */
        val truncated: Boolean,
    ) : AddonPreviewContent {
        /** True when [words] is a prefix of the file rather than all of it. */
        val partial: Boolean get() = words.size < total
    }

    /**
     * A sample of an emoji keyword pack: which emoji it names, and what it
     * calls them.
     *
     * A handful of rows is the whole question here — one look at 🎂 next to
     * its keywords tells you whether the pack is the language it claims and
     * whether the keywords are words anyone would type. Unlike a word list,
     * nobody wants to read all four thousand rows.
     */
    data class EmojiKeywords(
        val samples: List<Sample>,
        val total: Int,
        /** True when [total] is a floor rather than the real count. */
        val truncated: Boolean,
    ) : AddonPreviewContent {
        data class Sample(val emoji: String, val keywords: String)
    }

    /** A playable copy of the key sound. */
    data class Sound(val file: File) : AddonPreviewContent

    /**
     * A sound pack's variants, extracted so each can be played on its own.
     *
     * The question a pack raises is not "what does it sound like?" but "how
     * much does it vary?" — that is the whole reason to take a pack over a
     * single sound — so the preview hands back every variant rather than one
     * representative, and names the roles it fills.
     */
    data class SoundPack(
        val name: String,
        val variants: List<File>,
        val totalVariants: Int,
        /** Key-up recordings, if the pack has any; empty is the common case. */
        val releaseVariants: List<File> = emptyList(),
        val totalReleaseVariants: Int = 0,
        val roles: List<String> = emptyList(),
    ) : AddonPreviewContent

    /** Sticker images extracted beside the archive, ready to be shown. */
    data class Stickers(
        val images: List<File>,
        val total: Int,
    ) : AddonPreviewContent

    /**
     * What a plugin says about itself, and what it would be allowed to do.
     *
     * The odd one out: every other preview answers "is this content any good?",
     * while this one answers "should I let this run at all?". Only the manifest
     * is read — showing someone what a plugin claims it can do should not
     * involve touching the code that would do it.
     */
    data class Plugin(
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val permissions: List<PluginPermission>,
    ) : AddonPreviewContent

    /** The payload downloaded but couldn't be read as its declared type. */
    data class Unreadable(val text: AddonText) : AddonPreviewContent
}

/** Reads a downloaded payload into something showable. Blocking; call on IO. */
object AddonPreviewReader {

    /** More than this and the panel is a wall of text nobody reads. */
    private const val MAX_SNIPPETS = 40

    /**
     * How many words a dictionary preview keeps.
     *
     * The panel only ever shows a handful inline, but "show me the whole list"
     * is the question a word list actually raises, so the dialog needs the
     * words in hand. Ten thousand strings is a couple of hundred KB and covers
     * every dictionary anyone browses word-by-word; past that the dialog says
     * how much it is not showing rather than holding a 300k-word file in RAM
     * for a preview.
     */
    const val MAX_WORDS = 10_000

    /** Stop counting lines here; a 300k-word list doesn't need an exact figure. */
    private const val MAX_COUNTED_LINES = 200_000

    /** Enough rows of a keyword pack to judge it; nobody scrolls a fourth. */
    private const val MAX_KEYWORD_SAMPLES = 24

    private const val MAX_STICKER_IMAGES = 24

    /** Enough variants to hear the variation; nobody taps a ninth. */
    private const val MAX_PACK_VARIANTS = 8

    /** Enough words to judge a pack's level; the pack screen lists the rest. */
    private const val MAX_VOCAB_SAMPLES = 12

    /** Per-image ceiling while unpacking, so a hostile archive can't fill the cache. */
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024

    fun read(entry: AddonEntry, payload: File): AddonPreviewContent = when (entry.type) {
        AddonType.Snippets, AddonType.Espanso -> readSnippets(entry, payload)
        AddonType.Dictionary -> readDictionary(entry, payload)
        AddonType.EmojiKeywords -> readEmojiKeywords(entry, payload)
        AddonType.Sound -> AddonPreviewContent.Sound(payload)
        AddonType.SoundPack -> readSoundPack(payload)
        AddonType.Stickers -> readStickers(payload)
        AddonType.Plugin -> readPlugin(payload)
        AddonType.Vocabulary -> readVocabulary(payload)
        // Screenshots already answer the question these raise, and [Unknown] is
        // a type this build cannot read by definition. Listed rather than left
        // to an `else` so a new addon type has to come here and pick a side.
        AddonType.Theme,
        AddonType.Layout,
        AddonType.IconPack,
        AddonType.Font,
        AddonType.EmojiFont,
        AddonType.Unknown,
        -> AddonPreviewContent.Unreadable(
            AddonText.of(R.string.faddons_preview_error_no_preview),
        )
    }

    private fun readVocabulary(payload: File): AddonPreviewContent {
        val pack = runCatching { payload.inputStream().buffered().use { VocabPackFile.decode(it) } }.getOrNull()
            ?: return AddonPreviewContent.Unreadable(AddonText.of(R.string.faddons_preview_error_not_vocab_pack))
        return AddonPreviewContent.Vocabulary(
            name = pack.meta.name,
            samples = pack.words.take(MAX_VOCAB_SAMPLES).map { word ->
                AddonPreviewContent.Vocabulary.Sample(word.word, word.pos.firstOrNull().orEmpty(), word.definition)
            },
            total = pack.words.size,
        )
    }

    private fun readPlugin(payload: File): AddonPreviewContent {
        val read = runCatching { payload.inputStream().use { PluginFile.readManifest(it) } }
            .getOrNull()
        return when (read) {
            is PluginManifestResult.Ok -> AddonPreviewContent.Plugin(
                name = read.manifest.name,
                version = read.manifest.pluginVersion,
                author = read.manifest.author,
                description = read.manifest.description,
                permissions = read.permissions,
            )

            is PluginManifestResult.Rejected ->
                AddonPreviewContent.Unreadable(read.reasonText.toAddonText())

            // Null is the read itself having thrown; both land on the same
            // "this isn't a plugin" line, since neither leaves anything to show.
            PluginManifestResult.NotAPlugin, null -> AddonPreviewContent.Unreadable(
                AddonText.of(R.string.faddons_error_not_a_plugin),
            )
        }
    }

    /**
     * Reads the head of a keyword pack.
     *
     * Streamed and stopped early like [readDictionary]: the count is capped at
     * [MAX_COUNTED_LINES] so a pack naming every emoji in every script is
     * still one bounded pass, and only [MAX_KEYWORD_SAMPLES] rows are kept.
     */
    private fun readEmojiKeywords(entry: AddonEntry, payload: File): AddonPreviewContent {
        val samples = ArrayList<AddonPreviewContent.EmojiKeywords.Sample>()
        var counted = 0
        val ok = runCatching {
            openMaybeGzipped(entry, payload).bufferedReader().use { reader ->
                for (line in reader.lineSequence()) {
                    // "# " opens a comment; the keycap hash emoji (#️⃣) starts
                    // a data row with a bare "#".
                    if (line.isBlank() || line.startsWith("# ")) continue
                    val parts = line.split('\t')
                    val emoji = parts[0].trim()
                    val keywords = parts.getOrNull(1)?.trim().orEmpty()
                    if (emoji.isEmpty() || keywords.isEmpty()) continue
                    counted++
                    if (samples.size < MAX_KEYWORD_SAMPLES) {
                        samples.add(
                            AddonPreviewContent.EmojiKeywords.Sample(emoji, keywords),
                        )
                    }
                    if (counted >= MAX_COUNTED_LINES) break
                }
            }
        }.isSuccess
        if (!ok || samples.isEmpty()) {
            return AddonPreviewContent.Unreadable(
                AddonText.of(R.string.faddons_preview_error_not_keyword_pack),
            )
        }
        return AddonPreviewContent.EmojiKeywords(
            samples = samples,
            total = counted,
            truncated = counted >= MAX_COUNTED_LINES,
        )
    }

    /**
     * The snippets in a pack, whichever of the two formats it arrived in.
     *
     * An Espanso pack carries a second thing worth previewing: what conversion
     * costs it. That has to be readable *before* the install button, not after,
     * so the notes ride along with the entries.
     */
    private fun readSnippets(entry: AddonEntry, payload: File): AddonPreviewContent {
        val wantEspanso = entry.type == AddonType.Espanso
        // The entry declared which format it is in, so a payload in the other
        // one is a mistake in the manifest rather than something to reinterpret.
        val parsed = SnippetPayload.read(payload, entry.name.ifBlank { entry.id })
            ?.takeIf { it.isEspanso == wantEspanso }
            ?: return AddonPreviewContent.Unreadable(
                AddonText.of(
                    if (wantEspanso) {
                        R.string.faddons_preview_error_not_espanso_pack
                    } else {
                        R.string.faddons_preview_error_not_snippet_pack
                    },
                ),
            )
        return AddonPreviewContent.Snippets(
            entries = parsed.snippets.take(MAX_SNIPPETS).map {
                // A pattern snippet has no trigger word, so the preview shows
                // the pattern in its place rather than an empty line.
                AddonPreviewContent.Snippets.Entry(
                    it.label,
                    it.text,
                    it.trigger ?: it.triggerPattern.orEmpty(),
                    it.confirm,
                )
            },
            total = parsed.snippets.size,
            notes = parsed.notes.map { it.toAddonText() },
        )
    }

    private fun readDictionary(entry: AddonEntry, payload: File): AddonPreviewContent {
        val words = ArrayList<String>()
        var counted = 0
        val ok = runCatching {
            openMaybeGzipped(entry, payload).bufferedReader().use { reader ->
                while (counted < MAX_COUNTED_LINES) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    // Same shape the importer reads: "word [frequency]", with
                    // '#' starting a comment.
                    if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                    counted++
                    if (words.size < MAX_WORDS) words += trimmed.substringBefore(' ')
                }
            }
        }.isSuccess
        if (!ok || counted == 0) {
            return AddonPreviewContent.Unreadable(
                AddonText.of(R.string.faddons_error_no_words),
            )
        }
        return AddonPreviewContent.Dictionary(
            words = words,
            total = counted,
            truncated = counted >= MAX_COUNTED_LINES,
        )
    }

    private fun openMaybeGzipped(entry: AddonEntry, payload: File) =
        payload.inputStream().buffered().let {
            if (entry.path.endsWith(".gz", ignoreCase = true)) GZIPInputStream(it) else it
        }

    /**
     * Unpacks the first few sticker images beside the archive.
     *
     * Entry names are never used as paths — the same rule `StickerPackFile`
     * import follows — so a `../` inside the archive writes nowhere but the
     * preview directory.
     */
    /**
     * Extracts a pack's default key-down and key-up variants beside the payload.
     *
     * Reads the manifest first and pulls only the files it names, in its order,
     * so what the preview plays is what the keyboard would play — not whatever
     * happened to be in the archive.
     */
    private fun readSoundPack(payload: File): AddonPreviewContent {
        val manifest = runCatching {
            payload.inputStream().use { SoundPackFile.readManifest(it) }
        }.getOrNull() ?: return AddonPreviewContent.Unreadable(
            AddonText.of(R.string.faddons_preview_error_sound_pack_unreadable),
        )

        val press = manifest.press.take(MAX_PACK_VARIANTS)
        val release = manifest.release.take(MAX_PACK_VARIANTS)
        val outDir = File(payload.parentFile, payload.nameWithoutExtension + "_pack")
        outDir.mkdirs()

        // Match on the bare file name: the manifest addresses samples by path
        // and the archive lists them by entry name, and neither is ever joined
        // onto a directory here — every target below is named by this function.
        //
        // A tail maps to a *list* of targets, not one: a pack whose key-up list
        // reuses a key-down recording names the same archive entry twice, and
        // the entry only comes past once in a stream read.
        val wanted = HashMap<String, MutableList<File>>()
        fun plan(paths: List<String>, prefix: String): Array<File?> {
            val targets = arrayOfNulls<File>(paths.size)
            paths.forEachIndexed { index, path ->
                val tail = path.substringAfterLast('/').substringAfterLast('\\')
                val target = File(outDir, "${prefix}_$index.snd")
                targets[index] = target
                wanted.getOrPut(tail) { mutableListOf() }.add(target)
            }
            return targets
        }

        val pressTargets = plan(press, "variant")
        val releaseTargets = plan(release, "release")
        val written = HashSet<File>()

        val ok = runCatching {
            ZipInputStream(payload.inputStream().buffered()).use { zip ->
                while (true) {
                    val zipEntry = zip.nextEntry ?: break
                    val targets = wanted[zipEntry.name.substringAfterLast('/')]
                    if (zipEntry.isDirectory || targets == null || targets.first() in written) {
                        zip.closeEntry()
                        continue
                    }
                    val first = targets.first()
                    if (copyBounded(zip, first)) {
                        written += first
                        // The rest are the same bytes under another name, so
                        // they are copied from what just landed rather than by
                        // rewinding the archive.
                        for (extra in targets.drop(1)) {
                            if (runCatching { first.copyTo(extra, overwrite = true) }.isSuccess) {
                                written += extra
                            }
                        }
                    } else {
                        first.delete()
                    }
                    zip.closeEntry()
                }
            }
        }.isSuccess

        val variants = pressTargets.filterNotNull().filter { it in written }
        if (!ok || variants.isEmpty()) {
            return AddonPreviewContent.Unreadable(
                AddonText.of(R.string.faddons_preview_error_sound_pack_unreadable),
            )
        }
        return AddonPreviewContent.SoundPack(
            name = manifest.name,
            variants = variants,
            totalVariants = manifest.press.size,
            releaseVariants = releaseTargets.filterNotNull().filter { it in written },
            totalReleaseVariants = manifest.release.size,
            roles = manifest.filledRoles().map { it.serialName },
        )
    }

    private fun readStickers(payload: File): AddonPreviewContent {
        val outDir = File(payload.parentFile, payload.nameWithoutExtension + "_stickers")
        outDir.mkdirs()
        val images = ArrayList<File>(MAX_STICKER_IMAGES)
        var total = 0
        val ok = runCatching {
            ZipInputStream(payload.inputStream().buffered()).use { zip ->
                while (true) {
                    val zipEntry = zip.nextEntry ?: break
                    val name = zipEntry.name
                    if (zipEntry.isDirectory || !looksLikeImage(name)) {
                        zip.closeEntry()
                        continue
                    }
                    total++
                    if (images.size < MAX_STICKER_IMAGES) {
                        val target = File(outDir, "sticker_${images.size}.${extensionOf(name)}")
                        if (copyBounded(zip, target)) images += target else target.delete()
                    }
                    zip.closeEntry()
                }
            }
        }.isSuccess
        if (!ok || images.isEmpty()) {
            return AddonPreviewContent.Unreadable(
                AddonText.of(R.string.faddons_preview_error_stickers_unreadable),
            )
        }
        return AddonPreviewContent.Stickers(images = images, total = total)
    }

    private fun copyBounded(input: java.io.InputStream, target: File): Boolean = runCatching {
        var written = 0L
        target.outputStream().buffered().use { sink ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                written += read
                if (written > MAX_IMAGE_BYTES) return false
                sink.write(buffer, 0, read)
            }
        }
        written > 0
    }.getOrDefault(false)

    private fun looksLikeImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif")
    }

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "png").lowercase()
}
