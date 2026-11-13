// The armeabi-v7a libwmbergamot.so: no engine, only a refusal to load.
//
// Marian's ARM path runs its SSE code through sse2neon, which needs AArch64
// (float64x2_t), so there is no 32-bit ARM build of Bergamot. Play still
// wants every split with native code to cover the base's two ABIs, and the
// pins and the F-Droid recipe are simplest with one file per ABI, so 32-bit
// ARM gets this. BergamotNative never loads it (it requires a 64-bit
// process); if anything does, JNI_OnLoad fails and System.loadLibrary throws.

#include <jni.h>

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void) vm;
    (void) reserved;
    return JNI_ERR;
}
