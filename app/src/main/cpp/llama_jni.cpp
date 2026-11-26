#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <cstdint>
#include "llama.h"

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static std::atomic<bool> g_cancel{false};
static bool llama_abort_callback(void *) { return g_cancel.load(); }

// JNI's GetStringUTFChars returns Modified UTF-8, which is not the byte
// encoding expected by llama.cpp for supplementary Unicode characters. Convert
// UTF-16 explicitly so Tamil, emoji, and other non-ASCII input reach the model
// as standard UTF-8.
static std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (!value) return {};
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};

    std::string result;
    result.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xD800 && codepoint <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                codepoint = 0x10000 + ((codepoint - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (codepoint <= 0x7F) {
            result.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7FF) {
            result.push_back(static_cast<char>(0xC0 | (codepoint >> 6)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else if (codepoint <= 0xFFFF) {
            result.push_back(static_cast<char>(0xE0 | (codepoint >> 12)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else {
            result.push_back(static_cast<char>(0xF0 | (codepoint >> 18)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

// Convert standard UTF-8 model output back to Java UTF-16. NewStringUTF must
// not be used here: its input is Modified UTF-8 and can corrupt 4-byte UTF-8.
static jstring utf8_to_jstring(JNIEnv *env, const std::string &input) {
    std::vector<jchar> utf16;
    utf16.reserve(input.size());
    size_t i = 0;
    while (i < input.size()) {
        const unsigned char first = static_cast<unsigned char>(input[i++]);
        uint32_t codepoint = 0xFFFD;
        size_t continuation = 0;
        if (first <= 0x7F) {
            codepoint = first;
        } else if ((first & 0xE0) == 0xC0) {
            codepoint = first & 0x1F; continuation = 1;
        } else if ((first & 0xF0) == 0xE0) {
            codepoint = first & 0x0F; continuation = 2;
        } else if ((first & 0xF8) == 0xF0) {
            codepoint = first & 0x07; continuation = 3;
        }
        bool valid = i + continuation <= input.size();
        for (size_t j = 0; valid && j < continuation; ++j) {
            const unsigned char next = static_cast<unsigned char>(input[i++]);
            if ((next & 0xC0) != 0x80) { valid = false; break; }
            codepoint = (codepoint << 6) | (next & 0x3F);
        }
        if (!valid || codepoint > 0x10FFFF ||
            (codepoint >= 0xD800 && codepoint <= 0xDFFF)) {
            codepoint = 0xFFFD;
        }
        if (codepoint <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (codepoint >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (codepoint & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_opendroid_ai_core_llm_providers_LlamaCppNative_nativeLoad(
        JNIEnv *env, jobject, jstring path, jint contextSize, jint threads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_init();
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;
    g_model = llama_model_load_from_file(jstring_to_string(env, path).c_str(), model_params);
    if (!g_model) return JNI_FALSE;
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = (uint32_t) contextSize;
    // Keep prompt evaluation batches bounded on memory-constrained Android devices.
    // Context length and batch size are separate; using n_ctx for n_batch wastes RAM.
    ctx_params.n_batch = 256;
    ctx_params.n_ubatch = 128;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    ctx_params.abort_callback = llama_abort_callback;
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) { llama_model_free(g_model); g_model = nullptr; return JNI_FALSE; }
    g_cancel.store(false);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_opendroid_ai_core_llm_providers_LlamaCppNative_nativeGenerate(
        JNIEnv *env, jobject, jstring prompt, jint maxTokens, jfloat temperature) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_ctx) return env->NewStringUTF("[JARVIS ERROR] Model is not loaded.");
    g_cancel.store(false);
    const std::string input = jstring_to_string(env, prompt);
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(input.size() + 16);
    int32_t n = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(), tokens.data(),
                               (int32_t) tokens.size(), true, true);
    if (n < 0) {
        tokens.resize((size_t) -n);
        n = llama_tokenize(vocab, input.c_str(), (int32_t) input.size(), tokens.data(),
                           (int32_t) tokens.size(), true, true);
    }
    if (n <= 0) return env->NewStringUTF("[JARVIS ERROR] Prompt tokenization failed.");
    tokens.resize((size_t)n);
    const int context_limit = (int)llama_n_ctx(g_ctx);
    const int prompt_limit = context_limit - maxTokens - 1;
    if (prompt_limit <= 0) return env->NewStringUTF("[JARVIS ERROR] Context size is too small.");
    if ((int)tokens.size() > prompt_limit) {
        tokens.erase(tokens.begin(), tokens.end() - prompt_limit);
    }
    llama_memory_clear(llama_get_memory(g_ctx), true);
    llama_batch batch = llama_batch_init((int32_t)tokens.size(), 0, 1);
    for (int i = 0; i < (int)tokens.size(); ++i) {
        batch.token[i] = tokens[i]; batch.pos[i] = i; batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0; batch.logits[i] = (i == (int)tokens.size() - 1);
    }
    batch.n_tokens = (int32_t)tokens.size();
    if (llama_decode(g_ctx, batch) != 0) { llama_batch_free(batch); return env->NewStringUTF("[JARVIS ERROR] Prompt decode failed."); }
    llama_batch_free(batch);
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!sampler) return env->NewStringUTF("[JARVIS ERROR] Sampler initialization failed.");
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature > 0 ? temperature : 0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    std::string output;
    const int max_context = (int)llama_n_ctx(g_ctx);
    for (int i = 0; i < maxTokens && !g_cancel.load(); ++i) {
        llama_token token = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece[8192];
        int piece_len = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (piece_len > 0) output.append(piece, (size_t)piece_len);
        llama_batch next = llama_batch_init(1, 0, 1);
        next.token[0] = token; next.pos[0] = (int32_t)tokens.size() + i;
        next.n_seq_id[0] = 1; next.seq_id[0][0] = 0; next.logits[0] = true; next.n_tokens = 1;
        if (next.pos[0] >= max_context || llama_decode(g_ctx, next) != 0) {
            llama_batch_free(next); break;
        }
        llama_batch_free(next);
    }
    llama_sampler_free(sampler);
    return utf8_to_jstring(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_opendroid_ai_core_llm_providers_LlamaCppNative_nativeCancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_opendroid_ai_core_llm_providers_LlamaCppNative_nativeRelease(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_cancel.store(true);
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
}
