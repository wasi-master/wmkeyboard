import groovy.json.JsonSlurper
import java.io.File
import java.util.Properties
import org.gradle.api.Project

/**
 * `native/gates.json`, the one list of on-demand native gates, read by every
 * convention that wires one: `wmkeyboard.native-pins` (pins.tsv and the APK's
 * excludes), `wmkeyboard.native-libs` (the core modules' jniLibs per
 * channel), `wmkeyboard.native-split` (the library-only Play splits),
 * `wmkeyboard.feature-modules` (the generated `FeatureModules` object) and
 * `wmkeyboard.native-gates` (:app's dynamicFeatures and JNI keep rules).
 * settings.gradle.kts and native/gates.py read the same file on their own.
 */
class NativeGates(val abis: List<String>, val base: List<OwnLibrary>, val gates: List<Gate>) {

    /** A library the project builds itself, committed under [dir]/<abi>/. */
    data class OwnLibrary(
        val name: String,
        /** Relative to the repository root. */
        val dir: String,
        /** The script F-Droid's build runs to make it from source; null when it ships prebuilt. */
        val fdroidBuild: String?,
        /** Null for every ABI in [NativeGates.abis]. */
        val abis: List<String>?,
        /** JNI classes R8 must keep whole, for libraries that bind by mangled name. */
        val keep: List<String>,
        /**
         * A prebuilt library F-Droid's build cannot make (no [fdroidBuild])
         * but may fetch after the user's opt-in: the committed file's hash per
         * ABI, pinned under a gate of its own there, since F-Droid deletes the
         * file before the build can hash it. Every other build checks the
         * committed file against it, so a rebuilt library fails the build until
         * the pin is brought up to date.
         */
        val fdroidPin: FdroidPin? = null,
    )

    /** See [OwnLibrary.fdroidPin]. [byAbi] is ABI to SHA-256 and size. */
    data class FdroidPin(val gate: String, val byAbi: Map<String, Pair<String, Long>>)

    /** A library read out of a dependency's AAR on its own Maven repository. */
    data class MavenLibrary(
        val name: String,
        /** The pins.tsv gate: the owning gate's id unless split finer (npu-V79). */
        val gate: String,
        /** `group:module` of the AAR. */
        val module: String,
        /** Catalog alias, for an AAR that is not a dependency of the app and is resolved as a file. */
        val catalog: String?,
        val licence: String,
    )

    /**
     * When a gate exists in the build at all. A [flag] (the NPU) is read as a
     * Gradle property, a local.properties line or an environment variable;
     * a gate with no flag (Google Home) is on whenever its [requiresDir] is
     * there, since having gone to the trouble of unpacking an SDK is the
     * decision.
     */
    data class EnabledBy(
        val flag: String?,
        val env: String?,
        val default: Boolean,
        val buildConfig: String,
        val requiresDir: String?,
    )

    data class Gate(
        val id: String,
        /** The `FeatureModules` property; null for a gate with its own facade type (llm, translate). */
        val property: String?,
        val doc: String?,
        /** `split` (library only, wmkeyboard.native-split), `module` (its own build file) or null (no Play module). */
        val play: String?,
        /** Built on a Google SDK: not in the F-Droid build, and NotInBuild there. */
        val google: Boolean,
        val enabledBy: EnabledBy?,
        /** Its libraries never reach the APK, so there is nothing to exclude. */
        val neverPackaged: Boolean,
        val libraries: List<OwnLibrary>,
        val maven: List<MavenLibrary>,
    ) {
        val playModule: String? get() = play?.let { ":feature:$id" }

        /** The distinct jniLibs directories of this gate's own libraries, in table order. */
        val dirs: List<String> get() = libraries.map { it.dir }.distinct()

        fun enabled(project: Project): Boolean =
            enabled(project.rootDir) { by -> by.flag?.let { project.wmkbFlag(it, by.env.orEmpty()) } }

        fun enabled(rootDir: File, read: (EnabledBy) -> String?): Boolean {
            val by = enabledBy ?: return true
            if (by.requiresDir != null && !rootDir.resolve(by.requiresDir).isDirectory) return false
            if (by.flag == null) return true
            return (read(by) ?: by.default.toString()).toBoolean()
        }
    }

    fun gate(id: String): Gate = gates.firstOrNull { it.id == id } ?: error("native/gates.json has no gate `$id`")

    val ownLibraries: List<Pair<Gate, OwnLibrary>> get() = gates.flatMap { gate -> gate.libraries.map { gate to it } }

    companion object {
        const val PATH = "native/gates.json"

        fun read(file: File): NativeGates {
            @Suppress("UNCHECKED_CAST")
            val root = JsonSlurper().parse(file) as Map<String, Any?>
            return NativeGates(
                abis = strings(root["abis"]),
                base = maps(root["base"]).map(::ownLibrary),
                gates = maps(root["gates"]).map { gate ->
                    val id = gate["id"] as String
                    Gate(
                        id = id,
                        property = gate["property"] as String?,
                        doc = gate["doc"] as String?,
                        play = gate["play"] as String?,
                        google = gate["google"] as Boolean? ?: false,
                        enabledBy = (gate["enabledBy"] as Map<*, *>?)?.let {
                            EnabledBy(
                                flag = it["flag"] as String?,
                                env = it["env"] as String?,
                                default = it["default"] as Boolean? ?: false,
                                buildConfig = it["buildConfig"] as String,
                                requiresDir = it["requiresDir"] as String?,
                            )
                        },
                        neverPackaged = gate["neverPackaged"] as Boolean? ?: false,
                        libraries = maps(gate["libraries"]).map(::ownLibrary),
                        maven = maps(gate["maven"]).map {
                            MavenLibrary(
                                name = it["name"] as String,
                                gate = it["gate"] as String? ?: id,
                                module = it["module"] as String,
                                catalog = it["catalog"] as String?,
                                licence = it["licence"] as String? ?: "",
                            )
                        },
                    )
                },
            )
        }

        /** The table of the build [project] belongs to. */
        fun of(project: Project): NativeGates = read(project.rootDir.resolve(PATH))

        private fun ownLibrary(map: Map<*, *>) = OwnLibrary(
            name = map["name"] as String,
            dir = map["dir"] as String,
            fdroidBuild = map["fdroidBuild"] as String?,
            abis = (map["abis"] as List<*>?)?.map { it as String },
            keep = strings(map["keep"]),
            fdroidPin = (map["fdroidPin"] as Map<*, *>?)?.let { pin ->
                FdroidPin(
                    gate = pin["gate"] as String,
                    byAbi = pin.filterKeys { it != "gate" }.entries.associate { (abi, value) ->
                        val v = value as Map<*, *>
                        abi as String to ((v["sha256"] as String) to (v["size"] as Number).toLong())
                    },
                )
            },
        )

        private fun strings(value: Any?): List<String> = (value as List<*>?).orEmpty().map { it as String }

        private fun maps(value: Any?): List<Map<*, *>> = (value as List<*>?).orEmpty().map { it as Map<*, *> }
    }
}

/**
 * A `wmkb.*` flag the way every build file reads one: `-P`, then
 * local.properties, then the environment; null when none sets it.
 */
fun Project.wmkbFlag(name: String, env: String): String? {
    val local = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(local::load)
    return providers.gradleProperty(name).orNull ?: local.getProperty(name) ?: System.getenv(env)
}
