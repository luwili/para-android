#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::mutex guard;
std::once_flag backend_once;
std::atomic<bool> cancelled{false};
llama_model * model = nullptr;
llama_context * ctx = nullptr;
int context_size = 4096;

void release() {
    if (ctx) llama_free(ctx);
    if (model) llama_model_free(model);
    ctx = nullptr;
    model = nullptr;
}
void fail(JNIEnv * env, const char * message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
std::string bytes(JNIEnv * env, jbyteArray array) {
    const jsize size = env->GetArrayLength(array);
    if (size < 0 || size > 128 * 1024) throw std::runtime_error("INPUT_LIMIT");
    std::string result(size, '\0');
    env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
bool abort_decode(void *) { return cancelled.load(); }
bool load_progress(float, void *) { return !cancelled.load(); }
}

extern "C" JNIEXPORT void JNICALL
Java_app_para_schedule_ai_NativeBridge_cancel(JNIEnv *, jobject) { cancelled.store(true); }

extern "C" JNIEXPORT void JNICALL
Java_app_para_schedule_ai_NativeBridge_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(guard);
    release();
}

extern "C" JNIEXPORT void JNICALL
Java_app_para_schedule_ai_NativeBridge_load(JNIEnv * env, jobject, jstring path, jint n_ctx, jint threads) {
    std::lock_guard<std::mutex> lock(guard);
    const char * filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) return;
    try {
        release();
        cancelled.store(false);
        std::call_once(backend_once, [] {
            llama_backend_init();
            // Never log prompts, source files or generated text.
            llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
        });
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        mp.progress_callback = load_progress;
        model = llama_model_load_from_file(filename, mp);
        if (!model) throw std::runtime_error(cancelled.load() ? "CANCELLED" : "MODEL_LOAD_FAILED");
        context_size = std::clamp<int>(n_ctx, 512, 8192);
        auto cp = llama_context_default_params();
        cp.n_ctx = context_size;
        cp.n_batch = 128;
        cp.n_ubatch = 64;
        cp.n_threads = std::clamp<int>(threads, 1, 4);
        cp.n_threads_batch = cp.n_threads;
        cp.no_perf = true;
        cp.offload_kqv = false;
        cp.op_offload = false;
        ctx = llama_init_from_model(model, cp);
        if (!ctx) throw std::runtime_error("CONTEXT_MEMORY_FAILED");
        llama_set_abort_callback(ctx, abort_decode, nullptr);
    } catch (const std::exception & e) {
        release();
        fail(env, e.what());
    }
    env->ReleaseStringUTFChars(path, filename);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_app_para_schedule_ai_NativeBridge_generate(JNIEnv * env, jobject, jbyteArray input,
        jbyteArray grammar_bytes, jint output_limit, jobject progress) {
    std::lock_guard<std::mutex> lock(guard);
    try {
        if (!ctx || !model) throw std::runtime_error("MODEL_NOT_LOADED");
        if (cancelled.load()) throw std::runtime_error("CANCELLED");
        const auto prompt = bytes(env, input);
        const auto grammar = bytes(env, grammar_bytes);
        const int limit = std::clamp<int>(output_limit, 32, 3072);
        const auto * vocab = llama_model_get_vocab(model);
        const int n = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (n <= 0 || n + limit + 8 > context_size) throw std::runtime_error("CONTEXT_TOO_LONG");
        std::vector<llama_token> tokens(n);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), n, true, true) != n)
            throw std::runtime_error("TOKENIZE_FAILED");
        llama_memory_clear(llama_get_memory(ctx), true);
        auto batch = llama_batch_init(128, 0, 1);
        auto free_batch = [&batch](void *) { llama_batch_free(batch); };
        std::unique_ptr<void, decltype(free_batch)> batch_guard(reinterpret_cast<void *>(1), free_batch);
        auto chain_params = llama_sampler_chain_default_params();
        chain_params.no_perf = true;
        auto * raw_sampler = llama_sampler_chain_init(chain_params);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(raw_sampler, llama_sampler_free);
        auto * syntax = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!syntax) throw std::runtime_error("GRAMMAR_FAILED");
        llama_sampler_chain_add(raw_sampler, syntax);
        llama_sampler_chain_add(raw_sampler, llama_sampler_init_greedy());
        const auto begin = std::chrono::steady_clock::now();
        auto check = [&] {
            if (cancelled.load()) throw std::runtime_error("CANCELLED");
            if (std::chrono::steady_clock::now() - begin > std::chrono::minutes(5))
                throw std::runtime_error("GENERATION_TIMEOUT");
        };
        auto decode = [&](const llama_token * values, int count, int pos) {
            check();
            batch.n_tokens = count;
            for (int i = 0; i < count; ++i) {
                batch.token[i] = values[i];
                batch.pos[i] = pos + i;
                batch.n_seq_id[i] = 1;
                batch.seq_id[i][0] = 0;
                batch.logits[i] = i == count - 1;
            }
            if (llama_decode(ctx, batch) != 0)
                throw std::runtime_error(cancelled.load() ? "CANCELLED" : "DECODE_FAILED");
        };
        for (int offset = 0; offset < n; offset += 128)
            decode(tokens.data() + offset, std::min(128, n - offset), offset);
        jclass progress_class = env->GetObjectClass(progress);
        jmethodID on_progress = env->GetMethodID(progress_class, "onProgress", "(I)V");
        if (!on_progress) return nullptr;
        std::string result;
        for (int i = 0; i < limit; ++i) {
            check();
            const auto token = llama_sampler_sample(raw_sampler, ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                auto output = env->NewByteArray(result.size());
                env->SetByteArrayRegion(output, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
                return output;
            }
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (length < 0) {
                piece.resize(-length);
                length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            }
            if (length < 0 || result.size() + length > 96 * 1024) throw std::runtime_error("OUTPUT_LIMIT");
            result.append(piece.data(), length);
            if (i % 16 == 0) {
                env->CallVoidMethod(progress, on_progress, i + 1);
                if (env->ExceptionCheck()) return nullptr;
            }
            decode(&token, 1, n + i);
        }
        throw std::runtime_error("OUTPUT_LIMIT");
    } catch (const std::exception & e) { fail(env, e.what()); return nullptr; }
}
