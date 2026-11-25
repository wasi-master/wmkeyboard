# imagegen-qnn: the sticker generator's NPU runner

`libwmimagegen_qnn.so` runs Qualcomm AI Hub's Stable Diffusion 1.5 context
binaries (w8a16, one zip per Snapdragon) on the Hexagon NPU. `QnnImageNative`
in `core/intelligence/.../imagegen/` is its Kotlin side, and `QnnGraph` beside it
runs any one-graph binary, which is how the upscaler's NPU builds
(`tools/qnn-context`) run on it too.

It links nothing of Qualcomm's. `nativeInit` opens `libQnnHtp.so` and
`libQnnSystem.so` by path from the directory `NpuRuntime` unpacked them into
(the same runtime Compute = NPU uses), sets `ADSP_LIBRARY_PATH` so the DSP finds
the skel there, and takes their interface tables. Each context binary's first
graph is read with `QnnSystemContext_getBinaryInfo` (names, shapes, types,
scale and offset of every input and output), loaded with
`contextCreateFromBinary` and run with `graphExecute` on raw bytes; Kotlin
quantises and dequantises. While a model is loaded the HTP holds a burst
power vote, released in `nativeShutdown`.

## Building

```sh
ANDROID_NDK=/path/to/ndk native/imagegen-qnn/build.sh
```

arm64 only, into `core/intelligence/src/imagegennpu/jniLibs/`. The QNN API
headers carry Qualcomm's "Confidential and Proprietary" notice, so they are not
in this repository: the script fetches them from Qualcomm's own
[geniex-qairt-plugin](https://github.com/qualcomm/geniex-qairt-plugin)
repository at a pinned commit (QNN SDK build v2.50.40, API 2.39, the line AI
Hub compiled the binaries with), or uses `QNN_INCLUDE` pointed at a QAIRT SDK's
`include/QNN`. F-Droid's build has no NPU and never builds this library.
