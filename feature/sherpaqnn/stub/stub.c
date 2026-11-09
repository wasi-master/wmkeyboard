// The armeabi-v7a and x86_64 libwmsherpa_qnn.so in :feature:sherpaqnn's Play
// split: no recognizer, only a refusal to load.
//
// sherpa-onnx's QNN build exists for arm64 only, but bundletool rejects a
// bundle whose modules cover different ABI sets than the base's three. These
// two files make the split cover them. They live in the module's own
// src/main/jniLibs, which only the Play bundle packages: the gate's library
// directory in native/gates.json, the pins and F-Droid never see them.
// SherpaQnnNative loads the QNN build only on a Snapdragon arm64 phone; if
// anything loads one of these, JNI_OnLoad fails and System.loadLibrary throws.
//
// native/sherpa-onnx-jni/build.sh builds them beside the arm64 library; by
// hand, with the NDK (r27):
//   for t in armv7a-linux-androideabi28:armeabi-v7a x86_64-linux-android28:x86_64; do
//     clang --target=${t%%:*} -O2 -shared -fPIC -nostdlib++ -Wl,--build-id=sha1 \
//       -Wl,-z,max-page-size=16384 -o libwmsherpa_qnn.so stub.c
//     llvm-strip --strip-unneeded libwmsherpa_qnn.so
//     mv libwmsherpa_qnn.so ../src/main/jniLibs/${t##*:}/
//   done

#include <jni.h>

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void) vm;
    (void) reserved;
    return JNI_ERR;
}
