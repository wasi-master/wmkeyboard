package com.wasimaster.wmkeyboard.core.modules

import android.content.Context
import android.system.Os
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Native delivery for the builds Play Feature Delivery does not reach: the
 * GitHub build, the F-Droid build once the user opts in, and the offline
 * build, which has no internet permission and takes each library only as an
 * import of the file published for it ([import], [importArchive]).
 *
 * Such a build leaves a set of native libraries out of the APK and writes a
 * [NativePins] table in their place. [install] reads it at startup and puts
 * one [DownloadedModuleGate] behind each gate it names, the same
 * [ModuleGate] shape Play's modules use, so every facade, banner and
 * `awaitInstalled` caller works unchanged: a missing library is fetched the
 * first time its feature asks, verified against its pin, and loaded from
 * app storage.
 *
 * Two kinds of loading:
 * - the project's own libraries go through [FeatureModules.loadLibrary],
 *   which [install] points at the downloaded files;
 * - a library a dependency loads itself with `System.loadLibrary` (ML Kit,
 *   LiteRT, LiteRT-LM) cannot be redirected, so its facade calls [preload]
 *   first: a plain `dlopen` from the always-shipped engine library puts it in
 *   the class loader's namespace under its soname, where the dependency's own
 *   call finds it.
 *
 * Nothing here runs on a build without the pin table, which is every Play
 * build and every build that bundles its libraries.
 */
object NativeDelivery {
    private const val TAG = "NativeDelivery"

    /** The libraries [preload] also hands to `System.load`: checked to carry no JNI_OnLoad. */
    private val REGISTERED_WITH_ART = setOf("tensorflowlite_jni", "litertlm_jni")

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    private var store: NativeStore? = null

    @Volatile
    private var pins: List<NativePin> = emptyList()

    /** Every gate this build delivers, by name. */
    private val gates = HashMap<String, DownloadedModuleGate>()

    @Volatile
    private var dlopen: ((String) -> String?)? = null

    private val preloaded = HashSet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** This process's ABI, in the jniLibs spelling the pins use. */
    @Volatile
    var abi: String = ""
        private set

    /**
     * Reads the pin table, if this build has one, and installs the gates.
     * [needOptIn] is true on F-Droid. [dlopen] is the engine library's plain
     * `dlopen`, handed in from the module above this one.
     */
    fun install(context: Context, needOptIn: Boolean, dlopen: (String) -> String?) {
        val app = context.applicationContext
        // Licences are agreed to on every build, pins or not: FUTO Swipe's
        // model weights download on a bundled build too.
        NativeConsent.attach(app, needOptIn)
        val text = runCatching { app.assets.open(NativePins.ASSET).bufferedReader().use { it.readText() } }.getOrNull()
            ?: return
        abi = processAbi(app)
        val mine = NativePins.parse(text).filter { it.abi == abi }
        if (mine.isEmpty()) return
        val nativeStore = NativeStore(File(app.noBackupFilesDir, "natives"))
        store = nativeStore
        pins = mine
        this.dlopen = dlopen
        synchronized(gates) {
            for ((name, gatePins) in mine.groupBy { it.gate }) {
                gates[name] = DownloadedModuleGate(name, gatePins, nativeStore, scope)
            }
        }
        // Every FeatureModules gate the pins name. The others (llm and
        // translate, whose facades keep their own gate type, and the NPU's
        // per-chip gates) are read through [gate].
        for (name in FeatureModules.names) gate(name)?.let { FeatureModules.set(name, it) }
        FeatureModules.loadLibrary = { name ->
            val file = fileFor(name)
            if (file != null) System.load(file.path) else System.loadLibrary(name)
        }
        active = true
        // A library a previous build pinned and this one does not is dead weight.
        scope.launch { runCatching { nativeStore.prune(mine) } }
    }

    /** The gate named [name], for the facades that keep their own gate type (LLM, translate). */
    fun gate(name: String): ModuleGate? = synchronized(gates) { gates[name] }

    /** Every pin of this build for this ABI, for import and the storage screen. */
    fun pins(): List<NativePin> = pins

    fun store(): NativeStore? = store

    /**
     * The downloaded file of `lib<name>.so`, or null when this build ships it
     * (or has not got it yet).
     */
    fun fileFor(name: String): File? {
        val nativeStore = store ?: return null
        val pin = pins.firstOrNull { it.name == "lib$name.so" } ?: return null
        return nativeStore.fileOf(pin).takeIf { nativeStore.installed(pin) }
    }

    /**
     * Loads `lib<name>.so` into the class loader's namespace with a plain
     * `dlopen`, so the dependency that calls `System.loadLibrary(name)` finds
     * it. True when it is loaded or the APK ships it; false when this build
     * delivers it and it is not here yet, or the linker refused it.
     */
    fun preload(name: String): Boolean {
        if (!active || pins.none { it.name == "lib$name.so" }) return true
        synchronized(preloaded) {
            if (name in preloaded) return true
            val file = fileFor(name) ?: return false
            val error = dlopen?.invoke(file.path)
            if (error != null) {
                DebugLog.w(TAG, "dlopen lib$name.so failed: $error")
                return false
            }
            // Registered with ART as well, so the dependency's native methods
            // resolve whatever its own System.loadLibrary finds. The class
            // loader's search also walks /system/lib64, and a phone that ships
            // a libtensorflowlite_jni.so of its own (Samsung) hands LiteRT that
            // path, which the app may not open: the soname lookup the dlopen
            // above sets up is never asked, and LiteRT failed to load (#994).
            // Only libraries with no JNI_OnLoad, which a second load by another
            // name would run twice.
            if (name in REGISTERED_WITH_ART) {
                runCatching { System.load(file.path) }
                    .onFailure { DebugLog.w(TAG, "System.load lib$name.so failed: $it") }
            }
            preloaded += name
            return true
        }
    }

    /**
     * A directory holding every library of [gates] under its own name, for a
     * runtime that is handed a directory rather than files (LiteRT's NPU
     * dispatch, and the DSP side of QNN, which reads its skel by name from a
     * search path). Links into the store, or copies where links are refused.
     * Null until every library of those gates is installed.
     */
    fun directoryFor(gates: List<String>): File? {
        val nativeStore = store ?: return null
        val gatePins = pins.filter { it.gate in gates }
        if (gatePins.isEmpty() || !gatePins.all(nativeStore::installed)) return null
        val dir = File(nativeStore.root, "dirs/${gates.joinToString("+")}")
        dir.mkdirs()
        for (pin in gatePins) {
            val target = nativeStore.fileOf(pin)
            val link = File(dir, pin.name)
            if (link.exists() && runCatching { link.canonicalPath == target.canonicalPath }.getOrDefault(false)) continue
            link.delete()
            val linked = runCatching { Os.symlink(target.path, link.path) }.isSuccess
            if (!linked) target.copyTo(link, overwrite = true)
        }
        return dir
    }

    /**
     * Installs [file] when its hash is one of this build's pins, for an
     * import with no network. Returns the pin it matched, or null.
     */
    fun import(file: File): NativePin? {
        val nativeStore = store ?: return null
        val pin = nativeStore.pinFor(file, pins) ?: return null
        if (!nativeStore.install(pin, file, keepSource = true)) return null
        gate(pin.gate)?.let { (it as? DownloadedModuleGate)?.refresh() }
        return pin
    }

    /**
     * Installs every library of this build that [archive] holds at its
     * pinned entry: an Android library (`.aar`) from the vendor's Maven
     * repository, the file a [NativeSource.Maven] pin is read out of. Each
     * entry found is reported with whether it matched its pin; a library the
     * archive does not hold is left out.
     */
    fun importArchive(archive: File): Map<NativePin, Boolean> {
        val nativeStore = store ?: return emptyMap()
        val result = nativeStore.installFromArchive(archive, pins)
        result.keys.mapTo(HashSet()) { it.gate }.forEach { name -> (gate(name) as? DownloadedModuleGate)?.refresh() }
        return result
    }

    /**
     * Re-reads every gate, after the user turns on [NativeConsent.DOWNLOADS]:
     * a gate that stopped at the opt-in keeps saying so until something asks
     * it again, so a feature's screen would go on showing the opt-in banner
     * instead of its own Download button.
     */
    fun refreshAll() {
        val all = synchronized(gates) { gates.values.toList() }
        all.forEach(DownloadedModuleGate::refresh)
    }

    /** The pins of [gates] that are not on the phone yet. */
    fun missing(gates: Collection<String>): List<NativePin> {
        val nativeStore = store ?: return emptyList()
        return pins.filter { it.gate in gates && !nativeStore.installed(it) }
    }

    /**
     * Where [pin] is published, for fetching it on another device: the data
     * repository's release asset, or the vendor's `.aar` that holds it.
     */
    fun publishedUrl(pin: NativePin): String? = when (val source = pin.source) {
        NativeSource.Data -> NativeDownloader.dataUrl(pin)
        is NativeSource.Maven -> source.url
    }

    /** The name [pin] is published under: `<sha256>-<name>`, or the `.aar` it is inside. */
    fun publishedName(pin: NativePin): String = when (val source = pin.source) {
        NativeSource.Data -> "${pin.sha256}-${pin.name}"
        is NativeSource.Maven -> source.url.substringAfterLast('/')
    }

    /**
     * `arm64` → `arm64-v8a`: the ABI this process loads its own libraries for.
     * The directory is unset under Robolectric, which then reads as the first
     * supported ABI.
     */
    private fun processAbi(context: Context): String =
        when (context.applicationInfo.nativeLibraryDir?.let { File(it).name }) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        }
}

/**
 * A [ModuleGate] whose module is a set of [NativePin]s downloaded into a
 * [NativeStore]. Installed when every pin is; [requestInstall] fetches the
 * missing ones in order and reports their combined bytes, or stops at the
 * first agreement [NativeConsent] says the user has not given yet.
 */
class DownloadedModuleGate(
    val name: String,
    private val pins: List<NativePin>,
    private val store: NativeStore,
    private val scope: CoroutineScope,
) : ModuleGate {

    private val mutable = MutableStateFlow<ModuleState>(current())

    override val state: StateFlow<ModuleState> = mutable.asStateFlow()

    override val onDemand: Boolean get() = true

    /** Bytes still to fetch. */
    val missingBytes: Long get() = pins.filterNot(store::installed).sumOf { it.size }

    override val bytesToFetch: Long get() = missingBytes

    /** The licences this gate's downloads need, if any. */
    val licences: List<String> get() = pins.mapNotNull { it.licence }.distinct()

    @Volatile private var job: Job? = null

    private fun current(): ModuleState = when {
        pins.all(store::installed) -> ModuleState.Installed
        // The offline build: nothing to fetch with, so say so from the start
        // rather than offer a download that cannot work.
        !InternetPermission.granted -> ModuleState.Missing(failed = true, consent = NativeConsent.IMPORT)
        else -> ModuleState.Missing()
    }

    /** Re-reads the store, after an import. */
    fun refresh() {
        if (job?.isActive != true) mutable.value = current()
    }

    @Synchronized
    override fun requestInstall() {
        if (pins.all(store::installed)) {
            mutable.value = ModuleState.Installed
            return
        }
        if (job?.isActive == true) return
        if (!InternetPermission.granted) {
            mutable.value = current()
            return
        }
        NativeConsent.missingFor(pins)?.let { needed ->
            mutable.value = ModuleState.Missing(failed = true, consent = needed)
            return
        }
        val todo = pins.filterNot(store::installed)
        val total = todo.sumOf { it.size }
        mutable.value = ModuleState.Installing(0, total)
        job = scope.launch {
            var done = 0L
            mutable.value = try {
                for (pin in todo) {
                    NativeDownloader.fetch(pin, store) { bytes -> mutable.value = ModuleState.Installing(done + bytes, total) }
                    done += pin.size
                }
                ModuleState.Installed
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                DebugLog.w("NativeDelivery", "fetching $name failed: ${t.javaClass.simpleName}: ${t.message}")
                ModuleState.Missing(failed = true)
            }
        }
    }
}
