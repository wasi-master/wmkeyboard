// JNI surface for libwebp's WebPAnimEncoder: an animated WebP built from whole
// frames, with each frame stored as only what changed since the one before.
// Kotlin side: com.wasimaster.wmkeyboard.core.media.WebpAnimNative, wrapped by
// AnimatedWebpEncoder in the same package.
//
// A handle is one encoder and the picture its frames are copied into, reused
// for every frame. Frames come either as an android.graphics.Bitmap (ARGB_8888,
// locked with AndroidBitmap_lockPixels, so never copied into Java) or as ARGB
// ints the way Bitmap.getPixels fills them, which is also how a test on a
// desktop JVM feeds it. The encoder keeps its own copy of the last canvas, so
// a frame's pixels are free again as soon as the call returns.
//
// One handle is not safe to use from two threads at once; the Kotlin wrapper
// serialises its calls.

#include <jni.h>

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "webp/encode.h"
#include "webp/mux.h"

#ifdef WMWEBP_ANDROID_BITMAP
#include <android/bitmap.h>
#endif

#define JNI_FN(name) Java_com_wasimaster_wmkeyboard_core_media_WebpAnimNative_##name

typedef struct {
    WebPAnimEncoder* encoder;
    WebPPicture picture;  // use_argb, the canvas size; refilled for every frame
    int width;
    int height;
    int frames;
} Anim;

static Anim* anim_of(jlong handle) { return (Anim*)(intptr_t)handle; }

static void config_for(WebPConfig* config, jfloat quality, jboolean lossless, jint method) {
    WebPConfigInit(config);
    config->lossless = lossless ? 1 : 0;
    config->quality = quality < 0.f ? 0.f : quality > 100.f ? 100.f : quality;
    config->method = method < 0 ? 0 : method > 6 ? 6 : method;
    // Alpha is filtered and compressed on a second thread while the colour
    // planes are encoded.
    config->thread_level = 1;
}

static jboolean add_picture(Anim* anim, jint timestamp_ms, jfloat quality, jboolean lossless, jint method) {
    WebPConfig config;
    config_for(&config, quality, lossless, method);
    if (!WebPValidateConfig(&config)) return JNI_FALSE;
    if (!WebPAnimEncoderAdd(anim->encoder, &anim->picture, timestamp_ms, &config)) return JNI_FALSE;
    anim->frames++;
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeNew)(JNIEnv* env, jobject thiz, jint width, jint height, jint loop_count,
                                          jboolean minimize_size, jboolean allow_mixed, jint kmin, jint kmax) {
    (void)env;
    (void)thiz;
    if (width < 1 || height < 1 || width > WEBP_MAX_DIMENSION || height > WEBP_MAX_DIMENSION) return 0;
    WebPAnimEncoderOptions options;
    if (!WebPAnimEncoderOptionsInit(&options)) return 0;
    options.anim_params.loop_count = loop_count < 0 ? 0 : loop_count;
    options.anim_params.bgcolor = 0x00000000;  // transparent
    options.minimize_size = minimize_size ? 1 : 0;
    options.allow_mixed = allow_mixed ? 1 : 0;
    // A negative distance keeps the library's own choice.
    if (kmin >= 0) options.kmin = kmin;
    if (kmax >= 0) options.kmax = kmax;

    Anim* anim = (Anim*)calloc(1, sizeof(Anim));
    if (anim == NULL) return 0;
    anim->width = width;
    anim->height = height;
    if (!WebPPictureInit(&anim->picture)) {
        free(anim);
        return 0;
    }
    anim->picture.use_argb = 1;
    anim->picture.width = width;
    anim->picture.height = height;
    if (!WebPPictureAlloc(&anim->picture)) {
        free(anim);
        return 0;
    }
    anim->encoder = WebPAnimEncoderNew(width, height, &options);
    if (anim->encoder == NULL) {
        WebPPictureFree(&anim->picture);
        free(anim);
        return 0;
    }
    return (jlong)(intptr_t)anim;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeAddArgb)(JNIEnv* env, jobject thiz, jlong handle, jintArray argb,
                                                 jint timestamp_ms, jfloat quality, jboolean lossless, jint method) {
    (void)thiz;
    Anim* anim = anim_of(handle);
    if (anim == NULL || argb == NULL) return JNI_FALSE;
    const jsize count = (*env)->GetArrayLength(env, argb);
    if (count != (jsize)anim->width * anim->height) return JNI_FALSE;
    // Bitmap.getPixels' ints are unpremultiplied 0xAARRGGBB, exactly what a
    // use_argb picture holds, so the frame is one copy.
    WebPPicture* picture = &anim->picture;
    for (int y = 0; y < anim->height; y++) {
        (*env)->GetIntArrayRegion(env, argb, y * anim->width, anim->width,
                                  (jint*)(picture->argb + (size_t)y * picture->argb_stride));
    }
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    return add_picture(anim, timestamp_ms, quality, lossless, method);
}

#ifdef WMWEBP_ANDROID_BITMAP
// 255 / a in 16.16 fixed point, for undoing premultiplied alpha.
static uint32_t unpremul_scale[256];

static void init_unpremul(void) {
    if (unpremul_scale[255] != 0) return;
    unpremul_scale[0] = 0;
    for (uint32_t a = 1; a < 256; a++) unpremul_scale[a] = ((255u << 16) + a / 2) / a;
}

static inline uint32_t unpremul(uint32_t c, uint32_t scale) {
    const uint32_t v = (c * scale + (1u << 15)) >> 16;
    return v > 255 ? 255 : v;
}
#endif

JNIEXPORT jboolean JNICALL JNI_FN(nativeAddBitmap)(JNIEnv* env, jobject thiz, jlong handle, jobject bitmap,
                                                   jint timestamp_ms, jfloat quality, jboolean lossless,
                                                   jint method) {
    (void)thiz;
#ifdef WMWEBP_ANDROID_BITMAP
    Anim* anim = anim_of(handle);
    if (anim == NULL || bitmap == NULL) return JNI_FALSE;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || (int)info.width != anim->width ||
        (int)info.height != anim->height) {
        return JNI_FALSE;
    }
    // Before API 30 the flags were always 0, which is premultiplied: what
    // every Bitmap is unless setPremultiplied(false) was called on it.
    const int premultiplied = (info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
    if (premultiplied) init_unpremul();
    void* pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == NULL) {
        return JNI_FALSE;
    }
    // In memory an ARGB_8888 bitmap is R, G, B, A bytes, premultiplied.
    WebPPicture* picture = &anim->picture;
    for (int y = 0; y < anim->height; y++) {
        const uint8_t* src = (const uint8_t*)pixels + (size_t)y * info.stride;
        uint32_t* dst = picture->argb + (size_t)y * picture->argb_stride;
        for (int x = 0; x < anim->width; x++, src += 4) {
            uint32_t r = src[0], g = src[1], b = src[2];
            const uint32_t a = src[3];
            if (premultiplied && a != 255) {
                const uint32_t scale = unpremul_scale[a];
                r = unpremul(r, scale);
                g = unpremul(g, scale);
                b = unpremul(b, scale);
            }
            dst[x] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return add_picture(anim, timestamp_ms, quality, lossless, method);
#else
    // A desktop build has no android.graphics; its tests go through the ints.
    (void)env;
    (void)handle;
    (void)bitmap;
    (void)timestamp_ms;
    (void)quality;
    (void)lossless;
    (void)method;
    return JNI_FALSE;
#endif
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nativeAssemble)(JNIEnv* env, jobject thiz, jlong handle,
                                                    jint end_timestamp_ms) {
    (void)thiz;
    Anim* anim = anim_of(handle);
    if (anim == NULL || anim->frames == 0) return NULL;
    // A null frame closes the animation: its timestamp is when the last real
    // frame stops showing.
    if (!WebPAnimEncoderAdd(anim->encoder, NULL, end_timestamp_ms, NULL)) return NULL;
    WebPData data;
    WebPDataInit(&data);
    if (!WebPAnimEncoderAssemble(anim->encoder, &data) || data.size == 0 || data.size > INT32_MAX) {
        WebPDataClear(&data);
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)data.size);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize)data.size, (const jbyte*)data.bytes);
    WebPDataClear(&data);
    return out;
}

JNIEXPORT void JNICALL JNI_FN(nativeDelete)(JNIEnv* env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    Anim* anim = anim_of(handle);
    if (anim == NULL) return;
    WebPAnimEncoderDelete(anim->encoder);
    WebPPictureFree(&anim->picture);
    free(anim);
}
