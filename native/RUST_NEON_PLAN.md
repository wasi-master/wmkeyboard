# Moving hot code to Rust, with NEON where it pays

This is the working plan for moving parts of WM Keyboard's Kotlin into
`native/wm-engine` and adding ARM NEON kernels. It records why each piece moves,
the order, and the rules every step follows. Sessions doing the work update the
status lines below as they land.

## Why, and why not everything

The July prediction perf pass found that typing latency is mostly rendering
(Skia, RenderThread, recomposition), and that the compute-heavy parts were
either already native (Harper, the AI and voice runtimes, ML Kit) or fixable in
Kotlin. So code moves to Rust for two reasons only:

1. **One engine for Android and WMK Desktop.** `native/wm-engine` already holds
   every transliteration engine and the CJK lattice for both. Prediction is the
   largest piece the desktop still lacks.
2. **Data-parallel loops**, where NEON gives several times the throughput and
   ART's JIT does not vectorise: dot products over embeddings, per-pixel image
   work, palette search, per-sample audio work.

Branchy orchestration code gains little and stays in Kotlin.

## Phase 0: measure

A JVM microbenchmark per candidate below, plus on-device timing on the
CPH2481 with the odex loaded (memory perf-pass-2026-10-16 has the Perfetto
recipe and the trap). Output: milliseconds per call, calls per keystroke or per
action, share of frame time. A candidate under about 1 ms per action, or 0.3 ms
per keystroke, stays in Kotlin unless Phase 2 moves it for desktop sharing.

Status: host JVM measured 2026-10-21; phone not measured yet (the CPH2481's
wireless-debugging pairing had lapsed, and the device pass was skipped).
Round 2 (below) ran the phone for its own candidates, VectorIndex and the
glide decoders included.

**Harness:** `native/bench/phase0/` (`Phase0Bench.kt`, `run.sh`), outside
Gradle and never shipped. `run.sh host` compiles it with the Kotlin compiler
from the Gradle cache against a checkout's built `core/*` release jars and runs
it on the JVM; `run.sh device` dexes the same classes and runs them under ART
on the phone with `app_process`, so the phone column will time identical code.
Each case warms for at least a second (meant to give ART's JIT time on the phone), then times up to 400
calls. Real classes are called wherever they take plain arrays. Where the loop
sits behind `android.graphics` or a private function (NsfwFilter's CHW loop,
ImageGenEngine.toBitmap, PhotoEditRenderer.sharpenInPlace, WhisperRecorder's
chunk loop, GlideBeam.buildCosts), its body is copied verbatim onto arrays.
Inputs: random unit vectors; 512² sticker frames shaded to fill the palette; a
12 MP photo with a 25-line detector map and PP-OCRv5's 40×18,385 recogniser
output; 200 synthetic QWERTY swipes (mean 139 points, 4 ms apart) on the
bundled 17,306-word `en.txt`, where GlideBeam's top-1 is 92%. No real-swipe set
is checked in, so the FUTO test split was not used. The method splits are JFR
execution samples taken during the same runs.

**Host numbers** (M-series Mac, JDK 21, the quieter of two runs; the first run
shared the machine with another session's sweeps and read about 1.6x slower):

| Candidate | Median / p95 per call | How often | NEON-shaped share |
|---|---|---|---|
| `VectorIndex.score`, 1k × 768 | 0.74 / 0.79 ms | per meaning search, after a 300 ms pause; per AI memory recall | dots 88%; the rest is a `Hit` per item and a full sort |
| `VectorIndex.score`, 5k × 768 | 3.8 / 3.9 ms | same | dots 86% |
| `VectorIndex.score`, 20k × 768 (photos) | 17.0 / 18.0 ms | same | dots 80% |
| `GifEncoder.addFrame`, 512² | 4.4 / 4.6 ms | per frame, 30-60 per animated sticker into a GIF-only field | LZW 43% (serial hash), median cut 38%, index lookup 6%, bounds 5% |
| `GifEncoder`, whole 30-frame GIF | 135 / 141 ms | per such sticker sent | as above |
| `PaddleOcrPipeline.read` minus inference, 12 MP, 25 lines | 42 / 44 ms | per OCR read | `detectorInput`'s box average 39%, `ctcDecode` argmax 36%, `strip` bilinear 17% |
| `detectorInput`, 12 MP to 640² | 14.4 / 14.9 ms | per OCR read | all of it |
| `ctcDecode`, 40 × 18,385 | 0.22 / 0.24 ms | per 320 px piece, 25-75 per page | all of it |
| Cutout `normalise` + `alphaOf`, 320² | 0.25 + 0.16 ms | per cutout, beside a LiteRT segmentation | all of it |
| NsfwFilter CHW loop, 224² | 0.05 ms | per generated picture | all of it |
| ImageGen sampler step, 4×64×64 | 0.012 ms | per UNet step | all of it |
| ImageGen `toBitmap`, 512² | 0.61 ms | per picture and preview, beside seconds of UNet | all of it |
| PhotoEdit sharpen, 2048 × 1536 | 67 / 68 ms | per adjust change in the editor preview while sharpen > 0 (`base()` reruns) | all of it |
| PhotoEdit sharpen, 4000 × 3000 | 257 / 261 ms | per photo sent with sharpen > 0 | all of it |
| `GlideBeam.decode` | 2.2 / 6.7 ms | per swipe, at finger-up | `advance` (banded min-plus DP, per trie node) ~50%, `buildRemain` ~16%, `anchorLead` ~12% |
| `GlideBeam`, 6 live previews | 7.6 / 19.7 ms | per swipe, while the finger is down | same |
| `buildCosts` loop, 48 samples × 26 keys | 0.001 ms | per decode | the "distance loop": nothing to win |
| `CtcGlideBeam.decode` | 2.3 / 3.7 ms | per swipe, FUTO engine only | hash maps and trie walk |
| `DuetBeam.decode` | 1.1 / 5.8 ms | per two-thumb word | `advance` 89% |
| PCM16 to float + RMS, 1,600 samples | 0.001 ms | per 100 ms audio chunk | all of it |
| `WhisperMel.compute`, 5 s | 5.5 ms | per utterance, LiteRT Whisper only, beside seconds of inference | FFT |
| `WavEncoder.encode`, 5 s | 0.06 ms | per utterance, server engines only | all of it |

None of these runs per keystroke. Every "port" verdict in Phase 1 clears the
1 ms bar on the host already, and every "skip" is under 0.1 ms or under 1% of
the action it belongs to, so a phone several times slower than the Mac does
not flip a verdict. The phone column is still owed before a kernel's commit
claims a speed-up: `native/bench/phase0/run.sh device` once adb is paired.

Fixes these runs found in Kotlin, unmeasured and out of scope here:
`GlideBeam.anchorLead` takes a square root on every `advance` call when its
value depends only on the key, so it could be one table per anchor filled in
`buildCosts`. `VectorIndex.score` sorts every hit when its callers keep a few.
`GifEncoder.Palette.of` builds its used-bucket list through a boxed
`(0 until BUCKETS).filter` on every frame (fixed before the Rust port, see
"GIF encoder" under Phase 1).

## Shared infrastructure

Built first in Phase 2 (step 0), used by every Phase 1 kernel and every
Phase 2 port. Status: done.

**Kernels** live in `wm-engine/src/kernels/`, one file per kernel family
(`dot.rs` is the first: `dot_f32`, `dot_rows_f32`, `dot_i8`). Each kernel has:

- `scalar::name`, the reference: plain Rust, the definition of the result.
  x86_64 and armv7 run it.
- `neon::name` (and `dotprod::name`, `fp16::name` for the optional armv8.2
  extensions): `#[target_feature(enable = ...)]` over stable
  `core::arch::aarch64` intrinsics. `dotprod` and `fp16` are stable in the
  pinned 1.99.1.
- `name`, the dispatcher: asks `kernels::active()` and falls back to the
  reference.
- Tests comparing every SIMD version with the reference on seeded inputs
  (`kernels::testing::Rng`) over `testing::LENGTHS`, which covers every
  tail length, within a tolerance the file states. Integer kernels are exact.

`kernels::detected()` probes once with `is_aarch64_feature_detected!`.
`kernels::set_force_scalar` pins every kernel to its reference.

**Parity helper**: `wm-engine/src/java_heap.rs` is a binary heap that pops
in exactly `java.util.PriorityQueue`'s order, ties included (a
transcription of OpenJDK's sift steps, checked against orders recorded on a
JVM). The Kotlin walks order their frontiers by score alone, so equal scores
come out in heap order; a Rust port that must match them line for line uses
this instead of `std::collections::BinaryHeap`.

**JNI** (`wm-engine-jni`), one module per Kotlin class:

- `guard.rs`: `guarded` (every entry point's body runs in it: a panic is a
  `RuntimeException` in Kotlin), `read_units`/`give_units` (strings as
  UTF-16, lone surrogates intact, no modified-UTF-8 round trip),
  `with_critical` (a primitive array pinned for a closure that makes no JNI
  call), `give_ints`, `give_all_units`, `throw_state`.
- `handles.rs`: `Registry<T>`, Rust objects behind `long` handles that are
  never reused. Each call clones the `Arc`, so closing a handle while
  another thread is mid-call cannot free what it reads; a closed handle
  throws `IllegalStateException`.
- `kernels.rs`: `NativeEngine.nativeCpuFeatures`, `setForceScalar`,
  `nativeDotRows` (one query against a row-major matrix in one crossing:
  the shape `VectorIndex.score` needs).

**Kotlin**: `NativeEngine` (`core/language`, `core.engine`) loads the
library once for every bridge (`EngineNative` now goes through it) and
holds the switch. While a piece had both implementations it checked
`NativeEngine.useNative`, and `forceKotlin` turned every Rust path off and
pinned the kernels to scalar; a "Native prediction engine" setting drove it.
Once every Kotlin copy had moved to the test sources (step 4) the setting
reached nothing and was removed before it shipped. `forceKotlin` still pins
the kernels, and defaults from `WM_NATIVE=kotlin` (or
`-Dwmkeyboard.native=kotlin` for a JVM started by hand); the parity tests
now run the Kotlin oracles directly.

**Host builds**: every test task that can reach the library depends on
`:core:language:buildHostEngine` (`build-host.sh`), now including
`:core:prediction`'s. CI's unit-test job runs those tasks with the runner's
cargo and caches the build (`Swatinem/rust-cache`), so `unitTests` and the
glide and prediction evals load the real Rust code.

**Adding a kernel**: a file under `kernels/` in the shape above, an entry
point in `wm-engine-jni/src/kernels.rs` that pins its arrays with
`with_critical`, a `NativeEngine` method that checks `useNative` (or
`requireLoaded` when it has no Kotlin fallback), then rebuild the `.so`s
with `build.sh` and report the size change.

## Phase 1: NEON kernels

Verdicts from Phase 0, host Kotlin cost (see the table there). In priority order.

| Kernel | Kotlin cost (host) | How often | Verdict | Expected NEON gain |
|---|---|---|---|---|
| Photo editor sharpen (`PhotoEditRenderer.sharpenInPlace`) | 67 ms at the 2048 px preview, 257 ms at 12 MP | per adjust change in the editor while sharpen is on; per photo sent | **done** (`kernels/sharpen.rs`) | measured 18x at 2048 px and 11x at 12 MP in the renderer's bands (host); see the status line below |
| Embedding dot products (`VectorIndex.score`) | 0.74 / 3.8 / 17 ms at 1k / 5k / 20k vectors, 80-88% in the dots | per meaning search, per AI memory recall, per sticker in a tagging pass | **done** (f32; int8 measured and not adopted, see below) | measured 7-8x per search: 0.094 / 0.48 / 2.0 ms on the host |
| OCR pre/post-processing (`PaddleOcrPipeline`) | 42 ms per page outside inference | per OCR read | **port** `detectorInput`'s box average and `ctcDecode`'s argmax (75% of it); `strip` stays. **Done** (host), see below | 4-8x on those two: u8 pairwise sums and a 4-lane max with index. At 5x, roughly 42 to 17 ms per page. Measured on the host: box average 15.0 to 2.8 ms (5.3x), argmax 0.23 to 0.060 ms per piece (3.8x), the whole `read` 43-45 to 21-22 ms |
| GIF encoding (`GifEncoder`) | 4.4 ms a frame, 135 ms per 30-frame GIF | per animated sticker sent into a GIF-only field | **port, last**, as mostly scalar Rust. **Done** (host), see below | small from NEON: 43% is LZW's serial hash chain and 38% median cut. There is no nearest-colour search or dithering to vectorise (the palette is a lookup table). Rust's gain is bounds checks and allocation, maybe 2x. Try the Kotlin boxing fix first. Measured on the host: the Kotlin fix 4.36 to 3.65 ms a frame, then Rust 1.59 ms (2.7x over Phase 0), 130.5 to 47.7 ms a GIF |
| Glide shape scoring (`GlideBeam`, `DuetBeam`, `CtcGlideBeam`) | 2.2 / 6.7 ms per decode, 7.6 / 19.7 ms per stroke of previews; the distance loop is 1 µs | per swipe | **skip as a Phase 1 kernel**; Phase 2 step 4 ports the decoders | the time is in `advance`, a banded min-plus DP called per trie node. A JNI call per node would cost more than it saves, so vectorise its band (4-lane min/select, perhaps 2x on a decode) inside the Rust decoder |
| Cutout, NSFW filter, sticker generator glue | 0.42, 0.05 and 0.012-0.61 ms | per cutout, picture or UNet step, each beside a network that takes hundreds of ms to seconds | **skip** | under 1% of the action |
| Audio glue (PCM16 to float, energy, `WhisperMel`, `WavEncoder`) | 1 µs per 100 ms chunk; 5.5 ms mel and 0.06 ms WAV per utterance | per chunk; per utterance | **skip** | under 1% of capture or inference. No Kotlin resampler exists: capture is at 16 kHz already |

None is "port only for desktop": the glide decoders are the shared piece, and
Phase 2 already moves them.

Status: verdicts in from host numbers; the phone column is owed (see Phase 0).
Kernel sessions may start in the order above. Embedding dot products: done
(f32), host numbers only. OCR kernels: done, host only. GIF encoder: done,
host only, the last item: **Phase 1 is done** on host numbers; the phone
column is still owed for every kernel.

**Sharpen: done 2026-10-21, host only.** `kernels::sharpen::sharpen_argb`,
`NativeEngine.sharpen` (false without the library or with `useNative` off,
and `PhotoEditRenderer.sharpenRows` runs instead), one call per 256-row band
of `sharpenInPlace`. The Kotlin channel maths is `f32`, not integer, so the
kernel is too: `ld4` splits sixteen pixels into channel registers, the
neighbour sum is exact in `u16`, and the multiply, add and round run on four
`f32` lanes in Kotlin's order with no fused multiply-add. `fcvtas` rounds
half away from zero, which is `Math.round` for everything not clamped to 0.
The output is bit for bit Kotlin's: Rust tests cover every tail width,
1 to 5 rows and every centre value against every neighbour sum at each
slider step, and the harness compares the JVM loop with the native call on
both photo sizes at all 20 steps. Same-run host numbers (the machine was
shared, load 10 to 12 on 8 cores, so the Kotlin side read 1.2 to 1.4x slower
than Phase 0's):

| Case | Kotlin | Native, one call | Native, 256-row bands |
|---|---|---|---|
| 2048 × 1536 | 96 ms | 4.4 ms (22x) | 5.3 ms (18x) |
| 4000 × 3000 | 307 ms | 19.5 ms (16x) | 27.7 ms (11x) |

The bands column copies each band into a fresh array, as `getPixels` does,
and is the shape the renderer runs; the bands stay so a 12 MP picture is never
held as one 48 MB `IntArray`. Phone column owed (`run.sh device` now pushes
the arm64 `.so` and names it with `WM_ENGINE_LIB`). Library growth over
main's build with the glide decoders: arm64 +4,400 bytes, armv7 +2,160,
x86_64 +3,304. armv7 and x86_64 run the Rust
scalar reference, unmeasured.

A RuntimeShader on API 33+ was not built. It does not fit `base()`, which
draws on a software canvas so the vignette, grain and later layers can share
it; on that canvas Skia would interpret the shader per pixel on the CPU, and
the GPU route means uploading the picture, rendering through a
`HardwareRenderer` and reading 48 MB back at 12 MP before the next software
stage. It also cannot match Kotlin: AGSL sees premultiplied colours as
normalised floats (half precision allowed) and Skia rounds them back its own
way, so API 33+ and older devices would sharpen differently. It cannot be
timed on the host either (no Android graphics there), and the phone is not
paired. So NEON runs on every API level. What is left on a phone is
`getPixels`/`setPixels`, which unpremultiply and premultiply every pixel, a
cost the host harness cannot see and the phone run should time; locking the bitmap's own pixels
(`AndroidBitmap_lockPixels`) would remove both copies but changes results
for translucent pixels, so measure it on the phone first.

### Embedding dot products (done, f32)

`VectorIndex` keeps its vectors in one row-major `FloatArray` and scores
them with `NativeEngine.dotRows` in one crossing; the Kotlin loop stays as
the fallback when `useNative` is off. Callers now ask
`search(query, tuning, accept)`, which returns exactly what
`MeaningRank.select(score(...))` did, hits and order: the median comes from
a quickselect and only the hits over the bar are ranked, at most the
tuning's limit, so no `Hit` per item and no full sort. `VectorIndexParityTest`
holds the kernel to the Kotlin loop (same hits and order, scores within
`dot.rs`'s 1e-5; neighbours closer than that may trade places) and `search`
to `select`. The `.so` did not change: `dot_rows_f32` and `nativeDotRows`
came with the shared infrastructure.

Host numbers from the Phase 0 harness (`only=vector`, same machine and
load for both columns). "Before" is `score` alone; the old callers then
also ran `select`, whose median sorted a boxed copy of every score.

| Vectors × 768 | Before (Kotlin `score`) | After, Kotlin fallback (`search`) | After, NEON (`search`) | Heap before / after (read from file) |
|---|---|---|---|---|
| 1k | 0.69 ms | 0.63 ms | 0.094 ms | 3 / 4 MB (GC noise at this size) |
| 5k | 3.70 ms | 3.18 ms | 0.48 ms | 16 / 17 MB |
| 20k | 16.9 ms | 12.8 ms | 2.03 ms | 64.5 / 65.5 MB |

An index built a batch at a time grows by an eighth, so it holds at most
an eighth more than it needs (71.8 MB at 20k); `read` sizes it exactly. The
file format did not change.

**int8, measured and not adopted.** The test was the brief's: adopt only if
the top hits stay essentially identical. Inputs were real EmbeddingGemma 2
outputs: 4,200 documents (docs-site paragraphs, and the app's strings in
English, Bengali and French as short clip-like texts) and 380 queries
(headings and 2-4 word fragments), embedded with the models release's text
encoder through Python LiteRT, plus the 1,716 shipped emoji vectors. Each
variant ran through a float32 copy of `MeaningRank.select` with the
shipped tunings and was compared with f32:

| Storage | Top hit changed (text / emoji / sticker tags) | Kept-set Jaccard | Largest score error |
|---|---|---|---|
| int8 per vector, int8 query (`sdot`) | 2.1% / 2.6% / 4.3% | 0.93-0.97 | 0.0050 |
| int8 after centring and a block-Hadamard rotation | 0.5% / 0.3% / 1.1% | 0.984-0.995 | 0.0014 |
| fp16 | 0% / 0% / 0.1% | 0.997-0.998 | 0.00016 |

The model's vectors share one direction (the mean vector's norm is 0.82)
and one dimension carries a large share (largest magnitude 0.31, against
an RMS of 0.036), so a per-vector int8 step is about 9x coarser than a
typical value. The tunings separate hits that sit 0.01-0.02 apart (the
median gap between the first and second hit is 0.015), and an error of a
few thousandths reorders them. The int8 build was fast, 0.029 / 0.17 /
0.77 ms per search with 22 MB at 20k, but it changed what people would see.
fp16 rows (half the memory, widened to f32 with `vcvt` and summed with
`vfmaq_f32`) keep the ranking and would be the next step if the 20k scan
matters on a phone.

### OCR kernels (done)

`kernels/box_average.rs` (`box_average_bgr`) and `kernels/argmax.rs`
(`argmax_rows`), JNI `NativeEngine.nativeBoxAverageBgr` (one crossing per
photo) and `nativeArgmaxRows` (one per block of logits, 40 × 18,385 per
piece). `PaddleOcrPipeline.detectorInput` and `ctcDecode` call
`NativeEngine.boxAverageBgr` / `argmaxRows`, which answer false when
`useNative` is off (the library is absent, or `forceKotlin` is set), and
the Kotlin loops run then. Unlike prediction's, these Kotlin copies stay.
A photo the detector grows (scale 1 or more) stays on the Kotlin bilinear.

- Box average: not pairwise adds after all. A box is 6 or 7 photo pixels
  wide at 12 MP and its edges move with `floor`/`ceil`, so the kernel sums
  each box's rows down every column first (`vld4q_u8` splits sixteen ARGB
  pixels into planes, `uaddw` widens into `u16`, six registers per sixteen
  columns), stores the sums `[b, g, r, 0]` per column, then adds each
  output pixel's columns as one `u32x4` and normalises the three channels
  in one vector. Integer sums and the same IEEE divides and subtracts in the
  same order make it bit-identical to the Kotlin, not just close. A box
  taller than 257 rows (a photo over about 165,000 pixels on its long side)
  would overflow the `u16` sums and takes the scalar reference.
- Argmax: four accumulators of four lanes, each a value and an index moved
  only on a strict `fcmgt`, reduced by largest value, then smallest index
  among the lanes equal to it, then the tail by the same strict rule. So a
  tie gives the first index, `-0.0` ties `0.0`, a NaN never wins and a row
  starting with a NaN answers 0, all as the Kotlin scan does.
- Parity: Rust tests compare NEON with the reference bit for bit (ten photo
  sizes including every column tail, saturated white, tall boxes; argmax
  over `testing::LENGTHS` and 18,385 with ties, zeros, NaNs and
  infinities). `PaddleOcrNativeParityTest` (`:core:intelligence`) compares
  the Kotlin with the native path on generated pages of printed text (AWT,
  level, tilted and vertical lines, sensor noise; 4000×3000, 3024×4032,
  641×480, 1203×1601, 2000×37, 37×2000, 4096², and the grown sizes): the
  detector input bit for bit, `ctcDecode`'s text and confidence over six
  kinds of logits, and the lines of a whole `read` through a stand-in
  runtime whose detector and recognizer answer from the pixels they are
  given, so any difference upstream would change the text. No PaddleOCR
  graph runs on the JVM, so that stand-in is as close to the real models
  as the test gets; the kernels being exact is what carries the result.
- Speed (`native/bench/phase0/run.sh host only=ocr`, `WM_NATIVE=kotlin` for
  the before, two runs each, load average 6.8-7.6): `detectorInput` 12 MP
  14.8-15.3 to 2.76-2.88 ms; `ctcDecode` 0.224-0.234 to 0.060 ms; `read`
  minus inference 43.1-45.1 to 21.0-21.6 ms. What is left of `read` is
  mostly `strip`'s bilinear and the bench's stand-in copying 2.9 MB of
  logits per piece. Phone not measured (no pairing).
- Size, against a build of the main it merged into (55a8517e0, with the
  sharpen kernel): libwm_engine_jni arm64-v8a 2,120,680 -> 2,129,208 B
  (+8,528), armeabi-v7a 1,546,856 -> 1,550,248 (+3,392), x86_64 2,426,960
  -> 2,433,080 (+6,120). Segment padding makes these jumpy: main's build
  with and without sharpen is the same size, and the same two kernels
  measured +4,048 / +1,296 / +2,816 on the main before it.

### GIF encoder (done, in Rust)

`wm_engine::gif` holds the whole encoder: the header, `FrameEncoder::frame`
(bounds, palette, index mapping, LZW, with its tables kept for the next
frame) and an in-memory `Encoder` for WMK Desktop. The per-pixel loops are
the kernel family `kernels/gif.rs`. JNI `gif.rs` serves `GifNative`
(`core/content`): a frame is one crossing, its pixels pinned and its bytes
returned as one `byte[]`, with the tables in a thread-local. `GifEncoder`
keeps its API and writes what Rust returns; it calls `requireLoaded`, so
there is no Kotlin fallback. The Kotlin encoder moved to
`feature/ime/src/test/.../core/media/reference/KotlinGifEncoder.kt`, as
prediction's did, and `GifParityTest` compares whole `.gif` files from the
two: the animated WebP fixture and a lossy 512 px WebP sticker decoded on
Robolectric's native graphics, 30 smooth sticker frames, flat cartoon
frames, and edge cases (one pixel, one row or column, nothing drawn, alpha
127 against 128, 1 to 300 colours, noise that refills the code table,
delays past both clamps, no frames).

- The Kotlin fix came first and is in the oracle too: `Palette.of` built its
  used-bucket list through a boxed `(0 until BUCKETS).filter`, its median
  cut boxed indices on every split, and each frame allocated about a
  megabyte of histogram, lookup and hash tables. The encoder now keeps them
  and clears what the frame used. Same bytes.
- Exactness is free here: everything is integer. The median cut sorts by
  (channel, bucket) as Kotlin's `LongArray` key did; buckets are distinct,
  so a run over 96 long sorts by setting a bit per key in a 2^15-bit map and
  reading the bits back, and a shorter one with `sort_unstable`. LZW's
  output depends only on what its table holds, so the Kotlin hash became a
  table indexed by `prefix << data_bits | index` (2 MB at 8 bits), cleared
  through the list of slots it set rather than wholesale.
- NEON, where it is lane-parallel: the bounds test sixteen pixels per step
  (alpha 128 and up is the top bit) and, in a row that draws anything,
  read only in from each end; the histogram and the index mapping work out
  four buckets at a time with shifts, masks and the opacity select, and
  leave the table accesses to scalar code, since NEON has no scatter or
  gather. The histogram also folds runs of one bucket into lane sums, one
  table update per run: Kotlin's per-pixel update waits on the last when
  neighbours share a bucket.
- Host, Phase 0 harness, same runs. The Kotlin fix: 4.36 to 3.65 ms a
  512² frame, 130.5 to 109.3 ms a 30-frame GIF. Then Rust, against the
  fixed Kotlin: 3.61 to 1.59 ms a frame (1.98 with the kernels pinned to
  scalar), 108.2 to 47.7 ms a GIF (59.6 scalar). From Phase 0's 4.4 / 135
  ms that is 2.7x. Inside Rust, a frame of the bench's smooth sticker is
  about 0.40 ms histogram, 0.37 median cut, 0.63 LZW, 0.07 index mapping
  and 0.02 bounds (0.41 for the scalar bounds). The histogram's run folding
  does nothing for that sticker, whose bucket changes every two or three
  pixels, but takes a flat cartoon frame's histogram from 0.49 to 0.09 ms.
  Phone column owed.
- Size, against the main it merged into (c8d8068f3, with the OCR
  kernels): libwm_engine_jni arm64-v8a 2,129,208 -> 2,149,240 B (+20,032),
  armeabi-v7a 1,550,248 -> 1,562,240 (+11,992), x86_64 2,433,080 ->
  2,453,056 (+19,976).

## Phase 2: prediction core in Rust

Leaves first; Kotlin keeps the orchestration until the end.

1. `.wmdict` and `.wmng` readers, memory-mapped in Rust (the July "phase 3").
2. `FuzzyBeamSearch`, edit candidates and `KeyProximity`, `NgramReranker`, `FrequencyCodec`.
3. `UserLexicon` and the learning stores.
4. The glide decoders: `GlideBeam`, `DuetBeam`, `CtcGlideBeam`'s decode.
5. `SuggestionEngine` last, or never: its orchestration can stay Kotlin.

Each step keeps the Kotlin implementation until a parity test shows identical
output on the real dictionaries and on the glide eval sets, then deletes it.

### Step 1: the readers (done)

`MappedTrie`'s word-level queries (`frequencyOf`, `contains`, `complete`,
`entries`) and the whole of `MappedNgramPack` run in Rust: `wmdict::Dict`
and `wmng::Pack` over a `memmap2` mapping in `wm-engine-jni/src/dict.rs`,
behind `PredictionNative` handles that `NativeHandles` closes once the
Kotlin object is unreachable. The Kotlin reader code for those queries is
deleted. `MappedTrie` still maps the file in Kotlin too, for the node-level
`TrieWalker` methods the fuzzy walk, glide and the coverage checks read
node by node; those go when the walks move (steps 2 and 4).

- Completion order: `TrieCompleter` pops a `PriorityQueue` ordered by
  frequency alone, so equal frequencies come out in heap order. `Dict::complete`
  now uses `java_heap::JavaHeap` with the same pushes in the same order, so
  ties match too, and WMK Desktop gets the phone's order.
- N-gram strings are looked up as Kotlin's `toByteArray(UTF_8)` encodes them
  (a lone surrogate as `?`), through the new `*_bytes` methods.
- Parity: `NativeReaderParityTest` (`:app`). Against `PackedTrie`/`TrieCompleter`
  and against the n-gram counts the packs were built from, on the bundled
  en and bn lists, a 296k-word synthetic list with non-BMP, lone-surrogate
  and Hangul words, and (with `PARITY_DOWNLOADS`) the data repo's en, ko and
  th lists at the Large cap and the en and bn n-gram packs. Before the
  deletion it also ran the Kotlin readers on the same files. Every query
  agreed: 1.29M completions, every word's frequency, every entry in order,
  every vocabulary id, count and follower list.
- Size: libwm_engine_jni arm64-v8a 1,114,720 -> 1,177,680 B (+62,960),
  armeabi-v7a 784,728 -> 831,264 (+46,536), x86_64 1,245,760 -> 1,311,984
  (+66,224).
- Latency (`MappedTrieLatencyBench`, `PredictionLatencyBench`, two runs each,
  load average 3.5-4.3 with other sessions running): `frequencyOf` p50 0.75
  -> 0.42-0.54 us, `complete(3, 10)` p50 3.6-8.3 -> 5.1-5.3 us (p99 14-35 ->
  10-11 us), `contains` 0.21-0.29 -> 0.38 us; a whole keystroke
  (suggest + autocorrect) p50 0.404-0.457 -> 0.409-0.427 ms, unchanged.
  `pack.bigramCount` is about 1 us against Kotlin's sub-microsecond, because
  the Kotlin reader's head cache is gone; it moves inside Rust with
  `NgramReranker` in step 2.
- Evals: the `core.prediction.eval` family run before (041a6921a) and after
  gives byte-identical `metrics.json` and `context_miss.json` and the same
  report lines: top1 0.8195, top3 0.9300, MRR 0.8741, autocorrect precision
  0.9853, recall 0.7015, false corrections 0.

### Step 2: the correction walk (walk done)

`FuzzyBeamSearch.search` runs in Rust (`wm-engine`'s `fuzzy::search_units`,
JNI `PredictionNative.fuzzySearch`, one crossing per search) whenever every
trie it is given has a Rust twin: a `MappedTrie` is one, a `PackedTrie` gets
a copy of its codec bytes on first walk (`NativeFuzzySearch`), and an empty
trie is skipped. `KeyProximity` and `EditHabits` cross once by handle; the
touch frame and the key sets cross as arrays per search. Touch scoring,
ambiguous keys and their multi-unit spellings, learned habits, accents, the
half-space and supplementary letters are all in the port.

- Exactness: the Rust walk reproduces `BeamWorkspace`'s heap, push order,
  merge rule, k-th-best floor, `Math.max` and `coerceIn` semantics, UTF-16
  words and String ordering. Logarithms go through `strict_math::log`, a
  transcription of fdlibm that matches `StrictMath.log` bit for bit over
  3.4M inputs; the platform `ln` (Apple libm, bionic) differs from it in
  about 1% of results. The Kotlin walk calls `StrictMath.log` too, so the
  two agree on every runtime; on the aarch64 JVM the evals run on,
  `Math.log`, `exp` and `pow` already equal `StrictMath` (8M samples), so
  that switch changed no number there. x86_64 HotSpot's `Math.log` is an
  intrinsic that need not be fdlibm, which is why the switch was made.
- Parity: `NativeFuzzyParityTest` (`:app`), the prediction eval's 2,000 typo
  cases and 2,000 clean words over the bundled English list, through the
  plain walk, touch evidence, learned habits, every edit budget, mapped and
  in-memory twins and a user tier together, T9 frames, a nukta key, accents,
  half-spaces and Osage letters: 17,494 searches, 122,693 candidates, every
  score, edit cost, count, tier and accent bit-identical.
- Evals: the `core.prediction.eval` family (whose engine walks a
  `PackedTrie` twin and skips its empty lexicon, so it runs in Rust) gives
  byte-identical `metrics.json`, `context_miss.json` and report lines.
- Latency (`PredictionLatencyBench`, two runs per side, load average 2.2-2.6):
  keystroke (suggest + autocorrect) p50 0.442/0.449 -> 0.318/0.322 ms, p99
  0.649/0.656 -> 0.466/0.475 ms; octopus keystroke p50 0.508/0.560 ->
  0.348/0.346 ms.
- Size: arm64-v8a 1,177,680 -> 1,285,552 B (+107,872), armeabi-v7a 831,264
  -> 923,592 (+92,328), x86_64 1,311,984 -> 1,439,552 (+127,568): the walk,
  and the accent fold tables it is the first JNI caller of.
- Not deleted yet: the Kotlin walk still serves any search with a non-empty
  `Trie` (the user lexicon, the system personal dictionary), which has no
  Rust twin until step 3. `KeyProximity` stays in Kotlin for its other
  readers (glide, autocorrect's space-slip and digit checks).
  `NgramReranker` needs the lexicon's counts and moves with step 3.
  `FrequencyCodec` was ported earlier (`freq_codec`); its Kotlin file is one
  of the five `:tools:dictc` shares and stays.

### Step 3: the user lexicon and the learning stores (done)

`UserLexicon`, `NgramReranker`, `CorrectionMemory` and `CorrectionStats` are
facades over `wm-engine` (`user_lexicon`, `rerank`, `correction_memory`,
`correction_stats`; JNI `lexicon.rs`, `rerank.rs`, `corrections.rs`). The
Kotlin implementations are deleted from production and kept under
`app/src/test/.../prediction/reference/` as the parity oracles. The lexicon's
trie crosses to the fuzzy walk and the reranker by handle (`LexiconTrie`),
so every production trie now has a Rust twin.

- What stayed in Kotlin: Unicode case folding (`WordKey.of`) and the
  learnability gate (`WordContext.isLearnableWord`), so a load was two
  phases (Rust parsed the file, Kotlin judged and folded each word, Rust
  built). Both are in Rust now, on ICU4X, and a load is one call; see
  "Unicode on ICU4X" below. The reranker's caller still folds the context
  and candidates in Kotlin, and the correction stores take keys already
  folded.
- Ties made deterministic first (dfda1e508): wherever the Kotlin stores let
  `HashMap` iteration order pick a winner (trie children, follower and pair
  eviction, compaction), they now break ties by the word, and the Rust port
  matches that. Java's hash order is not portable, so this is the one
  behaviour change of the step. It moves one glide eval line: in
  `finished-stroke-guess.txt` eager mode, top1 0.943 -> 0.942 and one new
  hijack (where -> when) in four lines of the report, and 0.327 -> 0.325 in
  the fifth. The Kotlin stores at dfda1e508 give that same file byte for
  byte, so the Rust port itself moves nothing. Every other glide eval
  report and the whole `core.prediction.eval` family are byte-identical.
- File formats: the Rust stores read and write the same JSON. The parity
  tests save from both sides, compare the files as JSON values, cross-load
  each side's file, and load old and damaged files into both (both
  skip-store namings, files from before each additive field, junk words
  saved before the gate, the old 200-count marker, damaged correction
  files).
- Parity: `NativeLexiconParityTest` (random runs of every lexicon operation
  with every query, the trie node by node, compared after each step;
  compaction; old files), `NativeCorrectionsParityTest` (runs past every cap,
  expiry and halving window; old and damaged files), `NativeRerankParityTest`
  (strips over a lexicon trained on sentences drawn from the bundled English
  list, with capitals, the skip-gram backoff, recent-word bags and repeats).
  The existing `UserLexiconTest`, `CorrectionMemoryTest`,
  `CorrectionStatsTest` and `NgramRerankerTest` pass unchanged, as does the
  rest of the `core.prediction` package (951 tests).
- Latency (`PredictionLatencyBench`, two runs per side, load average
  2.8-3.3): keystroke p50 0.330/0.321 ms, p99 0.490/0.482 ms, against
  0.318/0.322 and 0.466/0.475 at step 2: unchanged within noise. The bench
  has no learned words, so it measures the crossing, not a busy lexicon.
- Size: arm64-v8a 1,285,552 -> 1,887,872 B (+602,320). Over a third of
  that is `core::slice::sort`'s stable sort instantiated once per
  comparator (121 copies in the step's code, 208 KB before stripping); the
  stores sort their exports, saves and evictions by distinct keys. Routing
  them through a few shared sort-key types would get most of it back
  (done: see "Library size" below).

### Step 2 follow-up: the Kotlin walk deleted

A plain `Trie` now has a twin too: `wm-engine`'s `UserTrie` (the `Trie` port
the lexicon already used) filled with the same entries, rebuilt whenever the
trie's new `version` counter has moved (`sources.rs`, source kind 2). It
could not be a `.wmdict`: the codec rounds frequencies (`FrequencyCodec`),
and an exact-count `Trie` read through one scored differently. Only tests
build a plain `Trie`, so every walk the keyboard makes has a twin, and the
Kotlin walk moved to `app/src/test/.../prediction/reference/` as
`KotlinFuzzyBeamSearch`, the oracle `NativeFuzzyParityTest` now compares
against. `FuzzyBeamSearch.search` keeps its signature; its `BeamWorkspace`
argument carries nothing.

### Step 4: the glide decoders (done)

`GlideBeam` (decode, align, sampleShape), `DuetBeam` and `CtcGlideBeam`'s
beam search run in Rust: `wm-engine`'s `glide` (`mod.rs`, `keymap.rs`,
`loops.rs`, `workspace.rs`, `duet.rs`, `ctc.rs`), JNI `glide.rs`, Kotlin
`NativeGlide`. The Kotlin decoders, their workspaces and `LoopScan` moved to
`app/src/test/.../gesture/reference/` as the oracles; `GlideWorkspace` and
`DuetWorkspace` keep only their size constants, and the decoders still take
them so no caller changed.

- Learned shapes stay in Kotlin (`GlideShapeStore`). A decode that reads
  them is two calls on one thread: the walk returns the words the shape
  channel will score (readings, then guesses) and the drawn stroke
  quantised; Kotlin looks up those words' shapes and, with seeding on, the
  words stored near the stroke; the finish call runs the shape channel, the
  seeding and the guess merge. Key maps cross once, as a handle on
  `GlideKeyMap`; each source's vocabulary cap crosses as the frequency
  Kotlin reads off `frequencyAtRank`.
- Exactness: f32 where Kotlin has Float and f64 where it has Double, in the
  same order of operations; Kotlin's `Long * Float` sample clock; the
  heaps, pools and quickselect swap for swap; `StrictMath` log and pow on
  the Kotlin side (no number moved on the aarch64 JVM).
- Parity (`NativeGlideParityTest`, oracles against production): the gesture
  eval corpus at every noise level plus a dwelling, looping one, against
  the bundled English list as a `Trie` and as a `PackedTrie` with a user
  tier of hyphen, apostrophe and dot words, under the shipped weights,
  wiggles, the vocabulary cap, wide anchors and seeding, with and without
  look-ahead and learned shapes: 11,250 decodes, 104,940 candidates and
  1,500 alignments and sampled shapes bit-identical. `DuetBeam` over the
  two-thumb corpus, clocked and clockless, whole words and prefixes: 9,000
  decodes, 46,123 candidates. `CtcGlideBeam` over emissions peaked along
  sampled words with noise, at both beam widths and under the cap: 2,724
  decodes, 21,792 candidates.
- Evals: the whole `core.gesture.eval` family (15 suites, the shift-detour
  eval included and not re-baselined) prints the same numbers and writes
  byte-identical reports before (step 3's merge, Kotlin decoders) and after.
  The only test changes are three loop tests that read the decoder's
  workspace, which now read the oracle's.
- Latency (`GestureLatencyBench` and `PredictionLatencyBench`, before at
  step 3's merge and after, alternating, two runs each; the machine was not
  idle, load average 8-17 with other sessions building): a whole stroke
  with six previews p50 13.5/14.3 -> 10.4/11.3 ms, p99 48.9/55.0 -> 42.3/43.7
  ms; one decode against the 4x lexicon p50 4.45/4.41 -> 3.42/4.54 ms; with
  a 6,445-word shape store 4.15/3.59 -> 3.12/2.68 ms. Typing walks the same
  Rust code before and after, and its keystroke p50 (0.382/0.345 -> 0.490/
  0.382 ms) moved with the load, not the change. Worth re-running idle.
- Size: arm64-v8a 2,010,704 -> 2,116,264 B (+105,560), armeabi-v7a
  1,462,992 -> 1,544,696 (+81,704), x86_64 2,297,184 -> 2,423,656
  (+126,472), against step 3's libraries, which already carried an unwired
  early build of the stroke decoder. The whole of glide against a step-3
  arm64 build without it: 1,887,872 -> 2,116,264 (+228,392).

### Step 5: what stays in Kotlin, and what porting it would take

`SuggestionEngine` stays in Kotlin, as planned: 5,031 lines of
orchestration over the Rust pieces, 41 public entry points. Most of what it
does is deciding which engines to ask and mixing their answers; the heavy
loops it calls are Rust now. For WMK Desktop, which already runs
`wm-engine`'s `predict::Predictor` (the typed path: `suggest`, `decide`,
the learning calls), porting the rest would mean:

- **Glide.** The decoders are done; desktop needs the orchestration around
  them: the engine's glide entry points (`tuneGlide` through
  `glideShapeOf`, about 400 lines) routing between `GlideBeam`, `DuetBeam`
  and the other `GlideDecoder`s, the contraction, apostrophe and trigger
  sources it builds per call, `GlideCoverage` (170 lines), `GlideCase`'s
  letter casing (335) and a shape store: `GlideShapeStore` (506) is Kotlin,
  file format and all.
- **The neural reading** (`NeuralGlide`): FUTO Swipe's encoder and decoder
  are ExecuTorch programs run on the app's ExecuTorch runtime
  (`core/voice`'s `FutoSwipe`). Desktop needs that runtime, or the models
  converted for one it has, and `SwipeFrame`'s features (`SwipeModel.kt`,
  194 lines); `glide::ctc` then reads the emissions as it does here.
- **Phonetic strips** (`phoneticCandidates`, `phoneticCommit`,
  `completionLatin`, `RomanizedIndex`): the transliteration engines are Rust
  already; how their readings are ranked into a strip, and the romanized
  index glide reads them through, are Kotlin.
- **Field and language context** (`seedFieldContext`, `owningLanguages`,
  the per-field mix): `language_mix` is ported; the field seeding and the
  language registry lookups are not.
- **Casing and display** (`displayOf`, `DictionaryCapitals`, `WordCase`):
  small. The fold itself is in Rust now (`word_key`, see "Unicode on
  ICU4X"), so desktop files words under the phone's keys; what is left is
  the display side: mapping a key back to its capitals (`displayOf`
  through the lexicon, `DictionaryCapitals`' shape tries) and matching the
  typed case (`WordCase`, `matchCase`), which need case mapping the other
  way (uppercase, titlecase) and would take `icu_casemap`'s.
- **Word segmentation for desktop.** `segment::words` is there; desktop's
  `predict` reads context and learns words by whitespace still, and would
  call it where the phone's `WordContext` and the service's `learn` do.
- **What desktop already has twice.** `predict::Lexicon` predates
  `user_lexicon::UserLexicon` and keeps its own trie and n-gram maps. It
  should become a wrapper over `UserLexicon`, as the correction stores'
  desktop wrappers are over theirs, so the two apps read one file format
  through one implementation.
- **Not worth porting:** contacts, app names, the system personal
  dictionary, notifications, anything that reads Android providers. Desktop
  has its own sources for those, and they enter the walk as sources like
  any other.

A full port is a few thousand lines and the main risk is behaviour drift in
the mixing, which no parity test covers today: the next step for desktop
would be an engine-level parity harness (Kotlin `SuggestionEngine` against
`Predictor` on the prediction eval's sessions), then moving one entry point
at a time behind it.

Status: steps 1 to 4 done, step 5 written up; Phase 2 complete. The
word key, the learnability gate and word segmentation followed on ICU4X
(below).

### Unicode on ICU4X: the word key and word segmentation (done)

What Phase 2 left in Kotlin because Rust had no Unicode tables, plus the
segmentation Thai, Lao, Khmer and Burmese prediction lacked. ICU4X 2.3
(Unicode 17, Unicode-3.0 licence): `icu_casemap`, `icu_normalizer`,
`icu_properties`, `icu_segmenter`, compiled data, default features off.

- **`word_key`**: `WordKey.of` / `surface` and `WordContext.isLearnableWord`
  on UTF-16 units. Kotlin's `lowercase()` is `toLowerCase(Locale.ROOT)`,
  which on ART is libcore's `CaseMapper`
  (libart/src/main/java/java/lang/CaseMapper.java), not ICU's full
  lowercase, and the port follows it: a string holding U+0130 or a high
  surrogate goes whole to ICU's root lowercase (full mappings, Unicode's
  Final_Sigma with case-ignorable skipping); any other string is the simple
  mapping (`u_tolower`) per unit, with Σ final when the unit before is Lu,
  Ll or Lt and the unit after is not. Root locale: no Turkish, Azeri or
  Lithuanian rules. NFC through `icu_normalizer`, with `WordKey`'s
  below-U+0300 shortcut. Lone surrogates pass through: ICU4X's UTF-16
  normaliser maps them to U+FFFD, so the valid runs between them are
  folded and normalised one at a time, which is exact because a lone
  surrogate is a non-ignorable, uncased starter and no context crosses it.
- **One-phase load**: `UserLexicon::load` folds and judges the file's words
  itself (`lexLoad` replaced `lexBeginLoad` / `lexFinishLoad`). WMK Desktop's
  `predict::Lexicon` now keys and gates with `word_key` too, instead of
  `str::to_lowercase` and an alphanumeric-ends check. The typing path still
  folds in Kotlin: a crossing per word would cost more than the fold.
- **Parity** (`WordKeyParityTest`, `:app`): Rust against `CaseMapper`
  transcribed over ICU4J 78, which Robolectric already puts on the test
  classpath and which is Unicode 17 like ICU4X. Every code point alone;
  every code point in eight sigma and composing contexts (each
  supplementary one is also a check of ICU4X's Final_Sigma against ICU's);
  every lone surrogate in five contexts; the bundled en and bn lists and,
  with `PARITY_DOWNLOADS`, the data repo's lists, each word as listed,
  capitalised and in capitals. Every one agrees, key, surface and gate:
  with the data repo's 431 lists at 20,000 words each, 7.1M words and
  17.6M spellings. One assumption is checked rather than taken: no BMP character
  lowercases out of the BMP, so Java's narrowing to a `char` loses nothing.
- **What a phone does differently** is Unicode version only. A phone folds
  with its own ICU, so it folds a character its Unicode lacks to itself
  and does not count it as a letter. Counted by age over every code point
  (the BMP only for the gate, which reads UTF-16 units): Android 14 and 15
  (Unicode 15.0, 15.1) differ on 55 folds and 44 letters, Android 16
  (Unicode 16.0) on 28 and 35, Android 7 (Unicode 8.0) on 255 and 369.
  They are characters added since: on Android 14 to 16, Latin additions in
  A7C0-A7DF, U+1C89 and new supplementary scripts; on Android 7 to 9 also
  Georgian Mtavruli (U+1C90 on, Unicode 11), which a Georgian all-caps word
  would hit. A store folded on such a phone and
  re-read here can disagree only on those characters, and a load re-folds
  only the capital spellings it keeps, which then go unremembered; nothing
  is dropped. HotSpot, where the JVM tests run Kotlin, differs from the
  phone in two ways the test accounts for (JDK 21's Unicode 15.0 and
  OpenJDK's word-boundary final-sigma rule): over every code point and
  four sigma contexts, 491 differences are characters newer than Unicode
  15.0, 58,523 are sigma contexts, and none is anything else. On the word
  lists HotSpot differs on 123 spellings, all of them the sigma rule.
- **A phone quirk the lists found**: polytonic Greek in capitals. τῆς
  uppercases to `ΤΗ͂Σ` (no capital eta with perispomeni exists, so the
  accent comes apart), and ART folds that to `τῆσ`: the unit before the Σ
  is the accent, which is not a cased letter, so the Σ is not final.
  HotSpot and ICU skip the accent and give `τῆς`, the list's spelling. So
  on a phone, an ancient or Pontic Greek word typed in capitals misses its
  dictionary entry (grc 109 spellings, pnt 12). Rust gives the phone's
  answer, as it must for the stores to agree; fixing it means
  `WordKey.of` reading Final_Sigma ICU's way on both sides (Kotlin and
  `word_key`), after which a key already stored the old way (`τῆσ`) would
  no longer be found. Left as a follow-up.
- **`segment`**: ICU4X's word segmenter with its LSTM models for Thai,
  Lao, Khmer and Burmese. LSTM because of size: the four models are
  310 KB of data, the four dictionaries 1.77 MB, and the dictionary
  constructor also links the Chinese/Japanese dictionary (3.8 MB on arm64
  in a measured build). Chinese and Japanese are left whole, as before.
  JNI `segmentWords`, Kotlin `WordSegments`.
- **Where text was split into words, and now is segmented**: nothing in
  prediction used Android's `BreakIterator`. `TextWordScan` (Learn from
  text) skipped every token in a spaceless script; it now scans Thai,
  Lao, Khmer and Burmese tokens as their words, pairs included, and still
  skips Han, kana, Tibetan, Javanese and Burmese the detector is sure is
  Zawgyi. `WordContext.completedWordBefore` / `lastTwoWords` /
  `lastThreeWords` read a run ending at the caret as one unfinished word
  (no context at all); they now take the run's last words, reading only
  its last 32 characters, with the words before the run filling in. The
  service's `learn` split a commit at spaces only; it now also splits a
  run in those scripts the keyboard does not know as a whole word.
- **Evals**: there is no Thai, Lao, Khmer or Burmese prediction eval (the
  `core.prediction.eval` family is English), so before and after could
  not be measured there. `wm-engine/tests/segment_eval.rs` (ignored; reads
  the data repo's top 20,000 list words) scores the segmenter on runs of
  two to six list words written together. Before this step none of those
  boundaries was found, since the whole run was one word:

  | | Boundary precision | Boundary recall | F1 | Words exact | µs per char (host) |
  |---|---|---|---|---|---|
  | Thai, LSTM | 0.450 | 0.752 | 0.563 | 0.322 | 15.5 |
  | Lao, LSTM | 0.774 | 0.922 | 0.841 | 0.792 | 13.3 |
  | Khmer, LSTM | 0.178 | 0.845 | 0.295 | 0.126 | 13.9 |
  | Burmese, LSTM | 0.402 | 0.831 | 0.542 | 0.319 | 13.6 |
  | Thai, dictionary | 0.354 | 0.760 | 0.483 | 0.319 | 0.22 |
  | Lao, dictionary | 0.740 | 0.929 | 0.824 | 0.825 | 0.23 |
  | Khmer, dictionary | 0.191 | 0.947 | 0.318 | 0.168 | 0.20 |
  | Burmese, dictionary | 0.403 | 0.961 | 0.568 | 0.452 | 0.24 |

  Low precision is mostly the lists, not the segmenter: both segmenters
  cut the same list entries, which are often compounds or phrases (the
  Khmer list is mostly multi-word entries with zero-width spaces, filtered
  out here; the Thai one, from subtitles, has fragments like `เธ` near the
  top). Recall is the number that says the words are found. The
  dictionary segmenter is about 70 times faster; at 15 µs a character the
  LSTM costs about 0.5 ms for a 32-character context read on the host,
  which is why the context window is capped. The phone column is owed,
  with the rest of this plan's.
- **Size**, against a fresh build of the main this branched from
  (f72a36bca, with the Zawgyi port; same size as its committed library):
  libwm_engine_jni arm64-v8a 2,232,944 -> 2,716,488 B (+483,544, +22%),
  armeabi-v7a 1,622,272 -> 2,082,712 (+460,440), x86_64 2,538,744 ->
  3,029,264 (+490,520). Measured piece by piece in a bare arm64 cdylib with
  the same profile: the segmenter about 373 KB (310 KB of it the four LSTM
  models, the rest word and grapheme break rules and the inference code),
  NFC about 73 KB, case mapping about 29 KB, the general-category map a few
  KB. The models are the floor for segmenting these scripts at all: ICU4X
  ships them as f32 matrices and has no smaller variant, and the
  dictionaries are 1.77 MB. Nothing else from ICU4X is linked: the
  segmenter's Chinese/Japanese dictionary and the other break iterators
  are named by constructors this crate never calls.

### Library size (done)

By the end of the ICU4X step `libwm_engine_jni` had grown from 1.1 MB to
2.7 MB on arm64. This pass shrank it without changing what it computes.

**What was in it**, from `cargo bloat` on an unstripped arm64 release build
and the linker map (2,716,488 B stripped, main 16d35c10b):

| Piece | Size | Notes |
|---|---|---|
| `.text` | 1,582 KB | |
| ... stable sort instantiations | 431 KB | 141 driftsort functions plus 92 shared small-sort helpers, one set per comparator closure: the stores (UserLexicon 95 KB, CorrectionMemory 56 KB), glide `Source` 47 KB, Hindi and Indic table builds 74 KB, fuzzy 31 KB |
| ... JNI entry points | 370 KB | engine code inlined into each `Java_*` symbol |
| ... std backtrace printing | 141 KB | gimli, addr2line, miniz_oxide and the demangler, reached from std's default panic hook |
| ... hashbrown, drop glue, fmt, serde_json | 130 KB | per-type `reserve_rehash` (31 copies), drop glue (220), formatting, JSON |
| `.rodata` | 726 KB | ICU4X LSTM models 310 KB; the four Khipro `.mim` files 196 KB; Zawgyi model 28 KB; normalizer, case and break data |
| `.rela.dyn` | 123 KB | 5,272 relocations of 24 bytes, mostly pointers in the compiled-in tables |
| `.eh_frame` + `.gcc_except_table` | 149 KB | unwind tables, needed by `catch_unwind` |
| `.data.rel.ro` | 92 KB | |

**What changed**, each measured with `build.sh` (stripped, all paths remapped):

| Change | arm64-v8a | armeabi-v7a | x86_64 |
|---|---|---|---|
| Before (16d35c10b) | 2,716,488 | 2,082,712 | 3,029,264 |
| Stable sorts through one instantiation (`wm_engine::sort`) | 2,324,752 (-391,736) | 1,781,496 (-301,216) | 2,516,168 (-513,096) |
| Khipro specs compiled in deflated | 2,177,496 (-147,256) | 1,629,280 (-152,216) | 2,371,616 (-144,552) |
| Relocations packed (`--pack-dyn-relocs=android`) | 2,065,864 (-111,632) | | |
| `opt-level = "s"` for serde_json and miniz_oxide | 2,062,464 (-3,400) | 1,588,632 (-40,648 with the row above) | 2,246,392 (-125,224 with the row above) |
| **After** | **2,062,464 (-24.1%)** | **1,588,632 (-23.7%)** | **2,246,392 (-25.8%)** |
| Merged onto main 0afd03d6a (CTC stroke readings), against its library | 2,737,336 -> 2,083,280 | 2,095,424 -> 1,601,392 | 3,053,648 -> 2,270,776 |

- **Sorts**: `wm_engine::sort::by` / `by_key` sort the positions `0..len`
  as `u32` through one comparator behind `dyn`, then permute the slice in
  place by following the permutation's cycles. A stable sort's output is
  fixed by its comparator when the comparator is a total order, and every
  caller's is (integers, UTF-16 order, `f64::total_cmp`), so the result is
  the slice method's; `sort`'s own tests compare the two with ties,
  owned elements and signed zeros. Every stable sort the Android library
  links goes through it except `kth_best`, which sorts plain `f64` scores
  inside the glide and fuzzy walks and keeps `slice::sort_by(f64::total_cmp)`
  (duet's copy now names the same comparator, so the three share one
  instantiation). The JSON exports insert into `serde_json::Map` instead of
  collecting into it, which had instantiated `BTreeMap`'s sort once per
  iterator type. `.text` 1,582 KB -> 1,232 KB.
- **Khipro**: `build.rs` deflates the four `.mim` files into `OUT_DIR`
  (miniz_oxide, level 10: 196 KB -> 30 KB) and `khipro` inflates a variant
  once, when its spec is first parsed. The files in `data/khipro/` stay
  verbatim for `update-khipro.sh`; a test checks each inflated spec against
  its file byte for byte. Adds `miniz_oxide` (MIT/Zlib/Apache-2.0, pure
  Rust, the crate std itself uses) as a dependency of `wm-engine`.
- **Relocations**: APS2 is read by bionic from API 23 and minSdk is 24.
  `llvm-readelf -r` decodes the same 5,272 relocations; `.rela.dyn` is
  13.6 KB. Segments stay 16 KB aligned and the 146 exported JNI symbols are
  the same on every ABI.

**Measured and not taken**:

- `--icf=safe` folds nothing (rustc emits no address-significance table);
  `--icf=all` saves 27 KB on arm64 but may merge functions whose addresses
  are compared, including NDK libunwind's C++, and could not be checked on a
  device.
- `opt-level = "s"` also for `jni` and `cesu8` saved another 2 KB; `jni`
  runs on every crossing, so it stays at 3.
- `panic = "abort"` is ruled out by `catch_unwind`, and with it the 149 KB
  of unwind tables. The 141 KB of backtrace printing comes with std's
  default panic hook from the precompiled std; dropping it needs
  `-Z build-std` (nightly).
- ICU4X's data is already only what the four constructors name. The LSTM
  models (310 KB) are the floor for segmenting Thai, Lao, Khmer and
  Burmese; storing them smaller would change their arithmetic.
- The Zawgyi model deflates only 28 KB -> 22 KB; not worth a second
  inflate path.

**Behaviour**: `cargo test` in `wm-engine` (471 tests) green. On the JVM,
the parity tests (`Native*ParityTest`, `WordKeyParityTest`), the Khipro
tests, the prediction evals (`PredictionEvalTest`, `ContextMissRateTest`,
`HabitGainTest`, the three rerank evals) and every glide eval under
`core.gesture.eval` ran twice in `:app`, once against a host build of main
before this pass (`WM_ENGINE_LIB`) and once against this one: 71 tests in
32 classes green both times, and every class's printed output identical
once timings are masked. After merging main 0afd03d6a (the CTC stroke
readings, whose new sorts go through `sort` too) the same run against a
host build of that main: 72 tests, identical output.

`PredictionLatencyBench` and `GestureLatencyBench` alone, alternating
before/after/before/after, load average 5 to 6.5 (other sessions idle but
not gone), p50 / p99 in ms:

| Bench | Before | After |
|---|---|---|
| keystroke (suggest + autocorrect) | 0.395 / 0.587, 0.405 / 0.620 | 0.408 / 0.603, 0.404 / 0.633 |
| octopus keystroke | 0.447 / 0.758, 0.473 / 0.808 | 0.475 / 0.790, 0.510 / 1.051 |
| suggest | 0.411 / 0.643, 0.452 / 0.727 | 0.419 / 0.673, 0.428 / 0.716 |
| glide decode, full path | 2.687 / 10.450, 2.901 / 11.177 | 2.619 / 9.820, 2.570 / 9.891 |
| glide stroke, 6 previews | 9.067 / 35.210, 9.386 / 36.609 | 9.419 / 36.420, 9.254 / 35.406 |

Within run-to-run noise. A first after-run that shared its JVM fork with the
evals read twice as slow on glide; alone it does not, so the benches have to
run on their own. The phone column is owed, with the rest of this plan's.

## Round 2: NEON after the ports

Phase 2 moved prediction and every glide decoder into Rust, and new
per-pixel Kotlin landed since Phase 0 (the camera tools, the upscaler,
frosted glass, Telegram's video stickers). This round measured all of it
the Phase 0 way and ported only what cleared the bar (about 1 ms per action
or 0.3 ms per keystroke, and over 1% of the action it belongs to).

**Harness additions** (`native/bench/phase0`): groups `frost`, `upscale`,
`webm`, `fuzzy` and `duet` run on host and phone; `renderfx`, `camera`
and `webp` are phone only (they need Android's graphics). `run.sh device`
takes `ENGINE_SO` (an arm64 library to push instead of the committed one,
for before and after on the same harness) and `PIN` (a `taskset` mask;
`c0` is the CPH2481's two A76 cores). It compiles the fuzzy walk's bridge
from source, so a checkout whose `core/*` jars predate the library still
matches it. The copied `buildCosts` loop went: its workspace is Rust now.

**The phone.** The CPH2481 is a MediaTek Helio G99 (MT6789: two Cortex-A76
at 2.2 GHz, six A55 at 2.0 GHz), Android 15. It is far slower than the
M-series host: ART runs the Kotlin loops 4 to 13 times slower, and Rust 3
to 4 times. Unpinned runs wander between the core types, so a phone
median moves by up to 1.5x between runs (CtcGlideBeam, which nothing here
touched, read 4.6 to 8.0 ms); pinned runs are steadier but still noisy.
The phone dropped off adb once in the middle; every column was run once
it was back.

| Candidate | Host | Phone (G99) | How often | Verdict |
|---|---|---|---|---|
| Glide DP band (`GlideBeam` advance + `build_remain`, `DuetBeam` advance) | Rust decode 1.85 ms, previews 6.4 ms, DuetBeam 1.1 ms; the band is 94% of a GlideBeam decode (advance 65%, build_remain 28%) and 90% of DuetBeam | decode 5.4-6.8 ms, six previews 22-31 ms, DuetBeam 3.6-4.5 ms | per swipe; previews while the finger moves | **port**, done |
| WebM frames (`WebmFrames.rgbOf`, `lumaOf`, `compose`) | 3.0-3.3 / 0.017 / 0.11 ms a 512 px frame | 14.7 / 3.2 / 0.64 ms a frame, about 90 frames a sticker | per video sticker imported or sent | **port** `rgbOf`, done; `lumaOf` a Kotlin fix; `compose` stays |
| Frosted glass (`FrostBake.boxBlur`, three passes on the quarter-size board) | 1.15 ms portrait board, 5.8 ms a whole QHD screen | 7-9 ms portrait, 19-24 landscape, 22-39 tablet, 45-59 QHD | per bake (theme, wallpaper or slider step), cached | **port**, done; RenderEffect compared |
| Upscaler glue (`UpscaleTiles` input/output, `UpscaleEngine`'s alpha merge, `bleed`) | 3.4 ms a 512 px ×2 pass, merge 1.2 ms, bleed 27 ms | 40 ms a pass, merge 4.8 ms, bleed 114 ms; 1080p pass 221 ms | per upscale, beside a network of about 1-3 s (512 px) to 10+ s (1080p) on this phone | **port** input/output/merge, done; `bleed` a Kotlin fix |
| `VectorIndex` with fp16 rows | f32 1.83 ms, fp16 0.91 ms at 20k × 768 (memory halves) | f32 5.9-6.2 ms, fp16 3.9 ms (the whole search 7.5 ms) | per meaning search | **not adopted**: rankings (below) |
| Fuzzy walk (`fuzzy::search_units`) | 0.32 ms a whole typo word, 0.045 ms a prefix | 1.08 ms a whole word, 0.19 ms a prefix | per keystroke | **skip as a kernel**: no rows to vectorise (below) |
| Camera frames, QR | no Kotlin per pixel | no Kotlin per pixel | 30 fps while scanning | **nothing to port**: the Y plane goes to zxing-cpp in one crossing already |
| Camera frames, OCR | a still, not a stream | rotating the 12 MP still 470 ms, `getPixels` 50 ms, both Skia | per capture | **nothing to port** in Kotlin: CameraX's `toBitmap` (libyuv) and Skia, then the pipeline Phase 1 ported. The rotate is worth a look outside this plan (see below) |
| `AnimatedWebpToGif` | no Kotlin per pixel | a 512 px frame: decode 5.6 ms, draw, scale and `getPixels` 1.4 ms, `GifEncoder` 6.5 ms | per animated sticker into a GIF field | **nothing to port**: decode, draw and scale are Skia, `getPixels` a copy, the encoder Rust since Phase 1 |

### Glide band (done)

`kernels::band` (`min_arc`, `min_steps`, `min_ahead`) is the inner loop of
`GlideBeam`'s `advance` and `build_remain` and `DuetBeam`'s `advance`: the
cheapest way into one sample from the band of samples the previous letter
may have ended on, charged for the gap between the distance the finger
travelled and the distance between the keys. With `gap_window` 14 the band
is about thirty samples, so it vectorises along the band, four samples a
step, not per trie node (no extra crossing: the decoders are Rust).

- Exact by construction: the same `f32` operations in the same order
  (`(arc_j - arc_i) - span`, `|gap| - ease`, `weight * over`, no fused
  multiply-add), unreachable samples masked to the cut, and a minimum,
  which over the same values is the same value in any order. There is no
  NaN, and no `-0.0` (a charge is `+0.0` or positive). Sample 0 (eased by
  the lead) and `build_remain`'s last sample (eased by the end anchor) stay
  scalar beside the kernel call. `build_remain` reads a key's costs and
  remainders along the samples, so the workspace keeps those two tables
  transposed for the near keys (`point_cost_t`, `remain_t`).
- Tests: each kernel against its reference bit for bit over every tail
  length, ties on the ease and signed zeros; and
  `glide::kernel_parity`, 150 swipes decoded with the kernels pinned to
  scalar and not (finished, with look-ahead, six previews each, DuetBeam
  whole and partial), equal. A planted 1e-4 error in the NEON charge fails
  it. `NativeGlideParityTest` (production against the Kotlin oracles) and
  the whole `core.gesture.eval` family (18 suites) ran twice through
  Gradle, once with `WM_NATIVE=kotlin` (the kernels pinned to their
  references, so the old arithmetic; the test JVM's environment was
  checked) and once with NEON: all green both times, and every report
  under `build/reports/gestureeval` and every line the suites print,
  timing lines aside, byte-identical.
- Speed: the Phase 0 harness against a build of the library before this
  round, alternating, two runs each. CtcGlideBeam, which this does not
  touch, is the control. Host (load 3.6-4.1): GlideBeam decode 1.82 to
  0.72-0.85 ms (p95 5.9 to 1.8-2.3), six previews 6.3 to 2.8-3.3 ms,
  DuetBeam 1.10-1.12 to 0.37 ms (p95 5.7 to 1.6); CtcGlideBeam 1.89 both.
  Phone, pinned to the A76s: decode 6.4-6.9 to 2.3-3.4 ms, previews 22-29
  to 8.6-14.0 ms, DuetBeam 3.6-5.0 to 1.7-1.9 ms; CtcGlideBeam 4.6-6.9
  both. `GestureLatencyBench` in the two Gradle runs read 4.4 to 0.88 ms
  a decode and 16.4 to 3.5 ms a stroke, but the first run shared the
  machine with its own compile, so the harness numbers are the fair ones.
- Size: arm64 +3,776 bytes on its own.

### WebM frames (done)

`WebmFrames.rgbOf` read three bytes a pixel through `ByteBuffer.get`.
`NativeEngine.yuvToRgb` takes the `Image` planes as they are (direct
buffers, each up to its limit, with its strides and the crop corner): one
crossing a frame and no copy, the shape the brief asked for camera frames
too. `kernels::yuv` converts sixteen pixels a step: `ld2` takes every other
byte of an interleaved chroma plane, the sums run in `i32` lanes as
Kotlin's `Int`s do, the shift is arithmetic and two saturating narrows
are `coerceIn(0, 255)`. Integer arithmetic, so the output is bit for bit
the loop's. A read past any plane is refused before anything is written,
and the Kotlin loop runs (as it does without the library or with the
native path off), so a malformed frame still fails where it failed.
NEON needs a luma pixel stride of 1, an even crop left and chroma strides
of 1 or 2; anything else takes the scalar reference. `lumaOf` copies whole
rows with a bulk `get` when the luma bytes sit side by side: a Kotlin
change, no kernel.

- Tests: sizes, crops, I420 and semi-planar planes with row padding and
  buffers ending at their last byte, and every luma and chroma value
  through each lane; `NativeEngineTest` against `WebmFrames`' loop on
  direct buffers, heap buffers and short planes refused.
- Host: 3.0-3.3 to 0.11-0.12 ms a frame (27x); `lumaOf` 0.017 to 0.012
  ms. Phone, pinned: 11.5-13.8 to 0.40 ms a frame, `lumaOf` 2.4 to 0.19
  ms, so a 90-frame sticker's conversion goes from about 1.3 s to 50 ms.
- `compose` (0.64 ms a frame on the phone, about 58 ms a sticker) stays:
  it is one pass over two arrays, close to what the memory allows.

### Frosted glass (done)

`NativeEngine.boxBlur` runs `FrostBake`'s three passes in one crossing;
`kernels::box_blur` slides four rows' windows side by side for the
horizontal box and sixteen channels (four columns) for the vertical one.
Each channel's `sum / window` is `(sum * ceil(2^24 / window)) >> 24`,
exact for every sum a window of up to 256 pixels holds (the test checks
all of them); a wider window takes the reference. Bit for bit the Kotlin,
which stays the fallback. `core/theme` now depends on `core/language` for
the engine, and its tests build the host library.

- RenderEffect, measured on the phone (Android 15) through a
  `HardwareRenderer` into an `ImageReader`, quarter-size board, radius 8:
  4.0 ms GPU-only and 8.4 ms with the pixels read back for a portrait
  board, 8.5 and 19.6 ms for a whole QHD screen, against NEON's 1.1 and
  11.4-11.9 ms pinned to the A76s. It is also a different picture (every
  pixel differs, by 2.5 levels a channel on average and up to 6), and
  only on API 31+, where `FrostBake` promises the same glass on Android 8
  as on 15. NEON is the faster correct path, so RenderEffect is not used.
- Host: 1.15 to 0.26 ms a portrait board, 2.1 to 0.46 landscape, 3.8 to
  0.79 tablet, 5.8 to 1.3 ms a QHD screen. Phone, pinned: 6.9-7.4 to
  1.1 ms portrait, 24 to 5.6-6.8 landscape, 23-39 to 4.7-5.6 tablet,
  45-51 to 11.4-11.9 ms QHD.

### Upscaler (done)

`kernels::upscale`: a tile's input as RGB planes (an IEEE divide by 255,
edge pixels repeated outside), the network's answer back to opaque ARGB
(`fcvtas`, which rounds a tie away from zero and so differs from
`Math.round` only on negative ties, which the clamp sends to 0 either way)
and `UpscaleEngine`'s alpha merge (the grey's mean as a multiply-shift
exact for every sum). One crossing a tile each, through
`NativeEngine.upscaleInput` / `upscaleOutput` / `mergeAlpha`, bit for bit
the Kotlin, which stays the fallback. `UpscaleTiles.bleed` searched the
whole picture once per ring, sixteen rings, through boxed lists; a ring
can only lie beside what the ring before it added, so it is now gathered
from there. Same result (the test keeps the old scan as its reference),
and a Kotlin change, not a kernel.

- Host: a 512 px ×2 pass's glue 3.4 to 0.56 ms, a 1024 px pass 13.0 to
  2.2, a 1080p pass 26 to 4.4 ms; the alpha merge about 2x (it is mostly
  memory); `bleed` on a 512 px sticker 27 to 2.6 ms. Phone, pinned: a
  512 px pass 38 to 27 ms (what is left is the stand-in network's copy
  and a fresh array per tile), a 1024 px pass 121 to 47, a 1080p pass 232
  to 79 ms; the merge 5.3 to 2.7 ms at 1024 px and unchanged at 4K
  (memory); `bleed` 114 to 11 ms. A 512 px sticker ×2 with alpha spends
  about 40 ms around its network instead of about 160.

### fp16 rows for `VectorIndex` (not adopted)

Speed, from a standalone probe on the host (the existing `dot_rows_f32`
against `fcvtl` widening and `vfmaq_f32` over half-precision rows, same
rows and query): 0.089 to 0.045 ms at 1k × 768, 0.46 to 0.23 at 5k, 1.83
to 0.91 at 20k, the matrix 61 to 31 MB. On the phone, pinned: 0.29 to
0.19 ms, 1.4-1.7 to 0.94-1.0, 5.9-6.2 to 3.9 ms; the whole f32 search
there is 7.5 ms at 20k, so fp16 would save about 2 ms and half the
memory.

The bar was that the top hits match the way f32's do, and they do not.
Measured on real EmbeddingGemma 2 outputs, re-embedded the way the
dot-product session did it (`embed_corpus.py` with the pinned models
release files, SHA-256 checked: 4,200 documents from the docs site and
the app's English, Bengali and French strings, 380 queries) plus the
1,716 shipped emoji vectors. Scores were summed in each implementation's
order in float32: the Kotlin loop as the reference, the NEON `dot_f32`
(two four-lane `fmla` accumulators, pairwise reduce), and that same NEON
order over rows rounded to half precision. "Same hits" is
`MeaningRank.select`'s whole answer, hits and order, under the shipped
tunings:

| Search | f32, NEON order: same hits / top hit | fp16 rows: same hits / top hit | Largest score error, f32 / fp16 |
|---|---|---|---|
| Text, 4,200 documents | 379 / 380, 380 / 380 | 297 / 380, 380 / 380 | 1.7e-6 / 1.6e-4 |
| Text, a 300-item clipboard | 380 / 380, 380 / 380 | 322 / 380, 380 / 380 | 1.6e-6 / 1.2e-4 |
| AI memory, 60 facts | 380 / 380, 380 / 380 | 367 / 380, 379 / 380 | 1.4e-6 / 1.3e-4 |
| Emoji by meaning | 380 / 380, 380 / 380 | 357 / 380, 380 / 380 | 1.6e-6 / 9.6e-5 |
| Sticker tags, 1,000 documents against the emoji | 1,000 / 1,000, 1,000 / 1,000 | 983 / 1,000, 997 / 1,000 | 1.7e-6 / 9.4e-5 |
| Emoji against emoji (tags) | 500 / 500, 500 / 500 | 500 / 500, 500 / 500 | 1.8e-6 / 1.1e-4 |

The f32 kernel's different summation order moves a score by under 2e-6
and changes one answer in 2,320 searches. fp16 moves scores by about
1e-4, enough to reorder hits the tunings keep 0.01-0.02 apart, and changes
the answer for up to 22% of text searches, and the top hit for 4 of them.
That is a visible change, so the rows stay f32. It would take a tuning
whose separations sit well clear of 1e-4 to revisit this.

### The fuzzy walk (not a kernel)

`fuzzy::search_units` is a best-first search over the trie with a binary
heap, not a matrix of edit rows: each popped state pushes its children's
edits one at a time. Sampled on the host: the heap's pop 28%, pushing
states 15%, the walk's own branching 22%, key-set membership 9%, copies
6%, the logarithm 4%, hashing 3%. Nothing there runs the same operation
over a row of data, so NEON has nothing to take; the heap's order is what
keeps the walk equal to the Kotlin oracle's. At 1.08 ms for a whole typo
word on the phone (0.19 ms a prefix), it is over the 0.3 ms keystroke bar
at the end of a long word, which makes it a candidate for algorithmic work
(fewer pushes, a cheaper key-set test), not for SIMD.

### Size

libwm_engine_jni against main after the library size pass (5e9207382),
rebuilt with these kernels merged in: arm64-v8a 2,083,280 -> 2,105,224 B
(+21,944), armeabi-v7a 1,601,392 -> 1,611,920 (+10,528), x86_64
2,270,776 -> 2,287,608 (+16,832). Before that merge, against the main it
branched from, the band kernel alone was +3,776 on arm64 and all four
+22,792. armv7 and x86_64 carry only the scalar references.

Found on the way, outside this plan: rotating a 12 MP OCR still upright
(`ImageProxy.toFramedBitmap`'s `createBitmap` with a matrix) takes about
470 ms on the G99, ten times `getPixels`. Asking CameraX for the rotation
(`ImageCapture`'s target rotation) or reading the unrotated still and
rotating the 640 px detector input instead would skip most of it.

Status: round 2 done, host and phone.

## Round 3: what is still Kotlin with a Rust port beside it (measured, nothing ported)

`wm-engine` holds ports of the CJK composers and the phonetic indexes for WMK
Desktop, and `wm-engine-jni` exposes neither, so the phone runs the Kotlin
copies. This round times both sides on the same files and buffers.

**Harness:** `run.sh <host|device> only=cjk,phonetic` (packs from
`DATA_REPO`) for Kotlin; `wm-engine/examples/cjk_phonetic_bench.rs` for Rust,
which reads the `bn_inputs.txt` the Kotlin group writes. Buffers: every prefix
of 200 held-out Pinyin clauses (4,243), of 16 everyday Japanese romaji
sentences (369), and of 311 common Bangla words romanized (2,048). The packs
are the real ones: `pinyin_v2`, `pinyin_ngrams`, `ja_kana`, `bn_phonetic_v1r2`.

**Phone, 2026-10-30:** CPH2481 pinned to the A76s, median / p95 in ms. Kotlin is
ART's JIT with no AOT profile, warmed for a second. Rust has no JNI crossing,
which a port would add once per call.

| Case | Kotlin | Rust |
|---|---|---|
| Pinyin, per keystroke | 6.89 / 29.8 | 4.26 / 67.2 as written; 3.34 / 10.1 with the Viterbi fix below |
| Pinyin, 17+ letters | 8.37 / 32.0 | 7.07 / 39.2; 4.61 / 15.8 with the fix |
| Japanese, per keystroke | 2.56 / 7.9 | 0.43 / 1.16 |
| Japanese, 17+ letters | 5.16 / 9.0 | 0.94 / 1.9 |
| Load `pinyin_v2.tsv`, first / warm | 1,020 / 247 | 46 / 36 |
| Load `pinyin_ngrams.tsv`, first / warm | 5,119 / 2,131 | 613 / 534 |
| Load `ja_kana.tsv`, first / warm | 4,142 / 2,307 | 512 / 415 |
| Keeps `ja_kana` | 44.8 MB Java heap | 54.4 MB native RSS |
| Bangla `lookup` | 0.010 / 0.025 | 0.002 / 0.006 |
| Bangla `lookupLoose` | 0.026 / 0.068 | 0.003 / 0.010 |
| Bangla `phoneticCandidates`, `phoneticCommit` | 0.038 / 0.116, 0.034 / 0.054 | not ported |
| Bangla `completions(10)`, a Khipro keystroke | 0.150 / 39.5 | not timed |
| Open the Bangla table | 8.5, 0 MB (mapped) | 1,257, 33.5 MB (builds from the list) |

**Verdicts:**

- **Phonetic indexes: skip.** The whole Banglish strip is about 40 µs, under
  the 0.3 ms bar by nearly ten times, and a JNI crossing would eat the 20 µs a
  port saves. The Rust index also builds from the list where Kotlin maps
  WMPI. This bench leaves out the user's spelling map and learned words, and
  times only the phonetic calls, not the rest of a Bangla suggestion pass.
- **`completions`: a Kotlin fix.** The p95 is a one-letter prefix walking tens
  of thousands of words through a boxed heap. It needs a cheaper walk or a
  stored top list for short prefixes, in either language.
- **CJK load: the largest gain here, and mostly about format.** Rust parses the
  three packs 4 to 7 times faster warm, and up to 22 times faster on a first
  load, which is what a user gets. It saves no memory, because `ja_kana` is
  mostly kana and UTF-8 spends three bytes where a `CharStore` spends two. A
  prebuilt mapped table, as the phonetic indexes have, would cost about what
  opening the Bangla table does, in either language, and would take the packs
  off the heap.
- **CJK keystrokes: worth porting for Japanese (6x), modest for Pinyin (2x
  median, 3x p95)**, and only after the two items below.

**Before wiring the port:**

- `lattice::viterbi` removed duplicate last words with a linear scan of its
  own best list, quadratic in the paths at a node. A buffer ending in a bare
  initial (`zh`, `sh`, `y`) builds thousands, so `yizh` took 425 ms on the host.
  A hash map, which is what the Kotlin decoder already uses, took mean Pinyin
  from 11.39 to 1.09 ms on the host with byte-identical output on all 4,243
  buffers. Not landed yet. WMK Desktop runs this code today.
- Output parity. Against Kotlin: Pinyin 4,240 of 4,243 strips identical (the
  rest differ in the twelfth candidate, an astral character's sort order),
  Bangla 2,048 of 2,048, and Japanese only 219 of 369. Japanese's first
  candidate agrees on 368, but the Rust strip drops a stitched reading Kotlin
  keeps (`watashihaga` loses 私はが). T9 pinyin, Zhuyin, Cangjie/Quick and the
  stroke method have no port and stay in Kotlin whatever happens.

Status: round 3 measured, host and phone.

### The port (done)

The seam is below the composers: `Lattice`, `ConversionDictionary` and
`CjkNgrams` are Rust behind Kotlin facades (JNI `cjk.rs`, Kotlin `CjkNative`), so
Pinyin, T9, Zhuyin, Jyutping, Japanese and imported methods all run on it, and the
composers, their graphs and learning stay in Kotlin. The Kotlin decoder and tables
moved to `app/src/test/.../input/composer/reference/` as the oracles.

- **Tables** are one flat byte layout, parsed or mapped. `ConversionDictionary.open`
  and `CjkNgrams.open` parse a pack once, write `<pack>.wmcd` beside it, marked with
  the pack's length and modification time, and map that file from then on. A failed
  write only means the next open parses again. No new download: the data repo's
  TSVs are what the phone still fetches.
- **User history** no longer answers per word. `CjkUserHistory.userData` snapshots a
  namespace into Rust once per change, and `Lattice.UserScope` keeps what each
  composer read before (Pinyin everything, imported methods picks, the rest only
  the candidate menu's answers).
- **Rust changes for parity:** the hashed dedupes in `viterbi` and `stitched_paths`,
  `text != reading` in the stitch gate, word length in UTF-16 units, fdlibm logs.

**Verified:** every strip for 4,243 Pinyin, 369 Japanese and 2,048 Bangla buffers is
byte-identical to the Kotlin decoder's on the real packs. `NativeCjkParityTest`
compares 2,000 generated decodes (astral words, history, every option) and the real
Pinyin pack, candidates in order with scores within 1e-9. The 204 tests of the CJK
suite pass, and `PinyinBenchmarkTest` reads what it always has: 54.2% / 83.7% full,
5.4% / 27.0% initials. All through kotlinc over Gradle's jars, not a Gradle run.

**Phone, A76s pinned**, through the real composers and JNI. Same day, same state,
pre-port Kotlin (`91ab0dd72^`) alternated with this commit's library over every
buffer: 4,243 Pinyin, 369 Japanese three times. Five Kotlin runs and three settled
Rust runs; the first Rust run after a `.wmcd` is written is slower (up to 6.9 mean
Pinyin) and is left out. Kotlin is ART's JIT with no AOT profile, warmed for 5 s.

| Case, ms | Kotlin | Rust |
|---|---|---|
| Pinyin keystroke, mean | 3.34-3.61 | 3.96-4.00 |
| Pinyin keystroke, median | 2.45-2.59 | 2.92-2.97 |
| Pinyin keystroke, p95 | 9.8-12.1 | 10.8-11.0 |
| Pinyin keystroke, p99 | 16.8-17.7 | 18.4-18.6 |
| Japanese keystroke, mean | 1.62-1.68 | 1.14-1.16 |
| Japanese keystroke, median | 1.36-1.47 | 1.00-1.02 |
| Japanese keystroke, p95 | 4.4-4.5 | 3.0-3.1 |
| Japanese keystroke, p99 | 5.5-6.6 | 3.6 |
| `pinyin_v2`, process's first load | 225 | 43 once, then 0.7 |
| `pinyin_ngrams`, process's first load | 1,277 | 576 once, then 0.6 |
| `ja_kana`, process's first load | 1,490 | 489 once, then 0.6 |
| Java heap held by the three | 64.9 MB | 0 (mapped, evictable) |

**Pinyin got about 0.6 ms slower per keystroke and Japanese about 0.5 ms faster.**
The load and heap wins stand. An earlier version of this table had Pinyin at
6.89 / 29.8 against 6.82 / 33.9 and loads of 1,020 / 5,119 / 4,142. Those runs were
hours apart in a phone state about twice as slow, and `bench`'s 400-call window
samples a different stretch of the buffers on each run, so they could not compare
anything. The same goes for the Kotlin column of the round 3 table above. Compare
keystrokes only with alternating runs over the whole buffer list.

**Where one keystroke goes**, the same buffers with timers in a scratch copy of
`Lattice.kt`, `PinyinComposer.kt`, `lattice.rs` and `cjk.rs`, two runs, mean ms:

| Stage | Pinyin | Share | Pinyin, slowest 5% | Japanese | Share |
|---|---:|---:|---:|---:|---:|
| Rust `viterbi` | 2.67-2.77 | 66% | 12.5-13.2 | 0.21-0.22 | 18% |
| Rust edges (`build_edges`) | 0.61-0.63 | 15% | 2.6 | 0.17-0.20 | 15% |
| JNI and its strings, beyond the Rust below | 0.44-0.46 | 11% | 1.7-1.8 | 0.15-0.21 | 13-16% |
| `PinyinGraph.build` | 0.11 | 3% | 0.24 | none | |
| Rust ordering (`by_coverage`, `by_first_word`) | 0.09 | 2% | 0.13 | 0.52 | 45% |
| Rest of the composer | 0.05 | 1% | 0.05 | 0.06 | 5% |
| Lattice marshal and split, variants, learning | 0.07 | 2% | 0.08 | 0.02 | 2% |
| Rust argument and result copies | 0.04 | 1% | 0.06 | 0.02 | 2% |

So the composers are not the cost, and porting `PinyinGraph` would save at most
0.11 ms. Rust's `viterbi` alone costs about what the whole Kotlin decoder did, so any
Pinyin gain has to come from inside it; why it is no faster than the JIT has not
been measured. The crossing is a tenth of the keystroke, and for Japanese the
target would be `by_first_word`, not the Viterbi.

**Costs:** libwm_engine_jni arm64-v8a 2,223,032 -> 2,511,328 B (+288,296),
armeabi-v7a 1,697,808 -> 1,915,576 (+217,768), x86_64 2,418,696 -> 2,756,592
(+337,896). Each `.wmcd` is about 16% bigger than its pack and sits beside it:
`ja_kana` is 41.5 MB plus 48.0, 89.5 MB in all.

## Rules for every step

- **Home:** kernels and ported code go in `wm-engine` / `wm-engine-jni`. That
  library is in the APK, loads at startup and in direct boot, and never moves
  to an on-demand gate. Typing cannot wait for a download.
- **NEON:** stable `core::arch::aarch64` intrinsics, not nightly `std::simd`.
  Runtime detection (`is_aarch64_feature_detected!`) for `dotprod` and `fp16`.
  armv7's NEON intrinsics are unstable in Rust, so armv7 runs the scalar
  reference (built with the target's `+neon`, which LLVM may vectorise);
  x86_64 and anything else get the scalar path too.
  Every kernel keeps a scalar reference implementation and a test comparing
  the two within a stated tolerance.
- **JNI:** batch work per call, never one crossing per element. Primitive
  arrays through `GetPrimitiveArrayCritical`, or direct ByteBuffers. Mapped
  data stays on the Rust side behind handles. Every entry point runs inside
  `catch_unwind`, so a panic becomes a Java exception, not a dead keyboard.
- **Tests and evals:** a host build of `wm-engine-jni` (macOS and Linux,
  `build-host.sh`) is built before JVM tests and in CI, so the `:app` suite and
  the glide evals exercise the Rust code, not a stub.
- **Builds:** reproducible as today (F-Droid builds `wm-engine` from source),
  16 KB aligned, only JNI symbols exported, size change reported per commit.
- **Not moving:** Compose UI, `WMKeyboardService` orchestration, settings, the
  AI and voice runtimes (already native), anything I/O or network bound.

## Risks

- JNI overhead on small calls can eat the gain; Phase 0 and batching catch it.
- Native crashes are harder to read: `catch_unwind`, symbols kept for the
  retrace bot, and a setting that forces the scalar Kotlin path during the
  migration.
- Two implementations during migration: parity tests gate every deletion.
- ART already vectorises a few simple loops: measure before porting.
