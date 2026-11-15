package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.helpvisuals.HelpTheme
import com.wasimaster.wmkeyboard.core.helpvisuals.HelpVisual
import com.wasimaster.wmkeyboard.core.helpvisuals.HelpVisualIndex
import com.wasimaster.wmkeyboard.core.helpvisuals.HelpVisualStore
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The help pictures as the settings app sees them: which setting has one, and
 * what is being fetched right now.
 *
 * A picture, a short clip or an off/on comparison sits above the words in a
 * setting's "?" dialog (see `HelpVisualSlot`). Which settings have one comes
 * from the index in the APK; the files themselves are downloaded, as one pack
 * per look or one at a time, whichever the user picked the first time they
 * met one ([com.wasimaster.wmkeyboard.core.settings.HelpPictures]).
 *
 * Downloads run here rather than in the dialog that asked for them, so closing
 * the dialog does not stop a pack halfway, and the next dialog opened sees the
 * same progress. Everything a screen reads is snapshot state, written on the
 * main thread.
 */
internal object HelpVisuals {

    /** The index once it is read, keyed by the title resource each visual belongs to. */
    class Loaded(
        val index: HelpVisualIndex,
        val byTitle: Map<Int, HelpVisual>,
        val store: HelpVisualStore,
        private val labels: Map<String, Int>,
    ) {
        /** The string resource a frame's label names, or 0. */
        @StringRes
        fun label(name: String?): Int = name?.let { labels[it] } ?: 0
    }

    /** What a pack or a single picture is doing. */
    sealed interface Fetch {
        /** [progress] is 0 to 1, or null where there is nothing to measure. */
        class Running(val progress: Float?) : Fetch

        class Failed(@StringRes val message: Int) : Fetch
    }

    /** Null until [warm] has read the index. */
    var loaded by mutableStateOf<Loaded?>(null)
        private set

    /** Each look's pack while it downloads, or after it failed. Absent otherwise. */
    val packs = mutableStateMapOf<HelpTheme, Fetch>()

    /** Each visual being fetched alone, by [fetchKey]. Absent once it is here. */
    val singles = mutableStateMapOf<String, Fetch>()

    /**
     * Goes up whenever a file arrives or the store is emptied, so a dialog that
     * read "not here yet" looks again.
     */
    var generation by mutableIntStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var warming = false

    private const val ASSET = "help/visuals.tsv"

    fun fetchKey(visual: HelpVisual, theme: HelpTheme): String = "${visual.key}/${theme.id}"

    /** Reads the index off the main thread, once. Safe to call from every screen. */
    fun warm(context: Context) {
        if (loaded != null || warming) return
        warming = true
        val app = context.applicationContext
        scope.launch { ready(app) }
    }

    /** The index, read now if [warm] has not finished: for the import screen, which has no settings graph. */
    suspend fun ready(context: Context): Loaded {
        loaded?.let { return it }
        val app = context.applicationContext
        val read = withContext(Dispatchers.IO) { read(app) }
        return withContext(Dispatchers.Main) { loaded ?: read.also { loaded = it } }
    }

    private fun read(app: Context): Loaded {
        val index = runCatching {
            app.assets.open(ASSET).use { HelpVisualIndex.parse(it.readBytes().decodeToString()) }
        }.getOrDefault(HelpVisualIndex.EMPTY)
        val resources = app.resources
        val pkg = app.packageName

        @Suppress("DiscouragedApi")
        fun id(name: String): Int = resources.getIdentifier(name, "string", pkg)
        val byTitle = HashMap<Int, HelpVisual>()
        val labels = HashMap<String, Int>()
        for ((key, visual) in index.visuals) {
            val title = id(key)
            // A picture for a row this version no longer has is simply never asked for.
            if (title != 0) byTitle[title] = visual
            for (frame in visual.frames) {
                // "=40 dp" is the words themselves, not a resource's name.
                val label = frame.label?.takeUnless { it.startsWith("=") } ?: continue
                if (label !in labels) labels[label] = id(label)
            }
        }
        val store = HelpVisualStore(File(app.noBackupFilesDir, HelpVisualStore.DIR), index)
        return Loaded(index, byTitle, store, labels)
    }

    /** The picture for the row titled [title], or null. */
    fun of(@StringRes title: Int): HelpVisual? = if (title == 0) null else loaded?.byTitle?.get(title)

    /** Fetches [visual]'s files in [theme], one request each. */
    fun fetch(visual: HelpVisual, theme: HelpTheme) {
        val store = loaded?.store ?: return
        val key = fetchKey(visual, theme)
        if (singles[key] is Fetch.Running) return
        singles[key] = Fetch.Running(null)
        scope.launch {
            val failure = attempt { store.fetch(visual, theme) }
            withContext(Dispatchers.Main) {
                if (failure == null) singles.remove(key) else singles[key] = Fetch.Failed(failure)
                generation++
            }
        }
    }

    /** Downloads every picture of [theme] as one pack. */
    fun fetchPack(theme: HelpTheme) {
        val store = loaded?.store ?: return
        if (packs[theme] is Fetch.Running) return
        packs[theme] = Fetch.Running(0f)
        scope.launch {
            var shown = 0
            val failure = attempt {
                store.fetchPack(theme) { progress ->
                    // A few times a second is more than a progress bar can show.
                    val percent = (progress * PERCENT).toInt()
                    if (percent != shown) {
                        shown = percent
                        scope.launch(Dispatchers.Main) {
                            if (packs[theme] is Fetch.Running) packs[theme] = Fetch.Running(progress)
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (failure == null) packs.remove(theme) else packs[theme] = Fetch.Failed(failure)
                generation++
            }
        }
    }

    /**
     * Installs a pack the user picked as a file. Returns the pictures added, or
     * null when the file is not a help picture pack.
     */
    suspend fun import(context: Context, uri: Uri, name: String): Int? {
        val store = loaded?.store ?: return null
        val staged = File(context.cacheDir, "help-pack-import.zip")
        return try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { input.copyTo(it) }
                } ?: return@withContext null
                store.installPack(staged, name)
            }.also { withContext(Dispatchers.Main) { generation++ } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: java.io.IOException) {
            null
        } finally {
            staged.delete()
        }
    }

    /** [import] for a file already on disk under its published name (the import screen's route). */
    suspend fun import(context: Context, file: File): Int? =
        ready(context).store.installPack(file).also { withContext(Dispatchers.Main) { generation++ } }

    /** Deletes every downloaded picture. */
    suspend fun clear(context: Context) {
        val store = ready(context).store
        withContext(Dispatchers.IO) { store.clear() }
        withContext(Dispatchers.Main) {
            packs.clear()
            singles.clear()
            generation++
        }
    }

    /** Has every open dialog read the store again, after a change no download made. */
    fun refresh() {
        generation++
    }

    /** Where [theme]'s pack is published, for fetching it on another device. */
    fun packAddress(theme: HelpTheme): String? =
        loaded?.index?.pack(theme)?.let { ServiceEndpoints.repo(ServiceRepo.DATA).rawUrl(it.repoPath) }

    /** Runs [block]; null when it worked, otherwise the sentence that says why not. */
    @StringRes
    private suspend fun attempt(block: suspend () -> Unit): Int? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: DownloadException.Blocked) {
        R.string.help_pictures_blocked
    } catch (_: java.io.IOException) {
        OfflineBuild.download(R.string.help_pictures_failed)
    } catch (_: SecurityException) {
        // What a socket throws on a build with no internet permission.
        OfflineBuild.download(R.string.help_pictures_failed)
    }

    private const val PERCENT = 100
}
