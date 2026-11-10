// GAR: thin JNI layer over llama.cpp for cc.aaro.gar.llm.LlamaEngine. See gar/DESIGN.md.
//
// LlamaEngine makes every call into this file from one dedicated thread, so nothing here locks.
// The only value shared with other threads is the abort flag: one byte of a direct ByteBuffer
// that Kotlin sets from any thread, read here between tokens and by llama.cpp's abort callback
// during llama_decode.
//
// Text crosses JNI as UTF-8 byte arrays, not jstring: JNI's "modified UTF-8" encodes characters
// outside the BMP differently, and generated text can end in the middle of a character.
//
// Status codes, stats indexes and stop reasons must match LlamaNative.kt.

#include <jni.h>

#include <algorithm>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {

constexpr const char * LOG_TAG = "GarLlama";

constexpr jint STATUS_OK = 0;
constexpr jint STATUS_CANCELLED = 1;
constexpr jint STATUS_ERROR = -1;

constexpr jlong STOP_END_OF_GENERATION = 0;
constexpr jlong STOP_STOP_STRING = 1;
constexpr jlong STOP_MAX_TOKENS = 2;
constexpr jlong STOP_CANCELLED = 3;

// evalPrefix stats
constexpr int PREFIX_TOKENS = 0;     // tokens in the prefix
constexpr int PREFIX_REUSED = 1;     // prefix tokens that were not evaluated (kept in memory or restored)
constexpr int PREFIX_RESTORED = 2;   // 1 if the prefix came from the state file
constexpr int PREFIX_MICROS = 3;
constexpr int PREFIX_STATS = 4;

// complete stats
constexpr int COMPLETE_STOP_REASON = 0;
constexpr int COMPLETE_SUFFIX_TOKENS = 1;
constexpr int COMPLETE_GENERATED = 2;      // sampled tokens, including a final end-of-generation token
constexpr int COMPLETE_PREFILL_MICROS = 3;
constexpr int COMPLETE_GENERATE_MICROS = 4;
constexpr int COMPLETE_STATS = 5;

bool g_verbose = false;
thread_local std::string t_last_error;     // reported to Kotlin through lastError()
thread_local std::string t_last_log_error; // last ERROR line llama.cpp logged during this call

void log_callback(ggml_log_level level, const char * text, void * /* user_data */) {
    thread_local ggml_log_level last_level = GGML_LOG_LEVEL_INFO;
    const bool continued = level == GGML_LOG_LEVEL_CONT;
    if (continued) {
        level = last_level;
    } else {
        last_level = level;
    }

    if (level == GGML_LOG_LEVEL_ERROR) {
        if (!continued) {
            t_last_log_error.clear();
        }
        t_last_log_error += text;
        while (!t_last_log_error.empty() && t_last_log_error.back() == '\n') {
            t_last_log_error.pop_back();
        }
    }

    if (level < GGML_LOG_LEVEL_WARN && !g_verbose) {
        return;
    }
#ifdef __ANDROID__
    const int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
            : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
            : level == GGML_LOG_LEVEL_INFO ? ANDROID_LOG_INFO
            : ANDROID_LOG_DEBUG;
    __android_log_write(prio, LOG_TAG, text);
#else
    fputs(text, stderr);
#endif
}

void log_info(const std::string & msg) {
#ifdef __ANDROID__
    __android_log_write(ANDROID_LOG_INFO, LOG_TAG, msg.c_str());
#else
    if (g_verbose) {
        fprintf(stderr, "%s: %s\n", LOG_TAG, msg.c_str());
    }
#endif
}

void begin_call() {
    t_last_error.clear();
    t_last_log_error.clear();
}

// Records a failure, with the last error llama.cpp logged as the detail.
void fail(const std::string & msg) {
    t_last_error = msg;
    if (!t_last_log_error.empty()) {
        t_last_error += ": " + t_last_log_error;
    }
}

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string bytes_to_string(JNIEnv * env, jbyteArray array) {
    if (array == nullptr) {
        return {};
    }
    const jsize n = env->GetArrayLength(array);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

jbyteArray string_to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(s.size()));
    if (array != nullptr) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(s.size()),
                reinterpret_cast<const jbyte *>(s.data()));
    }
    return array;
}

void set_stats(JNIEnv * env, jlongArray array, const jlong * values, int n) {
    if (array != nullptr && env->GetArrayLength(array) >= n) {
        env->SetLongArrayRegion(array, 0, n, values);
    }
}

struct GarLlama {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_batch batch{};
    int32_t n_batch = 0;
    int32_t n_ctx = 0;
    int32_t n_vocab = 0;
    const uint8_t * abort_flag = nullptr;

    // prefix tokens at positions [0, prefix.size()) of sequence 0, valid if has_prefix
    std::vector<llama_token> prefix;
    bool has_prefix = false;
    // Recurrent and hybrid models can't drop the end of a sequence, so their state right after
    // the prefix is kept here and restored before each completion instead.
    bool needs_snapshot = false;
    std::vector<uint8_t> snapshot;

    std::vector<llama_token_data> candidates; // n_vocab entries, for grammar sampling

    bool aborted() const {
        return __atomic_load_n(abort_flag, __ATOMIC_RELAXED) != 0;
    }

    llama_memory_t memory() const {
        return llama_get_memory(ctx);
    }

    ~GarLlama() {
        if (batch.token != nullptr) {
            llama_batch_free(batch);
        }
        if (ctx != nullptr) {
            llama_free(ctx);
        }
        if (model != nullptr) {
            llama_model_free(model);
        }
    }

    void forget_prefix() {
        prefix.clear();
        has_prefix = false;
        snapshot.clear();
        llama_memory_clear(memory(), false);
    }

    bool take_snapshot() {
        if (!needs_snapshot) {
            return true;
        }
        snapshot.resize(llama_state_seq_get_size(ctx, 0));
        return llama_state_seq_get_data(ctx, snapshot.data(), snapshot.size(), 0) == snapshot.size();
    }

    // Drops everything after the prefix from sequence 0.
    bool rewind_to_prefix() {
        if (!needs_snapshot) {
            return llama_memory_seq_rm(memory(), 0, static_cast<llama_pos>(prefix.size()), -1);
        }
        llama_memory_clear(memory(), false);
        return snapshot.empty()
                ? prefix.empty()
                : llama_state_seq_set_data(ctx, snapshot.data(), snapshot.size(), 0) > 0;
    }
};

bool abort_callback(void * data) {
    return static_cast<const GarLlama *>(data)->aborted();
}

// GGML_ABORT/GGML_ASSERT failures: ggml prints the message to stderr (nowhere on Android) and then
// forks to attach gdb/lldb. Log the message instead; ggml calls abort() right after.
void ggml_abort_log(const char * message) {
#ifdef __ANDROID__
    __android_log_write(ANDROID_LOG_FATAL, LOG_TAG, message);
#else
    fprintf(stderr, "%s: %s\n", LOG_TAG, message);
#endif
}

GarLlama * from_handle(jlong handle) {
    return reinterpret_cast<GarLlama *>(handle);
}

// Tokenizes with special tokens parsed, i.e. "<|im_start|>" in the text becomes that token.
bool tokenize(const GarLlama & g, const std::string & text, bool add_special, std::vector<llama_token> & out) {
    if (text.size() > INT32_MAX / 2) {
        return false;
    }
    out.resize(text.size() + 2);
    int32_t n = llama_tokenize(g.vocab, text.data(), static_cast<int32_t>(text.size()),
            out.data(), static_cast<int32_t>(out.size()), add_special, true);
    if (n < 0 && n != INT32_MIN) {
        out.resize(static_cast<size_t>(-n));
        n = llama_tokenize(g.vocab, text.data(), static_cast<int32_t>(text.size()),
                out.data(), static_cast<int32_t>(out.size()), add_special, true);
    }
    if (n < 0) {
        return false;
    }
    out.resize(static_cast<size_t>(n));

    // a prompt that already starts with the BOS text gets it twice when the model adds one
    const llama_token bos = llama_vocab_bos(g.vocab);
    if (add_special && out.size() >= 2 && bos != LLAMA_TOKEN_NULL && out[0] == bos && out[1] == bos) {
        out.erase(out.begin());
    }
    return true;
}

void append_piece(const GarLlama & g, llama_token token, std::string & out) {
    char buf[256];
    const int32_t n = llama_token_to_piece(g.vocab, token, buf, sizeof(buf), 0, true);
    if (n >= 0) {
        out.append(buf, static_cast<size_t>(n));
        return;
    }
    std::string big(static_cast<size_t>(-n), '\0');
    const int32_t m = llama_token_to_piece(g.vocab, token, big.data(), static_cast<int32_t>(big.size()), 0, true);
    if (m > 0) {
        out.append(big.data(), static_cast<size_t>(m));
    }
}

// Decodes tokens into sequence 0 starting at pos0, in n_batch chunks. Logits are computed for the
// last token only if `logits_last`. Returns llama_decode's result: 0 ok, 2 aborted, other errors.
int32_t decode(GarLlama & g, const llama_token * tokens, size_t n, llama_pos pos0, bool logits_last) {
    for (size_t i = 0; i < n; i += static_cast<size_t>(g.n_batch)) {
        const size_t m = std::min(static_cast<size_t>(g.n_batch), n - i);
        g.batch.n_tokens = static_cast<int32_t>(m);
        for (size_t j = 0; j < m; ++j) {
            g.batch.token[j] = tokens[i + j];
            g.batch.pos[j] = pos0 + static_cast<llama_pos>(i + j);
            g.batch.n_seq_id[j] = 1;
            g.batch.seq_id[j][0] = 0;
            g.batch.logits[j] = logits_last && i + j == n - 1;
        }
        if (g.aborted()) {
            return 2;
        }
        const int32_t res = llama_decode(g.ctx, g.batch);
        if (res != 0) {
            return res;
        }
    }
    return 0;
}

// Greedy sampling with grammar rejection sampling: the most likely token is checked against the
// grammar on its own, and only if the grammar rejects it is the grammar applied to the whole
// vocabulary (which is slow with 150k-260k tokens). Returns LLAMA_TOKEN_NULL if no token is allowed.
llama_token sample(GarLlama & g, llama_sampler * grammar) {
    const float * logits = llama_get_logits_ith(g.ctx, -1);
    if (logits == nullptr) {
        return LLAMA_TOKEN_NULL;
    }
    llama_token best = 0;
    for (llama_token i = 1; i < g.n_vocab; ++i) {
        if (logits[i] > logits[best]) {
            best = i;
        }
    }
    if (grammar == nullptr) {
        return best;
    }

    llama_token_data single = { best, logits[best], 0.0f };
    llama_token_data_array single_array = { &single, 1, -1, false };
    llama_sampler_apply(grammar, &single_array);
    if (single_array.data[0].logit != -INFINITY) {
        return best;
    }

    for (llama_token i = 0; i < g.n_vocab; ++i) {
        g.candidates[static_cast<size_t>(i)] = { i, logits[i], 0.0f };
    }
    llama_token_data_array all = { g.candidates.data(), g.candidates.size(), -1, false };
    llama_sampler_apply(grammar, &all);
    best = LLAMA_TOKEN_NULL;
    float best_logit = -INFINITY;
    for (size_t i = 0; i < all.size; ++i) {
        if (all.data[i].logit > best_logit) {
            best_logit = all.data[i].logit;
            best = all.data[i].id;
        }
    }
    return best;
}

// Returns where the earliest stop string starting at or after `from` ends, or npos.
size_t find_stop(const std::string & text, size_t from, const std::vector<std::string> & stops) {
    size_t best_pos = std::string::npos;
    size_t best_end = std::string::npos;
    for (const auto & stop : stops) {
        const size_t pos = text.find(stop, from);
        if (pos != std::string::npos && pos < best_pos) {
            best_pos = pos;
            best_end = pos + stop.size();
        }
    }
    return best_end;
}

struct SamplerDeleter {
    void operator()(llama_sampler * s) const { llama_sampler_free(s); }
};

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * /* vm */, void * /* reserved */) {
    const char * verbose = getenv("GAR_LLAMA_VERBOSE");
    g_verbose = verbose != nullptr && verbose[0] != '\0' && strcmp(verbose, "0") != 0;
#ifdef __ANDROID__
    // no fork + gdb from ggml's std::terminate handler inside the app process
    setenv("GGML_NO_BACKTRACE", "1", 0);
#endif
    ggml_set_abort_callback(ggml_abort_log);
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_cc_aaro_gar_llm_LlamaNative_version(JNIEnv * env, jclass) {
    const std::string version = std::string(llama_version()) + " (" GAR_LLAMA_COMMIT ")";
    return env->NewStringUTF(version.c_str());
}

JNIEXPORT jstring JNICALL
Java_cc_aaro_gar_llm_LlamaNative_systemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_cc_aaro_gar_llm_LlamaNative_lastError(JNIEnv * env, jclass) {
    std::string ascii = t_last_error;
    // NewStringUTF wants modified UTF-8, and log lines may contain anything
    for (char & c : ascii) {
        if (static_cast<unsigned char>(c) >= 0x80 || c == '\0') {
            c = '?';
        }
    }
    return env->NewStringUTF(ascii.c_str());
}

JNIEXPORT jlong JNICALL
Java_cc_aaro_gar_llm_LlamaNative_load(JNIEnv * env, jclass, jbyteArray path_bytes, jint n_ctx,
        jint n_threads, jint n_batch, jboolean repack, jobject abort_buffer) {
    begin_call();
    try {
        auto * flag = static_cast<const uint8_t *>(env->GetDirectBufferAddress(abort_buffer));
        if (flag == nullptr) {
            fail("the abort flag is not a direct buffer");
            return 0;
        }
        if (n_ctx <= 0 || n_threads <= 0 || n_batch <= 0) {
            fail("invalid context parameters");
            return 0;
        }
        const std::string path = bytes_to_string(env, path_bytes);
        auto g = std::make_unique<GarLlama>();
        g->abort_flag = flag;

        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0;
        mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
        mparams.use_extra_bufts = repack;
        // returning false aborts the load
        mparams.progress_callback = [](float, void * data) { return !static_cast<GarLlama *>(data)->aborted(); };
        mparams.progress_callback_user_data = g.get();
        g->model = llama_model_load_from_file(path.c_str(), mparams);
        if (g->model == nullptr) {
            fail(g->aborted() ? "loading was cancelled" : "can't load the model");
            return 0;
        }
        g->vocab = llama_model_get_vocab(g->model);

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = static_cast<uint32_t>(n_ctx);
        cparams.n_batch = static_cast<uint32_t>(n_batch);
        cparams.n_ubatch = static_cast<uint32_t>(n_batch);
        // only the last token's logits are ever requested (see decode()), so don't reserve output
        // buffers for n_batch rows of logits (151936 x 512 floats = 297 MiB for Qwen3)
        cparams.n_outputs_max = 1;
        cparams.n_seq_max = 1;
        cparams.n_threads = n_threads;
        cparams.n_threads_batch = n_threads;
        // keep the whole sliding window (Gemma 3), so that dropping a suffix restores exactly the
        // state after the prefix and cached and uncached runs give the same output
        cparams.swa_full = true;
        cparams.no_perf = true;
        cparams.abort_callback = abort_callback;
        cparams.abort_callback_data = g.get();
        g->ctx = llama_init_from_model(g->model, cparams);
        if (g->ctx == nullptr) {
            fail("can't create the context");
            return 0;
        }

        g->n_ctx = static_cast<int32_t>(llama_n_ctx(g->ctx));
        g->n_batch = static_cast<int32_t>(std::min(llama_n_batch(g->ctx), llama_n_ubatch(g->ctx)));
        g->n_vocab = llama_vocab_n_tokens(g->vocab);
        g->batch = llama_batch_init(g->n_batch, 0, 1);
        g->candidates.resize(static_cast<size_t>(g->n_vocab));
        g->needs_snapshot = llama_model_is_recurrent(g->model) || llama_model_is_hybrid(g->model);

        char desc[128];
        llama_model_desc(g->model, desc, sizeof(desc));
        log_info(std::string("loaded ") + desc + ", n_ctx " + std::to_string(g->n_ctx)
                + ", n_batch " + std::to_string(g->n_batch) + ", threads " + std::to_string(n_threads)
                + ", " + llama_print_system_info());
        return reinterpret_cast<jlong>(g.release());
    } catch (const std::exception & e) {
        fail(std::string("can't load the model: ") + e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_cc_aaro_gar_llm_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    delete from_handle(handle);
}

JNIEXPORT jint JNICALL
Java_cc_aaro_gar_llm_LlamaNative_tokenCount(JNIEnv * env, jclass, jlong handle, jbyteArray text,
        jboolean add_special) {
    begin_call();
    try {
        std::vector<llama_token> tokens;
        if (!tokenize(*from_handle(handle), bytes_to_string(env, text), add_special, tokens)) {
            fail("can't tokenize");
            return -1;
        }
        return static_cast<jint>(tokens.size());
    } catch (const std::exception & e) {
        fail(std::string("can't tokenize: ") + e.what());
        return -1;
    }
}

// Makes `text` (tokenized with BOS as the model wants it) the cached prefix of sequence 0: nothing
// to do if it is the current prefix, else it is restored from `cache_path` (a llama_state_seq
// file) if that exists and holds exactly these tokens, else evaluated.
JNIEXPORT jint JNICALL
Java_cc_aaro_gar_llm_LlamaNative_evalPrefix(JNIEnv * env, jclass, jlong handle, jbyteArray text,
        jbyteArray cache_path, jlongArray stats_out) {
    begin_call();
    GarLlama & g = *from_handle(handle);
    try {
        const int64_t t0 = now_us();
        jlong stats[PREFIX_STATS] = {};
        std::vector<llama_token> tokens;
        if (!tokenize(g, bytes_to_string(env, text), true, tokens)) {
            fail("can't tokenize the prefix");
            return STATUS_ERROR;
        }
        stats[PREFIX_TOKENS] = static_cast<jlong>(tokens.size());
        if (static_cast<int64_t>(tokens.size()) >= g.n_ctx) {
            fail("the prefix has " + std::to_string(tokens.size()) + " tokens, the context only "
                    + std::to_string(g.n_ctx));
            return STATUS_ERROR;
        }

        if (g.has_prefix && tokens == g.prefix) {
            stats[PREFIX_REUSED] = static_cast<jlong>(tokens.size());
            stats[PREFIX_MICROS] = now_us() - t0;
            set_stats(env, stats_out, stats, PREFIX_STATS);
            return STATUS_OK;
        }

        const std::string path = bytes_to_string(env, cache_path);
        if (!path.empty() && !tokens.empty()) {
            FILE * f = fopen(path.c_str(), "rb");
            if (f != nullptr) {
                fclose(f);
                g.forget_prefix();
                std::vector<llama_token> file_tokens(tokens.size());
                size_t n_file_tokens = 0;
                const size_t read = llama_state_seq_load_file(g.ctx, path.c_str(), 0,
                        file_tokens.data(), file_tokens.size(), &n_file_tokens);
                file_tokens.resize(read > 0 ? n_file_tokens : 0);
                if (read > 0 && file_tokens == tokens
                        && llama_memory_seq_pos_max(g.memory(), 0) == static_cast<llama_pos>(tokens.size()) - 1
                        && g.take_snapshot()) {
                    g.prefix = tokens;
                    g.has_prefix = true;
                    stats[PREFIX_REUSED] = static_cast<jlong>(tokens.size());
                    stats[PREFIX_RESTORED] = 1;
                    stats[PREFIX_MICROS] = now_us() - t0;
                    set_stats(env, stats_out, stats, PREFIX_STATS);
                    return STATUS_OK;
                }
                log_info("ignoring the prefix state file " + path);
                g.forget_prefix();
            }
        }

        // Evaluate the whole prefix, even if it starts like the old one: keeping the common start
        // would split the rest into different batches, which changes the logits slightly and so
        // can change the output (seen with FunctionGemma). The same prefix always gives the same
        // KV cache this way, also when it comes from a state file.
        g.forget_prefix();
        const int32_t res = decode(g, tokens.data(), tokens.size(), 0, false);
        if (res != 0 || !g.take_snapshot()) {
            g.forget_prefix();
            if (res == 2) {
                stats[PREFIX_MICROS] = now_us() - t0;
                set_stats(env, stats_out, stats, PREFIX_STATS);
                return STATUS_CANCELLED;
            }
            fail("can't evaluate the prefix (llama_decode " + std::to_string(res) + ")");
            return STATUS_ERROR;
        }
        g.prefix = std::move(tokens);
        g.has_prefix = true;
        stats[PREFIX_MICROS] = now_us() - t0;
        set_stats(env, stats_out, stats, PREFIX_STATS);
        return STATUS_OK;
    } catch (const std::exception & e) {
        g.forget_prefix();
        fail(std::string("can't evaluate the prefix: ") + e.what());
        return STATUS_ERROR;
    }
}

// Writes the state of the current prefix to `path` (llama_state_seq file with the prefix tokens).
JNIEXPORT jboolean JNICALL
Java_cc_aaro_gar_llm_LlamaNative_savePrefix(JNIEnv * env, jclass, jlong handle, jbyteArray path_bytes) {
    begin_call();
    GarLlama & g = *from_handle(handle);
    try {
        if (!g.has_prefix || g.prefix.empty()) {
            fail("no prefix to save");
            return JNI_FALSE;
        }
        if (!g.rewind_to_prefix()) {
            g.forget_prefix();
            fail("can't rewind to the prefix");
            return JNI_FALSE;
        }
        const std::string path = bytes_to_string(env, path_bytes);
        if (llama_state_seq_save_file(g.ctx, path.c_str(), 0, g.prefix.data(), g.prefix.size()) == 0) {
            fail("can't save the prefix state");
            return JNI_FALSE;
        }
        return JNI_TRUE;
    } catch (const std::exception & e) {
        fail(std::string("can't save the prefix state: ") + e.what());
        return JNI_FALSE;
    }
}

// Continues the cached prefix with `suffix` and generates greedily, optionally constrained by a
// GBNF grammar. Returns the generated text (also when cancelled), or null on errors.
JNIEXPORT jbyteArray JNICALL
Java_cc_aaro_gar_llm_LlamaNative_complete(JNIEnv * env, jclass, jlong handle, jbyteArray suffix_bytes,
        jbyteArray grammar_bytes, jint max_tokens, jobjectArray stop_arrays, jlongArray stats_out) {
    begin_call();
    GarLlama & g = *from_handle(handle);
    try {
        const int64_t t0 = now_us();
        jlong stats[COMPLETE_STATS] = {};
        stats[COMPLETE_STOP_REASON] = STOP_CANCELLED;

        if (!g.has_prefix) {
            fail("no prefix was evaluated");
            return nullptr;
        }
        if (!g.rewind_to_prefix()) {
            g.forget_prefix();
            fail("can't rewind to the prefix");
            return nullptr;
        }

        std::vector<llama_token> suffix;
        if (!tokenize(g, bytes_to_string(env, suffix_bytes), false, suffix)) {
            fail("can't tokenize the suffix");
            return nullptr;
        }
        if (suffix.empty()) {
            fail("the suffix is empty");
            return nullptr;
        }
        stats[COMPLETE_SUFFIX_TOKENS] = static_cast<jlong>(suffix.size());
        const auto n_prefix = static_cast<llama_pos>(g.prefix.size());
        if (n_prefix + static_cast<int64_t>(suffix.size()) >= g.n_ctx) {
            fail("the prompt has " + std::to_string(n_prefix + suffix.size()) + " tokens, the context only "
                    + std::to_string(g.n_ctx));
            return nullptr;
        }

        std::unique_ptr<llama_sampler, SamplerDeleter> grammar;
        if (grammar_bytes != nullptr) {
            const std::string gbnf = bytes_to_string(env, grammar_bytes);
            grammar.reset(llama_sampler_init_grammar(g.vocab, gbnf.c_str(), "root"));
            if (!grammar) {
                fail("invalid grammar");
                return nullptr;
            }
        }

        std::vector<std::string> stops;
        size_t max_stop = 0;
        const jsize n_stops = stop_arrays == nullptr ? 0 : env->GetArrayLength(stop_arrays);
        for (jsize i = 0; i < n_stops; ++i) {
            auto * array = static_cast<jbyteArray>(env->GetObjectArrayElement(stop_arrays, i));
            std::string stop = bytes_to_string(env, array);
            env->DeleteLocalRef(array);
            if (!stop.empty()) {
                max_stop = std::max(max_stop, stop.size());
                stops.push_back(std::move(stop));
            }
        }

        std::string text;
        int32_t res = decode(g, suffix.data(), suffix.size(), n_prefix, true);
        const int64_t t1 = now_us();
        stats[COMPLETE_PREFILL_MICROS] = t1 - t0;
        if (res == 0) {
            llama_pos pos = n_prefix + static_cast<llama_pos>(suffix.size());
            int32_t generated = 0;
            stats[COMPLETE_STOP_REASON] = STOP_MAX_TOKENS;
            while (generated < max_tokens) {
                if (g.aborted()) {
                    stats[COMPLETE_STOP_REASON] = STOP_CANCELLED;
                    break;
                }
                const llama_token token = sample(g, grammar.get());
                if (token == LLAMA_TOKEN_NULL) {
                    fail("the grammar allows no token after \"" + text + "\"");
                    return nullptr;
                }
                if (grammar) {
                    llama_sampler_accept(grammar.get(), token);
                }
                ++generated;
                if (llama_vocab_is_eog(g.vocab, token)) {
                    stats[COMPLETE_STOP_REASON] = STOP_END_OF_GENERATION;
                    break;
                }

                const size_t old_size = text.size();
                append_piece(g, token, text);
                if (!stops.empty()) {
                    const size_t from = old_size >= max_stop ? old_size - max_stop + 1 : 0;
                    const size_t end = find_stop(text, from, stops);
                    if (end != std::string::npos) {
                        text.resize(end);
                        stats[COMPLETE_STOP_REASON] = STOP_STOP_STRING;
                        break;
                    }
                }
                if (generated >= max_tokens || pos >= g.n_ctx) {
                    stats[COMPLETE_STOP_REASON] = STOP_MAX_TOKENS;
                    break;
                }

                res = decode(g, &token, 1, pos, true);
                if (res != 0) {
                    break;
                }
                ++pos;
            }
            stats[COMPLETE_GENERATED] = generated;
        }
        stats[COMPLETE_GENERATE_MICROS] = now_us() - t1;

        if (res == 2) {
            stats[COMPLETE_STOP_REASON] = STOP_CANCELLED;
        } else if (res != 0) {
            fail("llama_decode failed with " + std::to_string(res));
            return nullptr;
        }
        set_stats(env, stats_out, stats, COMPLETE_STATS);
        return string_to_bytes(env, text);
    } catch (const std::exception & e) {
        fail(std::string("completion failed: ") + e.what());
        return nullptr;
    }
}

} // extern "C"
