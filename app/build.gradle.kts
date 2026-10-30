import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    // native/gates.json: the Play splits in dynamicFeatures, the JNI keep rules.
    id("wmkeyboard.native-gates")
    alias(libs.plugins.kotlin.compose)
    id("wmkeyboard.compose-metrics")
    // Offline handwriting model imports (#666): rewrites three calls inside
    // ML Kit's digital-ink downloader. Inert on Play, where the library is in
    // :feature:handwriting, which applies it too.
    id("wmkeyboard.ink-offline-imports")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.licensee)
    // Applied at the root with their versions; see build.gradle.kts there.
    id("org.jetbrains.kotlinx.kover")
    id("com.autonomousapps.dependency-analysis")
}

// Coverage for the root's merged report (`./gradlew koverHtmlReportUnit`):
// :app's own unit tests, which are most of the project's.
kover {
    currentProject {
        createVariant("unit") { add("intlDebug") }
    }
}

// Every library the APK ships, checked against the licences this project can
// carry. A new dependency under anything else fails the build until someone
// has read its licence and added it here, with the reason.
//
//   ./gradlew :app:licenseeAndroidIntlRelease   (and any other variant)
//
// Reports, including a JSON inventory of every artifact and its licence, are
// written to build/reports/licensee/android<Variant>/.
licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allow("ISC")

    // Licences their POMs name by URL rather than by SPDX id. Each read and
    // found to be a permissive licence in the list above.
    allowDependency("com.github.mwiede", "jsch", libs.versions.jsch.get()) {
        because("BSD-3-Clause for JSch itself; JZlib is BSD-3-Clause and jBCrypt ISC (LICENSE*.txt in the repo)")
    }
    allowDependency("org.bouncycastle", "bcprov-jdk18on", libs.versions.bouncycastle.get()) {
        because("the Bouncy Castle licence is the MIT licence, word for word")
    }
    allowDependency("org.luaj", "luaj-jse", libs.versions.luaj.get()) {
        because("MIT, per luaj.sourceforge.net/license.txt")
    }

    // Google's own terms: ML Kit, Play services and Play Core. Allowed only
    // outside the F-Droid channel, which must not ship them at all; there the
    // allow list is the free one above and nothing else, so a Google binary
    // that slipped into an F-Droid build fails it here, before F-Droid's
    // scanner finds it.
    if (!fdroidChannel) {
        allowUrl("https://developers.google.com/ml-kit/terms") {
            because("ML Kit, every build but F-Droid's; bundled or through Play services")
        }
        allowUrl("https://developer.android.com/studio/terms.html") {
            because("Play services (Drive sign-in, ML Kit's unbundled models) outside F-Droid")
        }
        allowUrl("https://developer.android.com/guide/playcore/license") {
            because("Play Core (in-app updates, feature delivery), Play builds only")
        }
    }
}

// API keys for the network tools (GIF/sticker via KLIPY/GIPHY, web/image search via
// Brave Search, optional official Cloud Translation). Read from
// local.properties (never committed) or, failing that, environment variables —
// all optional: without a key the affected tool shows a "needs API key" panel
// and users can paste their own key in the tool's settings.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun apiKey(propertyName: String, envName: String): String =
    localProperties.getProperty(propertyName)?.trim()
        ?: System.getenv(envName)?.trim()
        ?: ""

fun flag(propertyName: String, envName: String, default: String = "false"): Boolean =
    (providers.gradleProperty(propertyName).orNull
        ?: localProperties.getProperty(propertyName)
        ?: System.getenv(envName)
        ?: default).toBoolean()

// Which store this build is going to. It decides more than a label: each
// channel gets a different updater, and each updater is a liability in the
// other two channels. Play In-App Updates is a Google binary F-Droid will not
// accept and only works for an install Play made; the GitHub updater installs
// an APK, which Play's Device and Network Abuse policy forbids outright for an
// app distributed through Play. So the choice selects source directories and a
// manifest overlay, not just a dependency, and the code that is wrong for a
// channel is absent from its APK rather than merely unreachable.
val playStoreChannel = flag("wmkb.enablePlayStore", "WMKB_ENABLE_PLAY_STORE")
val fdroidChannel = flag("wmkb.enableFdroid", "WMKB_ENABLE_FDROID")

// Seam one: which Play *services* are linked. SplitInstall for the on-demand
// :feature:llm module, and PlayAppUpdater, which lives here too.
val playServicesSourceDir = if (playStoreChannel) "src/play/java" else "src/noplay/java"

// Seam two: which updater a non-Play build gets. Play needs no second
// directory, its driver is already in src/play/java. F-Droid signs its own
// builds, so an APK from our GitHub release can never install over an F-Droid
// install; that channel gets a checker that links out and downloads nothing.
val updaterSourceDir: String? = when {
    playStoreChannel -> null
    fdroidChannel -> "src/fdroid/java"
    else -> "src/github/java"
}
val githubChannel = updaterSourceDir == "src/github/java"

// Whether Google Play services may be compiled in. Separate from the store
// channel above, and deliberately: a sideloaded build on an ordinary phone
// should still be able to reach Drive, while an F-Droid build must not carry
// the library at all. Same shape as the channel seam — `src/gms/java` supplies
// the real Drive authorizer, `src/nogms/java` declares the same entry point and
// reports the destination unavailable. The only thing behind it is getting an
// OAuth token; the Drive REST calls themselves are ordinary HTTP in
// :core:settings and compile everywhere.
val gmsChannel = flag("wmkb.enableGms", "WMKB_ENABLE_GMS")
val gmsSourceDir = if (gmsChannel) "src/gms/java" else "src/nogms/java"

// Whether Google's SDKs (ML Kit, LiteRT, LiteRT-LM) are in the build: in
// every channel but F-Droid, whose build may carry no Google artifact. The
// library modules read the same flag to pick their src/google or src/nogoogle
// sources; here src/google adds the application class that starts WorkManager
// on demand for ML Kit's model downloads, and the manifest that names it.
val googleSdks = !fdroidChannel
val googleSourceDir = "src/google/java".takeIf { googleSdks }

val channelSourceDirs = listOfNotNull(playServicesSourceDir, updaterSourceDir, gmsSourceDir, googleSourceDir)

// A build with no internet permission at all (#292): `-Pwmkb.noInternet=true`,
// GitHub's offline build. Everything on the device keeps working; each feature
// that fetches something fails the way it does offline. From 0.6.4 it is as
// slim as the standard build: native delivery stays on, so its feature
// libraries are pinned rather than bundled, and the `en` flavour keeps its
// language packs. Neither can be fetched without the permission; both come in
// as imports of the files the release and the data repository publish.
val noInternet = flag("wmkb.noInternet", "WMKB_NO_INTERNET")

// A build that cannot ask for location at all (#350): `-Pwmkb.noLocation=true`.
// The Location tool keeps its saved places and hides Locate me; the weather and
// compass tools fall back to a typed or searched place, as before.
val noLocation = flag("wmkb.noLocation", "WMKB_NO_LOCATION")

// Native delivery (wmkeyboard.native-pins): the GitHub and F-Droid builds
// leave the native libraries behind optional features out of the APK and
// fetch each one the first time its feature asks, the way Play's on-demand
// modules do for a Play install. On by default; off on Play, which has its own
// modules and whose policy forbids downloading code anywhere else. On in the
// no-internet build too, which cannot fetch a library but imports the same
// pinned file fetched elsewhere (NativeDelivery.import).
// `-Pwmkb.nativeDelivery=false` bundles everything as before.
val nativeDelivery = flag("wmkb.nativeDelivery", "WMKB_NATIVE_DELIVERY", default = "true") &&
    !playStoreChannel
if (nativeDelivery) apply(plugin = "wmkeyboard.native-pins")

// Manifest entries that belong to exactly one channel. REQUEST_INSTALL_PACKAGES
// and the install-result receiver must not exist in a Play or F-Droid APK, and
// the Play Store package query is pointless anywhere but Play.
val channelManifests = listOfNotNull(
    "src/play/AndroidManifest.xml".takeIf { playStoreChannel },
    "src/google/AndroidManifest.xml".takeIf { googleSdks },
    "src/github/AndroidManifest.xml".takeIf { githubChannel },
    "src/nointernet/AndroidManifest.xml".takeIf { noInternet },
    "src/nolocation/AndroidManifest.xml".takeIf { noLocation },
)

// Every interface language the repo has a translation for, as BCP-47 tags
// ("en,ar,bn,…,zh-CN"), read off the res/values-xx folders so the in-app
// picker cannot drift from what is actually translated. English is the
// unqualified res/values (see resources.properties).
val translatedLocales: String = run {
    val folder = Regex("values-([a-z]{2,3})(?:-r([A-Z]{2}))?")
    val tags = file("src/main/res").listFiles().orEmpty().mapNotNull { dir ->
        val match = folder.matchEntire(dir.name) ?: return@mapNotNull null
        if (!File(dir, "strings.xml").isFile) return@mapNotNull null
        val (language, region) = match.destructured
        if (region.isEmpty()) language else "$language-$region"
    }
    (listOf("en") + tags.sorted()).joinToString(",")
}

// Sideload packaging. With `-Pwmkb.splitApks=true`, assemble<Variant> emits one
// APK per ABI plus a universal fallback instead of a single fat APK — the
// arm64 artifact is roughly half the universal's size, which is what most
// GitHub-release downloaders want. Off by default so day-to-day builds and the
// AAB pipeline (Play does its own ABI splitting) are untouched. When it is on,
// the ndk.abiFilters lines below step aside: AGP treats abiFilters and ABI
// splits as conflicting ways of saying the same thing.
val splitApks = flag("wmkb.splitApks", "WMKB_SPLIT_APKS")

// The ABIs a release ships, and the last digit each gives the versionCode.
// Every APK and bundle carries `wmkb.versionCode * 10 + digit` in its manifest
// (from 0.6.2): 0 for one that holds every ABI (the universal APK, the Play
// bundle, a build with no split), then one per ABI. A phone that runs two of
// them installs the highest it can, so arm64-v8a sits above armeabi-v7a, and
// x86_64 above both for the x86 Chromebooks that also translate ARM code.
// F-Droid builds one APK per ABI and serves each phone the right one by these
// numbers (VercodeOperation in fdroid/com.wasimaster.wmkeyboard.yml), and the
// GitHub splits carry the same digits, so a GitHub APK and an F-Droid APK of
// one version still install over each other.
//
// Only the manifest gets the digit. BuildConfig.RELEASE_VERSION_CODE, the
// release asset names and the changelog files keep the plain number, which
// is what the update checks compare.
val abiDigits = linkedMapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)
val baseVersionCode = providers.gradleProperty("wmkb.versionCode").get().toInt()

// `-Pwmkb.abi=arm64-v8a`: a release with that ABI's native code alone and that
// ABI's versionCode, without the ABI split. F-Droid's recipe sets it, one
// build entry per ABI, because fdroidserver takes one APK from each build.
val singleAbi = providers.gradleProperty("wmkb.abi").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?.also { require(it in abiDigits) { "wmkb.abi=$it is not one of ${abiDigits.keys}" } }

// Macrobenchmarks and baseline profile generation (:benchmark). Off by default,
// for the reasons settings.gradle.kts gives; the flag has to match there.
val benchmarkBuild = flag("wmkb.benchmark", "WMKB_BENCHMARK")


// Where the generated baseline profile lives: AGP's own default directory for
// the one variant that generates it (see the benchmark block near the end).
val generatedProfileDir = "src/intlRelease/baselineProfiles"

android {
    // The unit-test worker dies with an EOFException on the default 512m: the
    // settings tests parse every strings*.xml in the app to check the search
    // index against the real resources, and the whole app suite runs in one
    // worker.
    testOptions.unitTests.all { it.maxHeapSize = "2g" }

    namespace = "com.wasimaster.wmkeyboard"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.wasimaster.wmkeyboard"
        minSdk = 24
        targetSdk = 36
        // Both from gradle.properties so :core:config stamps the same numbers on
        // crash reports as the manifest carries. Never hardcode them here again.
        // The plain number. Each output's manifest gets `* 10 + ABI digit`
        // (see abiDigits), set per output in androidComponents below.
        versionCode = baseVersionCode
        versionName = providers.gradleProperty("wmkb.versionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI target filtering is handled by ndk.abiFilters per buildType below.

        buildConfigField("String", "KLIPY_API_KEY", "\"${apiKey("wmkb.klipyApiKey", "WMKB_KLIPY_API_KEY")}\"")
        buildConfigField("String", "GIPHY_API_KEY", "\"${apiKey("wmkb.giphyApiKey", "WMKB_GIPHY_API_KEY")}\"")
        buildConfigField("String", "BRAVE_API_KEY", "\"${apiKey("wmkb.braveApiKey", "WMKB_BRAVE_API_KEY")}\"")
        buildConfigField("String", "TRANSLATE_API_KEY", "\"${apiKey("wmkb.translateApiKey", "WMKB_TRANSLATE_API_KEY")}\"")
        buildConfigField("String", "UNSPLASH_API_KEY", "\"${apiKey("wmkb.unsplashApiKey", "WMKB_UNSPLASH_API_KEY")}\"")
        buildConfigField("String", "PEXELS_API_KEY", "\"${apiKey("wmkb.pexelsApiKey", "WMKB_PEXELS_API_KEY")}\"")
        // The versionCode without its ABI digit, the same in every APK of a
        // release. The update checks compare this, never the manifest's
        // number: F-Droid's index and the GitHub asset names speak in it.
        buildConfigField("int", "RELEASE_VERSION_CODE", "$baseVersionCode")
        buildConfigField("Boolean", "ENABLE_PLAY_STORE", "$playStoreChannel")
        buildConfigField("Boolean", "ENABLE_FDROID", "$fdroidChannel")
        // The third channel is the absence of the other two, which is a fact
        // the update UI and the settings search index both have to test. Named
        // once here so they cannot disagree about the boolean algebra.
        buildConfigField("Boolean", "ENABLE_GITHUB_UPDATES", "$githubChannel")
        buildConfigField("Boolean", "ENABLE_GMS", "$gmsChannel")
        // What About calls this build: the standard one, or the one with no
        // internet permission. Shown as is, like a version name.
        buildConfigField("String", "EDITION", "\"${if (noInternet) "Offline" else "Standard"}\"")
        // This module's copies of :core:config's feature flags, for the
        // app-package screens: off in F-Droid's build, which has none of
        // Google's SDKs. Grammar is Harper, in every build.
        buildConfigField("Boolean", "ENABLE_ML_KIT_HANDWRITING", "$googleSdks")
        buildConfigField("Boolean", "ENABLE_ML_KIT_SCANNERS", "$googleSdks")
        buildConfigField("Boolean", "ENABLE_GRAMMAR", "true")
        // On-device AI everywhere (llama.cpp, GGUF); LiteRT-LM only with Google's SDKs.
        buildConfigField("Boolean", "ENABLE_LOCAL_LLM", "true")
        buildConfigField("Boolean", "ENABLE_LITERT_LM", "$googleSdks")
        buildConfigField("Boolean", "ENABLE_WHISPER", "$googleSdks")
        // OAuth client ids for the Dropbox and OneDrive backup destinations.
        // Not secrets: the sign-in uses PKCE so no client secret ships. A
        // build without them simply leaves those two destinations out.
        buildConfigField("String", "DROPBOX_APP_KEY", "\"${apiKey("wmkb.dropboxAppKey", "WMKB_DROPBOX_APP_KEY")}\"")
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"${apiKey("wmkb.oneDriveClientId", "WMKB_ONEDRIVE_CLIENT_ID")}\"")
        // Box's, which also needs its client secret: Box refuses a token
        // request without one. Neither protects anything once shipped; a
        // build without them leaves Box out.
        buildConfigField("String", "BOX_CLIENT_ID", "\"${apiKey("wmkb.boxClientId", "WMKB_BOX_CLIENT_ID")}\"")
        buildConfigField("String", "BOX_CLIENT_SECRET", "\"${apiKey("wmkb.boxClientSecret", "WMKB_BOX_CLIENT_SECRET")}\"")
        // Diagnostic builds only — see the same field in :core:config, which is
        // the copy DebugLog reads. Mirrored here for the app-package screens.
        buildConfigField("Boolean", "ENABLE_CRASH_SCREEN", "${flag("wmkb.enableCrashScreen", "WMKB_ENABLE_CRASH_SCREEN")}")
        // How many interface languages the `intl` build adds to English, for the
        // English-only build's App language row to name when it offers them.
        buildConfigField("int", "TRANSLATED_LANGUAGE_COUNT", "${translatedLocales.split(',').size - 1}")
    }

    // Interface languages, the words the app shows rather than the ones it
    // types. The translations recovered from Play weigh ~39 MB, and because
    // resources.arsc is stored uncompressed that lands on every APK whole,
    // which roughly doubles the English build. So on GitHub the choice is the
    // downloader's:
    // - intl: English plus the 48 translated languages.
    // - en:   English only, which is what every build before 0.5.10 was.
    // Nothing else differs. Same applicationId, same signing key and same
    // versionCode, so either one installs over the other as a plain update and
    // no setting is lost in the move.
    //
    // The two stores each take one. Play gets `en`: it translates the app
    // strings itself and injects them into the bundle at upload, which is
    // where the repo's translations came from in the first place, so an `intl`
    // bundle would only hand Play its own output back. F-Droid gets `intl`:
    // it ships one APK to everybody and nothing downstream of the build adds a
    // language, so the APK is the only place its users can get them from.
    // See the store aliases near the bottom of this file.
    flavorDimensions += "languages"

    productFlavors {
        // Declared first, so it is what the IDE and a bare `assemble` pick.
        //
        // APP_LOCALES is what the in-app language picker (About, and the first
        // wizard page) offers, so it must name only languages this install can
        // actually show. `en` on Play still gets the full list: Play injects
        // its translations into the bundle at upload, and Android 13+ fetches
        // the language split when the app language changes. So does `en` on
        // GitHub, the standard build: it fetches each language as a pack from
        // its release (LANGUAGE_PACKS), on Android 11 and up. The offline
        // build's `en` takes the same packs, imported from a file.
        create("intl") {
            dimension = "languages"
            buildConfigField("String", "APP_LOCALES", "\"$translatedLocales\"")
            buildConfigField("Boolean", "LANGUAGE_PACKS", "false")
        }
        create("en") {
            dimension = "languages"
            val packs = githubChannel
            val shipped = if (playStoreChannel || packs) translatedLocales else "en"
            buildConfigField("String", "APP_LOCALES", "\"$shipped\"")
            buildConfigField("Boolean", "LANGUAGE_PACKS", "$packs")
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = apiKey("RELEASE_STORE_FILE", "RELEASE_STORE_FILE")
            if (storeFilePath.isNotEmpty()) {
                val file = rootProject.file(storeFilePath)
                if (file.exists()) {
                    storeFile = file
                    storePassword = apiKey("RELEASE_STORE_PASSWORD", "RELEASE_STORE_PASSWORD")
                    keyAlias = apiKey("RELEASE_KEY_ALIAS", "RELEASE_KEY_ALIAS")
                    keyPassword = apiKey("RELEASE_KEY_PASSWORD", "RELEASE_KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                if (!splitApks) abiFilters += setOf("arm64-v8a")
            }
        }
        release {
            ndk {
                if (!splitApks) abiFilters += singleAbi?.let(::setOf) ?: abiDigits.keys
                debugSymbolLevel = "FULL"
            }
            // No debug-key fallback. A debug-signed "release" build looks
            // shippable and is not: Play rejects the debug key outright, and
            // anything installed from such a build can never be updated by the
            // real key. Without the keystore the build produces an *unsigned*
            // release APK instead — an obvious failure rather than a silent one.
            //
            // One line, and findByName, both for F-Droid's sake. Their builder
            // strips the signingConfigs block and every line matching
            // `^[\t ]*signingConfig\s*[= ]\s*[^ ]*$` before building — a regex
            // whose tail allows no spaces, so it took the assignment and left a
            // `.takeIf` continuation behind to fail on its own. Kept to one
            // line, the statement either goes whole or stays whole. And with
            // the block gone getByName would throw, where findByName returns
            // null and the build comes out unsigned, which is what they want.
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile?.exists() == true }
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        // A build for putting the app on a phone and using it, which is not
        // what either of the other two is for. `debug` is unusable as a
        // keyboard: a debuggable process gets no aggressive ART compilation,
        // ignores the baseline profile entirely, and carries Compose's
        // source-info and recomposition tracing, so the thing you are typing
        // on is not the thing you are shipping. `release` types like the real
        // app but costs a fourteen-minute R8 run to find out.
        //
        // `fast` is release minus R8: not debuggable, release-signed,
        // baseline profile applied, and no shrinking at all. It runs close
        // enough to release to judge typing latency by hand, and it builds in
        // roughly the time the Kotlin compile alone takes.
        //
        //     ./gradlew :app:installIntlFast
        //
        // What it deliberately does not test: anything R8 does. Keep rules
        // (the luaj ones especially) are never exercised here, so a build that
        // works as `fast` can still crash as `release`. Ship-checking is
        // `release`'s job; this one is for iterating.
        create("fast") {
            initWith(getByName("release"))
            // The whole point. Both switches, not one: under AGP 9
            // `optimization.enable` is a second, independent way to turn R8
            // on ("when enabled the Android plugin uses R8 for optimization"),
            // and `initWith` above copies release's `true` along with
            // everything else. Setting only `isMinifyEnabled = false` leaves
            // `minify<Variant>WithR8` doing the full shrink-and-optimize pass
            // this build type exists to skip.
            isMinifyEnabled = false
            isShrinkResources = false
            optimization {
                enable = false
            }
            // `initWith` copies it from release, which is already false --
            // stated here because it is the reason this build type exists.
            isDebuggable = false
            // Library modules declare only `debug` and `release`, so their
            // `release` variant is what a `fast` app links against.
            matchingFallbacks += "release"
            ndk {
                // One ABI: the phone in your hand, not the three the store
                // needs. Skips packaging and stripping two ABIs' worth of ML
                // Kit and LiteRT native code. `debugSymbolLevel` off for the
                // same reason -- nothing here is going to a crash symbolicator.
                abiFilters.clear()
                if (!splitApks) abiFilters += setOf("arm64-v8a")
                debugSymbolLevel = "NONE"
            }
        }
    }
    // Play builds carry the native runtimes behind optional features as
    // on-demand modules instead of in every install: LiteRT-LM (~20 MB per
    // ABI), ML Kit's translator (~16 MB) and ink recogniser, the LiteRT
    // interpreter, ONNX Runtime (~33 MB on arm64), the Qualcomm NPU runtime,
    // the Google Home APIs, and the project's own libraries (whisper.cpp,
    // ExecuTorch, Harper, Hunspell, Tesseract, librime, Mozc, Bergamot,
    // CTranslate2, zinnia, llama.cpp, OpenCV), each fetched the first time its
    // feature asks. Channel-gated because Play Feature Delivery only exists on
    // Play: every other build compiles the same code into the base, so for
    // them the modules are not even part of the build graph. The list is
    // native/gates.json: the wmkeyboard.native-gates convention adds every
    // `:feature:<gate>` module settings.gradle.kts included.

    // See the splitApks flag at the top of this file. Splits shape APK
    // assembly only — bundle<Variant> ignores this block entirely.
    splits {
        abi {
            isEnable = splitApks
            reset()
            include(*abiDigits.keys.toTypedArray())
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        jniLibs {
            // Uncompressed and page-aligned in the APK, which is both what the
            // 16 KB page-size requirement wants and what lets the loader mmap
            // the library straight out of the APK, in every channel.
            useLegacyPackaging = false
            // No keepDebugSymbols override: AGP strips as usual. Every .so we
            // package — ours and the prebuilt ML Kit/LiteRT ones — already
            // ships stripped (measured: 48 bytes of symbol table across all
            // three ABIs), so keeping them was buying nothing.
        }
        resources {
            // Bouncy Castle's bcprov, bcutil and bcpg each carry the same licence file.
            pickFirsts += "META-INF/LICENSE.md"
        }
    }
    // AGP signs a dependency list into every APK's signing block, encrypted
    // with a key only Google can read. F-Droid's `check apk` job rejects an APK
    // carrying it, and the F-Droid reference APK on each GitHub release is one
    // of ours. It lives only in the signing block, so dropping it changes no
    // file inside the APK. The bundle keeps it: Play is who reads it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Writes a LocaleConfig listing every res/values-xx the build carries,
        // and points the manifest at it. Without this, Android 13 and up has
        // no per-app entry under Settings > Apps > WM Keyboard > Language, so
        // the translations recovered by tools/i18n/pull-play-translations.py
        // could only ever follow the system language. Generated rather than
        // hand-written so the list cannot drift from the locales that shipped.
        generateLocaleConfig = true
        // Language packs (.github/scripts/language-packs.sh): the release
        // compiles the all-languages build with
        // -Pwmkb.stableResourceIds=<the English build's IDs>, so the strings
        // cut out of it carry the IDs the English APK looks them up by. The
        // IDs are the stableIds.txt AGP already writes for every link
        // (intermediates/stable_resource_ids_file): AGP passes aapt its own
        // --emit-ids, and aapt keeps only one, so a second one here never
        // wrote anything.
        providers.gradleProperty("wmkb.stableResourceIds").orNull?.let { additionalParameters += listOf("--stable-ids", it) }
    }

    // Android Lint. Severities live in config/lint/lint.xml — this block only
    // says *what* to analyse and *how loudly* to report it.
    lint {
        lintConfig = rootProject.file("config/lint/lint.xml")

        // Turn on every check Lint ships, including the large set that is
        // disabled by default (interoperability, correctness edge cases,
        // API-surface checks). lint.xml then silences the ones that do not
        // apply to an IME, each with a written reason.
        checkAllWarnings = true
        checkDependencies = true
        // Test sources ship bugs too, and the unit tests here encode the
        // dictionary/transliteration contracts.
        checkTestSources = true
        ignoreTestFixturesSources = false
        checkGeneratedSources = false

        // Real problems stop the build; style-grade warnings do not, so the
        // signal stays readable. Escalation to `error` is per-issue in lint.xml.
        abortOnError = true
        warningsAsErrors = false
        // `lintVital` runs automatically before every release build when this
        // is on, and with `checkDependencies` above that means a full
        // 19-module analysis — measured at 25 minutes, roughly half the wall
        // clock of a release install, to produce a report nobody reads at that
        // moment. Off by default so building a release APK for the phone is a
        // build and not an audit; CI turns it back on:
        //
        //     ./gradlew lintIntlRelease -Pwmkb.lintRelease=true
        //
        // Every other lint setting in this block is untouched, so the explicit
        // `lint*` tasks still analyse exactly as much as they always did.
        checkReleaseBuilds =
            providers.gradleProperty("wmkb.lintRelease").map { it.toBoolean() }.getOrElse(false)

        // Print the full explanation and the offending lines, not just the
        // one-line summary — a finding nobody understands is a finding nobody
        // fixes.
        explainIssues = true
        noLines = false
        absolutePaths = false

        // AGP 9 always writes HTML/XML/SARIF/text reports to
        // build/reports/lint-results-<variant>.*; the report toggles and output
        // paths were removed from the DSL.
    }

    // The Harper native libraries live in :core:intelligence under src/main/jniLibs,
    // so every edition carries them; F-Droid builds them from source. Play
    // builds package them in the on-demand :feature:grammar split instead.
}

// Compiles dictionaries-src/*.txt into assets/dictionaries/*.wmdict via the
// :tools:dictc host tool, which shares the app's own trie/codec sources so the
// binary format cannot drift between writer and reader. Wired into every
// variant's assets through the Variant API below.
val dictc: Configuration by configurations.creating

abstract class CompileDictionariesTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    /** Assets root chosen by AGP; wordlists land in `dictionaries/` inside it. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: org.gradle.process.ExecOperations

    @TaskAction
    fun run() {
        execOperations.javaexec {
            classpath = toolClasspath
            mainClass.set("MainKt")
            args(
                sourceDir.get().asFile.absolutePath,
                outputDir.get().asFile.resolve("dictionaries").absolutePath,
            )
        }
    }
}

val compileBundledDictionaries =
    tasks.register<CompileDictionariesTask>("compileBundledDictionaries") {
        sourceDir.set(layout.projectDirectory.dir("dictionaries-src"))
        toolClasspath.from(dictc)
    }

// Writes assets/layouts-index.tsv: one line per shipped JSON layout, in file
// order — `id<TAB>name<TAB>langId<TAB>keymanId<TAB>keymanVersion<TAB>desktop`. The keyboard
// reads this instead of the layouts themselves (see AssetLayouts): there are
// over fifteen hundred of them and a user has a handful on, so parsing the rest
// at every process start only filled the heap and held up the first settings
// frame. Names, languages and Keyman bindings are what the lists and the
// search need without opening a grid. `desktop` is 1 for a grid shaped like a
// desktop keyboard (a row of 13 or more keys: the whole number row with its
// backquote and equals), which the More layouts page lists after the ones
// drawn for a phone.
abstract class GenerateLayoutIndexTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val layoutsDir: DirectoryProperty

    /** Assets root chosen by AGP; the index lands at its top level. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val suffix = ".wmlayout.json"
        val slurper = groovy.json.JsonSlurper()
        val lines = layoutsDir.get().asFile.listFiles { f -> f.name.endsWith(suffix) }.orEmpty()
            .sortedBy { it.name }
            .map { file ->
                @Suppress("UNCHECKED_CAST")
                val root = slurper.parse(file) as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val layout = root["layout"] as Map<String, Any?>
                val id = "asset_" + file.name.removeSuffix(suffix)
                check(layout["id"] == id) { "${file.name}: id ${layout["id"]} does not match its file name" }
                fun clean(value: Any?) = (value as? String).orEmpty().replace('\t', ' ').replace('\n', ' ')
                @Suppress("UNCHECKED_CAST")
                val keyman = layout["keyman"] as? Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val layers = layout["layers"] as? Map<String, Map<String, Any?>>
                val base = layers?.get("letters") ?: layers?.values?.firstOrNull()
                val widest = (base?.get("rows") as? List<*>).orEmpty().maxOfOrNull { (it as? List<*>)?.size ?: 0 } ?: 0
                listOf(
                    id,
                    clean(layout["name"]),
                    clean(layout["langId"]),
                    clean(keyman?.get("keyboardId")),
                    clean(keyman?.get("version")),
                    if (widest >= 13) "1" else "",
                ).joinToString("\t")
            }
        val out = outputDir.get().asFile
        out.mkdirs()
        out.resolve("layouts-index.tsv").writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

// Guards the two limits ART puts on a method's size, which nothing else in
// the build notices and which cost the keyboard its typing speed twice before
// anyone saw it (KeyboardScreen, then KeyRows: 14% of the main thread in a
// typing burst, from a function the compiler had silently given up on).
//
// - 10,000 dex instructions: past it ART never compiles the method, JIT or
//   AOT, baseline profile or not. It is interpreted for the life of the app.
// - A 3 KB frame for its fast interpreter (nterp): 184 bytes plus 8 per
//   register plus 4 per outgoing argument slot, on arm64. Past it the method
//   runs in the slow interpreter until the JIT compiles it — which is every
//   call right after an install or an update. Registers and outs grow with
//   the widest call in the method: a single inline `KeyboardSettings.copy`
//   (208 argument slots) is enough on its own.
//
// Reads the dex out of a `fast` APK: release-mode d8, like the shipped build,
// but without R8, so the names are the source's own. Every method of the app's
// own that is past 9,000 instructions (headroom under the hard limit) or past
// the nterp frame fails the check unless dex-method-budget.txt names it with
// a ceiling it has not outgrown — and a method belongs there only if it runs
// rarely: a class initialiser, a settings screen, a one-off decode.
//
//     ./gradlew :app:checkDexMethodsEnFast
abstract class CheckDexMethodsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val budgetFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    private class Method(val name: String, val insns: Int, val registers: Int, val outs: Int) {
        /** nterp's frame for this method on arm64 (NterpGetFrameSize), rounded to the 16-byte stack alignment. */
        val frame: Int get() = (160 + registers * 8 + 8 + 8 + outs * 4 + 8 + 15) / 16 * 16
    }

    @TaskAction
    fun run() {
        val apk = apkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }.orEmpty().singleOrNull()
            ?: error("expected exactly one APK in ${apkDir.get().asFile}")
        val methods = ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .flatMap { entry -> readMethods(zip.getInputStream(entry).use { it.readBytes() }) }
                .filter { it.name.startsWith(APP_PACKAGE) }
                .toList()
        }
        check(methods.isNotEmpty()) { "no ${APP_PACKAGE} methods in ${apk.name}" }
        // Overloads share a name here; the widest one speaks for them.
        val byName = methods.groupBy { it.name }.mapValues { (_, all) ->
            Method(all.first().name, all.maxOf { it.insns }, all.maxOf { it.registers }, all.maxOf { it.outs })
        }
        val budget = budgetFile.get().asFile.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .associate { line ->
                val parts = line.split(Regex("\\s+"))
                require(parts.size == 3) { "dex-method-budget.txt: want `<method> <max insns> <max frame bytes>`, got `$line`" }
                parts[0] to (parts[1].toInt() to parts[2].toInt())
            }
        val failures = mutableListOf<String>()
        for (m in byName.values.sortedByDescending { it.insns }) {
            val over = m.insns > INSNS_HEADROOM || m.frame > NTERP_MAX_FRAME
            val allowed = budget[m.name]
            when {
                allowed != null && (m.insns > allowed.first || m.frame > allowed.second) ->
                    failures += "${m.name}: ${m.insns} insns / ${m.frame} B frame, past its budget of " +
                        "${allowed.first} / ${allowed.second}"
                allowed == null && over ->
                    failures += "${m.name}: ${m.insns} insns / ${m.frame} B frame " +
                        "(${m.registers} registers, ${m.outs} outs)"
            }
        }
        val stale = budget.keys.filter { name ->
            val m = byName[name]
            m == null || (m.insns <= INSNS_HEADROOM && m.frame <= NTERP_MAX_FRAME)
        }
        val lines = byName.values
            .filter { it.insns > INSNS_HEADROOM / 2 || it.frame > NTERP_MAX_FRAME * 3 / 4 }
            .sortedByDescending { it.insns }
            .map { "%6d insns  %4d regs  %3d outs  %5d B frame  %s".format(it.insns, it.registers, it.outs, it.frame, it.name) }
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
        stale.forEach { logger.warn("dex-method-budget.txt: $it is back under both limits (or gone); drop its line") }
        if (failures.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Methods too big for ART to run well (limits: $INSNS_HEADROOM insns, of ART's hard $INSNS_LIMIT; $NTERP_MAX_FRAME B nterp frame):")
                    failures.forEach { appendLine("  $it") }
                    appendLine("Split the method (a big lambda body can go in a composable lambda of its own), move wide")
                    appendLine("calls such as KeyboardSettings.copy / KeyboardUiState.copy into a small plain function, or,")
                    appendLine("for a method that genuinely runs rarely, give it a line in app/dex-method-budget.txt.")
                    append("Every method near the limits: ${report.get().asFile}")
                },
            )
        }
    }

    /** Every method with code in one dex file: its name and the sizes from its code_item header. */
    private fun readMethods(bytes: ByteArray): List<Method> {
        val dex = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        fun uleb(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        fun string(index: Int): String {
            pos = dex.getInt(dex.getInt(0x3C) + index * 4)
            uleb()
            val out = StringBuilder()
            while (true) {
                val a = bytes[pos++].toInt() and 0xFF
                if (a == 0) break
                val c = when {
                    a < 0x80 -> a
                    a and 0xE0 == 0xC0 -> ((a and 0x1F) shl 6) or (bytes[pos++].toInt() and 0x3F)
                    else -> ((a and 0x0F) shl 12) or ((bytes[pos++].toInt() and 0x3F) shl 6) or (bytes[pos++].toInt() and 0x3F)
                }
                out.append(c.toChar())
            }
            return out.toString()
        }
        fun typeName(index: Int): String =
            string(dex.getInt(dex.getInt(0x44) + index * 4)).removePrefix("L").removeSuffix(";").replace('/', '.')
        val methodIds = dex.getInt(0x5C)
        val classDefs = dex.getInt(0x64)
        val out = mutableListOf<Method>()
        for (c in 0 until dex.getInt(0x60)) {
            val classDataOff = dex.getInt(classDefs + c * 32 + 24)
            if (classDataOff == 0) continue
            pos = classDataOff
            val staticFields = uleb()
            val instanceFields = uleb()
            val direct = uleb()
            val virtual = uleb()
            repeat((staticFields + instanceFields) * 2) { uleb() }
            val entries = mutableListOf<Pair<Int, Int>>()
            for (count in listOf(direct, virtual)) {
                var index = 0
                repeat(count) {
                    index += uleb()
                    uleb()
                    val codeOff = uleb()
                    if (codeOff != 0) entries += index to codeOff
                }
            }
            for ((index, codeOff) in entries) {
                val id = methodIds + index * 8
                val owner = typeName(dex.getShort(id).toInt() and 0xFFFF)
                val name = string(dex.getInt(id + 4))
                out += Method(
                    name = "$owner.$name",
                    insns = dex.getInt(codeOff + 12),
                    registers = dex.getShort(codeOff).toInt() and 0xFFFF,
                    outs = dex.getShort(codeOff + 4).toInt() and 0xFFFF,
                )
            }
        }
        return out
    }

    private companion object {
        const val APP_PACKAGE = "com.wasimaster.wmkeyboard."
        /** ART's CompilerOptions huge-method threshold: at or past it, never compiled. */
        const val INSNS_LIMIT = 10_000
        /** Where the check starts complaining: a tenth under the hard limit. */
        const val INSNS_HEADROOM = 9_000
        /** interpreter::kNterpMaxFrame. */
        const val NTERP_MAX_FRAME = 3 * 1024
    }
}

val generateLayoutIndex =
    tasks.register<GenerateLayoutIndexTask>("generateLayoutIndex") {
        layoutsDir.set(layout.projectDirectory.dir("src/main/assets/layouts"))
    }

// Copies release-notes/<versionName>.md to assets/release-notes.md, so the
// "Updated to" notification and the About screen can show what changed in the
// installed version without the network. A version whose notes are not written
// yet ships without the asset, and the app falls back to fetching them from
// the tag (InstalledReleaseNotes).
abstract class BundleReleaseNotesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notes: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        notes.files.firstOrNull { it.isFile }?.copyTo(out.resolve("release-notes.md"), overwrite = true)
    }
}

val bundleReleaseNotes =
    tasks.register<BundleReleaseNotesTask>("bundleReleaseNotes") {
        notes.from(
            rootProject.layout.projectDirectory.file(
                "release-notes/${providers.gradleProperty("wmkb.versionName").get()}.md",
            ),
        )
    }

// Rewrites the merged assets with every bundled layout's JSON compacted. The
// files under src/main/assets/layouts are indented for people and for diffs:
// 44 MB across ~1,700 layouts, 14.5 MB without the whitespace, which in the
// APK is ~0.8 MB less to download and ~30 MB less on the phone. Only
// whitespace outside strings goes, so every value is the byte-identical
// text the parser would have read anyway. A transform of the merged ASSETS
// artifact rather than a generated directory beside the sources, because
// the sources would still be merged in and collide with it.
abstract class MinifyLayoutAssetsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun run() {
        val from = input.get().asFile
        val to = output.get().asFile
        to.deleteRecursively()
        from.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(from).invariantSeparatorsPath
            val dest = to.resolve(rel)
            dest.parentFile.mkdirs()
            if (rel.startsWith("layouts/") && rel.endsWith(".wmlayout.json")) {
                dest.writeText(compact(file.readText()))
            } else {
                file.copyTo(dest)
            }
        }
    }

    /** [json] without the whitespace between tokens. Strings, escapes included, are kept as written. */
    private fun compact(json: String): String {
        val out = StringBuilder(json.length / 2)
        var inString = false
        var escaped = false
        for (c in json) {
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                ' ', '\n', '\r', '\t' -> Unit
                '"' -> {
                    inString = true
                    out.append(c)
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val minify = tasks.register<MinifyLayoutAssetsTask>("minifyLayoutAssets$name")
        variant.artifacts.use(minify)
            .wiredWithDirectories(MinifyLayoutAssetsTask::input, MinifyLayoutAssetsTask::output)
            .toTransform(com.android.build.api.artifact.SingleArtifact.ASSETS)
    }
}

// The ABI digit on every output's versionCode (see abiDigits). An ABI split
// names its ABI; the universal APK and the bundle name none and take 0,
// unless the whole build is one ABI's (wmkb.abi).
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier ?: singleAbi
            output.versionCode.set(baseVersionCode * 10 + (abi?.let(abiDigits::getValue) ?: 0))
        }
    }
}

// Only the `fast` build: release-mode d8 like the shipped APK, but no R8, so
// the dex still carries the source's names. See [CheckDexMethodsTask].
androidComponents {
    onVariants(selector().withBuildType("fast")) { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        tasks.register<CheckDexMethodsTask>("checkDexMethods$name") {
            group = "verification"
            description = "Fails when an app method in the $name APK is too big for ART to compile or for nterp to run."
            apkDir.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK))
            budgetFile.set(layout.projectDirectory.file("dex-method-budget.txt"))
            report.set(layout.buildDirectory.file("reports/dex-methods/$name.txt"))
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            compileBundledDictionaries,
            CompileDictionariesTask::outputDir,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateLayoutIndex,
            GenerateLayoutIndexTask::outputDir,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            bundleReleaseNotes,
            BundleReleaseNotesTask::outputDir,
        )
        // The update-channel driver picked at the top of this file, added to
        // every production variant. This is the Variant API rather than the
        // `android.sourceSets` or `kotlin.sourceSets` DSL because neither of
        // those reaches the Kotlin compilation under AGP 9: there is no
        // `main` Kotlin source set any more, and an extra *Java* directory is
        // not mirrored into the Kotlin task, so a directory added that way
        // compiles nothing and every reference into it fails to resolve.
        //
        // The store channel is not a flavour of its own on purpose: a second
        // dimension would double every variant and every Gradle task name in
        // the project for one file.
        channelSourceDirs.forEach { variant.sources.kotlin?.addStaticSourceDirectory(it) }
        // The GitHub channel also needs two permissions and a receiver that
        // must not appear in any other APK, so its manifest is added the same
        // way. addStaticManifestFile appends, and a merged manifest overlays,
        // so the entries land on top of src/main's.
        channelManifests.forEach { variant.sources.manifests?.addStaticManifestFile(it) }

        // The `languages` flavour is applied here rather than in its own
        // productFlavors block because AGP 9 dropped resourceConfigurations
        // from ProductFlavor; localeFilters is reachable only from the DSL's
        // single androidResources block, which cannot vary per flavour, or
        // from the variant, which can.
        //
        // "en" keeps the unqualified res/values, which is the English one, and
        // drops every values-xx alongside it. That takes the translations of
        // the dependencies with it, so the saving is a little larger than the
        // app's own strings account for. Nothing filters the `intl` variant:
        // it packages whatever values-xx the merge produced.
        if (variant.productFlavors.any { (dimension, flavor) ->
                dimension == "languages" && flavor == "en"
            }
        ) {
            variant.androidResources.localeFilters.set(setOf("en"))
        }
    }
}

// The task name F-Droid's recipe was written against. Up to 0.6.x :app had a
// `capabilities` flavour (full, lite) beside `languages`, and F-Droid built
// `lite`. fdroiddata's bot writes each new `Builds:` entry by copying the last
// one, `gradle: [lite]` included, and nobody reviews what it writes, so the
// old name has to keep working until a merge request there switches the
// recipe to `gradle: [intl]` (fdroid/README.md). It means what F-Droid takes:
//
//     assembleLiteRelease  -> assembleIntlRelease
//
// The name alone is not enough. fdroidserver looks for the APK in the
// directory under build/outputs/apk/ whose name matches its flavour list,
// which is `lite`, and gives up with "Failed to find any output apks"
// otherwise. So the alias also mirrors the artifact to that path. Sync rather
// than Copy: fdroidserver refuses a directory holding more than one APK, and
// a Sync cannot leave a stale one behind from an earlier run.
fun registerStoreAlias(
    taskName: String,
    variantTaskName: String,
    variantOutputs: String,
    legacyOutputs: String,
) = tasks.register<Sync>(taskName) {
    group = "build"
    description = "Runs $variantTaskName and mirrors its artifact to build/$legacyOutputs."
    dependsOn(variantTaskName)
    from(layout.buildDirectory.dir(variantOutputs)) {
        include("*.apk", "*.aab")
    }
    into(layout.buildDirectory.dir(legacyOutputs))
}

registerStoreAlias(
    taskName = "assembleLiteRelease",
    variantTaskName = "assembleIntlRelease",
    variantOutputs = "outputs/apk/intl/release",
    legacyOutputs = "outputs/apk/lite/release",
)

kotlin {
    // The update-channel driver picked at the top of this file. It has to be
    // declared here and not only in `android.sourceSets`: under AGP 9 the
    // Kotlin plugin no longer mirrors extra Java source directories into its
    // own compilation, so a directory added there alone compiles nothing and
    // every reference into it fails to resolve.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)

        // The compiler is the cheapest static analyser available: it already
        // knows the types. `extraWarnings` turns on the diagnostics that are
        // off by default — unused expression results, redundant casts and
        // labels, useless elvis branches, unnecessary type arguments.
        extraWarnings.set(true)

        freeCompilerArgs.addAll(
            // Without this the compiler stops reporting warnings for a file as
            // soon as that file has an error — which hides warnings exactly when
            // the code is being changed most.
            "-Xreport-all-warnings",
        )

        // Gated rather than unconditional: CI can enforce a zero-warning build
        // (`./gradlew assemble -PwarningsAsErrors=true`) without making every
        // local edit fail on a warning mid-refactor.
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

// ---------------------------------------------------------------------------
// detekt: rule-based static analysis over the Kotlin sources.
//
// The `detekt<Variant>` tasks (detektDebug, ...) run *with type
// resolution* — they get the variant's compile classpath, which is what the
// high-value rules need: nullability, unreachable code, ignored return values
// and exhaustive-`when` checks are all impossible without resolved types.
// The plain `detekt` task is source-only and much weaker; it stays available
// for a fast pass but is not what `staticAnalysis` runs.
// ---------------------------------------------------------------------------
// Library-module *test* sources, analysed by this module's unit-test detekt
// task below. Main sources are NOT swept from here — each module's own
// wmkeyboard.detekt plugin analyses them against that module's classpath.
// (Analysing module sources with the app's classpath makes detekt see those
// classes as both source and binary, which fabricates UnreachableCode
// findings.) Test sources are safe to sweep: they are never packaged into a
// jar on this classpath.
val moduleTestSrc = listOf(
    "../core/language", "../core/tools", "../core/emoji",
    "../core/intelligence", "../feature/tools", "../feature/ime",
).map { "$it/src/test/java" }

detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    // `allRules` would also enable rules detekt itself marks as unstable or
    // opinionated; the ruleset in detekt.yml is curated explicitly instead.
    allRules = false
    parallel = true
    ignoreFailures = false
    // Report paths relative to the repo root so findings are clickable from CI
    // logs regardless of where the checkout lives.
    basePath = rootProject.projectDir.absolutePath
    source.setFrom(
        files(
            "src/main/java",
            // Only the channels this build compiles: the other directories
            // name types that are not on any classpath here.
            channelSourceDirs,
            "src/test/java",
            "src/androidTest/java",
        ),
    )
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "11"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
        txt.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = "11"
}

// detekt 1.23's Android integration is written against the AGP 7/8 variant API
// and creates no `detekt<Variant>` tasks under AGP 9, which would leave the
// project with source-only analysis — losing every rule that needs resolved
// types. So the type-resolution tasks are registered here by hand: same Detekt
// task type, classpath taken from the variant's own Kotlin compilation.
//
//   ./gradlew detektDebug            # main + channel sources
//   ./gradlew detektDebugUnitTest    # unit tests, with main on the classpath
fun registerTypeResolvedDetekt(
    taskName: String,
    description: String,
    sourceDirs: List<String>,
    compileTaskName: String,
    extraClasspath: List<String> = emptyList(),
) = tasks.register<Detekt>(taskName) {
    this.description = description
    group = "verification"

    val compileTask =
        tasks.named(compileTaskName, org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class)

    setSource(files(sourceDirs.map { layout.projectDirectory.dir(it) }))
    include("**/*.kt")
    exclude("**/build/**", "**/resources/**")

    // `libraries` is the variant's resolved compile classpath; android.jar has
    // to be added separately because it is on the bootclasspath, not there.
    classpath.setFrom(
        compileTask.map { it.libraries },
        androidComponents.sdkComponents.bootClasspath,
        files(extraClasspath.map { layout.buildDirectory.dir(it) }),
    )
    if (extraClasspath.isNotEmpty()) {
        dependsOn(compileTask)
    }

    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    ignoreFailures = false
    basePath = rootProject.projectDir.absolutePath
    jvmTarget = "11"

    reports {
        html.outputLocation.set(layout.buildDirectory.file("reports/detekt/$taskName.html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/detekt/$taskName.xml"))
        sarif.outputLocation.set(layout.buildDirectory.file("reports/detekt/$taskName.sarif"))
    }
}

// The detekt task names deliberately do not carry the `languages` flavour.
// That flavour changes which res/values-xx are packaged and nothing else, so
// there is no second set of Kotlin sources for it to analyse and a
// detektIntlDebug/detektEnDebug pair would run the same files twice. Only the
// compile task these borrow their classpath from has to name a real variant,
// and they take the `intl` one. F-Droid's stand-ins are analysed by a run
// with -Pwmkb.enableFdroid=true, which swaps them into channelSourceDirs and
// the library modules' sources.
registerTypeResolvedDetekt(
    taskName = "detektDebug",
    description = "Runs detekt with type resolution over the debug variant's sources.",
    sourceDirs = listOf("src/main/java") + channelSourceDirs,
    compileTaskName = "compileIntlDebugKotlin",
)

registerTypeResolvedDetekt(
    taskName = "detektDebugUnitTest",
    description = "Runs detekt with type resolution over the unit tests, including the library modules'.",
    sourceDirs = listOf("src/test/java") + moduleTestSrc,
    compileTaskName = "compileIntlDebugUnitTestKotlin",
    extraClasspath = listOf("tmp/kotlin-classes/intlDebug"),
)

registerTypeResolvedDetekt(
    taskName = "detektDebugAndroidTest",
    description = "Runs detekt with type resolution over the instrumentation tests.",
    sourceDirs = listOf("src/androidTest/java"),
    compileTaskName = "compileIntlDebugAndroidTestKotlin",
    extraClasspath = listOf("tmp/kotlin-classes/intlDebug"),
)

// `./gradlew check` should mean "everything the analysers know how to check".
tasks.named("check") {
    dependsOn("detektDebug", "detektDebugUnitTest")
}

dependencies {
    dictc(project(":tools:dictc"))

    // Installs app/src/main/baseline-prof.txt into the app's ART profile so the
    // listed methods are compiled ahead of time. Declared explicitly, not left
    // to arrive transitively behind Compose and Coil: it already does arrive
    // that way, but a dependency bump that dropped it would leave the profile
    // packaged in the APK and never applied — the keyboard would quietly go
    // back to warming up from cold on every process start, with nothing
    // failing to say so. Play installs are covered by the store either way;
    // this is what covers F-Droid and sideloads.
    implementation(libs.androidx.profileinstaller)

    // Static-analysis rule packs. These are analyser plugins, not app code —
    // nothing here reaches the APK.
    detektPlugins(libs.detekt.formatting)
    // Compose correctness rules (unremembered state, unstable parameters,
    // modifier misuse, composables that read state too often). Compose bugs are
    // recomposition bugs, and none of them are visible to the type checker.
    lintChecks(libs.compose.lint.checks)

    // Feature modules. Their build files declare project dependencies with
    // `api`, so :feature:ime alone would reach every :core:* transitively —
    // the explicit list is for the unit tests in src/test, which compile
    // against classes from every layer.
    implementation(project(":core:config"))
    implementation(project(":core:common"))
    implementation(project(":core:language"))
    implementation(project(":core:input"))
    implementation(project(":core:prediction"))
    implementation(project(":core:emoji"))
    implementation(project(":core:theme"))
    implementation(project(":core:icons"))
    implementation(project(":core:tools"))
    implementation(project(":core:kdeconnect"))
    implementation(project(":core:content"))
    implementation(project(":core:addons"))
    implementation(project(":core:voice"))
    implementation(project(":core:settings"))
    implementation(project(":core:feedback"))
    implementation(project(":core:plugins"))
    implementation(project(":core:intelligence"))
    implementation(project(":feature:tools"))
    implementation(project(":feature:addons"))
    implementation(project(":feature:ime"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // The fingerprint / device-credential prompt in front of the settings the
    // user chose to lock. Also why MainActivity is a FragmentActivity.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.layout)
    implementation(libs.androidx.compose.adaptive.navigation)
    // The language-settings screens edit DataStore Preferences directly.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    // Compiled against by src/google's WMFullApplication, which starts
    // WorkManager on demand. Already in every such APK at runtime (ML Kit's
    // digital-ink brings it), so this adds nothing to what ships.
    if (googleSdks) implementation(libs.androidx.work.runtime)

    // Play In-App Updates, for Play-channel builds only. Compiled against by
    // src/play/java; src/noplay/java is what every other channel gets, so no
    // Google binary is linked into an F-Droid or direct-download APK.
    if (playStoreChannel) {
        // Play Billing and the rating card ride on the app-update line on
        // purpose: F-Droid's recipe deletes every line naming
        // libs.play.app.update before its scanner runs, and the scanner knows
        // these artifacts by name. A line of their own would need a new sed in
        // the recipe, and so an fdroiddata MR, before the next F-Droid build
        // could pass.
        implementation(libs.play.app.update); implementation(libs.play.billing); implementation(libs.play.review)
        // SplitInstall + SplitCompat for the on-demand :feature:* modules
        // (every gate of native/gates.json with a Play module).
        implementation(libs.play.feature.delivery)
        // ML Kit's own half of running from an on-demand module. It has to be
        // in the base: it is what lets the ML Kit context that started with
        // the process find a library that arrived after it.
        implementation(libs.mlkit.dynamic.feature.support) {
            // 16.0.0-beta2 still depends on the monolithic play:core 1.10.0,
            // which the two split artifacts above replaced: its classes
            // duplicate theirs (checkDuplicateClasses fails the bundle), and
            // its manifest declares asset-pack services whose
            // `@bool/enable_system_*_service_default` come from a WorkManager
            // the feature modules do not have, so their AAPT step fails too.
            // The library itself only needs SplitInstall, which
            // feature-delivery provides under the same package names.
            exclude(group = "com.google.android.play", module = "core")
        }
        // WorkManager, which digital-ink declares and uses for its model
        // downloads. It has to be in the base even though the library that
        // wants it is in the :feature:handwriting split: its initializer is
        // an androidx.startup entry that only runs for the base at process
        // start, and its manifest services are enabled by its own `@bool`
        // resources, which the base's resource link needs to resolve once
        // AGP merges the split's components into the base manifest. Outside
        // Play it comes with digital-ink itself.
        implementation(libs.androidx.work.runtime)
    }
    // Only for the Drive backup destination's OAuth token. The Drive calls
    // themselves are plain HTTP and need nothing from Google.
    if (gmsChannel) {
        implementation(libs.play.services.auth)
    }

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // GalleryCoreTest builds ExifInterface tag lists; :core:content keeps the
    // library to itself (implementation), so the tests here need their own.
    testImplementation(libs.androidx.exifinterface)
    // The plugin tests drive the Lua sandbox through luaj types directly.
    testImplementation(libs.luaj.jse)
    // WordKeyParityTest holds the Rust word key to ART's fold, answered by
    // ICU4J at the Unicode version ICU4X carries (`android.icu` is a stub here).
    testImplementation(libs.icu4j)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // The empty activity ComposeTestRule launches into. Debug-only by design:
    // it adds a manifest entry, so it must never reach a release build.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The transliteration tests call the Rust text engines through JNI, so the
// library has to exist for this machine before the suite starts. The task is
// :core:language's, where the facades live; see the comment on it there.
tasks.withType<Test>().configureEach { dependsOn(":core:language:buildHostEngine") }

// `-Pwmkb.skipBenchmarks` drops the wall-clock benchmarks from the run. They
// assert against millisecond budgets measured on a quiet machine, so on a
// shared CI runner they measure the runner's neighbours rather than this
// project. Nothing else is filtered: the accuracy evals still run.
if (providers.gradleProperty("wmkb.skipBenchmarks").map(String::toBoolean).getOrElse(false)) {
    tasks.withType<Test>().configureEach {
        filter {
            excludeTestsMatching("*LatencyBench")
            excludeTestsMatching("*NoiseSweepTest")
        }
    }
}

// ---------------------------------------------------------------------------
// Baseline profile generation: `-Pwmkb.benchmark=true`
//
//   ./gradlew :app:generateIntlReleaseBaselineProfile -Pwmkb.benchmark=true
//
// Runs :benchmark's BaselineProfileGenerator on the connected device against a
// non-minified intl release build and writes what it recorded to
// src/intlRelease/baselineProfiles/. Commit the result. Every variant
// packages it (the block below this one), flag or not, next to the
// hand-written src/main/baseline-prof.txt.
//
// One variant and not the plugin's merged `generateBaselineProfile`: the plugin
// gives every release-like build type a profiling twin, `fast` included, in all
// four flavour pairs, and merging means running the journey on the device once
// for each of the eight to produce one file. The code a journey reaches is the
// same in all of them.
//
// The plugin also adds the `nonMinifiedRelease` and `benchmarkRelease` build
// types it installs, which is why the whole block sits behind the flag.
// ---------------------------------------------------------------------------
if (benchmarkBuild) {
    apply(plugin = "androidx.baselineprofile")

    configure<androidx.baselineprofile.gradle.consumer.BaselineProfileConsumerExtension> {
        mergeIntoMain = false
        // AGP's own default source directory for the variant, so a build
        // without the plugin (every ordinary one) still finds the file.
        baselineProfileOutputDir = "baselineProfiles"
        // Only on request: generation needs a device and several minutes.
        automaticGenerationDuringBuild = false
        filter {
            // The app's own code. Libraries ship profiles of their own, and
            // the subsystems no journey reaches stay out as they always have.
            include("com.wasimaster.wmkeyboard.**")
        }
    }

    dependencies {
        "baselineProfile"(project(":benchmark"))
    }
}

// The generated profile, for every variant rather than only the intl
// release one it was recorded from. That one already reads the directory as
// its own source set's default.
androidComponents {
    onVariants { variant ->
        if (variant.name != "intlRelease") {
            variant.sources.baselineProfiles?.addStaticSourceDirectory(generatedProfileDir)
        }
    }
}

// ---------------------------------------------------------------------------
// Docs screenshots: `-Pwmkb.docShots=true`
//
//   ./gradlew :app:testEnDebugUnitTest -Pwmkb.docShots=true
//
// Renders settings screens on the JVM (Robolectric native graphics +
// Roborazzi) and writes PNGs to build/docshots/. Everything below exists only
// under the flag: without it no dependency, source directory, resource link or
// heap setting here reaches a normal build or test run, which is the point.
// With it, the run is *only* the shots; the ordinary unit tests are filtered
// out, since the resource linking this needs makes them slower for nothing.
// ---------------------------------------------------------------------------
val docShots = flag("wmkb.docShots", "WMKB_DOC_SHOTS")

if (docShots) {
    android {
        // Robolectric needs the merged resources and manifest to inflate the
        // real theme, strings and the activity. Off by default because it
        // drags AAPT2 linking into every unit-test run.
        testOptions.unitTests.isIncludeAndroidResources = true
        // Native graphics plus a whole activity tree; 2g is the ordinary suite's.
        testOptions.unitTests.all {
            it.maxHeapSize = "4g"
            // Shots are independent, so two workers halve the run.
            it.maxParallelForks = 2
            // A shot that wedges must not hold the run forever.
            it.timeout.set(Duration.ofMinutes(90))
        }
    }

    androidComponents {
        onVariants { variant ->
            // Variant API for the same reason as the channel directories above:
            // AGP 9 no longer mirrors an extra Java directory into Kotlin.
            variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.sources?.let {
                it.kotlin?.addStaticSourceDirectory("src/docShots/java")
                // robolectric.properties pins sdk=34, a level with a
                // long-settled android-all jar.
                it.resources?.addStaticSourceDirectory("src/docShots/resources")
            }
        }
    }

    dependencies {
        testImplementation(libs.robolectric)
        testImplementation(libs.roborazzi)
        testImplementation(libs.roborazzi.compose)
        testImplementation(platform(libs.androidx.compose.bom))
        testImplementation(libs.androidx.compose.ui.test.junit4)
        testImplementation(libs.androidx.junit)
    }

    tasks.withType<Test>().configureEach {
        filter { includeTestsMatching("com.wasimaster.wmkeyboard.docshots.*") }
        systemProperty("roborazzi.test.record", "true")
        systemProperty("wmkb.docShots.out", layout.buildDirectory.dir("docshots").get().asFile.absolutePath)
        // `-Pwmkb.docShots.only=<regex>` renders just the ids it matches.
        systemProperty("wmkb.docShots.only", providers.gradleProperty("wmkb.docShots.only").getOrElse(""))
        // `-Pwmkb.docShots.modes=light` (or dark) renders one of the two, for a quick look.
        systemProperty("wmkb.docShots.modes", providers.gradleProperty("wmkb.docShots.modes").getOrElse(""))
        // AddonShots, the community repository's render: `-Pwmkb.addonShots.in=<dir>`
        // holds request.json and the submitted file, `-Pwmkb.addonShots.out=<dir>`
        // gets the PNGs and the report.
        systemProperty("wmkb.addonShots.in", providers.gradleProperty("wmkb.addonShots.in").getOrElse(""))
        systemProperty("wmkb.addonShots.out", providers.gradleProperty("wmkb.addonShots.out").getOrElse(""))
        // Every shot is a separate test; the report shows which ones failed.
        outputs.upToDateWhen { false }
    }
}
