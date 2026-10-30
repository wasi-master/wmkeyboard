// JNI surface for sherpa-onnx's C API: a streaming transducer recognizer
// (greedy, or by modified beam search with hotwords), a streaming Paraformer,
// Silero or TEN voice activity detection (as a speech flag, or cutting the audio into
// phrases for the Omnilingual model in omnilingual_jni.cpp) and the online
// punctuation model. Kotlin side:
// com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative.
//
// ONNX Runtime is not linked in. This library names libonnxruntime.so as a
// dependency and the dynamic linker finds it by soname, so Kotlin loads
// Microsoft's runtime first (from the APK, a Play split, or a file the phone
// fetched from Maven Central) and this library second.
//
// Text crosses the boundary as UTF-8 byte arrays, never through NewStringUTF:
// JNI's modified UTF-8 has no room for a four-byte character.

#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "sherpa-onnx/c-api/c-api.h"

namespace {

jbyteArray bytes(JNIEnv* env, const char* text) {
    const jsize n = text ? static_cast<jsize>(std::strlen(text)) : 0;
    jbyteArray out = env->NewByteArray(n);
    if (out && n > 0) env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(text));
    return out;
}

// A jstring's characters for as long as the object lives. Paths only, which
// are ASCII under the app's files directory.
class Chars {
public:
    Chars(JNIEnv* env, jstring s) : env_(env), s_(s), c_(s ? env->GetStringUTFChars(s, nullptr) : nullptr) {}
    ~Chars() { if (c_) env_->ReleaseStringUTFChars(s_, c_); }
    const char* get() const { return c_ && c_[0] ? c_ : nullptr; }
    Chars(const Chars&) = delete;
    Chars& operator=(const Chars&) = delete;

private:
    JNIEnv* env_;
    jstring s_;
    const char* c_;
};

// Silero and TEN VAD read fixed windows; the microphone delivers whatever it
// has. A detector that [keeps] its segments hands them out one by one (the
// Omnilingual engine decodes each); one that does not drops them, since only
// whether someone is speaking is wanted.
struct Vad {
    const SherpaOnnxVoiceActivityDetector* detector = nullptr;
    std::vector<float> pending;
    int32_t window = 512;
    bool keep = false;
};

// Which model family a detector runs; the values SherpaNative.VAD_* name.
constexpr jint kSilero = 0;
constexpr jint kTen = 1;

// Silero reads 32 ms windows at 16 kHz (v5 insists on exactly that), TEN 16 ms.
constexpr int32_t kSileroWindow = 512;
constexpr int32_t kTenWindow = 256;

// sherpa-onnx exits the process on a model it does not recognise, so only a
// file that is one of ours or has passed nativeVadProbe (vad_jni.cpp) gets
// here.
Vad* newVad(JNIEnv* env, jint kind, jstring model, jfloat threshold, jfloat minSilence, jfloat minSpeech,
            jfloat maxSpeech, bool keep) {
    Chars path(env, model);
    if (!path.get() || (kind != kSilero && kind != kTen)) return nullptr;
    SherpaOnnxVadModelConfig config;
    std::memset(&config, 0, sizeof(config));
    // Past [maxSpeech] seconds of speech without a pause, the detector takes
    // a much shorter pause as the end, so no segment grows without limit.
    if (kind == kTen) {
        config.ten_vad.model = path.get();
        config.ten_vad.threshold = threshold;
        config.ten_vad.min_silence_duration = minSilence;
        config.ten_vad.min_speech_duration = minSpeech;
        config.ten_vad.window_size = kTenWindow;
        config.ten_vad.max_speech_duration = maxSpeech;
    } else {
        config.silero_vad.model = path.get();
        config.silero_vad.threshold = threshold;
        config.silero_vad.min_silence_duration = minSilence;
        config.silero_vad.min_speech_duration = minSpeech;
        config.silero_vad.window_size = kSileroWindow;
        config.silero_vad.max_speech_duration = maxSpeech;
    }
    config.sample_rate = 16000;
    config.num_threads = 1;
    config.provider = "cpu";
    const SherpaOnnxVoiceActivityDetector* detector =
        SherpaOnnxCreateVoiceActivityDetector(&config, keep ? 60.0f : 30.0f);
    if (!detector) return nullptr;
    auto* vad = new Vad();
    vad->detector = detector;
    vad->window = kind == kTen ? kTenWindow : kSileroWindow;
    vad->keep = keep;
    return vad;
}

const SherpaOnnxOnlineRecognizer* recognizer(jlong h) {
    return reinterpret_cast<const SherpaOnnxOnlineRecognizer*>(h);
}

const SherpaOnnxOnlineStream* stream(jlong h) {
    return reinterpret_cast<const SherpaOnnxOnlineStream*>(h);
}

// The recognizer's own endpoint: trailing silence after words (rule 2).
// Silero decides first when the model is there; this is the fallback.
// Rule 1 (silence with no words yet) is pushed out of reach: the quiet
// before someone starts talking must never end the clip.
void setEndpoint(SherpaOnnxOnlineRecognizerConfig& config, jfloat trailingSilence) {
    config.enable_endpoint = 1;
    config.rule1_min_trailing_silence = 600.0f;
    config.rule2_min_trailing_silence = trailingSilence;
    config.rule3_min_utterance_length = 300.0f;
}

// A streaming transducer. A null [unit] decodes greedily; "bpe" or
// "cjkchar+bpe" decodes by modified beam search, the only search that follows
// a stream's hotwords, cutting them into pieces over [vocab].
jlong newRecognizer(JNIEnv* env, jstring encoder, jstring decoder, jstring joiner, jstring tokens,
                    jint threads, jfloat trailingSilence, jstring unit, jstring vocab, jfloat hotwordsScore) {
    Chars enc(env, encoder), dec(env, decoder), join(env, joiner), tok(env, tokens);
    Chars mode(env, unit), bpe(env, vocab);
    SherpaOnnxOnlineRecognizerConfig config;
    std::memset(&config, 0, sizeof(config));
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;
    config.model_config.transducer.encoder = enc.get();
    config.model_config.transducer.decoder = dec.get();
    config.model_config.transducer.joiner = join.get();
    config.model_config.tokens = tok.get();
    config.model_config.num_threads = threads;
    config.model_config.provider = "cpu";
    if (mode.get()) {
        config.decoding_method = "modified_beam_search";
        config.model_config.modeling_unit = mode.get();
        config.model_config.bpe_vocab = bpe.get();
        config.hotwords_score = hotwordsScore;
    } else {
        config.decoding_method = "greedy_search";
    }
    config.max_active_paths = 4;
    setEndpoint(config, trailingSilence);
    return reinterpret_cast<jlong>(SherpaOnnxCreateOnlineRecognizer(&config));
}

void decodeReady(jlong rec, jlong s) {
    while (SherpaOnnxIsOnlineStreamReady(recognizer(rec), stream(s))) {
        SherpaOnnxDecodeOnlineStream(recognizer(rec), stream(s));
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeRecognizer(
    JNIEnv* env, jclass, jstring encoder, jstring decoder, jstring joiner, jstring tokens,
    jint threads, jfloat trailingSilence) {
    return newRecognizer(env, encoder, decoder, joiner, tokens, threads, trailingSilence, nullptr, nullptr, 0.0f);
}

// The same transducer by modified beam search, for streams with hotwords.
// [unit] is "bpe" or "cjkchar+bpe" and nothing else: sherpa-onnx exits the
// process on any other.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeHotwordRecognizer(
    JNIEnv* env, jclass, jstring encoder, jstring decoder, jstring joiner, jstring tokens,
    jint threads, jfloat trailingSilence, jstring unit, jstring vocab, jfloat hotwordsScore) {
    if (!unit || !vocab) return 0;
    return newRecognizer(env, encoder, decoder, joiner, tokens, threads, trailingSilence, unit, vocab, hotwordsScore);
}

// A streaming Paraformer: an encoder, a decoder and no joiner. Greedy only;
// sherpa-onnx exits the process on any other search, so hotwords can't reach it.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeParaformerRecognizer(
    JNIEnv* env, jclass, jstring encoder, jstring decoder, jstring tokens, jint threads, jfloat trailingSilence) {
    Chars enc(env, encoder), dec(env, decoder), tok(env, tokens);
    SherpaOnnxOnlineRecognizerConfig config;
    std::memset(&config, 0, sizeof(config));
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;
    config.model_config.paraformer.encoder = enc.get();
    config.model_config.paraformer.decoder = dec.get();
    config.model_config.tokens = tok.get();
    config.model_config.num_threads = threads;
    config.model_config.provider = "cpu";
    config.decoding_method = "greedy_search";
    setEndpoint(config, trailingSilence);
    return reinterpret_cast<jlong>(SherpaOnnxCreateOnlineRecognizer(&config));
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeRecognizerFree(JNIEnv*, jclass, jlong rec) {
    if (rec) SherpaOnnxDestroyOnlineRecognizer(recognizer(rec));
}

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeStream(JNIEnv*, jclass, jlong rec) {
    return reinterpret_cast<jlong>(SherpaOnnxCreateOnlineStream(recognizer(rec)));
}

// A stream that listens for [hotwords]: UTF-8, one phrase a line, its words
// separated by spaces, already spelled for the model by the Kotlin side.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeHotwordStream(
    JNIEnv* env, jclass, jlong rec, jbyteArray hotwords) {
    const jsize n = hotwords ? env->GetArrayLength(hotwords) : 0;
    if (n == 0) return reinterpret_cast<jlong>(SherpaOnnxCreateOnlineStream(recognizer(rec)));
    std::string text(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(hotwords, 0, n, reinterpret_cast<jbyte*>(&text[0]));
    return reinterpret_cast<jlong>(SherpaOnnxCreateOnlineStreamWithHotwords(recognizer(rec), text.c_str()));
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeStreamFree(JNIEnv*, jclass, jlong s) {
    if (s) SherpaOnnxDestroyOnlineStream(stream(s));
}

// Feeds n samples (16 kHz, [-1, 1]) and decodes every frame they complete.
JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeFeed(
    JNIEnv* env, jclass, jlong rec, jlong s, jfloatArray samples, jint n) {
    if (n <= 0) return;
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) return;
    SherpaOnnxOnlineStreamAcceptWaveform(stream(s), 16000, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    decodeReady(rec, s);
}

// Says the clip is over and decodes what is left of it.
JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeFinish(JNIEnv*, jclass, jlong rec, jlong s) {
    // A little silence after the last word, so its final frames are decoded
    // rather than held back for context that never comes.
    static const std::vector<float> tail(16000 * 3 / 10, 0.0f);
    SherpaOnnxOnlineStreamAcceptWaveform(stream(s), 16000, tail.data(), static_cast<int32_t>(tail.size()));
    SherpaOnnxOnlineStreamInputFinished(stream(s));
    decodeReady(rec, s);
}

JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeText(JNIEnv* env, jclass, jlong rec, jlong s) {
    const SherpaOnnxOnlineRecognizerResult* result = SherpaOnnxGetOnlineStreamResult(recognizer(rec), stream(s));
    jbyteArray out = bytes(env, result ? result->text : nullptr);
    if (result) SherpaOnnxDestroyOnlineRecognizerResult(result);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeEndpoint(JNIEnv*, jclass, jlong rec, jlong s) {
    return SherpaOnnxOnlineStreamIsEndpoint(recognizer(rec), stream(s)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVad(
    JNIEnv* env, jclass, jstring model, jfloat threshold, jfloat minSilence, jfloat minSpeech) {
    return reinterpret_cast<jlong>(newVad(env, kSilero, model, threshold, minSilence, minSpeech, 300.0f, false));
}

// A detector that keeps what it hears as segments, each a phrase between
// pauses of at least [minSilence] seconds, read with nativeVadSegment.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeSegmenter(
    JNIEnv* env, jclass, jstring model, jfloat threshold, jfloat minSilence, jfloat minSpeech, jfloat maxSpeech) {
    return reinterpret_cast<jlong>(newVad(env, kSilero, model, threshold, minSilence, minSpeech, maxSpeech, true));
}

// Either of the two above for a model of [kind] (kSilero, kTen): a detector
// that keeps its segments when [keep] is true, one that only says whether
// someone is speaking otherwise.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVadOf(
    JNIEnv* env, jclass, jint kind, jstring model, jfloat threshold, jfloat minSilence, jfloat minSpeech,
    jfloat maxSpeech, jboolean keep) {
    return reinterpret_cast<jlong>(newVad(env, kind, model, threshold, minSilence, minSpeech, maxSpeech, keep == JNI_TRUE));
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVadFree(JNIEnv*, jclass, jlong h) {
    auto* vad = reinterpret_cast<Vad*>(h);
    if (!vad) return;
    SherpaOnnxDestroyVoiceActivityDetector(vad->detector);
    delete vad;
}

// Feeds n samples; true while the detector hears speech. A detector that
// does not keep its segments drops them as they complete.
JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVadFeed(
    JNIEnv* env, jclass, jlong h, jfloatArray samples, jint n) {
    auto* vad = reinterpret_cast<Vad*>(h);
    if (!vad || n <= 0) return JNI_FALSE;
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) return JNI_FALSE;
    vad->pending.insert(vad->pending.end(), data, data + n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    size_t at = 0;
    while (vad->pending.size() - at >= static_cast<size_t>(vad->window)) {
        SherpaOnnxVoiceActivityDetectorAcceptWaveform(vad->detector, vad->pending.data() + at, vad->window);
        at += vad->window;
        while (!vad->keep && !SherpaOnnxVoiceActivityDetectorEmpty(vad->detector)) {
            SherpaOnnxVoiceActivityDetectorPop(vad->detector);
        }
    }
    vad->pending.erase(vad->pending.begin(), vad->pending.begin() + static_cast<std::ptrdiff_t>(at));
    return SherpaOnnxVoiceActivityDetectorDetected(vad->detector) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVadReset(JNIEnv*, jclass, jlong h) {
    auto* vad = reinterpret_cast<Vad*>(h);
    if (!vad) return;
    SherpaOnnxVoiceActivityDetectorReset(vad->detector);
    vad->pending.clear();
}

// The oldest finished segment's samples, taken off the queue; null when
// there is none.
JNIEXPORT jfloatArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVadSegment(JNIEnv* env, jclass, jlong h) {
    auto* vad = reinterpret_cast<Vad*>(h);
    if (!vad || SherpaOnnxVoiceActivityDetectorEmpty(vad->detector)) return nullptr;
    const SherpaOnnxSpeechSegment* segment = SherpaOnnxVoiceActivityDetectorFront(vad->detector);
    jfloatArray out = nullptr;
    if (segment) {
        out = env->NewFloatArray(segment->n);
        if (out && segment->n > 0) env->SetFloatArrayRegion(out, 0, segment->n, segment->samples);
        SherpaOnnxDestroySpeechSegment(segment);
    }
    SherpaOnnxVoiceActivityDetectorPop(vad->detector);
    return out;
}

// The clip is over: the samples short of a window go in padded with
// silence, and speech still going on becomes a last segment.
JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeVadFlush(JNIEnv*, jclass, jlong h) {
    auto* vad = reinterpret_cast<Vad*>(h);
    if (!vad) return;
    if (!vad->pending.empty()) {
        vad->pending.resize(static_cast<size_t>(vad->window), 0.0f);
        SherpaOnnxVoiceActivityDetectorAcceptWaveform(vad->detector, vad->pending.data(), vad->window);
        vad->pending.clear();
    }
    SherpaOnnxVoiceActivityDetectorFlush(vad->detector);
}

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativePunctuation(
    JNIEnv* env, jclass, jstring model, jstring vocab) {
    Chars m(env, model), v(env, vocab);
    SherpaOnnxOnlinePunctuationConfig config;
    std::memset(&config, 0, sizeof(config));
    config.model.cnn_bilstm = m.get();
    config.model.bpe_vocab = v.get();
    config.model.num_threads = 1;
    config.model.provider = "cpu";
    return reinterpret_cast<jlong>(SherpaOnnxCreateOnlinePunctuation(&config));
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativePunctuationFree(JNIEnv*, jclass, jlong h) {
    if (h) SherpaOnnxDestroyOnlinePunctuation(reinterpret_cast<const SherpaOnnxOnlinePunctuation*>(h));
}

// [text] (UTF-8) with punctuation and capitals added.
JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativePunctuate(
    JNIEnv* env, jclass, jlong h, jbyteArray text) {
    const jsize n = env->GetArrayLength(text);
    std::string in(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(text, 0, n, reinterpret_cast<jbyte*>(&in[0]));
    const char* out = SherpaOnnxOnlinePunctuationAddPunct(
        reinterpret_cast<const SherpaOnnxOnlinePunctuation*>(h), in.c_str());
    jbyteArray result = bytes(env, out ? out : in.c_str());
    if (out) SherpaOnnxOnlinePunctuationFreeText(out);
    return result;
}

}  // extern "C"
