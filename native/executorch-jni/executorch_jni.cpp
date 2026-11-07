// JNI surface for ExecuTorch: open a .pte, describe a method's tensors, run
// a method. Everything about a particular model (Asr4All's streaming and
// punctuation pass, Parakeet's transducer loop, Moonshine's decoder loop) is
// Kotlin, in com.wasimaster.wmkeyboard.core.voice, which keeps this file a
// plain tensor pipe that does not care what the model is.
//
// Tensors cross as primitive arrays: float32 and float16 as FloatArray, int64
// as LongArray, int32 as IntArray, bool and uint8 as ByteArray. The dtype
// a method declares decides the conversion in both directions. An output
// that is a plain number rather than a tensor (a program's constant methods,
// such as Parakeet's blank id) comes back as a one-element array of shape [].

#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <executorch/extension/module/module.h>
#include <executorch/extension/tensor/tensor.h>
#include <executorch/runtime/platform/runtime.h>

using executorch::aten::ScalarType;
using executorch::extension::Module;
using executorch::extension::TensorPtr;
using executorch::runtime::EValue;
using executorch::runtime::Error;
using executorch::runtime::HierarchicalAllocator;
using executorch::runtime::MethodMeta;
using executorch::runtime::Span;

namespace {

// A method's memory-planned tensors, in buffers with [kTensorPadding] to spare.
struct PlannedMemory {
    std::vector<std::vector<uint8_t>> buffers;
    std::vector<Span<uint8_t>> spans;
    std::unique_ptr<HierarchicalAllocator> allocator;
};

struct Session {
    // Before [module]: a loaded method keeps pointing into these.
    std::unordered_map<std::string, std::unique_ptr<PlannedMemory>> planned;
    std::unique_ptr<Module> module;
    std::mutex mutex;
};

void throwJava(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// IEEE half <-> float, without depending on compiler __fp16 support.
uint16_t toHalf(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000;
    int32_t exp = static_cast<int32_t>((x >> 23) & 0xff) - 127 + 15;
    uint32_t mant = x & 0x7fffff;
    if (exp <= 0) return static_cast<uint16_t>(sign);
    if (exp >= 31) return static_cast<uint16_t>(sign | 0x7c00);
    return static_cast<uint16_t>(sign | (exp << 10) | (mant >> 13));
}

float fromHalf(uint16_t h) {
    const uint32_t sign = (h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1f;
    uint32_t mant = h & 0x3ff;
    uint32_t x;
    if (exp == 0) {
        if (mant == 0) {
            x = sign;
        } else {
            exp = 127 - 15 + 1;
            while (!(mant & 0x400)) {
                mant <<= 1;
                --exp;
            }
            mant &= 0x3ff;
            x = sign | (exp << 23) | (mant << 13);
        }
    } else if (exp == 31) {
        x = sign | 0x7f800000 | (mant << 13);
    } else {
        x = sign | ((exp - 15 + 127) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &x, 4);
    return f;
}

// XNNPACK reads whole vector registers, so it may read this far past the end
// of any buffer it is given (XNN_EXTRA_BYTES) and asks its callers to allocate
// that much more. Without it, a large allocation ends on a guard page under
// Scudo, and a transpose loading 16 bytes at the last float faults (#982):
// Parakeet's mel is big enough from about 1.3 s of speech. The inputs and the
// memory-planned tensors both go to XNNPACK as they are, so both need it;
// Module's own planned buffers are exact, hence [loadPadded].
constexpr size_t kTensorPadding = 128;

// [dtype, rank, dims...] for one tensor.
void describe(std::vector<jlong>& out, ScalarType type, executorch::runtime::Span<const int32_t> sizes) {
    out.push_back(static_cast<jlong>(type));
    out.push_back(static_cast<jlong>(sizes.size()));
    for (auto d : sizes) out.push_back(d);
}

// Loads [method] over padded planned memory, once. Module::execute would
// otherwise load it over buffers of its own.
Error loadPadded(Session& s, const std::string& method, const MethodMeta& meta) {
    if (s.module->is_method_loaded(method)) return Error::Ok;
    auto memory = std::make_unique<PlannedMemory>();
    const size_t count = meta.num_memory_planned_buffers();
    memory->buffers.reserve(count);
    memory->spans.reserve(count);
    for (size_t i = 0; i < count; ++i) {
        auto size = meta.memory_planned_buffer_size(i);
        if (!size.ok()) return size.error();
        memory->buffers.emplace_back(static_cast<size_t>(*size) + kTensorPadding);
        memory->spans.emplace_back(memory->buffers.back().data(), static_cast<size_t>(*size));
    }
    memory->allocator = std::make_unique<HierarchicalAllocator>(
        Span<Span<uint8_t>>(memory->spans.data(), memory->spans.size()));
    const Error loaded = s.module->load_method(method, memory->allocator.get());
    if (loaded == Error::Ok) s.planned[method] = std::move(memory);
    return loaded;
}

}  // namespace

/**
 * Whether this is the QNN build (libwmexecutorch_qnn), whose programs may
 * hand parts of themselves to the Snapdragon NPU.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_executorch_EtNative_nativeHasQnn(JNIEnv*, jclass) {
#ifdef WM_QNN
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

/**
 * Where the DSP finds the HTP skel: the directory the NPU runtime was
 * unpacked into, ahead of the system's own. The QNN libraries themselves are
 * already loaded by then (Kotlin preloads them by path), so the backend's
 * open of libQnnHtp.so by name finds them. A no-op in the plain build.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_executorch_EtNative_nativeSetQnnDirectory(JNIEnv* env, jclass, jstring dir) {
#ifdef WM_QNN
    const char* d = env->GetStringUTFChars(dir, nullptr);
    if (!d) return;
    std::string adsp = std::string(d) + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
    env->ReleaseStringUTFChars(dir, d);
#else
    (void) env;
    (void) dir;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_executorch_EtNative_nativeOpen(JNIEnv* env, jclass, jstring path) {
    executorch::runtime::runtime_init();
    auto s = std::make_unique<Session>();
    s->module = std::make_unique<Module>(str(env, path), Module::LoadMode::Mmap);
    if (s->module->load() != Error::Ok) return 0;
    return reinterpret_cast<jlong>(s.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_executorch_EtNative_nativeClose(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        s->module.reset();
    }
    delete s;
}

/**
 * The tensors [method] takes and returns, flattened: number of inputs, number
 * of outputs, then [dtype, rank, dims...] for each input and each output that
 * is a tensor (a non-tensor slot reads as dtype -1, rank 0). Null when the
 * program has no such method.
 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_executorch_EtNative_nativeMethodMeta(
    JNIEnv* env, jclass, jlong handle, jstring method) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return nullptr;
    std::lock_guard<std::mutex> lock(s->mutex);
    auto meta = s->module->method_meta(str(env, method));
    if (!meta.ok()) return nullptr;
    std::vector<jlong> out;
    out.push_back(static_cast<jlong>(meta->num_inputs()));
    out.push_back(static_cast<jlong>(meta->num_outputs()));
    for (size_t i = 0; i < meta->num_inputs(); ++i) {
        auto info = meta->input_tensor_meta(i);
        if (info.ok()) describe(out, info->scalar_type(), info->sizes());
        else { out.push_back(-1); out.push_back(0); }
    }
    for (size_t i = 0; i < meta->num_outputs(); ++i) {
        auto info = meta->output_tensor_meta(i);
        if (info.ok()) describe(out, info->scalar_type(), info->sizes());
        else { out.push_back(-1); out.push_back(0); }
    }
    jlongArray arr = env->NewLongArray(static_cast<jsize>(out.size()));
    env->SetLongArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

/**
 * Runs [method] on [inputs] (one primitive array per input, in the shapes
 * [shapes] gives) and returns its outputs as data, shape, data, shape...:
 * each data array in the type the class comment lists, each shape a LongArray.
 * Throws IllegalStateException when the method fails.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_voice_executorch_EtNative_nativeExecute(
    JNIEnv* env, jclass, jlong handle, jstring jmethod, jobjectArray inputs, jobjectArray shapes) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) {
        throwJava(env, "model is not open");
        return nullptr;
    }
    const std::string method = str(env, jmethod);
    std::lock_guard<std::mutex> lock(s->mutex);
    auto meta = s->module->method_meta(method);
    if (!meta.ok()) {
        throwJava(env, "no method " + method);
        return nullptr;
    }
    const jsize n = env->GetArrayLength(inputs);
    if (static_cast<size_t>(n) != meta->num_inputs()) {
        throwJava(env, method + ": wrong number of inputs");
        return nullptr;
    }
    const Error loaded = loadPadded(*s, method, *meta);
    if (loaded != Error::Ok) {
        throwJava(env, method + ": could not load, error " + std::to_string(static_cast<int>(loaded)));
        return nullptr;
    }

    // Owned buffers for the inputs; the tensors point into them.
    std::vector<std::vector<uint8_t>> buffers(n);
    std::vector<TensorPtr> tensors;
    std::vector<EValue> values;
    for (jsize i = 0; i < n; ++i) {
        auto info = meta->input_tensor_meta(i);
        if (!info.ok()) {
            throwJava(env, method + ": input is not a tensor");
            return nullptr;
        }
        const ScalarType type = info->scalar_type();
        auto jshape = static_cast<jlongArray>(env->GetObjectArrayElement(shapes, i));
        const jsize rank = env->GetArrayLength(jshape);
        std::vector<jlong> dims(rank);
        env->GetLongArrayRegion(jshape, 0, rank, dims.data());
        env->DeleteLocalRef(jshape);
        std::vector<executorch::aten::SizesType> sizes(dims.begin(), dims.end());
        size_t count = 1;
        for (auto d : dims) count *= static_cast<size_t>(d);

        jobject data = env->GetObjectArrayElement(inputs, i);
        auto& buf = buffers[i];
        switch (type) {
            case ScalarType::Float: {
                buf.resize(count * 4 + kTensorPadding);
                env->GetFloatArrayRegion(static_cast<jfloatArray>(data), 0, static_cast<jsize>(count),
                                         reinterpret_cast<jfloat*>(buf.data()));
                break;
            }
            case ScalarType::Half: {
                std::vector<jfloat> f(count);
                env->GetFloatArrayRegion(static_cast<jfloatArray>(data), 0, static_cast<jsize>(count), f.data());
                buf.resize(count * 2 + kTensorPadding);
                auto* h = reinterpret_cast<uint16_t*>(buf.data());
                for (size_t k = 0; k < count; ++k) h[k] = toHalf(f[k]);
                break;
            }
            case ScalarType::Long: {
                buf.resize(count * 8 + kTensorPadding);
                env->GetLongArrayRegion(static_cast<jlongArray>(data), 0, static_cast<jsize>(count),
                                        reinterpret_cast<jlong*>(buf.data()));
                break;
            }
            case ScalarType::Int: {
                buf.resize(count * 4 + kTensorPadding);
                env->GetIntArrayRegion(static_cast<jintArray>(data), 0, static_cast<jsize>(count),
                                       reinterpret_cast<jint*>(buf.data()));
                break;
            }
            case ScalarType::Bool:
            case ScalarType::Byte: {
                buf.resize(count + kTensorPadding);
                env->GetByteArrayRegion(static_cast<jbyteArray>(data), 0, static_cast<jsize>(count),
                                        reinterpret_cast<jbyte*>(buf.data()));
                break;
            }
            default:
                env->DeleteLocalRef(data);
                throwJava(env, method + ": unsupported input type");
                return nullptr;
        }
        env->DeleteLocalRef(data);
        if (env->ExceptionCheck()) return nullptr;
        tensors.push_back(executorch::extension::from_blob(buf.data(), sizes, type));
        values.emplace_back(*tensors.back());
    }

    auto result = s->module->execute(method, values);
    if (!result.ok()) {
        throwJava(env, method + ": execution failed, error " + std::to_string(static_cast<int>(result.error())));
        return nullptr;
    }
    const auto& outs = *result;

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(outs.size() * 2), objectClass, nullptr);
    for (size_t i = 0; i < outs.size(); ++i) {
        if (!outs[i].isTensor()) {
            jobject value = nullptr;
            if (outs[i].isInt()) {
                const jlong v = static_cast<jlong>(outs[i].toInt());
                jlongArray a = env->NewLongArray(1);
                env->SetLongArrayRegion(a, 0, 1, &v);
                value = a;
            } else if (outs[i].isDouble()) {
                const jfloat v = static_cast<jfloat>(outs[i].toDouble());
                jfloatArray a = env->NewFloatArray(1);
                env->SetFloatArrayRegion(a, 0, 1, &v);
                value = a;
            } else if (outs[i].isBool()) {
                const jbyte v = outs[i].toBool() ? 1 : 0;
                jbyteArray a = env->NewByteArray(1);
                env->SetByteArrayRegion(a, 0, 1, &v);
                value = a;
            }
            if (value) {
                env->SetObjectArrayElement(out, static_cast<jsize>(i * 2), value);
                env->SetObjectArrayElement(out, static_cast<jsize>(i * 2 + 1), env->NewLongArray(0));
                env->DeleteLocalRef(value);
            }
            continue;
        }
        const auto& t = outs[i].toTensor();
        const size_t count = static_cast<size_t>(t.numel());
        jobject data = nullptr;
        switch (t.scalar_type()) {
            case ScalarType::Float: {
                jfloatArray a = env->NewFloatArray(static_cast<jsize>(count));
                env->SetFloatArrayRegion(a, 0, static_cast<jsize>(count), t.const_data_ptr<float>());
                data = a;
                break;
            }
            case ScalarType::Half: {
                std::vector<jfloat> f(count);
                const auto* h = reinterpret_cast<const uint16_t*>(t.const_data_ptr());
                for (size_t k = 0; k < count; ++k) f[k] = fromHalf(h[k]);
                jfloatArray a = env->NewFloatArray(static_cast<jsize>(count));
                env->SetFloatArrayRegion(a, 0, static_cast<jsize>(count), f.data());
                data = a;
                break;
            }
            case ScalarType::Long: {
                jlongArray a = env->NewLongArray(static_cast<jsize>(count));
                env->SetLongArrayRegion(a, 0, static_cast<jsize>(count),
                                        reinterpret_cast<const jlong*>(t.const_data_ptr<int64_t>()));
                data = a;
                break;
            }
            case ScalarType::Int: {
                jintArray a = env->NewIntArray(static_cast<jsize>(count));
                env->SetIntArrayRegion(a, 0, static_cast<jsize>(count),
                                       reinterpret_cast<const jint*>(t.const_data_ptr<int32_t>()));
                data = a;
                break;
            }
            case ScalarType::Bool:
            case ScalarType::Byte: {
                jbyteArray a = env->NewByteArray(static_cast<jsize>(count));
                env->SetByteArrayRegion(a, 0, static_cast<jsize>(count),
                                        reinterpret_cast<const jbyte*>(t.const_data_ptr()));
                data = a;
                break;
            }
            default:
                break;
        }
        std::vector<jlong> dims(t.sizes().begin(), t.sizes().end());
        jlongArray shape = env->NewLongArray(static_cast<jsize>(dims.size()));
        env->SetLongArrayRegion(shape, 0, static_cast<jsize>(dims.size()), dims.data());
        env->SetObjectArrayElement(out, static_cast<jsize>(i * 2), data);
        env->SetObjectArrayElement(out, static_cast<jsize>(i * 2 + 1), shape);
        if (data) env->DeleteLocalRef(data);
        env->DeleteLocalRef(shape);
    }
    return out;
}
