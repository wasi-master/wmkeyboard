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

// Same channel flag :app reads. It decides where the LiteRT-LM runtime lives:
// non-Play builds compile src/llmbridge (and the litertlm dependency)
// straight into this module, exactly as before the split; Play builds leave
// both out of the base APK — the on-demand :feature:llm module carries them
// instead, so the ~20 MB per ABI runtime only reaches devices that use
// On-device AI. See LlmRuntime.kt for the seam. ML Kit's translator
// (src/translatebridge, :feature:translate) and its ink recogniser
// (src/inkbridge, :feature:handwriting) follow the same arrangement, and so
// does the scanner's PaddleOCR reader (src/paddlebridge), which runs on the
// LiteRT interpreter Whisper brings and so shares :feature:litert.
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

// F-Droid's build may carry no Google artifact (fdroid/README.md): no ML Kit,
// LiteRT or LiteRT-LM, so none of the bridges above, and src/nogoogle's
// stand-ins for the few classes that call ML Kit directly instead of
// src/google. Everything else is the same code in every build; what it finds
// at runtime (FeatureModules, NativeDelivery) decides what is offered. Read
// the way :core:config reads it for its ENABLE_* fields.
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

// The Qualcomm NPU runtime for Compute = NPU (#804): on unless
// wmkb.enableNpu=false, never in the F-Droid build. Read the same way as
// ENABLE_NPU in :core:config. On Play it is the on-demand :feature:npu split
// instead, so it is packaged here only off Play; see the convention for what
// it carries and why as assets.
val npuRuntime = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    fun prop(name: String, env: String, default: String) =
        providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: System.getenv(env) ?: default
    prop("wmkb.enableNpu", "WMKB_ENABLE_NPU", "true").toBoolean() &&
        !prop("wmkb.enableFdroid", "WMKB_ENABLE_FDROID", "false").toBoolean()
}
// A build with native delivery (see wmkeyboard.native-pins) fetches the same
// files on demand instead, so it does not carry them as assets either. Read
// the way :app reads it.
val nativeDelivery = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    fun prop(name: String, env: String, default: String) =
        providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: System.getenv(env) ?: default
    prop("wmkb.nativeDelivery", "WMKB_NATIVE_DELIVERY", "true").toBoolean() && !playStoreChannel
}
if (npuRuntime && !playStoreChannel && !nativeDelivery) apply(plugin = "wmkeyboard.npu-runtime")

android {
    namespace = "com.wasimaster.wmkeyboard.intelligence"
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

androidComponents {
    onVariants { variant ->
        // Static-source-dir mechanism rather than a flavour folder for the
        // same AGP 9 reason as the channel seams in :app — and conditional,
        // which a flavour folder cannot be.
        variant.sources.kotlin?.addStaticSourceDirectory(if (googleSdks) "src/google/java" else "src/nogoogle/java")
        if (!playStoreChannel && googleSdks) {
            variant.sources.kotlin?.addStaticSourceDirectory("src/llmbridge/java")
            // The same arrangement for ML Kit's translator: see TranslateRuntime.kt.
            variant.sources.kotlin?.addStaticSourceDirectory("src/translatebridge/java")
            // And for its ink recogniser: see InkRuntime.kt.
            variant.sources.kotlin?.addStaticSourceDirectory("src/inkbridge/java")
            // And for PaddleOCR's graphs on LiteRT: see PaddleRuntime.kt.
            variant.sources.kotlin?.addStaticSourceDirectory("src/paddlebridge/java")
            // And for search by meaning's EmbeddingGemma graphs: see EmbeddingRuntime.kt.
            variant.sources.kotlin?.addStaticSourceDirectory("src/semanticbridge/java")
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
    api(project(":core:settings"))
    api(project(":core:prediction"))
    api(project(":core:language"))
    api(project(":core:common"))
    api(project(":core:config"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    // ML Kit's shared core, named on its own: MlKitInit reaches into it, and
    // on Play none of the libraries below that used to bring it along are in
    // the base any more.
    if (googleSdks) implementation(libs.mlkit.common)
    // Gemini Nano through Android AICore (GeminiNano.kt): only a client
    // that binds AICore's service, so it is in the base on every channel
    // with Google's SDKs. Its AARs say minSdk 26; the manifest overrides
    // that and GeminiNano checks the version before calling in.
    if (googleSdks) implementation(libs.mlkit.genai.prompt)
    // InkPackImports serves imported handwriting packs to ML Kit's own OkHttp
    // client, so it speaks OkHttp's interceptor API.
    implementation(libs.okhttp)
    if (!playStoreChannel && googleSdks) {
        // Handwriting. Play builds leave it out of the base APK; the on-demand
        // :feature:handwriting module carries it instead, so the recogniser's
        // ~6.5 MB per ABI only reaches devices that write by hand.
        implementation(libs.mlkit.digital.ink)
        implementation(libs.litertlm.android)
        // On-device translation for the translate tool, and the bundled
        // language identifier that stands in for the online services' "detect
        // language": ML Kit's translator has to be told what it is reading.
        // Play builds leave both out of the base APK; the on-demand
        // :feature:translate module carries them instead, so the translator's
        // ~16 MB per ABI of native code only reaches devices that use it.
        implementation(libs.mlkit.translate)
        implementation(libs.mlkit.language.id)
        // The scanner's PaddleOCR reader and search by meaning. The same
        // interpreter :core:voice links for Whisper, so this adds nothing to
        // the APK.
        implementation(libs.litert) {
            // Play's AI pack delivery, for a model provider this app never uses:
            // it brings asset-delivery's services and a data-sync foreground
            // service permission. See the litert entry in libs.versions.toml.
            exclude(group = "com.google.android.play", module = "ai-delivery")
        }
    }
    // Always, whatever the channel: OnDeviceTranslatorCatalogueTest pins the
    // hand-written language table to the library's own, and the table is
    // compiled into every build whether or not the library is. InkModelTagsTest
    // does the same for the generated ink tag list.
    testImplementation(libs.mlkit.translate)
    testImplementation(libs.mlkit.digital.ink)

    testImplementation(libs.junit)
}

// Tests that reach the Rust text engines load libwm_engine_jni for this
// machine, which :core:language builds; see the comment on its task.
tasks.withType<Test>().configureEach { dependsOn(":core:language:buildHostEngine") }
