// JNI surface for CTranslate2 running Meta's NLLB-200 (distilled 600M, int8):
// load the model directory once, translate a batch of sentences between two
// of its 200 languages. Kotlin side: com.wasimaster.wmkeyboard.core.translate.Ct2Native.
//
// Text crosses the boundary as UTF-8 byte arrays, never as Java strings: JNI's
// modified UTF-8 writes an emoji as two three-byte surrogates, which
// SentencePiece would not read as one character. Language tags are NLLB's
// FLORES-200 codes ("ben_Beng"), plain ASCII, so they come as strings.
//
// The tokenisation is NLLB's as Hugging Face's tokenizer does it: the source
// is [source tag] + SentencePiece pieces + "</s>", and decoding starts from
// the target tag, which the result then drops.
//
// The output layer is cut to the target's script. NLLB's vocabulary is
// 256,206 pieces for 200 languages, and every decoding step multiplies the
// decoder's state by all of them: the largest matrix in the model, and the
// packing buffer ruy keeps for it. A sentence into Bengali can only use
// Bengali pieces, pieces with no letters (digits, punctuation, spaces) and the
// pieces of its own source (a name or a link copied through), about 7,600 rows
// besides the source's, so decoding scores only those. The FLORES tag names
// the script (ISO 15924, "Beng"), which is all the cut needs; a script not in
// the table below decodes against the whole vocabulary. See README.md.
//
// A handle may not be used from two threads at once; Kotlin serialises every
// call behind one lock. Every C++ exception becomes a Java RuntimeException,
// so a bad file costs a translation, not the keyboard.

#include <jni.h>

#include <algorithm>
#include <exception>
#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "ctranslate2/decoding.h"
#include "ctranslate2/layers/common.h"
#include "ctranslate2/logging.h"
#include "ctranslate2/models/sequence_to_sequence.h"
#include "ctranslate2/translator.h"
#include "sentencepiece_processor.h"

namespace {

struct Engine {
    std::unique_ptr<ctranslate2::Translator> translator;
    sentencepiece::SentencePieceProcessor pieces;
    // Per target script, the sorted ids its sentences may use; filled on first use.
    std::map<std::string, std::vector<size_t>> scripts;
};

// Decoding stops here even if the model never says "</s>": a sentence that
// reaches it has gone into a loop, which greedy NLLB can on odd input.
constexpr size_t kMaxDecoding = 256;

struct Range {
    char32_t first;
    char32_t last;
};

// The Unicode blocks of each script NLLB writes, by the ISO 15924 code its
// FLORES tags end in. The Devanagari danda and double danda are listed with
// every Indic script that ends its sentences with them.
const std::map<std::string, std::vector<Range>>& scriptRanges() {
    static const std::map<std::string, std::vector<Range>> table = [] {
        const std::vector<Range> cjk = {{0x2E80, 0x2FDF}, {0x3100, 0x312F}, {0x31A0, 0x31BF}, {0x3400, 0x4DBF},
                                        {0x4E00, 0x9FFF}, {0xF900, 0xFAFF}, {0x20000, 0x323AF}};
        const Range danda = {0x0964, 0x0965};
        std::map<std::string, std::vector<Range>> t = {
            {"Latn", {{0x41, 0x5A}, {0x61, 0x7A}, {0xC0, 0x2FF}, {0x1D00, 0x1DBF}, {0x1E00, 0x1EFF},
                      {0x2C60, 0x2C7F}, {0xA720, 0xA7FF}, {0xAB30, 0xAB6F}, {0xFF21, 0xFF3A}, {0xFF41, 0xFF5A}}},
            {"Arab", {{0x0600, 0x06FF}, {0x0750, 0x077F}, {0x0870, 0x08FF}, {0xFB50, 0xFDFF}, {0xFE70, 0xFEFF}}},
            {"Cyrl", {{0x0400, 0x052F}, {0x1C80, 0x1C8F}, {0x2DE0, 0x2DFF}, {0xA640, 0xA69F}}},
            {"Deva", {{0x0900, 0x097F}, {0x1CD0, 0x1CFF}, {0xA8E0, 0xA8FF}}},
            {"Beng", {{0x0980, 0x09FF}, danda}},
            {"Guru", {{0x0A00, 0x0A7F}, danda}},
            {"Gujr", {{0x0A80, 0x0AFF}, danda}},
            {"Orya", {{0x0B00, 0x0B7F}, danda}},
            {"Taml", {{0x0B80, 0x0BFF}, danda}},
            {"Telu", {{0x0C00, 0x0C7F}, danda}},
            {"Knda", {{0x0C80, 0x0CFF}, danda}},
            {"Mlym", {{0x0D00, 0x0D7F}, danda}},
            {"Sinh", {{0x0D80, 0x0DFF}, danda}},
            {"Thai", {{0x0E00, 0x0E7F}}},
            {"Laoo", {{0x0E80, 0x0EFF}}},
            {"Tibt", {{0x0F00, 0x0FFF}}},
            {"Mymr", {{0x1000, 0x109F}, {0xA9E0, 0xA9FF}, {0xAA60, 0xAA7F}}},
            {"Geor", {{0x10A0, 0x10FF}, {0x1C90, 0x1CBF}, {0x2D00, 0x2D2F}}},
            {"Ethi", {{0x1200, 0x139F}, {0x2D80, 0x2DDF}, {0xAB00, 0xAB2F}}},
            {"Khmr", {{0x1780, 0x17FF}, {0x19E0, 0x19FF}}},
            {"Tfng", {{0x2D30, 0x2D7F}}},
            {"Grek", {{0x0370, 0x03FF}, {0x1F00, 0x1FFF}}},
            {"Armn", {{0x0530, 0x058F}, {0xFB13, 0xFB17}}},
            {"Hebr", {{0x0590, 0x05FF}, {0xFB1D, 0xFB4F}}},
            {"Hans", cjk},
            {"Hant", cjk},
        };
        std::vector<Range> japanese = cjk;
        japanese.insert(japanese.end(), {{0x3040, 0x30FF}, {0x31F0, 0x31FF}, {0xFF66, 0xFF9F}});
        t["Jpan"] = japanese;
        // Korean as NLLB writes it is Hangul, with the odd Hanja.
        std::vector<Range> korean = cjk;
        korean.insert(korean.end(), {{0x1100, 0x11FF}, {0x3130, 0x318F}, {0xA960, 0xA97F}, {0xAC00, 0xD7FF}});
        t["Hang"] = korean;
        return t;
    }();
    return table;
}

// Characters that belong to no one script: ASCII digits and punctuation, the
// Latin-1 signs, combining accents, general punctuation and symbols (which
// holds SentencePiece's "▁" and the zero-width joiners), CJK and full-width
// punctuation, variation selectors and emoji.
bool neutral(char32_t c) {
    if (c < 0x80) return !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
    return (c >= 0x80 && c <= 0xBF) || c == 0xD7 || c == 0xF7 || (c >= 0x0300 && c <= 0x036F) ||
           (c >= 0x2000 && c <= 0x2BFF) || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFE00 && c <= 0xFE0F) ||
           (c >= 0xFF01 && c <= 0xFF20) || (c >= 0xFF3B && c <= 0xFF40) || (c >= 0xFF5B && c <= 0xFF65) ||
           (c >= 0x1F000 && c <= 0x1FAFF);
}

// Whether every character of the UTF-8 token is neutral or in ranges.
bool writtenIn(const std::string& token, const std::vector<Range>& ranges) {
    for (size_t i = 0; i < token.size();) {
        const auto lead = static_cast<unsigned char>(token[i]);
        const size_t length = lead < 0x80 ? 1 : lead < 0xE0 ? 2 : lead < 0xF0 ? 3 : 4;
        char32_t c = length == 1 ? lead : lead & (0x7F >> length);
        for (size_t k = 1; k < length && i + k < token.size(); ++k)
            c = (c << 6) | (static_cast<unsigned char>(token[i + k]) & 0x3F);
        i += length;
        if (neutral(c)) continue;
        if (std::none_of(ranges.begin(), ranges.end(), [c](const Range& r) { return c >= r.first && c <= r.last; }))
            return false;
    }
    return true;
}

// The ids a sentence into target may produce besides its source's: the
// target's script and the neutral pieces, never another language's tag.
// Empty when the script is not in the table: the whole vocabulary, then.
const std::vector<size_t>& scriptIds(Engine* e, const ctranslate2::Vocabulary& vocabulary, const std::string& target) {
    const size_t cut = target.rfind('_');
    const std::string script = cut == std::string::npos ? "" : target.substr(cut + 1);
    auto cached = e->scripts.find(script);
    if (cached != e->scripts.end()) return cached->second;
    std::vector<size_t> ids;
    const auto ranges = scriptRanges().find(script);
    if (ranges != scriptRanges().end()) {
        for (size_t id = 0; id < vocabulary.size(); ++id) {
            const std::string& token = vocabulary.to_token(id);
            const size_t tag = token.find('_');
            const bool languageTag = token.size() == 8 && tag == 3;
            if (!languageTag && writtenIn(token, ranges->second)) ids.push_back(id);
        }
        for (const std::string* special : {&vocabulary.unk_token(), &vocabulary.bos_token(), &vocabulary.eos_token()})
            ids.push_back(vocabulary.to_id(*special));
        std::sort(ids.begin(), ids.end());
        ids.erase(std::unique(ids.begin(), ids.end()), ids.end());
    }
    return e->scripts.emplace(script, std::move(ids)).first->second;
}

// What Translator::translate_batch does for this model, with the output layer
// cut to the target's script: run on the translator's own worker thread (its
// thread pool and ruy context), through CTranslate2's public layers.
std::vector<std::vector<std::string>> decodeRestricted(Engine* e, ctranslate2::models::SequenceToSequenceReplica& base,
                                                       const std::vector<std::vector<std::string>>& batch,
                                                       const std::string& target,
                                                       const ctranslate2::DecodingOptions& options) {
    auto& replica = dynamic_cast<ctranslate2::models::EncoderDecoderReplica&>(base);
    const auto& model = static_cast<const ctranslate2::models::SequenceToSequenceModel&>(*replica.model());
    const auto scoped_device_setter = model.get_scoped_device_setter();
    const auto device = model.device();
    const auto& source_vocabulary = model.get_source_vocabulary(0);
    const auto& target_vocabulary = model.get_target_vocabulary();

    const auto source_ids = source_vocabulary.to_ids(batch, 512, model.with_source_bos(), model.with_source_eos());
    ctranslate2::StorageView lengths(ctranslate2::DataType::INT32, device);
    ctranslate2::StorageView ids =
        ctranslate2::layers::make_sequence_inputs(source_ids, device, model.preferred_size_multiple(), &lengths);
    ctranslate2::StorageView memory(replica.encoder().output_type(), device);
    replica.encoder()(std::vector<ctranslate2::StorageView>{std::move(ids)}, lengths, memory);

    ctranslate2::layers::Decoder& decoder = replica.decoder();
    ctranslate2::layers::DecoderState state = decoder.initial_state();
    state.emplace("memory", std::move(memory));
    state.emplace("memory_lengths", std::move(lengths));

    const std::vector<std::vector<size_t>> start =
        target_vocabulary.to_ids({{target}}, 0, model.decoder_start_token(), nullptr);
    std::vector<size_t> allowed = scriptIds(e, target_vocabulary, target);
    if (!allowed.empty()) {
        for (const auto& sentence : source_ids) allowed.insert(allowed.end(), sentence.begin(), sentence.end());
        allowed.insert(allowed.end(), start[0].begin(), start[0].end());
        std::sort(allowed.begin(), allowed.end());
        allowed.erase(std::unique(allowed.begin(), allowed.end()), allowed.end());
    }
    // Back to the whole vocabulary first: update_output_layer adds a new
    // cut's id map to the last one's without clearing it (emplace keeps the
    // old entries), so two cuts in a row would map the forced target tag,
    // and every id after it, to the wrong rows. The reset clears the map.
    decoder.update_output_layer(model.preferred_size_multiple());
    if (!allowed.empty()) decoder.update_output_layer(model.preferred_size_multiple(), allowed);

    const std::vector<std::vector<size_t>> starts(batch.size(), start[0]);
    const std::vector<size_t> end_ids{target_vocabulary.eos_id()};
    auto results = ctranslate2::decode(decoder, state, starts, end_ids, options);
    std::vector<std::vector<size_t>> best;
    best.reserve(results.size());
    for (auto& result : results) {
        best.push_back(result.hypotheses.empty() ? std::vector<size_t>{} : std::move(result.hypotheses[0]));
        while (!best.back().empty() && best.back().back() == target_vocabulary.eos_id()) best.back().pop_back();
    }
    return target_vocabulary.to_tokens(best);
}

void throwJava(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass error = env->FindClass("java/lang/RuntimeException");
    if (error != nullptr) env->ThrowNew(error, message);
}

std::string string(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::vector<std::string> utf8(JNIEnv* env, jobjectArray texts) {
    const jsize n = env->GetArrayLength(texts);
    std::vector<std::string> out;
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto array = static_cast<jbyteArray>(env->GetObjectArrayElement(texts, i));
        const jsize length = array == nullptr ? 0 : env->GetArrayLength(array);
        std::string text(static_cast<size_t>(length), '\0');
        if (length > 0) env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(&text[0]));
        if (array != nullptr) env->DeleteLocalRef(array);
        out.push_back(std::move(text));
    }
    return out;
}

jobjectArray toJava(JNIEnv* env, const std::vector<std::string>& texts) {
    jclass bytes = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(texts.size()), bytes, nullptr);
    if (out == nullptr) return nullptr;
    for (size_t i = 0; i < texts.size(); ++i) {
        const std::string& text = texts[i];
        jbyteArray array = env->NewByteArray(static_cast<jsize>(text.size()));
        if (array == nullptr) return nullptr;
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        env->SetObjectArrayElement(out, static_cast<jsize>(i), array);
        env->DeleteLocalRef(array);
    }
    return out;
}

Engine* engine(jlong handle) { return reinterpret_cast<Engine*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    ctranslate2::set_log_level(ctranslate2::LogLevel::Off);
    return JNI_VERSION_1_6;
}

// modelDir holds CTranslate2's model.bin, config.json and its shared
// vocabulary, plus the SentencePiece model under the name its conversion
// gave it: NLLB's sentencepiece.bpe.model, or T5's spiece.model (MADLAD-400).
// threads is how many cores one translation may use.
JNIEXPORT jlong JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_Ct2Native_nativeLoad(JNIEnv* env, jobject, jstring modelDir, jint threads) {
    try {
        const std::string dir = string(env, modelDir);
        auto loaded = std::make_unique<Engine>();
        auto status = loaded->pieces.Load(dir + "/sentencepiece.bpe.model");
        for (const char* other : {"/spiece.model", "/sentencepiece.model"}) {
            if (status.ok()) break;
            status = loaded->pieces.Load(dir + other);
        }
        if (!status.ok()) throw std::runtime_error(status.ToString());
        ctranslate2::ReplicaPoolConfig config;
        config.num_threads_per_replica = static_cast<size_t>(std::max(1, static_cast<int>(threads)));
        // int8 weights as stored; activations in float32, the only path on a phone CPU.
        loaded->translator = std::make_unique<ctranslate2::Translator>(
            dir, ctranslate2::Device::CPU, ctranslate2::ComputeType::INT8, std::vector<int>{0}, false, config);
        return reinterpret_cast<jlong>(loaded.release());
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    } catch (...) {
        throwJava(env, "NLLB model failed to load");
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_Ct2Native_nativeFree(JNIEnv*, jobject, jlong handle) {
    delete engine(handle);
}

// Each of texts (UTF-8, one sentence each) from source into target, in order.
// beam 1 is greedy search.
JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_Ct2Native_nativeTranslate(
        JNIEnv* env, jobject, jlong handle, jobjectArray texts, jstring sourceTag, jstring targetTag, jint beam) {
    try {
        Engine* e = engine(handle);
        const std::string source = string(env, sourceTag);
        const std::string target = string(env, targetTag);
        const std::vector<std::string> inputs = utf8(env, texts);
        std::vector<std::vector<std::string>> batch;
        size_t longest = 0;
        batch.reserve(inputs.size());
        for (const std::string& text : inputs) {
            std::vector<std::string> tokens{source};
            std::vector<std::string> pieces;
            const auto status = e->pieces.Encode(text, &pieces);
            if (!status.ok()) throw std::runtime_error(status.ToString());
            tokens.insert(tokens.end(), pieces.begin(), pieces.end());
            tokens.push_back("</s>");
            longest = std::max(longest, tokens.size());
            batch.push_back(std::move(tokens));
        }
        ctranslate2::DecodingOptions options;
        options.beam_size = static_cast<size_t>(std::max(1, static_cast<int>(beam)));
        // A translation runs to about twice its source at most; the cap
        // stops a loop well before kMaxDecoding on a short line.
        options.max_length = std::min(kMaxDecoding, longest * 2 + 10);
        auto results = e->translator
            ->post<std::vector<std::vector<std::string>>>([&](ctranslate2::models::SequenceToSequenceReplica& replica) {
                return decodeRestricted(e, replica, batch, target, options);
            })
            .get();
        std::vector<std::string> out;
        out.reserve(results.size());
        for (auto& tokens : results) {
            // The first token is the target tag the decoding started from.
            if (!tokens.empty() && tokens.front() == target) tokens.erase(tokens.begin());
            std::string text;
            const auto status = e->pieces.Decode(tokens, &text);
            if (!status.ok()) throw std::runtime_error(status.ToString());
            out.push_back(std::move(text));
        }
        return toJava(env, out);
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    } catch (...) {
        throwJava(env, "NLLB translation failed");
    }
    return nullptr;
}

// For a model told the target on the source side and decoding from its own
// start token, as T5 models like MADLAD-400 are: each of texts becomes
// [prefix] + SentencePiece pieces + "</s>", and the whole vocabulary is
// scored. beam 1 is greedy search.
JNIEXPORT jobjectArray JNICALL
Java_com_wasimaster_wmkeyboard_core_translate_Ct2Native_nativeTranslatePrefixed(
        JNIEnv* env, jobject, jlong handle, jobjectArray texts, jstring prefixTag, jint beam) {
    try {
        Engine* e = engine(handle);
        const std::string prefix = string(env, prefixTag);
        const std::vector<std::string> inputs = utf8(env, texts);
        std::vector<std::vector<std::string>> batch;
        size_t longest = 0;
        batch.reserve(inputs.size());
        for (const std::string& text : inputs) {
            std::vector<std::string> tokens{prefix};
            std::vector<std::string> pieces;
            const auto status = e->pieces.Encode(text, &pieces);
            if (!status.ok()) throw std::runtime_error(status.ToString());
            tokens.insert(tokens.end(), pieces.begin(), pieces.end());
            tokens.push_back("</s>");
            longest = std::max(longest, tokens.size());
            batch.push_back(std::move(tokens));
        }
        ctranslate2::TranslationOptions options;
        options.beam_size = static_cast<size_t>(std::max(1, static_cast<int>(beam)));
        options.max_decoding_length = std::min(kMaxDecoding, longest * 2 + 10);
        const auto results = e->translator->translate_batch(batch, options);
        std::vector<std::string> out;
        out.reserve(results.size());
        for (const auto& result : results) {
            std::string text;
            if (!result.hypotheses.empty()) {
                const auto status = e->pieces.Decode(result.hypotheses[0], &text);
                if (!status.ok()) throw std::runtime_error(status.ToString());
            }
            out.push_back(std::move(text));
        }
        return toJava(env, out);
    } catch (const std::exception& e) {
        throwJava(env, e.what());
    } catch (...) {
        throwJava(env, "Translation failed");
    }
    return nullptr;
}

}  // extern "C"
