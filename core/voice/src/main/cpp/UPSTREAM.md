# Cactus Whistle native runtime provenance

`libneedle.a` and `needle.h` are the unmodified Android artifacts published by Cactus Compute in the Hugging Face `Cactus-Compute/needle3` repository, revision `f84005f8992caf37f17b0d64a4b5b31a84ce0d2a`:

- `android-arm64/libneedle.a` and `android-arm64/needle.h` → `prebuilt/arm64-v8a/`

The upstream ARMv7 archive was evaluated but is not used: its object file has an unresolved reference to libc++'s private `std::__ndk1::__hash_memory(void const*, unsigned int)` symbol, which is absent from the NDK 28 and 29 libc++ runtimes. ARMv7 builds therefore use the unavailable JNI stub and fall back to Android's system recognizer, like x86_64.
- Source: https://huggingface.co/Cactus-Compute/needle3/tree/f84005f8992caf37f17b0d64a4b5b31a84ce0d2a/

The upstream Needle source and Whistle model are Apache-2.0; the upstream license is included as `LICENSE`. The API header declares the ABI and is the authority for calls used here. Whistle model data (`whistle.cact`) is downloaded separately and is not bundled. The downloader pins revision `b358ddadd89b7a713b5aa131f23032d3cca1b251`, verifies its 16,919,407-byte size and SHA-256 `b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb`. The Whistle model repository: https://huggingface.co/Cactus-Compute/whistle. Runtime input contract: mono float PCM at 16 kHz, [-1,1], up to 30 seconds; supported explicit languages: en, de, fr, es, it, nl, pl.
