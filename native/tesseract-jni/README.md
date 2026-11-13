# tesseract-jni: offline text recognition for most scripts

Tesseract 5.5.1 and Leptonica 1.85.0 behind a five-function JNI surface, for
the text scanner. Kotlin entry point:
`com.wasimaster.wmkeyboard.core.ocr.TesseractNative` (in
`core/intelligence/src/main`). ML Kit keeps reading Latin script; this covers
the scripts it cannot (issue #306).

The prebuilt `libwmtess.so` files are committed under
`core/intelligence/src/tesseract/jniLibs/<abi>/`, so the app builds without an
NDK. A directory of its own lets Play's `:feature:ocr` split carry it alone.
F-Droid `scandelete`s it and does not rebuild it, so its build has no
Tesseract and the scanner's offline reads stay unavailable there. Rebuild after you change anything here:

```sh
# Needs NDK r29 (29.0.14206865), CMake 3.22+ and Ninja.
ANDROID_NDK=/opt/homebrew/share/android-ndk native/tesseract-jni/build.sh
```

or run the **Tesseract native build** workflow by hand and commit its
artifact. The sources come from Tesseract4Android 4.9.0 (pinned by commit in
`build.sh`), which vendors both libraries with the Android config headers.

## Why the library is small

The published Tesseract4Android AAR is 7.17 MB per ABI (arm64). This build
is 1.68 MB, from the same sources:

| Step | arm64 stripped |
|---|---:|
| Tesseract4Android AAR (4 libraries) | 7.17 MB |
| One library, everything static, `-Os`, LTO, `--gc-sections`, only JNI exported | 2.61 MB |
| No libpng / libjpeg (the app passes decoded bitmaps) | 2.32 MB |
| No legacy engine (upstream's `TESSERACT_SRC_LEGACY`; `tessdata_fast` is LSTM only) | 1.77 MB |
| `lstm/` and `arch/` back at `-O2`, the recognition hot path | 1.82 MB |
| Own JNI (5 functions) instead of Tesseract4Android's 113 | 1.68 MB |

armeabi-v7a is 1.15 MB. `-Oz` saves another ~0.13 MB and
was not taken, because it would also shrink the matrix code the reads spend
their time in.

## Contract with Kotlin

- `nativeCreate(dataDir, languages) -> Long`: 0 when the data is missing or
  unreadable. `dataDir` is the `tessdata` directory itself (Tesseract 5 does
  not append `tessdata/`). `languages` is a pack name such as `ben`, or
  `ben+eng`. The engine is LSTM only.
- `nativeRecognize(handle, bitmap, thresholding) -> String?`: the bitmap
  must be ARGB_8888. `thresholding` is Tesseract's `thresholding_method`
  (0 Otsu, 2 Sauvola). Words are split by spaces and lines by newlines.
  Empty when the photo held no text; null only when the read failed (the
  bitmap is unusable, the engine errored) or was cancelled.
- `nativeCancel(handle)` is safe from any thread while a read runs.
- `nativeDestroy(handle)`, `nativeVersion()`.
- One engine is not safe to share across threads. `TesseractOcr` keeps every
  call on one thread of its own.

## Traps found on the way

- Tesseract's default thresholding is one global Otsu threshold for the
  whole image. Phone photos are never evenly lit: vignetting, a shadow or
  the torch's hot spot and the threshold turns a whole region black, text
  and all, so a sharp photo of print came back as no text at all. The
  keyboard reads with Sauvola (a local threshold) and also with Otsu, which
  still wins on light text on a dark screen, and keeps the better read.
  Reproduce on a desktop with `tesseract photo.png - --oem 1 --psm 3 -c
  thresholding_method=0` against `=2`.

- Tesseract4Android's `Java_*` functions are not marked `JNIEXPORT`. Built with
  `-fvisibility=hidden` they vanish and the linker drops everything (a
  bogus 0.44 MB). The version script `jni.map` is what decides exports here.
- CMake 4 no longer ships `AndroidNdkModules`, which the upstream armv7 build
  uses for `cpufeatures`; `CMakeLists.txt` builds that file directly.
