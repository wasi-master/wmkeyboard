pluginManagement {
    repositories {
        maven { url = java.net.URI("https://maven-central.storage-download.googleapis.com/maven2") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
// Convention plugins (per-module type-resolved detekt).
includeBuild("build-logic")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = java.net.URI("https://maven-central.storage-download.googleapis.com/maven2") }
        google()
    }
}

rootProject.name = "WM Keyboard"
include(":app")

// Library modules, layered bottom-up. :core:config carries the build flags and
// API keys every library reads; :core:common the shared utilities; the rest are
// one feature area each. :feature:* sit above :core:settings (they read the
// settings repository), :feature:ime is the keyboard runtime itself.
include(":core:config")
include(":core:common")
include(":core:language")
include(":core:input")
include(":core:keyman")
include(":core:prediction")
include(":core:emoji")
include(":core:theme")
include(":core:icons")
include(":core:tools")
include(":core:kdeconnect")
include(":core:content")
include(":core:addons")
include(":core:voice")
include(":core:settings")
include(":core:feedback")
include(":core:plugins")
include(":core:intelligence")
include(":feature:tools")
include(":feature:addons")
include(":feature:ime")
// Dynamic feature module, Play channel only: the on-demand home of the
// LiteRT-LM runtime. :app adds it to `dynamicFeatures` behind the same flag,
// and a dynamic-feature module that no app registers cannot resolve its own
// `featureName`, so including it unconditionally breaks every non-Play build
// the moment an unqualified task name (`assembleFullDebug`, and so the whole
// of CI) fans out across every project. It is therefore in the build only
// when the channel that consumes it is. Developers who want it in the IDE
// have wmkb.enablePlayStore=true in local.properties already, since that is
// what building for Play needs.
val playStoreChannel: Boolean = run {
    val local = java.util.Properties()
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use(local::load)
    // Same precedence as `flag()` in app/build.gradle.kts: -P, then
    // local.properties, then the environment, then off.
    (providers.gradleProperty("wmkb.enablePlayStore").orNull
        ?: local.getProperty("wmkb.enablePlayStore")
        ?: System.getenv("WMKB_ENABLE_PLAY_STORE")
        ?: "false").toBoolean()
}
if (playStoreChannel) {
    include(":feature:llm")
    // The same arrangement for ML Kit's on-device translator.
    include(":feature:translate")
    // The LiteRT interpreter (offline Whisper, the sticker editor's own
    // background remover) and ML Kit's ink recogniser, the same way.
    include(":feature:litert")
    include(":feature:handwriting")
}
// Macrobenchmarks and the baseline profile generator (-Pwmkb.benchmark=true).
// Out of the ordinary build for two reasons. Applying the baselineprofile
// plugin to :app adds two build types to every flavour pair, which every
// configuration, analyser and `./gradlew tasks` then pays for. And this
// module's tests install a release-like build over the app on whichever
// device is connected, which nobody should get by running a task on a whim.
val benchmarkBuild: Boolean = run {
    val local = java.util.Properties()
    file("local.properties").takeIf { it.exists() }?.inputStream()?.use(local::load)
    (providers.gradleProperty("wmkb.benchmark").orNull
        ?: local.getProperty("wmkb.benchmark")
        ?: System.getenv("WMKB_BENCHMARK")
        ?: "false").toBoolean()
}
if (benchmarkBuild) include(":benchmark")
// Host-side dictionary compiler: turns dictionaries-src/*.txt into the .wmdict
// binary assets at build time, sharing the app's own trie/codec sources so the
// written format can never drift from the reader.
include(":tools:dictc")
// Konsist architecture rules over the whole source tree (tests only).
include(":tools:architecture")
