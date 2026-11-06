import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    // This module's jniLibs of on-demand gates, per channel (native/gates.json).
    id("wmkeyboard.native-libs")
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

// Same channel flag :app reads. It decides where the LiteRT interpreter that
// runs Whisper lives: non-Play builds compile src/whisperbridge (and the
// litert dependency) straight into this module; Play builds leave both out of
// the base APK — the on-demand :feature:litert module carries them instead,
// so the interpreter only reaches devices that use offline dictation. See
// WhisperRuntime.kt for the seam, and :core:intelligence for the same
// arrangement this one copies.
val playStoreChannel = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    (providers.gradleProperty("wmkb.enablePlayStore").orNull
        ?: localProperties.getProperty("wmkb.enablePlayStore")
        ?: System.getenv("WMKB_ENABLE_PLAY_STORE")
        ?: "false").toBoolean()
}

// F-Droid's build carries no Google artifact, so no LiteRT and no
// src/whisperbridge: WhisperEngine.AVAILABLE is false there. See the same
// flag in :core:intelligence.
val googleSdks = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    !(providers.gradleProperty("wmkb.enableFdroid").orNull
        ?: localProperties.getProperty("wmkb.enableFdroid")
        ?: System.getenv("WMKB_ENABLE_FDROID")
        ?: "false").toBoolean()
}

android {
    namespace = "com.wasimaster.wmkeyboard.voice"
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

// Offline Sherpa's libraries: libwmsherpa (native/sherpa-onnx-jni) from
// src/onnx/jniLibs, and Microsoft's libonnxruntime.so out of its AAR. Kept
// apart from src/main/jniLibs because they ride their own gate, `onnx`: on
// Play the on-demand :feature:onnx split packages both instead, so this
// module carries neither there.
if (!playStoreChannel) apply(plugin = "wmkeyboard.onnxruntime")

// Offline Vosk's libraries (#1165): libwmvosk (native/vosk-jni) from
// src/vosk/jniLibs, and Alpha Cephei's libvosk.so out of their AAR. The `vosk`
// gate's: :feature:vosk carries both on Play. A prebuilt binary, so F-Droid's
// build has neither.
if (!playStoreChannel && googleSdks) apply(plugin = "wmkeyboard.vosk")

androidComponents {
    onVariants { variant ->
        // Static-source-dir mechanism rather than a flavour folder for the
        // same AGP 9 reason as the channel seams in :app — and conditional,
        // which a flavour folder cannot be.
        if (!playStoreChannel && googleSdks) {
            variant.sources.kotlin?.addStaticSourceDirectory("src/whisperbridge/java")
        }
    }
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
    api(project(":core:language"))
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
    if (!playStoreChannel && googleSdks) {
        implementation(libs.litert)
    }
}

// Tests that reach the Rust text engines load libwm_engine_jni for this
// machine, which :core:language builds; see the comment on its task.
tasks.withType<Test>().configureEach { dependsOn(":core:language:buildHostEngine") }
