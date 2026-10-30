#!/usr/bin/env bash
# Phase 0 benchmark harness for native/RUST_NEON_PLAN.md. No Gradle: compiles
# Phase0Bench.kt with the Kotlin compiler from the Gradle cache against the
# release classes jars a previous build left in a checkout (plus Samplers.kt,
# PaddleOcrPipeline.kt, VectorIndex.kt, MeaningRank.kt, NativeEngine.kt,
# GifEncoder.kt, FrostBake.kt, UpscaleTiles.kt and the Kotlin GIF encoder kept in the tests from source,
# which shadow the jars' copies, so a kernel's before and after time this
# checkout's code; EXTRA_SRC adds more), then
#   host     runs it on this machine's JVM
#   dex      d8s it (plus the jars and kotlin-stdlib) into out/bench.dex
#   device   pushes the dex and runs it under ART with app_process
#
# Usage: native/bench/phase0/run.sh <host|dex|device> [only=vector,gif,ocr,image,glide,audio,frost,upscale,webm,fuzzy,cjk,phonetic] [budget=1]
# Env:   CHECKOUT  checkout whose core/*/build jars to use (default: this one)
#        DATA_REPO wmkeyboard-data checkout for only=cjk,phonetic (default: ~/Work/wmkeyboard-data)
#        WM_NATIVE=kotlin  the Kotlin paths and scalar kernels (the "before"); the
#                  host library is found from the working directory, as in tests
#        OUT       work dir (default: native/bench/phase0/out, gitignored)
#        PIN       a taskset mask for the phone run (c0: the CPH2481's two A76 cores)
#        ENGINE_SO the arm64 libwm_engine_jni.so to push (default: the committed one)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
CHECKOUT="${CHECKOUT:-$REPO}"
OUT="${OUT:-$HERE/out}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
GC="$HOME/.gradle/caches/modules-2/files-2.1"
KV=2.4.21
mode="${1:-host}"; shift || true

cached() { find "$GC/$1" -name "$2" ! -name '*sources*' | sort | tail -1; }
STDLIB="$(cached org.jetbrains.kotlin/kotlin-stdlib/$KV "kotlin-stdlib-$KV.jar")"
KCP="$(cached org.jetbrains.kotlin/kotlin-compiler-embeddable/$KV '*.jar'):$STDLIB:$(cached org.jetbrains.kotlin/kotlin-script-runtime '*.jar'):$(cached org.jetbrains.kotlin/kotlin-reflect '*.jar'):$(cached org.jetbrains.kotlin/kotlin-daemon-embeddable/$KV '*.jar'):$(cached org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.11.0 '*.jar'):$(cached org.jetbrains/annotations '*.jar')"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
# KeyProximity's layouts are kotlinx.serialization classes.
SERIAL="$(cached org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.12.0 '*.jar'):$(cached org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.12.0 '*.jar')"
# SuggestionEngine, for the phonetic group.
COROUTINES="$(cached org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.11.0 '*.jar')"

MODULES="common config content intelligence prediction voice language input emoji"
mkdir -p "$OUT/lib"
LIBS=""
for m in $MODULES; do
  src="$CHECKOUT/core/$m/build/intermediates/runtime_library_classes_jar/release/bundleLibRuntimeToJarRelease/classes.jar"
  [ -f "$src" ] || { echo "missing $src: build that checkout once first" >&2; exit 1; }
  cp "$src" "$OUT/lib/core-$m.jar"
  LIBS="$LIBS:$OUT/lib/core-$m.jar"
  # CjkConfig's enums name their string resources while they load.
  r="$CHECKOUT/core/$m/build/intermediates/compile_r_class_jar/release/generateReleaseRFile/R.jar"
  if [ -f "$r" ]; then
    cp "$r" "$OUT/lib/core-$m-r.jar"
    LIBS="$LIBS:$OUT/lib/core-$m-r.jar"
  fi
done
LIBS="${LIBS#:}"
FRIENDS="$(echo "$LIBS" | tr ':' ',')"

if [ ! -f "$OUT/classes/com/wasimaster/wmkeyboard/bench/Phase0Bench.class" ] || [ "$HERE/Phase0Bench.kt" -nt "$OUT/classes/com/wasimaster/wmkeyboard/bench/Phase0Bench.class" ]; then
  rm -rf "$OUT/classes"
  # The fuzzy walk's bridge, so it matches this checkout's engine library
  # when the jars come from an older build.
  PRED="$REPO/core/prediction/src/main/java/com/wasimaster/wmkeyboard/core/prediction"
  java -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 \
    -Xfriend-paths="$FRIENDS" -cp "$STDLIB:$LIBS:$ANDROID_JAR" -d "$OUT/classes" "$HERE/Phase0Bench.kt" \
    "$REPO/core/intelligence/src/main/java/com/wasimaster/wmkeyboard/core/imagegen/Samplers.kt" \
    "$REPO/core/intelligence/src/main/java/com/wasimaster/wmkeyboard/core/ocr/PaddleOcrPipeline.kt" \
    "$REPO/core/intelligence/src/main/java/com/wasimaster/wmkeyboard/core/semantic/VectorIndex.kt" \
    "$REPO/core/intelligence/src/main/java/com/wasimaster/wmkeyboard/core/semantic/MeaningRank.kt" \
    "$REPO/core/language/src/main/java/com/wasimaster/wmkeyboard/core/engine/NativeEngine.kt" \
    "$REPO/core/content/src/main/java/com/wasimaster/wmkeyboard/core/media/GifEncoder.kt" \
    "$REPO/core/theme/src/main/java/com/wasimaster/wmkeyboard/core/theme/FrostBake.kt" \
    "$PRED/FuzzyBeamSearch.kt" "$PRED/NativeFuzzySearch.kt" "$PRED/NativeWordKey.kt" "$PRED/PredictionNative.kt" "$PRED/TouchModel.kt" \
    "$REPO/core/intelligence/src/main/java/com/wasimaster/wmkeyboard/core/upscale/UpscaleTiles.kt" \
    "$REPO/core/intelligence/src/main/java/com/wasimaster/wmkeyboard/core/upscale/UpscaleModels.kt" \
    "$REPO/feature/ime/src/test/java/com/wasimaster/wmkeyboard/core/media/reference/KotlinGifEncoder.kt" ${EXTRA_SRC:-}
fi

# The cjk and phonetic groups' packs, from the data repo: one flat directory.
DATA_REPO="${DATA_REPO:-$HOME/Work/wmkeyboard-data}"
wants_data=""
case " $* " in *only=*cjk*|*only=*phonetic*) wants_data=1 ;; esac
if [ -n "$wants_data" ]; then
  mkdir -p "$OUT/data"
  cp "$DATA_REPO"/cjk/pinyin_v2.tsv "$DATA_REPO"/cjk/pinyin_ngrams.tsv "$DATA_REPO"/cjk/ja_kana.tsv "$DATA_REPO"/cjk/eval/pinyin_eval.tsv \
    "$REPO"/app/src/main/assets/dictionaries/pinyin_syllables.txt "$REPO"/app/src/main/assets/dictionaries/han_levels.txt "$OUT/data/"
  gunzip -c "$DATA_REPO/data/bn/bn_full_v2.txt.gz" > "$OUT/data/bn_full_v2.txt"
  gunzip -c "$DATA_REPO/data/bn/bn_phonetic_v1r2.wmpi.gz" > "$OUT/data/bn_phonetic.wmpi"
fi

case "$mode" in
  host)
    # android.jar last: its stubs only satisfy class loading; nothing here calls them.
    # NativeEngine finds this checkout's host build (native/wm-engine-jni/build-host.sh).
    java -Xss8m -cp "$OUT/classes:$STDLIB:$SERIAL:$COROUTINES:$LIBS:$ANDROID_JAR" com.wasimaster.wmkeyboard.bench.Phase0Bench "$REPO/app/dictionaries-src/en.txt" ${wants_data:+data=$OUT/data} "$@"
    ;;
  dex|device)
    if [ ! -f "$OUT/bench.dex.jar" ] || [ "$OUT/classes" -nt "$OUT/bench.dex.jar" ]; then
      (cd "$OUT/classes" && jar cf "$OUT/bench-classes.jar" .)
      # The classes built from source above replace the jars' copies, which
      # d8 would otherwise reject as defined twice.
      fresh=$(cd "$OUT/classes" && find com -name '*.class' ! -path 'com/wasimaster/wmkeyboard/bench/*')
      for jar in $(echo "$LIBS" | tr ':' ' '); do zip -q -d "$jar" $fresh >/dev/null 2>&1 || true; done
      "$SDK/build-tools/36.0.0/d8" --release --min-api 31 --lib "$ANDROID_JAR" --output "$OUT/bench.dex.jar" \
        "$OUT/bench-classes.jar" "$STDLIB" $(echo "$SERIAL" | tr ':' ' ') $COROUTINES $(echo "$LIBS" | tr ':' ' ')
    fi
    [ "$mode" = dex ] && exit 0
    if [ -n "$wants_data" ]; then
      adb shell mkdir -p /data/local/tmp/wmbench-data
      adb push "$OUT/data/." /data/local/tmp/wmbench-data >/dev/null
    fi
    adb push "$OUT/bench.dex.jar" /data/local/tmp/wmbench.jar >/dev/null
    adb push "$REPO/app/dictionaries-src/en.txt" /data/local/tmp/wmbench-en.txt >/dev/null
    # The committed arm64 engine, named outright: app_process has no APK to load it from.
    adb push "${ENGINE_SO:-$REPO/core/language/src/main/jniLibs/arm64-v8a/libwm_engine_jni.so}" /data/local/tmp/libwm_engine_jni.so >/dev/null
    adb shell "WM_ENGINE_LIB=/data/local/tmp/libwm_engine_jni.so CLASSPATH=/data/local/tmp/wmbench.jar ${PIN:+taskset $PIN }app_process /system/bin com.wasimaster.wmkeyboard.bench.Phase0Bench /data/local/tmp/wmbench-en.txt ${wants_data:+data=/data/local/tmp/wmbench-data} $*"
    ;;
esac
