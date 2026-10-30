// JNI over liblouis's back-translation, for BrailleContracted in :core:input.
// Liblouis keeps its compiled tables in process-wide state, so every call here
// is serialized on the Kotlin side.
#include <jni.h>
#include <stdlib.h>
#include "liblouis.h"

#define MAX_CELLS 4096

JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_input_BrailleNative_open(JNIEnv *env, jobject thiz, jstring data_path, jstring table) {
    const char *path = (*env)->GetStringUTFChars(env, data_path, NULL);
    const char *name = (*env)->GetStringUTFChars(env, table, NULL);
    jboolean ok = JNI_FALSE;
    if (path && name) {
        lou_setLogLevel(LOU_LOG_OFF);
        ok = lou_setDataPath(path) && lou_checkTable(name) ? JNI_TRUE : JNI_FALSE;
    }
    if (path) (*env)->ReleaseStringUTFChars(env, data_path, path);
    if (name) (*env)->ReleaseStringUTFChars(env, table, name);
    return ok;
}

// [cells] holds LOU_DOTS | mask per cell. Null when liblouis refuses them.
JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_input_BrailleNative_backTranslate(JNIEnv *env, jobject thiz, jstring table, jcharArray cells) {
    jsize count = (*env)->GetArrayLength(env, cells);
    if (count <= 0 || count > MAX_CELLS) return NULL;
    // A contraction is at most a word or so of print; liblouis stops at the end of the buffer.
    int out_len = count * 16 + 64;
    widechar *in = malloc(sizeof(widechar) * count);
    widechar *out = malloc(sizeof(widechar) * out_len);
    const char *name = (*env)->GetStringUTFChars(env, table, NULL);
    jstring result = NULL;
    if (in && out && name) {
        (*env)->GetCharArrayRegion(env, cells, 0, count, (jchar *)in);
        int in_len = count;
        if (lou_backTranslateString(name, in, &in_len, out, &out_len, NULL, NULL, dotsIO)) {
            result = (*env)->NewString(env, (const jchar *)out, out_len);
        }
    }
    if (name) (*env)->ReleaseStringUTFChars(env, table, name);
    free(in);
    free(out);
    return result;
}
