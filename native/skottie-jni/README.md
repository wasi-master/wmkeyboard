# skottie-jni: Skia's Lottie player, for sticker templates

[Skottie](https://skia.org/docs/user/modules/skottie/) is the Lottie player in
Google's Skia graphics library. Lottie (airbnb), which draws every other
animation in the app, stays the default. Skottie is for the imported sticker
templates whose text layers use Skottie's own text extensions in the text
document (`t.d.k[].s`), which Lottie ignores:

| Key | What it does |
|---|---|
| `rs` | resize: 1 scales the text to fit its box, 2 only scales it down |
| `mf`, `xf` | the smallest and largest size that resizing may pick |
| `xl` | the most lines the text may wrap to while it is resized |
| `vj` | vertical alignment in the box: top, centre or bottom, by line metrics (0 to 2) or by the glyphs as drawn (3 to 5) |

All four are part of Skottie's text adapter and always on. The text is
written into the JSON before `load`, so the bridge has no text API. Kotlin
entry point: `com.wasimaster.wmkeyboard.core.media.skottie.SkottieNative`,
wrapped by `SkottieAnimation` in the same package.

The built libraries are committed under `core/content/src/skottie/jniLibs/`,
so the app builds without an NDK. It is gate `skottie` in
`native/gates.json`: GitHub builds leave it out of the APK and pin it for
download, Play builds ship it in the on-demand `:feature:skottie` split, and
F-Droid deletes the committed copies and builds them with this script through
`native/build-fdroid-libs.sh`. Rebuild after you change anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/skottie-jni/build.sh
```

It needs CMake, Ninja, Python 3 and a host C++ compiler on `PATH`. It fetches
Skia at the pinned branch unless `SKIA_SRC` points at a checkout of it, then
only the ten third-party trees this build compiles or reads, each at the
commit Skia's `DEPS` pins and as one commit with no history; ICU, HarfBuzz,
Expat and libgrapheme's Unicode data are fetched sparsely, only the paths
compiled. gn is built from source at the revision Skia's `bin/fetch-gn` names,
so no prebuilt binary takes part. Skia's objects stay in the checkout's
`out/wm-<abi>`, so a second build with `SKIA_SRC` set is incremental. From
nothing, an arm64 build took 3 minutes on an M-series Mac (fetching, gn, then
Skia) and both ABIs about 6; a rebuild of the JNI alone takes seconds. The
committed libraries were built with NDK r29 (`29.0.14206865`), the one
F-Droid's recipe names. The build machine's paths are mapped out of every
object and gn compiles with paths relative to the checkout, so two builds with
the same NDK come out byte for byte the same: a build from a fresh clone in a
temporary directory matched the committed two.

## Provenance

Skia branch `chrome/m158`, commit `37e919d8669ebe4a8912dcef03db14b9b55f70c7`,
with one fix in `patches/` (below), built with its own gn files as an
official (release) build with these arguments, in `build.sh`:

- CPU raster only: no Ganesh, Graphite, GL, Vulkan, Metal or Dawn.
- No PDF, SVG or XPS backend, no tools, no tracing, no Perfetto.
- Decoders for PNG (libpng, zlib), JPEG (libjpeg-turbo) and WebP (libwebp)
  for templates' image assets; no GIF, RAW, AVIF or JPEG XL, and no encoders.
- Text: HarfBuzz shaping, FreeType, and SkUnicode on libgrapheme with ICU's
  bidi code only (about 15 files, no ICU data file), which is the smaller of
  the SkUnicode back ends that still breaks lines and finds scripts the way
  Unicode says. Built-in ICU would add its data file, megabytes of it.
- Fonts: Android's font manager (`/system/etc/fonts.xml`, read with Expat)
  on a phone; on a desktop build only the fonts a caller passes.
- `skia_enable_optimize_size`, which compiles with `-Oz` and drops some fast
  paths. Every third-party tree is the bundled one, never the build
  machine's.

Skottie's SkSL runtime effects stay in: the standard After Effects effects
it supports (hue and saturation, fractal noise, threshold, bulge, sphere,
displacement map, black and white) and some blend modes are runtime effects
too. The JNI is linked against the static libraries with `--gc-sections`,
`--icf=all`, `--exclude-libs,ALL`, a version script and `--strip-all`.

The patch, `skunicode-libgrapheme-toupper.patch`, fixes upper-casing in
SkUnicode's libgrapheme back end, which Skottie runs for a text layer with
`"ca": 1` (all caps). Upstream sized the output to exactly the input's bytes,
but libgrapheme always writes its terminating NUL inside the buffer, so the
last byte of every string was lost: THANKS drew as THANK, and ধন্যবাদ lost
the last byte of দ and came out garbled. It also cut short any text that
grows when upper-cased (ß becomes SS). The fix asks libgrapheme for the
length first and leaves room for the NUL. `build.sh` applies each patch once
and leaves a checkout that already has it alone.

Skia is BSD-3-Clause, HarfBuzz MIT, FreeType the FreeType License (FTL, one
of its two), libgrapheme ISC, ICU Unicode-3.0, libpng its own licence,
libjpeg-turbo the IJG licence and BSD-3-Clause, zlib its own, Expat MIT and
libwebp BSD-3-Clause; all of them are in
`app/src/main/assets/licenses/skottie-third-party.txt` and credited on the
About screen.

## Contract with Kotlin

- `load(json, resourceDir, fontPaths, fontFamilies) -> Long`: 0 when the JSON
  does not parse. The JSON crosses as UTF-16 and is encoded to real UTF-8 in
  the bridge (JNI's modified UTF-8 would spell an emoji as two surrogates).
  `resourceDir` is where image assets' `u`/`p` paths are read from; base64
  `data:` URIs need none. Images are decoded once, at load.
- Each of `fontPaths` is registered under the name at the same index of
  `fontFamilies`, or its own family name when that is empty. A template's
  font finds it by `fName` (Skottie asks the resource provider) or by
  `fFamily` (it asks the font manager), case ignored. The passed fonts come
  before the system's in fallback too, so a Latin template draws Bengali text
  from a Bengali font passed beside it.
- `width`, `height` (the animation's canvas, rounded up), `fps`, `duration`
  (seconds).
- `render(handle, frameTimeSeconds, bitmap) -> Boolean`: an `ARGB_8888`
  bitmap, drawn into where it lies (`AndroidBitmap_lockPixels`), cleared to
  transparent and the animation scaled to fit it.
- `renderPixels(handle, frameTimeSeconds, width, height, argb) -> Boolean`:
  the same into unpremultiplied `0xAARRGGBB` ints, for tests on a desktop
  JVM, which has no Bitmap.
- `release(handle)`.

A handle is not safe for two threads at once, since seeking moves the
animation's state; `SkottieAnimation` serialises its calls. Skottie's own
warnings and errors go to logcat under `WMSkottie`.

## Checking it on a desktop

`build.sh --desktop DIR` builds the same sources for the machine it runs on
(a `.dylib` on macOS). `SkottieNative` and `SkottieAnimation` compile with
kotlinc against `android.jar` (compile only), stand-ins for `FeatureModules`
and `ModuleGate`, and `GatedLibrary` lifted verbatim out of `ModuleGate.kt`.
The test template is 512×512, one second at 30 fps: a rounded square turning
under a text layer that grows from 70 to 100 per cent, the text white with a
dark stroke, its font `fName` `Inter-Bold`. It is loaded with Inter and Noto
Sans Bengali (`play/pipeline/fonts/`) and rendered at the first and the last
frame:

| Case | Text | Extensions | Result |
|---|---|---|---|
| bn-fit | ধন্যবাদ | `rs` 1, `mf` 20, `xf` 300, `vj` 4, box 440×300 | grown to fill the box's width, centred by ink |
| bn-lines | আপনাকে অনেক অনেক ধন্যবাদ বন্ধু | `rs` 2, `mf` 10, `xl` 2, `vj` 4, box 400×260 | two lines, broken between words |
| latin | Thank you so much! | `rs` 1, `xl` 3, `vj` 4 | three lines of Inter, found by `fName` |
| bn-plain | ধন্যবাদ | none | 90 px at the top of the box |

All caps (`"ca": 1`), the template-pack session's repro plus edge cases:
THANKS and thanks both draw THANKS (the same as `"ca": 0`), ধন্যবাদ draws
whole, straße draws STRASSE, "thanks ধন্যবাদ" draws both words, and an empty
text draws nothing without failing.

The Bengali came from Noto Sans Bengali by fallback, since the template's
font is Inter, and it is shaped correctly: the য-ফলা of ন্য and the ন্ধ
conjunct of বন্ধু match the same words set by Pillow with raqm. A frame took 1 to
3 ms on an M-series Mac (44 ms for all 30 frames of bn-lines); a JSON that
does not parse returns 0, and a released handle renders nothing.

## ABIs

arm64-v8a and armeabi-v7a, API 24, 16 KB page aligned: 4,639,592 and
2,997,240 bytes. Only the eight `Java_..._SkottieNative_*` functions are
exported, and the library needs nothing but libc, libm, libdl, liblog and
libjnigraphics.
