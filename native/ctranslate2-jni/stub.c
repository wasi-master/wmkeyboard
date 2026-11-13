// The armeabi-v7a libwmct2.so: no engine, only a refusal to load.
//
// NLLB-200's distilled 600M model is 600 MB of int8 weights that
// CTranslate2 reads into memory, beyond what a 32-bit process can count on
// having. Play still wants every split with native code to cover the base's
// two ABIs, and the pins and the F-Droid recipe are simplest with one file
// per ABI, so 32-bit ARM gets this. Ct2Native never loads it (it requires a
// 64-bit process); if anything does, JNI_OnLoad fails and
// System.loadLibrary throws.

#include <jni.h>

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void) vm;
    (void) reserved;
    return JNI_ERR;
}
