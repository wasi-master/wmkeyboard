# indicxlit-jni: IndicXlit on ONNX Runtime

[IndicXlit](https://github.com/AI4Bharat/IndicXlit) (AI4Bharat, MIT) reads a
word typed in Latin letters as Bengali, Hindi, Urdu and 18 more Indic
languages. The phonetic layouts offer its readings in the strip, after the
word the space bar writes (Neural suggestions, on each language's page). Its
mirror image, the same network trained Indic to English, spells the words of
a romanized dictation (Hinglish, Banglish...) that the spelling maps do not
know. Kotlin entry points: `XlitNative` and `IndicXlit` in
`core/intelligence/src/main/java/com/wasimaster/wmkeyboard/core/xlit/`, with
`IndicXlitModels.NATIVE` or `IndicXlitModels.LATIN`.

The two directions share this library. Their graphs have the same shapes
(6 layers, 4 heads of 64) and only the vocabularies differ: which ids go in
is the Kotlin side's business (`IndicXlitVocab`), and the search reads the
output vocabulary's size off `logp`.

`libwmxlit.so` is the model's beam search over its two ONNX graphs
(`xlit.cpp`) and a JNI layer (`xlit_jni.cpp`). It links ONNX Runtime the way
`libwmsherpa` does (see `../sherpa-onnx-jni/README.md`): at build time
against `ort_stub.c` (one unversioned `OrtGetApiBase`, soname
`libonnxruntime.so`) and the runtime's headers at a pinned tag; at run time
`XlitNative` loads Microsoft's `libonnxruntime.so` first, and the dependency
binds to it. No ONNX Runtime binary is in the repository or the build.

The libraries are committed under `core/voice/src/onnx/jniLibs/` beside
`libwmsherpa.so`, so they ride the same `onnx` delivery gate
(`:feature:onnx` on Play, pinned for native delivery elsewhere). Rebuild after
you change anything here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/indicxlit-jni/build.sh
```

Plain clang from the NDK, two source files, a few seconds per ABI. The output
is the same bytes on every run with the same NDK.

## The model

Not bundled. `IndicXlitModels` pins the three files of each direction the
data repository's `scripts/export_indicxlit.py` writes (encoder, decoder
step, vocabulary: 13.1 MB for English to Indic, 13.0 MB for
`--direction indic-en`) by size and SHA-256; they are assets of its
`models` release.
The export reads the fairseq checkpoint into plain PyTorch modules, writes the
encoder with every decoder layer's cross-attention keys and values as its
outputs, and the decoder as one step with its own self-attention cache, then
quantises both to int8.

## Contract with Kotlin

Handles are `Long`s, 0 on failure.

- `nativeOpen(encoder, decoder, threads)`: both sessions, graph optimisation
  on, `threads` intra-op threads (the keyboard uses one).
- `nativeTransliterate(handle, src, allowed, beam, maxLen)`: `src` is the
  language token, the letters' ids and `</s>`; `allowed` is a flag per target
  id (the keyboard allows `</s>` and the target script's characters). Returns
  up to `beam` readings best first, packed as count, float bits of the score,
  ids; null when the run failed.
- `nativeClose(handle)`.

The search is fairseq's: `2 * beam` expansions a step, a reading that writes
`</s>` is finished with its log-probability divided by its length counting
the `</s>`, and the search stops once `beam` readings have finished.

## Checking it on the build machine

`host_test.cpp` drives `xlit.cpp` against a desktop ONNX Runtime (the one in
the `onnxruntime` Python wheel will do):

```sh
c++ -std=c++17 -O2 -I<onnxruntime>/include/onnxruntime/core/session \
    -o host_test xlit.cpp host_test.cpp -L<dir with libonnxruntime> -lonnxruntime
./host_test indicxlit-encoder.int8.onnx indicxlit-decoder.int8.onnx allowed.txt 4 < ids.txt
```

Its readings for 300 Bengali and 300 Hindi test words were identical, in the
same order, to the reference search in Python over the same graphs; for the
Indic-to-English graphs, so were those for 300 words each of Bengali, Hindi,
Tamil and Urdu (25 ms a word, beam 4, one thread, on a busy M-series Mac).

## ABIs

| ABI | Size |
|---|---|
| arm64-v8a | about 250 KB |
| armeabi-v7a | about 160 KB |

Both are 16 KB aligned (`-z max-page-size=16384`).
