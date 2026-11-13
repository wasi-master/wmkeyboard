// The armeabi-v7a libwmllama_hexagon.so in :feature:llamanpu's Play
// split: no engine, only a refusal to load.
//
// The Hexagon build exists for arm64 only, but bundletool rejects a bundle whose modules
// cover different ABI sets than the base's two. This file makes the
// split cover the base's ABIs. It lives in the module's own src/main/jniLibs, which only
// the Play bundle packages: the gate's library directory in native/gates.json,
// the pins and F-Droid never see it. LlamaNative loads it only on an
// arm64 Snapdragon with Compute on NPU; if anything loads it, JNI_OnLoad fails and
// System.loadLibrary throws.
//
// Built with the NDK (r29) by:
//   for t in armv7a-linux-androideabi28:armeabi-v7a; do
//     clang --target=${t%%:*} -O2 -shared -fPIC -nostdlib++ -Wl,--build-id=sha1 \
//       -Wl,-z,max-page-size=16384 -o libwmllama_hexagon.so stub.c
//     llvm-strip --strip-unneeded libwmllama_hexagon.so
//     mv libwmllama_hexagon.so ../src/main/jniLibs/${t##*:}/
//   done

#include <jni.h>

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void) vm;
    (void) reserved;
    return JNI_ERR;
}
