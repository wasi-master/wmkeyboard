import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute

// Native delivery for the builds Play Feature Delivery does not reach (GitHub,
// and F-Droid behind its opt-in): the native libraries behind optional
// features are left out of the APK, and `NativePinsTask` writes their sizes
// and SHA-256s into assets/native/pins.tsv, which NativeDelivery in
// :core:common reads at startup to fetch each one the first time its feature
// asks. Applied by :app only off Play and with the internet permission.
//
// What is delivered, and from where, is native/gates.json (NativeGates). The
// project's own libraries are pinned from the jniLibs the build would have
// packaged and published on the data repository's `natives` release.
// Dependencies' libraries are pinned from the AAR this build resolved and read
// out of that AAR on its own Maven repository: ML Kit's, LiteRT's and
// Qualcomm's terms do not allow mirroring them.

val table = NativeGates.of(project)

/** Our own libraries: name to gate. */
val ownSpecs: Map<String, String> = table.ownLibraries.associate { (gate, library) -> library.name to gate.id }

/** Where the build keeps them; F-Droid's recipe builds and scans the same paths. */
val ownDirs: List<String> = table.ownLibraries.map { (_, library) -> library.dir }.distinct()

/** A dependency's library: its gate, the module whose AAR holds it, a licence. */
data class Dep(val gate: String, val module: String, val licence: String = "")

// Google's SDKs are not in F-Droid's build, so neither are their pins; a gate
// switched off (the NPU, wmkb.enableNpu=false) pins nothing either. The NPU
// runtime keeps in step with NpuSupport.runtimeLibraries: arm64 only, the
// chips it lists, one gate per Hexagon generation so a phone fetches its own
// chip's pair. ONNX Runtime is MIT and in every build, read out of its own
// AAR on Maven Central, so it needs no licence dialog; the
// wmkeyboard.onnxruntime convention packages it into :core:voice and the
// exclude below takes it out again.
val fdroidChannel = (wmkbFlag("wmkb.enableFdroid", "WMKB_ENABLE_FDROID") ?: "false").toBoolean()
val pinnedGates = table.gates.filter { !(it.google && fdroidChannel) && it.enabled(project) }
val dependencySpecs: Map<String, Dep> = pinnedGates.flatMap { gate ->
    gate.maven.map { it.name to Dep(it.gate, it.module, it.licence) }
}.toMap()

val pinAbis = table.abis

// None of them goes into the APK. Our own libraries reach :app from the core
// modules' jniLibs, the dependencies' from their AARs; one exclude list covers
// both, in every channel, and the NPU runtime is never packaged here at all.
extensions.getByType(ApplicationExtension::class.java).packaging.jniLibs.excludes +=
    (ownSpecs.keys + table.gates.filterNot { it.neverPackaged }.flatMap { gate -> gate.maven.map { it.name } }).map { "**/$it" }

// The NPU runtime is not a dependency of the app, so its two AARs are resolved
// here as files, the way the wmkeyboard.npu-runtime convention takes them.
// Every library with a catalog alias in the table is resolved this way.
val npuAars = configurations.create("nativePinsNpu") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
// ONNX Runtime is not a dependency either; its AAR rides the same
// configuration, so one artifact view covers both.
run {
    val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    val aliases = pinnedGates.flatMap { gate -> gate.maven.mapNotNull { it.catalog } }.distinct()
    for (alias in aliases) {
        val module = catalog.findLibrary(alias).get().get()
        dependencies.add(npuAars.name, "${module.module.group}:${module.module.name}:${module.versionConstraint.requiredVersion}@aar")
    }
}

val artifactType = Attribute.of("artifactType", String::class.java)

extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
    val task = tasks.register(
        "nativePins${variant.name.replaceFirstChar { it.uppercase() }}",
        NativePinsTask::class.java,
    ) {
        val own = ArrayList<String>()
        for (dir in ownDirs) {
            for (abi in pinAbis) {
                val abiDir = rootProject.file("$dir/$abi")
                for (file in abiDir.listFiles().orEmpty().sortedBy { it.name }) {
                    val gate = ownSpecs[file.name] ?: continue
                    own += listOf(gate, abi, file.name, file.path).joinToString("\t")
                    ownFiles.from(file)
                }
            }
        }
        ownLibraries.set(own)
        // Prebuilt libraries F-Droid deletes but may fetch (Whistle): pinned
        // from the table there, checked against the committed file elsewhere.
        fdroidPins.set(
            table.ownLibraries.mapNotNull { (_, library) -> library.fdroidPin?.let { library to it } }
                .flatMap { (library, pin) ->
                    pin.byAbi.filterKeys { it in pinAbis }.map { (abi, value) ->
                        listOf(pin.gate, abi, library.name, value.first, value.second.toString()).joinToString("\t")
                    }
                },
        )
        fdroid.set(fdroidChannel)
        abis.set(pinAbis)
        mavenLibraries.set(dependencySpecs.map { (name, dep) -> listOf(name, dep.gate, dep.module, dep.licence).joinToString("\t") })
        // The raw AARs of this variant's own runtime classpath: the exact
        // versions whose Java code the APK carries, so the pinned library is
        // the one that code expects.
        val view = variant.runtimeConfiguration.incoming.artifactView {
            attributes { attribute(artifactType, "aar") }
            lenient(true)
            componentFilter { it is ModuleComponentIdentifier }
        }
        val npuView = npuAars.incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier }
        }
        aars.from(view.files, npuView.files)
        aarCoordinates.set(
            view.artifacts.resolvedArtifacts.zip(npuView.artifacts.resolvedArtifacts) { a, b ->
                (a + b).mapNotNull { artifact ->
                    val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
                    "${artifact.file.path}\t${id.group}:${id.module}:${id.version}"
                }
            },
        )
        outputDir.set(layout.buildDirectory.dir("generated/nativePins/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(task, NativePinsTask::outputDir)
}
