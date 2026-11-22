package com.wasimaster.wmkeyboard.core.input.chewing

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictPack
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore
import com.wasimaster.wmkeyboard.input.R
import java.io.File

/**
 * libchewing's system dictionary: libchewing-data's characters and phrases
 * in libchewing's trie format, one file (native/chewing-jni/build.sh --data),
 * published in the data repository. Kept out of the library so the library
 * stays small and the dictionary downloads only for whoever picks Chewing.
 *
 * An ordinary [CjkDictPack], so it goes through the CJK pack path
 * unchanged: `CjkDictDownloadManager` fetches it resumably, checks the
 * pinned SHA-256 and moves it into `cjk_dicts/chewing/`, and the offline
 * import installs the same file by its published name. The built-in Zhuyin
 * composer never reads it.
 *
 * The format is the `chewing` crate's, the one libwmchewing is built with.
 * A new libchewing-data release, or a crate whose format moved, publishes a
 * new file under a new name; the old name moves to `legacyFileNames` only
 * when the old file still opens.
 */
object ChewingData {

    /** The libchewing-data release the dictionary is compiled from. */
    const val DATA_VERSION = "2026.3.22"

    val pack: CjkDictPack = CjkDictPack(
        id = "chewing",
        langId = "zh",
        displayNameRes = R.string.core_input_chewing_pack_title,
        descriptionRes = R.string.core_input_chewing_pack_subtitle,
        path = "cjk/chewing/chewing-$DATA_VERSION.dat",
        sizeBytes = 4_507_083L,
        sha256 = "cd00aea1eadaaeab55ea8a080e0c3c4ea3b7f3f10733feb351b6eb15103ebf20",
        fileName = "chewing-$DATA_VERSION.dat",
    )

    val packs: List<CjkDictPack> = listOf(pack)

    /** The downloaded dictionary, or null while there is none. */
    fun file(filesDir: File): File? = CjkDictStore.packFile(filesDir, pack).takeIf(File::isFile)
}
