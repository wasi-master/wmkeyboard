# zxingcpp-jni: QR codes and barcodes with zxing-cpp

[zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) is the C++ port of the
ZXing barcode library, maintained by Axel Waggershauser. The QR scanner reads
with it wherever ML Kit's barcode reader cannot run: F-Droid's build (no ML
Kit at all), a Play build on a phone without Play services (Play's ML Kit
artifact takes its model from Play services), and anyone who turns on
QR and barcode scanner › Read codes with zxing-cpp. Kotlin entry point:
`com.wasimaster.wmkeyboard.core.barcode.ZxingNative`, wrapped by
`ZxingReader` in the same package and by `ZxingBarcodeReader` in
`:feature:ime` (`ime/scan`), which sits behind the same `BarcodeReader`
interface as ML Kit's reader.

The built libraries are committed under `core/intelligence/src/zxing/jniLibs/`,
so the app builds without an NDK. GitHub builds leave them out of the APK and
pin them (gate `zxing` in `native/gates.json`), Play builds ship them in the
on-demand `:feature:zxing` split, and F-Droid deletes the committed copies and
builds them with this script through `native/build-fdroid-libs.sh`, as does
the release workflow for the reference APK F-Droid compares against. Rebuild
after you change anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/zxingcpp-jni/build.sh
```

It needs CMake and Ninja on `PATH`, and fetches zxing-cpp at the pinned tag
unless `ZXING_SRC` points at a checkout of it. The committed libraries were
built with NDK r29 (`29.0.14206865`), the one F-Droid's recipe names. A
rebuild takes about 25 seconds. The script maps the build machine's paths out
of the library and links with `--strip-all`, so two builds with the same NDK
come out byte for byte the same: a build from a fresh clone in another
directory matched the committed three.

## Provenance

zxing-cpp tag `v3.1.1`, commit `287c85df6f961c8efbfb5ffd736cd9457b8b890e`,
unmodified. `CMakeLists.txt` adds upstream's own `core/` with its readers on
and everything else off: no writers (so none of the bundled libzint is
compiled), no C API, no examples, tests or wrappers. Every symbology stays
on. The core is a static library linked into one shared library with
`wm_zxing_jni.cpp` and libc++ static, `-Os`, with the linker dropping what
the JNI never reaches. zxing-cpp is Apache-2.0
(`app/src/main/assets/licenses/apache-2.0.txt`); the libzueci copy it
carries for character sets is BSD-3-Clause and includes Bjoern Hoehrmann's
MIT UTF-8 decoder (`bsd-3-clause-zueci.txt`). Both are credited on the About
screen.

## Contract with Kotlin

- `nativeReadLuma(buffer, width, height, rowStride, effort, maxSymbols, formats) -> Array<ByteArray>?`:
  a camera frame's Y plane where CameraX left it, a direct `ByteBuffer`,
  never copied. One JNI crossing per frame.
- `nativeReadPixels(argb, width, height, effort, maxSymbols, formats) -> Array<ByteArray>?`:
  a picture's pixels as `Bitmap.getPixels` fills them, pinned with
  `GetPrimitiveArrayCritical` for the read (which makes no JNI call).

Both look for every format the library reads and return up to `maxSymbols`
codes' text as UTF-8 bytes (never Java strings: modified UTF-8 spells a
character outside the BMP differently, and a QR code can hold an emoji), or
null when there were none, and fill `formats` with each code's zxing-cpp
`BarcodeFormat` value: the ISO/IEC 15424 symbology letter in the low byte,
the variant in the next (`Q` for QR, `E1` for EAN-13). `effort` is a bit set:
1 is zxing-cpp's `tryHarder`, 2 its `tryInvert` (light codes on a dark
ground). Turned linear codes and downscaling are always tried. Text is
zxing-cpp's `TextMode::Plain`, the symbol's content as written, which is what
ML Kit calls the raw value. A C++ exception (bad input) comes back as a
`RuntimeException` once nothing is pinned.

One deliberate difference from upstream: ISO/IEC 15420 normalises UPC codes
to 13-digit EAN-13 content, and zxing-cpp follows it, so a UPC-A symbol comes
back as an EAN-13 starting with 0 and a UPC-E as 13 digits. The bridge gives
them as ML Kit does, UPC-A with its own 12 digits and UPC-E with its 8 (from
zxing-cpp's `UPCE` extra), so the result card and the text that gets typed
are the same with either reader.

Live frames are read at the quick setting, with `tryInvert` on every other
frame (`ZxingBarcodeReader`); `tryInvert` only costs a second pass when the
first found nothing. A picture from the gallery is read with both bits set.

## Checking it on a desktop

`build.sh --desktop DIR` builds the same sources for the machine it runs on
(a `.dylib` on macOS) with the JNI headers of `JAVA_HOME` or the NDK. Loaded
into a plain JVM with `ZxingNative` and `ZxingReader` compiled by kotlinc
(and a stand-in `FeatureModules`), and fed zxing-cpp's own test images
(`test/samples`, the ones with a `.txt` expectation, WebP converted to PNG),
each read three ways: as ARGB pixels at full effort, and as a Y plane with 64
bytes of padding after every row, quick and then with `tryInvert`:

| Samples | Images | Picture | Frame, quick | Frame, with invert |
|---|---:|---:|---:|---:|
| qrcode-1 to -6 | 137 | 124 | 121 | 124 |
| datamatrix-1 to -4 | 90 | 89 | 88 | 88 |
| pdf417-1 to -3 | 56 | 56 | 56 | 56 |
| ean13-1 to -4 | 60 | 39 | 27 | 27 |
| ean8-1 | 9 | 9 | 9 | 9 |
| upca-1, upce-1 | 8 | 8 | 7 | 7 |
| code128-1 to -3 | 22 | 22 | 21 | 21 |
| code39-1, itf-1, aztec-1 | 51 | 50 | 48 | 49 |
| All | 433 | 397 | 377 | 381 |

These match upstream's own expectations for the upright images (its
blackbox test wants 27 of the EAN-13 sets fast and 40 with `tryHarder`; most
of ean13-2 is blurred photographs). The misses are the hard cases those sets
are kept for. A quick frame read took 0.9 ms on average on an M-series Mac;
a phone will be several times slower, still well inside a frame.

## ABIs

arm64-v8a and armeabi-v7a, API 24, 16 KB page aligned: 1,097,400 and
772,668 bytes. Only the two `Java_..._ZxingNative_*` functions
are exported, and the library needs nothing but libc, libm and libdl.
