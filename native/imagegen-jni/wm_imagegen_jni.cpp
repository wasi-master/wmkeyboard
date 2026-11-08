// JNI surface of the sticker generator's MNN runner. Kotlin side:
// com.wasimaster.wmkeyboard.core.imagegen.ImageGenNative.
//
// A handle is one loaded module. Calls on one handle must not overlap (the
// service runs them on one thread); different handles are independent.

#include <jni.h>

#include <string>
#include <vector>

#include "imagegen.hpp"

namespace {

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

std::vector<std::string> strs(JNIEnv* env, jobjectArray a) {
    std::vector<std::string> r;
    if (!a) return r;
    jsize n = env->GetArrayLength(a);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(a, i));
        r.push_back(str(env, s));
        env->DeleteLocalRef(s);
    }
    return r;
}

wmimagegen::Model* model(jlong handle) { return reinterpret_cast<wmimagegen::Model*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_ImageGenNative_nativeLoad(JNIEnv* env, jobject, jstring path,
                                                                       jobjectArray inputs, jobjectArray outputs,
                                                                       jint threads) {
    try {
        auto m = wmimagegen::Model::load(str(env, path), strs(env, inputs), strs(env, outputs), threads);
        return reinterpret_cast<jlong>(m.release());
    } catch (...) {
        return 0;
    }
}

// inputs: one FloatArray or IntArray per declared input; shapes: one IntArray
// each. Returns the first output as NCHW floats, or null; its shape goes into
// outShape when that array is large enough.
// Like nativeLoad, on the GPU when compute is 1 (OpenCL, else Vulkan, else
// the CPU after all) with GPU kernels cached in the file cache. Which one it
// got: nativeBackend.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_ImageGenNative_nativeLoadOn(JNIEnv* env, jobject, jstring path,
                                                                         jobjectArray inputs, jobjectArray outputs,
                                                                         jint threads, jint compute, jstring cache) {
    try {
        auto m = wmimagegen::Model::load(str(env, path), strs(env, inputs), strs(env, outputs), threads, compute,
                                         str(env, cache));
        return reinterpret_cast<jlong>(m.release());
    } catch (...) {
        return 0;
    }
}

// 0 for the CPU, 3 for OpenCL, 7 for Vulkan (MNN's forward types).
JNIEXPORT jint JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_ImageGenNative_nativeBackend(JNIEnv*, jobject, jlong handle) {
    return handle ? model(handle)->backend() : -1;
}

JNIEXPORT jfloatArray JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_ImageGenNative_nativeRun(JNIEnv* env, jobject, jlong handle,
                                                                      jobjectArray inputs, jobjectArray shapes,
                                                                      jintArray outShape) {
    auto* m = model(handle);
    if (!m || !inputs || !shapes) return nullptr;
    jsize n = env->GetArrayLength(inputs);
    if (env->GetArrayLength(shapes) != n) return nullptr;
    jclass floatArray = env->FindClass("[F");
    std::vector<wmimagegen::Tensor> tensors(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto shape = static_cast<jintArray>(env->GetObjectArrayElement(shapes, i));
        auto data = env->GetObjectArrayElement(inputs, i);
        if (!shape || !data) return nullptr;
        jsize rank = env->GetArrayLength(shape);
        tensors[i].shape.resize(static_cast<size_t>(rank));
        env->GetIntArrayRegion(shape, 0, rank, tensors[i].shape.data());
        if (env->IsInstanceOf(data, floatArray)) {
            auto a = static_cast<jfloatArray>(data);
            jsize len = env->GetArrayLength(a);
            tensors[i].floats.resize(static_cast<size_t>(len));
            env->GetFloatArrayRegion(a, 0, len, tensors[i].floats.data());
        } else {
            auto a = static_cast<jintArray>(data);
            jsize len = env->GetArrayLength(a);
            tensors[i].ints.resize(static_cast<size_t>(len));
            env->GetIntArrayRegion(a, 0, len, reinterpret_cast<jint*>(tensors[i].ints.data()));
        }
        env->DeleteLocalRef(shape);
        env->DeleteLocalRef(data);
    }
    std::vector<float> out;
    std::vector<int> dims;
    bool ok = false;
    try {
        ok = m->run(tensors, out, dims);
    } catch (...) {
        ok = false;
    }
    if (!ok) return nullptr;
    if (outShape) {
        jsize cap = env->GetArrayLength(outShape);
        std::vector<jint> d(static_cast<size_t>(cap), 0);
        for (size_t i = 0; i < dims.size() && i < d.size(); ++i) d[i] = dims[i];
        env->SetIntArrayRegion(outShape, 0, cap, d.data());
    }
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (!result) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_ImageGenNative_nativeFree(JNIEnv*, jobject, jlong handle) {
    delete model(handle);
}

JNIEXPORT jstring JNICALL
Java_com_wasimaster_wmkeyboard_core_imagegen_ImageGenNative_nativeDescribe(JNIEnv* env, jobject, jstring path) {
    std::string json;
    try {
        json = wmimagegen::describe(str(env, path));
    } catch (...) {
        json.clear();
    }
    if (json.empty()) return nullptr;
    return env->NewStringUTF(json.c_str());
}

}  // extern "C"
