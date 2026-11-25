// JNI surface for Skia's Skottie: loads a Lottie animation and draws any
// frame of it into a bitmap, on the CPU.
// Kotlin side: com.wasimaster.wmkeyboard.core.media.skottie.SkottieNative,
// wrapped by SkottieAnimation in the same package.
//
// Skottie is here for the templates Lottie (airbnb) cannot draw faithfully:
// those whose text layers use Skottie's own auto-fit extensions (`rs` resize,
// `mf`/`xf` minimum and maximum size, `ml` maximum lines, `vj` vertical
// alignment). Those are part of Skottie's text adapter and always on. Text is
// written into the JSON by the caller before load, so there is no text API.
//
// Text is shaped with HarfBuzz and broken into lines by SkUnicode on
// libgrapheme (with ICU's bidi code only), so Bengali conjuncts and Arabic
// joining come out right. Typefaces come first from the font files the caller
// names, then from the system: Android's fonts.xml on a phone, nothing on a
// desktop build. The caller's files also take part in fallback, so a Latin
// template can draw Bengali text from a Bengali font passed alongside it.
//
// A handle is one animation. Not safe for two threads at once: seeking moves
// the animation's state. The Kotlin wrapper serialises its calls.

#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "include/core/SkCanvas.h"
#include "include/core/SkColor.h"
#include "include/core/SkData.h"
#include "include/core/SkFontMgr.h"
#include "include/core/SkFontStyle.h"
#include "include/core/SkImageInfo.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkRect.h"
#include "include/core/SkStream.h"
#include "include/core/SkString.h"
#include "include/core/SkTypeface.h"
#include "modules/skottie/include/Skottie.h"
#include "modules/skresources/include/SkResources.h"
#include "modules/skshaper/utils/FactoryHelpers.h"

#if defined(__ANDROID__)
#include <android/bitmap.h>
#include <android/log.h>
#include "include/ports/SkFontMgr_android.h"
#include "include/ports/SkFontScanner_FreeType.h"
#else
#include "include/ports/SkFontMgr_empty.h"
#endif

#define JNI_FN(name) Java_com_wasimaster_wmkeyboard_core_media_skottie_SkottieNative_##name

namespace {

// The system's fonts: on a phone, what /system/etc/fonts.xml lists, with its
// fallback chain per script and language. Made once; it parses that file.
sk_sp<SkFontMgr> systemFonts() {
    static sk_sp<SkFontMgr> fonts = [] {
#if defined(__ANDROID__)
        sk_sp<SkFontMgr> mgr = SkFontMgr_New_Android(nullptr, SkFontScanner_Make_FreeType());
#else
        sk_sp<SkFontMgr> mgr = SkFontMgr_New_Custom_Empty();
#endif
        return mgr ? mgr : SkFontMgr::RefEmpty();
    }();
    return fonts;
}

struct NamedFace {
    SkString name;
    sk_sp<SkTypeface> face;
};

bool sameName(const char* a, const SkString& b) {
    return a != nullptr && strcasecmp(a, b.c_str()) == 0;
}

// The caller's typefaces in front of the system's. A template asks for a font
// by its `fName` (through the resource provider) or its `fFamily` (through
// here), and both find a caller's font registered under either. Fallback for
// a character the template's font lacks tries the caller's fonts in order,
// then the system's.
class CallerFontMgr final : public SkFontMgr {
public:
    CallerFontMgr(sk_sp<SkFontMgr> system, std::vector<NamedFace> faces)
            : fSystem(std::move(system)), fFaces(std::move(faces)) {}

    sk_sp<SkTypeface> byName(const char name[]) const {
        if (name == nullptr) return nullptr;
        for (const NamedFace& f : fFaces) {
            if (sameName(name, f.name)) return f.face;
        }
        // The file's own names, for a caller that passed no useful one.
        for (const NamedFace& f : fFaces) {
            SkString family;
            f.face->getFamilyName(&family);
            if (sameName(name, family)) return f.face;
            SkString postscript;
            if (f.face->getPostScriptName(&postscript) && sameName(name, postscript)) return f.face;
        }
        return nullptr;
    }

protected:
    int onCountFamilies() const override { return fSystem->countFamilies(); }
    void onGetFamilyName(int index, SkString* name) const override { fSystem->getFamilyName(index, name); }
    sk_sp<SkFontStyleSet> onCreateStyleSet(int index) const override { return fSystem->createStyleSet(index); }
    sk_sp<SkFontStyleSet> onMatchFamily(const char family[]) const override { return fSystem->matchFamily(family); }

    sk_sp<SkTypeface> onMatchFamilyStyle(const char family[], const SkFontStyle& style) const override {
        if (sk_sp<SkTypeface> mine = byName(family)) return mine;
        return fSystem->matchFamilyStyle(family, style);
    }

    sk_sp<SkTypeface> onMatchFamilyStyleCharacter(const char family[], const SkFontStyle& style,
                                                  const char* bcp47[], int bcp47Count,
                                                  SkUnichar character) const override {
        for (const NamedFace& f : fFaces) {
            if (f.face->unicharToGlyph(character) != 0) return f.face;
        }
        return fSystem->matchFamilyStyleCharacter(family, style, bcp47, bcp47Count, character);
    }

    sk_sp<SkTypeface> onMakeFromData(sk_sp<SkData> data, int index) const override {
        return fSystem->makeFromData(std::move(data), index);
    }
    sk_sp<SkTypeface> onMakeFromStreamIndex(std::unique_ptr<SkStreamAsset> stream, int index) const override {
        return fSystem->makeFromStream(std::move(stream), index);
    }
    sk_sp<SkTypeface> onMakeFromStreamArgs(std::unique_ptr<SkStreamAsset> stream,
                                           const SkFontArguments& args) const override {
        return fSystem->makeFromStream(std::move(stream), args);
    }
    sk_sp<SkTypeface> onMakeFromFile(const char path[], int index) const override {
        return fSystem->makeFromFile(path, index);
    }
    sk_sp<SkTypeface> onLegacyMakeTypeface(const char family[], SkFontStyle style) const override {
        if (sk_sp<SkTypeface> mine = byName(family)) return mine;
        sk_sp<SkTypeface> system = fSystem->legacyMakeTypeface(family, style);
        // Nothing at all on a desktop build: the caller's first font is the default.
        if (!system && !fFaces.empty()) return fFaces.front().face;
        return system;
    }

private:
    const sk_sp<SkFontMgr> fSystem;
    const std::vector<NamedFace> fFaces;
};

// Typefaces by `fName` from the caller's fonts; images and nested animations
// from the resource directory, data: URIs included.
class Resources final : public skresources::ResourceProvider {
public:
    Resources(sk_sp<skresources::ResourceProvider> files, sk_sp<CallerFontMgr> fonts)
            : fFiles(std::move(files)), fFonts(std::move(fonts)) {}

    sk_sp<SkData> load(const char path[], const char name[]) const override {
        return fFiles ? fFiles->load(path, name) : nullptr;
    }
    sk_sp<skresources::ImageAsset> loadImageAsset(const char path[], const char name[],
                                                  const char id[]) const override {
        return fFiles ? fFiles->loadImageAsset(path, name, id) : nullptr;
    }
    sk_sp<SkTypeface> loadTypeface(const char name[], const char url[]) const override {
        if (sk_sp<SkTypeface> mine = fFonts->byName(name)) return mine;
        return fFiles ? fFiles->loadTypeface(name, url) : nullptr;
    }

private:
    const sk_sp<skresources::ResourceProvider> fFiles;
    const sk_sp<CallerFontMgr> fFonts;
};

class Log final : public skottie::Logger {
public:
    void log(Level level, const char message[], const char* /*json*/) override {
#if defined(__ANDROID__)
        __android_log_print(level == Level::kError ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG, "WMSkottie", "%s",
                            message);
#else
        (void)level;
        (void)message;
#endif
    }
};

struct Anim {
    sk_sp<skottie::Animation> animation;
};

Anim* animOf(jlong handle) { return reinterpret_cast<Anim*>(static_cast<intptr_t>(handle)); }

std::string utf8Of(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    // GetStringUTFChars gives modified UTF-8, which spells a character outside
    // the BMP (an emoji) as two surrogates; Skottie's JSON reader wants real
    // UTF-8. So the string goes out as UTF-16 and is encoded here.
    const jsize n = env->GetStringLength(s);
    const jchar* units = env->GetStringCritical(s, nullptr);
    if (units == nullptr) return {};
    std::string out;
    out.reserve(static_cast<size_t>(n) + n / 2);
    for (jsize i = 0; i < n; i++) {
        uint32_t c = units[i];
        if (c >= 0xD800 && c < 0xDC00 && i + 1 < n && units[i + 1] >= 0xDC00 && units[i + 1] < 0xE000) {
            c = 0x10000 + ((c - 0xD800) << 10) + (units[i + 1] - 0xDC00);
            i++;
        } else if (c >= 0xD800 && c < 0xE000) {
            c = 0xFFFD;  // a lone surrogate
        }
        if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (c >> 18)));
            out.push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }
    env->ReleaseStringCritical(s, units);
    return out;
}

// Plain UTF-8 for a path or a family name: modified UTF-8 is fine there.
SkString skStringOf(JNIEnv* env, jstring s) {
    if (s == nullptr) return SkString();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return SkString();
    SkString out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

void draw(const skottie::Animation& animation, SkCanvas* canvas, int width, int height) {
    canvas->clear(SK_ColorTRANSPARENT);
    const SkRect dst = SkRect::MakeIWH(width, height);
    animation.render(canvas, &dst);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL JNI_FN(load)(JNIEnv* env, jobject /*thiz*/, jstring json, jstring resourceDir,
                                     jobjectArray fontPaths, jobjectArray fontFamilies) {
    if (json == nullptr) return 0;
    const std::string text = utf8Of(env, json);
    if (text.empty()) return 0;

    std::vector<NamedFace> faces;
    const sk_sp<SkFontMgr> system = systemFonts();
    const jsize paths = fontPaths ? env->GetArrayLength(fontPaths) : 0;
    const jsize names = fontFamilies ? env->GetArrayLength(fontFamilies) : 0;
    for (jsize i = 0; i < paths; i++) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(fontPaths, i));
        auto name = i < names ? static_cast<jstring>(env->GetObjectArrayElement(fontFamilies, i)) : nullptr;
        SkString file = skStringOf(env, path);
        SkString family = skStringOf(env, name);
        if (path) env->DeleteLocalRef(path);
        if (name) env->DeleteLocalRef(name);
        if (file.isEmpty()) continue;
        sk_sp<SkTypeface> face = system->makeFromFile(file.c_str(), 0);
        if (!face) continue;
        if (family.isEmpty()) face->getFamilyName(&family);
        faces.push_back({family, std::move(face)});
    }
    auto fonts = sk_make_sp<CallerFontMgr>(system, std::move(faces));

    sk_sp<skresources::ResourceProvider> files;
    SkString dir = skStringOf(env, resourceDir);
    if (!dir.isEmpty()) {
        files = skresources::FileResourceProvider::Make(dir, skresources::ImageDecodeStrategy::kPreDecode);
    }
    // Images and fonts inlined as base64 data: URIs, which most exported
    // templates use, and each image decoded once however often it is drawn.
    files = skresources::CachingResourceProvider::Make(skresources::DataURIResourceProviderProxy::Make(
            std::move(files), skresources::ImageDecodeStrategy::kPreDecode, fonts));

    sk_sp<skottie::Animation> animation =
            skottie::Animation::Builder()
                    .setFontManager(fonts)
                    .setResourceProvider(sk_make_sp<Resources>(std::move(files), fonts))
                    .setTextShapingFactory(sk_make_sp<SkShapers::HarfbuzzFactory>())
                    .setLogger(sk_make_sp<Log>())
                    .make(text.data(), text.size());
    if (!animation) return 0;
    auto* anim = new Anim{std::move(animation)};
    return static_cast<jlong>(reinterpret_cast<intptr_t>(anim));
}

JNIEXPORT jint JNICALL JNI_FN(width)(JNIEnv*, jobject, jlong handle) {
    Anim* anim = animOf(handle);
    return anim ? static_cast<jint>(std::ceil(anim->animation->size().width())) : 0;
}

JNIEXPORT jint JNICALL JNI_FN(height)(JNIEnv*, jobject, jlong handle) {
    Anim* anim = animOf(handle);
    return anim ? static_cast<jint>(std::ceil(anim->animation->size().height())) : 0;
}

JNIEXPORT jfloat JNICALL JNI_FN(fps)(JNIEnv*, jobject, jlong handle) {
    Anim* anim = animOf(handle);
    return anim ? static_cast<jfloat>(anim->animation->fps()) : 0.f;
}

JNIEXPORT jfloat JNICALL JNI_FN(duration)(JNIEnv*, jobject, jlong handle) {
    Anim* anim = animOf(handle);
    return anim ? static_cast<jfloat>(anim->animation->duration()) : 0.f;
}

JNIEXPORT jboolean JNICALL JNI_FN(render)(JNIEnv* env, jobject, jlong handle, jfloat frameTimeSeconds,
                                          jobject bitmap) {
#if defined(__ANDROID__)
    Anim* anim = animOf(handle);
    if (anim == nullptr || bitmap == nullptr) return JNI_FALSE;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        return JNI_FALSE;
    }
    // An ARGB_8888 bitmap is R, G, B, A bytes, premultiplied, which is what
    // Skia draws into directly.
    const SkImageInfo image = SkImageInfo::Make(static_cast<int>(info.width), static_cast<int>(info.height),
                                                kRGBA_8888_SkColorType, kPremul_SkAlphaType);
    std::unique_ptr<SkCanvas> canvas = SkCanvas::MakeRasterDirect(image, pixels, info.stride);
    if (canvas) {
        anim->animation->seekFrameTime(frameTimeSeconds);
        draw(*anim->animation, canvas.get(), image.width(), image.height());
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return canvas ? JNI_TRUE : JNI_FALSE;
#else
    // A desktop build has no android.graphics; its tests use renderPixels.
    (void)env;
    (void)handle;
    (void)frameTimeSeconds;
    (void)bitmap;
    return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL JNI_FN(renderPixels)(JNIEnv* env, jobject, jlong handle, jfloat frameTimeSeconds,
                                                jint width, jint height, jintArray argb) {
    Anim* anim = animOf(handle);
    if (anim == nullptr || argb == nullptr || width < 1 || height < 1) return JNI_FALSE;
    if (env->GetArrayLength(argb) != static_cast<jsize>(width) * height) return JNI_FALSE;
    // Drawn premultiplied, then handed over unpremultiplied 0xAARRGGBB ints,
    // what Bitmap.setPixels takes (B, G, R, A bytes on a little-endian CPU).
    const SkImageInfo premul = SkImageInfo::Make(width, height, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    std::vector<uint32_t> drawn(static_cast<size_t>(width) * height);
    std::unique_ptr<SkCanvas> canvas = SkCanvas::MakeRasterDirect(premul, drawn.data(), premul.minRowBytes());
    if (!canvas) return JNI_FALSE;
    anim->animation->seekFrameTime(frameTimeSeconds);
    draw(*anim->animation, canvas.get(), width, height);
    std::vector<uint32_t> out(drawn.size());
    const SkPixmap src(premul, drawn.data(), premul.minRowBytes());
    const SkImageInfo unpremul = premul.makeAlphaType(kUnpremul_SkAlphaType);
    if (!src.readPixels(unpremul, out.data(), unpremul.minRowBytes())) return JNI_FALSE;
    env->SetIntArrayRegion(argb, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jint*>(out.data()));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI_FN(release)(JNIEnv*, jobject, jlong handle) {
    delete animOf(handle);
}

}  // extern "C"

static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "an ARGB int is B, G, R, A bytes");
