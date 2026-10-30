package com.wasimaster.wmkeyboard.core.input.composer

import android.os.Build
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NotInBuild
import kr.pe.bab2min.Kiwi
import kr.pe.bab2min.KiwiBuilder
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Corrects the word spacing of Korean text with Kiwi, a morphological analyser
 * (bab2min/Kiwi, Apache-2.0): 나는학교에간다 becomes 나는 학교에 간다.
 *
 * Three things have to be on the phone: the `kiwi` gate's library, about 18 MB, built
 * upstream for arm64 and x86-64 only, and the [PACK_ID] pack, an 88 MB zip of the
 * model that takes 104 MB unpacked. Opened on a Mac it took 3.3 seconds to load and
 * 450 MB of memory, so it is never held between uses: [release] lets it go, and the
 * caller is expected to call that soon after the last [fix].
 *
 * The library binds its JNI to the `kr.pe.bab2min` classes, so those two files are
 * upstream's as they stand except for dropping their static load: a library that
 * came through [FeatureModules] has to be loaded by it.
 */
object KoreanSpacing {

    const val PACK_ID = "kiwi_model"

    private const val LIBRARY = "KiwiJava"
    private const val MODEL_DIR = "model"
    private const val DONE_MARKER = ".unpacked"
    private const val WORKERS = 1
    private const val MAX_ENTRY_BYTES = 200L * 1024 * 1024

    private var kiwi: Kiwi? = null
    private var libraryLoaded = false

    /** This build has the library at all, and for this phone's processor. */
    val supported: Boolean
        get() = FeatureModules.kiwi !== NotInBuild && Build.SUPPORTED_ABIS.orEmpty().any { it == "arm64-v8a" || it == "x86_64" }

    /** Everything is on the phone, so [fix] can answer. A few file checks at most. */
    fun ready(filesDir: File): Boolean = supported && FeatureModules.kiwi.installed && modelPack(filesDir) != null

    private fun modelPack(filesDir: File): File? = CjkDictStore.downloadedFileFor(filesDir, PACK_ID)

    /** [text] with its spacing corrected, or null when Kiwi could not run. Slow the first time: never on the main thread. */
    @Synchronized
    fun fix(filesDir: File, text: String): String? {
        if (!ready(filesDir)) return null
        return runCatching { (kiwi ?: open(filesDir).also { kiwi = it }).space(text) }.getOrNull()
    }

    /** Frees the model. The next [fix] opens it again. */
    @Synchronized
    fun release() {
        val open = kiwi ?: return
        kiwi = null
        runCatching { open.close() }
    }

    private fun open(filesDir: File): Kiwi {
        if (!libraryLoaded) {
            FeatureModules.loadLibrary(LIBRARY)
            libraryLoaded = true
        }
        val dir = unpacked(filesDir)
        return KiwiBuilder(dir.path, WORKERS, KiwiBuilder.BuildOption.default_, KiwiBuilder.ModelType.cong).use { it.build() }
    }

    /** The pack is one zip because a pack is one file; Kiwi wants a directory of them. */
    @Throws(IOException::class)
    private fun unpacked(filesDir: File): File {
        val pack = requireNotNull(modelPack(filesDir)) { "No model" }
        val dir = File(pack.parentFile, MODEL_DIR)
        val marker = File(dir, DONE_MARKER)
        if (marker.isFile) return dir
        dir.deleteRecursively()
        dir.mkdirs()
        ZipInputStream(pack.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // The pack is pinned by checksum, but a path out of the folder is never right.
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name.isEmpty() || name == "..") continue
                File(dir, name).outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ENTRY_BYTES) throw IOException("$name is too big")
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
        marker.createNewFile()
        return dir
    }
}
