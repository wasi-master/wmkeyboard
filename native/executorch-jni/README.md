# executorch-jni: the keyboard's ExecuTorch runtime

A selective build of [ExecuTorch](https://github.com/pytorch/executorch) and a
small JNI bridge over its C++ `Module` API. Three offline dictation engines and
one glide engine run on it, each with its own Kotlin pipeline in
`core/voice/src/main/.../voice`:

| Engine | Kotlin | Model | Pipeline |
|---|---|---|---|
| Offline Asr4All (#289) | `asr4all/` | Asr4All S/M/L, English | streaming CTC windows with carried caches, then a punctuation and casing pass |
| Offline Parakeet | `parakeet/` | NVIDIA Parakeet TDT 0.6B v3, 25 European languages | preprocessor, encoder, then greedy token-and-duration transducer decoding |
| Offline Moonshine | `moonshine/` | Moonshine Tiny, English | encoder on the clip at its own length, then the decoder's cached loop |
| FUTO Swipe glide engine | `futoswipe/` | FUTO Swipe encoder and English QWERTY decoder | the stroke on a 60 Hz clock, 64 points, encoder then decoder, then `CtcGlideBeam` in `:core:prediction` over the dictionary tries |

The bridge (`EtNative` in `executorch/`) knows nothing about speech: it opens
a `.pte`, describes a method's tensors and runs a method. Models, files,
hashes and folders are one catalog (`EtModels.kt`); downloads, Storage and
the settings screens are shared by all three engines.

It is not the published `executorch-android` AAR. That one carries every
operator, the LLM, image and ASR runners, fbjni and its own `libc++_shared.so`
(9 MB on arm64, no armv7). This build carries only the
operators in `ops.txt` (the union of what the three models' programs call,
read from the programs themselves), the optimized kernels where they exist
and the portable ones otherwise, and the XNNPACK delegate; it links libc++
statically and builds armv7 too. On arm64 that is 3.8 MB. The optimized
kernels are there for Parakeet's preprocessor, whose `_fft_r2c` exists only
among them; Asr4All alone needed 2.4 MB.

Adding a model: read its programs' operators (as below), add any missing to
`ops.txt`, rebuild, and write its pipeline over `EtRunner`.

```py
from executorch.exir._serialize._program import deserialize_pte_binary
prog = deserialize_pte_binary(open("model.pte", "rb").read()).program
ops = {o.name + ("." + o.overload if o.overload else "") for p in prog.execution_plan for o in p.operators}
```

`executorch_prim::*` and `aten::sym_size.int` are built into the runtime and
stay out of `ops.txt`.

The built libraries are committed under `core/voice/src/executorch/jniLibs/`,
so the app builds without an NDK. They ride their own delivery gate,
`executorch` (`:feature:executorch` on Play, a pinned download on the GitHub
and F-Droid builds), apart from whisper.cpp's `speech` gate, so turning on the
glide engine fetches 3.8 MB and not the whole speech set.
F-Droid deletes the committed copies and builds them with this script
(through `native/build-fdroid-libs.sh`), and so does the release workflow for
the reference APK F-Droid compares against; the script maps the build
machine's paths out of the library so the two builds match. Rebuild after
you change anything here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/executorch-jni/build.sh
```

It needs CMake, Ninja and a Python 3 with PyYAML and typing_extensions on
`PATH`: selective builds generate the operator registration from `ops.txt`.
No torch install is needed. The generator runs over torchgen, and the
optimized kernels include ATen's CPU vector headers; both come from
PyTorch's own sources at the tag ExecuTorch pins (`v2.14.0`), fetched by the
script, with the few headers a torch build would generate written by it. ExecuTorch is fetched at the pinned
commit with the submodules it needs (XNNPACK, flatbuffers, flatcc, Eigen,
pocketfft, ...) unless `EXECUTORCH_SRC` points at a checkout of it, which
ExecuTorch requires to be named `executorch`. Each ABI is a full ExecuTorch
build, so the script takes a while.

## Provenance

ExecuTorch `v1.5.1`, commit `3b60683923245cf472b7323426920e15623ba361`,
unmodified, BSD-3-Clause (credited in the app's About screen).

No model is bundled. Each downloads on request from Hugging Face at a pinned
revision, every file checked against the SHA-256 in `EtCatalog`:

- Asr4All: `futo-org/asr4all-{s,m,l}`, the `executorch/xnnpack_int8` export.
  Its weights are under their own restricted-use licence, which allows
  dictation; the text is in the app's licence assets.
- Parakeet: `larryliu0820/parakeet-tdt-0.6b-v3-executorch`, the
  `xnnpack/int4` export (8-bit activations, 4-bit weights, 719 MB) of
  `nvidia/parakeet-tdt-0.6b-v3`, CC-BY-4.0.
- Moonshine: `software-mansion/react-native-executorch-moonshine-tiny`, the
  XNNPACK encoder and decoder of Useful Sensors' Moonshine Tiny, MIT.
- FUTO Swipe: `futo-org/futo-swipe`, the fp32 encoder
  (`honorable_sturgeon`) and English QWERTY decoder (`magic_macaw`), under
  the FUTO Model Weights License 1.0, which asks for a visible "powered by
  FUTO Swipe" notice. Their programs add three operators to the list:
  `aten::atan2.out`, `aten::cumsum.out` and `aten::lt.Scalar_out`. The
  optional context language model (`hungry_jellyfish`, which would add
  `aten::clamp.out`) is not used: on FUTO's test set it moved top-1 by half a
  point, and the strip's own context reranker already runs after the glide.

## Checked on the desktop

`CMakeLists.txt` also builds a macOS dylib, and through it the real Kotlin
pipelines ran on the JVM with the real models:

- Asr4All: logits matched ExecuTorch's Python runtime to 2e-6, and on 16-bit
  audio the transcript, punctuation included, matched the bundle's own
  reference script word for word, on a 4-second and a 57-second clip.
- Parakeet: English, German and French clips transcribed correctly, the
  language worked out by the model; about 0.2 s a phrase after the first,
  which packs the weights.
- Moonshine: English correct, about 0.4 s a phrase; switching between it and
  Parakeet closes one program set and opens the other cleanly.

## Contract with Kotlin

- `nativeOpen(path) -> Long`: a handle (the program memory-mapped), or 0.
- `nativeClose(handle)`.
- `nativeMethodMeta(handle, method) -> LongArray?`: input count, output count,
  then `[dtype, rank, dims...]` per tensor; null without such a method.
- `nativeExecute(handle, method, inputs, shapes) -> Array<Any?>`: one primitive
  array per input (float32 and float16 as `FloatArray`, int64 as `LongArray`,
  int32 as `IntArray`, bool and uint8 as `ByteArray`, converted to what the
  method declares), outputs back as data and shape pairs. An output that is a
  plain number (a constant method such as Parakeet's `blank_id`) comes back
  as a one-element array with an empty shape. Throws `IllegalStateException`
  when the method fails.
- One thread at a time per program; `EtNative.dispatcher` is that thread.
