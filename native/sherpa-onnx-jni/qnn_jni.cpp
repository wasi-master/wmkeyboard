// libwmsherpa_qnn: sherpa-onnx's offline recognizer on the Snapdragon NPU,
// through Qualcomm's QNN HTP backend. Kotlin side:
// com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaQnnNative.
//
// Two model families, both k2-fsa's ready-made QNN context binaries (one set
// per chip and per fixed clip length, released as asr-models-qnn-binary-2 and
// -3): Parakeet TDT 0.6B v3 (encoder.bin, decoder.bin, joiner.bin) and
// Moonshine Tiny English (encoder.bin, decoder.bin). sherpa-onnx pads a
// shorter clip to the length the binaries were compiled for and cuts a longer
// one; the Kotlin side never hands it more than that.
//
// sherpa-onnx's C API has no QNN fields, so this goes through its C++ API,
// which libwmsherpa does not link. It is its own library, apart from
// libwmsherpa: sherpa-onnx built with SHERPA_ONNX_ENABLE_QNN for arm64 alone,
// against Qualcomm's QNN headers. Like libwmsherpa it names libonnxruntime.so
// as a dependency (sherpa-onnx-core's other recognizers call ONNX Runtime), so
// Kotlin loads Microsoft's runtime and the NPU runtime's libraries first.
//
// The QNN libraries are opened by path (libQnnHtp.so, libQnnSystem.so in the
// directory NpuRuntime unpacked), and the DSP finds the chip's skel through
// ADSP_LIBRARY_PATH, which nativeSetAdspDirectory points at that directory.

#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>

#include "sherpa-onnx/csrc/offline-recognizer.h"

namespace {

jbyteArray bytes(JNIEnv* env, const std::string& text) {
    const jsize n = static_cast<jsize>(text.size());
    jbyteArray out = env->NewByteArray(n);
    if (out && n > 0) env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(text.data()));
    return out;
}

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (!c) return {};
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

sherpa_onnx::QnnConfig qnn(const std::string& runtimeDir, const std::string& binaries) {
    sherpa_onnx::QnnConfig c;
    c.backend_lib = runtimeDir + "/libQnnHtp.so";
    c.system_lib = runtimeDir + "/libQnnSystem.so";
    c.context_binary = binaries;
    return c;
}

// sherpa-onnx exits the process on some bad configurations instead of
// returning; Validate() catches the ones a missing or wrong file causes.
jlong create(sherpa_onnx::OfflineRecognizerConfig& config) {
    config.model_config.provider = "qnn";
    config.model_config.num_threads = 1;
    config.model_config.debug = false;
    config.decoding_method = "greedy_search";
    if (!config.Validate()) return 0;
    try {
        auto* r = new sherpa_onnx::OfflineRecognizer(config);
        return reinterpret_cast<jlong>(r);
    } catch (...) {
        return 0;
    }
}

}  // namespace

/**
 * Where the DSP finds the HTP skel: [dir], the NPU runtime's directory, ahead
 * of the system's own places for it. Process-wide, as the environment is.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaQnnNative_nativeSetAdspDirectory(
        JNIEnv* env, jclass, jstring dir) {
    const std::string d = str(env, dir);
    if (d.empty()) return;
    // Semicolons, not colons: the DSP loader's separator.
    const std::string adsp = d + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;"
                                 "/system/vendor/lib/rfsa/adsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
}

/** Parakeet TDT from its three context binaries; 0 when it will not load. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaQnnNative_nativeParakeet(
        JNIEnv* env, jclass, jstring encoder, jstring decoder, jstring joiner, jstring tokens,
        jstring runtimeDir) {
    sherpa_onnx::OfflineRecognizerConfig config;
    config.model_config.tokens = str(env, tokens);
    config.model_config.model_type = "nemo_transducer";
    config.model_config.transducer.qnn_config = qnn(
        str(env, runtimeDir),
        str(env, encoder) + "," + str(env, decoder) + "," + str(env, joiner));
    return create(config);
}

/** Moonshine from its encoder and decoder context binaries; 0 when it will not load. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaQnnNative_nativeMoonshine(
        JNIEnv* env, jclass, jstring encoder, jstring decoder, jstring tokens, jstring runtimeDir) {
    sherpa_onnx::OfflineRecognizerConfig config;
    config.model_config.tokens = str(env, tokens);
    config.model_config.moonshine.qnn_config = qnn(
        str(env, runtimeDir), str(env, encoder) + "," + str(env, decoder));
    return create(config);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaQnnNative_nativeFree(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<sherpa_onnx::OfflineRecognizer*>(handle);
}

/**
 * The first [count] samples (16 kHz mono, in [-1, 1]) as text, UTF-8; null
 * when the decode throws. One whole clip, no longer than the binaries' length.
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaQnnNative_nativeDecode(
        JNIEnv* env, jclass, jlong handle, jfloatArray samples, jint count) {
    auto* r = reinterpret_cast<sherpa_onnx::OfflineRecognizer*>(handle);
    if (!r || !samples || count <= 0) return bytes(env, "");
    const jsize n = env->GetArrayLength(samples);
    if (count > n) count = n;
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) return nullptr;
    std::string text;
    bool ok = true;
    try {
        auto stream = r->CreateStream();
        stream->AcceptWaveform(16000, data, count);
        r->DecodeStream(stream.get());
        text = stream->GetResult().text;
    } catch (...) {
        ok = false;
    }
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return ok ? bytes(env, text) : nullptr;
}
