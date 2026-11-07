// JNI surface for speech out: a VITS voice run straight on ONNX Runtime.
// Kotlin side: com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
// (nativeVits*), driven by core.voice.speech.MmsSpeech.
//
// Meta's MMS-TTS voices are VITS models whose text frontend is one token per
// character, so the Kotlin side turns text into ids and this file only runs
// the network; sherpa-onnx's own TTS (kokoro_jni.cpp) wants a lexicon these
// voices do not ship.
//
// Same rules as sherpa_jni.cpp: ONNX Runtime is found by soname at load time
// (the link stub only names OrtGetApiBase, which is all the C++ API calls),
// and text crosses the boundary as UTF-8 bytes.

#include <jni.h>

#include <array>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "onnxruntime_cxx_api.h"

namespace {

Ort::Env& env() {
    static Ort::Env e(ORT_LOGGING_LEVEL_ERROR, "wmsherpa-vits");
    return e;
}

struct Vits {
    std::unique_ptr<Ort::Session> session;
    std::vector<std::string> inputs;
    std::vector<std::string> outputs;
};

Vits* vits(jlong h) { return reinterpret_cast<Vits*>(h); }

}  // namespace

extern "C" {

// Opens the model at [model] with [threads] intra-op threads; 0 when it will
// not load.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVits(
    JNIEnv* jni, jclass, jstring model, jint threads) {
    const char* path = jni->GetStringUTFChars(model, nullptr);
    if (!path) return 0;
    std::string p(path);
    jni->ReleaseStringUTFChars(model, path);
    try {
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(threads > 0 ? threads : 1);
        options.SetInterOpNumThreads(1);
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        auto out = std::make_unique<Vits>();
        out->session = std::make_unique<Ort::Session>(env(), p.c_str(), options);
        Ort::AllocatorWithDefaultOptions allocator;
        for (size_t i = 0; i < out->session->GetInputCount(); ++i) {
            out->inputs.emplace_back(out->session->GetInputNameAllocated(i, allocator).get());
        }
        for (size_t i = 0; i < out->session->GetOutputCount(); ++i) {
            out->outputs.emplace_back(out->session->GetOutputNameAllocated(i, allocator).get());
        }
        return reinterpret_cast<jlong>(out.release());
    } catch (...) {
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVitsFree(JNIEnv*, jclass, jlong h) {
    delete vits(h);
}

// The model's custom metadata entry [key] (sample_rate, add_blank, ...),
// UTF-8; empty when it has none.
JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVitsMeta(
    JNIEnv* jni, jclass, jlong h, jstring key) {
    std::string value;
    const char* k = jni->GetStringUTFChars(key, nullptr);
    if (k && h) {
        try {
            Ort::AllocatorWithDefaultOptions allocator;
            auto meta = vits(h)->session->GetModelMetadata();
            auto v = meta.LookupCustomMetadataMapAllocated(k, allocator);
            if (v) value = v.get();
        } catch (...) {
        }
    }
    if (k) jni->ReleaseStringUTFChars(key, k);
    jbyteArray out = jni->NewByteArray(static_cast<jsize>(value.size()));
    if (out && !value.empty()) {
        jni->SetByteArrayRegion(out, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
    }
    return out;
}

// One sentence: token ids in, samples at the model's rate out ([-1, 1]).
// The three scales are VITS's: sampling noise, duration (1 / speed) and
// duration noise. Null when the run fails.
JNIEXPORT jfloatArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVitsRun(
    JNIEnv* jni, jclass, jlong h, jlongArray ids, jfloat noiseScale, jfloat lengthScale, jfloat noiseScaleW) {
    Vits* v = vits(h);
    const jsize n = ids ? jni->GetArrayLength(ids) : 0;
    if (!v || n <= 0) return nullptr;
    std::vector<int64_t> x(static_cast<size_t>(n));
    jni->GetLongArrayRegion(ids, 0, n, reinterpret_cast<jlong*>(x.data()));
    try {
        auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
        const std::array<int64_t, 2> xShape = {1, n};
        const std::array<int64_t, 1> one = {1};
        int64_t length = n;
        int64_t speaker = 0;
        float noise = noiseScale, len = lengthScale, noiseW = noiseScaleW;
        std::vector<Ort::Value> values;
        std::vector<const char*> names;
        // By name: exports differ in order, and in whether they take a speaker.
        for (const std::string& name : v->inputs) {
            if (name == "x" || name == "input") {
                values.push_back(Ort::Value::CreateTensor<int64_t>(memory, x.data(), x.size(), xShape.data(), 2));
            } else if (name == "x_length" || name == "x_lengths" || name == "input_lengths") {
                values.push_back(Ort::Value::CreateTensor<int64_t>(memory, &length, 1, one.data(), 1));
            } else if (name == "noise_scale") {
                values.push_back(Ort::Value::CreateTensor<float>(memory, &noise, 1, one.data(), 1));
            } else if (name == "length_scale") {
                values.push_back(Ort::Value::CreateTensor<float>(memory, &len, 1, one.data(), 1));
            } else if (name == "noise_scale_w") {
                values.push_back(Ort::Value::CreateTensor<float>(memory, &noiseW, 1, one.data(), 1));
            } else if (name == "sid") {
                values.push_back(Ort::Value::CreateTensor<int64_t>(memory, &speaker, 1, one.data(), 1));
            } else {
                return nullptr;
            }
            names.push_back(name.c_str());
        }
        const char* output = v->outputs.empty() ? "y" : v->outputs[0].c_str();
        auto result = v->session->Run(Ort::RunOptions{nullptr}, names.data(), values.data(), values.size(), &output, 1);
        const float* samples = result[0].GetTensorData<float>();
        const size_t count = result[0].GetTensorTypeAndShapeInfo().GetElementCount();
        jfloatArray out = jni->NewFloatArray(static_cast<jsize>(count));
        if (out && count > 0) jni->SetFloatArrayRegion(out, 0, static_cast<jsize>(count), samples);
        return out;
    } catch (...) {
        return nullptr;
    }
}

}  // extern "C"
