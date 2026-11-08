// JNI surface of fastText's language identifier (lid.176, 176 languages),
// the first identifier the translate tool asks where ML Kit's is missing
// (F-Droid and the offline build); CLD3 (native/cld3-jni) answers when this
// one is unsure. Kotlin side: com.wasimaster.wmkeyboard.core.translate.FastTextNative.
//
// The quantised model (lid.176.ftz, 938 KB) is compiled into the library by
// model.S, so the langid gate brings it along and nothing is read from disk.
// Text crosses as UTF-8 bytes. The answer is one line per language,
// "<code>\t<probability>", likeliest first; codes are fastText's labels
// without "__label__" (ISO 639-1 where there is one, else 639-3).

#include <jni.h>

#include <istream>
#include <memory>
#include <mutex>
#include <streambuf>
#include <string>
#include <utility>
#include <vector>

#include "fasttext.h"

extern "C" const char wm_lid176[];
extern "C" const char wm_lid176_end[];

namespace {

// An istream over bytes already in memory.
struct MemoryBuf : std::streambuf {
    MemoryBuf(const char* begin, const char* end) {
        char* b = const_cast<char*>(begin);
        setg(b, b, const_cast<char*>(end));
    }
};

std::mutex g_mutex;
std::unique_ptr<fasttext::FastText> g_model;
bool g_failed = false;

fasttext::FastText* model() {
    if (g_model || g_failed) return g_model.get();
    try {
        MemoryBuf buf(wm_lid176, wm_lid176_end);
        std::istream in(&buf);
        auto m = std::make_unique<fasttext::FastText>();
        m->loadModel(in);
        g_model = std::move(m);
    } catch (...) {
        g_failed = true;
    }
    return g_model.get();
}

}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_FastTextNative_nativeIdentify(
        JNIEnv* env, jclass, jbyteArray utf8, jint languages) {
    if (!utf8) return nullptr;
    const jsize n = env->GetArrayLength(utf8);
    std::string text(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(utf8, 0, n, reinterpret_cast<jbyte*>(&text[0]));
    // fastText reads one line, its words split at whitespace; a newline in
    // the text would end it early.
    for (char& c : text) {
        if (c == '\n' || c == '\r') c = ' ';
    }
    text += '\n';
    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        fasttext::FastText* m = model();
        if (!m) return nullptr;
        try {
            MemoryBuf buf(text.data(), text.data() + text.size());
            std::istream in(&buf);
            std::vector<std::pair<fasttext::real, std::string>> predictions;
            m->predictLine(in, predictions, languages > 0 ? languages : 1, 0.0f);
            static const std::string kPrefix = "__label__";
            for (const auto& p : predictions) {
                std::string label = p.second;
                if (label.compare(0, kPrefix.size(), kPrefix) == 0) label.erase(0, kPrefix.size());
                out += label;
                out += '\t';
                out += std::to_string(p.first);
                out += '\n';
            }
        } catch (...) {
            return nullptr;
        }
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
    return result;
}
