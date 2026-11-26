package com.wasimaster.wmkeyboard.core.ocr

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import java.io.File
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/**
 * PaddleOCR's PP-OCRv5 mobile models, the scanner's reader for Chinese and
 * Japanese wherever ML Kit's recognizers for them are missing (#757):
 * sideload builds, Play builds on phones without Play services, and F-Droid's
 * build, which has no ML Kit at all and reads Latin script with it too.
 *
 * One recognizer covers Simplified and Traditional Chinese, Japanese kana and
 * kanji, and English, so the whole set is a single pack, [PACK], in
 * [OcrPacks]: a text detector, a line recognizer and the recognizer's
 * character list. None of it is in the APK.
 *
 * The two graphs come in one format per [Runtime], and a build downloads only
 * its own:
 * - [Runtime.LITERT], wherever LiteRT is in the build: the conversions
 *   published with iFleey's PPOCRv5-Android (Apache-2.0, as are
 *   PaddlePaddle's weights), pinned to one commit.
 * - [Runtime.MNN], in F-Droid's build, which may carry nothing of Google's,
 *   and in any build whose Google libraries are switched off ([GoogleLibraries]):
 *   PaddlePaddle's own weights exported to MNN by the data repository's
 *   `scripts/export_ppocrv5_mnn.py`, with their float16 rounding matched to
 *   the LiteRT pair's, so both read the same text. They run on the MNN
 *   library the sticker generator brings (see [MnnPaddleRuntime]).
 *
 * Every file is checked against its SHA-256, because a graph that loads but
 * is not the expected one would read garbage rather than fail. Inputs are
 * fixed: a 640 pixel square for the detector and a 48 by 320 strip for the
 * recognizer, which [PaddleOcrPipeline] fits every photo and line to.
 */
object PaddleOcrModel {

    /** The pack name, in [OcrPacks] and [OcrLanguages] alongside Tesseract's. */
    const val PACK = "ppocrv5"

    /** The PPOCRv5-Android commit the LiteRT downloads are pinned to. */
    const val SOURCE_COMMIT = "dd9d9478a7c9d33bb3cee11c406a8d7316d787dd"

    /** The data repository's release the MNN pair is an asset of. */
    const val RELEASE_TAG = "models"

    /** What runs the graphs. */
    enum class Runtime { LITERT, MNN }

    /** This build's: LiteRT where the build has it and may run it, MNN otherwise. */
    val runtime: Runtime get() = if (BuildConfig.GOOGLE_SDKS && GoogleLibraries.usable) Runtime.LITERT else Runtime.MNN

    /**
     * Every file either runtime reads, under the names they are published
     * with, so a copy fetched by hand imports as it is (see [partOf]).
     * [runtime] is null for the character list, which both read.
     */
    enum class Part(val fileName: String, val bytes: Long, val sha256: String, val runtime: Runtime?) {
        DETECTOR("ocr_det_fp16.tflite", 2_444_316L, "f02bb7693e98dacd08075ac911dbdfdb0184b5f582cabb0c49ca41c5120fe189", Runtime.LITERT),
        RECOGNIZER("ocr_rec_fp16.tflite", 8_324_208L, "559b9f822a19887bbf18a9607619406710c0aeec90577117fb620d5cf6e0a55d", Runtime.LITERT),
        MNN_DETECTOR("ppocrv5-det.mnn", 4_736_960L, "284230c74bc0e395ae1f164d4ede101888f62f3507e3d5dac4125af8ac4a9a43", Runtime.MNN),
        MNN_RECOGNIZER("ppocrv5-rec.mnn", 16_503_532L, "9662f1605cd4e3848b7bf6f67c73315dc32349864d653784349aedb575abc330", Runtime.MNN),
        CHARACTERS("keys_v5.txt", 74_012L, "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b", null),
    }

    /** The three files [runtime] reads. */
    fun parts(runtime: Runtime = this.runtime): List<Part> =
        Part.entries.filter { it.runtime == null || it.runtime == runtime }

    fun detector(runtime: Runtime = this.runtime): Part =
        if (runtime == Runtime.MNN) Part.MNN_DETECTOR else Part.DETECTOR

    fun recognizer(runtime: Runtime = this.runtime): Part =
        if (runtime == Runtime.MNN) Part.MNN_RECOGNIZER else Part.RECOGNIZER

    /** This build's three files together, for progress and the storage check. */
    val totalBytes: Long get() = parts().sumOf { it.bytes }

    /**
     * Where [part] downloads from: the LiteRT pair and the character list
     * from PPOCRv5-Android's tree, the MNN pair from the data repository's
     * [RELEASE_TAG] release as `<sha256>-<name>`. Null for the MNN pair when
     * the data repository is not on GitHub.
     */
    fun url(part: Part): String? {
        if (part.runtime != Runtime.MNN) {
            return "https://raw.githubusercontent.com/iFleey/PPOCRv5-Android/$SOURCE_COMMIT/app/src/main/assets/models/${part.fileName}"
        }
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/download/$RELEASE_TAG/${part.sha256}-${part.fileName}"
    }

    fun dir(filesDir: File): File = File(filesDir, "ocr/paddle")

    fun file(filesDir: File, part: Part): File = File(dir(filesDir), part.fileName)

    /** Whole only with this build's three files: each lands complete or not at all. */
    fun isDownloaded(filesDir: File): Boolean = parts().all { file(filesDir, it).isFile }

    /** This build's part a file named [name] is, or null for any other file. */
    fun partOf(name: String): Part? = parts().firstOrNull { it.fileName == name }
}
