// JNI surface for Qwen3-ASR (#1437), run by sherpa-onnx's offline recognizer:
// a convolutional front end, an audio encoder and a Qwen3 decoder with a KV
// cache, each its own ONNX file, plus the Qwen tokenizer's vocab.json,
// merges.txt and tokenizer_config.json in one folder. Kotlin side:
// com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative (nativeQwen3*),
// driven by Qwen3AsrEngine.
//
// The model is not a streaming one: the Kotlin side cuts the microphone's
// audio into phrases (Silero, through sherpa_jni.cpp's segmenter) and each
// phrase comes here whole. A phrase can carry the language to write in
// (Qwen3's own name for it, "English") and the words to listen for, both of
// which go into the decoder's prompt.
//
// sherpa-onnx exits the process on a model whose graphs are not the shape it
// expects, and that process is the keyboard, so a model the user brings is
// first opened here with plain ONNX Runtime (nativeQwen3Probe) and checked
// the way offline-qwen3-asr-model.cc checks it.
//
// Same rules as sherpa_jni.cpp: ONNX Runtime is found by soname at load time,
// and text crosses the boundary as UTF-8 bytes.

#include <jni.h>

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "onnxruntime_cxx_api.h"
#include "sherpa-onnx/c-api/c-api.h"

namespace {

jbyteArray utf8(JNIEnv* env, const char* text) {
    const jsize n = text ? static_cast<jsize>(std::strlen(text)) : 0;
    jbyteArray out = env->NewByteArray(n);
    if (out && n > 0) env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(text));
    return out;
}

std::string text(JNIEnv* env, jbyteArray bytes) {
    const jsize n = bytes ? env->GetArrayLength(bytes) : 0;
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte*>(&out[0]));
    return out;
}

// A path's characters for as long as the object lives; ASCII, under filesDir.
class Path {
public:
    Path(JNIEnv* env, jstring s) : env_(env), s_(s), c_(s ? env->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~Path() { if (c_) env_->ReleaseStringUTFChars(s_, c_); }
    const char* get() const { return c_ && c_[0] ? c_ : nullptr; }
    Path(const Path&) = delete;
    Path& operator=(const Path&) = delete;

private:
    JNIEnv* env_;
    jstring s_;
    const char* c_;
};

const SherpaOnnxOfflineRecognizer* recognizer(jlong h) {
    return reinterpret_cast<const SherpaOnnxOfflineRecognizer*>(h);
}

Ort::Env& probeEnv() {
    static Ort::Env e(ORT_LOGGING_LEVEL_ERROR, "wmsherpa-qwen3-probe");
    return e;
}

bool kvType(ONNXTensorElementDataType t) {
    return t == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || t == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16 ||
           t == ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16;
}

// Where the decoder's past keys start among its inputs, as
// offline-qwen3-asr-model.cc has it (kDecoderPastKvInputStartIndex).
constexpr size_t kPastKvStart = 4;

}  // namespace

extern "C" {

// A Qwen3-ASR recognizer over the three graphs and the tokenizer folder; 0
// when it will not load. [maxTotalLen] caps prompt plus audio plus text in
// the decoder's cache; sherpa-onnx keeps it under what the export allows.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeQwen3(
    JNIEnv* env, jclass, jstring convFrontend, jstring encoder, jstring decoder, jstring tokenizerDir,
    jint threads, jint maxTotalLen, jint maxNewTokens) {
    Path conv(env, convFrontend), enc(env, encoder), dec(env, decoder), tok(env, tokenizerDir);
    if (!conv.get() || !enc.get() || !dec.get() || !tok.get()) return 0;
    SherpaOnnxOfflineRecognizerConfig config;
    std::memset(&config, 0, sizeof(config));
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;
    config.decoding_method = "greedy_search";
    config.model_config.num_threads = threads;
    config.model_config.provider = "cpu";
    config.model_config.qwen3_asr.conv_frontend = conv.get();
    config.model_config.qwen3_asr.encoder = enc.get();
    config.model_config.qwen3_asr.decoder = dec.get();
    config.model_config.qwen3_asr.tokenizer = tok.get();
    config.model_config.qwen3_asr.max_total_len = maxTotalLen;
    config.model_config.qwen3_asr.max_new_tokens = maxNewTokens;
    // As good as greedy: sherpa-onnx samples, and this leaves it no room to.
    config.model_config.qwen3_asr.temperature = 1e-6f;
    config.model_config.qwen3_asr.top_p = 0.8f;
    config.model_config.qwen3_asr.seed = 42;
    config.model_config.qwen3_asr.hotwords = "";
    return reinterpret_cast<jlong>(SherpaOnnxCreateOfflineRecognizer(&config));
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeQwen3Free(JNIEnv*, jclass, jlong h) {
    if (h) SherpaOnnxDestroyOfflineRecognizer(recognizer(h));
}

// The first [count] samples of a phrase (16 kHz mono, [-1, 1]) as text, UTF-8.
// [language] is Qwen3's name for the language to write in, or null to let the
// model tell; [hotwords] (UTF-8) is a comma-separated list of words to listen
// for, or null; [maxNewTokens] caps what the decoder may write for it.
JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeQwen3Decode(
    JNIEnv* env, jclass, jlong h, jfloatArray samples, jint count, jbyteArray language, jbyteArray hotwords,
    jint maxNewTokens) {
    if (!h || count <= 0) return utf8(env, nullptr);
    const SherpaOnnxOfflineStream* stream = SherpaOnnxCreateOfflineStream(recognizer(h));
    if (!stream) return utf8(env, nullptr);
    const std::string lang = text(env, language);
    const std::string words = text(env, hotwords);
    if (!lang.empty()) SherpaOnnxOfflineStreamSetOption(stream, "language", lang.c_str());
    if (!words.empty()) SherpaOnnxOfflineStreamSetOption(stream, "hotwords", words.c_str());
    if (maxNewTokens > 0) {
        const std::string cap = std::to_string(maxNewTokens);
        SherpaOnnxOfflineStreamSetOption(stream, "max_new_tokens", cap.c_str());
    }
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) {
        SherpaOnnxDestroyOfflineStream(stream);
        return utf8(env, nullptr);
    }
    SherpaOnnxAcceptWaveformOffline(stream, 16000, data, count);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    SherpaOnnxDecodeOfflineStream(recognizer(h), stream);
    const SherpaOnnxOfflineRecognizerResult* result = SherpaOnnxGetOfflineStreamResult(stream);
    jbyteArray out = utf8(env, result ? result->text : nullptr);
    if (result) SherpaOnnxDestroyOfflineRecognizerResult(result);
    SherpaOnnxDestroyOfflineStream(stream);
    return out;
}

// Whether the three graphs are ones sherpa-onnx's Qwen3-ASR loader takes
// without exiting: a front end of one input and one output, an encoder of two
// inputs and one output, and a decoder of 1 + 2 x layers outputs and
// 4 + 2 x layers inputs whose past keys and values are float, half or
// bfloat16. Opens each with plain ONNX Runtime, so it is safe on any file;
// the decoder of a large model takes seconds and its size in memory while it
// is checked. 0 when they pass, 1 for a file ONNX Runtime cannot open, 2 for
// graphs of another shape.
JNIEXPORT jint JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeQwen3Probe(
    JNIEnv* env, jclass, jstring convFrontend, jstring encoder, jstring decoder) {
    Path conv(env, convFrontend), enc(env, encoder), dec(env, decoder);
    if (!conv.get() || !enc.get() || !dec.get()) return 1;
    try {
        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(1);
        // Checking shapes needs no optimised graph, and skipping it is much quicker.
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_DISABLE_ALL);
        {
            Ort::Session s(probeEnv(), conv.get(), options);
            if (s.GetInputCount() != 1 || s.GetOutputCount() != 1) return 2;
        }
        {
            Ort::Session s(probeEnv(), enc.get(), options);
            if (s.GetInputCount() != 2 || s.GetOutputCount() != 1) return 2;
        }
        Ort::Session s(probeEnv(), dec.get(), options);
        const size_t outputs = s.GetOutputCount();
        if (outputs < 3 || (outputs - 1) % 2 != 0) return 2;
        const size_t layers = (outputs - 1) / 2;
        if (s.GetInputCount() != 4 + 2 * layers) return 2;
        const auto key = s.GetInputTypeInfo(kPastKvStart).GetTensorTypeAndShapeInfo().GetElementType();
        const auto value = s.GetInputTypeInfo(kPastKvStart + 1).GetTensorTypeAndShapeInfo().GetElementType();
        if (!kvType(key) || !kvType(value)) return 2;
        return 0;
    } catch (const Ort::Exception&) {
        return 1;
    } catch (const std::exception&) {
        return 1;
    }
}

}  // extern "C"
