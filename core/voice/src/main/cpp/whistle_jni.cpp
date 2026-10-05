#include <jni.h>
#include <algorithm>
#include <cstdio>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#ifndef CACTUS_UNSUPPORTED_ABI
#include "needle.h"
#endif

namespace {
std::mutex api_mutex;
#ifndef CACTUS_UNSUPPORTED_ABI
// needle_load may retain pointers into the archive for mmap-like zero-copy
// access. Keep the backing bytes alive for the process lifetime.
std::vector<unsigned char> model_bytes;
#endif
constexpr int kMaxSamples = 16000 * 30;
constexpr int kOutputCapacity = 256 * 1024;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whistle_WhistleEngine_nativeAvailable(JNIEnv*, jclass) {
#ifdef CACTUS_UNSUPPORTED_ABI
    return JNI_FALSE;
#else
    return JNI_TRUE;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whistle_WhistleEngine_nativeLoad(JNIEnv* env, jclass, jbyteArray bytes) {
#ifdef CACTUS_UNSUPPORTED_ABI
    return env->NewStringUTF("Cactus Whistle is unavailable for this ABI");
#else
    if (!bytes || env->GetArrayLength(bytes) == 0) return env->NewStringUTF("Model file is empty");
    const jsize size = env->GetArrayLength(bytes);
    std::vector<unsigned char> model(static_cast<size_t>(size));
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(model.data()));
    if (env->ExceptionCheck()) return nullptr;
    std::lock_guard<std::mutex> lock(api_mutex);
    model_bytes = std::move(model);
    const int result = needle_load(model_bytes.data(), static_cast<unsigned long long>(model_bytes.size()));
    if (result < 0) {
        const char* error = needle_last_error();
        return env->NewStringUTF(error ? error : "needle_load failed");
    }
    return nullptr;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whistle_WhistleEngine_nativeTranscribe(JNIEnv* env, jclass, jfloatArray audio, jstring language, jstring keywords) {
#ifdef CACTUS_UNSUPPORTED_ABI
    return env->NewStringUTF("Cactus Whistle is unavailable for this ABI");
#else
    if (!audio) return env->NewStringUTF("PCM is null");
    const jsize count = env->GetArrayLength(audio);
    if (count <= 0 || count > kMaxSamples) return env->NewStringUTF("PCM must contain 1..480000 samples");
    std::vector<float> pcm(static_cast<size_t>(count));
    env->GetFloatArrayRegion(audio, 0, count, pcm.data());
    if (env->ExceptionCheck()) return nullptr;
    const char* lang = language ? env->GetStringUTFChars(language, nullptr) : nullptr;
    const char* bias = keywords ? env->GetStringUTFChars(keywords, nullptr) : nullptr;
    std::vector<char> output(kOutputCapacity, 0);
    std::lock_guard<std::mutex> lock(api_mutex);
    const int result = needle_transcribe(pcm.data(), count, lang, bias, 0, output.data(), static_cast<int>(output.size()));
    if (bias) env->ReleaseStringUTFChars(keywords, bias);
    if (lang) env->ReleaseStringUTFChars(language, lang);
    if (result < 0) {
        const char* error = needle_last_error();
        return env->NewStringUTF(error ? error : "needle_transcribe failed");
    }
    return env->NewStringUTF(output.data());
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_whistle_WhistleEngine_nativeReset(JNIEnv*, jclass) {
#ifndef CACTUS_UNSUPPORTED_ABI
    std::lock_guard<std::mutex> lock(api_mutex);
    needle_reset();
#endif
}
