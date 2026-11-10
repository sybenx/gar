# GAR router eval

Measures how well the on-device model maps a transcript to one of GAR's tools: utterances with
the expected tool and slots run through the real `LlmRouter` (prompt, grammar, parsing, call
filters) against llama-server on a Mac or Linux machine. For how the router works, see
[DESIGN.md](../DESIGN.md); for the build, [BUILD.md](../BUILD.md).

## What it measures, and why

Dicio's sentence matcher stays the fast path. The model only sees what the matcher misses: the
paraphrases, the mishearings and the requests no skill does. So the cases are written in GAR's
spoken form, the way Vosk small-en-us transcribes speech and the way Whisper's text comes out of
`SpokenForm` (`:gar:router`): lowercase words, numbers spelled out, no punctuation except
apostrophes. Some are real Vosk mishearings such as "time or" for "timer" or "spot if i" for
Spotify, tagged `stt: vosk`. A good share of the cases are out of scope, where the right answer is
`no_match`. The eval doesn't run Dicio's matcher: every case goes to the model.

Per run, over the commands (positive cases) and the out-of-scope requests (negatives):

- **tool accuracy**: commands that got the expected tool;
- **exact slots**: commands that got the expected tool and acceptable slots (below);
- **abstention**: out-of-scope requests that got no call (`no_match`, dropped by a filter, or a
  failed output). The rest are **false calls**, which run a tool the user didn't ask for;
- **overall correct**: commands with exact slots plus abstentions, over all cases;
- **latency** per request, over all cases (depends on the machine's load, so compare it only
  within one run).

The router gets the app's call filters (`KeywordFilter`, Guard B, and `SlotPlausibilityFilter`,
Guard A; DESIGN.md "Call filters"). They don't change what the model generates, so every report
also scores the same outputs as if no call had been dropped, and records what each guard alone
would drop: calls on out-of-scope requests (good), misroutes of commands to another tool (good)
and calls of the expected tool (LOST, bad). The app's third filter, `SongCallFilter` for
`song_identify`, is app code and isn't in the eval, so the eval's false calls into
`song_identify` overstate what the app does.

## The two sets

**`cases.yml`, the dev set.** Grouped by tool; each group of Step 3 skills sits under its
`gar:<group>` marker. Tuning prompts, descriptions, keywords and filters uses a split recorded in
the comments (see the file's header): in each group, negatives included, the first 70% (rounded)
of the cases above its "added 2026-10-25" marker are dev, the rest of them held out. Cases under
an "added ..." marker, and groups whose header says they were never tuned on, were written before
any variant ran and are held out too. Tune on the dev cases only, and check on the held-out ones.
New tools get their cases here, and a tool with `keywords` gets a few held-out paraphrases that
its keywords must accept.

**`holdout.yml`, the blind set.** Written by an agent that hadn't seen `cases.yml`'s cases, any
report, `tools.yml`'s keywords and examples or any code, only the tool names, the tool and slot
descriptions, DESIGN.md's Step 3 skills table and `cases.yml`'s format header (see the file's
header). Never tune anything on it: run it once at the end of a round, as the final check. If one
of its cases turns out to be wrong, fix the expectation, never the router.

Either set has a Whisper version, made from it with synthetic speech (see "The Whisper set").

## Case format

```yaml
cases:
  - {text: start a pasta timer for eight minutes, tool: timer_set, slots: {duration: eight minutes, name: [pasta, pasta timer]}}
  - {text: remind me to buy milk, tool: reminder_add, slots: {what: buy milk}, optional: [when]}
  - {text: tell me a joke, tool: no_match}
  - {text: call urine, tool: call_contact, slots: {who: urine}, stt: vosk, said: call aaron}
```

- `text`: the transcript.
- `tool`: the expected tool from `app/src/main/gar/tools.yml`, or `no_match` for an out-of-scope
  request.
- `slots`: slot -> accepted value, or a list of accepted values. A value is a word span of the
  text (the grammar only lets the model write spans) or one of an enum slot's values; both sides
  are compared after `Utterances.normalize`. Every expected slot must be filled with an accepted
  value.
- `optional`: slots that may be filled or left out, with any value.
- `stt`: `vosk` when the text has a Vosk mishearing or word split that other recognizers don't
  make ("urine", "you tube"). The expectations stay those of the text.
- `said`: what was meant, where known, in the same spoken form. The Whisper set speaks it instead of
  the text, and its slot values follow it ("urine" becomes "aaron").

A filled slot that is neither in `slots` nor in `optional` makes the slots inexact. The grammar
always fills a required slot, so a command without its value ("set an alarm") lists that slot in
`optional`; an optional slot the user didn't say is expected left out. Accept the spans the slot
description allows ("exactly as the user said it": "the pasta timer" as well as "pasta"), not
whatever the model wrote.

The runner only checks tool names. `GarCallFilterTest` (`:app`) checks that the app's filter keeps
every positive case of `cases.yml` with each of its accepted values, so a new command must
contain one of its tool's keywords and slot values that Guard A accepts.

## llama-server and the models

Build llama-server from the llama.cpp submodule GAR pins (`gar/llm/llama.cpp`), so tokenizer,
grammar and sampling match the app's. Get the submodule as in
[BUILD.md, section 2](../BUILD.md#2-get-the-code), then build it as in
[BUILD.md, section 7](../BUILD.md#7-the-eval), with the SDK's CMake and Ninja and outside the
repository, so the submodule stays clean. CPU only (no Metal, no BLAS), tuned for this computer's
CPU, so its timings don't carry over to the phone. The same command:

```bash
C=$ANDROID_HOME/cmake/3.31.6/bin; "$C/cmake" -S gar/llm/llama.cpp -B ~/.cache/gar/llama.cpp -G Ninja -DCMAKE_MAKE_PROGRAM="$C/ninja" -DCMAKE_BUILD_TYPE=Release -DGGML_METAL=OFF -DGGML_BLAS=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_USE_PREBUILT_UI=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF && "$C/cmake" --build ~/.cache/gar/llama.cpp --target llama-server -j 4
```

If `~/.cache/gar/llama.cpp` was configured before with another generator, delete it first: CMake
won't switch generators in the same build folder.

```bash
export LLAMA_SERVER=~/.cache/gar/llama.cpp/bin/llama-server
```

`$LLAMA_SERVER --version` should name commit `620d222`. The models are the files the app
downloads (`ModelCatalog.kt`, pinned Hugging Face revisions):

| Model | Format | File (sha256) |
|---|---|---|
| Qwen3 0.6B, Apache-2.0 | `hermes` | [Qwen3-0.6B-Q4_K_M.gguf](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf) (`ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a`) |
| FunctionGemma 270M, [Gemma Terms of Use](https://ai.google.dev/gemma/terms) | `functiongemma` | [functiongemma-270m-it-q8_0.gguf](https://huggingface.co/ggml-org/functiongemma-270m-it-GGUF/resolve/2566ce14aedfc14fdd0de955ba67346425e67126/functiongemma-270m-it-q8_0.gguf) (`83940d4dd9676710856f43523bed096164a595a96f6b34771610a03937de5270`) |

```bash
curl -L --create-dirs -o ~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf
```

```bash
shasum -a 256 ~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf
```

## Running

Needs a JDK 21 (`JAVA_HOME`, see BUILD.md), python3 and curl. Run one eval at a time, without a
Gradle build or an emulator next to it: each run starts its own llama-server (0.5 to 0.8 GB).

```bash
gar/eval/run-eval.sh ~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf hermes
```

```bash
CASES=gar/eval/holdout.yml gar/eval/run-eval.sh ~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf hermes
```

The arguments are the model file, its prompt format (`hermes` for Qwen3, `functiongemma`) and
optionally the port (default 18950; the script refuses a port something already answers on).
Environment variables:

| Variable | Default | |
|---|---|---|
| `LLAMA_SERVER` | `llama-server` on `PATH` | the llama-server binary |
| `CASES` | `gar/eval/cases.yml` | the cases file, relative to the repository root |
| `GUARDS` | `keywords,slots` | the router's call filters; `none` for none |
| `THREADS` | 4 | llama-server's CPU threads; 1 if they starve each other on a busy machine |
| `TIMEOUT` | 120 | seconds a request may take, and llama-server may take to load. The first request evaluates the whole prompt (about 2300 tokens), which takes minutes on a busy machine |
| `REPLAY` | | an earlier report to score again, below |
| `EVAL_OUT` | `gar/router/build/eval` | where reports go |

The KV cache of the previous request's common prefix carries over on a server, so outputs depend
a little on what ran before on it (a few cases changed tool when run after others). Only runs on
fresh servers compare, which is why each run starts its own.

**Replay.** Filters, keywords, resolvers and labels don't change what the model generates, so a
change to them can be scored on the outputs an earlier report recorded, in seconds and without
llama-server or a model:

```bash
REPLAY=gar/router/build/eval/report-Qwen3-0.6B-Q4_K_M-hermes.json gar/eval/run-eval.sh
```

Model and format come from the report, and the cases file from its `.meta.json` (else set
`CASES` to the file the report was recorded with: a case the report doesn't have fails as an
engine error, and the script says so). Outputs are matched by text. A prompt change (descriptions,
tool order, a new tool) needs a real run: the runner warns when the replayed report was recorded
with another prompt. The Gradle task does the same with `-Pgar.eval.replay=<report.json>` (see
`gar/router/build.gradle.kts` for its properties), but writes over the report of the same model
and format unless `-Pgar.eval.out` says otherwise.

**Exit status**: 0 for a complete run; 1 when Gradle failed or a case failed in the engine (a
timeout, a dead server, an output the replay doesn't have), since the report then scores those
cases as failed; 2 for bad arguments or setup.

## Where reports go

`EVAL_OUT` (default `gar/router/build/eval`), under `<cases file name>/` for a `CASES` other than
`cases.yml` (`holdout/`), `replay/` for a replay and `guards-<GUARDS>/` for other `GUARDS`:

- `report-<model>-<format>.json`: the report. The next run with the same settings overwrites it.
- `report-<model>-<format>.log`: the runner's output (misses, guard drops, the summary).
- `report-<model>-<format>.server.log`: llama-server's log.
- `report-<model>-<format>.meta.json`: commit and whether the tree was dirty, the cases file and
  sha256 hashes of it, `tools.yml` and `skill_definitions.yml`, the model file, llama-server's
  version, threads, context, timeout and what a replay replayed.
- `runs/<start time>-report-...`: a copy of each run's files (a replay has no server log), which
  the next run doesn't overwrite.

`./gradlew clean` deletes `gar/router/build`: copy reports worth keeping elsewhere, or set
`EVAL_OUT` to a directory outside the repository.

## Comparing two runs

```bash
gar/eval/compare.py <before.json> <after.json>
```

Standard library Python, reading only the two reports. Cases are matched by text. It prints:

1. what each report is (model, format, date, guards, cases, prompt tokens, and the `.meta.json`
   when there is one), how the prompts differ (tools added, removed or changed) and warnings when
   model, format, guards, case sets or file hashes differ;
2. the summary metrics side by side with deltas in percentage points, on all cases and again on
   the cases both reports share (the comparison that counts when cases were added);
3. tool and exact-slot accuracy per expected tool on the shared cases, with the cases each tool
   fixed and broke;
4. where the calls went: per tool, the model's choice before the filters, the result after them
   and the false calls;
5. the shared cases that BROKE (correct to wrong), got FIXED, or CHANGED otherwise, each with the
   cause: `model` (the raw output differs), `filter` (the same raw output, another verdict:
   parsing, keywords, Guard A, resolvers), `label` (the expectation changed; the report has no
   expected slots, so a slot label change shows as the same answer scored differently) or
   `engine` (a timeout or a dead server on one side);
6. the cases only one report has, with their accuracy per tool;
7. per guard: calls dropped on out-of-scope requests, misroutes and LOST, and which commands it
   newly loses.

Options: `--only-changed` hides unchanged rows in 3 and 4, `--tool NAME` keeps rows and cases about
one tool, `--json` prints the same data as JSON, and `--fail-on-broke` exits 1 when a shared case
broke (for a filter change checked on a replay).

## Reading a report

- `model`, `format`, `server`, `guards`, `date`, `static_prefix` (the cached part of the prompt:
  instructions and tool declarations) and `static_prefix_tokens` (missing for a replay).
- `summary`: `cases`, `positives`, `negatives`, `tool_accuracy`, `exact_slot_accuracy`,
  `abstention_accuracy`, `false_call_rate` (fractions), `abstained_on_positives` (commands that got
  no call), `failed` (malformed outputs and engine failures together), `dropped` (calls a filter
  dropped) and the latencies `latency_mean_ms`, `latency_p50_ms`, `latency_p90_ms`. The overall
  correct rate is only in the runner's output and in compare.py.
- `summary_without_filter`: the same outputs scored as if no call had been dropped, with
  `correct` overall.
- `per_tool`: per expected tool, `n`, `tool_accuracy` and `exact_slot_accuracy`. No `no_match` row:
  abstention is in `summary`.
- `cases`, one per case in file order:
  - `text`, `expected_tool`;
  - `got`: the tool that would run, `no_match`, `dropped` (a filter dropped the call) or `failed`
    (with `failure`: a malformed output, or "timed out", "engine error" for the engine);
  - `slots`: the slots as the model wrote them (also for a dropped call), and `missing_required`;
  - `call` and `call_slots`: the model's call before the filters, also when it was kept; missing
    when the model made no call;
  - `drop_reason` for a dropped call, and `guard_drops`: what each guard alone would say about the
    call (empty when every guard keeps it);
  - `tool_correct`, `slots_exact`, `correct` (for a negative: abstained);
  - `raw`: the model's output as generated, and the timings `millis` (the whole route),
    `prompt_tokens`, `cached_tokens`, `generated_tokens`, `prefill_ms`, `generate_ms`.

The runner's output (`.log`) lists each case it got wrong as `MISS text -> got {slots}` with the
drop reason, the summary per expected tool, and per guard the commands it dropped as `misroute`
(another tool's call: good) or `LOST` (the expected tool's: bad).

## The Whisper set

With Whisper the router gets `SpokenForm`'s spoken form of Whisper's text, not Vosk's words.
`gar/eval/stt/make-whisper-cases.sh` makes a Whisper version of a cases file: it speaks each case
(`said`, else `text`) with three macOS voices (`say`, US, UK and Australian English), transcribes
each clip with whisper-cli as the app calls Whisper (greedy, no fallback, no timestamps, English,
`audio_ctx` from the clip length as `AudioCtx.forSamples` computes it), puts the transcripts
through `SpokenForm` (`./gradlew :gar:router:spokenForm`), and writes one cases file per voice with
only the text changed. It prints the word error rate of each voice against the cases' text (`said`
where there is one) and on the `stt: vosk` cases alone. Clips are synthetic: they measure
Whisper's style and SpokenForm, not accuracy on real voices.

whisper-cli comes from the submodule GAR pins (`gar/stt/whisper.cpp`), built outside the repository
like llama-server:

```bash
C=$ANDROID_HOME/cmake/3.31.6/bin; "$C/cmake" -S gar/stt/whisper.cpp -B ~/.cache/gar/whisper.cpp -G Ninja -DCMAKE_MAKE_PROGRAM="$C/ninja" -DCMAKE_BUILD_TYPE=Release -DGGML_METAL=OFF -DGGML_BLAS=OFF -DWHISPER_BUILD_TESTS=OFF && "$C/cmake" --build ~/.cache/gar/whisper.cpp --target whisper-cli -j 4
```

The models are the files the app downloads (`SttModelCatalog.kt` pins their size and sha256),
FUTO's Whisper ACFT English q8_0 files (Apache-2.0):

```bash
curl -L --create-dirs -o ~/.cache/gar/stt/ggml-base.en-acft-q8_0.bin https://dl.voiceinput.futo.org/VoiceInput/base_en_acft_q8_0.bin
curl -L --create-dirs -o ~/.cache/gar/stt/ggml-tiny.en-acft-q8_0.bin https://dl.voiceinput.futo.org/VoiceInput/tiny_en_acft_q8_0.bin
```

Then, one at a time and without another Gradle build next to it (the script runs Gradle once):

```bash
gar/eval/stt/make-whisper-cases.sh ~/.cache/gar/stt/ggml-base.en-acft-q8_0.bin
gar/eval/stt/make-whisper-cases.sh ~/.cache/gar/stt/ggml-base.en-acft-q8_0.bin gar/eval/holdout.yml
CASES=gar/eval/stt/out/cases-whisper-base.en-acft-q8_0-samantha.yml gar/eval/run-eval.sh ~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf hermes
```

The first run takes a while (about 450 clips per voice through `say` and whisper-cli); clips and
transcripts are cached in `~/.cache/gar/stt/eval` (`STT_CACHE`), so later runs only redo what
changed. `VOICES`, `THREADS` and `WHISPER_CLI` are in the script's header. Output goes to
`gar/eval/stt/out/` (not in git): `<cases>-whisper-<model>-<voice>.yml` per voice and
`<cases>-whisper-<model>.tsv` with every transcript (reference, Whisper's text, spoken form, word
errors). A case Whisper heard nothing for is left out of its file and listed. Compare a Whisper
run with the run on the source file through `compare.py`: cases are matched by text, so the shared
cases are the ones Whisper heard exactly as written, and the rest show up as cases only one report
has. Two cases that come out the same in one voice share a text, which a replay can't tell apart.

## Latest results

2026-11-02 (PARITY batches 5 and 6): the 28 tools of `tools.yml`, the slotless `weather` tool
appended last, with the call filters, on the same llama-server build, threads and machine as
below (other work running). `cases.yml` has 458 cases: the 446 of 2026-10-27 (three weather
requests relabeled `weather`) and the `gar:weather` dev group (8 commands, 4 near negatives:
"set the thermostat to seventy", "play cold by coldplay", "it's raining men", "how hot does the
oven get"). In `holdout.yml` two weather requests are relabeled `weather`. The "shared" rows leave
out every weather case (443 and 180 cases) and compare with the 2026-10-27 outputs replayed with
today's router (`REPLAY=`, the "after the review" rows below, which weren't kept): the same
catalog without `weather` reran on fresh servers and wrote the same outputs on every shared case
(static prefix 2494 tokens), so those rows are also the catalog without the tool. Descriptions
tried, on `cases.yml` only: (a) "Tell the weather, the forecast, rain, snow or the temperature
outside." (as merged, 2543 tokens), (b) "Tell the weather forecast." (2533), (c) "Tell the weather
forecast, rain, snow, wind or temperature, here or in a named place." (2548), (d) (c) with "now or
on another day," before "here" (2554). The holdout ran with (a) and (c).

Qwen3 0.6B Q4_K_M (`hermes`):

| set | cases (commands, out of scope) | tool | exact slots | abstention | false calls | mean latency |
|---|---|---|---|---|---|---|
| `cases.yml`, (c), kept | 458 (375, 83) | 76.8% | 57.3% | 68.7% | 26 | 1146 ms |
| `cases.yml`, (c), 443 shared | 443 (363, 80) | 76.3% | 56.5% | 68.8% | 25 | 1146 ms |
| `cases.yml`, (a) | 458 (375, 83) | 76.8% | 58.9% | 66.3% | 28 | 885 ms |
| `cases.yml`, (a), 443 shared | 443 (363, 80) | 76.3% | 58.1% | 67.5% | 26 | 890 ms |
| `cases.yml`, (b), 443 shared | 443 (363, 80) | 75.2% | 57.0% | 68.8% | 25 | 897 ms |
| `cases.yml`, (d), 443 shared | 443 (363, 80) | 76.0% | 55.4% | 67.5% | 26 | 841 ms |
| `cases.yml`, no weather tool, 443 shared | 443 (363, 80) | 74.9% | 57.6% | 71.2% | 23 | 771 ms |
| `holdout.yml`, (c), kept | 182 (145, 37) | 77.9% | 64.1% | 70.3% | 11 | 829 ms |
| `holdout.yml`, (c), 180 shared | 180 (143, 37) | 77.6% | 63.6% | 70.3% | 11 | 829 ms |
| `holdout.yml`, (a) | 182 (145, 37) | 77.2% | 64.1% | 62.2% | 14 | 920 ms |
| `holdout.yml`, (a), 180 shared | 180 (143, 37) | 76.9% | 63.6% | 62.2% | 14 | 922 ms |
| `holdout.yml`, no weather tool, 180 shared | 180 (143, 37) | 76.9% | 63.6% | 67.6% | 12 | 794 ms |

- The tool stays, with description (c), by the lead's explicit call against the rule. The rule
  (undo it if it costs more than 2 points of tool accuracy or abstention on the shared cases of
  either set and tuning doesn't recover it, then keep the better catalog) says remove it: as
  merged, (a) cost 3.7 points of abstention on `cases.yml` and 5.4 on the holdout, with tool
  accuracy 1.4 points up and even. Tuned, (c) still costs 2.5 points of abstention on the dev set
  (57 of 80 against 55; 0.5 over the rule, and no variant got it back: "how are you doing today"
  -> `date_query`, which every variant does, "book a flight to denver" -> `navigate` and "how many
  songs did the beatles release" -> `song_identify`, which the app's `SongCallFilter` keeps too;
  "how bright is the sun" got fixed), with tool accuracy 1.4 points up. None of these false calls
  is a weather call. Exact slots: 1.1 points down on `cases.yml` (model noise in timer names,
  volume levels and media queries). On the 443 shared dev cases the catalog without the tool is
  correct overall on 266 (60.0%), with (c) on 260 (58.7%); what the tool adds is reach: 10 of the
  11 weather commands in `cases.yml` and 2 of 2 in the holdout get it, and without the tool none
  can. (An earlier version of this note compared overall correct on the whole sets, 59.4% against
  at most 59.0%, which counts the 11 weather commands only the tool can get right; (a) scores
  60.3% on that measure anyway.) The keep was weighed on those two things: the dev set's cost and
  the weather commands' reach.
- The holdout is no longer blind for the weather tool: its numbers (with (c) 2.7 points more
  abstention than without the tool, "time flies when you're having fun" and "open the window it's
  hot in here" abstain now, "how are you doing today" broke; tool accuracy 0.7 up, exact slots
  even) were read before the keep was decided. Report it from now on only as a check, and judge
  later weather changes on the dev set alone.
- Weather with (c): 10 of the 11 `cases.yml` weather commands and both of the holdout's get the
  tool; "will i need an umbrella this afternoon" abstains in every variant ((a) and (d) miss only
  it too, (b) also loses "is it going to snow in park city on friday" and "when is the rain
  supposed to stop"). Near negatives: the thermostat abstains (an `alarm_set` call dropped by
  its keywords), "how hot does the oven get" abstains with (b), (c) and (d) (with (a) it got a
  weather call), "it's raining men" gets a weather call in every variant, which the app's
  `WeatherCallFilter` drops (the eval doesn't run it), and "play cold by coldplay" gets
  `media_play` with "play" in its query (a slot error, not a weather call). "how far away is the
  moon" gets a weather call in every variant, dropped by its keywords.
- Keywords weren't changed for this run: they only filter, and the one weather call on an
  out-of-scope request they keep ("it's raining men") is `WeatherCallFilter`'s to drop in the app.
  After the review, degrees, fog, frost and freeze were added (weather words GAR's own sentences
  and `WeatherWords` take, which the keyword guard dropped), with two dev cases that have none of
  the old keywords ("how many degrees is it going to be tomorrow", "will it freeze tonight");
  `cases.yml` has 460 cases now, and those two haven't run yet.
- Reports in `~/.cache/gar/b56-reports/` on the Mac: `out/` (a), `r1/` (b), `r2/` (c, with
  `holdout/`), `r3/` (d), `noweather/` and the replayed baselines `baseline-replay-*/`, with the
  shared cases files in `cases/`.

2026-10-27 (PARITY batches 3 and 4): the 27 tools of `tools.yml`, `device_setting` in place of
`wifi` and `bluetooth` and `media_play` appended, with the call filters, on the same llama-server
build, threads and machine as Step 5 below (other work running). Static prefix 2494 tokens (Step
5: 2321). `cases.yml` has 446 cases: Step 5's 361 (its wifi and Bluetooth commands relabelled as
`device_setting` calls), the batch 3 and 4 groups (held out: 35 device settings, 18 media, 5 call
backs) and 27 dev cases written after the first run ("added 2026-10-27 (batch 3-4), dev"). In
`holdout.yml` the 10 Wi-Fi and Bluetooth commands are relabelled and three out-of-scope requests
became commands ("turn on airplane mode", "turn up the screen brightness", "put on dancing queen
by abba"); its "179 shared" rows leave those three out. "Unmerged" is the same catalog with the
`wifi` and `bluetooth` tools back (28 tools, 2655 tokens; gar/DESIGN.md "Device settings" has the
steps), run to decide the merge.

Qwen3 0.6B Q4_K_M (`hermes`):

| set | cases (commands, out of scope) | tool | exact slots | abstention | false calls | mean latency |
|---|---|---|---|---|---|---|
| `cases.yml` | 446 (363, 83) | 71.3% | 54.8% | 71.1% | 24 | 801 ms |
| `cases.yml`, the 361 Step 5 cases | 361 (294, 67) | 73.5% | 59.2% | 74.6% | 17 | 786 ms |
| `cases.yml`, unmerged | 446 (363, 83) | 71.3% | 55.9% | 65.1% | 29 | 838 ms |
| `cases.yml`, unmerged, the 361 Step 5 cases | 361 (294, 67) | 73.5% | 60.2% | 68.7% | 21 | 828 ms |
| `cases.yml`, Step 5 | 361 (294, 67) | 74.8% | 60.2% | 71.6% | 19 | 938 ms |
| `holdout.yml` | 182 (143, 39) | 74.1% | 60.8% | 69.2% | 12 | 837 ms |
| `holdout.yml`, 179 shared | 179 (140, 39) | 74.3% | 61.4% | 69.2% | 12 | 837 ms |
| `holdout.yml`, unmerged | 182 (143, 39) | 75.5% | 63.6% | 56.4% | 17 | 860 ms |
| `holdout.yml`, unmerged, 179 shared | 179 (140, 39) | 75.7% | 64.3% | 56.4% | 17 | 859 ms |
| `holdout.yml`, Step 5, 179 shared | 179 (140, 39) | 77.9% | 67.1% | 66.7% | 13 | 973 ms |
| `cases.yml`, after the review (replay) | 446 (363, 83) | 74.9% | 57.6% | 72.3% | 23 | replay |
| `cases.yml`, after the review (replay), the 361 Step 5 cases | 361 (294, 67) | 77.2% | 62.9% | 74.6% | 17 | replay |
| `holdout.yml`, after the review (replay) | 182 (143, 39) | 76.9% | 63.6% | 69.2% | 12 | replay |
| `holdout.yml`, after the review (replay), 179 shared | 179 (140, 39) | 77.1% | 64.3% | 69.2% | 12 | replay |

- The merge stays. Against Step 5 on the shared cases the merged catalog loses 1.4 points of tool
  accuracy on `cases.yml` and 3.6 on the holdout (more than the 2 points allowed) and gains 3.0
  and 2.6 points of abstention (2.4 when the three relabelled requests count as out of scope, as
  they were). With `wifi` and `bluetooth` back, tool accuracy is the same on `cases.yml` and 1.4
  points higher on the holdout (2 commands), exact slots 1.0 and 2.9 points higher, but abstention
  drops 6 and 13 points under the merged catalog (4 and 5 more false calls; 3.0 and 10.3 points
  under Step 5). Overall correct is higher merged (57.8% against 57.6% on `cases.yml`, 62.6%
  against 62.1% on the holdout), with 161 fewer prompt tokens.
- The tool accuracy lost is `media_play`'s, not the merge's: it takes `media_control`'s commands.
  11 of `cases.yml`'s 13 `media_control` commands and 4 of the holdout's 5 are `media_play` calls
  now (most dropped by its keywords, so they get nothing), among them the tool's own examples
  "pause the music" and "skip this song"; `media_control` went from 9 and 5 right to 0. Leaving
  out the shared commands lost to a `media_play` call (8 and 5), tool accuracy is 75.5% (Step 5:
  74.1%) and 77.0% (77.0%). The dev near misses "skip to the next song" and "continue the
  podcast" show it too.
- Wi-Fi and Bluetooth through `device_setting`: the right tool more often (15 of 18 and 10 of 10,
  before 13 and 9), exact slots less often (9 and 6, before 12 and 9; the same after the review
  accepted status for the three questions labelled show only): the model writes leftover
  words into the level ("show", "available", "connected"), `toggle` for show, `on` for a question,
  and sometimes another setting ("is my headset connected" and "i want to pair my headphones" ->
  battery saver on).
- `device_setting`'s other settings (held out): 14 of 30 commands get the tool, 8 with exact
  slots. All 7 brightness commands go to `volume` or `volume_set`; "turn on nfc", "turn off
  location", "turn off auto rotate" and "put my phone on vibrate" to the flashlight (dropped by
  its keywords); questions get `on` ("is my gps on" -> location on, "is my phone on silent" ->
  battery saver on). 1 of its 5 near misses abstains: "what does airplane mode do" -> airplane
  mode on, "my screen is cracked" -> screen timeout off. Its dev cases: 7 of 10 commands, 4 exact,
  3 of 4 near misses abstain.
- `media_play` (held out): 12 of 14 commands, 5 exact (6 before the review dropped `optional:
  [app]` from cases that name no app: the app slot gets leftovers such as "the eagles", "album"
  and "podcast", and the query keeps "on you tube music"); 3 of 4 near misses abstain. Dev: 7 of
  8, 3 exact (5 before); "let's play a game" gets a call. Call backs through `call_contact` (held
  out): 3 of 5.
- The outputs are deterministic for a catalog: the second `cases.yml` run (with the dev cases)
  wrote the same outputs as the first on the 419 cases both had. Reports in
  `~/.cache/gar/b34-reports/` on the Mac.
- After the review (the "replay" rows): the same model outputs scored again (`REPLAY=`, no
  llama-server; the prompt didn't change) with the router's `media_play` -> `media_control`
  rewrite (gar/DESIGN.md "Call filters"), `device_setting` without the bare "screen" and
  "display" keywords, and the review's label fixes (no `optional: [app]` where no app is said,
  status accepted for three questions). `media_control` gets 13 of 15 and 4 of 5 (before 0): only
  "resume playback" (-> `stopwatch`), "pause this" (-> `timer_set`) and "hold on stop the music
  for a sec" (-> `alarm_set`) stay lost, all dropped by the guards. On the shared cases tool
  accuracy is now 2.4 points above Step 5 on `cases.yml` and 0.8 below on the holdout (inside the
  2 points), abstention 3.0 and 2.5 points above. "My screen is cracked" now abstains. The app's
  own checks of `device_setting` and the volume calls (gar/DESIGN.md "Device settings") don't run
  in the eval. A run on fresh servers is still to do.

2026-10-26 (Step 5): the 27 tools of `tools.yml` with the call filters (`GUARDS=keywords,slots`),
llama-server built from `620d222` (CPU only, 4 threads) on an Apple M1 with 16 GB and other work
running, so latency only compares within one run. Step 4 is the last run before this step's case
and router changes; its `cases.yml` report was lost, only its summary is known.

Qwen3 0.6B Q4_K_M (`hermes`):

| set | cases (commands, out of scope) | tool | exact slots | abstention | false calls | mean latency |
|---|---|---|---|---|---|---|
| `cases.yml` | 361 (294, 67) | 74.8% | 60.2% | 71.6% | 19 | 938 ms |
| `cases.yml`, Step 4 | 361 (294, 67) | 74.8% | 60.2% | 71.6% | 19 | - |
| `holdout.yml` | 182 (140, 42) | 77.9% | 67.1% | 61.9% | 16 | 982 ms |
| `holdout.yml`, its 160 Step 4 cases | 160 (123, 37) | 77.2% | 65.9% | 64.9% | 13 | 969 ms |
| `holdout.yml`, Step 4 | 160 (123, 37) | 77.2% | 63.4% | 64.9% | 13 | 803 ms |

FunctionGemma 270M Q8_0 (`functiongemma`), the base model the fine-tune starts from (no earlier
run with these tools):

| set | cases (commands, out of scope) | tool | exact slots | abstention | false calls | mean latency |
|---|---|---|---|---|---|---|
| `cases.yml` | 361 (294, 67) | 53.1% | 34.0% | 53.7% | 31 | 175 ms |
| `holdout.yml` | 182 (140, 42) | 59.3% | 40.0% | 61.9% | 16 | 196 ms |

- Qwen3 wrote the same outputs as an earlier llama-server build on all 361 `cases.yml` cases and
  as Step 4 on the 160 shared holdout cases, and the router's Step 5 fixes change no verdict on
  any of the four runs (replayed with Step 4's code). So the only gain is three holdout commands
  without their value ("make a phone call", "i want to open an app", "start navigation") whose
  required slot the expectation now lists as `optional`. Of the 22 new blind holdout cases 15 are
  right, among them all 8 `song_identify` commands; an alarm question, an alarm removal and a
  calendar question went to `alarm_set` and `calendar_add`, "stop the countdown" got `name:
  "countdown"`, and 3 of the 5 song near misses got a call.
- The app's `SongCallFilter`, which the eval doesn't run, drops 5 of Qwen3's 19 false calls on
  `cases.yml` (all into `song_identify`: "who sings hello", "sing me a song", ...; abstention
  79.1% in the app) and 1 of 16 on the holdout ("who sings hotel california", 64.3%); of
  FunctionGemma's, 4 of 31 and 2 of 16.
- The base FunctionGemma is five times faster but hardly ever abstains by itself (`no_match` on 3
  of 67 out-of-scope requests; 4.5% and 2.4% abstention without the filters), so the filters do
  all of its abstaining, and its slot check alone drops 10 and 11 calls of the right tool for
  slots filled with the wrong words (the time of day as `calendar_add`'s duration, "set a
  reminder" -> `when: "set"`).
