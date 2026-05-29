// Thin JNI bridge for llama.cpp. Single global model / context (we only run
// one inference at a time on this device anyway). Generation is synchronous
// because Mabu's CPU can't usefully overlap streaming with anything else.

#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef MABU_HAS_LLAMA

#include "llama.h"

static llama_model*   g_model = nullptr;
static llama_context* g_ctx   = nullptr;
static llama_sampler* g_smpl  = nullptr;
static bool g_backend_inited = false;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mabu_anima_LlamaInference_nativeLoadModel(
        JNIEnv* env, jclass /*cls*/,
        jstring jpath, jint ctxSize, jint nThreads, jfloat temperature, jint topK, jfloat topP) {
    if (g_model) {
        LOGE("model already loaded, call release first");
        return JNI_FALSE;
    }

    if (!g_backend_inited) {
        llama_backend_init();
        g_backend_inited = true;
    }

    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    LOGI("loading model from %s (ctx=%d, threads=%d)", cpath, ctxSize, nThreads);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(cpath, mparams);
    env->ReleaseStringUTFChars(jpath, cpath);

    if (!g_model) {
        LOGE("llama_model_load_from_file returned null");
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)ctxSize;
    cparams.n_batch         = 256;
    cparams.n_threads       = nThreads;
    cparams.n_threads_batch = nThreads;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("llama_init_from_model returned null");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    auto sparams = llama_sampler_chain_default_params();
    g_smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("model + context + sampler ready");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mabu_anima_LlamaInference_nativeGenerate(
        JNIEnv* env, jclass /*cls*/, jstring jprompt, jint maxTokens) {
    if (!g_model || !g_ctx || !g_smpl) {
        LOGE("not loaded");
        return env->NewStringUTF("");
    }

    const char* cprompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string promptStr(cprompt);
    env->ReleaseStringUTFChars(jprompt, cprompt);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // Tokenize. Two-call pattern: size with negative result, then actual tokenize.
    int probe = llama_tokenize(vocab, promptStr.c_str(), (int)promptStr.size(),
                               nullptr, 0, true, true);
    int n_prompt_tokens = -probe;
    if (n_prompt_tokens <= 0) {
        LOGE("tokenize probe failed: %d", probe);
        return env->NewStringUTF("");
    }
    std::vector<llama_token> prompt_tokens(n_prompt_tokens);
    if (llama_tokenize(vocab, promptStr.c_str(), (int)promptStr.size(),
                       prompt_tokens.data(), n_prompt_tokens, true, true) < 0) {
        LOGE("tokenize failed");
        return env->NewStringUTF("");
    }

    // Reset KV cache so each call is independent (no chat history yet).
    llama_memory_clear(llama_get_memory(g_ctx), false);

    // Eval prompt in one batch.
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), (int32_t)prompt_tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("prompt decode failed");
        return env->NewStringUTF("");
    }

    std::string result;
    result.reserve(maxTokens * 4);
    char buf[256];
    for (int i = 0; i < maxTokens; i++) {
        llama_token tok = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        result.append(buf, n);

        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, nb) != 0) {
            LOGE("decode failed at token %d", i);
            break;
        }
    }

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_mabu_anima_LlamaInference_nativeRelease(JNIEnv* /*env*/, jclass /*cls*/) {
    if (g_smpl) { llama_sampler_free(g_smpl); g_smpl = nullptr; }
    if (g_ctx)  { llama_free(g_ctx);          g_ctx  = nullptr; }
    if (g_model){ llama_model_free(g_model);  g_model= nullptr; }
    LOGI("released");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mabu_anima_LlamaInference_nativeAvailable(JNIEnv*, jclass) {
    return JNI_TRUE;
}

#else  // MABU_HAS_LLAMA -- llama.cpp source not present, stub everything

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mabu_anima_LlamaInference_nativeLoadModel(
        JNIEnv*, jclass, jstring, jint, jint, jfloat, jint, jfloat) {
    LOGE("llama.cpp not built in -- run setup-llama.ps1");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mabu_anima_LlamaInference_nativeGenerate(
        JNIEnv* env, jclass, jstring, jint) {
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL
Java_com_mabu_anima_LlamaInference_nativeRelease(JNIEnv*, jclass) {}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mabu_anima_LlamaInference_nativeAvailable(JNIEnv*, jclass) {
    return JNI_FALSE;
}

#endif  // MABU_HAS_LLAMA
