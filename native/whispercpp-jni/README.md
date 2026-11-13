# whispercpp-jni: Offline Whisper on whisper.cpp

[whisper.cpp](https://github.com/ggml-org/whisper.cpp) runs Offline Whisper's
ggml models (#440), beside the LiteRT graphs it already had. Kotlin entry
point: `com.wasimaster.wmkeyboard.core.voice.whisper.WhisperCpp` (in
`core/voice/src/main`), driven from `WhisperCppPlan` and the IME service.

Where a LiteRT graph has Hugging Face's whole `generate()` traced inside it,
whisper.cpp runs the decoder loop itself, so this bridge can offer what
Whisper takes: a language by name (any of the 99), detection kept to the
languages being dictated in, an initial prompt, beam search, text while it
decodes, stopping part way, and clips past 30 seconds.

The built libraries are committed under `core/voice/src/main/jniLibs/`, so the
app builds without an NDK, and every build carries them.
F-Droid deletes the committed copies and builds them with this script
(through `native/build-fdroid-libs.sh`), and so does the release workflow for
the reference APK F-Droid compares against; the script maps the build
machine's paths out of the libraries so the two builds match. Rebuild after
you change anything here:

```sh
ANDROID_NDK=/path/to/ndk-r29 native/whispercpp-jni/build.sh
```

It needs CMake, Ninja and Python 3 on `PATH`, and fetches whisper.cpp, and
Khronos's OpenCL headers and ICD loader, at the pinned commits unless
`WHISPER_SRC`, `OPENCL_HEADERS_SRC` and `OPENCL_LOADER_SRC` point at checkouts
of them. A full rebuild is five configurations and takes several minutes
each. `WMKB_WHISPER_GPU=0` skips the OpenCL one.

## Provenance

whisper.cpp `v1.9.5`, commit `d1be6fde11ac6e0407606b4e42fe72d34add8037`,
unmodified, built with the CPU backend (no OpenMP), and for the OpenCL
variant ggml's OpenCL backend beside it. whisper.cpp and ggml are MIT;
`LICENSE` is upstream's. The OpenCL variant compiles against Khronos's
OpenCL-Headers and links against its OpenCL-ICD-Loader (both Apache-2.0,
`v2026.05.29`, the same pins as `native/llamacpp-jni/pin.sh`); neither ships,
the phone's own `libOpenCL.so` answers at run time.

The models are not bundled. The keyboard downloads them on request from the
`ggerganov/whisper.cpp` Hugging Face repo at revision
`5359861c739e955e79d9a303bcbc70fb988958b1`, and checks each against the
SHA-256 in `WhisperCatalog` (the LFS oid Hugging Face reports for it). The
weights are OpenAI's Whisper (MIT), quantized by the whisper.cpp project.

## ABIs

| ABI | Library | Instruction set |
|---|---|---|
| arm64-v8a | `libwmwhispercpp.so` | ARMv8.0, every 64-bit phone |
| arm64-v8a | `libwmwhispercpp_v82.so` | ARMv8.2 with dot product and fp16 |
| armeabi-v7a | `libwmwhispercpp.so` | ARMv7 with NEON |
| arm64-v8a | `libwmwhispercpp_opencl.so` | ARMv8.2 as above, plus ggml's OpenCL backend for Adreno |

`WhisperCpp` loads the ARMv8.2 build only when every core's `Features` line in
`/proc/cpuinfo` lists `asimddp`, `asimdhp` and `fphp`; big.LITTLE parts can mix
generations, and one core without them would die on an illegal instruction.
Quantized matrix maths, which is most of the work, runs several times faster
with the dot-product instructions.

## The GPU build

`libwmwhispercpp_opencl.so` is the ARMv8.2 build with Qualcomm's OpenCL
backend for ggml compiled in (Adreno matrix and flash-attention kernels,
embedded as source, compiled by the phone's driver). It binds its own JNI
class, `WhisperCppGpu` (`-DWM_JNI_CLASS`, the `JNI_FN` macro), so it loads
beside the CPU build in one process: Compute can change in a running
keyboard, and a GPU that fails hands the clip to the CPU build already
loaded. With `use_gpu` off it runs on its own CPU backend, but the app never
asks it to; a CPU clip goes to the CPU build, which never starts OpenCL.

It links to `libOpenCL.so` by name. The loader built here satisfies the link;
on the phone the vendor's library does, which an app targeting API 31 or
later only sees because `core/voice`'s manifest declares it
(`uses-native-library`, not required). A phone without one fails that load
and dictates on the CPU. `WhisperCppGpu` loads it only on Adreno (the kgsl
node, `SOC_MANUFACTURER` QTI or `HARDWARE` qcom) with ARMv8.2 cores, since
the backend refuses every GPU but Adreno and Intel.

What a GPU failure looks like, and what catches it:

- No device, or the backend will not start: whisper.cpp falls back to its CPU
  backend quietly, and says so only in its log. `nativeOpen` listens to the
  log for the backend it took (`nativeOnGpu`), and Kotlin closes such a
  handle and opens the CPU build instead.
- Not enough memory for the model: the buffer allocation fails and
  `whisper_init` returns null; the CPU build opens it.
- A failure while transcribing that whisper.cpp reports: the clip runs again
  on the CPU build.
- An OpenCL error ggml cannot recover from aborts the process (`CL_CHECK`),
  and so does a driver crash. Kotlin writes the step it is on to
  `noBackupFilesDir/whispercpp-gpu-attempt` first and removes it after; the
  next process finds it and remembers the failure. Verdicts are keyed to
  `Build.FINGERPRINT` and the install time, so a system or app update tries
  again, as does choosing GPU on the screen.

`nativeGpuInit` sets `GGML_OPENCL_KERNEL_CACHE_DIR` to
`codeCacheDir/whispercpp-opencl`, so the driver compiles the kernels once
(seconds) and later processes load the binaries. `codeCacheDir` is emptied
by an app update, which is when the kernels change.

### Why OpenCL, and not Vulkan

Measured on an M2 Mac (16 GB), Vulkan through MoltenVK and Apple's Metal as
a reference, `whisper-bench` at this pin: encoder time for one 30 s window,
q8_0 models, 4 threads, median of 5:

| Model | Vulkan | Metal | CPU (NEON, 4 threads) |
|---|---|---|---|
| Tiny | 94 ms | 57 ms | not measurable, see below |
| Base | 199 ms | 96 ms | not measurable |
| Small | 620 ms | 318 ms | not measurable |

The CPU column could not be measured: other builds held the Mac at a load
average of 100 to 220 for the whole session, and the CPU runs ranged from
0.4 to 3 s for Tiny alone, while the GPU ones were steady. Memory: the GPU
holds the weights instead of the CPU (Small: 264 MB on the GPU against
211 MB repacked plus 53 MB on the CPU), and the compute buffers are the same
size either way (Small: conv 23 MB, encode 34 MB, cross 29 MB, decode 98 MB),
so on a phone, whose GPU shares the RAM, the peak is about the same.

Words: a 25 s English clip (macOS `say`) came out word for word the same on
CPU, Vulkan and Metal for Tiny, Base and Small, greedy and beam 5. One comma
differed (Base, greedy): the GPU accumulates in fp16. Through this bridge
(JNI from a JVM, Metal standing in for OpenCL, beam 5) Base matched the CPU
build byte for byte and Tiny differed in two capitals and a comma.

Vulkan was not taken, though it would reach Mali, Xclipse and PowerVR too:

- Size. ggml-vulkan carries every shader variant as SPIR-V; the backend
  alone was 29 MB on the Mac, against 4.6 MB for this whole OpenCL library.
- Phones. ggml-vulkan has no tuning for Mali, and its Adreno path only
  turns on for GPUs with cooperative-matrix support; the llama.cpp GPU work
  (`native/llamacpp-jni`) came to the same choice. Qualcomm writes and tests
  the OpenCL backend for Adreno itself.

Not measured on a phone: no Adreno phone was at hand, and the two phones on
wireless debugging refused the connection during this work. Rough
expectation from the hardware: an Adreno 7xx GPU (Snapdragon 8 Gen 1 and
later, about 2 to 4 TFLOPS fp16) should run Small's encoder in well under a
second, a few times faster than four ARMv8.2 cores; an Adreno 6xx (about 0.5
to 1 TFLOPS) should still beat the cores on Small and roughly match them on
Base; Tiny gains nothing worth the GPU's start-up, which is why Automatic
leaves it on the CPU. The first use compiles the kernels and can take
several seconds.

## Contract with Kotlin

- `nativeOpen(path) -> Long`: a handle, or 0 when the model will not load.
- `nativeClose(handle)`.
- `nativeTranscribe(handle, pcm, languages, translate, prompt, beamSize, threads) -> ByteArray?`:
  16 kHz mono float PCM, any length. `languages` are Whisper codes: none detects
  among all, one is forced, several are detected among those only (one extra
  encoder pass). Returns `"<code>\n<text>"` as UTF-8, or null when
  `nativeAbort` stopped it. Throws `IllegalStateException` on failure.
- `nativePartial(handle) -> ByteArray`: what is decoded so far, from any
  thread, empty between transcriptions.
- `nativeAbort(handle)`: stops the running transcription at its next step.

The OpenCL build binds the same functions to `WhisperCppGpu`, with
`nativeOpen(path, useGpu)` in place of `nativeOpen(path)`, and two more:

- `nativeGpuInit(cacheDir) -> ByteArray`: starts the OpenCL backend and lists
  the GPU devices it accepted, one description a line; empty for none.
- `nativeOnGpu(handle) -> Boolean`: whether the model went to the GPU.

Text crosses as UTF-8 bytes, never `NewStringUTF`: Whisper's byte-level tokens
can split a character, and JNI's modified UTF-8 has no room for that or for a
four-byte emoji. Decoding is one pass (no temperature fallback), with
non-speech tokens suppressed and a token budget of about 20 a second of audio,
which is what stops a decoder that has fallen into repeating itself.
