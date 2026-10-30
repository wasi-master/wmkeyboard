# docscan-jni: the built-in document scanner

The scanner tool opens Google's document scanner where Google Play services
can run it. Everywhere else (the F-Droid build, phones without Play services,
a Google scanner that fails to start, or anyone who switches on **Use the
built-in scanner**) it opens the keyboard's own: `PageScanActivity` in
`:feature:ime`, on this library. It finds the page in a camera frame or a
photo, flattens the four corners the user settles on into a straight page,
and can clean the page up. The pages go to the keyboard through
`DocScanPages`, exactly as Google's scanner's do.

Kotlin entry point: `com.wasimaster.wmkeyboard.core.docscan.DocScanNative`,
wrapped by `DocScanner` and `PageCorners` in the same package
(`:core:intelligence`).

The built libraries are committed under `core/intelligence/src/docscan/jniLibs/`,
so the app builds without an NDK. GitHub builds leave them out of the APK and
pin them (gate `docscan`), Play builds ship them in the on-demand
`:feature:docscan` split, and F-Droid deletes the committed copies and builds
them with this script through `native/build-fdroid-libs.sh`, as does the
release workflow for the reference APK F-Droid compares against. Rebuild after
you change anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/docscan-jni/build.sh
```

It needs CMake and Ninja on `PATH`, and fetches OpenCV at the pinned commit
unless `OPENCV_SRC` points at a checkout of it. The committed libraries were
built with NDK r29 (`29.0.14206865`), the one F-Droid's recipe names. Each ABI
builds those four OpenCV modules first and deletes its
build tree before the next ABI starts, so the script never holds more than one
tree of a few hundred MB.

## Provenance

OpenCV `5.0.0`, commit `40738fb16ceddb5fb3fea747585f7ce6abb0605b`,
unmodified, under the Apache License 2.0
(`app/src/main/assets/licenses/apache-2.0.txt`). Only the `core`, `imgproc`,
`flann` and `geometry` modules are built (OpenCV 5 moved the contour and
perspective calls the scanner makes into `geometry`, which needs `flann`), static, `-Os`, with the options in `opencv.cmake`: no IPP,
KleidiCV, FastCV, ITT, OpenCL, Eigen, LAPACK or TBB, and no Carotene, so
nothing is downloaded at configure time and nothing prebuilt goes in. The one
third-party file compiled in is Android's `cpufeatures` (Apache-2.0, in
OpenCV's `3rdparty/`). `zlib` comes from the platform (`libz.so`), as does
`libjnigraphics.so` for the bitmap access.

Three things keep the build reproducible. Every path of the build machine is
mapped out with `-ffile-prefix-map`. OpenCV's build-information string, which
would otherwise carry the machine's compiler paths and which OpenCV's startup
keeps alive, is replaced with a fixed line after configuring. And the library
is stripped at the link (`--strip-all`), so its build id hashes only the bytes
that ship: linked unstripped, two builds from different source paths came out
identical except for those 20 bytes.

## Contract with Kotlin

Bitmaps cross as `android.graphics.Bitmap`, `ARGB_8888` only, locked for one
call and read in place. Nothing is kept between calls, so any thread may call.

- `nativeDetect(bitmap) -> FloatArray?`: nine floats, the page's corners
  clockwise from top left as x, y pairs in the bitmap's pixels, then a
  confidence 0..1; null when it found no page.
- `nativeWarp(src, corners: FloatArray(8), dst) -> Boolean`: the page inside
  `corners` drawn flat over all of `dst`, whose size the caller picks
  (`PageCorners.pageSize`, the longer of each pair of opposite sides).
- `nativeEnhance(bitmap, mode) -> Boolean`, in place: 1 colour, 2 grey,
  3 black and white (`PageFilter`).

## How it works

`docscan.cpp` holds all of it, shared with the desktop check.

**Detection** runs at 640 px on the long side, with text closed away
(morphological close) so the page reads as one surface. Four maps each give
contours: Canny at thresholds from the picture's median, a gentle Canny for
a pale page on a pale desk, Canny over each colour channel for a page on a
background of its own brightness, and an Otsu threshold of the bright region
for a page on wood grain that swamps every edge map. Each contour's convex
hull is simplified to four points where it can be. A quad is kept when its
corners are between about 53 and 127 degrees and the edges run along at
least 55% of its outline. A side lying on the frame's border counts as
covered (a page running off the picture has no edge there), but only when two
other sides follow real edges, or the frame itself would win. The biggest
well-supported quad wins.

**Flattening** is one `getPerspectiveTransform` and `warpPerspective`, the
border replicated.

**Clean-up** measures the light the paper is lit by on a grid (8 px cells at
512 px), per red, green, blue and grey, from the brightest unsaturated pixels
of each cell, so text (dark) and coloured boxes or stamps (saturated) are not
mistaken for paper. Cells without enough paper are filled from their
neighbours, then the grid is smoothed and scaled up, and each channel is
divided by it: paper comes out white, shadows lift, ink and colour stay.
Black and white then thresholds locally (strokes) and against a level (so
the paper's grain does not speckle), and keeps anything very dark black, so
a filled box is not hollowed out. A dilation-based paper estimate, the usual
approach, was tried first and washed a large blue box out to white.

## Theme copier

`themecopy.cpp` reads a keyboard theme off a screenshot, in the same library for
the OpenCV in it (`nativeCopyTheme(bitmap, widthDp)`, wrapped by `ThemeCopier`).
It is measurement, not a trained model: a colour read from the pixels is exact and
a network's would be an estimate. No length is in pixels, so a phone, a tablet, a
landscape shot and a crop take the same path.

**Grid.** Two readers. One takes everything smaller than a key from a top-hat and
a black-hat per colour channel, at four thresholds; the pitch comes from a vote
over every pair of blobs side by side, then rows from whichever blob has the most
neighbours on its comb. The other takes closed outlines the size of a key. Rows
have to be nearly full and not crowded, which rejects lines of text. If the theme
redrawn over the grid matches poorly, the other reader gets a turn and the better
one is kept. Keys' edges come from colour profiles out from each label, the row
under the letters from where its keys part from the board.

**Surfaces.** Every key is scaled to one size and the per-pixel median taken, which
removes the labels since no two share a glyph. Face, border, shadow, sheen and
texture are read from that tile, and the outline by fitting the shapes of
`ThemeRendering.kt` against its mask. The board is fitted as one colour, then
linear, radial and sweep gradients with two to four stops, then a picture filled
under the keys by push-pull. Where the board changes under the keys and their
faces change with it, the slope of one against the other is the keys' alpha, and
a picture is read back out of translucent keys.

**Output.** JSON a `ThemeCodec` decodes, with sizes and a report under `_copy`, plus
any pictures as RGBA. `confidence` is 1 less the 85th percentile of the Lab error
between the picture and the theme redrawn over its grid, over 25.

`tools/themecopy/` is the test bench: `fuzz.py` draws keyboards with their answers
(every outline, gradient, photo, border, shadow, alpha, screen and JPEG level),
`run.py` runs the desktop tool (`desktop/themecopy_cli.cpp`) and writes importable
`.wmtheme.json` files, `score.py` grades them per field.

## Desktop check

`desktop/check.sh <photos> <out>` builds the same `docscan.cpp` against a host
OpenCV built with `opencv.cmake`, runs it on every JPEG or PNG in `<photos>`,
and writes each one's outline, flattened page and three clean-ups as JPEGs.
`desktop/make_samples.py <dir>` writes synthetic phone photos with their true
corners in `truth.json`, which the check then scores. Measured on a Mac
(M-series, loaded) with the seven page samples and one without a page, at
1600 × 2133:

| Sample | Corner error, worst of four | Detect |
|---|---|---|
| grey desk, straight | 4 px | 13–26 ms |
| grey desk, tilted | 4 px | 17–42 ms |
| wood grain, steep perspective | 7 px | 25–37 ms |
| white desk, low contrast | 5 px | 24–50 ms |
| checked cloth as bright as the paper | 7 px | 16–41 ms |
| page running off the frame | 4 px | 23–35 ms |
| dim, hard shadow across the page | 4 px | 14–39 ms |
| wood grain, no page | none found (right) | 13–31 ms |

Flattening takes 2–14 ms and each clean-up 15–120 ms there. The one visible
flaw is a thin line where the synthetic shadow's razor edge crossed the page:
the light grid is 18 px coarse at that size, and real shadows are softer.

## ABIs

arm64-v8a, armeabi-v7a and x86_64, API 24, 16 KB page aligned. Only the four
`Java_..._DocScanNative_*` symbols are exported. Sizes are in the table below.

| ABI | libwmdocscan.so |
|---|---|
| arm64-v8a | 3,484,904 bytes |
| armeabi-v7a | 2,561,140 bytes |
| x86_64 | 3,864,064 bytes |
