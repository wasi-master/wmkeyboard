# zinnia-jni: Chinese and Japanese handwriting with zinnia

[zinnia](https://github.com/taku910/zinnia) is Taku Kudo's online handwriting
recogniser: an SVM over stroke-direction features that scores one written
character against every character a model knows. The keyboard uses it where
ML Kit's Digital Ink recogniser is not available or not picked: F-Droid's
build (no ML Kit at all), Cantonese (ML Kit has no model for it), and anyone
who turns on Handwriting > Offline recogniser for Chinese and Japanese.
Kotlin entry point: `com.wasimaster.wmkeyboard.core.handwriting.ZinniaNative`,
wrapped by `ZinniaRecognizer` and `ZinniaModels` in the same package.

The built libraries are committed under `core/intelligence/src/zinnia/jniLibs/`,
so the app builds without an NDK. GitHub builds leave them out of the APK and
pin them (gate `zinnia`), Play builds ship them in the on-demand
`:feature:zinnia` split, and F-Droid deletes the committed copies and builds
them with this script through `native/build-fdroid-libs.sh`, as does the
release workflow for the reference APK F-Droid compares against. The script
maps the build machine's paths out of the library, so two builds with the
same NDK come out byte for byte the same (checked: a second build matched).
Rebuild after you change anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/zinnia-jni/build.sh
```

It needs CMake and Ninja on `PATH`, and fetches zinnia at the pinned commit
unless `ZINNIA_SRC` points at a checkout of it. The committed libraries were
built with NDK r29 (`29.0.14206865`), the one F-Droid's recipe names. A
rebuild takes about fifteen seconds.

## Provenance

zinnia commit `581faa8f6f15e4a7b21964be3a5ec36265c80e5b` (2016, the head of
upstream's repository; release 0.06 plus build fixes), unmodified. Only the
recogniser's four sources are compiled (`character.cpp`, `feature.cpp`,
`recognizer.cpp`, `sexp.cpp`), `-Os`, as a static library linked into one
shared library with `wm_zinnia_jni.cpp` and libc++ static. The trainer, the
SVM solver and the C API wrapper are left out: models are trained elsewhere.
The `config.h` in upstream's tree was generated on someone's machine and is
not used; `CMakeLists.txt` passes the handful of `HAVE_*` defines the sources
test. zinnia is BSD-3-Clause (`app/src/main/assets/licenses/bsd-zinnia.txt`).

## Models

The recogniser is useless without a model, and none ships in the APK or the
library. The three the keyboard offers are the Tegaki project's 0.3 releases,
published byte for byte in the data repository under `cjk/zinnia/` and pinned
by SHA-256 in `core/input/.../zinnia/ZinniaData.kt`:

| Model | Characters | Size | Source of the published file |
|---|---|---|---|
| `handwriting-ja-0.3.model` | 6,420 | 26.1 MB | Debian `tegaki-zinnia-japanese_0.3.orig.tar.gz` |
| `handwriting-zh_CN-0.3.model` | 6,763 (GB 2312) | 26.8 MB | Debian `tegaki-zinnia-simplified-chinese_0.3.orig.tar.gz` |
| `handwriting-zh_TW-0.3.model` | 11,853 (Big5) | 52.2 MB | `tegaki-zinnia-traditional-chinese-0.3.zip`, pkgsrc distfiles; its SHA-1 matches OpenMandriva's record |

All three are LGPL 2.1 (`app/src/main/assets/licenses/lgpl-2.1-tegaki.txt`),
inherited from the Tomoe project's stroke data they are trained on; Tegaki's
releases carry the licence text, and the Traditional Chinese model was
bootstrapped by Tegaki from character components. They are data files read
through `mmap`, so the library links none of their code. The CJK pack
downloader fetches them (raw.githubusercontent serves them gzip-encoded on
the wire), and the offline import installs them by file name.

## Contract with Kotlin

- `nativeOpen(path) -> Long`: a handle over the memory-mapped model, 0 when
  the file is missing or not a zinnia model of the version this reads.
- `nativeClose(handle)`.
- `nativeSize(handle) -> Int`: how many characters the model knows.
- `nativeClassify(handle, size, points, strokeEnds, nbest, scores) -> Array<ByteArray>?`:
  one character in a `size`-unit square, `points` as x, y pairs in writing
  order, `strokeEnds[i]` the number of points once stroke `i` is done. The
  `nbest` best labels come back as UTF-8 bytes, best first, with their scores
  in `scores` when it is not null.

zinnia reads one character per call and takes each point as a share of the
box, so Kotlin does the rest (`ZinniaInk`): it cuts a line of ink into
characters at the gaps between them, scales each by its own bounding box into
the 1000-unit square the Tegaki models were trained in (longer side about
820, centred near 500, 490, as measured over their training sets). A gap can
fall between two characters or between the halves of one (石 and 艮 in 硍),
so every way of joining neighbouring pieces back up (up to three to a
character, up to six pieces) is read, and the one whose characters zinnia
scores highest on average leads. Classification only reads the mapped model,
so it is safe on any thread; Kotlin still holds one lock per handle so a
close cannot free the model under a running call.

## Checking it on a desktop

`build.sh --desktop DIR` builds the same sources for the machine it runs on
(a `.dylib` on macOS) with the JNI headers of `JAVA_HOME` or the NDK. Loaded
into a plain JVM with `ZinniaNative`, `ZinniaInk` and `ZinniaRecognizer`
compiled by kotlinc, and fed the models' own training characters drawn the
way a finger would (resampled, scaled to 300 to 480 px at a random place on
a 1080 x 520 canvas, rotated and sheared up to about 5 degrees, jittered):

| Model (240 characters, 60 pairs) | Top 1 | Top 4 | Two characters in one line, top 1 |
|---|---|---|---|
| Japanese | 96.3% | 99.2% | 91.7% |
| Simplified Chinese | 98.8% | 100% | 100% |
| Traditional Chinese | 93.3% | 99.6% | 91.7% |

These are the training characters, so real handwriting will do worse; the
point of the run is that the binding, the box fit and the segmentation work.
Characters whose parts stand apart (川 八 小 什 竹 好 明 林) stayed whole in
every model. Before joinings were weighed, a plain cut at every gap read
Traditional Chinese pairs right only 57% of the time, splitting left-right
characters into their halves. Measured on a machine at a load average near
80, so the per-character times it printed (60 to 180 ms) say little about a
phone.

## ABIs

arm64-v8a and armeabi-v7a, API 24, 16 KB page aligned, about 450 KB
(arm64) each, most of it libc++'s streams, which zinnia's error messages use.
Only the four `Java_..._ZinniaNative_*` functions are exported.
