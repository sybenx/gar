# Speech eval (Whisper, on the Mac)

Accuracy of GAR's Whisper setup on short commands, above all contact names, for base.en and tiny.en
ACFT q8_0 with and without a contact-name prompt (whisper-design.md sections 14 and 16b). Audio and
results stay out of git, under `~/.cache/gar/stt-eval/`.

```sh
gar/stt/eval/build-mac.sh          # gar_whisper_eval + whisper-cli into ~/.cache/gar/whisper-mac/bin
python3 gar/stt/eval/make_clips.py # clips.yml -> ~/.cache/gar/stt-eval/clips/*.wav (about a minute)
python3 gar/stt/eval/run.py        # 2 models x 5 modes, under 30 s; results/latest.{md,json}
```

- `native/gar_whisper_eval.cpp` calls `whisper_full` with gar_whisper.cpp's parameters (greedy,
  no fallback, no timestamps, one segment, 64 tokens, flash attention off) and the prompt as
  tokens, from the same whisper.cpp pin and ggml settings. `whisper-cli` is built too, for spot
  checks (`-ac`, `--prompt`), but it can't set `single_segment` or `max_tokens`.
- The prompt is the app's: the owner (`owner: Aaron`, always first, as the app puts the owner's
  own name first) and the clip's `prompt` contacts go through a copy of `ContactPromptNames.names`
  (cleaned, first names, then short full names; no other contacts) and `NamePrompt.fit` (at most
  16 tokens, counted by `gar_whisper_eval MODEL --tokenize`), then " {names}." Keep these copies
  in step with `GarSttPromptNames.kt`, `NamePrompt.kt` and `WhisperEngine.promptFor`. The report
  says how many named clips kept their name in the prompt.
- Modes: `none`; `names` (the owner, the clip's own names, then its other contacts: the best
  case, the name said as a starred contact or recent caller); `names-without` (the same without
  the clip's own names); `phone`, the contacts as the device reads them (owner, `starred`, 10
  recent callers seeded by the clip id with the name said among them for `--phone-share` of the
  clips, default 0.5; nobody else); `others-40`, the Pixel's failure of 2026-11-02 ("Call Aaron."
  came out "Call Youran"): no owner, the clip's other contacts, fitted to the old 40-token cap.
  With `names` and `names-without` the report gives the break-even share: how often the name said
  must be in the prompt for the prompt to beat none, on WER and on exact clips.
- `run.py` gives each clip the app's `audio_ctx` (`AudioCtx.forSamples`, min 128) and scores the
  text through GAR's own `SpokenForm` on the JVM (`SpokenFormFilter.java`, from the classes of the
  last `:gar:router` build), or a small Python stand-in when those are missing. Options: `--models`,
  `--modes`, `--prompt-format`, `--max-prompt-tokens`, `--min-audio-ctx`, `--normalizer`, `--label`,
  `--phone-share`, `--manifest` (another clips file; `make_clips.py` takes it too).
- `clips.yml`: 85 commands, 43 with names, 5 `say` voices (Samantha, Daniel, Karen, Moira, Rishi),
  half with noise and a level change, plus the four bench clips (`say` WAVs, not phone recordings,
  `gar/stt/src/test/resources/wav`). Clips are cut like the Endpointer's: 300 ms before the speech,
  200 ms after. `say` voices are not Aaron's: real recordings ("Keep recordings") can join with `wav:`.
- Decode times are the Mac's (4 threads); the Pixel runs roughly 5 to 7 times slower (base.en
  median 190 ms there, 27 to 31 ms here across runs). For the prompt's cost on the phone, use
  `SttBenchReceiver` (`--ez bias false` against the default) rather than a ratio.
- No held-out set: the prompt format, the token cap and the on-by-default decision were all
  chosen on these clips, so small differences between settings are ties. New real recordings
  (`wav:`) should be kept apart and reported separately, never used for choosing.
- The voice log's recordings are that held-out set: `gar/tools/voice-log.py --suspect --pull-wavs
  DIR` writes `DIR/clips.yml` (see `gar/tools/README.md`); fill in each clip's `text`, then
  `python3 gar/stt/eval/run.py --manifest DIR/clips.yml --modes none,recorded --label voice-log`.
  `recorded` gives Whisper each clip's `prompt` as the phone did, not fitted again; the other
  modes work too but have no phone book to draw from.
