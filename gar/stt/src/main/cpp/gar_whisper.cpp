// GAR: thin JNI layer over whisper.cpp for cc.aaro.gar.stt.WhisperEngine and SileroVad. See
// gar/DESIGN.md and gar-notes/whisper-design.md section 3.
//
// WhisperEngine makes every call on a Whisper handle from one dedicated thread, and SileroVad
// every call on a VAD handle from the capture thread, so nothing here locks. The only value shared
// with other threads is the abort flag: one byte of a direct ByteBuffer that Kotlin sets from any
// thread, read by ggml's abort callback between graph nodes during encoding and decoding.
//
// Text crosses JNI as UTF-8 byte arrays, not jstring: JNI's "modified UTF-8" encodes characters
// outside the BMP differently, and Whisper's text can end in the middle of a character.
//
// Status codes and stats indexes must match WhisperNative.kt.

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

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {

constexpr const char * LOG_TAG = "GarWhisper";

constexpr jint STATUS_OK = 0;
constexpr jint STATUS_CANCELLED = 1;
// other values: whisper_full's own (negative) error codes

// transcribe stats
constexpr int STAT_STATUS = 0;
constexpr int STAT_TOTAL_US = 1;   // the whole call, steady clock
constexpr int STAT_ENCODE_US = 2;  // the encoder (one encode per call)
constexpr int STAT_TOKENS = 3;     // text tokens of segment 0
constexpr int STATS = 4;

// transcribe scores
constexpr int SCORE_NO_SPEECH = 0;  // P(<|nospeech|>) at the SOT position, see no_speech_prob()
constexpr int SCORE_CONFIDENCE = 1; // exp(mean ln p) over the text tokens
constexpr int SCORES = 2;

// Silero's window at 16 kHz
constexpr int VAD_FRAME_SAMPLES = 512;

// 100 ms at 16 kHz, the shortest clip whisper_full transcribes
constexpr jint MIN_SAMPLES = 1600;

bool g_verbose = false;
thread_local bool t_loading = false;        // INFO lines pass while a model loads
thread_local std::string t_last_error;     // reported to Kotlin through lastError()
thread_local std::string t_last_log_error; // last ERROR line whisper.cpp logged during this call

void log_write(ggml_log_level level, const char * text) {
#ifdef __ANDROID__
    const int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
            : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
            : level == GGML_LOG_LEVEL_INFO ? ANDROID_LOG_INFO
            : ANDROID_LOG_DEBUG;
    __android_log_write(prio, LOG_TAG, text);
#else
    (void) level;
    fputs(text, stderr);
#endif
}

// WARN and ERROR always; INFO only while loading (whisper_init_state's buffer sizes), since
// whisper_vad_detect_speech_no_reset logs four INFO lines per 32 ms frame.
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

    if (level < GGML_LOG_LEVEL_WARN && !g_verbose && !(t_loading && level == GGML_LOG_LEVEL_INFO)) {
        return;
    }
    log_write(level, text);
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

// GGML_ABORT/GGML_ASSERT failures: ggml prints the message to stderr (nowhere on Android) and then
// forks to attach gdb/lldb. Log the message instead; ggml calls abort() right after.
void ggml_abort_log(const char * message) {
#ifdef __ANDROID__
    __android_log_write(ANDROID_LOG_FATAL, LOG_TAG, message);
#else
    fprintf(stderr, "%s: %s\n", LOG_TAG, message);
#endif
}

void begin_call() {
    t_last_error.clear();
    t_last_log_error.clear();
}

// Records a failure, with the last error whisper.cpp logged as the detail.
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

struct LoadingScope {
    LoadingScope() { t_loading = true; }
    ~LoadingScope() { t_loading = false; }
};

struct GarWhisper {
    whisper_context * ctx = nullptr;
    int n_threads = 4;
    const uint8_t * abort_flag = nullptr;

    bool aborted() const {
        return __atomic_load_n(abort_flag, __ATOMIC_RELAXED) != 0;
    }

    ~GarWhisper() {
        if (ctx != nullptr) {
            whisper_free(ctx);
        }
    }
};

GarWhisper * from_handle(jlong handle) {
    return reinterpret_cast<GarWhisper *>(handle);
}

whisper_vad_context * vad_from_handle(jlong handle) {
    return reinterpret_cast<whisper_vad_context *>(handle);
}

bool abort_callback(void * data) {
    return static_cast<const GarWhisper *>(data)->aborted();
}

// Whisper's no-speech probability, as OpenAI computes it: P(<|nospeech|>) at the <|startoftranscript|>
// position. whisper_full's own value (whisper_full_get_segment_no_speech_prob) reads the logits
// of the first prompt token, but only the last token's are computed; with no_timestamps the
// prompt is [SOT, <|notimestamps|>], so it reads stale data. One decoder step on SOT alone,
// against the encoder output whisper_full left in the state, costs about one token's time. It
// leaves out the initial prompt of contact names, so they can't talk the guard out of silence.
// Returns 0 (and logs) if that fails.
float no_speech_prob(GarWhisper & g) {
    const whisper_token sot = whisper_token_sot(g.ctx);
    const float * logits = whisper_decode(g.ctx, &sot, 1, 0, g.n_threads) == 0 ? whisper_get_logits(g.ctx) : nullptr;
    const int n_vocab = whisper_n_vocab(g.ctx);
    const whisper_token nosp = whisper_token_nosp(g.ctx);
    if (logits == nullptr || nosp < 0 || nosp >= n_vocab) {
        log_write(GGML_LOG_LEVEL_WARN, "can't compute the no-speech probability\n");
        return 0.0f;
    }
    float max_logit = -INFINITY;
    for (int i = 0; i < n_vocab; ++i) {
        max_logit = std::max(max_logit, logits[i]);
    }
    double sum = 0.0;
    for (int i = 0; i < n_vocab; ++i) {
        sum += std::exp(static_cast<double>(logits[i] - max_logit));
    }
    return static_cast<float>(std::exp(static_cast<double>(logits[nosp] - max_logit)) / sum);
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * /* vm */, void * /* reserved */) {
    const char * verbose = getenv("GAR_WHISPER_VERBOSE");
    g_verbose = verbose != nullptr && verbose[0] != '\0' && strcmp(verbose, "0") != 0;
#ifdef __ANDROID__
    // no fork + gdb from ggml's std::terminate handler inside the app process
    setenv("GGML_NO_BACKTRACE", "1", 0);
#endif
    ggml_set_abort_callback(ggml_abort_log);
    whisper_log_set(log_callback, nullptr);
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_cc_aaro_gar_stt_WhisperNative_version(JNIEnv * env, jclass) {
    const std::string version = std::string(whisper_version()) + " (" GAR_WHISPER_COMMIT ")";
    return env->NewStringUTF(version.c_str());
}

JNIEXPORT jstring JNICALL
Java_cc_aaro_gar_stt_WhisperNative_systemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_cc_aaro_gar_stt_WhisperNative_lastError(JNIEnv * env, jclass) {
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
Java_cc_aaro_gar_stt_WhisperNative_load(JNIEnv * env, jclass, jbyteArray path_bytes, jint n_threads,
        jobject abort_buffer) {
    begin_call();
    LoadingScope loading;
    try {
        auto * flag = static_cast<const uint8_t *>(env->GetDirectBufferAddress(abort_buffer));
        if (flag == nullptr) {
            fail("the abort flag is not a direct buffer");
            return 0;
        }
        if (n_threads <= 0) {
            fail("invalid thread count");
            return 0;
        }
        const std::string path = bytes_to_string(env, path_bytes);
        auto g = std::make_unique<GarWhisper>();
        g->abort_flag = flag;
        g->n_threads = n_threads;

        whisper_context_params cparams = whisper_context_default_params();
        cparams.use_gpu = false;
        // Not flash attention (whisper.cpp's default): at this pin its decoder attends over the
        // cross K/V padded to a multiple of 256 positions without a mask, while each encode
        // writes only audio_ctx of them, so with a small audio_ctx the text depends on whatever
        // earlier calls left in the padding. The plain path uses exactly audio_ctx positions.
        cparams.flash_attn = false;
        g->ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
        if (g->ctx == nullptr) {
            fail("can't load the model");
            return 0;
        }
        log_info(std::string("loaded ") + whisper_model_type_readable(g->ctx)
                + ", n_vocab " + std::to_string(whisper_n_vocab(g->ctx))
                + ", n_audio_ctx " + std::to_string(whisper_n_audio_ctx(g->ctx))
                + ", ftype " + std::to_string(whisper_model_ftype(g->ctx))
                + ", threads " + std::to_string(n_threads)
                + ", " + whisper_print_system_info());
        return reinterpret_cast<jlong>(g.release());
    } catch (const std::exception & e) {
        fail(std::string("can't load the model: ") + e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_cc_aaro_gar_stt_WhisperNative_free(JNIEnv *, jclass, jlong handle) {
    delete from_handle(handle);
}

// Whisper's tokens for a text, for the initial prompt of contact names (NamePrompt.kt,
// whisper-design.md section 14). Returns null on errors.
JNIEXPORT jintArray JNICALL
Java_cc_aaro_gar_stt_WhisperNative_tokenize(JNIEnv * env, jclass, jlong handle, jbyteArray text_bytes) {
    begin_call();
    GarWhisper & g = *from_handle(handle);
    try {
        const std::string text = bytes_to_string(env, text_bytes);
        if (text.size() > INT32_MAX / 2) {
            fail("the text is too long");
            return nullptr;
        }
        std::vector<whisper_token> tokens(text.size() + 1);
        int n = whisper_tokenize(g.ctx, text.c_str(), tokens.data(), static_cast<int>(tokens.size()));
        if (n < 0) {
            tokens.resize(static_cast<size_t>(-n));
            n = whisper_tokenize(g.ctx, text.c_str(), tokens.data(), static_cast<int>(tokens.size()));
        }
        if (n < 0) {
            fail("can't tokenize");
            return nullptr;
        }
        jintArray out = env->NewIntArray(n);
        if (out != nullptr) {
            env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint *>(tokens.data()));
        }
        return out;
    } catch (const std::exception & e) {
        fail(std::string("can't tokenize: ") + e.what());
        return nullptr;
    }
}

// One whisper_full call on the first n samples of pcm (16 kHz mono float). Returns segment 0's
// text (empty if Whisper wrote none, or when cancelled), or null on errors; stats[STAT_STATUS]
// says which.
JNIEXPORT jbyteArray JNICALL
Java_cc_aaro_gar_stt_WhisperNative_transcribe(JNIEnv * env, jclass, jlong handle, jfloatArray pcm_array,
        jint n, jint audio_ctx, jint max_tokens, jintArray prompt_array, jlongArray stats_out,
        jfloatArray scores_out) {
    begin_call();
    GarWhisper & g = *from_handle(handle);
    jlong stats[STATS] = {};
    jfloat scores[SCORES] = {};
    auto report = [&](jint status) {
        stats[STAT_STATUS] = status;
        if (stats_out != nullptr && env->GetArrayLength(stats_out) >= STATS) {
            env->SetLongArrayRegion(stats_out, 0, STATS, stats);
        }
        if (scores_out != nullptr && env->GetArrayLength(scores_out) >= SCORES) {
            env->SetFloatArrayRegion(scores_out, 0, SCORES, scores);
        }
    };
    try {
        const int64_t t0 = now_us();
        if (pcm_array == nullptr || n < 0 || n > env->GetArrayLength(pcm_array)) {
            fail("invalid sample count");
            report(-1);
            return nullptr;
        }
        if (audio_ctx <= 0 || max_tokens <= 0) {
            fail("invalid audio_ctx or max_tokens");
            report(-1);
            return nullptr;
        }
        if (n < MIN_SAMPLES) {
            // whisper_full skips clips under 100 ms anyway, and with 0 samples it would reuse the
            // previous call's mel spectrogram
            report(STATUS_OK);
            return string_to_bytes(env, "");
        }
        // a copy, not GetPrimitiveArrayCritical: the call lasts hundreds of milliseconds
        std::vector<float> pcm(static_cast<size_t>(n));
        env->GetFloatArrayRegion(pcm_array, 0, n, pcm.data());

        std::vector<whisper_token> prompt;
        if (prompt_array != nullptr) {
            prompt.resize(static_cast<size_t>(env->GetArrayLength(prompt_array)));
            env->GetIntArrayRegion(prompt_array, 0, static_cast<jsize>(prompt.size()),
                    reinterpret_cast<jint *>(prompt.data()));
            // whisper.cpp indexes its embeddings with these unchecked
            const whisper_token n_vocab = whisper_n_vocab(g.ctx);
            for (const whisper_token token : prompt) {
                if (token < 0 || token >= n_vocab) {
                    fail("invalid prompt token " + std::to_string(token));
                    report(-1);
                    return nullptr;
                }
            }
        }

        // whisper-design.md 3.2
        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.greedy.best_of = 1;
        p.temperature = 0.0f;
        p.temperature_inc = 0.0f; // no fallback re-decodes
        p.n_threads = g.n_threads;
        p.language = "en";
        p.detect_language = false;
        p.translate = false;
        p.no_context = true;
        p.no_timestamps = true;
        p.single_segment = true;
        p.print_progress = false;
        p.print_timestamps = false;
        p.print_realtime = false;
        p.print_special = false;
        p.suppress_blank = true;
        p.suppress_nst = false; // would ban ":", "+", "/" and more as tokens
        p.max_tokens = max_tokens;
        p.audio_ctx = audio_ctx;
        p.prompt_tokens = prompt.empty() ? nullptr : prompt.data();
        p.prompt_n_tokens = static_cast<int>(prompt.size());
        // Never drop the segment as silence inside whisper.cpp: the caller gets the text with
        // noSpeechProb and confidence and decides (TranscriptFilter).
        p.no_speech_thold = 1.0f;
        p.vad = false;
        p.abort_callback = abort_callback;
        p.abort_callback_user_data = &g;

        whisper_reset_timings(g.ctx);
        const int res = whisper_full(g.ctx, p, pcm.data(), n);
        stats[STAT_TOTAL_US] = now_us() - t0;
        if (whisper_timings * timings = whisper_get_timings(g.ctx)) {
            stats[STAT_ENCODE_US] = static_cast<jlong>(std::llround(timings->encode_ms * 1000.0));
            delete timings;
        }

        if (g.aborted()) {
            // aborts surface as -6 to -9, or as a short result when they hit between tokens
            report(STATUS_CANCELLED);
            return string_to_bytes(env, "");
        }
        if (res != 0) {
            fail("whisper_full failed with " + std::to_string(res));
            report(res);
            return nullptr;
        }

        std::string text;
        if (whisper_full_n_segments(g.ctx) > 0) {
            const char * segment = whisper_full_get_segment_text(g.ctx, 0);
            if (segment != nullptr) {
                text = segment;
            }
            scores[SCORE_NO_SPEECH] = no_speech_prob(g);
            const whisper_token eot = whisper_token_eot(g.ctx);
            const int n_tokens = whisper_full_n_tokens(g.ctx, 0);
            double sum_log_p = 0.0;
            int n_text = 0;
            for (int i = 0; i < n_tokens; ++i) {
                if (whisper_full_get_token_id(g.ctx, 0, i) < eot) {
                    const float token_p = whisper_full_get_token_p(g.ctx, 0, i);
                    sum_log_p += std::log(std::max(token_p, 1e-10f));
                    ++n_text;
                }
            }
            stats[STAT_TOKENS] = n_text;
            scores[SCORE_CONFIDENCE] = n_text > 0 ? static_cast<float>(std::exp(sum_log_p / n_text)) : 0.0f;
        }
        stats[STAT_TOTAL_US] = now_us() - t0;
        report(STATUS_OK);
        return string_to_bytes(env, text);
    } catch (const std::exception & e) {
        fail(std::string("transcription failed: ") + e.what());
        report(-1);
        return nullptr;
    }
}

JNIEXPORT jlong JNICALL
Java_cc_aaro_gar_stt_WhisperNative_vadLoad(JNIEnv * env, jclass, jbyteArray path_bytes, jint n_threads) {
    begin_call();
    LoadingScope loading;
    try {
        const std::string path = bytes_to_string(env, path_bytes);
        whisper_vad_context_params vparams = whisper_vad_default_context_params();
        vparams.n_threads = n_threads > 0 ? n_threads : 1;
        vparams.use_gpu = false;
        whisper_vad_context * vad = whisper_vad_init_from_file_with_params(path.c_str(), vparams);
        if (vad == nullptr) {
            fail("can't load the VAD model");
            return 0;
        }
        return reinterpret_cast<jlong>(vad);
    } catch (const std::exception & e) {
        fail(std::string("can't load the VAD model: ") + e.what());
        return 0;
    }
}

// Speech probability of one 512-sample frame, continuing from the previous frames. -1 on errors.
JNIEXPORT jfloat JNICALL
Java_cc_aaro_gar_stt_WhisperNative_vadProbability(JNIEnv * env, jclass, jlong handle, jfloatArray frame) {
    begin_call();
    try {
        if (frame == nullptr || env->GetArrayLength(frame) != VAD_FRAME_SAMPLES) {
            fail("a VAD frame must have 512 samples");
            return -1.0f;
        }
        float samples[VAD_FRAME_SAMPLES];
        env->GetFloatArrayRegion(frame, 0, VAD_FRAME_SAMPLES, samples);
        whisper_vad_context * vad = vad_from_handle(handle);
        // A failed VAD graph still returns true and leaves the previous frame's probability in
        // place, so a logged ERROR line counts as a failure too.
        if (!whisper_vad_detect_speech_no_reset(vad, samples, VAD_FRAME_SAMPLES) || whisper_vad_n_probs(vad) < 1
                || !t_last_log_error.empty()) {
            fail("VAD failed");
            return -1.0f;
        }
        return whisper_vad_probs(vad)[0];
    } catch (const std::exception & e) {
        fail(std::string("VAD failed: ") + e.what());
        return -1.0f;
    }
}

JNIEXPORT void JNICALL
Java_cc_aaro_gar_stt_WhisperNative_vadReset(JNIEnv *, jclass, jlong handle) {
    whisper_vad_reset_state(vad_from_handle(handle));
}

JNIEXPORT void JNICALL
Java_cc_aaro_gar_stt_WhisperNative_vadFree(JNIEnv *, jclass, jlong handle) {
    whisper_vad_free(vad_from_handle(handle));
}

} // extern "C"
