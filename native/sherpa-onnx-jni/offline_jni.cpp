// JNI surface for sherpa-onnx's offline recognizer over the model families
// Offline Sherpa decodes a phrase at a time: DataoceanAI's Dolphin (CTC, 40
// Eastern languages), Moonshine's per-language models, SenseVoice, Zipformer,
// WeNet and NeMo CTC models, and offline transducers. Kotlin side:
// com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative (nativeOffline*),
// driven by SherpaEngine for a model whose EtModel.sherpaKind is not
// STREAMING.
//
// None of these is a streaming model: the Kotlin side cuts the microphone's
// audio into phrases (Silero, through sherpa_jni.cpp's segmenter) and each
// phrase comes here whole, as with Qwen3-ASR (qwen3_jni.cpp).
//
// sherpa-onnx exits the process on a model it cannot read, and that process
// is the keyboard, so only models this app pins by SHA-256 come here.
//
// Same rules as sherpa_jni.cpp: ONNX Runtime is found by soname at load time,
// and text crosses the boundary as UTF-8 bytes.

#include <jni.h>

#include <cstring>
#include <string>

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

// The families SherpaNative.OFFLINE_* name. [a], [b] and [c] are the model's
// files in the order each family lists them below.
constexpr jint kDolphin = 0;      // a: model
constexpr jint kMoonshine = 1;    // a: encoder, b: merged decoder
constexpr jint kSenseVoice = 2;   // a: model
constexpr jint kZipformerCtc = 3; // a: model
constexpr jint kWenetCtc = 4;     // a: model
constexpr jint kNemoCtc = 5;      // a: model
constexpr jint kTransducer = 6;   // a: encoder, b: decoder, c: joiner

}  // namespace

extern "C" {

// An offline recognizer for a model of [kind]; 0 when it will not load or
// the files the kind needs are missing.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeOffline(
    JNIEnv* env, jclass, jint kind, jstring a, jstring b, jstring c, jstring tokens, jint threads) {
    Path pa(env, a), pb(env, b), pc(env, c), tok(env, tokens);
    if (!pa.get() || !tok.get()) return 0;
    SherpaOnnxOfflineRecognizerConfig config;
    std::memset(&config, 0, sizeof(config));
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;
    config.decoding_method = "greedy_search";
    config.model_config.tokens = tok.get();
    config.model_config.num_threads = threads;
    config.model_config.provider = "cpu";
    switch (kind) {
        case kDolphin:
            config.model_config.dolphin.model = pa.get();
            break;
        case kMoonshine:
            if (!pb.get()) return 0;
            config.model_config.moonshine.encoder = pa.get();
            config.model_config.moonshine.merged_decoder = pb.get();
            break;
        case kSenseVoice:
            config.model_config.sense_voice.model = pa.get();
            config.model_config.sense_voice.language = "auto";
            // Inverse text normalisation: digits, and the marks it writes.
            config.model_config.sense_voice.use_itn = 1;
            break;
        case kZipformerCtc:
            config.model_config.zipformer_ctc.model = pa.get();
            break;
        case kWenetCtc:
            config.model_config.wenet_ctc.model = pa.get();
            break;
        case kNemoCtc:
            config.model_config.nemo_ctc.model = pa.get();
            break;
        case kTransducer:
            if (!pb.get() || !pc.get()) return 0;
            config.model_config.transducer.encoder = pa.get();
            config.model_config.transducer.decoder = pb.get();
            config.model_config.transducer.joiner = pc.get();
            break;
        default:
            return 0;
    }
    return reinterpret_cast<jlong>(SherpaOnnxCreateOfflineRecognizer(&config));
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeOfflineFree(JNIEnv*, jclass, jlong h) {
    if (h) SherpaOnnxDestroyOfflineRecognizer(recognizer(h));
}

// The first [count] samples of a phrase (16 kHz mono, [-1, 1]) as text, UTF-8.
// [language] (UTF-8) goes to the stream's "language" option, which the models
// that can be told one read (SenseVoice); null or empty leaves it unset.
JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeOfflineDecode(
    JNIEnv* env, jclass, jlong h, jfloatArray samples, jint count, jbyteArray language) {
    if (!h || count <= 0) return utf8(env, nullptr);
    const SherpaOnnxOfflineStream* stream = SherpaOnnxCreateOfflineStream(recognizer(h));
    if (!stream) return utf8(env, nullptr);
    const std::string lang = text(env, language);
    if (!lang.empty()) SherpaOnnxOfflineStreamSetOption(stream, "language", lang.c_str());
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

}  // extern "C"
