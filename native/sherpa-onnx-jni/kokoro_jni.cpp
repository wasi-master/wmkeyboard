// JNI surface for Kokoro voices: sherpa-onnx's offline TTS, Kokoro model.
// Kotlin side: com.wasimaster.wmkeyboard.core.voice.sherpa.SherpaNative
// (nativeKokoro*), driven by core.voice.speech.KokoroSpeech.
//
// Since sherpa-onnx 2.0 its TTS phonemises from the lexicon files a model
// ships (English and Chinese for Kokoro) and links no espeak-ng, so it builds
// into this library without bringing GPL code along. A word missing from the
// lexicon is skipped; the Kotlin side spells out numbers first.
//
// Text crosses as UTF-8 bytes, as in sherpa_jni.cpp.

#include <jni.h>

#include <cstring>
#include <string>

#include "sherpa-onnx/c-api/c-api.h"

namespace {

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

const SherpaOnnxOfflineTts* tts(jlong h) { return reinterpret_cast<const SherpaOnnxOfflineTts*>(h); }

}  // namespace

extern "C" {

// Opens a Kokoro model. lexicons and ruleFsts are comma-separated paths, lang
// the model's default ("en-us"); 0 when it will not load.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeKokoro(
    JNIEnv* env, jclass, jstring model, jstring voices, jstring tokens, jstring lexicons, jstring ruleFsts,
    jstring lang, jint threads) {
    const std::string m = str(env, model), v = str(env, voices), t = str(env, tokens), l = str(env, lexicons),
                      r = str(env, ruleFsts), g = str(env, lang);
    SherpaOnnxOfflineTtsConfig config;
    std::memset(&config, 0, sizeof(config));
    config.model.kokoro.model = m.c_str();
    config.model.kokoro.voices = v.c_str();
    config.model.kokoro.tokens = t.c_str();
    config.model.kokoro.lexicon = l.c_str();
    config.model.kokoro.lang = g.c_str();
    config.model.kokoro.data_dir = "";
    config.model.kokoro.dict_dir = "";
    config.model.kokoro.length_scale = 1.0f;
    config.model.num_threads = threads > 0 ? threads : 1;
    config.model.debug = 0;
    config.model.provider = "cpu";
    config.rule_fsts = r.c_str();
    config.rule_fars = "";
    // One sentence per call: SpeechOut already feeds it sentence by sentence.
    config.max_num_sentences = 1;
    config.silence_scale = 0.2f;
    return reinterpret_cast<jlong>(SherpaOnnxCreateOfflineTts(&config));
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeKokoroFree(JNIEnv*, jclass, jlong h) {
    if (h) SherpaOnnxDestroyOfflineTts(tts(h));
}

JNIEXPORT jint JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeKokoroSampleRate(JNIEnv*, jclass, jlong h) {
    return h ? SherpaOnnxOfflineTtsSampleRate(tts(h)) : 0;
}

JNIEXPORT jint JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeKokoroSpeakers(JNIEnv*, jclass, jlong h) {
    return h ? SherpaOnnxOfflineTtsNumSpeakers(tts(h)) : 0;
}

// [utf8] read by speaker [sid] at [speed] (1 = the voice's pace): mono floats
// at nativeKokoroSampleRate, or null when nothing came out.
JNIEXPORT jfloatArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_sherpa_SherpaNative_nativeKokoroRun(
    JNIEnv* env, jclass, jlong h, jbyteArray utf8, jint sid, jfloat speed) {
    if (!h || !utf8) return nullptr;
    const jsize n = env->GetArrayLength(utf8);
    std::string text(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(utf8, 0, n, reinterpret_cast<jbyte*>(&text[0]));
    SherpaOnnxGenerationConfig gen;
    std::memset(&gen, 0, sizeof(gen));
    gen.sid = sid;
    gen.speed = speed > 0 ? speed : 1.0f;
    gen.silence_scale = 0.2f;
    const SherpaOnnxGeneratedAudio* audio = SherpaOnnxOfflineTtsGenerateWithConfig(tts(h), text.c_str(), &gen, nullptr, nullptr);
    if (!audio) return nullptr;
    jfloatArray out = nullptr;
    if (audio->n > 0) {
        out = env->NewFloatArray(audio->n);
        if (out) env->SetFloatArrayRegion(out, 0, audio->n, audio->samples);
    }
    SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio);
    return out;
}

}  // extern "C"
