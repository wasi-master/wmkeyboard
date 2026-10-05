# R8 rules for the release build (isMinifyEnabled + isShrinkResources +
# optimization { enable = true }, i.e. R8 full mode).
#
# Full mode assumes nothing is reached by reflection unless a rule says so, so
# every rule below exists because something resolves a name at runtime: a native
# library, a serializer, or a string previously written to disk.
#
# Rule of thumb for this app: if a name can end up inside a DataStore
# preference, an exported .wmconfig/.wmlayout/.wmtheme file, or a JNI symbol, it
# must not be renamed. Breaking one of those is invisible in debug builds and
# quietly corrupts a user's saved settings in release.

# --- Crash reports stay readable ---------------------------------------------
# Line numbers survive so a release stack trace can be de-obfuscated with
# build/outputs/mapping/<variant>/mapping.txt. SourceFile is flattened to a
# constant so the original file names are not shipped.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Signature/InnerClasses/EnclosingMethod are what kotlinx.serialization reads to
# reconstruct generic types (Map<String, ThemeSpec>, List<KeyAction>). Without
# them a generic serializer resolves to raw types and decoding fails at runtime
# only.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# --- Enums persisted by name -------------------------------------------------
# ~30 settings enums round-trip through DataStore and the config backup as their
# constant name: `ThemeMode.valueOf(stored)`, `"${tool.name}=%08X"`, and the
# same for ToolbarTool, HapticStyle, ScreenReaderMode, SpaceSwipeAction,
# NumeralSystem, EmojiTabMode and the rest. If R8 renames a constant or unboxes
# the enum, every previously saved setting decodes to null and silently reverts
# to its default, and exported config files stop importing.
#
# The default Android rules keep values()/valueOf but not the constant *fields*,
# and the field name is exactly what has to match the stored string.
-keepclassmembers enum com.wasimaster.wmkeyboard.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- kotlinx.serialization ---------------------------------------------------
# The library ships consumer rules covering ordinary @Serializable classes.
# These add the parts that matter for this app's *file formats* — .wmlayout,
# .wmtheme, .wmicons, .wmstickers, snippets, symbol sets, and the clipboard and
# lexicon stores — so a rules regression cannot make a user's saved packs
# undecodable in release only.
-keepclassmembers @kotlinx.serialization.Serializable class com.wasimaster.wmkeyboard.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.wasimaster.wmkeyboard.**$$serializer { *; }

# KeyAction is a sealed interface whose implementations are @Serializable data
# objects discriminated by @SerialName ("text", "shift", "space", ...). That tag
# is written into every stored layout, and LayoutSpec.kt registers a
# polymorphicDefaultDeserializer against KeyAction::class so an unknown tag
# degrades to KeyAction.Unknown instead of losing the layout. Keep the hierarchy
# so both the discriminator lookup and the sealed-subclass registration resolve.
-keep class com.wasimaster.wmkeyboard.core.layout.KeyAction { *; }
-keep class com.wasimaster.wmkeyboard.core.layout.KeyAction$* { *; }

# --- Harper grammar engine (JNI) ---------------------------------------------
# libharper_jni.so binds by the mangled Java name
# (Java_com_wasimaster_wmkeyboard_core_grammar_HarperNative_*), so neither the
# class name nor the external method names may be renamed or stripped.
-keep class com.wasimaster.wmkeyboard.core.grammar.HarperNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The grammar lint models are decoded from JSON produced by that native code —
# keep their serializers explicitly so a rules regression can never silently
# break offline grammar checking.
-keep,includedescriptorclasses class com.wasimaster.wmkeyboard.core.grammar.**$$serializer { *; }
-keepclassmembers class com.wasimaster.wmkeyboard.core.grammar.* {
    *** Companion;
}
-keepclasseswithmembers class com.wasimaster.wmkeyboard.core.grammar.* {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- LiteRT-LM on-device models (JNI, full flavor) ---------------------------
# The AAR ships no consumer rules, and its native side calls back into Kotlin by
# name (MessageCallback, config objects serialized over JNI via gson) — keep the
# whole surface so R8's release optimization can't rename what the .so resolves
# at runtime.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# --- LiteRT / TF-Lite classic runtime (Whisper, full flavor) -----------------
# org.tensorflow.lite.Interpreter is a thin Java shell over JNI: the native side
# looks up these classes and their fields by name, and the delegates (NNAPI,
# GPU) are discovered reflectively and may be absent from the APK entirely.
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# --- LuaJ (plugin sandbox) ---------------------------------------------------
# Almost no -keep rules, on purpose. PluginSandbox constructs every library class
# it wants directly, so R8 keeps the interpreter by reference and strips the
# parts nothing points at — luajava (Java interop), luajc (bytecode backend) and
# the JSR-223 script engine. That stripping is a security property in its own
# right: the reflective Java-coercion surface never ships. Only the warnings need
# silencing, because those stripped corners reference optional dependencies that
# are on no classpath here (the POM declares none).
#
# The AST parser (org.luaj.vm2.parser, org.luaj.vm2.ast) does ship, for the plugin
# editor's language service in core.plugins.lua, and it is not part of that
# property. It turns text into a tree. It references nothing outside its own two
# packages but LuaValue, LuaString and LuaBoolean; it has no class$ or
# Class.forName idiom, so it needs no keep rule; and it cannot compile or run
# anything, because LuaC has its own lexer and never reads the tree. No plugin can
# reach it either: the sandbox installs no require, package, luajava or load.
# Measured with R8 over the luaj jar alone, keeping every AST node public: 50 KB of
# dex, 23 KB compressed. LuaLanguageShrinkerTest pins that the three stripped
# corners stay unreferenced.
-dontwarn org.apache.bcel.**
-dontwarn javax.script.**
-dontwarn org.luaj.vm2.luajc.**
-dontwarn org.luaj.vm2.script.**

# The exception, and it is not optional. LuaJ is compiled at Java 1.4 source
# level, where `Foo.class` is not a class constant but a `class$("...")` helper
# that hands a *string* to Class.forName. Bit32Lib binds its two function classes
# that way, so the dex references them nowhere, R8 strips them as dead, and the
# first plugin to load dies at Bit32Lib's own install with NoClassDefFoundError —
# in release builds only, which is why no test and no debug run ever saw it.
# LibFunction.bind then calls newInstance, so the no-arg constructor has to stay
# with the class. Naming the two classes rather than keeping org.luaj.vm2.lib.**
# is deliberate: the wider rule would drag PackageLib and LuajavaLib back into
# the APK, and their absence is the sandbox's outermost wall.
-keep class org.luaj.vm2.lib.Bit32Lib$Bit32Lib2 { <init>(); }
-keep class org.luaj.vm2.lib.Bit32Lib$Bit32LibV { <init>(); }

# And keeping them is only half of it, because both classes are package-private
# and so is the constructor bind reaches. R8 repackages what it can into the root
# package, which moved LibFunction — the class that actually calls newInstance —
# out of org.luaj.vm2.lib while the two -keep rules above pinned its targets to
# it. Same-package access became cross-package access and the load failed a step
# later with IllegalAccessException instead. Pinning the package names costs
# nothing: the class names inside them are still obfuscated, and nothing here is
# reached by name from outside.
-keeppackagenames org.luaj.**

# --- AndroidX WorkManager & Room (transitive ML Kit dependency) --------------
# Room database implementations (e.g. WorkDatabase_Impl) are instantiated reflectively
# by androidx.startup during app launch.
-keep class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-keep class androidx.work.impl.WorkDatabase_Impl {
    <init>(...);
}
-dontwarn androidx.work.impl.**

# --- ML Kit component registrars (full flavor) --------------------------------
# ML Kit finds its pieces the Firebase way: each library's manifest lists a
# registrar class by name as <meta-data> on MlKitComponentDiscoveryService, and
# ComponentDiscovery does Class.forName + getDeclaredConstructor().newInstance()
# on every one. firebase-components ships `-keep class * implements
# ComponentRegistrar`, which is enough for ProGuard and for R8 in compat mode,
# where a kept class keeps its no-arg constructor for free. Full mode keeps only
# what the rule names, and this rule names no members, so R8 sees a constructor
# nothing in the dex calls and removes it. Discovery then hits
# NoSuchMethodException, logs "Could not instantiate ..." and moves on with an
# empty component list. The first getClient() call — the document scanner in
# DocScanActivity.onCreate, the barcode scanner and the text recognizer inside
# their panels' remember {} — then reads SharedPrefManager out of that empty
# list and dies with a bare NullPointerException from the telemetry logger's
# constructor. Release only (#146); debug and `fast` never shrink.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}

# --- Google's shaded "shared Random" classes (full flavor) --------------------
# The GMS-built ML Kit jars carry Guava-style Random subclasses whose setSeed
# throws once a `final boolean` field is true. That field is assigned after
# super() returns, and java.util.Random's constructor calls setSeed from inside
# super(), so at that moment the field is still false and the call goes
# through. R8 sees the field only ever written `true`, folds the check to
# "always throw" and merges the two classes, so the class initializer that
# builds them dies with "Setting the seed on the shared Random object is not
# permitted" wrapped in ExceptionInInitializerError. In Digital Ink that
# initializer sits under the model download manager, so every handwriting
# download failed at once in release (#235); debug and `fast` never shrink.
# Keeping the classes and their fields stops the folding and the merge.
-keep class com.google.android.gms.internal.** extends java.util.Random {
    <fields>;
}


# --- On-device AI runtime bridge ---------------------------------------------
# LitertLmRuntime is reached ONLY by reflection (LocalLlmEngine's facade):
# from the base APK in sideload builds, from the on-demand :feature:llm split
# in Play builds. Nothing references it statically either way, so without this
# rule R8 strips it and On-device AI dies at Class.forName. Keep the whole
# class: its interface methods are only provably reachable once the reflective
# construction is visible, which it never is to R8.
-keep class com.wasimaster.wmkeyboard.core.localllm.bridge.LitertLmRuntime {
    <init>();
    *;
}

# --- On-device translation bridge ---------------------------------------------
# The same arrangement and the same reason: MlKitTranslateRuntime is reached
# ONLY by reflection (OnDeviceTranslator's facade), from the base APK in
# sideload builds and from the on-demand :feature:translate split in Play
# builds.
-keep class com.wasimaster.wmkeyboard.core.translate.bridge.MlKitTranslateRuntime {
    <init>();
    *;
}

# --- Native Whistle and LiteRT sticker bridge -------------------------------
# JNI symbol names bind directly to WhistleEngine's external methods.
-keep class com.wasimaster.wmkeyboard.core.voice.whistle.WhistleEngine { *; }

# --- LiteRT bridge (sticker background remover) ------------------------------
# Same again: both are reached ONLY by reflection (WhisperEngine's and
# LocalSubjectCutout's facades), from the base APK in sideload builds and from
# the on-demand :feature:litert split in Play builds.
-keep class com.wasimaster.wmkeyboard.core.voice.whisper.bridge.LitertWhisperRuntime {
    <init>();
    *;
}
-keep class com.wasimaster.wmkeyboard.core.stickers.bridge.LitertCutoutRuntime {
    <init>();
    *;
}

# --- Handwriting bridge --------------------------------------------------------
# Reached ONLY by reflection (HandwritingModels' facade), from the base APK in
# sideload builds and from the on-demand :feature:handwriting split in Play.
-keep class com.wasimaster.wmkeyboard.core.handwriting.bridge.MlKitInkRuntime {
    <init>();
    *;
}

# --- JSch (SFTP backup location) ----------------------------------------------
# JSch builds every algorithm with Class.forName on a name from its config
# table, so R8 sees no reference to any of them and would strip them all. Keep
# exactly the classes core/settings' SftpAlgorithms can reach, each with the
# no-arg constructor JSch calls; NewBackupLocationsTest in :app fails when the two
# drift apart. Everything else in JSch (ChannelSftp, Kerberos, zlib, agents,
# the Bouncy-Castle-only ciphers nobody offers) goes, which is the point of
# naming them rather than keeping com.jcraft.jsch.**.
-keep class com.jcraft.jsch.jce.** { <init>(); }
-keep class com.jcraft.jsch.jbcrypt.JBCrypt { <init>(); }
-keep class com.jcraft.jsch.bc.XDH { <init>(); }
-keep class com.jcraft.jsch.bc.MLKEM768 { <init>(); }
-keep class com.jcraft.jsch.bc.SNTRUP761 { <init>(); }
-keep class com.jcraft.jsch.bc.SignatureEd25519 { <init>(); }
-keep class com.jcraft.jsch.bc.KeyPairGenEdDSA { <init>(); }
-keep class com.jcraft.jsch.bc.ChaCha20Poly1305 { <init>(); }
-keep class com.jcraft.jsch.DH25519MLKEM768 { <init>(); }
-keep class com.jcraft.jsch.DH25519SNTRUP761 { <init>(); }
-keep class com.jcraft.jsch.DH25519 { <init>(); }
-keep class com.jcraft.jsch.DHEC256 { <init>(); }
-keep class com.jcraft.jsch.DHEC384 { <init>(); }
-keep class com.jcraft.jsch.DHEC521 { <init>(); }
-keep class com.jcraft.jsch.DHGEX256 { <init>(); }
-keep class com.jcraft.jsch.DHGEX1 { <init>(); }
-keep class com.jcraft.jsch.DHG14 { <init>(); }
-keep class com.jcraft.jsch.DHG14256 { <init>(); }
-keep class com.jcraft.jsch.DHG16 { <init>(); }
-keep class com.jcraft.jsch.DHG18 { <init>(); }
-keep class com.jcraft.jsch.CipherNone { <init>(); }
-keep class com.jcraft.jsch.UserAuthNone { <init>(); }
-keep class com.jcraft.jsch.UserAuthPassword { <init>(); }
-keep class com.jcraft.jsch.UserAuthKeyboardInteractive { <init>(); }
-keep class com.jcraft.jsch.UserAuthPublicKey { <init>(); }
# Several of those classes and constructors are package-private, reached from
# JSch's own classes in the same package. Repackaging would split them apart
# and turn the lookup into an IllegalAccessException, as it did for LuaJ above.
-keeppackagenames com.jcraft.jsch.**
# Optional integrations JSch compiles against and never loads here.
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**

# --- Bouncy Castle (SFTP and SMB crypto) --------------------------------------
# Lightweight API only, every class reached by a direct reference, so R8 keeps
# what is used and nothing needs a rule. The jar also carries the JCA provider,
# LDAP stores and the like, which reference classes Android does not have.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jsse.**
