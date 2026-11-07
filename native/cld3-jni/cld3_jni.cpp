// JNI surface of CLD3, Google's neural language identifier, for the
// translate tool's source detection where ML Kit's identifier is missing
// (F-Droid and the offline build). Kotlin side:
// com.wasimaster.wmkeyboard.core.translate.Cld3Native.
//
// Text crosses as UTF-8 bytes. The answer is one line per language,
// "<code>\t<probability>\t<reliable 0|1>\t<proportion>", most of the text
// first; codes are CLD3's (ISO 639-1 where there is one, "-Latn" for a
// language written in Latin letters that usually is not).

#include <jni.h>

#include <mutex>
#include <string>

#include "nnet_language_identifier.h"

namespace {

std::mutex g_mutex;
chrome_lang_id::NNetLanguageIdentifier* g_identifier = nullptr;

chrome_lang_id::NNetLanguageIdentifier& identifier() {
    // The model is compiled in; building it takes a few milliseconds.
    if (!g_identifier) g_identifier = new chrome_lang_id::NNetLanguageIdentifier(0, 1000);
    return *g_identifier;
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_Cld3Native_nativeIdentify(
        JNIEnv* env, jclass, jbyteArray utf8, jint languages) {
    if (!utf8) return nullptr;
    const jsize n = env->GetArrayLength(utf8);
    std::string text(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(utf8, 0, n, reinterpret_cast<jbyte*>(&text[0]));
    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto results = identifier().FindTopNMostFreqLangs(text, languages > 0 ? languages : 1);
        for (const auto& r : results) {
            if (r.language == chrome_lang_id::NNetLanguageIdentifier::kUnknown) continue;
            out += r.language;
            out += '\t';
            out += std::to_string(r.probability);
            out += '\t';
            out += r.is_reliable ? '1' : '0';
            out += '\t';
            out += std::to_string(r.proportion);
            out += '\n';
        }
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
    return result;
}
