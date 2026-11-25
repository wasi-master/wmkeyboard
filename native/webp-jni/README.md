# webp-jni: animated WebP with libwebp's own encoder

[libwebp](https://chromium.googlesource.com/webm/libwebp) is Google's WebP
codec. This library carries its encoder and `WebPAnimEncoder`, the animation
encoder that stores each frame as the rectangle that changed since the frame
before, blended over it or not, whichever is smaller. Android has no
animated WebP encoder, and the container-only fallback
(`AnimatedWebpWriter`, which wraps whole still frames) makes a two-second
sticker several times larger than WhatsApp's 500 KB limit for animated
stickers. Kotlin entry point: `com.wasimaster.wmkeyboard.core.media.WebpAnimNative`,
wrapped by `AnimatedWebpEncoder` in the same package. `StickerImage` encodes
animated PNG and Telegram stickers with it and keeps `AnimatedWebpWriter` as
the fallback when the library did not load.

The built libraries are committed under `core/content/src/main/jniLibs/`
and listed in the `base` list of `native/gates.json`: every build carries
them in its base APK, with nothing to download. F-Droid deletes the committed
copies and builds them with this script through
`native/build-fdroid-libs.sh`. Rebuild after you change anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/webp-jni/build.sh
```

It needs CMake and Ninja on `PATH`, and fetches libwebp at the pinned tag
unless `WEBP_SRC` points at a checkout of it. The committed libraries were
built with NDK r29 (`29.0.14206865`), the one F-Droid's recipe names. A
rebuild from a fresh clone takes about 15 seconds. The script maps the build
machine's paths out of the library and links with `--strip-all`, so two builds
with the same NDK come out byte for byte the same: a build from a fresh clone
in another directory matched the committed two.

## Provenance

libwebp tag `v1.6.0`, commit `4fa21912338357f89e4fd51cf2368325b59e9bd9`,
unmodified. `CMakeLists.txt` adds upstream's own build with every tool off
(no cwebp, dwebp, gif2webp, img2webp, vwebp, webpmux, extras), so none of the
image readers those need is looked for, and links libsharpyuv, libwebp and
libwebpmux statically into one shared library with `wm_webp_jni.c`. libwebp
brings its decoder along, which `WebPAnimEncoder` calls to lay each finished
frame onto its canvas; the linker keeps only what the JNI reaches. Written in
C, so no C++ runtime is linked. libwebp is BSD-3-Clause with Google's patent
grant (`app/src/main/assets/licenses/bsd-3-clause-libwebp.txt`), credited on
the About screen.

## Contract with Kotlin

- `nativeNew(width, height, loopCount, minimizeSize, allowMixed, kmin, kmax) -> Long`:
  an encoder for the canvas, 0 when it could not be made. A negative `kmin`
  or `kmax` keeps libwebp's own key-frame spacing; `allowMixed` lets each
  frame be lossy or lossless, whichever is smaller.
- `nativeAddBitmap(handle, bitmap, timestampMs, quality, lossless, method)`:
  an `ARGB_8888` bitmap of the canvas's size, locked with
  `AndroidBitmap_lockPixels` and unpremultiplied on the way in (a bitmap is
  premultiplied unless its flags say otherwise). Shown from `timestampMs`.
- `nativeAddArgb(handle, argb, timestampMs, quality, lossless, method)`: the
  same frame as unpremultiplied `0xAARRGGBB` ints, what `Bitmap.getPixels`
  fills, which is exactly what a `use_argb` WebP picture holds. Also how a
  desktop test feeds frames.
- `nativeAssemble(handle, endTimestampMs) -> ByteArray?`: closes the last
  frame at `endTimestampMs` and returns the file. Once per handle.
- `nativeDelete(handle)`.

Timestamps must not go down. `quality` is 0 to 100 (the effort of a lossless
encode), `method` 0 (fast) to 6 (small); `AnimatedWebpEncoder` uses 4,
libwebp's own default. Alpha is compressed on a second thread
(`thread_level` 1). A handle is not safe for two threads at once;
`AnimatedWebpEncoder` serialises its calls.

## Checking it on a desktop

`build.sh --desktop DIR` builds the same sources for the machine it runs on
(a `.dylib` on macOS) with libwebp's `webpinfo` beside it. `WebpAnimNative`
and `AnimatedWebpEncoder` compile with kotlinc against `android.jar` as a
compile-only classpath; the Bitmap overload is never called, so a plain JVM
runs them. The test encodes ten 512×512 frames 100 ms apart (a red square
moving across a transparent canvas, over a half-transparent band), lossy at
quality 75 and lossless, then checks the files:

| | Lossy | Lossless |
|---|---:|---:|
| Size | 1,578 bytes | 654 bytes |
| Encode, M-series Mac | 30 ms | 14 ms |
| `webpinfo` | 10 frames, 512×512, loop 0, no errors | same |
| Decoded by Pillow (its own libwebp) | 10 frames of 100 ms | 10 frames, every pixel equal to the input |

A wrong-sized frame is refused without adding anything, and a closed
encoder's `finish` returns null.

What it buys, on a sticker-like animation: a 512×512 photograph cut to a
circle with a Bengali caption that types itself in and bobs, 30 frames 66 ms
apart (two seconds), at quality 75:

| Encoded as | Size |
|---|---:|
| 30 whole still frames (what `AnimatedWebpWriter` wraps) | 425,308 bytes |
| `AnimatedWebpEncoder`, `minimizeSize` on (the default) | 56,706 bytes, 104 ms |
| `AnimatedWebpEncoder`, `minimizeSize` off | 58,866 bytes, 54 ms |

The still frames alone come close to WhatsApp's 500 KB limit for animated
stickers; the animation encoder's file is an eighth of that.

## ABIs

arm64-v8a and armeabi-v7a, API 24, 16 KB page aligned: 305,072 and 237,752
bytes. Only the five `Java_..._WebpAnimNative_*` functions are exported, and
the library needs nothing but libc, libm, libdl and libjnigraphics.
