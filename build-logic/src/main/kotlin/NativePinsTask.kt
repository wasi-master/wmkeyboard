import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes `native/pins.tsv` for the `wmkeyboard.native-pins` convention: one
 * line per native library this build leaves out of its APK, with the size
 * and SHA-256 the app checks a download or an import against. The format is
 * the one `NativePins` in :core:common reads.
 *
 * Two kinds of line:
 * - the project's own libraries, hashed from the jniLibs files the build
 *   would otherwise have packaged, served from the data repository's
 *   `natives` release (`data`);
 * - a dependency's library, hashed from the entry in the very AAR the build
 *   resolved, served from that AAR on its own Maven repository (`maven`).
 *
 * Fails when a library it was told to pin is in none of its inputs, so a
 * version bump that renames or drops one breaks the build instead of
 * shipping a feature that can never load.
 */
abstract class NativePinsTask : DefaultTask() {

    /**
     * `gate\tabi\tname\tsha256\tsize` for each prebuilt library with an
     * F-Droid pin (`fdroidPin` in native/gates.json). A file of that name and
     * ABI among [ownLibraries] must hash to it; an absent one (F-Droid's
     * build, which deletes it) is pinned from it when [fdroid] is set.
     */
    @get:Input
    abstract val fdroidPins: ListProperty<String>

    @get:Input
    abstract val fdroid: org.gradle.api.provider.Property<Boolean>

    /** `gate\tabi\tname\tpath` for each of the project's own libraries. */
    @get:Input
    abstract val ownLibraries: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val ownFiles: ConfigurableFileCollection

    /** `name\tgate\tgroup:module\tlicence` for each dependency library to pin. */
    @get:Input
    abstract val mavenLibraries: ListProperty<String>

    /** `path\tgroup:module:version` for every AAR the libraries may be in. */
    @get:Input
    abstract val aarCoordinates: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val aars: ConfigurableFileCollection

    /** ABIs to pin dependency libraries for. */
    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val lines = ArrayList<String>()
        val pinned = HashSet<String>()
        val ownShas = HashMap<String, String>()
        for (entry in ownLibraries.get()) {
            val (gate, abi, name, path) = entry.split('\t')
            val file = File(path)
            val sha = sha256(file.readBytes())
            lines += line(gate, abi, name, sha, file.length(), "data", "")
            pinned += name
            ownShas["$abi/$name"] = sha
        }
        for (entry in fdroidPins.get()) {
            val (gate, abi, name, sha, size) = entry.split('\t')
            val present = ownShas["$abi/$name"]
            if (present != null) {
                check(present == sha) {
                    "native pins: $abi/$name is $present, but its fdroidPin in native/gates.json says $sha. " +
                        "Update the pin (and the size) after rebuilding the library."
                }
            } else if (fdroid.get()) {
                lines += line(gate, abi, name, sha, size.toLong(), "data", "")
            }
        }
        val wanted = mavenLibraries.get().map { it.split('\t') }
        val byModule = wanted.groupBy { it[2] }
        for (entry in aarCoordinates.get()) {
            val (path, coordinates) = entry.split('\t')
            val module = coordinates.substringBeforeLast(':')
            val libs = byModule[module] ?: continue
            ZipFile(File(path)).use { zip ->
                for (lib in libs) {
                    val (name, gate, _, licence) = lib
                    for (abi in abis.get()) {
                        val zipEntry = zip.getEntry("jni/$abi/$name") ?: continue
                        val bytes = zip.getInputStream(zipEntry).use { it.readBytes() }
                        val source = "maven|${repositoryFor(module)}|$coordinates|jni/$abi/$name"
                        lines += line(gate, abi, name, sha256(bytes), bytes.size.toLong(), source, licence)
                        pinned += name
                    }
                }
            }
        }
        val missing = wanted.map { it[0] }.filterNot { it in pinned }
        check(missing.isEmpty()) { "native pins: no library found for $missing" }
        val out = outputDir.get().asFile.resolve("native")
        out.mkdirs()
        out.resolve("pins.tsv").writeText(
            "# gate\tabi\tname\tsha256\tsize\tsource\tlicence\n" + lines.sorted().joinToString("\n") + "\n",
        )
    }

    private fun line(gate: String, abi: String, name: String, sha: String, size: Long, source: String, licence: String) =
        listOf(gate, abi, name, sha, size.toString(), source, licence).joinToString("\t")

    // Google's own artifacts are on Google Maven; everyone else's (Qualcomm,
    // Microsoft, Alpha Cephei, the WebRTC SDK) on Maven Central.
    private fun repositoryFor(module: String): String =
        if (module.startsWith("com.google.") || module.startsWith("androidx.")) GOOGLE_MAVEN else MAVEN_CENTRAL

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val GOOGLE_MAVEN = "https://dl.google.com/android/maven2"
        const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
    }
}
