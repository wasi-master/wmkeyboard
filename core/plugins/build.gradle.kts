plugins {
    alias(libs.plugins.android.library)
    id("wmkeyboard.detekt")
    alias(libs.plugins.kotlin.compose)
    id("wmkeyboard.compose-metrics")
    alias(libs.plugins.kotlin.serialization)
    // libwm_python sits in src/plugins/jniLibs, the `plugins` gate of native/gates.json.
    id("wmkeyboard.native-libs")
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
    namespace = "com.wasimaster.wmkeyboard.plugins"
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

    testImplementation(libs.junit)
}

// Plugins run in libwm_python (native/wm-python-jni over native/wm-python and
// native/wm-python-tooling). A JVM unit test has no APK, so every test task
// that runs a plugin (this module's, :app's and :feature:ime's) first builds
// the library for this machine, which PythonNative finds under
// native/wm-python-jni/target. Same arrangement as :core:language's
// buildHostEngine.
val buildHostPython by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libwm_python for this machine, so JVM unit tests can run plugins."
    val native = rootProject.layout.projectDirectory.dir("native")
    inputs.dir(native.dir("wm-python/src"))
    inputs.dir(native.dir("wm-python-tooling/src"))
    inputs.dir(native.dir("wm-python-jni/src"))
    inputs.files(
        native.file("wm-python/Cargo.toml"),
        native.file("wm-python-tooling/Cargo.toml"),
        native.file("wm-python-jni/Cargo.toml"),
        native.file("wm-python-jni/Cargo.lock"),
        native.file("wm-python-jni/build-host.sh"),
    )
    outputs.files(
        listOf("libwm_python.dylib", "libwm_python.so", "wm_python.dll")
            .map { native.file("wm-python-jni/target/release/$it") },
    )
    val cargoBin = providers.environmentVariable("CARGO_HOME").map { "$it/bin" }
        .orElse(providers.systemProperty("user.home").map { "$it/.cargo/bin" })
    val path = providers.environmentVariable("PATH").orElse("")
    environment("PATH", listOf(cargoBin.get(), path.get()).joinToString(File.pathSeparator))
    workingDir(native.dir("wm-python-jni"))
    commandLine("bash", "build-host.sh")
    doFirst {
        val found = environment["PATH"].toString().split(File.pathSeparator).any { File(it, "cargo").canExecute() }
        check(found) {
            "cargo is not on the PATH, and the unit tests need libwm_python built for this machine. " +
                "Install Rust from https://rustup.rs, or build it elsewhere and run the tests with " +
                "-Dwmkeyboard.python.lib=<path to libwm_python>."
        }
    }
}

tasks.withType<Test>().configureEach { dependsOn(buildHostPython) }
