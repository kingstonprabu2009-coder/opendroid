#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include "llama.h"

static std::mutex g_mutex;
static llama_model * g_model = nullptr;
static llama_context * g_ctx = nullptr;
static std::atomic<bool> g_cancel{false};
static bool llama_abort_callback(void *) { return g_cancel.load(); }

static std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
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
    return env->NewStringUTF(output.c_str());
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
