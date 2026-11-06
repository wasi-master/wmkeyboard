package com.wasimaster.wmkeyboard.core.modules

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Where one of the app's on-demand modules stands on this install.
 *
 * Play builds carry some native runtimes as Play Feature Delivery modules,
 * fetched the first time a feature needs them; every other build compiles
 * the same code into the base APK. This is the state a facade in a core
 * module reads before it reaches for its runtime, and what a settings screen
 * or panel shows while the module is on its way. The same shape as
 * `TranslateModuleState`, which predates it.
 */
sealed interface ModuleState {

    /** The runtime is here: always, outside Play; after the download, on Play. */
    data object Installed : ModuleState

    /**
     * Not here. [failed] when the last attempt to fetch it went wrong.
     * [consent] names what the user has to agree to first, when that is why
     * it is missing: [NativeConsent.DOWNLOADS] on an F-Droid build that has
     * not opted in, a licence id, or [NativeConsent.IMPORT] on the offline
     * build, which can only import it. Such a state counts as [failed], so a
     * caller waiting for the module stops waiting.
     *
     * [confirm] when Play holds the download until the user confirms it: an
     * install Play did not make, or a large download on mobile data. Play
     * shows that question only from an app screen, never from a keyboard, so
     * the settings app asks it the next time it comes to the front.
     */
    data class Missing(
        val failed: Boolean = false,
        val consent: String? = null,
        val confirm: Boolean = false,
    ) : ModuleState

    /** On its way from Play. Both figures are 0 before Play reports any. */
    data class Installing(val bytes: Long = 0, val totalBytes: Long = 0) : ModuleState
}

/**
 * Whether a module's home is present on this install, and how to fetch it
 * when it is not. Play builds swap a SplitInstall-backed gate into
 * `FeatureModules` at startup (see `installOnDemandDelivery` in `:app`);
 * everywhere else the default [AlwaysInstalled] is the truth.
 */
interface ModuleGate {
    val state: StateFlow<ModuleState>

    /** Idempotent; safe to call while a download is already running. */
    fun requestInstall()

    val installed: Boolean get() = state.value == ModuleState.Installed

    /**
     * Whether this gate is backed by a Play module at all. True on Play even
     * once the module is installed; false wherever the runtime is compiled
     * into the base APK. Lets a facade say "this build has it" without
     * loading anything, before Play has fetched it.
     */
    val onDemand: Boolean get() = false

    /**
     * What fetching this module would still download, in bytes: what a
     * feature adds to its own model's size when it offers a download that
     * has to bring the library first. 0 when the module is here, and where
     * the size is not known before the download starts (Play publishes none).
     */
    val bytesToFetch: Long get() = 0L
}

/** The gate of every channel that compiles its runtimes into the base APK. */
object AlwaysInstalled : ModuleGate {
    override val state: StateFlow<ModuleState> = MutableStateFlow(ModuleState.Installed)

    override fun requestInstall() = Unit
}

/**
 * The gate of a runtime this build does not carry at all: the ML Kit and
 * LiteRT ones in F-Droid's build, which may hold no Google artifact. Missing
 * and failed, so nothing waits for it, and there is nothing to fetch.
 */
object NotInBuild : ModuleGate {
    override val state: StateFlow<ModuleState> = MutableStateFlow(ModuleState.Missing(failed = true))

    override fun requestInstall() = Unit
}

/**
 * How the modules' code reaches what a module holds. `FeatureModules`, the
 * object with one gate per module, is generated from native/gates.json into
 * this package (the wmkeyboard.feature-modules convention) and forwards its
 * [loadLibrary], [awaitInstalled] and [load] here.
 */
internal object ModuleLoading {

    /**
     * How a native library is loaded. `System.loadLibrary` wherever the
     * library is in the base APK. Play swaps in SplitInstallHelper's, which
     * also finds a library in a split installed after this process started,
     * where the class loader's own search path does not reach yet.
     */
    @Volatile
    var loadLibrary: (String) -> Unit = System::loadLibrary

    /**
     * Asks for the module and waits until it is here, reporting Play's byte
     * counts on the way. False when the fetch failed; the caller decides what
     * that means to the user. A cancelled caller leaves the download running:
     * Play owns it, and the next caller finds it further along.
     */
    suspend fun awaitInstalled(gate: ModuleGate, onProgress: (bytes: Long, totalBytes: Long) -> Unit): Boolean {
        if (gate.installed) return true
        gate.requestInstall()
        val settled = gate.state.first { current ->
            if (current is ModuleState.Installing) onProgress(current.bytes, current.totalBytes)
            current == ModuleState.Installed || (current is ModuleState.Missing && current.failed)
        }
        return settled == ModuleState.Installed
    }

    /**
     * Builds a bridge class by name. Throws when it is not loadable, which on
     * Play means the split is installed but this process cannot see it yet.
     * [loader] must be the app's class loader: SplitCompat patches that one,
     * and a library's own would miss the split.
     */
    fun <T> load(className: String, loader: ClassLoader): T {
        @Suppress("UNCHECKED_CAST")
        return Class.forName(className, true, loader).getDeclaredConstructor().newInstance() as T
    }
}

/**
 * A native library that lives in [gate]'s module on Play and in the base APK
 * everywhere else. Nothing is loaded until [available] is read, and on Play
 * not before the module is installed, so a read while it is missing stays
 * retryable instead of caching the miss for the life of the process.
 */
class GatedLibrary(private val gate: () -> ModuleGate, private val load: () -> Boolean) {

    @Volatile private var loaded = false

    /** Set once a load was tried with the module present and failed: final. */
    @Volatile private var absent = false

    /** Whether the library is loaded now; tries to load it if it can. */
    val available: Boolean
        get() {
            if (loaded) return true
            if (absent || !gate().installed) return false
            synchronized(this) {
                if (!loaded && !absent) {
                    if (load()) loaded = true else absent = true
                }
            }
            return loaded
        }

    /**
     * Whether this build has the library, counting a Play module not fetched
     * yet. Loads nothing while the module is missing; outside Play it is
     * [available].
     */
    val shipped: Boolean get() = (gate().onDemand && !absent && !gate().installed) || available
}
