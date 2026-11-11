plugins {
    alias(libs.plugins.android.library)
    id("wmkeyboard.detekt")
    alias(libs.plugins.kotlin.compose)
    id("wmkeyboard.compose-metrics")
    alias(libs.plugins.kotlin.serialization)
    // Applied at the root with their versions; see build.gradle.kts there.
    id("org.jetbrains.kotlinx.kover")
    id("com.autonomousapps.dependency-analysis")
}

// Coverage for the root's merged report (`./gradlew koverHtmlReportUnit`):
// this module's debug unit tests.
kover {
    currentProject {
        createVariant("unit") { add("debug") }
    }
}

android {
    namespace = "com.wasimaster.wmkeyboard.language"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        extraWarnings.set(true)
        freeCompilerArgs.addAll("-Xreport-all-warnings")
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

// The text engines in this module's transliteration package (AvroPhonetic,
// Khipro, BijoyAnsi, the Indic engine and the rest) are Rust, in
// native/wm-engine, reached through the JNI library native/wm-engine-jni
// builds; the Kotlin objects are facades over EngineNative. The APK carries
// the prebuilt .so files committed under src/main/jniLibs, and F-Droid
// rebuilds them from source. A JVM unit test has no APK, so every test task
// that reaches the engine (this module's, and :app's) first builds the
// library for this machine, which EngineNative finds under
// native/wm-engine-jni/target. cargo keeps its own incremental state, so a
// run with nothing changed costs well under a second. Needs cargo
// (https://rustup.rs); CARGO_HOME/bin goes on the PATH for a Gradle daemon
// started from a shell without it.
val buildHostEngine by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libwm_engine_jni for this machine, so JVM unit tests can load the Rust text engines."
    val native = rootProject.layout.projectDirectory.dir("native")
    inputs.dir(native.dir("wm-engine/src"))
    inputs.dir(native.dir("wm-engine/data"))
    inputs.dir(native.dir("wm-engine-jni/src"))
    // Compiled in with include_str!, so a change there is a change to the library.
    inputs.dir(rootProject.layout.projectDirectory.dir("app/src/main/assets/avro"))
    inputs.files(
        native.file("wm-engine/Cargo.toml"),
        native.file("wm-engine/build.rs"),
        native.file("wm-engine-jni/Cargo.toml"),
        native.file("wm-engine-jni/Cargo.lock"),
        native.file("wm-engine-jni/build-host.sh"),
    )
    outputs.files(
        listOf("libwm_engine_jni.dylib", "libwm_engine_jni.so", "wm_engine_jni.dll")
            .map { native.file("wm-engine-jni/target/release/$it") },
    )
    val cargoBin = providers.environmentVariable("CARGO_HOME").map { "$it/bin" }
        .orElse(providers.systemProperty("user.home").map { "$it/.cargo/bin" })
    val path = providers.environmentVariable("PATH").orElse("")
    environment("PATH", listOf(cargoBin.get(), path.get()).joinToString(File.pathSeparator))
    workingDir(native.dir("wm-engine-jni"))
    commandLine("bash", "build-host.sh")
    doFirst {
        // Windows names the binary cargo.exe and has no execute bit to read (#1193).
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val names = if (windows) listOf("cargo.exe", "cargo.cmd", "cargo") else listOf("cargo")
        val found = environment["PATH"].toString().split(File.pathSeparator).any { dir ->
            names.any { name -> File(dir, name).let { it.isFile && (windows || it.canExecute()) } }
        }
        check(found) {
            "cargo is not on the PATH, and the unit tests need libwm_engine_jni built for this machine. " +
                "Install Rust from https://rustup.rs, or build it elsewhere and run the tests with " +
                "-Dwmkeyboard.engine.lib=<path to libwm_engine_jni>."
        }
    }
}

tasks.withType<Test>().configureEach { dependsOn(buildHostEngine) }

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    // FUTO Keyboard's layouts are YAML. The same parser `:core:content` uses
    // for Espanso snippets, so this adds a dependency edge and not a library.
    implementation(libs.snakeyaml)

    testImplementation(libs.junit)
}
