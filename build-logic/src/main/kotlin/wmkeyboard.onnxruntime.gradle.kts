import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// Packages Microsoft's ONNX Runtime (libonnxruntime.so, the C runtime only)
// as native libraries of the module that applies it: :core:voice outside Play,
// beside libwmsherpa from src/onnx/jniLibs, and the on-demand :feature:onnx
// split on Play. A build with native delivery on (wmkeyboard.native-pins)
// leaves the file out of the APK again and pins it instead, read on the phone
// out of this same AAR on Maven Central; only a build that bundles its
// libraries (the offline build) ships it.
//
// The AAR is resolved as a file from its own configuration, never as a
// dependency: as one, AGP would also package its Java API and its own JNI
// library, which nothing here uses.

val onnxRuntime = configurations.create("onnxRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
catalog.findLibrary("onnxruntime-android").get().get().let { module ->
    dependencies.add(onnxRuntime.name, "${module.module.group}:${module.module.name}:${module.versionConstraint.requiredVersion}@aar")
}

// The ABIs the app ships; the AAR's x86 and x86_64 go nowhere.
val onnxAbis = listOf("arm64-v8a", "armeabi-v7a")

// One task per variant: AGP points each one's output at a directory of its
// own variant's, which one shared task could not have.
extensions.getByType(AndroidComponentsExtension::class.java).apply {
    onVariants(selector().all()) { variant ->
        val task = tasks.register(
            "onnxRuntimeLibs${variant.name.replaceFirstChar { it.uppercase() }}",
            OnnxRuntimeLibs::class.java,
        ) {
            archives.from(onnxRuntime)
            abis.set(onnxAbis)
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(task, OnnxRuntimeLibs::outputDir)
    }
}
