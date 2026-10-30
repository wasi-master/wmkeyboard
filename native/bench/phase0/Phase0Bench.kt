// Phase 0 of native/RUST_NEON_PLAN.md: what every NEON candidate costs in
// Kotlin today. Not part of any Gradle build and never shipped: run.sh
// compiles it against a checkout's built module jars, runs it on the host
// JVM, and dexes the same classes for ART on a phone (app_process), so both
// columns of the plan's table time identical code.
//
// Where the production loop sits behind android.graphics or a private
// function (Bitmap scaling, AudioRecord capture, PhotoEditRenderer's
// getPixels bands, ImageGenEngine.toBitmap), the loop body is copied here
// verbatim on plain arrays and says so. Everything else calls the real class.
@file:Suppress("ForbiddenMethodCall", "MagicNumber")

package com.wasimaster.wmkeyboard.bench

import com.wasimaster.wmkeyboard.core.engine.NativeEngine
import com.wasimaster.wmkeyboard.core.gesture.CtcGlideBeam
import com.wasimaster.wmkeyboard.core.gesture.DuetBeam
import com.wasimaster.wmkeyboard.core.gesture.DuetWorkspace
import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideToken
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.gesture.SwipeEmissions
import com.wasimaster.wmkeyboard.core.input.composer.CjkContext
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.CjkLearning
import com.wasimaster.wmkeyboard.core.input.composer.CjkNgrams
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.input.composer.HanLevels
import com.wasimaster.wmkeyboard.core.input.composer.JapaneseComposer
import com.wasimaster.wmkeyboard.core.input.composer.Jianpin
import com.wasimaster.wmkeyboard.core.input.composer.PinyinComposer
import com.wasimaster.wmkeyboard.core.input.composer.PinyinSyllables
import com.wasimaster.wmkeyboard.core.imagegen.EulerSampler
import com.wasimaster.wmkeyboard.core.imagegen.LcmSampler
import com.wasimaster.wmkeyboard.core.imagegen.NoiseSchedule
import com.wasimaster.wmkeyboard.core.media.GifEncoder
import com.wasimaster.wmkeyboard.core.media.reference.KotlinGifEncoder
import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrPipeline
import com.wasimaster.wmkeyboard.core.ocr.PaddleRuntime
import com.wasimaster.wmkeyboard.core.prediction.BeamWorkspace
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.KeyProximity
import com.wasimaster.wmkeyboard.core.prediction.PackedTrie
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.semantic.MeaningRank
import com.wasimaster.wmkeyboard.core.semantic.VectorIndex
import com.wasimaster.wmkeyboard.core.stickers.CutoutMask
import com.wasimaster.wmkeyboard.core.theme.FrostBake
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.EngineNative
import com.wasimaster.wmkeyboard.core.transliteration.PackedPhoneticTable
import com.wasimaster.wmkeyboard.core.prediction.PhoneticSchemes
import com.wasimaster.wmkeyboard.core.upscale.UpscaleModels
import com.wasimaster.wmkeyboard.core.upscale.UpscaleTiles
import com.wasimaster.wmkeyboard.core.voice.WavEncoder
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperMel
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Keeps results alive so no loop is optimised away. */
@Volatile var sink: Any? = null
@Volatile var sinkF = 0f

object Phase0Bench {

    private var budgetScale = 1.0
    private val rows = ArrayList<String>()

    /**
     * Runs [op] until it is warm (at least [minWarm] calls and a second of
     * wall time, so ART's JIT has compiled it), then times up to [maxCalls]
     * calls or three seconds, and prints median and p95 in ms.
     */
    private fun bench(name: String, often: String, minWarm: Int = 20, maxCalls: Int = 400, op: () -> Any?) {
        val warmUntil = System.nanoTime() + (1e9 * budgetScale).toLong()
        var w = 0
        while (w < minWarm || System.nanoTime() < warmUntil) { sink = op(); w++ }
        val times = LongArray(maxCalls)
        var n = 0
        val stopAt = System.nanoTime() + (3e9 * budgetScale).toLong()
        while (n < maxCalls && (n < 15 || System.nanoTime() < stopAt)) {
            val t0 = System.nanoTime()
            sink = op()
            times[n++] = System.nanoTime() - t0
        }
        val sorted = times.copyOf(n).also { it.sort() }
        val med = sorted[n / 2] / 1e6
        val p95 = sorted[min(n - 1, (n * 95) / 100)] / 1e6
        val row = String.format(Locale.ROOT, "%-46s median %9.3f ms  p95 %9.3f ms  n=%-4d  %s", name, med, p95, n, often)
        println(row)
        rows += row
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dict = args.firstOrNull { it.endsWith(".txt") } ?: "app/dictionaries-src/en.txt"
        budgetScale = args.firstOrNull { it.startsWith("budget=") }?.substringAfter('=')?.toDouble() ?: 1.0
        val only = args.firstOrNull { it.startsWith("only=") }?.substringAfter('=')?.split(',')?.toSet()
        println("runtime: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}, cores ${Runtime.getRuntime().availableProcessors()}")
        fun on(group: String) = only == null || group in only
        if (on("vector")) vectors()
        if (on("gif")) gif()
        if (on("ocr")) ocr()
        if (on("image")) images()
        if (on("glide") || on("duet")) glide(File(dict), duetOnly = !on("glide"))
        if (on("audio")) audio()
        if (on("frost")) frost()
        if (on("renderfx")) renderEffect()
        if (on("camera")) camera()
        if (on("webp")) webpFrames()
        if (on("upscale")) upscale()
        if (on("webm")) webm()
        if (on("fuzzy")) fuzzy(File(dict))
        // Only with data=<dir>: the packs are not in this repository.
        val data = args.firstOrNull { it.startsWith("data=") }?.substringAfter('=')?.let(::File)
        if (data != null && on("cjk")) cjk(data)
        if (data != null && on("phonetic")) phonetic(data, File(dict))
        println("done")
    }

    // ---- Round 3: conversion IMEs and phonetic lookup, still Kotlin on the phone --

    /** Runs [op] once in this process, the way a pack loads once, and reports what it keeps. */
    private fun <T> firstLoad(name: String, op: () -> T): T {
        val before = usedHeap()
        val t0 = System.nanoTime()
        val value = op()
        val ms = (System.nanoTime() - t0) / 1e6
        sink = value
        println(String.format(Locale.ROOT, "%-46s first load %9.1f ms, retained %.1f MB", name, ms, (usedHeap() - before) / 1e6))
        return value
    }

    /** A buffer as it is typed: every prefix, shortest first. */
    private fun prefixes(words: List<String>): List<String> = words.flatMap { w -> (1..w.length).map { w.substring(0, it) } }

    /** Median and p95 for [buffers], and for the buffer lengths the lattice cost grows with. */
    private fun perLength(name: String, buffers: List<String>, op: (String) -> Any?) {
        var i = 0
        bench("$name, every prefix", "per keystroke") { op(buffers[i++ % buffers.size]) }
        for ((label, range) in listOf("1-8" to 1..8, "9-16" to 9..16, "17+" to 17..Int.MAX_VALUE)) {
            val some = buffers.filter { it.length in range }
            if (some.isEmpty()) continue
            var k = 0
            bench("  $name, $label letters", "per keystroke") { op(some[k++ % some.size]) }
        }
    }

    private fun dump(file: File, buffers: List<String>, answer: (String) -> List<String>) {
        file.bufferedWriter().use { out ->
            for (b in buffers) out.write(b + "\t" + answer(b).joinToString("|") + "\n")
        }
    }

    /**
     * What the phone's Chinese and Japanese composers cost. native/wm-engine's
     * src/cjk holds a port nothing on the phone calls yet;
     * examples/cjk_phonetic_bench.rs times it on the same files and buffers.
     */
    private fun cjk(dir: File) {
        PinyinSyllables.valid = PinyinSyllables.parse(File(dir, "pinyin_syllables.txt").readText().lineSequence())
        Jianpin.index = Jianpin.build(PinyinSyllables.valid)
        HanLevels.table = HanLevels.parse(File(dir, "han_levels.txt").readText().lineSequence())
        CjkLearning.store = null
        CjkContext.before = ""
        fun table(name: String) = File(dir, name).bufferedReader().useLines(ConversionDictionary::parse)
        fun pairs(name: String) = File(dir, name).bufferedReader().useLines(CjkNgrams::parse)

        CjkDictionaries.pinyin = firstLoad("ConversionDictionary pinyin_v2.tsv") { table("pinyin_v2.tsv") }
        CjkDictionaries.pinyinNgrams = firstLoad("CjkNgrams pinyin_ngrams.tsv") { pairs("pinyin_ngrams.tsv") }
        CjkDictionaries.japanese = firstLoad("ConversionDictionary ja_kana.tsv") { table("ja_kana.tsv") }
        bench("ConversionDictionary pinyin_v2.tsv, warm", "per pack load", minWarm = 1, maxCalls = 5) { table("pinyin_v2.tsv") }
        bench("CjkNgrams pinyin_ngrams.tsv, warm", "per pack load", minWarm = 1, maxCalls = 5) { pairs("pinyin_ngrams.tsv") }
        bench("ConversionDictionary ja_kana.tsv, warm", "per pack load", minWarm = 1, maxCalls = 3) { table("ja_kana.tsv") }

        val clauses = File(dir, "pinyin_eval.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore('\t') }
            .filterIndexed { i, _ -> i % 5 == 0 }
            .take(200)
        val pinyin = prefixes(clauses)
        val kana = prefixes(JAPANESE_ROMAJI)
        println("cjk: ${clauses.size} clauses, ${pinyin.size} pinyin buffers, ${kana.size} romaji buffers")
        perLength("PinyinComposer.candidates", pinyin) { CjkDictionaries.invalidate(); PinyinComposer.candidates(it) }
        perLength("JapaneseComposer.candidates", kana) { CjkDictionaries.invalidate(); JapaneseComposer.candidates(it) }
        if (System.getProperty("java.vm.name") != "Dalvik") {
            dump(File(dir, "kotlin_pinyin.txt"), pinyin) { CjkDictionaries.invalidate(); PinyinComposer.candidates(it) }
            dump(File(dir, "kotlin_japanese.txt"), kana) { CjkDictionaries.invalidate(); JapaneseComposer.candidates(it) }
        }
    }

    /**
     * What Banglish costs per keystroke: the table the phone maps, the lookups
     * on it, and the engine's whole strip and space-bar reading around them.
     * The inputs are the list's own words romanized, written to the data
     * directory so the Rust side reads the same ones.
     */
    private fun phonetic(dir: File, english: File) {
        val listed = File(dir, "bn_full_v2.txt").inputStream().use { DictionaryLoader.loadEntries(it) }.map { it.first }
        val words = listed.take(5000).filterIndexed { i, _ -> i % 16 == 0 }
        val typed = words.map { EngineNative.bengaliRomanizeWord(it) }.filter { it.length >= 2 }
        File(dir, "bn_inputs.txt").writeText(typed.joinToString("\n") + "\n")
        val inputs = prefixes(typed)
        val native = prefixes(words.take(100))
        println("phonetic: ${listed.size} listed words, ${typed.size} romanized, ${inputs.size} buffers")

        val table = firstLoad("PackedPhoneticTable.open bn_phonetic.wmpi") {
            checkNotNull(PackedPhoneticTable.open(File(dir, "bn_phonetic.wmpi"))) { "table refused" }
        }
        val index = BengaliPhoneticIndex(table)
        perLength("BengaliPhoneticIndex.lookup", inputs) { index.lookup(it) }
        perLength("BengaliPhoneticIndex.lookupLoose", inputs) { index.lookupLoose(it) }
        var n = 0
        bench("BengaliPhoneticIndex.completions, 10", "per keystroke, Khipro") { index.completions(native[n++ % native.size], 10) }

        val entries = english.inputStream().use { DictionaryLoader.loadEntries(it) }
        val engine = SuggestionEngine(PackedTrie.of(entries), index, UserLexicon(null))
        val bn = PhoneticSchemes.BENGALI.languageId
        perLength("SuggestionEngine.phoneticCandidates, 12", inputs) { engine.phoneticCandidates(bn, it, 12) }
        perLength("SuggestionEngine.phoneticCommit", inputs) { engine.phoneticCommit(bn, it, previousWord = "আমি") }

        if (System.getProperty("java.vm.name") != "Dalvik") {
            val built = BengaliPhoneticIndex(File(dir, "bn_full_v2.txt").inputStream().use { DictionaryLoader.loadEntries(it) })
            dump(File(dir, "kotlin_bn.txt"), inputs) { built.lookup(it) }
        }
    }

    /** Everyday Japanese typed in romaji; no held-out set exists for it. */
    private val JAPANESE_ROMAJI = listOf(
        "watashihagakuseidesu", "kyouhaiitenkidesune", "ashitanokaigiwananjikaradesuka", "arigatougozaimasu",
        "denshawookuremashita", "tomodachitoeigawomimashita", "konbanhananiwotabemasuka", "nihongonobenkyouhatanoshii",
        "sumimasenmichiwooshietekudasai", "shigotogaowattararenrakushimasu", "kaisyanomaedematteimasu", "ameganottekitanode",
        "kodomonokoronoomoide", "sensyuunodoyoubiniikimashita", "ohayougozaimasu", "yoroshikuonegaishimasu",
    )

    // ---- Embedding dot products ------------------------------------------------

    private fun unitVector(r: Random, d: Int): FloatArray {
        val v = FloatArray(d) { r.nextFloat() * 2f - 1f }
        var s = 0f
        for (x in v) s += x * x
        val inv = 1f / sqrt(s)
        for (i in v.indices) v[i] *= inv
        return v
    }

    private fun vectors() {
        val d = 768
        val r = Random(1)
        for (count in intArrayOf(1_000, 5_000, 20_000)) {
            val raw = Array(count) { unitVector(r, d) }
            val heap = usedHeap()
            val index = VectorIndex(d)
            raw.forEachIndexed { i, v -> index.put("item/$i", 0L, v) }
            val built = usedHeap() - heap
            val file = File.createTempFile("bench", ".vec").apply { deleteOnExit() }
            index.write(file)
            val before = usedHeap()
            val read = VectorIndex.read(file, d)
            val loaded = usedHeap() - before
            sink = read
            println(String.format(Locale.ROOT, "VectorIndex ${count}x$d heap %.1f MB read from its file, %.1f MB built by put (growth slack); file %.1f MB", loaded / 1e6, built / 1e6, file.length() / 1e6))
            val q = unitVector(r, d)
            for (native in listOf(false, true)) {
                if (native && !NativeEngine.loaded) continue
                NativeEngine.forceKotlin = !native
                val how = if (native) "native" else "kotlin"
                bench("VectorIndex.score ${count}x$d $how", "every hit, sorted") { index.score(q) }
                bench("VectorIndex.search ${count}x$d $how", "per search (300 ms debounce)") { index.search(q, MeaningRank.TEXT) }
            }
            NativeEngine.forceKotlin = false
            // The same dot products with no Hit objects and no sort, on the
            // f32 vectors: the part the kernel replaces.
            val out = FloatArray(count)
            bench("  dots only ${count}x$d kotlin f32", "share of the above") {
                for (i in 0 until count) {
                    val v = raw[i]
                    var dot = 0f
                    for (k in 0 until d) dot += v[k] * q[k]
                    out[i] = dot
                }
                out
            }
        }
    }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(50) }
        return rt.totalMemory() - rt.freeMemory()
    }

    // ---- GIF encoding -------------------------------------------------------------

    private object Discard : OutputStream() {
        var bytes = 0L
        override fun write(b: Int) { bytes++ }
        override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
    }

    /**
     * A sticker-like frame: a soft-edged blob on transparent, shaded and
     * textured so its colours fill most of the palette, moving with [phase].
     */
    private fun stickerFrame(side: Int, phase: Int): IntArray {
        val px = IntArray(side * side)
        val c = side / 2f
        val radius = side * 0.42f
        val shift = phase * 0.21f
        for (y in 0 until side) for (x in 0 until side) {
            val dx = x - c - 20f * sin(shift)
            val dy = y - c - 20f * cos(shift)
            val d = sqrt(dx * dx + dy * dy)
            val a = ((radius - d) * 0.25f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            val rr = (128 + 100 * sin(x * 0.031f + shift)).toInt().coerceIn(0, 255)
            val gg = (128 + 100 * sin(y * 0.027f - shift)).toInt().coerceIn(0, 255)
            val bb = (128 + 90 * sin((x + y) * 0.019f + 2 * shift)).toInt().coerceIn(0, 255)
            px[y * side + x] = ((a * 255).toInt() shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        return px
    }

    private fun gif() {
        val side = 512
        val frames = Array(30) { stickerFrame(side, it) }
        // The Rust encoder behind GifEncoder against the Kotlin one it
        // replaced (the test sources' reference): the same bytes first.
        val kotlin = java.io.ByteArrayOutputStream()
        val native = java.io.ByteArrayOutputStream()
        KotlinGifEncoder(kotlin, side, side).apply { frames.forEach { addFrame(it, 40) } }.finish()
        GifEncoder(native, side, side).apply { frames.forEach { addFrame(it, 40) } }.finish()
        check(kotlin.toByteArray().contentEquals(native.toByteArray())) { "native GIF differs from Kotlin's" }
        println("  native GIF: byte for byte Kotlin's, ${native.size()} bytes for 30 frames")
        var f = 0
        val oracle = KotlinGifEncoder(Discard, side, side)
        bench("GifEncoder.addFrame 512x512 kotlin", "per frame; x30-60 per animated sticker sent as GIF") {
            oracle.addFrame(frames[f++ % frames.size], 40)
        }
        bench("GifEncoder whole GIF, 30 frames 512x512 kotlin", "per animated sticker into a GIF-only field", minWarm = 3, maxCalls = 30) {
            val e = KotlinGifEncoder(Discard, side, side)
            for (fr in frames) e.addFrame(fr, 40)
            e.finish()
        }
        for (scalar in listOf(true, false)) {
            NativeEngine.forceKotlin = scalar
            val how = if (scalar) "native scalar" else "native"
            val encoder = GifEncoder(Discard, side, side)
            bench("GifEncoder.addFrame 512x512 $how", "per frame") {
                encoder.addFrame(frames[f++ % frames.size], 40)
            }
            bench("GifEncoder whole GIF, 30 frames 512x512 $how", "per animated sticker", minWarm = 3, maxCalls = 30) {
                val e = GifEncoder(Discard, side, side)
                for (fr in frames) e.addFrame(fr, 40)
                e.finish()
            }
        }
        NativeEngine.forceKotlin = false
    }

    // ---- OCR pre/post-processing ---------------------------------------------

    /** PP-OCRv5 mobile's recognizer shape: 40 steps of 18,385 classes. */
    private class FakePaddle(private val lines: Int) : PaddleRuntime {
        override val recognizerSteps = 40
        override val recognizerClasses = 18_385
        private val logits = FloatArray(recognizerSteps * recognizerClasses).also { out ->
            val r = Random(3)
            for (i in out.indices) out[i] = r.nextFloat() * 0.01f
            for (t in 0 until recognizerSteps) out[t * recognizerClasses + if (t % 3 == 0) 0 else 1 + r.nextInt(5000)] = 0.9f
        }

        override fun open(detector: File, recognizer: File, threads: Int) = Unit
        override fun detect(input: FloatArray, output: FloatArray) = textMap(output, lines)
        override fun recognize(input: FloatArray, output: FloatArray) { System.arraycopy(logits, 0, output, 0, output.size) }
        override fun close() = Unit
    }

    /** A detector map with [lines] printed lines of text across a page. */
    private fun textMap(prob: FloatArray, lines: Int) {
        val side = PaddleOcrPipeline.DET_SIDE
        prob.fill(0f)
        val pitch = side / (lines + 1)
        for (l in 0 until lines) {
            val top = pitch * (l + 1) - 6
            val left = 40 + (l * 37) % 80
            val right = side - 40 - (l * 53) % 160
            for (y in top until top + 12) for (x in left until right) prob[y * side + x] = 0.95f
        }
    }

    private fun photo(w: Int, h: Int): IntArray {
        val r = Random(5)
        return IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(0x1000000) }
    }

    private fun ocr() {
        val w = 4000
        val h = 3000
        val pixels = photo(w, h)
        val charset = List(18_383) { "c$it" }
        val detIn = FloatArray(PaddleOcrPipeline.DET_SIDE * PaddleOcrPipeline.DET_SIDE * 3)
        bench("OCR detectorInput 12MP -> 640^2", "per OCR read (once per photo)", minWarm = 3, maxCalls = 30) {
            PaddleOcrPipeline.detectorInput(pixels, w, h, detIn)
        }
        val letterbox = PaddleOcrPipeline.detectorInput(pixels, w, h, detIn)
        val prob = FloatArray(PaddleOcrPipeline.DET_SIDE * PaddleOcrPipeline.DET_SIDE)
        textMap(prob, 25)
        bench("OCR boxes (640^2 map, 25 lines)", "per OCR read") { PaddleOcrPipeline.boxes(prob, letterbox) }
        val boxes = PaddleOcrPipeline.boxes(prob, letterbox)
        val box = boxes.first()
        bench("OCR strip (one line)", "per text line (x25 per page)") { PaddleOcrPipeline.strip(pixels, w, h, box) }
        val strip = PaddleOcrPipeline.strip(pixels, w, h, box)!!
        val recIn = FloatArray(PaddleOcrPipeline.REC_HEIGHT * PaddleOcrPipeline.REC_WIDTH * 3)
        bench("OCR recognizerInput (one 320 px piece)", "per piece (1-3 per line)") {
            PaddleOcrPipeline.recognizerInput(strip, 0, min(strip.width, PaddleOcrPipeline.REC_WIDTH), recIn)
        }
        val fake = FakePaddle(25)
        val recOut = FloatArray(fake.recognizerSteps * fake.recognizerClasses)
        fake.recognize(recIn, recOut)
        bench("OCR ctcDecode 40x18385", "per piece") {
            PaddleOcrPipeline.ctcDecode(recOut, fake.recognizerSteps, fake.recognizerClasses, charset)
        }
        bench("OCR read() minus inference, 12MP, 25 lines", "per OCR read", minWarm = 3, maxCalls = 20) {
            PaddleOcrPipeline.read(fake, pixels, w, h, charset)
        }
    }

    // ---- Sticker cutout, NSFW filter, generator, photo editor ----------------

    private fun images() {
        val cutSide = 320
        val cutPixels = photo(cutSide, cutSide)
        val cutIn = FloatArray(cutPixels.size * 3)
        bench("Cutout normalise 320^2", "per cutout (sticker editor action)") { CutoutMask.normalise(cutPixels, cutIn) }
        val map = FloatArray(cutSide * cutSide) { (it % 997) / 997f }
        bench("Cutout alphaOf 320^2", "per cutout") { CutoutMask.alphaOf(map) }

        // NsfwFilter.unsafe's CHW loop, verbatim, on the 224^2 pixels the
        // native scaler hands it.
        val nsfwSide = 224
        val nsfwPx = photo(nsfwSide, nsfwSide)
        val chw = FloatArray(3 * nsfwSide * nsfwSide)
        bench("NsfwFilter normalise 224^2 (copied loop)", "per generated picture") {
            val plane = nsfwSide * nsfwSide
            for (i in 0 until plane) {
                val p = nsfwPx[i]
                chw[i] = ((p shr 16 and 0xff) / 255f - 0.5f) / 0.5f
                chw[plane + i] = ((p shr 8 and 0xff) / 255f - 0.5f) / 0.5f
                chw[2 * plane + i] = ((p and 0xff) / 255f - 0.5f) / 0.5f
            }
            chw
        }

        // The generator's Kotlin between network calls: one sampler step on a
        // 4x64x64 latent, and ImageGenEngine.toBitmap's loop (copied) at 512^2.
        val schedule = NoiseSchedule(0.00085, 0.012, "scaled_linear", 1000)
        val r = Random(9)
        val latent = FloatArray(4 * 64 * 64) { r.nextFloat() - 0.5f }
        val output = FloatArray(latent.size) { r.nextFloat() - 0.5f }
        val noise = FloatArray(latent.size) { r.nextFloat() - 0.5f }
        val lcm = LcmSampler(schedule, 4, vPrediction = false)
        bench("ImageGen LcmSampler.step 4x64x64", "per UNet step (1-8 per picture)") { lcm.step(latent, output, 0) { noise } }
        val euler = EulerSampler(schedule, intArrayOf(999), "trailing", vPrediction = false)
        bench("ImageGen EulerSampler.step 4x64x64", "per UNet step") { euler.step(latent, output, 0) { noise } }
        val size = 512
        val decoded = FloatArray(3 * size * size) { r.nextFloat() }
        bench("ImageGen toBitmap 512^2 (copied loop)", "per picture + per preview") {
            val plane = size * size
            IntArray(plane) { i ->
                val rr = (decoded[i] * 255f).roundToInt().coerceIn(0, 255)
                val gg = (decoded[plane + i] * 255f).roundToInt().coerceIn(0, 255)
                val bb = (decoded[2 * plane + i] * 255f).roundToInt().coerceIn(0, 255)
                (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }

        // PhotoEditRenderer.sharpenRows, copied onto one IntArray
        // (android.graphics.Color's accessors are the same shifts inlined;
        // sharpenInPlace bands it through getPixels/setPixels), then the
        // Rust kernel behind NativeEngine.sharpen on the same pixels: the
        // whole frame in one call, and in sharpenInPlace's 256-row bands.
        for ((pw, ph) in listOf(4000 to 3000, 2048 to 1536)) {
            val src = photo(pw, ph)
            val dst = IntArray(src.size)
            val often = if (pw == 2048) "per adjust change in the editor preview, sharpen > 0" else "per photo sent with sharpen > 0"
            bench("PhotoEdit sharpen ${pw}x$ph (copied loop)", often) {
                sharpen(src, dst, pw, ph, 0.5f * 1.6f)
            }
            if (!NativeEngine.useNative) continue
            val native = IntArray(src.size)
            for (step in 1..20) {
                val a = step * 0.05f * 1.6f
                sharpen(src, dst, pw, ph, a)
                check(NativeEngine.sharpen(src, pw, 0, ph, a, native)) { "native sharpen refused the frame" }
                val wrong = dst.indices.count { dst[it] != native[it] }
                check(wrong == 0) { "native sharpen differs from Kotlin at $wrong of ${dst.size} pixels, a=$a" }
            }
            println("  native sharpen ${pw}x$ph: bit for bit Kotlin's at all 20 slider steps")
            bench("PhotoEdit sharpen ${pw}x$ph (NativeEngine, one call)", often) {
                NativeEngine.sharpen(src, pw, 0, ph, 0.5f * 1.6f, native)
            }
            bench("PhotoEdit sharpen ${pw}x$ph (NativeEngine, 256-row bands)", often) {
                var y0 = 0
                while (y0 < ph) {
                    val count = min(256, ph - y0)
                    val top = max(0, y0 - 1)
                    val bottom = min(ph, y0 + count + 1)
                    val band = src.copyOfRange(top * pw, bottom * pw)
                    val out = IntArray(count * pw)
                    NativeEngine.sharpen(band, pw, y0 - top, count, 0.5f * 1.6f, out)
                    System.arraycopy(out, 0, native, y0 * pw, out.size)
                    y0 += count
                }
            }
        }
    }

    private fun sharpenChannel(c: Int, l: Int, r: Int, u: Int, d: Int, a: Float): Int =
        (c + a * (4 * c - l - r - u - d) / 4f).roundToInt().coerceIn(0, 255)

    private fun sharpen(src: IntArray, out: IntArray, w: Int, h: Int, a: Float): IntArray {
        for (y in 0 until h) {
            val up = if (y == 0) y else y - 1
            val down = if (y == h - 1) y else y + 1
            for (x in 0 until w) {
                val c = src[y * w + x]
                val l = src[y * w + max(0, x - 1)]
                val r = src[y * w + min(w - 1, x + 1)]
                val u = src[up * w + x]
                val d = src[down * w + x]
                out[y * w + x] = (c ushr 24 shl 24) or
                    (sharpenChannel(c shr 16 and 0xff, l shr 16 and 0xff, r shr 16 and 0xff, u shr 16 and 0xff, d shr 16 and 0xff, a) shl 16) or
                    (sharpenChannel(c shr 8 and 0xff, l shr 8 and 0xff, r shr 8 and 0xff, u shr 8 and 0xff, d shr 8 and 0xff, a) shl 8) or
                    sharpenChannel(c and 0xff, l and 0xff, r and 0xff, u and 0xff, d and 0xff, a)
            }
        }
        return out
    }

    // ---- Glide decoders -------------------------------------------------------------

    private const val KEY_W = 60f
    private const val KEY_H = 84f
    private val ROWS = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)

    private val centers: Map<Char, Pair<Float, Float>> = buildMap {
        ROWS.forEachIndexed { row, (letters, offset) ->
            letters.forEachIndexed { i, ch -> put(ch, (offset + i + 0.5f) * KEY_W to (row + 0.5f) * KEY_H) }
        }
    }

    /**
     * A swipe through [word]'s keys: each corner jittered by a fifth of a key,
     * points every 4 ms at a finger speed of about a key width per 35 ms, the
     * touch rate a 240 Hz panel reports.
     */
    private fun swipe(word: String, r: Random): List<GesturePoint> {
        val corners = word.map { ch ->
            val (x, y) = centers.getValue(ch)
            (x + gauss(r) * 0.2f * KEY_W) to (y + gauss(r) * 0.2f * KEY_H)
        }
        val out = ArrayList<GesturePoint>()
        var t = 0L
        out += GesturePoint(corners[0].first, corners[0].second, t)
        for (i in 1 until corners.size) {
            val (ax, ay) = corners[i - 1]
            val (bx, by) = corners[i]
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            val steps = max(1, (len / KEY_W * 35f / 4f).toInt())
            for (s in 1..steps) {
                t += 4
                val f = s.toFloat() / steps
                out += GesturePoint(ax + (bx - ax) * f + gauss(r) * 1.5f, ay + (by - ay) * f + gauss(r) * 1.5f, t)
            }
        }
        return out
    }

    private fun gauss(r: Random): Float {
        val u = max(1e-9, r.nextDouble())
        return (sqrt(-2 * ln(u)) * cos(2 * PI * r.nextDouble())).toFloat()
    }

    /** FUTO-encoder-shaped emissions for [path]: per point, letters by distance, blank between keys. */
    private fun emissions(path: List<GesturePoint>): SwipeEmissions {
        val steps = path.size
        val out = FloatArray(steps * SwipeEmissions.CLASSES)
        val letters = ('a'..'z').map { centers.getValue(it) }
        for (t in 0 until steps) {
            val p = path[t]
            var sum = 0.0
            val base = t * SwipeEmissions.CLASSES
            for (k in 0 until 26) {
                val dx = (p.x - letters[k].first) / KEY_W
                val dy = (p.y - letters[k].second) / KEY_H
                val e = exp(-(dx * dx + dy * dy) * 2.0)
                out[base + k] = e.toFloat()
                sum += e
            }
            out[base + SwipeEmissions.BLANK] = 0.6f
            sum += 0.6
            for (k in 0 until SwipeEmissions.CLASSES) out[base + k] = ln(max(1e-6, out[base + k] / sum)).toFloat()
        }
        return SwipeEmissions(steps, out)
    }

    private fun glide(dictFile: File, duetOnly: Boolean) {
        val entries = dictFile.inputStream().use { DictionaryLoader.loadEntries(it) }
        val trie = Trie().apply { entries.forEach { (word, f) -> insert(word, f) } }
        val sources = trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
        val keyCenters = centers.map { (ch, xy) -> KeyCenter(ch.code, xy.first, xy.second) }
        val keys = GlideKeyMap.of(keyCenters, KEY_W)
        val r = Random(7)
        val words = entries.map { it.first }
            .filter { w -> w.length in 3..10 && w.all { it in 'a'..'z' } }
            .take(4000).shuffled(r).take(200)
        val paths = words.map { swipe(it, r) }
        println("glide: ${entries.size} words, ${paths.size} swipes, mean ${paths.sumOf { it.size } / paths.size} points")

        var i = 0
        if (duetOnly) return duet(paths, keys, sources)
        val beam = GlideBeam()
        val ws = GlideWorkspace()
        var hits = 0
        for ((w, p) in words.zip(paths)) if (beam.decode(p, keys, KEY_W, sources, ws).firstOrNull()?.word == w) hits++
        println("glide: GlideBeam top-1 on these swipes ${hits * 100 / words.size}%")
        bench("GlideBeam.decode (finished swipe)", "per swipe at finger-up") {
            beam.decode(paths[i++ % paths.size], keys, KEY_W, sources, ws)
        }
        bench("GlideBeam 6 live previews of one stroke", "per swipe while the finger is down") {
            val p = paths[i++ % paths.size]
            for (step in 1..6) {
                val upTo = p.size * step / 6
                if (upTo >= 3) beam.decode(p.subList(0, upTo), keys, KEY_W, sources, ws)
            }
        }
        // Phase 0 also timed buildCosts' loop here, copied off the Kotlin
        // workspace (1 us a decode). The decoders are Rust now (Phase 2 step
        // 4) and the workspace holds only its sizes, so that copy is gone.

        val ctc = CtcGlideBeam()
        val ems = paths.map { emissions(it) }
        bench("CtcGlideBeam.decode (FUTO engine's beam)", "per swipe at finger-up, FUTO engine only") {
            ctc.decode(ems[i++ % ems.size], sources)
        }

        duet(paths, keys, sources)
    }

    private fun duet(paths: List<List<GesturePoint>>, keys: GlideKeyMap, sources: List<FuzzyBeamSearch.WalkSource>) {
        var i = 0
        val duet = DuetBeam()
        val dws = DuetWorkspace()
        val tokens = paths.map { listOf<GlideToken>(GlideToken.Swipe(it)) }
        bench("DuetBeam.decode (one-swipe word)", "per two-thumb word") {
            duet.decode(tokens[i++ % tokens.size], keys, KEY_W, sources, dws)
        }
    }

    // ---- Round 2: frosted glass, upscaler glue, WebM frames, the fuzzy walk ----

    /** A board-sized backdrop: soft gradients with some texture, opaque. */
    private fun backdrop(w: Int, h: Int): IntArray {
        val r = Random(13)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val rr = (128 + 90 * sin(x * 0.013f) + r.nextInt(20)).toInt().coerceIn(0, 255)
            val gg = (128 + 90 * cos(y * 0.017f) + r.nextInt(20)).toInt().coerceIn(0, 255)
            val bb = ((x + y) * 255 / (w + h)).coerceIn(0, 255)
            (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    /**
     * FrostBake.bake's pixel work: three boxBlur passes on the quarter-size
     * copy (the real function). The surrounding createScaledBitmap, getPixels,
     * setPixels and the colour-filtered upscale draw are Skia's and not here.
     */
    private fun frost() {
        // Board sizes: phone portrait and landscape, a tablet, and a whole
        // wallpaper band at phone size, each at the bake's quarter size.
        for ((w, h, what) in listOf(
            Triple(1080, 850, "phone portrait board"),
            Triple(2400, 700, "phone landscape board"),
            Triple(2560, 1100, "tablet board"),
            Triple(1440, 3200, "whole QHD screen"),
        )) {
            val sw = max(8, w / 4)
            val sh = max(8, h / 4)
            val src = backdrop(sw, sh)
            val px = IntArray(src.size)
            for (blur in floatArrayOf(10f, 25f)) {
                val radius = FrostBake.radiusFor(blur, sw, sh)
                bench("FrostBake 3x boxBlur ${sw}x$sh r=$radius ($what)", "per bake: theme/wallpaper/slider change, cached") {
                    System.arraycopy(src, 0, px, 0, src.size)
                    repeat(3) { FrostBake.boxBlur(px, sw, sh, radius) }
                    px
                }
                if (!NativeEngine.useNative) continue
                val native = src.copyOf()
                check(NativeEngine.boxBlur(native, sw, sh, radius, 3)) { "native blur refused the picture" }
                check(native.contentEquals(px)) { "native blur differs from Kotlin" }
                bench("FrostBake 3x boxBlur ${sw}x$sh r=$radius (NativeEngine)", "per bake") {
                    System.arraycopy(src, 0, px, 0, src.size)
                    NativeEngine.boxBlur(px, sw, sh, radius, 3)
                    px
                }
            }
        }
    }

    /**
     * The blur RenderEffect gives on Android 12+, for comparison with the
     * bake's box passes: the quarter-size board drawn through a
     * RenderNode with a blur effect by a HardwareRenderer into an
     * ImageReader, waited for, then (as the bake needs pixels) copied back.
     * Phone only. Not the same picture: a Gaussian, Skia's sigma, GPU
     * rounding, and only on API 31+.
     */
    private fun renderEffect() {
        if (System.getProperty("java.vm.name") != "Dalvik") return println("renderfx: phone only")
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepare()
        for ((w, h, what) in listOf(Triple(270, 212, "phone portrait board"), Triple(360, 800, "whole QHD screen"))) {
            val src = backdrop(w, h)
            val bitmap = android.graphics.Bitmap.createBitmap(src, w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val reader = android.media.ImageReader.newInstance(
                w, h, android.graphics.PixelFormat.RGBA_8888, 2,
                android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
            )
            val node = android.graphics.RenderNode("blur").apply {
                setPosition(0, 0, w, h)
                // Three box passes of radius 8 are a Gaussian of about sigma 8.
                setRenderEffect(android.graphics.RenderEffect.createBlurEffect(8f, 8f, android.graphics.Shader.TileMode.CLAMP))
                val canvas = beginRecording()
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                endRecording()
            }
            val renderer = android.graphics.HardwareRenderer().apply {
                setSurface(reader.surface)
                setContentRoot(node)
            }
            val out = IntArray(w * h)
            var frames = 0
            fun draw(readBack: Boolean): Any? {
                // Re-recorded each time: an unchanged node draws no new frame.
                node.beginRecording().also { it.drawBitmap(bitmap, 0f, 0f, null) }
                node.endRecording()
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                val image = reader.acquireLatestImage() ?: error("no frame from the HardwareRenderer")
                frames++
                image.use {
                    if (!readBack) return it
                    val hw = android.graphics.Bitmap.wrapHardwareBuffer(it.hardwareBuffer!!, null)!!
                    val soft = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    soft.getPixels(out, 0, w, 0, 0, w, h)
                    soft.recycle()
                    hw.recycle()
                }
                return out
            }
            checkNotNull(draw(true)) { "no frame from the HardwareRenderer" }
            bench("RenderEffect blur ${w}x$h, GPU only ($what)", "per bake on API 31+") { draw(false) }
            bench("RenderEffect blur ${w}x$h + readback ($what)", "per bake on API 31+") { draw(true) }
            val box = src.copyOf()
            check(NativeEngine.boxBlur(box, w, h, 8, 3))
            val off = box.indices.count { box[it] != out[it] }
            var sum = 0L
            var worst = 0
            for (i in box.indices) for (shift in intArrayOf(0, 8, 16)) {
                val d = kotlin.math.abs((box[i] shr shift and 0xFF) - (out[i] shr shift and 0xFF))
                sum += d
                worst = max(worst, d)
            }
            println(String.format(Locale.ROOT, "  RenderEffect vs box passes: %d of %d pixels differ, mean %.2f, worst %d levels a channel; %d frames drawn", off, box.size, sum / (3.0 * box.size), worst, frames))
            renderer.destroy()
            reader.close()
        }
    }

    /**
     * What the camera tools do to a frame in Kotlin-reachable code, phone
     * only. The QR scanner hands CameraX's Y plane to zxing-cpp as it is
     * (ZxingReader.readFrame, one crossing, no copy), so it has no per-pixel
     * Kotlin to time. OCR reads a still: ImageProxy.toFramedBitmap
     * (CameraX's toBitmap, then a rotate through createBitmap) and
     * PaddleOcr's getPixels, both Skia, before the pipeline Phase 1 ported.
     */
    private fun camera() {
        if (System.getProperty("java.vm.name") != "Dalvik") return println("camera: phone only")
        val (w, h) = 4000 to 3000
        val source = android.graphics.Bitmap.createBitmap(photo(w, h), w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val rotate = android.graphics.Matrix().apply { postRotate(90f) }
        bench("Camera still: rotate 12MP (createBitmap + Matrix)", "per OCR capture", minWarm = 3, maxCalls = 20) {
            android.graphics.Bitmap.createBitmap(source, 0, 0, w, h, rotate, true).also { it.recycle() }
        }
        val pixels = IntArray(w * h)
        bench("Camera still: getPixels 12MP", "per OCR read (PaddleOcr.recognize)", minWarm = 3, maxCalls = 20) {
            source.getPixels(pixels, 0, w, 0, 0, w, h)
        }
        source.recycle()
    }

    /**
     * AnimatedWebpToGif.compose's per-frame work, phone only: decode the
     * frame's still (a lossy WebP), draw it onto the canvas, draw the canvas
     * scaled into the GIF-sized bitmap, getPixels; then the Rust GifEncoder.
     * Everything before the encoder is Skia: no Kotlin pixel loop.
     */
    private fun webpFrames() {
        if (System.getProperty("java.vm.name") != "Dalvik") return println("webp: phone only")
        val side = 512
        val frames = Array(30) { stickerFrame(side, it) }
        val stills = frames.map { px ->
            val b = android.graphics.Bitmap.createBitmap(px, side, side, android.graphics.Bitmap.Config.ARGB_8888)
            java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 80, it); b.recycle() }.toByteArray()
        }
        val canvasBitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        val scaledBitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(canvasBitmap)
        val scaled = android.graphics.Canvas(scaledBitmap)
        val smooth = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
        }
        val whole = android.graphics.Rect(0, 0, side, side)
        val pixels = IntArray(side * side)
        var f = 0
        bench("AnimatedWebpToGif frame: decode still 512^2", "per frame") {
            android.graphics.BitmapFactory.decodeByteArray(stills[f % 30], 0, stills[f++ % 30].size).also { it.recycle() }
        }
        val still = android.graphics.BitmapFactory.decodeByteArray(stills[0], 0, stills[0].size)
        bench("AnimatedWebpToGif frame: draw + scale + getPixels 512^2", "per frame") {
            canvas.drawBitmap(still, null, whole, null)
            scaled.drawBitmap(canvasBitmap, whole, whole, smooth)
            scaledBitmap.getPixels(pixels, 0, side, 0, 0, side, side)
            pixels
        }
        val encoder = GifEncoder(Discard, side, side)
        bench("AnimatedWebpToGif frame: GifEncoder.addFrame (Rust)", "per frame") { encoder.addFrame(frames[f++ % 30], 40) }
    }

    /**
     * The upscaler's Kotlin around the network: UpscaleTiles.pass with a
     * stand-in network that answers a prepared tile (so input and output run,
     * not MNN), the alpha merge loop UpscaleEngine.run puts each tile through
     * (copied: it sits in a lambda over a Bitmap), and bleed/alphaAsGrey.
     */
    private fun upscale() {
        val side = UpscaleModels.TILE
        val outSide = side * UpscaleModels.SCALE
        val r = Random(17)
        val answer = FloatArray(3 * outSide * outSide) { r.nextFloat() }
        val network = { _: FloatArray, _: Int -> answer }
        for ((w, h, what) in listOf(Triple(512, 512, "sticker x2"), Triple(1024, 1024, "sticker x4, 2nd pass"), Triple(1920, 1080, "1080p photo x2"))) {
            val px = photo(w, h)
            val tiles = UpscaleTiles.tiles(w, h, UpscaleTiles.side(w, h)).size
            for (native in listOf(false, true)) {
                if (native && !NativeEngine.loaded) continue
                NativeEngine.forceKotlin = !native
                val how = if (native) "native" else "kotlin"
                bench("Upscale pass glue ${w}x$h ($tiles tiles, no network) $how", "per upscale pass ($what)", minWarm = 3, maxCalls = 40) {
                    UpscaleTiles.pass(px, w, h, network, { false }) { _, _, _, _, rgb -> sink = rgb }
                }
            }
            NativeEngine.forceKotlin = false
            // The last pass's alpha merge: every output pixel once.
            val outW = w * 2
            val bigAlpha = IntArray(outW * h * 2) { r.nextInt() }
            val tileRgb = IntArray(outSide * outSide) { r.nextInt() }
            bench("Upscale alpha merge ${outW}x${h * 2} (copied loop)", "per upscale with alpha ($what)", minWarm = 3, maxCalls = 40) {
                var y = 0
                while (y < h * 2) {
                    var x = 0
                    while (x < outW) {
                        val tw = min(outSide, outW - x)
                        val th = min(outSide, h * 2 - y)
                        for (row in 0 until th) {
                            val from = (y + row) * outW + x
                            for (col in 0 until tw) {
                                val i = row * tw + col
                                tileRgb[i] = (UpscaleTiles.greyToAlpha(bigAlpha[from + col]) shl 24) or (tileRgb[i] and 0xFFFFFF)
                            }
                        }
                        x += outSide
                    }
                    y += outSide
                }
                tileRgb
            }
            if (NativeEngine.useNative) {
                bench("Upscale alpha merge ${outW}x${h * 2} (NativeEngine)", "per upscale with alpha ($what)", minWarm = 3, maxCalls = 40) {
                    var y = 0
                    while (y < h * 2) {
                        var x = 0
                        while (x < outW) {
                            NativeEngine.mergeAlpha(tileRgb, min(outSide, outW - x), min(outSide, h * 2 - y), bigAlpha, outW, y * outW + x)
                            x += outSide
                        }
                        y += outSide
                    }
                    tileRgb
                }
            }
        }
        // bleed on a sticker: a round opaque blob on transparent black.
        val s = 512
        val sticker = stickerFrame(s, 0).map { if ((it ushr 24) < 255) it and 0xFFFFFF else it }.toIntArray()
        val work = IntArray(sticker.size)
        bench("Upscale alphaAsGrey 512^2", "per upscale with alpha") { UpscaleTiles.alphaAsGrey(sticker) }
        bench("Upscale bleed 512^2 sticker (16 rings)", "per upscale with alpha", minWarm = 3, maxCalls = 40) {
            System.arraycopy(sticker, 0, work, 0, sticker.size)
            UpscaleTiles.bleed(work, s, s)
        }
    }

    // WebmFrames.rgbOf and lumaOf (private, over android.media.Image), copied
    // onto direct ByteBuffers laid out as an Image's planes: I420
    // (pixelStride 1) and the semi-planar layout hardware decoders hand back
    // (chroma pixelStride 2, U and V interleaved).
    private class Planes(val y: ByteBuffer, val yRow: Int, val u: ByteBuffer, val v: ByteBuffer, val cRow: Int, val cPixel: Int)

    private fun planes(w: Int, h: Int, semi: Boolean): Planes {
        val r = Random(19)
        val y = ByteBuffer.allocateDirect(w * h).also { b -> repeat(w * h) { b.put(it, (16 + r.nextInt(220)).toByte()) } }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        return if (semi) {
            val uv = ByteBuffer.allocateDirect(cw * ch * 2).also { b -> repeat(cw * ch * 2) { b.put(it, (16 + r.nextInt(224)).toByte()) } }
            val v = uv.duplicate().also { it.position(1) }.slice()
            Planes(y, w, uv, v, cw * 2, 2)
        } else {
            val u = ByteBuffer.allocateDirect(cw * ch).also { b -> repeat(cw * ch) { b.put(it, (16 + r.nextInt(224)).toByte()) } }
            val v = ByteBuffer.allocateDirect(cw * ch).also { b -> repeat(cw * ch) { b.put(it, (16 + r.nextInt(224)).toByte()) } }
            Planes(y, w, u, v, cw, 1)
        }
    }

    private fun rgbOf(p: Planes, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        for (row in 0 until height) {
            val yRow = row * p.yRow
            val cRow = row / 2
            for (col in 0 until width) {
                val luma = p.y.get(yRow + col).toInt() and 0xFF
                val cCol = col / 2
                val cb = (p.u.get(cRow * p.cRow + cCol * p.cPixel).toInt() and 0xFF) - 128
                val cr = (p.v.get(cRow * p.cRow + cCol * p.cPixel).toInt() and 0xFF) - 128
                val c = 298 * (luma - 16)
                val rr = ((c + 409 * cr + 128) shr 8).coerceIn(0, 0xFF)
                val g = ((c - 100 * cb - 208 * cr + 128) shr 8).coerceIn(0, 0xFF)
                val b = ((c + 516 * cb + 128) shr 8).coerceIn(0, 0xFF)
                out[row * width + col] = (rr shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun lumaOf(p: Planes, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            val from = row * p.yRow
            for (col in 0 until width) out[row * width + col] = p.y.get(from + col)
        }
        return out
    }

    private fun webm() {
        val side = 512
        for (semi in listOf(false, true)) {
            val p = planes(side, side, semi)
            val layout = if (semi) "semi-planar" else "I420"
            bench("WebmFrames rgbOf 512^2 $layout (copied)", "per colour frame, ~90 per video sticker") { rgbOf(p, side, side) }
            if (!NativeEngine.useNative) continue
            val native = IntArray(side * side)
            val call = {
                NativeEngine.yuvToRgb(p.y, p.yRow, 1, p.u, p.cRow, p.cPixel, p.v, p.cRow, p.cPixel, 0, 0, side, side, side, native)
            }
            check(call()) { "native YUV refused the frame" }
            val kotlin = rgbOf(p, side, side)
            val wrong = native.indices.count { native[it] != kotlin[it] }
            check(wrong == 0) { "native YUV differs from Kotlin at $wrong pixels" }
            println("  native rgbOf $layout: bit for bit Kotlin's")
            bench("WebmFrames rgbOf 512^2 $layout (NativeEngine)", "per colour frame") { call(); native }
        }
        val p = planes(side, side, false)
        bench("WebmFrames lumaOf 512^2 (copied)", "per alpha frame") { lumaOf(p, side, side) }
        bench("WebmFrames lumaOf 512^2 (row bulk get)", "per alpha frame") {
            val out = ByteArray(side * side)
            val rows = p.y.duplicate()
            for (row in 0 until side) {
                rows.position(row * p.yRow)
                rows.get(out, row * side, side)
            }
            out
        }
        val rgb = rgbOf(p, side, side)
        val alpha = lumaOf(p, side, side)
        val pixels = IntArray(side * side)
        bench("WebmFrames compose 512^2 (copied)", "per frame") {
            for (i in pixels.indices) {
                val a = alpha[i].toInt() and 0xFF
                pixels[i] = (a shl 24) or (rgb[i] and 0xFFFFFF)
            }
            pixels
        }
    }

    /**
     * The Rust correction walk through FuzzyBeamSearch.search: dictionary
     * words with one typo of each kind, as the keyboard searches per keystroke
     * (the prefix typed so far, limit 10). For its split, sample the process.
     */
    private fun fuzzy(dictFile: File) {
        val entries = dictFile.inputStream().use { DictionaryLoader.loadEntries(it) }
        val trie = Trie().apply { entries.forEach { (word, f) -> insert(word, f) } }
        val sources = trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
        val r = Random(23)
        val words = entries.map { it.first }.filter { w -> w.length in 4..10 && w.all { it in 'a'..'z' } }.take(3000).shuffled(r).take(200)
        val typos = words.map { w ->
            val i = r.nextInt(w.length)
            when (r.nextInt(4)) {
                0 -> w.removeRange(i, i + 1)
                1 -> w.substring(0, i) + ('a' + r.nextInt(26)) + w.substring(i)
                2 -> w.substring(0, i) + ('a' + r.nextInt(26)) + w.substring(i + 1)
                else -> if (i + 1 < w.length) w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) else w
            }
        }
        val prefixes = typos.flatMap { t -> (1..t.length).map { t.substring(0, it) } }
        val search = FuzzyBeamSearch()
        val ws = BeamWorkspace()
        var i = 0
        bench("FuzzyBeamSearch.search whole typo word", "per keystroke (end of word)") {
            search.search(sources, typos[i++ % typos.size], KeyProximity.QWERTY, 10, ws)
        }
        bench("FuzzyBeamSearch.search every prefix", "per keystroke") {
            search.search(sources, prefixes[i++ % prefixes.size], KeyProximity.QWERTY, 10, ws)
        }
    }

    // ---- Voice capture glue -------------------------------------------------------

    private fun audio() {
        val r = Random(11)
        val chunk = ShortArray(1600) { (r.nextInt(2000) - 1000).toShort() }
        val raw = FloatArray(chunk.size)
        // WhisperRecorder.capture's per-chunk loop, copied (it reads AudioRecord).
        bench("PCM16 -> float + RMS, 1600 samples (copied)", "per 100 ms audio chunk", minWarm = 2000) {
            var rawSq = 0.0
            for (i in 0 until chunk.size) {
                val s = chunk[i] / 32768f
                raw[i] = s
                rawSq += (s * s).toDouble()
            }
            sinkF = sqrt(rawSq / chunk.size).toFloat()
            raw
        }
        val clip = FloatArray(WhisperMel.SAMPLE_RATE * 5) { (r.nextFloat() - 0.5f) * 0.2f }
        val cols = 1 + WhisperMel.N_FFT / 2
        val filters = melFilters(WhisperMel.N_MEL, cols)
        bench("WhisperMel.compute, 5 s clip, 80 mel", "per utterance, LiteRT Whisper only", minWarm = 3, maxCalls = 40) {
            WhisperMel.compute(clip, filters, cols, WhisperMel.N_MEL)
        }
        bench("WavEncoder.encode, 5 s clip", "per utterance, server engines only") { WavEncoder.encode(clip) }
    }

    /** Triangular bands over [cols] bins, the shape Whisper's filterbank has. */
    private fun melFilters(nMel: Int, cols: Int): FloatArray {
        val f = FloatArray(nMel * cols)
        for (m in 0 until nMel) {
            val centre = (m + 1) * (cols - 2) / (nMel + 1)
            val half = max(1, centre / 12 + 1)
            for (c in max(0, centre - half)..min(cols - 1, centre + half)) {
                f[m * cols + c] = 1f - kotlin.math.abs(c - centre).toFloat() / (half + 1)
            }
        }
        return f
    }
}
