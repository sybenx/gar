# GAR tools (on the Mac)

## voice-log.py: what happened to each voice command

Reads the voice log that GAR keeps on the phone (gar/DESIGN.md, "Voice log"): one JSON object per
turn, from the start (mic button, assist gesture, wake word, typed, a reopened microphone, a
continued-listening window, a card's button) through capture, Whisper, `SpokenForm`, Dicio's matching, the model and the answer,
with timings and failure signals. Python 3, standard library only.

```sh
python3 gar/tools/voice-log.py                         # over adb: table + per-stage summary
python3 gar/tools/voice-log.py -s 4B091JEKB --since 2h # one device, the last two hours
python3 gar/tools/voice-log.py --suspect               # only turns marked suspect
python3 gar/tools/voice-log.py --skill telephone       # turns that touched a skill id or tool
python3 gar/tools/voice-log.py --file gar-voice-log-20261102.zip   # a shared copy, no adb
python3 gar/tools/voice-log.py --suspect --pull-wavs ~/.cache/gar/stt-eval/clips/voice-log
python3 gar/tools/voice-log.py --suspect --json | jq .  # the raw turns
```

- **Source.** Without `--file` it runs `adb -s <serial> exec-out run-as cc.aaro.gar.gar ls|cat
  files/gar-voice-log/...` (`run-as` works on debug builds, no root). The serial comes from `-s`,
  else `ANDROID_SERIAL`, else adb's only device; `--package` for another build. `--file` takes
  the JSONL files, a folder of them, or the zip from Settings, Language model, "Share voice log"
  (repeatable). Rotated files (`voice-log.1.jsonl` .. `.4.jsonl`) are read oldest first; a line
  that isn't JSON is skipped with a note on stderr.
- **Table.** One row per turn: local time, how it started, what was heard (Whisper's raw text
  `->` the spoken form, or the typed input), the route (`sentence`, `follow_up_answer`,
  `online_gate`, `model:<tool>`, `fallback`, with "model dropped" or "rejected <skill>" when the
  model or GarMatchCheck had a say; a follow-up's outcome first, as `follow_up:rewritten
  sentence` or `follow_up:deferred:media sentence`, and its rewrite after `=>` in what was
  heard), the answer (the skill, `(shown)` when typed input got it on the card only, or
  `no_input(<why>)`, `stt_error`, `error`, `missing_permissions:<skill>`), the latency from the end of speech (or the typed
  input) to the first spoken word, else to the route, the share of janky frames GAR's windows
  drew during the turn (`jank`, from its `frames`), and the suspect reasons.
- **Filters.** `--suspect`; `--since 2h|30m|1d|2026-11-02|2026-11-02T14:00` (local time unless
  an offset is given); `--skill ID` matches the skill that answered, the model's tool or Dicio's
  best candidate.
- **Summary** (`--summary-only` for just this): how turns started, why capture ended, the
  transcript filter's drops, routes, GarMatchCheck's verdicts, the model's outcomes and filter
  drops, follow-up outcomes, answers, answers shown only, continued-listening windows that
  heard nothing or only noise (`continued_silent`, `continued_hallucination`), error classes,
  suspect reasons, median and p90 of end of speech to text,
  to route and to first word, and the frames of all turns (janky share, slowest frame).
- **`--pull-wavs DIR`** copies the WAVs of the selected suspect turns (the phone keeps the last
  20 turns' and the newest suspect ones, up to 200 and 30 MB) and writes `DIR/clips.yml` in
  `gar/stt/eval`'s clip format: `owner` (the turns' prompt owner), empty `starred` and
  `contacts`, and per clip id `vl-<turn id>`, what was heard in a comment, `text` from the retry
  that marked it (a repeat, an edit, "no I said ...": check it against the recording) or else a
  `# text: TODO` comment that keeps the eval from loading the file until you fill it in, the
  prompt names the phone sent and the WAV's absolute path. Replay them with
  `python3 gar/stt/eval/run.py --manifest DIR/clips.yml --modes none,recorded --label voice-log`.
  Real recordings are a held-out set (gar/stt/eval/README.md): report them apart, never tune on
  them.
- **Tests:** `python3 -m unittest discover -s gar/tools -p 'test_*.py'` (the sample in
  `testdata/voice-log-sample.jsonl`, a fake adb, a shared zip).

## jank.py: startup and frame times on the emulator

Measures a build on the emulator (gar/DESIGN.md, "Frame rate"), to compare before and after a
change. gartest renders in software at 60 Hz, so every printout says "emulator, software
rendering, 60 Hz: compare before/after only"; the phone's frame times come from the voice log's
`frames` (GarJank). Python 3, standard library only. It refuses any serial that doesn't start with
`emulator-` (default `emulator-5580`) and never starts an emulator.

```sh
python3 gar/tools/jank.py startup --runs 5 --label before   # cold and warm am start -W
python3 gar/tools/jank.py session --json > after.json        # 3 typed commands, 3 scrolls
python3 gar/tools/jank.py sheet --runs 10                    # GAR must be the assistant
```

- **`startup`**: `--runs` cold starts (`am force-stop`, then `am start -W`) and as many warm ones
  (HOME, then `am start -W`). While GAR is the assistant its voice interaction service brings the
  process back right after `am force-stop`, so a cold run checks `pidof` first, force-stops up to
  three times, and counts as `process_warm` (summarized apart, App.onCreate not included) when
  the process is still there. Each kind gets TotalTime, WaitTime and logcat's `Displayed` and `Fully drawn`
  times as p50, p90 and max. This is the startup measure: gfxinfo and JankStats can't see a stall
  before the first frame. `am start -W` gives up after 10 s, so the script waits up to 30 s more
  for `Displayed` (a cold debug start on gartest takes 13 to 27 s).
- **`session`**: `dumpsys gfxinfo <pkg> reset` and `logcat -c`, then types the commands
  (`--command`, repeatable; the field is found in a `uiautomator dump`, waiting up to 40 s for
  GAR's window and tapping the top bar's rightmost button once when the field is hidden), waits
  `--answer-seconds` after each and scrolls three times. Once the field first shows it dumps the
  start's frames (printed apart) and resets gfxinfo again, so the session's numbers leave the
  start out. Prints the session's numbers from
  `dumpsys gfxinfo` (total and janky frames, percentiles, slow UI thread, missed deadlines, the
  histogram); the `framestats` PROFILEDATA, which only holds the last 120 frames of each window,
  with frames flagged as first frame or layout or size change listed apart; and the `GarJank:`
  lines.
- **`sheet`**: the assist key (`input keyevent KEYCODE_ASSIST`) and Back `--runs` times (the
  shell may not call `cmd voiceinteraction show` on API 35), the `GarSheet:
  first_frame_ms=` lines as p50, p90 and max.
- `--json` prints the result as JSON (progress goes to stderr), `--label` names the run,
  `--package` another build (default `cc.aaro.gar.gar`).
- **Tests:** `test_jank.py`, on saved output in `testdata/` (`gfxinfo.txt`,
  `gfxinfo-framestats.txt` with flagged rows, `garjank-logcat.txt`, `am-start.txt`) and a fake
  adb.
