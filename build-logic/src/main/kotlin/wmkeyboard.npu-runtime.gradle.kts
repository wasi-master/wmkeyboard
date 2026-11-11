import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// Packages the NPU runtime behind Compute = NPU for on-device AI (#804) as
// assets: LiteRT's dispatch libraries from
// com.google.ai.edge.litert:litert-npu-runtime-{qualcomm,mediatek,google-tensor},
// and from Qualcomm's com.qualcomm.qti:qnn-runtime only the files the
// Snapdragons in NpuSupport need. The MediaTek and Tensor dispatch libraries
// (Apache-2.0, about 0.3 and 0.6 MB) drive the phone's own vendor drivers, so
// nothing else comes with them.
// Never the whole QNN AAR, which is 75 MB of every Hexagon generation, and
// never libQnnHtpPrepare.so (88 MB, compiles models on the phone; every
// catalog NPU build arrives compiled). Applied by :core:intelligence outside
// Play and by the on-demand :feature:npu split on Play, and only where the
// wmkb.enableNpu flag allows it: the QNN libraries are Qualcomm's licence, so
// never in F-Droid.
//
// The AARs are resolved as files from their own configuration, never as
// dependencies: as dependencies AGP would package all of their native
// libraries uncompressed.

// Keep in step with NpuSupport.runtimeLibraries in :core:intelligence.
val npuLibraries = listOf(
    "libLiteRtDispatch_Qualcomm.so",
    "libLiteRtDispatch_MediaTek.so",
    "libLiteRtDispatch_GoogleTensor.so",
    "libQnnSystem.so",
    "libQnnHtp.so",
    // SM8550, Snapdragon 8 Gen 2.
    "libQnnHtpV73Stub.so",
    "libQnnHtpV73Skel.so",
    // SM8650, Snapdragon 8 Gen 3.
    "libQnnHtpV75Stub.so",
    "libQnnHtpV75Skel.so",
    // SM8750, Snapdragon 8 Elite.
    "libQnnHtpV79Stub.so",
    "libQnnHtpV79Skel.so",
    // SM8850, Snapdragon 8 Elite Gen 5.
    "libQnnHtpV81Stub.so",
    "libQnnHtpV81Skel.so",
)

val npuRuntime = configurations.create("npuRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
for (alias in listOf(
    "litert-npu-runtime-qualcomm",
    "litert-npu-runtime-mediatek",
    "litert-npu-runtime-google-tensor",
    "qnn-runtime",
)) {
    val module = catalog.findLibrary(alias).get().get()
    dependencies.add(npuRuntime.name, "${module.module.group}:${module.module.name}:${module.versionConstraint.requiredVersion}@aar")
}

// One task per variant: AGP points each one's output at a directory of its
// own variant's, which one shared task could not have.
extensions.getByType(AndroidComponentsExtension::class.java).apply {
    onVariants(selector().all()) { variant ->
        val task = tasks.register(
            "npuRuntimeAssets${variant.name.replaceFirstChar { it.uppercase() }}",
            NpuRuntimeAssets::class.java,
        ) {
            archives.from(npuRuntime)
            libraries.set(npuLibraries)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, NpuRuntimeAssets::outputDir)
    }
}
