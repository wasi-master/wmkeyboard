import java.util.Properties

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
// this module's full-flavour debug unit tests.
kover {
    currentProject {
        createVariant("unit") { add("fullDebug") }
    }
}

// Same channel flag :app reads. It decides where the LiteRT interpreter that
// runs the sticker editor's own background remover lives: non-Play full
// builds compile src/cutoutbridge (and the litert dependency) straight into
// this module; Play builds leave both out of the base APK — the on-demand
// :feature:litert module carries them instead. See CutoutRuntime.kt for the
// seam, and :core:intelligence for the arrangement this one copies.
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

android {
    namespace = "com.wasimaster.wmkeyboard.content"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
    }

    flavorDimensions += "capabilities"
    productFlavors {
        create("full") { dimension = "capabilities" }
        create("lite") { dimension = "capabilities" }
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
        if (!playStoreChannel && variant.flavorName == "full") {
            variant.sources.kotlin?.addStaticSourceDirectory("src/cutoutbridge/java")
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
    api(project(":core:tools"))
    api(project(":core:common"))
    // Numeral tables, FancyStyles and the Avro rules for the selection macros.
    api(project(":core:language"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Reading Espanso match files. Loader only, always under SafeConstructor;
    // see the version catalog and EspansoYaml.
    implementation(libs.snakeyaml)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    // Reads the frames of an APNG, which is what an animated Signal sticker
    // is. Android decodes only the first one. See ApngFrames.
    implementation(libs.apng4android.apng)
    // A supertype of that library's drawable. See the version catalog.
    compileOnly(libs.androidx.vectordrawable.animated)
    // Background removal in the sticker editor. Full only: lite ships a stub
    // with the same signatures, and its editor simply has no cutout button.
    "fullImplementation"(libs.mlkit.subject.segmentation)
    // The same removal without Play services: U2-Net-P on the interpreter
    // :core:voice links for Whisper, so this adds nothing to the APK. Play
    // builds get it from the :feature:litert module instead.
    if (!playStoreChannel) {
        "fullImplementation"(libs.litert)
    }
}
