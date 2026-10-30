package com.wasimaster.wmkeyboard.core.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.wasimaster.wmkeyboard.core.settings.KeySoundOutput
import com.wasimaster.wmkeyboard.core.settings.KeySoundStyle
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Plays the key-press sound. Click and Standard come from the device's system
 * sound-effect pack, matching the stock keyboard. Pop, Thock and Chime are
 * synthesized in-app and played through a [SoundPool]: many devices map the
 * spacebar, delete and return system effects to the same audio file, which
 * made the three styles indistinguishable. Custom is a file the user installed,
 * from an addon repository or their own storage, played through the same pool.
 * Pack is a [SoundPackStore] pack: many recordings, one picked per keystroke,
 * optionally a different set per [KeySoundRole], per individual key and per
 * [KeySoundPhase].
 * Lives outside the IME service so the settings app and the sound & haptics
 * tool can preview the sound being adjusted.
 */
object KeySoundPlayer {

    /** Minimum spacing between previews so slider drags don't machine-gun. */
    private const val PREVIEW_GAP_MS = 150L

    /**
     * How long a previewed keystroke is held down for.
     *
     * A preview is a tap nobody performed, so the gap between its two halves
     * has to be invented. 110 ms is around the middle of a real one: fast
     * typists sit near 70 ms and a deliberate press runs past 150, and either
     * end reads as wrong — shorter and the two clicks smear into one, longer
     * and the pack sounds sluggish next to how it will actually feel.
     */
    private const val PREVIEW_STROKE_MS = 110L

    private const val SAMPLE_RATE = 44100

    /** Bump when the synthesis recipes change to regenerate cached files. */
    private const val CACHE_VERSION = 1

    private var lastPreviewAt = 0L

    private var pool: SoundPool? = null

    /**
     * Held rather than looked up per press: [systemFx] runs inside the pointer-
     * down handler, and `getSystemService` there put a service lookup on the
     * touch path of every keystroke. Built from the application context so the
     * singleton never pins an activity or the IME's own context.
     */
    @Volatile
    private var audio: AudioManager? = null

    /**
     * Whether headphones or a Bluetooth speaker are connected, kept current by
     * a device callback for the same reason as [audio]: a press asks, and
     * asking the system every time would be a binder call per keystroke. Null
     * until the first press that has to know.
     */
    @Volatile
    private var headphonesConnected: Boolean? = null

    /** Whether a key sound may play right now under [output] (#1021). */
    fun outputAllows(context: Context, output: KeySoundOutput): Boolean = when (output) {
        KeySoundOutput.ANYWHERE -> true
        KeySoundOutput.HEADPHONES -> headphonesConnected(context)
        KeySoundOutput.SPEAKER -> !headphonesConnected(context)
    }

    private fun headphonesConnected(context: Context): Boolean = headphonesConnected ?: synchronized(this) {
        headphonesConnected ?: run {
            val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            manager.registerAudioDeviceCallback(
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                        headphonesConnected = readHeadphones(manager)
                    }

                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                        headphonesConnected = readHeadphones(manager)
                    }
                },
                Handler(Looper.getMainLooper()),
            )
            readHeadphones(manager).also { headphonesConnected = it }
        }
    }

    // Not Bluetooth SCO: that is the phone-call route, listed for a paired
    // headset whether or not it is what media plays on.
    private fun readHeadphones(manager: AudioManager): Boolean =
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in PRIVATE_OUTPUTS }

    private val PRIVATE_OUTPUTS = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )

    private val soundIds = mutableMapOf<KeySoundStyle, Int>()
    private val loadedIds = mutableSetOf<Int>()

    /**
     * Installed sounds, loaded on demand rather than up front — there can be
     * thirty of them and only the selected one is ever played. Keyed by store
     * id *and* modification time, so replacing a sound under the same id is
     * picked up instead of replaying the stale sample.
     */
    private val customIds = mutableMapOf<String, Int>()

    /**
     * The one sound pack currently decoded into the pool.
     *
     * One, not a map: a pack is many samples, and keeping the previous
     * selection resident so a user who switched away might switch back is
     * memory spent on a guess. Switching packs unloads the old one.
     */
    private var loadedPack: LoadedPack? = null

    private class LoadedPack(
        val packId: String,
        val manifest: SoundPackManifest,
        /** Sample name -> pool id. */
        val samples: Map<String, Int>,
        /**
         * Last variant played per role and phase, so no role ever repeats
         * itself. Press and release keep separate cursors: they are separate
         * lists, and a shared one would make "which release did I last play?"
         * depend on how many letters were typed in between.
         */
        val lastIndex: Array<IntArray> = Array(KeySoundPhase.entries.size) {
            IntArray(KeySoundRole.entries.size) { -1 }
        },
    ) {
        /**
         * The same cursor per key that names its own samples, keyed by phase.
         *
         * A map rather than more slots in [lastIndex] because the set of keys
         * is the pack's, not this build's, and because the role cursors are
         * read for every keystroke on the board while these are read only on
         * the keys a pack actually named. Created lazily for the same reason:
         * most packs name none.
         */
        private var keyIndex: HashMap<String, IntArray>? = null

        fun lastKeyIndex(key: String, phase: KeySoundPhase): Int =
            keyIndex?.get(key)?.get(phase.ordinal) ?: -1

        fun rememberKeyIndex(key: String, phase: KeySoundPhase, index: Int) {
            val map = keyIndex ?: HashMap<String, IntArray>().also { keyIndex = it }
            map.getOrPut(key) { IntArray(KeySoundPhase.entries.size) { -1 } }[phase.ordinal] = index
        }
    }

    /** Builds the pool and starts decoding so the first key press isn't late. */
    fun warmUp(context: Context) {
        // Outside the lock, and first. This runs on a worker at IME start while
        // [play] runs on the main thread from the pointer-down handler, and the
        // two share this object's monitor — so anything slow done while holding
        // it is time a keypress can be made to wait for. Synthesising three
        // waveforms and writing them to disk (which only happens on the first
        // run after an install or a cleared cache) is by far the slowest thing
        // here, and it needs no shared state, so it happens before the lock is
        // ever taken. [ensurePool] still checks the same files itself, so a
        // press that gets there first is correct either way — it just finds
        // them already written.
        prepareCacheFiles(context)
        ensurePool(context)
        // Same reason: the Click and Standard styles never touch the pool, so
        // without this their first press is the one paying for the lookup.
        if (audio == null) {
            audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
    }

    /**
     * Rate-limited [play] for settings UIs: sounds the values being edited
     * (not yet necessarily persisted), at most once per [PREVIEW_GAP_MS].
     */
    fun preview(
        context: Context,
        style: KeySoundStyle,
        volume: Float,
        customId: String = "",
        target: KeySoundTarget = KeySoundTarget.DEFAULT,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewAt < PREVIEW_GAP_MS) return
        lastPreviewAt = now
        play(context, style, volume, customId, target)
    }

    /**
     * [preview] of a whole keystroke: the press now, the release a held
     * moment later.
     *
     * For the places where the user is choosing a *pack* rather than nudging a
     * volume. A pack that recorded the switch coming back up is only half
     * itself on the way down, and a picker that plays half of it is picking on
     * the wrong evidence. Costs nothing for a pack without release samples —
     * [play] finds none and stays quiet.
     */
    fun previewStroke(
        context: Context,
        style: KeySoundStyle,
        volume: Float,
        customId: String = "",
        target: KeySoundTarget = KeySoundTarget.DEFAULT,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewAt < PREVIEW_GAP_MS) return
        lastPreviewAt = now
        play(context, style, volume, customId, target, KeySoundPhase.PRESS)
        strokeHandler.postDelayed(
            { play(context, style, volume, customId, target, KeySoundPhase.RELEASE) },
            PREVIEW_STROKE_MS,
        )
    }

    /** Only ever used to time [previewStroke]'s second half. */
    private val strokeHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Decodes a pack's samples ahead of the first press.
     *
     * Worth its own entry point because a pack is many files: without this the
     * first several keystrokes after the IME starts fall back to the system
     * click while the pool catches up, which reads as the pack not working.
     * Safe to call with a blank or unknown id, and cheap to call repeatedly —
     * a pack already resident is left alone.
     */
    fun preload(context: Context, packId: String) {
        if (packId.isBlank()) return
        synchronized(this) { residentPack(context, packId) }
    }

    /**
     * [customId] is the [SoundStore] id to play when [style] is
     * [KeySoundStyle.CUSTOM] and the [SoundPackStore] id when it is
     * [KeySoundStyle.PACK]; it is ignored otherwise. A sound that has been
     * deleted, or is still decoding, falls back to a system effect rather than
     * to silence — a keystroke that makes no sound reads as a missed keystroke.
     *
     * [target] only means anything for [KeySoundStyle.PACK], and only for a
     * pack that filled that role or named that key; everything else plays one
     * sound for every key.
     *
     * [phase] likewise. [KeySoundPhase.RELEASE] is the one call that may end in
     * silence on purpose: only a pack can have recorded a key coming back up,
     * and most have not, so a release with nothing behind it returns without
     * the system-click fallback a press would get. Falling back there would
     * give every style on the list a second click it never had.
     */
    fun play(
        context: Context,
        style: KeySoundStyle,
        volume: Float,
        customId: String = "",
        target: KeySoundTarget = KeySoundTarget.DEFAULT,
        phase: KeySoundPhase = KeySoundPhase.PRESS,
    ) {
        val vol = volume.coerceIn(0.05f, 1f)
        if (phase == KeySoundPhase.RELEASE) {
            if (style != KeySoundStyle.PACK) return
            val shot = synchronized(this) { packShot(context, customId, target, phase) } ?: return
            val packVol = vol * shot.second
            pool?.play(shot.first, packVol, packVol, 1, 0, 1f)
            return
        }
        when (style) {
            KeySoundStyle.CLICK -> systemFx(context, AudioManager.FX_KEY_CLICK, vol)
            KeySoundStyle.STANDARD -> systemFx(context, AudioManager.FX_KEYPRESS_STANDARD, vol)
            KeySoundStyle.PACK -> {
                // Resolved and played under the lock: picking the variant reads
                // and writes the pack's per-role cursor, so two fingers landing
                // together must not both read the same "last played" value and
                // both avoid it.
                val shot = synchronized(this) { packShot(context, customId, target, phase) }
                if (shot != null) {
                    val packVol = vol * shot.second
                    pool?.play(shot.first, packVol, packVol, 1, 0, 1f)
                } else {
                    systemFx(context, AudioManager.FX_KEY_CLICK, vol)
                }
            }
            KeySoundStyle.CUSTOM -> {
                val id = synchronized(this) { customSampleId(context, customId) }
                if (id != null) {
                    pool?.play(id, vol, vol, 1, 0, 1f)
                } else {
                    systemFx(context, AudioManager.FX_KEY_CLICK, vol)
                }
            }
            else -> {
                val id = synchronized(this) {
                    ensurePool(context)
                    soundIds[style]?.takeIf { it in loadedIds }
                }
                if (id != null) {
                    pool?.play(id, vol, vol, 1, 0, 1f)
                } else {
                    // Still decoding (a press right after IME start): fall
                    // back to the nearest system effect rather than silence.
                    systemFx(context, AudioManager.FX_KEYPRESS_STANDARD, vol)
                }
            }
        }
    }

    /**
     * Whether [style] with [id] has a sound behind it at all.
     *
     * Asked of the *store*, not of the pool: a sample still decoding is
     * playable and will be audible a keystroke or two from now, while one that
     * was never installed — or has been deleted — never will be. The caller
     * uses this to decide whether a theme's sound stands or whether the user's
     * own pick takes over ([com.wasimaster.wmkeyboard.core.settings.keySound]),
     * and that answer must not flip mid-burst because the decoder is behind.
     *
     * True for every style that renders itself or borrows a system effect;
     * only Custom and Pack can name something that is not there.
     */
    fun canPlay(context: Context, style: KeySoundStyle, id: String): Boolean =
        canPlay(SoundStore.get(context), SoundPackStore.get(context), style, id)

    /** [canPlay] against stores handed in, so it can be answered without a context. */
    fun canPlay(
        sounds: SoundStore,
        packs: SoundPackStore,
        style: KeySoundStyle,
        id: String,
    ): Boolean = when (style) {
        KeySoundStyle.CUSTOM -> resolveCustom(sounds, id) != null
        KeySoundStyle.PACK -> packs.resolve(id)?.let { packs.existingDirFor(it) } != null
        else -> true
    }

    /**
     * An installed sound's store id and file, or null when [soundId] names
     * nothing this device has.
     *
     * [soundId] resolves by the store id first, then by the sound's display
     * name. The id is minted per device at install time, so a *distributed*
     * theme has no way to know it — but an addon-installed sound keeps its
     * catalogue name on every device, which is what a theme's `soundCustomId`
     * can carry ("Typewriter") next to a `requires` on that sound's addon.
     *
     * Takes no lock and caches nothing, so [canPlay] can ask it from anywhere.
     */
    private fun resolveCustom(store: SoundStore, soundId: String): Pair<String, File>? {
        if (soundId.isBlank()) return null
        // Resolved to the store id before anything is cached, so forgetCustom
        // (which speaks store ids) still evicts a name-resolved sample.
        val resolvedId = if (store.existingFileFor(soundId) != null) {
            soundId
        } else {
            store.sounds().firstOrNull { it.name.equals(soundId, ignoreCase = true) }?.id
        } ?: return null
        val file = store.existingFileFor(resolvedId) ?: return null
        return resolvedId to file
    }

    /**
     * The pool's sample id for an installed sound, loading it on first use.
     * Null while it is still decoding, and on the press that triggers the load.
     */
    private fun customSampleId(context: Context, soundId: String): Int? {
        val (resolvedId, file) = resolveCustom(SoundStore.get(context), soundId) ?: return null
        val key = "$resolvedId:${file.lastModified()}"
        customIds[key]?.let { return it.takeIf { id -> id in loadedIds } }
        val p = ensurePool(context)
        customIds[key] = p.load(file.path, 1)
        return null
    }

    /**
     * The pool id and gain for one keystroke of a pack, or null to fall back.
     *
     * Null covers three cases that all sound the same to the user and are all
     * temporary: the pack is not installed, it is still decoding, or the role's
     * chosen variant failed to load. Only ever called under this object's
     * monitor.
     */
    private fun packShot(
        context: Context,
        packId: String,
        target: KeySoundTarget,
        phase: KeySoundPhase,
    ): Pair<Int, Float>? {
        val pack = residentPack(context, packId) ?: return null
        // Asked in two steps rather than one so the cursor can follow the list
        // that won: a key the pack named keeps its own "not that one again"
        // memory, and a key it did not shares its role's.
        val perKey = pack.manifest.keySamplesFor(target, phase)
        val names = perKey.ifEmpty { pack.manifest.samplesFor(target.role, phase) }
        if (names.isEmpty()) return null

        val index: Int
        if (perKey.isEmpty()) {
            val cursor = pack.lastIndex[phase.ordinal]
            val slot = target.role.ordinal
            index = nextVariant(names.size, cursor[slot])
            cursor[slot] = index
        } else {
            index = nextVariant(names.size, pack.lastKeyIndex(target.key, phase))
            pack.rememberKeyIndex(target.key, phase, index)
        }

        val sampleId = pack.samples[names[index]] ?: return null
        if (sampleId !in loadedIds) return null
        return sampleId to pack.manifest.gainFor(target)
    }

    /**
     * A variant index that is never [last].
     *
     * Monkeytype picks uniformly across the whole list, which on a three-variant
     * set repeats the previous sample about a third of the time — and a repeat
     * is precisely what having variants is supposed to prevent. Drawing from the
     * other `n - 1` instead is still uniform among them and costs one integer
     * per role.
     */
    private fun nextVariant(size: Int, last: Int): Int {
        if (size <= 1) return 0
        if (last !in 0 until size) return Random.nextInt(size)
        val drawn = Random.nextInt(size - 1)
        return if (drawn >= last) drawn + 1 else drawn
    }

    /**
     * The requested pack, decoded into the pool, loading it on first use.
     *
     * Returns the pack as soon as its samples are *queued*: the caller checks
     * [loadedIds] for the one variant it actually wants, so a pack whose first
     * three samples are ready plays them while the rest are still decoding.
     */
    private fun residentPack(context: Context, packId: String): LoadedPack? {
        if (packId.isBlank()) return null
        val store = SoundPackStore.get(context)
        // Resolved to the store id before anything is cached, so forgetPack
        // (which speaks store ids) still evicts a name-resolved pack. A theme
        // or a repository can only know the pack's catalogue name, never the
        // id minted on this device at install time.
        val resolved = store.resolve(packId) ?: return null
        loadedPack?.let { if (it.packId == resolved) return it }

        val manifest = store.manifestFor(resolved) ?: return null
        val p = ensurePool(context)
        unloadPack()

        val samples = HashMap<String, Int>()
        // Every sample the pack has, per-key sets included. What bounds this is
        // the pack's own size: the importer counts bytes as it extracts and
        // refuses a pack past [SoundPackFile.MAX_TOTAL_BYTES], so a pack that
        // installed at all is one this can decode whole.
        for (name in manifest.allSamples()) {
            val file = store.sampleFile(resolved, name) ?: continue
            samples[name] = p.load(file.path, 1)
        }
        if (samples.isEmpty()) return null
        return LoadedPack(resolved, manifest, samples).also { loadedPack = it }
    }

    /** Releases the resident pack's samples. Caller holds the monitor. */
    private fun unloadPack() {
        loadedPack?.samples?.values?.forEach { sampleId ->
            pool?.unload(sampleId)
            loadedIds.remove(sampleId)
        }
        loadedPack = null
    }

    /**
     * Drops a pack the user deleted or replaced, for the same reason
     * [forgetCustom] exists: the pool's copy outlives the files.
     */
    @Synchronized
    fun forgetPack(packId: String) {
        if (loadedPack?.packId == packId) unloadPack()
    }

    /**
     * Drops a sound the user deleted or replaced. The [SoundPool] holds the
     * decoded sample independently of the file, so without this a deleted sound
     * would keep playing until the process restarted.
     */
    @Synchronized
    fun forgetCustom(soundId: String) {
        val stale = customIds.keys.filter { it.substringBeforeLast(':') == soundId }
        for (key in stale) {
            customIds.remove(key)?.let { sampleId ->
                pool?.unload(sampleId)
                loadedIds.remove(sampleId)
            }
        }
    }

    private fun systemFx(context: Context, effect: Int, volume: Float) {
        val am = audio ?: (
            context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            ).also { audio = it }
        am.playSoundEffect(effect, volume)
    }

    @Synchronized
    private fun ensurePool(context: Context): SoundPool {
        pool?.let { return it }
        val p = SoundPool.Builder()
            // Eight rather than four: a pack with release samples puts two
            // sounds in the air per keystroke, and the release of the key
            // before last is still ringing while the next one goes down. At
            // four, a fast run on a long-tailed pack starts cutting its own
            // oldest stream — audible as keys that stop finishing.
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(this) { loadedIds.add(sampleId) }
        }
        val dir = File(context.cacheDir, "keysounds").apply { mkdirs() }
        for (style in SYNTHESIZED_STYLES) {
            val file = cacheFileFor(dir, style)
            if (!file.exists()) writeWav(file, synthesize(style))
            soundIds[style] = p.load(file.path, 1)
        }
        pool = p
        return p
    }

    /** The three styles this object renders itself, rather than borrowing from the system. */
    private val SYNTHESIZED_STYLES =
        listOf(KeySoundStyle.POP, KeySoundStyle.THOCK, KeySoundStyle.CHIME)

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "keysounds").apply { mkdirs() }

    private fun cacheFileFor(dir: File, style: KeySoundStyle): File =
        File(dir, "${style.name.lowercase()}_v$CACHE_VERSION.wav")

    /**
     * Renders any missing waveform to the cache, taking no lock.
     *
     * Idempotent and safe to race with [ensurePool]: both skip a file that is
     * already there, and both write the same bytes for one that is not. See
     * [warmUp] for why it is worth keeping out of the monitor.
     */
    private fun prepareCacheFiles(context: Context) {
        val dir = runCatching { cacheDir(context) }.getOrNull() ?: return
        for (style in SYNTHESIZED_STYLES) {
            val file = cacheFileFor(dir, style)
            if (!file.exists()) runCatching { writeWav(file, synthesize(style)) }
        }
    }

    // ---- synthesis ----

    /** One sine component: frequency sweeps [startHz] → [endHz] over the sound. */
    private class Partial(val startHz: Double, val endHz: Double, val gain: Double)

    /** Only the three synthesised styles reach here; the rest never call it. */
    private fun synthesize(style: KeySoundStyle): ShortArray = when (style) {
        // Low sine with a quick downward pitch bend: a soft bubble-pop thump.
        KeySoundStyle.POP -> render(
            durationMs = 55, decayMs = 16.0,
            partials = listOf(Partial(175.0, 110.0, 1.0)),
        )
        // Deeper fundamental plus a quiet octave, slower decay: the woody
        // bottomed-out sound of a lubed mechanical board.
        KeySoundStyle.THOCK -> render(
            durationMs = 120, decayMs = 38.0,
            partials = listOf(
                Partial(92.0, 80.0, 1.0),
                Partial(184.0, 160.0, 0.35),
            ),
        )
        // Two high inharmonic sines ringing out: a small bell strike.
        KeySoundStyle.CHIME -> render(
            durationMs = 150, decayMs = 50.0,
            partials = listOf(
                Partial(1568.0, 1568.0, 1.0),
                Partial(2093.0, 2093.0, 0.6),
            ),
        )
        else -> error("$style uses the system sound pack")
    }

    private fun render(durationMs: Int, decayMs: Double, partials: List<Partial>): ShortArray {
        val n = SAMPLE_RATE * durationMs / 1000
        val attack = SAMPLE_RATE * 2 / 1000 // 2 ms fade-in kills the onset click
        val phases = DoubleArray(partials.size)
        val mix = DoubleArray(n)
        var peak = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            var env = exp(-t * 1000.0 / decayMs)
            if (i < attack) env *= i.toDouble() / attack
            var sample = 0.0
            partials.forEachIndexed { j, part ->
                val hz = part.startHz + (part.endHz - part.startHz) * i / n
                phases[j] += 2.0 * PI * hz / SAMPLE_RATE
                sample += part.gain * sin(phases[j])
            }
            mix[i] = sample * env
            if (abs(mix[i]) > peak) peak = abs(mix[i])
        }
        val norm = if (peak > 0) 0.85 / peak else 0.0
        return ShortArray(n) { (mix[it] * norm * Short.MAX_VALUE).toInt().toShort() }
    }

    /** Minimal 16-bit mono PCM WAV container around [samples]. */
    private fun writeWav(file: File, samples: ShortArray) {
        val dataSize = samples.size * 2
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)               // PCM chunk size
        header.putShort(1)              // PCM format
        header.putShort(1)              // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)  // byte rate
        header.putShort(2)              // block align
        header.putShort(16)             // bits per sample
        header.put("data".toByteArray())
        header.putInt(dataSize)
        val pcm = java.nio.ByteBuffer.allocate(dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort(it) }
        // Written to a sibling and renamed into place, never straight to the
        // destination. [prepareCacheFiles] runs this off the object's monitor
        // while [ensurePool] may be testing the same path with exists() and
        // handing it to SoundPool.load from the main thread — and an
        // outputStream() on the real path truncates it first, so that reader
        // could see a header with no samples behind it. SoundPool fails such a
        // load silently: the style would keep falling back to the system click
        // for the life of the process, with nothing to say why. A rename is
        // atomic within the directory, so a reader sees the old file or the
        // whole new one.
        val dir = file.parentFile ?: return
        val tmp = File.createTempFile(file.nameWithoutExtension, ".tmp", dir)
        try {
            tmp.outputStream().use {
                it.write(header.array())
                it.write(pcm.array())
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: java.io.IOException) {
            // Leave no partial sibling behind; the destination is untouched, so
            // the next ensurePool writes it under the lock the way it always did.
            tmp.delete()
            throw e
        }
    }
}
