// JNI surface for Vosk (Alpha Cephei's Kaldi-based recogniser): the offline
// Vosk dictation engine. Kotlin side:
// com.wasimaster.wmkeyboard.core.voice.vosk.VoskNative.
//
// libvosk.so is Alpha Cephei's own build, read out of their vosk-android AAR
// (Apache-2.0) on Maven Central and delivered by the `vosk` gate; this bridge
// does not link against it. It finds the C API (vosk_api.h) with dlsym in the
// copy the Kotlin side has already loaded, so it builds with nothing but the
// NDK and loads on any phone, and a libvosk that is missing or too old turns
// into a 0 handle instead of a crash.
//
// Handles are plain pointers in jlongs. Nothing here is thread-safe; the
// Kotlin side calls it from one thread.

#include <jni.h>

#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

typedef struct VoskModel VoskModel;
typedef struct VoskRecognizer VoskRecognizer;

static struct {
    int tried;
    int ok;
    void (*set_log_level)(int);
    VoskModel *(*model_new)(const char *);
    void (*model_free)(VoskModel *);
    VoskRecognizer *(*recognizer_new)(VoskModel *, float);
    void (*recognizer_free)(VoskRecognizer *);
    int (*accept_waveform_f)(VoskRecognizer *, const float *, int);
    const char *(*result)(VoskRecognizer *);
    const char *(*partial_result)(VoskRecognizer *);
    const char *(*final_result)(VoskRecognizer *);
    void (*reset)(VoskRecognizer *);
} api;

/* Binds the C API once. The library was loaded by its soname beforehand, so
 * this dlopen finds that copy rather than searching for another. */
static int bind_api(void) {
    if (api.tried) return api.ok;
    api.tried = 1;
    void *lib = dlopen("libvosk.so", RTLD_NOW);
    if (!lib) return 0;
#define BIND(field, name) \
    if (!(*(void **)&api.field = dlsym(lib, name))) return 0;
    BIND(set_log_level, "vosk_set_log_level")
    BIND(model_new, "vosk_model_new")
    BIND(model_free, "vosk_model_free")
    BIND(recognizer_new, "vosk_recognizer_new")
    BIND(recognizer_free, "vosk_recognizer_free")
    BIND(accept_waveform_f, "vosk_recognizer_accept_waveform_f")
    BIND(result, "vosk_recognizer_result")
    BIND(partial_result, "vosk_recognizer_partial_result")
    BIND(final_result, "vosk_recognizer_final_result")
    BIND(reset, "vosk_recognizer_reset")
#undef BIND
    // Kaldi logs every model load at length; warnings and errors only.
    api.set_log_level(-1);
    api.ok = 1;
    return 1;
}

static jbyteArray bytes(JNIEnv *env, const char *text) {
    if (!text) text = "";
    jsize n = (jsize)strlen(text);
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out && n > 0) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *)text);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_vosk_VoskNative_nativeBind(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return bind_api() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_vosk_VoskNative_nativeModel(JNIEnv *env, jclass cls, jstring path) {
    (void)cls;
    if (!bind_api() || !path) return 0;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    if (!p) return 0;
    VoskModel *model = api.model_new(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return (jlong)(intptr_t)model;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_vosk_VoskNative_nativeModelFree(JNIEnv *env, jclass cls, jlong model) {
    (void)env;
    (void)cls;
    if (api.ok && model) api.model_free((VoskModel *)(intptr_t)model);
}

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_vosk_VoskNative_nativeRecognizer(
    JNIEnv *env, jclass cls, jlong model, jfloat sample_rate) {
    (void)env;
    (void)cls;
    if (!api.ok || !model) return 0;
    return (jlong)(intptr_t)api.recognizer_new((VoskModel *)(intptr_t)model, sample_rate);
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_vosk_VoskNative_nativeRecognizerFree(JNIEnv *env, jclass cls, jlong rec) {
    (void)env;
    (void)cls;
    if (api.ok && rec) api.recognizer_free((VoskRecognizer *)(intptr_t)rec);
}

/* [count] samples in [-1, 1]. Vosk's float entry point wants 16-bit PCM
 * values in floats, so they are scaled here. 1 when the recogniser heard an
 * utterance end (read it with nativeResult), 0 while it goes on, -1 on error. */
JNIEXPORT jint JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_vosk_VoskNative_nativeAccept(
    JNIEnv *env, jclass cls, jlong rec, jfloatArray samples, jint count) {
    (void)cls;
    if (!api.ok || !rec || !samples || count <= 0) return 0;
    jsize len = (*env)->GetArrayLength(env, samples);
    if (count > len) count = len;
    float *pcm = malloc(sizeof(float) * (size_t)count);
    if (!pcm) return -1;
    (*env)->GetFloatArrayRegion(env, samples, 0, count, pcm);
    for (jint i = 0; i < count; i++) {
        float s = pcm[i] * 32767.0f;
        pcm[i] = s > 32767.0f ? 32767.0f : (s < -32768.0f ? -32768.0f : s);
    }
    int got = api.accept_waveform_f((VoskRecognizer *)(intptr_t)rec, pcm, count);
    free(pcm);
    return got;
}

/* The utterance that just ended, as Vosk's JSON ({"text": ...}). */
JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_vosk_VoskNative_nativeResult(JNIEnv *env, jclass cls, jlong rec) {
    (void)cls;
    return bytes(env, api.ok && rec ? api.result((VoskRecognizer *)(intptr_t)rec) : NULL);
}

/* The utterance so far ({"partial": ...}). */
JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_vosk_VoskNative_nativePartial(JNIEnv *env, jclass cls, jlong rec) {
    (void)cls;
    return bytes(env, api.ok && rec ? api.partial_result((VoskRecognizer *)(intptr_t)rec) : NULL);
}

/* The last utterance, flushed ({"text": ...}); the recogniser starts afresh. */
JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_vosk_VoskNative_nativeFinal(JNIEnv *env, jclass cls, jlong rec) {
    (void)cls;
    return bytes(env, api.ok && rec ? api.final_result((VoskRecognizer *)(intptr_t)rec) : NULL);
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_vosk_VoskNative_nativeReset(JNIEnv *env, jclass cls, jlong rec) {
    (void)env;
    (void)cls;
    if (api.ok && rec) api.reset((VoskRecognizer *)(intptr_t)rec);
}
