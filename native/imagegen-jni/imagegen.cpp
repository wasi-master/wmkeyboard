#include "imagegen.hpp"

#include <MNN/expr/Executor.hpp>
#include <MNN/expr/ExprCreator.hpp>
#include <MNN/expr/Module.hpp>

#include <cstring>
#include <sstream>

namespace wmimagegen {

using MNN::BackendConfig;
using MNN::ScheduleConfig;
using MNN::Express::Executor;
using MNN::Express::Module;
using MNN::Express::VARP;

struct Model::Impl {
    std::shared_ptr<Executor::RuntimeManager> runtime;
    std::shared_ptr<Module> module;
    std::vector<std::string> inputs;
    int backend;
    std::string cache;
    // Whether the GPU kernels compiled for this module were written to the cache.
    bool cached = false;
};

Model::Model(Impl* impl) : impl_(impl) {}

Model::~Model() { delete impl_; }

namespace {

std::unique_ptr<Model::Impl> loadOn(MNNForwardType type, const std::string& path,
                                    const std::vector<std::string>& inputs,
                                    const std::vector<std::string>& outputs, int threads,
                                    const std::string& cache) {
    ScheduleConfig schedule;
    schedule.type = type;
    schedule.backupType = MNN_FORWARD_CPU;
    BackendConfig backend;
    // Float32 compute always. MNN's float16 path gives a wrong UNet output
    // (measured: cosine 0.41-0.59 against the reference at most timesteps),
    // and a one-step model magnifies any error in its noise prediction. On
    // the CPU Normal is float32 already; the GPU backends need High for it.
    backend.precision = type == MNN_FORWARD_CPU ? BackendConfig::Precision_Normal : BackendConfig::Precision_High;
    backend.power = BackendConfig::Power_High;
    backend.memory = BackendConfig::Memory_Low;
    schedule.backendConfig = &backend;
    if (type == MNN_FORWARD_CPU) {
        schedule.numThread = threads > 0 ? threads : 4;
    } else {
        // For the GPU backends numThread is a mode: buffer memory, kernels
        // tuned once and kept in the cache (MNN_GPU_MEMORY_BUFFER |
        // MNN_GPU_TUNING_FAST).
        schedule.mode = MNN_GPU_MEMORY_BUFFER | MNN_GPU_TUNING_FAST;
    }
    std::shared_ptr<Executor::RuntimeManager> runtime(Executor::RuntimeManager::createRuntimeManager(schedule));
    if (!runtime) return nullptr;
    if (type != MNN_FORWARD_CPU && !cache.empty()) runtime->setCache(cache);
    Module::Config config;
    config.shapeMutable = true;
    std::shared_ptr<Module> module(Module::load(inputs, outputs, path.c_str(), runtime, &config));
    if (!module) return nullptr;
    return std::unique_ptr<Model::Impl>(new Model::Impl{runtime, module, inputs, static_cast<int>(type), cache});
}

}  // namespace

std::unique_ptr<Model> Model::load(const std::string& path, const std::vector<std::string>& inputs,
                                   const std::vector<std::string>& outputs, int threads, int compute,
                                   const std::string& cache) {
    std::unique_ptr<Impl> impl;
    if (compute == kGpu) {
        // isBackendSupport loads the backend's system library to answer, so a
        // phone without OpenCL (or Vulkan) says no here rather than later.
        ScheduleConfig probe;
        probe.type = MNN_FORWARD_CPU;
        std::shared_ptr<Executor::RuntimeManager> cpu(Executor::RuntimeManager::createRuntimeManager(probe));
        std::vector<bool> support = cpu ? cpu->isBackendSupport({MNN_FORWARD_OPENCL, MNN_FORWARD_VULKAN})
                                        : std::vector<bool>{false, false};
        if (support[0]) impl = loadOn(MNN_FORWARD_OPENCL, path, inputs, outputs, threads, cache);
        if (!impl && support[1]) impl = loadOn(MNN_FORWARD_VULKAN, path, inputs, outputs, threads, cache);
    }
    if (!impl) impl = loadOn(MNN_FORWARD_CPU, path, inputs, outputs, threads, {});
    if (!impl) return nullptr;
    return std::unique_ptr<Model>(new Model(impl.release()));
}

int Model::backend() const { return impl_->backend; }

bool Model::run(const std::vector<Tensor>& inputs, std::vector<float>& out, std::vector<int>& outShape) {
    using namespace MNN::Express;
    if (inputs.size() != impl_->inputs.size()) return false;
    std::vector<VARP> vars;
    vars.reserve(inputs.size());
    for (const auto& t : inputs) {
        size_t count = 1;
        for (int d : t.shape) count *= static_cast<size_t>(d);
        VARP v;
        if (!t.ints.empty()) {
            if (t.ints.size() != count) return false;
            v = _Input(t.shape, NCHW, halide_type_of<int32_t>());
            auto* p = v->writeMap<int32_t>();
            if (!p) return false;
            std::memcpy(p, t.ints.data(), count * sizeof(int32_t));
        } else {
            if (t.floats.size() != count) return false;
            v = _Input(t.shape, NCHW, halide_type_of<float>());
            auto* p = v->writeMap<float>();
            if (!p) return false;
            std::memcpy(p, t.floats.data(), count * sizeof(float));
        }
        vars.push_back(v);
    }
    auto outs = impl_->module->onForward(vars);
    if (outs.empty() || outs[0].get() == nullptr) return false;
    VARP o = outs[0];
    auto* info = o->getInfo();
    if (!info) return false;
    if (info->order == NC4HW4) {
        o = _Convert(o, NCHW);
        info = o->getInfo();
        if (!info) return false;
    }
    const float* p = o->readMap<float>();
    if (!p) return false;
    out.assign(p, p + info->size);
    outShape.assign(info->dim.begin(), info->dim.end());
    // A GPU backend compiles and tunes its kernels on the first run; written
    // out once, so the next load of this model starts from them.
    if (impl_->backend != MNN_FORWARD_CPU && !impl_->cache.empty() && !impl_->cached) {
        impl_->runtime->updateCache();
        impl_->cached = true;
    }
    return true;
}

namespace {

std::string quote(const std::string& s) {
    std::string r = "\"";
    for (char c : s) {
        if (c == '"' || c == '\\') r += '\\';
        if (static_cast<unsigned char>(c) < 0x20) continue;
        r += c;
    }
    return r + "\"";
}

}  // namespace

std::string describe(const std::string& path) {
    std::shared_ptr<Module> module(Module::load({}, {}, path.c_str()));
    if (!module) return "";
    const Module::Info* info = module->getInfo();
    if (!info) return "";
    std::ostringstream json;
    json << "{\"inputs\":[";
    for (size_t i = 0; i < info->inputNames.size(); ++i) {
        if (i) json << ",";
        json << "{\"name\":" << quote(info->inputNames[i]) << ",\"dims\":[";
        if (i < info->inputs.size()) {
            const auto& in = info->inputs[i];
            for (size_t d = 0; d < in.dim.size(); ++d) json << (d ? "," : "") << in.dim[d];
            json << "],\"type\":\"" << (in.type.code == halide_type_float ? "float" : "int") << "\"}";
        } else {
            json << "],\"type\":\"float\"}";
        }
    }
    json << "],\"outputs\":[";
    for (size_t i = 0; i < info->outputNames.size(); ++i) json << (i ? "," : "") << quote(info->outputNames[i]);
    json << "],\"biz\":" << quote(info->bizCode) << "}";
    return json.str();
}

}  // namespace wmimagegen
