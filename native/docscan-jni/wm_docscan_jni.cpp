// JNI surface of the document scanner: find the page in a bitmap, flatten
// it into another, clean one up in place. Kotlin side:
// com.wasimaster.wmkeyboard.core.docscan.DocScanNative.
//
// Bitmaps cross as android.graphics.Bitmap, ARGB_8888 only, locked for the
// length of one call and wrapped as OpenCV matrices without a copy. Nothing
// is kept between calls, so any thread may call at any time.

#include <android/bitmap.h>
#include <jni.h>

#include <memory>
#include <vector>

#include "docscan.hpp"
#include "themecopy.hpp"

namespace {

// A locked ARGB_8888 bitmap as an RGBA matrix over its own pixels.
class Locked {
public:
    Locked(JNIEnv* env, jobject bitmap) : env_(env), bitmap_(bitmap) {
        AndroidBitmapInfo info;
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return;
        locked_ = true;
        mat = cv::Mat(static_cast<int>(info.height), static_cast<int>(info.width), CV_8UC4, pixels, info.stride);
    }
    ~Locked() {
        if (locked_) AndroidBitmap_unlockPixels(env_, bitmap_);
    }
    Locked(const Locked&) = delete;
    Locked& operator=(const Locked&) = delete;

    bool ok() const { return locked_ && !mat.empty(); }
    cv::Mat mat;

private:
    JNIEnv* env_;
    jobject bitmap_;
    bool locked_ = false;
};

}  // namespace

extern "C" {

// Eight floats, the corners clockwise from top left as x, y pairs, then the
// detector's confidence; null when it found no page.
JNIEXPORT jfloatArray JNICALL
Java_com_wasimaster_wmkeyboard_core_docscan_DocScanNative_nativeDetect(JNIEnv* env, jobject, jobject bitmap) {
    wmdocscan::Quad quad;
    {
        Locked src(env, bitmap);
        if (!src.ok()) return nullptr;
        try {
            quad = wmdocscan::detectPage(src.mat);
        } catch (...) {
            return nullptr;
        }
    }
    if (!quad.found) return nullptr;
    jfloat values[9];
    for (int i = 0; i < 4; ++i) {
        values[i * 2] = quad.corners[i].x;
        values[i * 2 + 1] = quad.corners[i].y;
    }
    values[8] = quad.score;
    jfloatArray out = env->NewFloatArray(9);
    if (out) env->SetFloatArrayRegion(out, 0, 9, values);
    return out;
}

// The page inside corners (eight floats, in src pixels, clockwise from top
// left) drawn flat over the whole of dst.
JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_docscan_DocScanNative_nativeWarp(
        JNIEnv* env, jobject, jobject src, jfloatArray corners, jobject dst) {
    if (env->GetArrayLength(corners) < 8) return JNI_FALSE;
    jfloat c[8];
    env->GetFloatArrayRegion(corners, 0, 8, c);
    const cv::Point2f quad[4] = {{c[0], c[1]}, {c[2], c[3]}, {c[4], c[5]}, {c[6], c[7]}};
    Locked from(env, src);
    Locked to(env, dst);
    if (!from.ok() || !to.ok()) return JNI_FALSE;
    try {
        wmdocscan::warpPage(from.mat, quad, to.mat);
    } catch (...) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// mode: 1 colour, 2 grey, 3 black and white (wmdocscan::Enhance).
JNIEXPORT jboolean JNICALL
Java_com_wasimaster_wmkeyboard_core_docscan_DocScanNative_nativeEnhance(
        JNIEnv* env, jobject, jobject bitmap, jint mode) {
    Locked image(env, bitmap);
    if (!image.ok()) return JNI_FALSE;
    try {
        wmdocscan::enhance(image.mat, mode);
    } catch (...) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Label-sized things in the bitmap, four ints each: x y width height.
JNIEXPORT jintArray JNICALL
Java_com_wasimaster_wmkeyboard_core_docscan_DocScanNative_nativeLabelCandidates(
        JNIEnv* env, jobject, jobject bitmap) {
    std::vector<cv::Rect> found;
    {
        Locked src(env, bitmap);
        if (!src.ok()) return nullptr;
        try {
            found = wmdocscan::labelCandidates(src.mat);
        } catch (...) {
            return nullptr;
        }
    }
    std::vector<jint> flat;
    for (const auto& r : found) {
        flat.push_back(r.x);
        flat.push_back(r.y);
        flat.push_back(r.width);
        flat.push_back(r.height);
    }
    jintArray out = env->NewIntArray(static_cast<jsize>(flat.size()));
    if (out && !flat.empty()) env->SetIntArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    return out;
}

// A keyboard theme read off a screenshot: the theme's JSON, then for each picture
// it needs that picture's name and an int array of width, height and ARGB pixels.
// Null when the bitmap shows no key grid.
JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_docscan_DocScanNative_nativeCopyTheme(
        JNIEnv* env, jobject, jobjectArray bitmaps, jfloat widthDp, jobjectArray texts, jintArray boxes) {
    // Each run of text read in the first bitmap, with four ints in boxes: x y w h.
    std::vector<wmdocscan::ReadText> letters;
    if (texts && boxes) {
        const jsize count = env->GetArrayLength(texts);
        if (env->GetArrayLength(boxes) == count * 4) {
            std::vector<jint> b(static_cast<size_t>(count) * 4);
            env->GetIntArrayRegion(boxes, 0, count * 4, b.data());
            for (jsize i = 0; i < count; ++i) {
                auto text = static_cast<jstring>(env->GetObjectArrayElement(texts, i));
                if (!text) continue;
                const char* chars = env->GetStringUTFChars(text, nullptr);
                if (chars) {
                    letters.push_back({chars, {b[i * 4], b[i * 4 + 1], b[i * 4 + 2], b[i * 4 + 3]}});
                    env->ReleaseStringUTFChars(text, chars);
                }
                env->DeleteLocalRef(text);
            }
        }
    }
    wmdocscan::ThemeCopy copy;
    {
        const jsize pictures = env->GetArrayLength(bitmaps);
        if (pictures < 1) return nullptr;
        std::vector<std::unique_ptr<Locked>> locked;
        for (jsize i = 0; i < pictures; ++i) {
            locked.push_back(std::make_unique<Locked>(env, env->GetObjectArrayElement(bitmaps, i)));
            if (!locked.back()->ok()) return nullptr;
        }
        std::vector<cv::Mat> others;
        for (jsize i = 1; i < pictures; ++i) others.push_back(locked[i]->mat);
        try {
            copy = wmdocscan::copyTheme(locked[0]->mat, widthDp, false, letters, others);
        } catch (...) {
            return nullptr;
        }
    }
    if (!copy.found) return nullptr;
    jclass object = env->FindClass("java/lang/Object");
    if (!object) return nullptr;
    const auto size = static_cast<jsize>(1 + copy.images.size() * 2);
    jobjectArray out = env->NewObjectArray(size, object, nullptr);
    if (!out) return nullptr;
    env->SetObjectArrayElement(out, 0, env->NewStringUTF(copy.json.c_str()));
    jsize at = 1;
    for (const auto& [name, image] : copy.images) {
        const auto count = static_cast<jsize>(2 + image.total());
        jintArray pixels = env->NewIntArray(count);
        if (!pixels) return nullptr;
        jint* to = env->GetIntArrayElements(pixels, nullptr);
        to[0] = image.cols;
        to[1] = image.rows;
        jint* next = to + 2;
        for (int y = 0; y < image.rows; ++y) {
            const auto* row = image.ptr<cv::Vec4b>(y);
            for (int x = 0; x < image.cols; ++x) {
                *next++ = static_cast<jint>(static_cast<uint32_t>(row[x][3]) << 24 | static_cast<uint32_t>(row[x][0]) << 16 |
                                            static_cast<uint32_t>(row[x][1]) << 8 | row[x][2]);
            }
        }
        env->ReleaseIntArrayElements(pixels, to, 0);
        env->SetObjectArrayElement(out, at++, env->NewStringUTF(name.c_str()));
        env->SetObjectArrayElement(out, at++, pixels);
        env->DeleteLocalRef(pixels);
    }
    return out;
}

}  // extern "C"
