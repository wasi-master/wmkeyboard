# llamacpp-jni: GGUF models on llama.cpp

[llama.cpp](https://github.com/ggml-org/llama.cpp) is the second on-device AI
runtime, beside LiteRT-LM. LiteRT-LM runs only `.litertlm` and `.task`
bundles; llama.cpp runs any GGUF, so a user can download one of the curated
GGUF rows or import whatever model they like. `LocalLlmEngine` routes a GGUF
here and everything else to LiteRT-LM, which stays the default. Kotlin entry
point: `com.wasimaster.wmkeyboard.core.localllm.LlamaNative`, wrapped by
`LlamaModel`/`LlamaSession`/`LlamaVision` (plain Kotlin) and
`LlamaCppRuntime` (the `LlmRuntime` implementation, Android) in the same
package. Every build carries libmtmd, llama.cpp's multimodal library, so a
vision model reads pictures through its projector (`mmproj` GGUF), and on
arm64 a third build adds the OpenCL backend for Adreno GPUs.

The built libraries are committed under `core/intelligence/src/llama/jniLibs/`,
a directory of their own so the Play split carries nothing else. GitHub builds
leave them out of the APK and pin them (gate `llama`), Play builds ship them in
the on-demand `:feature:llama` split, and F-Droid deletes the committed copies
and builds them with this script through `native/build-fdroid-libs.sh`. The
script maps the build machine's paths out of the library and fixes llama.cpp's
build number and commit, so two builds with the same NDK come out the same.
Rebuild after you change anything here:

```sh
ANDROID_NDK=/path/to/ndk-r29 native/llamacpp-jni/build.sh
```

It needs CMake and Ninja on `PATH`, and fetches llama.cpp at the release
`pin.sh` names unless `LLAMA_SRC` points at a checkout of it. The committed
libraries were built with NDK r29 (`29.0.14206865`), the one F-Droid's recipe
names. Five configurations, a few minutes each.
The OpenCL one also clones Khronos's OpenCL-Headers and OpenCL-ICD-Loader at
the tags `pin.sh` names (or uses `OPENCL_HEADERS_SRC`/`OPENCL_LOADER_SRC`),
and needs Python 3 for llama.cpp's kernel-embedding script.

## Provenance

llama.cpp release `b11951`, commit `a71c00f0bb2d60a4a52837d80baa534b651ebd91`,
unmodified: `libllama`, ggml's CPU backend, `common/` (for the chat templates
and their Jinja interpreter) and `tools/mtmd` (libmtmd, the projectors of
vision models) built as static libraries and linked into one shared library
with `llamacpp_jni.cpp` and libc++ static; the OpenCL build adds ggml's
OpenCL backend with its kernels embedded as source. llama.cpp and ggml are
MIT (`LICENSE` is upstream's); the chat code includes nlohmann/json (MIT).
Both are in `app/src/main/assets/licenses/mit-llama-cpp.txt`. libmtmd's
vendored decoders (stb_image, miniaudio) and hashes (xxHash, SHA) are not in
the library: pictures arrive as decoded RGB from Kotlin, and the linker drops
the code nothing calls (checked with `strings` on the arm64 build). The OpenCL headers and ICD
loader (Apache-2.0) are only compiled and linked against, never shipped. No
HTTP, OpenSSL, OpenMP, KleidiAI, Vulkan or Metal backend is built for a phone.

## One ggml or two

libwmllama carries its own ggml rather than sharing one `libggml.so` with
libwmwhispercpp. The two upstreams sync ggml at different times (whisper.cpp
v1.9.5 and llama.cpp b11951 do not hold the same revision), so a shared
library would pin both projects to whichever revision they last agreed on and
make every upgrade of one wait for the other. They also come through
different gates (`speech` and `llama`), so a shared library would need a
third gate both depend on. The cost is ggml's CPU code twice, roughly a
megabyte, on a phone that has both. With `-fvisibility=hidden` and a version
script exporting only the JNI entry points, the two copies never see each
other's symbols.

## ABIs

| ABI | Library | Instruction set |
|---|---|---|
| arm64-v8a | `libwmllama.so` | ARMv8.0, every 64-bit phone |
| arm64-v8a | `libwmllama_v82.so` | ARMv8.2 with dot product and fp16 |
| armeabi-v7a | `libwmllama.so` | ARMv7 with NEON; small models only (32-bit address space) |
| arm64-v8a | `libwmllama_opencl.so` | ARMv8.2 as above, plus the OpenCL backend for Adreno; in `core/intelligence/src/llamagpu/jniLibs`, gate `llamagpu` |

`LlamaNative` loads the ARMv8.2 build only when every core's `Features` line
in `/proc/cpuinfo` lists `asimddp`, `asimdhp` and `fphp`, the same rule as
whisper.cpp's. Quantized matrix maths runs several times faster with them.

The OpenCL build is a gate of its own (`llamagpu`, the `:feature:llamagpu`
split on Play), so only an Adreno phone with Compute on GPU downloads its
10 MB; `LlamaCpp.prepareGpu` asks for it when GPU is picked with a GGUF model
on the phone, and the first GPU answer does too. The three arm64 builds bind
the same JNI names, so a process keeps whichever it loaded first. `LlamaCppRuntime` asks for the OpenCL build
(`LlamaNative.wantGpuLibrary`) when Compute is GPU (or NPU) on an Adreno
phone (`/dev/kgsl-3d0`, or `Build.SOC_MANUFACTURER` QTI) with ARMv8.2 cores.
It links to `libOpenCL.so` by name, which the app declares as an optional
native library; a phone whose namespace has none fails the load and gets the
ARMv8.2 build. With Compute on CPU in a process that holds the OpenCL build,
models open with an empty device list (`gpu_layers` 0), so the GPU backend
never starts. A process that loaded a CPU build first cannot switch: the
keyboard recycles itself once out of sight (`LlamaCpp.waitsForGpuRestart`).

## Which GPU backend

llama.cpp has two that reach Android phones. The OpenCL backend is
Qualcomm's: matrix kernels written for Adreno, verified on Adreno 750 and
830, tuned first for Q4_0 and since for the K-quants. It refuses every GPU
but Adreno and Intel (`ggml_opencl_is_device_supported`), and calls `exit(1)`
in kernel set-up on an unknown family, so it must never load elsewhere. The
Vulkan backend runs on any Vulkan 1.2 GPU, Mali included, but every public
report on Mali has it slower than the CPU or hanging (llama.cpp discussion
9464: 24 s through Vulkan against 1.6 s on the CPU for the same prompt; a
Radxa Orion O6 with a Mali-G720 "far below CPU"), its precompiled SPIR-V
shaders would add tens of megabytes, and no phone here could check it. So
only OpenCL is built, and Mali, PowerVR and Xclipse phones run GGUF models on
the CPU.

GPU verdicts mirror LiteRT-LM's (`AcceleratorVerdicts`, files
`llama-gpu-failed`/`llama-gpu-attempt` in the no-backup dir): a model that
fails to load or answer on the GPU is marked and reloads on the CPU; a native
crash leaves the attempt file and counts as a failure in the next process;
`llama-gpu-library-*` does the same for starting the OpenCL backend itself
(`nativeGpuDevices`), so a driver that dies there costs one crash, not one
per process. Verdicts reset after a system or app update.

## Contract with Kotlin

Handles are native pointers as `Long`; 0 means failure.

- `nativeGpuDevices() -> ByteArray`: `name\tdescription` per GPU device the
  loaded build found; empty for a CPU build. Starts the GPU backend.
- `nativeModelOpen(path, useMmap, gpuLayers)`, `nativeModelClose(model)`.
  Opening reads the GGUF's chat template too (ChatML when it has none).
  `gpuLayers` 0 passes an empty device list, so neither the model nor its
  contexts touch a GPU (no KV offload, no op offload); more offloads that
  many layers (`ALL_LAYERS` = 999).
- `nativeModelInfo(model) -> LongArray`: params, bytes, trained context,
  layers, heads, KV heads, embedding width, sliding window, has-template,
  vocabulary. `LlamaModelInfo` names them and estimates the KV cache per token.
- `nativeSessionOpen(model, contextTokens, threads, batch)`,
  `nativeSessionClose(session)`: one `llama_context` plus a sampler chain.
- `nativeSetSampler(session, temperature, topK, topP, minP, seed)`:
  temperature 0 is greedy.
- `nativePromptChat(session, roles, contents, enableThinking) -> Int`: renders
  the messages with the model's template (llama.cpp's Jinja interpreter,
  falling back to its built-in formats) and loads them, ending on the
  assistant's turn. Template text is tokenized with special tokens; message
  text without, so a message can never open a turn of its own. The tokens the
  cache already holds as a prefix are kept, and only the rest is decoded.
  Returns the prompt's length, or -1 too long, -2 aborted, -3 decode failed,
  -4 template failed.
- `nativePromptRaw(session, text, addSpecial)`: the same for raw text.
- `nativeVisionOpen(model, mmprojPath, useGpu, threads)`,
  `nativeVisionClose(vision)`: a projector over a loaded model, or 0 when it
  will not load (a projector for another model, an audio-only one).
- `nativePromptChatImages(session, vision, roles, contents, rgb, widths,
  heights, enableThinking)`: `nativePromptChat` with pictures (RGB bytes,
  three per pixel) at the start of the last user message. The template is
  rendered with libmtmd's marker there (the marker is broken up wherever it
  appears in a message), the rendered parts go to
  `mtmd_tokenize_from_parts` keeping each part's special-token rule, and the
  picture chunks are encoded and decoded by
  `mtmd_helper_eval_chunk_single`. Text is decoded at explicit positions
  (`Session::n_past`), because a Qwen-VL picture takes fewer positions than
  embeddings (M-RoPE). The session records a picture's cache cells as
  `IMAGE_TOKEN` (-2), and prefix reuse stops at the first picture of the
  cache or the prompt. -5 when the projector failed.
- `nativeNext(session) -> ByteArray?`: samples and decodes one token. UTF-8 of
  whole characters only (empty while one is incomplete); null once the answer
  is over, for the reason `nativeStopReason` gives: 0 end of turn, 1 window
  full, 2 aborted, 3 error.
- `nativeAbort(session)`: any thread. Stops a prompt load at its next batch
  (llama.cpp's abort callback) and an answer at its next token.
- `nativeScore(session, tokens) -> FloatArray?`: each token's log-probability
  given the ones before it, in nats; element 0 is NaN. Batches of 32 with
  every position's logits kept. Replaces the cache's contents.
- `nativeTokenize`, `nativeTokenPiece`, `nativeContextSize`,
  `nativeTokensUsed`, `nativeLastReused`, `nativeSystemInfo`.

Text crosses as UTF-8 bytes, never `NewStringUTF`: a token can end inside a
character, and JNI's modified UTF-8 has no room for four-byte characters.
Logging goes to logcat (tag `WMLlama`), warnings and errors only.

## Checking it on a desktop

`host-test.sh` builds the same bridge for macOS or Linux (CPU only, like the
phones) and prints the library's path. The Kotlin above `LlamaNative` that has
no Android in it (`LlamaNative.kt`, `LlamaSession.kt`, `GgufFile.kt`) compiles
with Android Studio's bundled `kotlinc` beside two small stubs (a
`FeatureModules` whose `loadLibrary` calls `System.load`, and `ModelFormat`),
and runs on its JBR with `-Dwm.libdir=<dir of libwmllama.dylib>`.

Measured that way on 2026-10-20 on an Apple M2 (4 performance cores), 4
threads, greedy, a 390-token prompt, the ARMv8.2 build of the bridge. The Mac
was busy with other builds (load average 30 to 55), so these are the best of
three runs, and noisy:

| Model | Prompt (prefill) | Answer (generation) |
|---|---|---|
| Qwen 2.5 0.5B Instruct Q4_K_M | 173 tokens/s | 89 tokens/s |
| Gemma 3 270M IT Q8_0 | 1,508 tokens/s | 158 tokens/s |

The same runs checked: the template renders (ChatML for Qwen, Gemma's own
for Gemma, sliding-window cache included); the second turn of a chat reused
59 of 82 prompt tokens and decoded only the rest; an emoji survived the
UTF-8 streaming; `<|im_start|>system` inside a user message stayed 17 text
tokens; an abort from another thread ended the answer within one token; and
scoring put "tomorow" 11 nats below "tomorrow" under Qwen.

Library sizes (stripped), b11951 with NDK r29: arm64 7.3 MB each, the
OpenCL build 10.5 MB, armeabi-v7a 5.1 MB. With libmtmd on b11753 and NDK r27
(2026-10-21) they were 7.15, 10.1 and 4.9 MB. libmtmd
added about 0.8 MB to each; the OpenCL backend and its kernels (embedded as
OpenCL C source, compiled by the phone's driver on first use) about 3 MB.
Before that: arm64 6.4 MB each, armeabi-v7a 4.4 MB. About 2.6 MB of that is llama.cpp's code for the model architectures
it knows, and roughly 1.5 MB the chat templates' Jinja interpreter and
template analysis, built at `-Os`. llama itself at `-Os` would save another
12% but looked slower on a small model in these noisy runs, so it stays at
`-O3`.

### GPU and pictures, 2026-10-21

The same harness with the bridge built twice for the Mac: CPU only, and with
`WM_GPU=metal` (Metal, which no phone has, standing in for "a GPU backend"),
4 threads, greedy, a 510-token prompt and a 128-token answer, four runs each,
best and worst shown. A first round under a load average of 90 to 130 from
other sessions starved the CPU (24 and 16 tokens/s for Qwen); the round below
ran at a load of 10 to 22, still above the four performance cores, so the CPU
column is low against the quiet runs above (173 and 89 for Qwen).

| Model | CPU prompt | CPU answer | Metal prompt | Metal answer |
|---|---|---|---|---|
| Qwen 2.5 0.5B Q4_K_M | 67 to 79 tokens/s | 33 to 41 tokens/s | 1,916 to 3,470 tokens/s | 55 to 100 tokens/s |
| Gemma 3 1B Q4_K_M | 23 to 36 tokens/s | 7 to 23 tokens/s | 1,221 to 1,857 tokens/s | 27 to 44 tokens/s |

What carries over to a phone is the shape, not the numbers. Reading the
prompt is one large matrix product per layer, which a GPU does many times
faster; the answer is one token at a time, bound by memory bandwidth and, for
a model this small, by the cost of launching each step on the GPU, so it
gains little or nothing. Metal's kernels, Apple's unified memory and its
driver are not Adreno's, so no ratio here is a phone's.

Estimates for phones, not measured (no phone was available), from the one
public Adreno 830 run with llama.cpp's OpenCL backend (Snapdragon 8 Elite,
Qwen 2.5 Coder 1.5B Q8_0: 579 tokens/s reading a prompt and 21 to 31
writing, ngl 99; llama.cpp discussion 23736) scaled by model size. Take them
as plus or minus a factor of two:

| Model | Snapdragon 8 Elite, GPU | 8 Elite, CPU (v82 build) | Snapdragon 8 Gen 2 or 3, GPU |
|---|---|---|---|
| Qwen 2.5 0.5B Q4_K_M | prompt 1,000 to 1,500, answer 30 to 45 | prompt 150 to 300, answer 40 to 60 | prompt 600 to 1,000, answer 25 to 35 |
| Gemma 3 1B Q4_K_M | prompt 600 to 900, answer 25 to 35 | prompt 80 to 150, answer 25 to 35 | prompt 400 to 600, answer 20 to 30 |

So on Adreno the GPU is worth it for long prompts (a screenshot's few
hundred picture tokens, a page of text to rewrite): seconds become a
fraction of a second. For a short prompt and a long answer it is about even
with the CPU, and for the 0.5B model the CPU can be faster. Q4_0 files would
be faster on the GPU still (Qualcomm's first target); the catalog keeps
Q4_K_M. Mali phones (the CPH2481's G57 among them) run on the CPU as before.

Pictures, checked with SmolVLM 500M Q8_0 and its Q8_0 projector on a
1080 x 2160 chat screenshot (scaled to 512 x 1024, 236 positions with the
question): on the CPU and on Metal alike (greedy, so the same words) it
named the contact and read "Can you bring the book I lent you last week?"
off the last bubble, though its first summary of the chat was muddled.
A Qwen2-VL run, the M-RoPE case the explicit positions are for, was set up
but lost to a reboot of the Mac before it finished: positions after a
picture in a Qwen-VL model are checked by reading the code, not by a run. A
follow-up turn without the picture kept the 4 tokens before it and decoded
the rest; the picture sent again after that turn kept the 51 tokens before
its position. Prompt with the picture: 5.1 s on Metal, 64 s on the starved
CPU. That third prompt carried a message with `<__media__>` typed in it,
which stayed text: unescaped, it would have been a second marker for one
picture, and the prompt would have failed.

Nothing here has run on a phone yet.
