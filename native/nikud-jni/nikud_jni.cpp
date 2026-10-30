// JNI surface of libwmnikud for HebrewNikud in :core:input: Phonikud's Hebrew
// diacritizer on ONNX Runtime. The model reads a sentence's characters and says,
// for each, which point it takes and whether a shin dot goes right or left; the
// character handling is all on the Kotlin side, where it can be tested.
#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "onnxruntime_cxx_api.h"

namespace {

const char* const kTag = "WMKeyboard.Nikud";

Ort::Env& env() {
    static Ort::Env instance(ORT_LOGGING_LEVEL_WARNING, "wmnikud");
    return instance;
}

// Per token, the class with the highest score along the last axis.
void argmax(const Ort::Value& logits, size_t tokens, jint* out, size_t stride) {
    const auto shape = logits.GetTensorTypeAndShapeInfo().GetShape();
    if (shape.size() != 3 || static_cast<size_t>(shape[1]) != tokens) throw std::runtime_error("unexpected output shape");
    const auto classes = static_cast<size_t>(shape[2]);
    const float* data = logits.GetTensorData<float>();
    for (size_t i = 0; i < tokens; ++i) {
        const float* row = data + i * classes;
        out[i * 2 + stride] = static_cast<jint>(std::max_element(row, row + classes) - row);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_input_NikudNative_nativeOpen(JNIEnv* jni, jclass, jstring path, jint threads) {
    const char* chars = jni->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    const std::string model(chars);
    jni->ReleaseStringUTFChars(path, chars);
    try {
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(threads);
        options.SetInterOpNumThreads(1);
        return reinterpret_cast<jlong>(new Ort::Session(env(), model.c_str(), options));
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "open failed: %s", e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_input_NikudNative_nativeClose(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Ort::Session*>(handle);
}

// Two ints per token: its point class, then its shin class. Null when the run failed.
JNIEXPORT jintArray JNICALL
Java_com_wasimaster_wmkeyboard_core_input_NikudNative_nativeRun(JNIEnv* jni, jclass, jlong handle, jintArray ids) {
    auto* session = reinterpret_cast<Ort::Session*>(handle);
    if (!session || !ids) return nullptr;
    const jsize count = jni->GetArrayLength(ids);
    if (count <= 0) return nullptr;
    std::vector<jint> given(count);
    jni->GetIntArrayRegion(ids, 0, count, given.data());
    std::vector<int64_t> input(given.begin(), given.end());
    std::vector<int64_t> mask(count, 1);
    std::vector<int64_t> types(count, 0);
    const std::array<int64_t, 2> shape{1, count};
    try {
        const auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        std::array<Ort::Value, 3> inputs{
            Ort::Value::CreateTensor<int64_t>(memory, input.data(), input.size(), shape.data(), shape.size()),
            Ort::Value::CreateTensor<int64_t>(memory, mask.data(), mask.size(), shape.data(), shape.size()),
            Ort::Value::CreateTensor<int64_t>(memory, types.data(), types.size(), shape.data(), shape.size()),
        };
        const std::array<const char*, 3> inputNames{"input_ids", "attention_mask", "token_type_ids"};
        const std::array<const char*, 2> outputNames{"nikud_logits", "shin_logits"};
        auto outputs = session->Run(Ort::RunOptions{nullptr}, inputNames.data(), inputs.data(), inputs.size(),
                                    outputNames.data(), outputNames.size());
        std::vector<jint> packed(static_cast<size_t>(count) * 2);
        argmax(outputs[0], count, packed.data(), 0);
        argmax(outputs[1], count, packed.data(), 1);
        jintArray result = jni->NewIntArray(static_cast<jsize>(packed.size()));
        if (result) jni->SetIntArrayRegion(result, 0, static_cast<jsize>(packed.size()), packed.data());
        return result;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "run failed: %s", e.what());
        return nullptr;
    }
}

}  // extern "C"
