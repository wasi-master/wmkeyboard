# imagegen-jni: the sticker generator's MNN runtime

`libwmimagegen.so` is [MNN](https://github.com/alibaba/MNN) 3.6.1 (Apache-2.0),
built static with its CPU backend, and on arm64 its OpenCL and Vulkan
backends too, under a small JNI layer:

- `imagegen.cpp` / `imagegen.hpp` load one `.mnn` module per handle (a text
  encoder, a UNet, an image decoder or the safety classifier) and run it on
  float or int32 tensors, returning the first output as NCHW floats. Compute is
  always float32 (`Precision_Normal` on the CPU, `Precision_High` on the GPU
  backends): MNN's float16 path gives a wrong UNet output, and a one-step
  model multiplies any error in its noise prediction. Asked for the GPU
  (`nativeLoadOn`), it tries OpenCL, then Vulkan, then the CPU, and keeps the
  GPU's compiled kernels in a cache file for the next load.
- `wm_imagegen_jni.cpp` is the surface `ImageGenNative` in
  `core/intelligence/.../imagegen/` binds to.

The JNI surface knows nothing of pictures, so the scanner's PaddleOCR reader
uses it too in builds without LiteRT (`MnnPaddleRuntime` in
`core/intelligence/.../ocr/`): its detector and recogniser are `.mnn` modules
with an `image` input and a `probs` output, run the same way. So does the
upscaler (`core/intelligence/.../upscale/`): waifu2x's UpConv7 networks, an
`image` input and an `upscaled` output, run a 142-pixel tile at a time.

Everything else (the CLIP tokenizer, the schedulers, classifier-free guidance,
the loop) is Kotlin, so a cancel lands between two network calls and the CPU and
NPU paths share one loop.

## Building

```sh
ANDROID_NDK=/path/to/ndk native/imagegen-jni/build.sh
```

Fetches MNN at the pinned commit (or uses `MNN_SRC`), builds `arm64-v8a`
and `armeabi-v7a` one after another into
`core/intelligence/src/imagegen/jniLibs/`. The MNN options leave out everything
that downloads at configure time (KleidiAI), the float16 kernels, transformer
fusion, the tools and the converter. `MNN_INTERNAL` (model authentication and
metrics logging) stays off.

## Checking without a phone

```sh
PYTHON=/path/to/python-with-MNN native/imagegen-jni/desktop/check.sh <bundle dir> [file prefix]
```

builds the same `imagegen.cpp` for the computer, runs a bundle's three networks
through it and compares each output with MNN's own Python bindings on the same
inputs. On the SDXS bundle every output matches exactly.
