// The sticker generator's native half: one MNN module (a text encoder, a
// UNet, an image decoder or an image classifier) loaded from a .mnn file and
// run on float or int32 tensors. The diffusion loop, the scheduler and the
// tokenizer are Kotlin's (core/intelligence .../imagegen); this file only
// moves tensors in and out of MNN, so the same code serves every model.
//
// Shared by the JNI surface (wm_imagegen_jni.cpp) and the desktop check
// (desktop/imagegen_cli.cpp).
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace wmimagegen {

struct Tensor {
    std::vector<int> shape;
    std::vector<float> floats;  // used when ints is empty
    std::vector<int32_t> ints;
};

class Model {
public:
    // Where a module runs. kGpu tries MNN's OpenCL backend, then its Vulkan
    // one, and falls back to the CPU when the phone offers neither.
    enum Compute { kCpu = 0, kGpu = 1 };
    // What load picked: kCpu, or MNN's forward type for the GPU backend.
    static constexpr int kOpenCl = 3;
    static constexpr int kVulkan = 7;

    // Null when the file does not load or does not have the named tensors.
    // cache: a file MNN keeps a GPU backend's compiled kernels in, so the
    // next load skips compiling them; empty for none.
    static std::unique_ptr<Model> load(const std::string& path, const std::vector<std::string>& inputs,
                                       const std::vector<std::string>& outputs, int threads,
                                       int compute = kCpu, const std::string& cache = {});
    ~Model();

    // The backend the module runs on: kCpu, kOpenCl or kVulkan.
    int backend() const;

    // Defined in imagegen.cpp; public so its loader there can build one.
    struct Impl;

    // The first output as NCHW floats, its shape in outShape. False when MNN
    // refused the inputs (wrong count, shape or type).
    bool run(const std::vector<Tensor>& inputs, std::vector<float>& out, std::vector<int>& outShape);

private:
    explicit Model(Impl* impl);
    Impl* impl_;
};

// The tensors a .mnn file declares, as JSON:
// {"inputs":[{"name":"x","dims":[1,4,-1,-1],"type":"float"}],"outputs":["y"],"biz":"..."}
// Empty when the file is not an MNN model.
std::string describe(const std::string& path);

}  // namespace wmimagegen
