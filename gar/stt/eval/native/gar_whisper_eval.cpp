// GAR: the speech eval's decoder. Loads one Whisper model and transcribes clips with exactly the
// whisper_full_params of gar/stt/src/main/cpp/gar_whisper.cpp (whisper-design.md 3.2): greedy,
// best_of 1, no fallback, no timestamps, one segment, flash attention off, the caller's audio_ctx
// and prompt tokens. whisper-cli has no single_segment or max_tokens switch and tokenizes its
// --prompt differently, so the eval uses this instead.
//
// usage: gar_whisper_eval MODEL [THREADS] < jobs
//   one job per line, tab separated: ID, WAV path (16 kHz mono PCM16), audio_ctx, prompt text
//   (may be empty; tokenized with whisper_tokenize, as WhisperEngine.tokenize does)
//   prints one JSON object per job: id, text, audio_ctx, samples, prompt_tokens, tokens,
//   confidence, encode_ms, total_ms (or id and error)
// usage: gar_whisper_eval MODEL --tokenize < texts
//   prints how many tokens each line takes (whisper_tokenize), one number per line, for run.py's
//   copy of NamePrompt.fit
#include "whisper.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr int MAX_TOKENS = 64; // WhisperEngine's limit for commands
constexpr int MIN_SAMPLES = 1600; // gar_whisper.cpp: under 100 ms returns "" without whisper_full

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

uint32_t le32(const char * p) {
    return static_cast<uint8_t>(p[0]) | static_cast<uint8_t>(p[1]) << 8
            | static_cast<uint8_t>(p[2]) << 16 | static_cast<uint32_t>(static_cast<uint8_t>(p[3])) << 24;
}

uint16_t le16(const char * p) {
    return static_cast<uint16_t>(static_cast<uint8_t>(p[0]) | static_cast<uint8_t>(p[1]) << 8);
}

// 16 kHz mono PCM16 only, as WavReader; samples scaled by 1/32768
bool read_wav(const std::string & path, std::vector<float> & out, std::string & error) {
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        error = "can't open " + path;
        return false;
    }
    std::string data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (data.size() < 12 || data.compare(0, 4, "RIFF") != 0 || data.compare(8, 4, "WAVE") != 0) {
        error = "not a WAV file";
        return false;
    }
    bool format_ok = false;
    size_t pos = 12;
    while (pos + 8 <= data.size()) {
        const std::string id = data.substr(pos, 4);
        const size_t size = le32(data.data() + pos + 4);
        const size_t body = pos + 8;
        if (id == "fmt " && body + 16 <= data.size()) {
            const uint16_t format = le16(data.data() + body);
            const uint16_t channels = le16(data.data() + body + 2);
            const uint32_t rate = le32(data.data() + body + 4);
            const uint16_t bits = le16(data.data() + body + 14);
            format_ok = (format == 1 || format == 0xFFFE) && channels == 1 && rate == 16000 && bits == 16;
            if (!format_ok) {
                error = "not 16 kHz mono PCM16";
                return false;
            }
        } else if (id == "data" && format_ok) {
            const size_t n = std::min(size, data.size() - body) / 2;
            out.resize(n);
            for (size_t i = 0; i < n; ++i) {
                out[i] = static_cast<int16_t>(le16(data.data() + body + 2 * i)) / 32768.0f;
            }
            return true;
        }
        pos = body + size + (size & 1);
    }
    error = "no data chunk";
    return false;
}

std::string json_string(const std::string & s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out + "\"";
}

std::vector<std::string> split_tabs(const std::string & line) {
    std::vector<std::string> fields;
    std::string field;
    std::istringstream in(line);
    while (std::getline(in, field, '\t')) {
        fields.push_back(field);
    }
    if (!line.empty() && line.back() == '\t') {
        fields.emplace_back();
    }
    return fields;
}

std::vector<whisper_token> tokenize(whisper_context * ctx, const std::string & text) {
    if (text.empty()) {
        return {};
    }
    std::vector<whisper_token> tokens(text.size() + 1);
    int n = whisper_tokenize(ctx, text.c_str(), tokens.data(), static_cast<int>(tokens.size()));
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = whisper_tokenize(ctx, text.c_str(), tokens.data(), static_cast<int>(tokens.size()));
    }
    tokens.resize(static_cast<size_t>(std::max(n, 0)));
    return tokens;
}

void quiet_log(ggml_log_level, const char *, void *) {}

} // namespace

int main(int argc, char ** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s MODEL [THREADS | --tokenize] < jobs\n", argv[0]);
        return 2;
    }
    const bool tokenize_only = argc > 2 && std::strcmp(argv[2], "--tokenize") == 0;
    const int n_threads = argc > 2 && !tokenize_only ? std::atoi(argv[2]) : 4;
    whisper_log_set(quiet_log, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false; // gar_whisper.cpp: stale padded cross K/V with a small audio_ctx
    whisper_context * ctx = whisper_init_from_file_with_params(argv[1], cparams);
    if (ctx == nullptr) {
        fprintf(stderr, "can't load %s\n", argv[1]);
        return 1;
    }

    std::string line;
    if (tokenize_only) {
        while (std::getline(std::cin, line)) {
            std::cout << tokenize(ctx, line).size() << std::endl;
        }
        whisper_free(ctx);
        return 0;
    }
    while (std::getline(std::cin, line)) {
        if (line.empty()) {
            continue;
        }
        const std::vector<std::string> f = split_tabs(line);
        if (f.size() < 3) {
            std::cout << "{\"error\":\"bad job line\"}" << std::endl;
            continue;
        }
        const std::string & id = f[0];
        const int audio_ctx = std::atoi(f[2].c_str());
        const std::string prompt_text = f.size() > 3 ? f[3] : "";

        std::vector<float> pcm;
        std::string error;
        if (!read_wav(f[1], pcm, error)) {
            std::cout << "{\"id\":" << json_string(id) << ",\"error\":" << json_string(error) << "}" << std::endl;
            continue;
        }
        std::vector<whisper_token> prompt = tokenize(ctx, prompt_text);

        const int64_t t0 = now_us();
        std::string text;
        int n_text = 0;
        double sum_log_p = 0.0;
        double encode_ms = 0.0;
        if (static_cast<int>(pcm.size()) >= MIN_SAMPLES) {
            // gar_whisper.cpp's transcribe, field by field
            whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
            p.greedy.best_of = 1;
            p.temperature = 0.0f;
            p.temperature_inc = 0.0f;
            p.n_threads = n_threads;
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
            p.suppress_nst = false;
            p.max_tokens = MAX_TOKENS;
            p.audio_ctx = audio_ctx;
            p.prompt_tokens = prompt.empty() ? nullptr : prompt.data();
            p.prompt_n_tokens = static_cast<int>(prompt.size());
            p.no_speech_thold = 1.0f;
            p.vad = false;

            whisper_reset_timings(ctx);
            if (whisper_full(ctx, p, pcm.data(), static_cast<int>(pcm.size())) != 0) {
                std::cout << "{\"id\":" << json_string(id) << ",\"error\":\"whisper_full failed\"}" << std::endl;
                continue;
            }
            if (whisper_timings * timings = whisper_get_timings(ctx)) {
                encode_ms = timings->encode_ms;
                delete timings;
            }
            if (whisper_full_n_segments(ctx) > 0) {
                const char * segment = whisper_full_get_segment_text(ctx, 0);
                text = segment != nullptr ? segment : "";
                const whisper_token eot = whisper_token_eot(ctx);
                const int n_tokens = whisper_full_n_tokens(ctx, 0);
                for (int i = 0; i < n_tokens; ++i) {
                    if (whisper_full_get_token_id(ctx, 0, i) < eot) {
                        sum_log_p += std::log(std::max(whisper_full_get_token_p(ctx, 0, i), 1e-10f));
                        ++n_text;
                    }
                }
            }
        }
        const double total_ms = (now_us() - t0) / 1000.0;
        const double confidence = n_text > 0 ? std::exp(sum_log_p / n_text) : 0.0;

        char numbers[256];
        snprintf(numbers, sizeof(numbers),
                ",\"audio_ctx\":%d,\"samples\":%zu,\"prompt_tokens\":%zu,\"tokens\":%d,"
                "\"confidence\":%.4f,\"encode_ms\":%.2f,\"total_ms\":%.2f}",
                audio_ctx, pcm.size(), prompt.size(), n_text, confidence, encode_ms, total_ms);
        std::cout << "{\"id\":" << json_string(id) << ",\"text\":" << json_string(text) << numbers << std::endl;
    }
    whisper_free(ctx);
    return 0;
}
