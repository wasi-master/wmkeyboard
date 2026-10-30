import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Copies `libonnxruntime.so` for each ABI out of Microsoft's
 * onnxruntime-android AAR into a jniLibs directory (`<abi>/libonnxruntime.so`),
 * for the `wmkeyboard.onnxruntime` convention. Only the C runtime: the AAR's
 * Java API and its JNI library (`libonnxruntime4j_jni.so`) stay out, since
 * libwmsherpa calls the runtime's C API directly.
 *
 * Fails when an ABI's library is missing, so a version that drops one breaks
 * the build instead of shipping a Sherpa that cannot load. The `webrtc` gate's
 * split takes its one library out of its own AAR the same way ([library]).
 */
abstract class OnnxRuntimeLibs : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archives: ConfigurableFileCollection

    /** ABIs to copy, in the jniLibs spelling. */
    @get:Input
    abstract val abis: ListProperty<String>

    /** The file to copy; ONNX Runtime's when unset. */
    @get:Input
    @get:Optional
    abstract val library: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun unpack() {
        val wanted = abis.get()
        val name = library.getOrElse("libonnxruntime.so")
        fileSystemOperations.sync {
            for (archive in archives.files) {
                from(archiveOperations.zipTree(archive)) {
                    include(wanted.map { "jni/$it/$name" })
                    eachFile { path = path.removePrefix("jni/") }
                }
            }
            includeEmptyDirs = false
            into(outputDir)
        }
        val out = outputDir.get().asFile
        val missing = wanted.filterNot { out.resolve("$it/$name").isFile }
        check(missing.isEmpty()) { "$name missing for $missing in ${archives.files.map { it.name }}" }
    }
}
