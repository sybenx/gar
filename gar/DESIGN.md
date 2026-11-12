# Garble (GAR) design (Step 2: LLM fallback router)

The app is called Garble since 2026-11. GAR stays the project's code name: the `gar/` modules,
the `org.stypox.dicio.gar` package, the `// GAR` markers and the repository.

GAR is a fork of Dicio. Dicio's sentence matcher stays the fast path. Only when it finds no confident
match does GAR ask a small on-device model to map the transcript to one tool call with raw slot
strings. Kotlin code then resolves the raw strings deterministically, asks the user when it can't,
and runs the target skill through Dicio's normal pipeline (permissions, cards, speech, follow-ups).

Everything GAR adds lives in new files: the `gar/` Gradle modules and the
`org.stypox.dicio.gar` package inside `:app`. Upstream files get small, marked edits only
(search for `GAR`).

## Modules

| Module | Kind | Contents |
|---|---|---|
| `:gar:router` | pure Kotlin/JVM | tool catalog, prompt formats, GBNF generation, output parsing and validation, `LlmRouter`, call filters, slot resolvers, `CompletionEngine` interface, `LlamaServerEngine` (JVM, for tests and the eval), eval runner |
| `:gar:llm` | Android library + CMake/JNI | llama.cpp (git submodule, pinned), thin JNI layer, `LlamaEngine : CompletionEngine` |
| `:gar:assist` | Android library | `VoiceInteractionService` + session that hands off to `MainActivity` |
| `:gar:stt` | Android library + CMake/JNI | whisper.cpp (second submodule, pinned) in `libgar_whisper.so`, `WhisperEngine`, `SileroVad`, and the pure end-pointing, filter and WAV code ("Speech recognition (Whisper)") |
| `:app` (`org.stypox.dicio.gar`) | glue | evaluator hook, tool binding to Dicio skills, ask/confirm outputs, model manager and lifecycle, downloader, settings screen |

Dependencies: `:app -> :gar:router, :gar:llm, :gar:assist, :gar:stt`; `:gar:llm -> :gar:router`;
`:gar:stt` depends on no other GAR module. `:gar:router`
must not depend on Android so its tests run on the JVM.

## Data flow

```
InputEvent.Final(utterances)
  -> SkillEvaluator -> garMatchUtterances: Dicio ranker on each alternative (one input at a time, GAR edit)
       a default-batch match that GarMatchCheck rejects is no match ("Checking Dicio's own matches")
  -> (no match, LLM enabled)  GarFallback.tryRoute(utterances)                           [GarMatching.kt, GAR edit]
       -> LlmRouter.route(utterances[0])   (model loaded by GarModelManager)
            prompt = format.staticPrefix(tools) [KV-cached] + format.suffix(utterance)
            grammar = format.grammar(tools, utterance)   (tool names, slot keys, slot values = word spans of the utterance)
            raw = engine.complete(...)
            -> parse + validate -> call rewrite (MediaControlRewrite) -> call filter (KeywordFilter, SlotPlausibilityFilter; "Call filters")
            -> RouterOutput.Call(tool, rawSlots) | Dropped(call, reason) | NoMatch | Failed
       -> Call: pending follow-up batches are dropped (like a default-batch match), then
            phone locked and !tool.on_lock_screen -> GarLockedSkill ("Unlock your phone first.")
            else SkillWithResult(GarRoutedSkill(targetSkillInfo), AlwaysBestScore, call)
  -> Dicio checks the TARGET skill's permissions, then GarRoutedSkill.generateOutput:
       rewrite raw slots the target can't take as said (GarSlotRewrites: "the pasta timer" -> "pasta",
         "divide X by Y" -> "X divided by Y")
       resolve slots (duration/date/time/number/app; contact/text stay as said)
         missing or unresolvable or ambiguous -> GarAskSlotOutput (question, follow-up skill)
       confirmation enabled and (tool.confirm or FunctionGemma) -> GarConfirmOutput (tool.confirm_text,
         Yes/No buttons + voice yes/no)
       else -> target skill.generateOutput(ctx, inputData)
```

A match that GarMatchCheck rejected is still a new command and not an answer, because answers
are matched before the default skills. So once no alternative matched, `garMatchUtterances` drops
the pending batches before the model gets the input, as an accepted default match or a model call
would ("Conversation"). An input that no skill matched (no match, Dropped, Failed) goes to Dicio's
normal text fallback ("Could you repeat?") and keeps a pending question for another try, since it
may be a misheard answer. A follow-up question that the user ignores therefore never keeps the
model away: the next command that a skill or the model takes replaces it.

## Tool catalog

Source of truth: `app/src/main/sentences/skill_definitions.yml` (upstream) plus
`app/src/main/gar/tools.yml` (GAR). Both are copied into the APK assets at build time
(`assets/gar/`) and loaded by `ToolCatalog.load(toolsYaml, definitionsYaml)` from `:gar:router`
(snakeyaml-engine). The loader validates the join and fails loudly; a unit test in `:app` loads the
real files so a broken catalog fails `testDebugUnitTest`.

```yaml
tools:
  - name: timer_set            # tool name the model sees; [a-z][a-z0-9_]*, unique
    skill: timer               # SkillInfo.id
    sentence: set              # sentence id in skill_definitions.yml (omit for LLM-only skills)
    description: Start a countdown timer.
    confirm: true              # show GAR's confirmation card on the LLM path (default true)
    confirm_text: "Set the {name} timer for {duration}?"   # the card's question, never shown to the model
    on_lock_screen: true       # the model's calls may run on a locked phone (default false)
    slots:                     # for sentence-backed tools: keys must be capture ids of that sentence
      duration: {kind: duration, required: true, description: How long, exactly as the user said it.}
      name: {kind: text, description: Optional timer label, exactly as the user said it.}
    examples: ["set a timer for ten minutes", "start a pasta timer for eight minutes"]
    keywords: [timer, countdown]   # optional, never shown to the model (the real timer_set has none)

  - name: media_control        # several capture-less sentences behind one enum slot
    skill: media
    sentence_from: {slot: action, map: {play: play, pause: pause, next: next, previous: previous}}
    description: Control the media that is playing.
    confirm: false
    slots:
      action: {kind: enum, values: [play, pause, next, previous], required: true}
```

Slot kinds: `text`, `contact`, `app`, `enum`, `number`, `duration`, `datetime`, `date`, `time`.
Compatibility with capture types: `string` <- text/contact/app/enum; `duration` <- duration;
`number` <- number; `date_time` <- datetime/date/time. Catalog order is the prompt order and is
fixed (tool order changes model outputs). The model always also sees a `no_match` pseudo-tool,
appended last. Its description is per format: `HermesFormat` says "Call this for general
questions, chit-chat and anything that no other function does." (tuned on the eval for Qwen3
abstention; a system message, a stricter `current_time` description and the name `not_a_command`
were tried and dropped because they cost commands or abstention), FunctionGemma keeps "Call this
when no other function matches the request." (the tuned text didn't help it).

Online skills have no tools except weather (search, lyrics, translation, joke, knowledge,
dictionary, currency), nor has the skill that turns them all on ("Online skills"). Weather's
slotless `weather{}` is offered even while the skill is off, behind its ask-once gate ("Weather").
The song skill has one:
it works offline first, and its network tier only runs once the user sets up a service ("Step 4:
song"). At routing time only
tools whose skill is enabled and built (`SkillHandler.enabledSkillsInfo` + `build()`) are offered,
plus the tools of online skills that are off, wrapped in their `GarOnlineGateSkill`,
and `wake_word` only once a wake word device is chosen (GAR's default is none); the static prefix
is rebuilt when that set changes.

`confirm: false` is only for tools that are harmless when the model guesses wrong and for tools
whose skill confirms on its own; `GarCatalogTest` keeps that list:
- harmless: `timer_query`, `current_time`, `calculate`, `alarm_list`, `calendar_query` (only
  reads), `volume`, `volume_set`, `do_not_disturb` (the answer says the new state, one tap undoes
  it), `device_setting` (says what changed, or opens the setting's page or controls, "Device
  settings"; GAR checks its calls against the words first and asks before a switch they don't
  name, `DeviceSettingCalls`), `stopwatch`, `date_query`;
- confirmed by the skill: `call_contact` (the telephone skill asks before calling), `sms_send`
  (its card reads the recipient's name and the message back and asks "Ready to send it?"; a
  spoken yes or a tap on Send sends);
- `song_identify`: it names what plays, or opens Audile, or listens for 10 seconds when the user
  set up a service; the listening card says where the recording goes and has a Stop button that
  drops it before anything is sent. A wrong call the user doesn't stop does send 10 seconds of
  sound to that service, so this is not "harmless": the song sentences, `SongSkill.score` and,
  for the model's calls, `SongCallFilter` only take inputs that point at what plays and name no
  other thing ("Step 4: song"). Confirming only the model's calls (not Dicio's matches) when
  tier 2 would run is still open.

With a FunctionGemma model every tool is confirmed. A `confirm_text` map gives one question per
value of the tool's enum slot; a tool with several enum slots names the one it goes by,
`{slot: <enum slot>, map: {<value>: <template>}}` (`device_setting`'s action). `on_lock_screen` is
true only for tools without private data or lasting effects: `timer_*`, `media_control`,
`flashlight`, `current_time`, `calculate`, `alarm_*`, `volume`, `volume_set`, `do_not_disturb`,
`device_setting`, `stopwatch`, `date_query`, `song_identify`, `media_play` (also in
`GarCatalogTest`; what plays is on the lock screen's media controls already, and a recording only
goes to the service the user set up). `device_setting` gates itself: its skill answers "Unlock your
phone first." before it opens a page, a radio's controls or an access page, lengthens the screen
timeout, or switches airplane mode or battery saver ("Device settings").
Calls, apps, navigation, notifications, the wake word, the calendar, texts and the Wi-Fi and
Bluetooth settings pages need an unlocked phone; the assistant service doesn't offer the lock
screen affordance at all (`gar/assist/README.md`).

The catalog after PARITY batches 3 and 4 (27 tools, prompt order): `timer_set`, `timer_cancel`,
`timer_query`, `call_contact`, `open_app`, `media_control`, `flashlight`, `current_time`,
`navigate`, `calculate`, `read_notifications`, `wake_word`, `alarm_set`, `alarm_list`,
`alarm_cancel`, `calendar_add`, `calendar_query`, `reminder_add`, `sms_send`, `volume`,
`volume_set`, `do_not_disturb`, `device_setting`, `stopwatch`, `date_query`, `song_identify`,
`media_play`. Batch 3 replaced `wifi` and `bluetooth` with `device_setting` at their position
(one tool for every phone setting, merged so the prompt gets shorter while the model reaches 13
more settings for paraphrases the sentences miss); batch 4 appended `media_play` (what to play is
free text no sentence can list). Everything else in the two batches is sentences, or Kotlin
resolution of existing tools' calls (`open_app` for a settings page, `read_notifications` and
`sms_send` for messages, replies and email, `call_contact` for call backs). Qwen3's static prefix
grew from 8884 to 9503 characters, 2321 to 2494 tokens (as llama-server counted them in the
2026-10-26 and 2026-10-27 evals).

The merge was decided on the eval (2026-10-27, `gar/eval/README.md` "Latest results"; the rule:
undo it if the merged catalog loses more than 2 points of tool accuracy or abstention on either
set, then keep the better catalog). Against Step 5 on the shared cases the merged catalog loses
1.4 points of tool accuracy on `cases.yml` and 3.6 on the holdout and gains 3.0 and 2.6 points of
abstention. So the same catalog with `wifi` and `bluetooth` back (28 tools, 2655 tokens; the steps
are in "Device settings") ran on both sets too: the same tool accuracy on `cases.yml` and 1.4
points more on the holdout, 1 and 3 points more exact slots, but 6 and 13 points less abstention
than the merged catalog and a lower overall rate. The merge stays. The tool accuracy lost is
`media_play`'s, not the merge's: 15 of the 18 shared `media_control` commands became `media_play`
calls (most dropped by its keywords), "pause the music" and "skip this song" among them; without
the cases lost to a `media_play` call, tool accuracy is even with Step 5 or better. Through
`device_setting`, Wi-Fi and Bluetooth commands get the right tool more often (25 of 28, before 22)
and exact slots less often (15, before 21: leftover words in the level, `toggle` for show, `on`
for a question, now and then another setting).

Found by that eval and fixed in Kotlin after it (review of batches 3-4), with no prompt change:
- `media_play` against `media_control`: the router rewrites a `media_play` call whose words are a
  transport control ("pause the music", "skip this song", "keep playing", "play the last song
  again") into the `media_control` call they ask for, before Guard B ("Call filters",
  `MediaControlRewrite`); the eval scores it too. `MediaPlayWords.fromSlots` no longer turns a
  skip or a non-media query into "play anything" ("Media").
- `device_setting`'s slots: the app drops a call whose words name another setting or another
  thing, runs a question as the setting's status, and asks before a switch the words don't name
  (`DeviceSettingCalls`, "Device settings"). App only: the eval doesn't run it.
- brightness commands that went to `volume` or `volume_set` run as the brightness when the words
  are about it (`DeviceSettingCalls.output`, app only).
- the app no longer keeps a `media_play` app the user didn't say after on, in or with ("album",
  "podcast"); the eval's media_play cases now count such leftovers as slot errors (no more
  `optional: [app]` where no app is said).

Replayed on the same model outputs (`gar/eval/README.md`, "Latest results"), the router's part
brings `media_control` back to 13 of 15 and 4 of 5, and tool accuracy on the shared cases to 2.4
points above Step 5 on `cases.yml` and 0.8 below on the holdout. Still open: tuning
`media_play`'s description on the dev cases (`cases.yml`, "added 2026-10-27 (batch 3-4), dev"),
never on the holdout, and an eval run on fresh servers.

Batch 5 appended `weather{}` (28 tools; gar/PARITY.md, "Keeping the model's prompt small",
point 4; "Weather" has the tool). The 2026-11-02 eval (`gar/eval/README.md`, "Latest results")
measured it on the cases both catalogs share, every weather case left out, against the
2026-10-27 outputs replayed with today's router; the catalog without the tool reran on fresh
servers and wrote those same outputs. The rule was the one above (undo it beyond 2 points of tool
accuracy or abstention on either set unless tuning recovers it, then keep the better catalog). As
merged ("Tell the weather, the forecast, rain, snow or the temperature outside.") it cost 3.7
and 5.4 points of abstention on `cases.yml` and the holdout, none of it through weather calls:
adding a tool moves the 0.6B model's answers to unrelated requests ("how are you doing today" ->
`date_query`). Three descriptions were tried on `cases.yml` only; the kept one, "Tell the weather
forecast, rain, snow, wind or temperature, here or in a named place.", costs 2.5 points of
abstention on `cases.yml` (2 of 80 requests), with tool accuracy 1.4 points up and 12 of the 13
weather commands of both sets reaching the tool. On the dev set the rule says remove it (0.5
points over), and on the 443 shared dev cases the catalog without the tool is correct overall
slightly more often (60.0% against 58.7%; comparing the whole sets instead counts the 11 weather
commands that only the tool can get right). The tool stays by the lead's explicit call: weather is
a top daily request that no sentence list covers, and the tool reaches 10 of the 11 dev weather
commands. The holdout's numbers (2.7 points more abstention with the tool) were read before that
call, so the holdout is no longer blind for the weather tool: later weather changes are judged on
the dev set, the holdout only reported (gar/eval/README.md, "Latest results"). Removing the tool
means the tool block in `tools.yml`, its line in `ToolCatalogTest`'s pinned list and
`GarCatalogTest`'s confirm and lock-screen lists, the five relabeled eval cases back to
`no_match`, and `WeatherSkillTest`'s tool cases. Static prefix 2548 tokens.

Sentence captures only hold what Dicio's sentences produce: a recurring time, a date without a
time or a percentage can't be bound to a `date_time`/`number` capture without losing what the user
said, so `GarSentenceBinder` throws for them; tools that need them must be LLM-only
(`GarToolSkill`).

## Prompt formats (`PromptFormat`)

Two implementations; both must render prompts byte-identical to llama.cpp's own chat template
rendering (`llama-server /apply-template`) for the same tools and message, which a test checks.
Reference harness that produced the Step 1 numbers: `~/src/gar-notes/prototypes/gar-eval/`.

- `HermesFormat` (Qwen3, default): ChatML, tools as JSON in the system turn, `enable_thinking=false`
  (empty `<think>\n\n</think>\n\n` in the assistant turn), output
  `<tool_call>\n{"name": "...", "arguments": {...}}\n</tool_call>`.
- `FunctionGemmaFormat`: `developer` turn with the trigger line and declarations, output
  `<start_function_call>call:NAME{key:<escape>value<escape>,...}<end_function_call>` with keys in
  alphabetical order (the template dictsorts them).

Grammar per utterance (GBNF): root = one call of one offered tool or `no_match`; per tool, keys in
the fixed order, required keys always present, optional keys optional; enum slot values from the
enum; every other slot value restricted to a contiguous word span of the normalized utterance
(rules `s_i ::= "w_i" (" " s_{i+1})?`, `span ::= s_1 | ... | s_n`). Stop after the closing token.

`Utterances.normalize` keeps a `+` that starts a word and comes before a digit, from a typed
international number ("+44 7700 900123"). It never follows a word character ("5+3" is still "5
3"). Because the `+` stays at the start of its word, any part of the result normalizes to itself,
so the grammar's word spans and the span check hold, and normalizing twice changes nothing.
Ordinary inputs and the goldens are unchanged (Vosk never emits `+`, so only typed input changes:
"2 +3" stays as typed). The `+` reaches `call_contact`'s and `sms_send`'s slots, and
`spokenPhoneNumber` keeps it.

Required typed slots (duration, time, date, datetime, number) stay required in the grammar, although
that makes the model fill them when the user said no value ("i need a timer" -> `duration:
"timer"`, which Guard A drops, see "Call filters"). Making them optional (2026-10-26, grammar only,
the prompt unchanged) changed no output on the 332 eval cases: Qwen3-0.6B still wrote "timer",
"pay rent", "meeting" and "to buy milk", since the prompt's schema lists the slots as required. On
the review probe it changed one output, for the worse: "set a reminder for the meeting tomorrow at
nine" lost its `when`, which the user had said. Not kept.

## Router output validation

`parse(raw)` never throws: unknown tool, bad JSON/syntax, missing required slot, value not a span,
duplicate keys -> `Failed(reason)` or the slot treated as missing (missing required slots are
asked for, not guessed). Extra keys are dropped. A `Failed` result falls back to Dicio's text fallback.

## Call filters

With 26 tools Qwen3-0.6B abstains much less on out-of-scope requests than with 12 ("good
morning" -> `current_time`, "what's the capital of france" -> `date_query(day: "france")`, "ring
sarah" -> `alarm_set(time: "ring")`). The prompt alone didn't fix that, so `LlmRouter` takes an
optional `CallFilter` that checks every validated call; a call it drops becomes
`RouterOutput.Dropped(call, reason)`, which callers treat like `NoMatch` (GarFallback logs it at
debug level with the reason; the eval reports it). A filter that throws keeps the call. The app
(`garCallFilter`) and the eval use `CallFilter.allOf(KeywordFilter, SlotPlausibilityFilter)`; the
app adds `SongCallFilter` for `song_identify` ("Step 4: song"), `NavigationCallFilter` for
`navigate` ("Everyday offline answers", navigation) and `DeviceSettingCalls.filter` for
`device_setting`, `volume` and `volume_set` ("Device settings"), which the eval doesn't run.

Before the filter, `LlmRouter` runs a `CallRewrite` (`MediaControlRewrite` by default, so in the
app and the eval): a `media_play` call whose input opens with a transport control (pause, stop,
hold, resume, unpause, continue, keep playing, next, skip, previous, go back) or asks to play
only the next or last track ("play the last song again") becomes the offered `media_control` call
with that action. With `media_play` in the prompt the model gave it 15 of the 18 shared
`media_control` commands (2026-10-27), and its keywords then dropped most of them. A request that
names what to play ("play stop and stare", "play back in black") stays `media_play`. Like the
filters, it doesn't change what the model generates, so a replay scores it. A rewrite that throws
keeps the call. The filters:

- Guard B, `KeywordFilter`: a tool with `keywords` in `tools.yml` (never shown to the model) is
  only kept when the normalized utterance contains one of them. A keyword is a word or phrase
  matched on whole consecutive words; each word also matches with a common English ending
  (`s`, `es`, `'s`, `d`, `ed`, `er`, `est`, `ing`: "alarm" ~ "alarms", "loud" ~ "louder"), but
  never inside another word ("time" doesn't match "timer"). The loader rejects a keyword list that
  misses one of the tool's own examples. Keywords are for tools whose requests reliably name what
  they are about and that the model often calls for other requests: `current_time`, `date_query`,
  `flashlight`, `read_notifications`, `wake_word`, `alarm_*`, `sms_send`, `device_setting` (the
  wifi and bluetooth tools' keywords plus each setting's words), `stopwatch`, `song_identify`,
  `media_play` (play, put on, listen, ..., playlist, podcast, album, episode). Tools without a reliable cue have none: calls, apps, navigation, timers, the
  calculator, the calendar ("give dad a ring", "take me to the train station"), media ("next one
  please"), volume ("turn it up") and Do Not Disturb ("no calls or notifications for now").
  Keywords are English, like slot resolution; GarFallback only routes English. The lists follow
  how people ask, with recall first where the tool confirms anyway: `sms_send` has "send" ("send
  mom i'm on my way"; also "send a picture to dad", whose read-back the user then declines) and
  `read_notifications` "message" ("read my messages", confirmed); `current_time` has "noon" and
  "midnight" ("is it noon yet"), `wake_word` "wake up word".
- Guard A, `SlotPlausibilityFilter` (`resolve/`): a typed slot (duration, time, date, datetime,
  number, by the called tool's kinds in `tools.yml`) that is present but `Unresolvable` drops the
  call when its words are no value of that kind, so the call is a misroute and not a value to ask
  about: no word of such a value at all ("timer", "alarm", "to buy milk", "france"), or a complete
  value of another kind, which belongs to other tools (a duration as an alarm's time, "one hour":
  a timer; a time of day as a day, "what's the weather tomorrow morning" -> `date_query`; a time
  of day or a day as a duration). A vague value of the right kind keeps the call, and GAR asks
  about it ("Sorry, I didn't get that. When?"): "later", "after lunch", "a few minutes", "soon",
  "the minimum", "saturday at nine" for an alarm, and a bare number in a duration, time or date
  slot ("i need a timer for twelve minutes" -> `duration: "twelve"`, the unit left out). Missing
  slots and `Ambiguous` values (am or pm, a month without its day) keep the call too; on a date
  slot, only an ambiguous value with a time spoken counts as "a time of day". Whether a time of
  day counts as a day depends on the tool, through the slot rewrite and without touching
  `tools.yml`: `calendar_query` takes the day of it (`TypedSlotRewrites.dayOfTimeOfDay`):
  "tomorrow morning", "friday night" and "tomorrow at three pm" mean that day; "tonight", "this
  afternoon", "at seven" and "noon" mean today (no day). A bare number ("seven", maybe the
  seventh) stays as said and is asked about. The filter applies the same rewrite, so it keeps
  these calls. `date_query` still drops a time of day as a day ("what's the weather tomorrow
  morning"). The words of a value
  (`TypedValueWords`, English): numbers (not "one", mostly the pronoun: "the next one"), time
  units, amounts ("few", "couple", "while", "bit"), parts of day (not in a greeting: "good
  morning"), meals, "am", "pm", "o'clock", "today", "tomorrow", "later", "soon", "now", "early",
  "weekend", weekdays, months (not "may"), ordinals, and for numbers "percent", "max", "min";
  not "next", "this", "time", "clock" or "date". The filter resolves exactly what `GarCallRunner`
  would resolve: the same `SlotResolvers` (the context's `ParserFormatter` and language, looked up
  per call) after the same `GarSlotRewrites`; a slot the rewrite empties counts as missing.
  Languages without number and date support keep every call. Skills that resolve their own
  sentence captures through `GarCallRunner` (alarm, calendar, date, volume) use the same kinds as
  their tools in `tools.yml` (their tests compare them).
  `TypedSlotRewrites` (`resolve/`, applied by `GarSlotRewrites` and by the filter) maps words that
  mean a value but don't resolve: volume levels as the volume sentences read them ("half" = fifty
  percent instead of 0.5, "max", "volume thirty percent"), "next" and the next alarm's day
  ("tomorrow's alarm", "tonight") as the alarm to cancel (no time), the next day alone as no time
  for a new alarm ("tomorrow morning": GAR asks "What time?", like the alarm sentences), and days
  that name no day ("what's the date" -> `day: "date"`, "the week", "right now") as today,
  "today's date" as today and "three days" as "in three days". Volume levels made only of "the",
  "my", "a", "its", "to" or "at" after the volume nouns are no level ("set the volume" gives
  `level: "the"`): GAR asks "What level?", the question for a number slot named level.
  The volume sentences ask it too: a set sentence that says no level ("set the volume", "change
  the volume", "adjust the volume") resolves with `VolumeTool.specOf`, which gives `SET_SPEC`
  (`SPEC` with the level required). Questions (`SpokenVolume.question`, from
  `DeviceSettingWords.openingOf` in `VolumeSkill.score`) and max words keep `SPEC`, so a question
  that reaches a set sentence still reports the volume. Before, the sentence path resolved with
  `SPEC`, where every slot is optional, and "set the volume" only reported it. "What's the
  volume" matches no volume sentence (about 0.57 by Dicio's scorer, under 0.70) and the model's
  `volume` (action required) and `volume_set` (level required) can't report, so it stays a gap in
  PARITY.md: a query sentence would need a guard against "what's the volume of a sphere".
  `calendar_query`'s day is the day of a time of day (above).

Eval (2026-10-26, Qwen3-0.6B Q4_K_M, 329 cases: 269 commands, 60 out of scope; the eval scores
the same model outputs with and without the filters):

| | tool | exact slots | abstention | false calls |
|---|---|---|---|---|
| Step 3 prompt, no filter | 75.1% | 59.5% | 43.3% | 34 |
| + keywords only | 75.1% | 59.5% | 75.0% | 15 |
| + slot check only | 74.3% | 59.5% | 55.0% | 27 |
| + both | 74.3% | 59.5% | 83.3% | 10 |
| + `media_control` description (below) | 75.8% | 61.3% | 83.3% | 10 |

With both filters and the final prompt they drop 53 calls: 23 on out-of-scope requests, 28
misroutes of commands (which would have run the wrong tool or asked a pointless question, e.g.
"turn off my alarm" -> `alarm_set(time: "alarm")`, "wake me up at seven" -> `wake_word`), and 2
commands that had the right tool but no value in the slot ("time or ten minutes" ->
`duration: "time"`, "remind me to buy milk" -> `when: "to buy milk"`), which GAR used to ask about.
The keywords never dropped a call of the expected tool. The 10 remaining false calls pass both
checks: questions with a keyword ("what time does the pharmacy close", "how many hours of sleep
do i need" -> `current_time`, "will it rain today" -> `date_query(day: "today")`, "what's the
wifi password", "how do i send a text message"), `volume` without keywords ("turn up the heat",
"what's the volume of a sphere", "how loud is a jet engine"), "move my meeting to friday" ->
`navigate` and "book a table for two" -> `calendar_add` ("two" is a time, am or pm).

A review probe (2026-10-26) ran 104 other realistic commands and 30 other out-of-scope requests
that aren't in the eval (not tuned on; scratch, not in `cases.yml`). There the filters caught 12
more out-of-scope calls (abstention 40% -> 80%) but also dropped 20 calls of the right tool:

- required typed slots the user didn't fill: the grammar makes the model fill a required slot,
  so "missing" never comes from the model and the slot check drops what used to be a question
  ("i need a timer" -> `duration: "timer"`, "i need a reminder to pay rent" -> `when: "pay
  rent"`, like "remind me to buy milk" in the eval);
- values the resolvers can't read yet: "later", "after lunch", "a few minutes", "five more
  minutes", "in twenty minutes" and "tomorrow morning" as an alarm time;
- phrasings without a keyword: "send mom i'm on my way" (send alone, as chosen above), "read my
  messages", "is it noon yet".

The second round below fixes most of these.

"Set an alarm for tomorrow morning" and "wake me up tomorrow" (and "tonight") now stay with the
alarm sentences, which ask for the time (`AlarmSkill.isNextDay`): before, the alarm skill passed
them to the model as no time of day, and the slot check then dropped the model's call. Other days
("friday") still go to the model, since an alarm rings at the next occurrence of its time. The
wake word's keywords include "microphone" and "mic", the listening sentences' own words, and the
alarm tools' "wakeup". With three held-out cases for them (332 cases; the model's other outputs
unchanged) the eval scores tool 75.7%, exact slots 61.0%, abstention 83.3% (10 false calls),
overall 65.1%; the earlier keywords would have dropped two of the three.

Second round (2026-10-26, after the probe): Guard A keeps vague values of the slot's kind, the
resolvers read more values, wider keywords, and GAR sentences for inputs Dicio's timer took (see
"Step 3 skills"). Tuned on the dev cases and the probe only. The filters, resolvers and keywords
don't change what the model generates, so each change is scored on the same recorded outputs
(`-Pgar.eval.replay`); the final outputs were recorded once more on fresh servers, identical to
the earlier ones on all 332 + 134 shared cases. Dev: 153 cases (124 commands, 29 out of scope);
held-out: the other 179 of the 332, plus 10 paraphrases for the new keywords in the last row
(189); probe: 104 commands, 30 out of scope (tool = exact slots there, it has no slot labels):

| change | dev: tool / exact / abstention (false calls) | held-out | probe |
|---|---|---|---|
| before | 79.0% / 71.0% / 79.3% (6) | 73.0% / 52.7% / 87.1% (4) | 48.1% / 80.0% (6) |
| required typed slots optional in the grammar (not kept, see "Prompt formats") | unchanged | unchanged | 48.1% / 80.0% (6), one value left out |
| Guard A keeps vague values | 79.0% / 71.0% / 75.9% (7) | unchanged | 56.7% / 80.0% (6) |
| + resolvers | unchanged | unchanged | 57.7% / 80.0% (6) |
| + keywords | 79.0% / 71.0% / 69.0% (9) | 73.0% / 52.7% / 83.9% (5) | 62.5% / 80.0% (6) |
| + GAR sentences (fast path only) | unchanged | unchanged | unchanged |
| final, 342 cases with the new paraphrases | 79.0% / 71.0% / 69.0% (9) | 73.4% / 53.8% / 83.9% (5) | 62.5% / 80.0% (6) |

The costs are out-of-scope requests that now get a question or a card: "my alarm didn't go off
this morning" (`alarm_set` asks what time; "this morning" is a vague time), "send a picture to
dad", "send an email to my boss" (sms read-backs the user declines) and "read my last text message"
(confirmed reading of the notifications). Since PARITY batch 4 the last two are commands on the
phone: the email and messages sentences take them before the model, and the model's `sms_send`
for an email opens the mail app instead ("Messages and email"). Of the 10 new paraphrases, 7 got the right tool through
the filters (the model abstained on "do i have any messages" and navigated "get me back online").

What the guards drop now (final outputs): on the 342 cases the keywords drop 32 calls (15 out of
scope, 17 misroutes, none of the expected tool) and the slot check 21 (6 out of scope, 13
misroutes, 2 of the expected tool: "time or ten minutes" -> `duration: "time"`, "remind me to buy
milk" -> `when: "to buy milk"`); on the probe the keywords drop 19 (6 out of scope, 13 misroutes;
before 24 with 5 of the expected tool) and the slot check 17 (8 out of scope, 5 misroutes, 4 of
the expected tool; before 30 with 14): "i need a timer" -> `duration: "timer"`, "i need a
reminder to pay rent" -> `when: "pay rent"`, "add a meeting to my calendar" -> `when: "meeting"`
(required slots the model fills with the request's own words, see "Prompt formats") and "delete
all my alarms" -> `alarm_cancel(time: "all")` (the alarm sentences take that one in the app).

A blind held-out set (`gar/eval/holdout.yml`, 160 cases: 123 commands, 37 out of scope, written by
another agent while this round was tuned, run once at the end): before this round tool 77.2%,
exact slots 61.8%, abstention 73.0% (10 false calls), overall 64.4%; after it tool 78.9%, exact
slots 62.6%, abstention 70.3% (11 false calls), overall 64.4% (all of the change from Guard A;
without any filter: tool 84.6%, abstention 43.2%). There the keywords drop 15 calls (8 out of
scope, 1 of the expected tool) and the slot check 12 (3 out of scope, 6 of the expected tool;
before 17 with 8).

The only prompt change of this round: `media_control`'s description lists its actions ("Play,
pause, skip or go back in the music or media that is playing." instead of "Control the music or
media that is playing."), tuned on the dev cases (overall 71.2% -> 72.5%, nothing worse) and
checked on the held-out cases (57.4% -> 59.1%; `media_control` 38% -> 77% tool accuracy overall).
"Set a new alarm" for `alarm_set` gained commands on dev but cost an abstention, and more calendar
wording lost on dev; both were dropped.

## Checking Dicio's own matches

Dicio's matcher still accepts a sentence with one word swapped: "switch off the music" matches the
flashlight's "switch off the light", "turn off the tv" the timer's "turn off the timer", "turn on
the lights" the media skill's "turn on the player". So the evaluator's matching step
(`garMatchUtterances`, `GarMatching.kt`) gets a check from `GarFallback.matchCheck` once per input,
and `SkillRanker.getBest` asks it about every match of Dicio's default batch before it drops the
pending follow-up batches; answers to a pending question are never checked. A rejected match is no
match: the batches stay, the next STT alternative is tried as upstream does, and when none matches
the input goes to the model, then to the text fallback. Once every alternative failed, a rejected
match ends a pending question (see "Conversation"). There is no check (GAR behaves like Dicio)
when the model is off, missing or can't load (`GarRouterSource.canRoute`: its load failed or
`GarLoadGuard` refuses it, until the user retries), or when the language isn't English.

`GarMatchCheck` rejects a match when:

- the input names none of the skill's keywords outside the sentence's captures (a capture takes
  any words: "what will the weather be tomorrow" matched the date's "what will the date be
  .day."). The keywords are the tools' `keywords`, used only when every tool of the skill has
  some, plus sentence-only keywords kept out of `tools.yml` so that they don't widen Guard B:
  "clock" for the alarm ("show my clock"), "mike" for listening and the media skill's nouns (song,
  music, video, ..., "one", podcast, episode, playlist, radio), the connectivity sentences' words
  (the wifi and bluetooth tools' old keywords, since `device_setting` took their calls) and the
  device settings' ringer and page words ("mute my phone", "open developer options"). Skills
  without tools and without sentence keywords (the everyday answers, calls, messages, email, the
  media controls) are never rejected. "next", "previous", "last",
  "following" and "rewind" count only in an exact match (score 1): the media sentences make the
  noun optional, so "go to the next page" and "play the last voicemail" matched with one word
  swapped;
- a match that isn't exact has a word naming another thing next to the keyword: "turn on light
  mode", "turn on wifi calling", "read my notification settings", "start the video call"; or it is
  an alarm cancel without a cancel verb ("next alarm"), since the alarm sentences' cancels aren't
  confirmed;
- the timer (its tools have no keywords): kept when the input names the timer (timer, countdown,
  ping, bell, alert, sound, ringtone, beep, buzz, outside the timer's name), when a cancel or a
  question names a running timer ("cancel the pasta", the name or a part of it), when a question
  without a name comes while a timer runs ("how long is left"), or when a new timer whose duration
  Dicio understood says "time" (STT drops the r of "timer") or "minute alarm" ("set a five minute
  alarm"). "time" alone isn't the timer's word: "the time" cancelled the running timer, "turn off
  screen time" and "how much time is left on the battery" matched. Without a running timer, a
  cancel or a question that doesn't say "timer" goes to the model, since Dicio would only answer
  that there is no timer;
- navigation (2026-11-02): a match that isn't exact and has the "how (do|can|would|should) i
  get|go" line's verbs without a "how" (or "where") before them and no route word
  (`NavigationWords.skipsHow`): "when should I go to bed" matched "how should I go to .where."
  with its "how" skipped. "How do I get to the airport", "where can I get gas" and "can I get
  directions to the mall" stay;
- the calculator's calculation has no number, or one number among other words: with the online
  skills off (GAR's default) "what is .calculation." takes any question ("what's the time in
  london", "what's the forecast on the 5th"), and the calculator only computes between numbers. With
  one it says that number back ("5" for "what's the forecast on the 5th", "64" for "the square
  root of sixty four", which the model's call gets right through GAR's rewrite). A number alone still runs: the number parser already
  turns "12 divided by 4" into 3. With two or more numbers, the match goes to the model when
  words follow the last number (the calculator ignores them), or when non-blank words between two
  numbers score below 0.3 on the calculator's operator sentences (`CalculatorSkill.getOperation`'s
  threshold; the calculator would add the numbers). Examples: "what is the score of the 49ers
  game" (dicio-numbers reads "score" as 20, "of" is the calculator's "times" and "ers game" is
  left over, so it answered 980), "two and three", "2 x 3", "eight times seven please". Blank gaps
  are fine, because the parser takes "plus" and "minus" into the next number ("seven plus nine"
  is 7 and +9). `GarMatchCheck.State.calculatorOperators` defaults to the English operator
  sentences, since only English matches are checked. Calculations followed by extra words ("what
  is 8 times 7 please", "5 plus 3 equals") so take the model's second or so; "the score of the
  game 3 to 2" still runs, since every part scores as an operator.

Near-misses that keep the keyword and add a harmless word still run ("turn on the night light"
turns on the flashlight). Since `gar:answers`, the world clock takes "what time is it in tokyo"
(it used to give the local time), the math skill "the square root of sixty four" and "what's
fifteen percent of eighty", and the holidays "what's the date of easter", before the calculator
or the current time see them ("Everyday offline answers").
`GarRankingTest` ranks with the skills of a new install (online skills off, behind their gates) and, for most lists,
with all skills too: the skills' own phrasings are kept, the near-misses rejected, with and without
running timers. Bare "mute" and "unmute" no longer stop wake-word listening; they go to the model.

## Resolvers (`:gar:router`, `resolve/`)

`Resolution<T> = Resolved(value) | Ambiguous(options, kind) | Unresolvable(reason)`.

- `DateTimeResolver` wraps dicio-numbers (`ParserFormatter`), English rules first, with a fixed
  `now` for tests. Known dicio-numbers gaps it must handle (Step 1 findings,
  `~/src/gar-notes/recon/numbers.md` and `zz-critic-gaps.md`):
  - time slots get an `at ` prefix ("seven fifteen" is otherwise July 15);
  - "ten/eleven/twelve + minutes" parse as years: normalize spoken hour+minute to `HH:MM` first;
  - reject partial parses (the whole slot must be consumed, ignoring filler words);
  - bare weekdays and days of the month roll forward to their next occurrence;
  - a month said without its day ("december", "in december", "next month", "december 2027") is
    no day. dicio-numbers read it as the 1st or as today's day of the month; now it is
    `Ambiguous(options, DAY_OF_MONTH)` with every day of that month as an option (from today on
    when it is this month), each at the time said, if any. `date` gives the same options as
    `ResolvedDate`. A month name rolls forward, also after "in" or "on" ("january" said in
    October is next January, "in october" this October from today on; dicio-numbers alone reads
    "in october" said in October as next October); with "next" or a year it stays as
    dicio-numbers reads it. A counted month is an offset, so a day ("one month ago", "1 month
    ago"). A day said as a cardinal after "the" ("the twelve of december", "the twenty one of
    december") is that day: it is read as "the 12th of december", because dicio-numbers reads
    such a cardinal up to 24 as an hour on the 1st. "december at midnight" stays
    `Unresolvable`, because each day's end is the next day's start and a day picked by its date
    would be the wrong one. GAR asks "Which day in December?" (no options on the card, there are
    too many). `GarCallRunner` reads the answer inside that month: "the fifth", "5", "the twenty
    first", "the fifth at nine pm" (its own time wins), "the fifth at seven" (then morning or
    evening). The time said with the month stays. Another month ("january") asks again for that
    month, and a complete new answer ("tomorrow") is taken. A day of this month that has passed
    ("the fifth" asked on the 25th, or today at a passed time) answers nothing, so the question
    stays for another try; on its own the answer would be next month's fifth. Per tool:
    `calendar_add` and `reminder_add` ask, since they need a point in time; `calendar_query` asks
    too, because the calendar shows one day only (`CalendarStore.eventsOn`,
    `CalendarRequest.Query`, `EventsOutput`); `date_query` asks; alarms have only a time slot, so
    "seven am in december" stays not understood ("What time?"). The answers are understood in
    English only, like the rest of GAR's answer handling;
  - the small hours of a night said with its day are on the next day ("friday night at two" is
    early Saturday), and so is a day's "midnight": "friday at midnight", "midnight on friday" and
    "today at midnight" are the midnight after that day, like "tonight at midnight" (said on
    Sunday, "sunday at midnight" is tonight, not a week away). A day's midnight is that day's end
    ("tomorrow at midnight", "december twelve at midnight"), confirmed by Aaron 2026-10-27;
  - no am/pm and no part of day on hours 1-11 -> `Ambiguous([am, pm])` (never guess);
  - durations: "an hour and a half", "two and a half minutes", "a minute and a half", "five
    more minutes" ("more", "another", "extra" dropped), "one minute thirty", "an hour fifteen"
    (a number after the last unit counts the next smaller one), "twenty minutes from now", "ten
    minutes later" (a timer for twenty minutes, which Guard A would otherwise drop as a time of
    day);
  - "N hours/minutes from now", "in N ..."; in a time slot the time of day then, rounded like the
    alarm fast path (+30 s, to the minute, but never the minute that has begun: "in ten seconds"
    would ring tomorrow) and only within a day ("i need an alarm in twenty minutes"). `now` is a
    `ZonedDateTime` (the skills' clocks are zoned too). Hours, minutes and seconds are added on
    the time line, so they are that much real time across a daylight saving change: "in twenty
    minutes" at the first 1:50 am of the night the clock goes back is the second 1:10 am. Days,
    months and years are calendar ones ("in three days" is at the same time of day). When the
    result is a time the clock shows twice, `ResolvedDateTime.offset` says which one is meant.
    `atZone` uses it, and so do the calendar's "that time has passed" check and its provider
    millis (`EventTimes.offset`, `CalendarTime.providerMillis`). The resolver's offsets are
    elapsed time. An alarm set "in N minutes" still goes to the clock app as an hour and minute,
    and inside the repeated hour the clock app may read that as the earlier, already passed 1:10
    (the `AlarmClock` intent can't carry an offset). A calendar event that starts inside the
    repeated hour gets its end by clock-face arithmetic, so a reminder at the first 1:50 am lasts
    75 real minutes;
  - "this weekend", "the weekend": its Saturday (today on Saturday and Sunday); "next weekend"
    stays unresolvable (whose next weekend?), so GAR asks;
  - a part of day without a clock time ("tonight" 9 pm, "tomorrow morning" 9 am) resolves with
    `ResolvedDateTime.approximateTime`: events keep that hour, a reminder asks "What time?" and
    reads an am/pm answer as the one nearer to the part of day ("nine" after "tonight" = 9 pm,
    "twelve" midnight, `CalendarSkill.NearestTimeResolver`); the answer is on the day said, after
    the next midnight when it is more than 12 hours before the part of day's hour ("tonight",
    "one": 1 am tomorrow, not today's, which has passed), and "tonight" said after 9 pm is still
    today ("eleven forty five" in a quarter of an hour, `reminderAfterPartOfDay`);
  - recurrence ("every weekday", "every monday", "daily", "weekends") -> `Recurrence` value;
  - report which fields were spoken (date part, time part).
  Languages other than en/it -> `Unresolvable`.
- `DurationResolver`, `NumberResolver` (percent, "fifty percent").
- `NameMatcher` (pure): fuzzy match of a spoken name against candidates, tolerant of STT splits
  ("spot if i" ~ "Spotify", "you tube" ~ "YouTube"); returns Resolved/Ambiguous(top N)/Unresolvable.
  `:app` matches app slots against the launcher labels, so the confirmation shows the app that
  will open and close matches become a pick question. Contact slots stay as said: the telephone
  skill matches contacts itself and always asks before calling; a number spelled out digit by
  digit is dialed through the telephone skill's confirmation instead. The telephone skill lists a
  contact's numbers through `SmsNumbers.distinct` (GAR edit in `TelephoneSkill.generateOutput`):
  one number stored as 435-555-0167 and +14355550167 is listed once (+14355550167), so a close
  match goes straight to its call confirmation instead of a list of numbers. SMS is unchanged.

## Engines (`CompletionEngine`)

```kotlin
interface CompletionEngine {
    suspend fun complete(request: CompletionRequest): Completion
    suspend fun close()
}
```

The engine keeps the KV cache of the last `prefix` and only evaluates the suffix when the prefix is
unchanged. `LlamaEngine` (`:gar:llm`): one model per instance, all native calls on one dedicated
thread, `swa_full = true`, greedy sampling with grammar rejection sampling, stop on end-of-generation
or a stop string, cancellation, optional prefix state file so reopening the UI skips the prefill.
`LlamaServerEngine` (`:gar:router`): the same contract over llama-server's HTTP API, for the eval
and integration tests on the Mac.

## Model lifecycle and settings

`GarModelManager` (`@Singleton`): which model file is active, download/import/delete, and a
ref-counted engine: `acquire()` when the assistant UI is visible (MainActivity `onStart`, not on
configuration changes; VIS session shown), `release()` on hide with a 30 s grace delay, plus
on-demand load for the wake-word path. Loading starts as soon as the UI opens so it overlaps with
listening. `onTrimMemory` unloads an idle engine.

The context is sized for the prompt (`GarModelManager.contextSizeFor`: prefix tokens + 256 for the
utterance + 96 for the answer, at least 2048; about 2270 prefix tokens for the 26 tools of Step 3,
1000 for the 12 before, 2494 for the 27 after PARITY batches 3 and 4, as the 2026-10-27 eval
counted them), since the
whole KV cache is allocated and cleared at load (112 KiB per token for Qwen3-0.6B). The JNI layer
sets `n_outputs_max = 1` (only the last token's logits are
used). Before routing, the prefix is warmed up outside the router's 20 s timeout.

Counting the prefix's tokens needs a loaded engine. Before, every start loaded at 2048 tokens,
counted, then reloaded at the counted size: two loads of about 1.3 s each on the Pixel 9a.
`GarModelManager` now remembers the size each model needed (`cache/gar-context-size`, last model
only, keyed by the model file's id and prompt family) and loads at that size, so a start loads
once. A new or switched model, a cleared cache, or a longer prefix (an update that adds tools)
loads twice once. A shorter prefix keeps the bigger context until the next load
(`loadWithContextFor`, `GarContextSizeTest`). `GarEngineHolder`'s triggers (settings flow,
`onStart`, `onResume`, a route) already merged into one load; `GarEngineHolderTest` pins that a
start's preloads plus a first command that comes while the model loads load it once.

A model that failed to load is not loaded again until the file changes or the user taps the error
in the settings (`GarEngineHolder` remembers the failure). A load that never finishes because the
process died (llama.cpp aborts on some malformed GGUF files, or the system kills the app for
memory) leaves a marker file (`GarLoadGuard`); after two such loads in a row the file is blocked
the same way, so a bad import can't crash the app on every start. Imports bigger than a quarter of
the phone's RAM, or than the free storage, are rejected. After the UI hides, the model stays 30 s
only while the process is not cached (assistant bound, wake word running); otherwise it is unloaded
after 6 s, before Android's freezer stops the timer.

The load and the warm-up give way to a command being matched ("Routing speed"): they wait for a
running sentence match (2 s at most, before the native load lock), and the engine thread runs at
`GarMatchPriority.MODEL_LOAD_PRIORITY` (nice 9: lower than normal, short of
`THREAD_PRIORITY_BACKGROUND`, which would move it to the little cores) until the warm-up is done
(`LlamaEngine.background`, applied through `LlamaConfig.threadPriority`, since `:gar:llm` has no
`android.*` code). llama.cpp starts its worker threads from the engine thread for each
evaluation (no OpenMP), so they inherit that priority. Routing a command and a warm-up for
changed tools run at the normal priority; saving a prefix state file always runs low.

Settings (`gar_settings.proto`, own DataStore file): LLM on/off (on), model (Qwen3-0.6B Q4_K_M
default, FunctionGemma 270M Q8_0, custom GGUF), confirm model-understood commands (on).
Downloads: pinned Hugging Face revision URLs, HTTP status check, resume with `Range`, size and
sha256 verification. If the server answers a resume with another part of the file (a wrong or
missing `Content-Range`), the `.part` file is deleted and the file downloaded from the start,
once per attempt; a second wrong answer, or one to a download that started at 0, still fails
("Server resumed at X instead of Y"). This downloader (also for the Whisper speech model, "Speech recognition (Whisper)"), Vosk and
wake-word model downloads are the core assistant's only network access; llama.cpp is built without its HTTP/TLS code. The one exception among GAR's
skills is the song skill's recognition service (Step 4): off until the user picks AudD or
ACRCloud and adds their own token, and its settings, its card and its first answer say that it
sends a recording over the internet. Upstream's online skills stay off by default, and GAR asks
once before one of them runs ("Online skills").

| Model | File | Bytes | sha256 |
|---|---|---|---|
| Qwen3-0.6B Q4_K_M (Apache-2.0) | `unsloth/Qwen3-0.6B-GGUF` @ `50968a44…` `Qwen3-0.6B-Q4_K_M.gguf` | see `ModelCatalog.kt` | `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a` |
| FunctionGemma 270M Q8_0 (Gemma Terms of Use) | `ggml-org/functiongemma-270m-it-GGUF` `functiongemma-270m-it-q8_0.gguf` | 291557792 | `83940d4d…5270` (full value in `ModelCatalog.kt`) |

## Assistant entry points

`:gar:assist` registers a `VoiceInteractionService` (supportsAssist, recognitionService =
Dicio's `SttService`). Since 2026-11-02 its session shows the assistant sheet in its own window
("Assistant sheet" below); with "Open the full app for the assistant" on, or when the sheet's class
can't load, `onShow` starts `MainActivity` with `ACTION_ASSIST` and finishes as before. The `ASSIST`
activity filter stays for explicit callers. The user selects GAR under
Settings > Apps > Default apps > Digital assistant app.

The home screen shows "Make GAR your assistant" while GAR isn't the digital assistant
(`GarAssistantCard`, `gar/assist/` in `:app`, with one GAR line in Dicio's `InteractionList`).

- **When it shows:** neither `GarAssist.isActiveAssistant` nor
  `RoleManager.isRoleHeld(ROLE_ASSISTANT)` (API 29+), checked on every resume
  (`LifecycleResumeEffect`), so the card goes away when the user comes back from Settings. GAR
  counts as the assistant either way, also when the system took only its ASSIST activity. In
  previews it shows nothing (no Hilt there).
- **Text:** "Swipe up from a bottom corner (or hold Home) to open GAR, and let Audile listen in
  the background." Then: "Tap Default digital assistant app, then GAR." It names the corner
  swipe (gesture navigation) and holding Home (three buttons) because GrapheneOS has no
  hold-power-for-assistant option (gar/PARITY.md). The card doesn't promise the lock screen: the assist
  path doesn't show `MainActivity` over the keyguard (only the wake word does), and that isn't
  checked on the phone. The UI copies `WakeWordWidgetImpl`: `MessageCard` on
  secondaryContainer, a centered headline and body, then "No thanks" and "Open settings".
- **Why no direct request:** `ROLE_ASSISTANT` has `requestable="false"` in PermissionController's
  `roles.xml`, and the picker activity (`Intent.ACTION_MANAGE_DEFAULT_APP`) needs
  `MANAGE_ROLE_HOLDERS`.
- **What "Open settings" opens,** the first that works (`GarAssistantPrompt.SETTINGS_ACTIONS`):
  1. `Settings.ACTION_VOICE_INPUT_SETTINGS`: Settings' ManageAssist, whose first row is "Default
     digital assistant app" when `config_show_assist_and_voice_input` is set (true in AOSP).
  2. `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`: PermissionController's default apps list.
  3. `ACTION_SETTINGS`.
- **Hiding:** GAR never changes the role. "No thanks", or GAR being the assistant once, hides the
  card for good (`assistant_card_hidden` in `gar_settings.proto`, `GarAssistantCardStore`); it
  doesn't come back if the user later picks another assistant.
- **Not checked** on the phone: the ManageAssist row on GrapheneOS and the card hiding after
  picking GAR. Tests: `GarAssistantCardTest` (the pure rules on `GarAssistantPrompt` and the
  store).

Over the lock screen: only the wake word shows `MainActivity` there (Dicio's `setShowWhenLocked`
in `handleWakeWordTurnOnScreen`; `onStop` turns it off again). The evaluator's interaction log
lives as long as the process, so the cards of an unlocked phone (messages and reply drafts, call
log rows with numbers, email drafts, texts) would show to anyone who says the wake word, and
their buttons and pending questions ("Want to reply?") would work there. A GAR line in
`MainActivity` calls `SkillEvaluator.forgetInteractions` (a GAR method with an empty default)
when the wake word opens GAR on a locked phone (`isDeviceLocked`, as GAR's other lock checks): it
clears the log, the previous output and the ranker's batches (review). A second wake word on the
same locked phone starts over too. Not checked on the phone.

### Assistant sheet

The assistant is a sheet in the VoiceInteractionSession's own window (`setUiEnabled(true)`,
`onCreateContentView()` returns a ComposeView; spec: gar-notes/ui-makeover-design.md, section 1).
MainActivity stays for history, settings and "Open the full app for the assistant", and for a
wake word on a dark screen.

- **Mic silenced in the sheet** (Pixel, 2026-11-05: 8 failed turns in two bursts, fine once the
  full app was open). The session window never makes GAR's process TOP (it stays FOREGROUND
  through the VIS bindings), and AudioPolicyService::updateUidStates_l silences a non-TOP
  recorder when another app records at TOP or a privacy-sensitive capture runs; only a TOP
  assistant with VOICE_RECOGNITION wins. GAR already holds the mic capability, so waiting or a
  foreground service doesn't help. Now a capture that stays silenced hands the turn to
  MainActivity, which listens on arrival (`GarMicSilenced`, set by the sheet), instead of an
  stt_error. Not built yet: a translucent GAR activity started with `startAssistantActivity()`
  under the sheet, which would make GAR TOP and keep the sheet up.

- **Ways in**
  - Assist gesture, power button or corner swipe: the session shows the sheet
    (`GarAssist.SOURCE_ASSIST`).
  - Wake word: `WakeService` starts the capture as before. Then `GarSheet.showForHotword` shows
    the sheet with `SOURCE_HOTWORD` when all of these hold:
    - GAR's assistant service is ready;
    - the screen is on (`PowerManager.isInteractive`);
    - GAR's home isn't in front (it shows the turn itself);
    - the full app isn't wanted.

    Otherwise the MainActivity / full-screen-intent path runs as before. It turns the screen on
    and shows over the keyguard.
  - MainActivity keeps its `ACTION_ASSIST` filter.
  - No Quick Settings tile yet: `showAssistSession` from a tile can't collapse the shade, so the
    sheet would draw over an expanded shade (open: a `startActivityAndCollapse` trampoline, or
    accept that).
- **Setting.** "Open the full app for the assistant" (`assist_full_screen = 66` in
  gar_settings.proto, off by default) brings back MainActivity. It is under GAR settings >
  Assistant. It is read from `GarStartupCache` at show. Until the cache has read the settings,
  the sheet is used.
- **Loading.** `:gar:assist` has no Compose and no Hilt. It loads
  `org.stypox.dicio.gar.sheet.GarSheetUi` once by name (`GarAssist.SHEET_UI_CLASS`, `@Keep` for
  R8). If that fails, sessions open MainActivity.
  - `GarVoiceInteractionService.onReady` calls `prewarm` on a lowest-priority thread. It only
    starts `GarStartupCache` and builds `LocaleManager` (one language read). It never touches
    skills, STT, TTS or the model.
  - First show: a shell (handle, state row "Starting") draws from the cached theme. If the cache
    is empty there is one empty frame, never a blocking read.
  - Then the singletons come through `GarSheetEntryPoint` on the first main-thread idle after the
    first frame, or 300 ms after it at the latest: on gartest under load the shell's spinner kept
    the main thread from ever going idle, and the sheet stayed in "Starting" (found on the
    emulator pass). When a show in this process or MainActivity has already built them, they are
    fetched at once.
  - The model is acquired with `GarModelManager.acquireAfterFirstFrame(view)`. The handle is
    closed in teardown.
- **Timing line.** `GarSheet: first_frame_ms=<n> source=<assist|hotword|tile> cold=<bool>` is
  logged once per show. It measures onShow to the Choreographer frame after the first draw.
  `cold` means no earlier show in this process fetched the singletons and MainActivity isn't
  created. `GarJank.track(window, "sheet")` runs while the sheet is shown, and
  `GarJank.state(window, "mic", micState.tag)` tags the mic state.
- **Session plumbing.** `GarSessionHost` carries the session context, its Dialog window, the
  source, whether the phone was locked at show, `dismiss()` (hide, then finish, posted) and
  `openApp(intent)`.
  - `openApp` checks `KeyguardManager.isKeyguardLocked` at the moment of the call, so a phone
    unlocked meanwhile skips the trampoline. While locked it routes through
    `GarUnlockActivity`. That activity is not exported, shows when locked, is translucent and has
    no UI. It calls `requestDismissKeyguard`, then starts the targets. If the user cancels, it
    just finishes.
  - Any hide (dismiss, home, shade, recents, lock screen) runs the sheet's teardown and finishes
    the session.
  - A voice activity (`GarAssist.startVoiceActivity`) shown while the sheet is up takes the
    session over: the sheet is torn down and the UI turned off.
- **Window.** The session's theme sets `FLAG_TRANSLUCENT_STATUS` and
  `FLAG_TRANSLUCENT_NAVIGATION`, which drew a grey band over the sheet's bottom edge on gartest.
  The sheet clears both, sets `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` and transparent bars, and pads
  itself by `navigationBars union ime`.
- **Spike 1.0** (read from the Android 15/16 framework sources, then checked on gartest where
  noted)
  - **Back:** a callback on `window.onBackInvokedDispatcher` at PRIORITY_DEFAULT outranks the
    Dialog's default, which is registered at system priority.
    - The dispatcher only accepts app callbacks while predictive back is on for the app: Android
      16 with targetSdk 36, or the manifest opt-in. Otherwise registration is refused with a log
      line. No `enableOnBackInvokedCallback` was added to the manifest: it would change
      MainActivity too.
    - So on the Pixel, back uses `OnBackAnimationCallback` (34+), whose progress shrinks and
      slides the sheet; on 33 a plain `OnBackInvokedCallback`.
    - On the API 35 emulator and on 28-32, back is a key event: Dialog.onKeyUp, then
      VoiceInteractionWindow.onBackPressed, then the session's onBackPressed. The session
      forwards it to `GarSessionUi.onBackPressed` on every API level. Both paths dismiss.
  - **IME:** the session window is built with takesFocus=true (focusable, NOT_TOUCH_MODAL, no
    ALT_FOCUSABLE_IM). `DisplayContent.assignRelativeLayerForIme` puts the IME directly above its
    target, even though the IME's own layer (13) is below TYPE_VOICE_INTERACTION (21).
    - So the keyboard button opens an in-sheet `OutlinedTextField`; typing stops listening, and
      IME Send calls `GarVoiceLog.hint(TYPED)` then `processInputEvent(Final)`. The `EXTRA_TYPE`
      hand-off isn't used by the sheet.
    - The window is MATCH_PARENT with fitInsetsTypes 0, so it doesn't resize; the sheet pads
      itself by the IME inset. Checked on gartest: the keyboard comes up under the sheet and the
      sheet rises above it.
  - **Lock screen:** TYPE_VOICE_INTERACTION (layer 21) is above the notification shade and
    keyguard (17). The system hides the session when the lock screen shows
    (`onLockscreenShown`).
  - **Screen off:** the sheet doesn't turn the display on. That is why a wake word on a dark
    screen keeps the MainActivity path. FLAG_TURN_SCREEN_ON isn't used.
- **What it shows.** A bottom-anchored Surface (ExpandedShape, surfaceContainerLow, at most 640 dp
  wide and 85% of the height below the status bar) over a 32% scrim. Top to bottom, which is also
  the TalkBack order:
  1. a 48 dp handle that says "Close";
  2. the state row (32 dp mic, state label; no live region while Listening or Starting; focused
     after the first frame);
  3. the request: italic while pending, tap to edit;
  4. the latest answer, at most 50% of the height and scrolling; a polite live region only when
     TTS doesn't say it (`QuestionAnswer.spoken`, GAR-marked, set by the evaluator before the
     answer lands), so TalkBack and TTS never speak at once and the barge-in recording doesn't
     hear TalkBack;
  5. chips, or starters before the first turn (none when locked);
  6. the pinned action row: keyboard, the 72 dp mic, "Open GAR".

  3 to 5 sit in a weighted (`fill = false`), scrolling Column, so with the keyboard up or in
  landscape the 85% cap squeezes them and never the handle, the state row or the action row.

  Pane and window title are "Garble".
- **Whose turns.** Only the session's turns are shown (`GarSheetState.baseline/select`, tested).
  - The baseline counts every answered turn at show, plus one for a route still in flight. A
    pending question from the wake word's own capture is not counted (`countPending = false`
    for HOTWORD). For HOTWORD the baseline is taken in onShow from the evaluator WakeService
    already built, not at the deferred cold fetch: the wake word's turn can be answered before
    that fetch (cold first frames of 12 to 16 s on gartest), and the sheet would hide its own
    answer.
  - A pending question from before the show stays hidden until it is answered. If it ends
    without an answer, the baseline comes back down, so the sheet's first answer isn't hidden.
    Known race: the pending-at-show question ends in silence and a new question arrives within
    the same frame.
  - Over the lock screen the sheet calls `SkillEvaluator.forgetConversation()`. That clears
    follow-ups, previousOutput, the continued window and the ranker's batches, but keeps the log.
    MainActivity's locked path keeps `forgetInteractions()` (which now calls
    `forgetConversation()` too).
  - For the wake word the forget happens in WakeService before the capture starts
    (`GarSheet.beforeHotwordCapture`, one GAR-marked line): a cold sheet fetches the evaluator
    only after its first frame, and by then "reply ok" could have matched the
    MessagesReadAnswerSkill batch of a message read while unlocked. It runs when the device is
    locked and no sheet is up (a sheet over the lock screen keeps its own turns), so it also
    covers the screen-off MainActivity path. The ranker's batches go under the evaluator's match
    mutex, launched before any input exists.
- **Behaviour**
  - Start: ASSIST and TILE start listening (`GarVoiceLog.listen(ASSIST)`, with a 100 ms
    duplicate backoff). HOTWORD doesn't start a second capture. `GarBargeIn.start` is the one
    barge-in. On warm shows the singletons are fetched in onShow, so listening starts at once.
  - Mic: Speaking stops speech only. Unavailable hands off to MainActivity. Listening in a
    continued window calls `stopBriefListening`. Listening, Starting and Transcribing use Dicio's
    onClick. Idle and Thinking start a new capture with `GarVoiceLog.listen(MIC_BUTTON)`.
  - Dismiss: swipe down (anchoredDraggable, 40% positional threshold, 125 dp/s fling,
    ModalBottomSheet's nested-scroll pattern), scrim tap, back, the handle, "Open GAR", a skill
    leaving GAR, screen off (only after the screen was on during the show), and the idle timer.
  - Teardown, for every way out including system hides: stop speech, capture and the continued
    window; unregister the receivers and the back callback; restore the permission requester
    unless the home screen replaced it; clear `GarSheet.isShown` (only if the shown sheet is this
    show's, so an overlapping newer session isn't clobbered); close the jank tracker and the
    model handle; clear KEEP_SCREEN_ON.
  - Idle (`GarSheetState.idleDecision`, tested): busy means listening, starting, transcribing,
    thinking, speaking, or an open continued window. When not busy, the sheet goes after 8 s with
    an answer, 6 s without one, or 15 s when speech input is unavailable, counted from the moment
    it stopped being busy or was last touched. Each base time goes through
    `AccessibilityManager.getRecommendedTimeoutMillis` (API 29+). Touches reset the timer, and a
    focused text field stops it. With touch exploration on (TalkBack) there is no auto-dismiss:
    swipes and focus moves send the sheet no touches, and hearing an answer and finding a chip
    takes longer than 8 s. The controller follows `addTouchExplorationStateChangeListener` while
    shown, and an accessibility delegate on the ComposeView resets the timer on accessibility
    focus and hover-enter events (it still counts for the lock screen's keep-screen-on window). The moment busy ends is taken in the same composition: the
    first build took it one frame later, so a turn that thought for longer than the timeout was
    dismissed as soon as its answer came (found on the emulator pass). KEEP_SCREEN_ON is set only
    while busy, or over the lock screen within `GarKeepScreenOn.LOCKED_WINDOW_MILLIS` of the last
    busy moment, and only with the setting on.
  - Touch: while GAR speaks, a tap on the sheet only stops speech (`Modifier.garTapStopsSpeech`).
    Starter chips are disabled until the first pointer-up after show, or 300 ms after show if no
    finger was down.
  - Continued listening counts the sheet as on screen (`GarContinuedListening.onScreen`).
- **Leaving GAR**
  - `SkillContext.android` and the sheet's `LocalContext` are `GarLeavingContext`s. While the
    sheet is shown, a start first dismisses it; while locked, it goes through
    `GarUnlockActivity`.
  - Safety net: when the latest answer's skill is in `GarSheet.LEAVES_GAR` (navigation, open,
    camera, website, song, places, telephone, email, sms, calendar, alarm, notes, media_play,
    messages), the sheet goes if its window loses focus (checked 500 ms after the answer, and on
    any focus loss within 10 s). `alarm` stands in for the spec's "clock open" (no `clock`
    skill id); `messages` and `media_play` were added because `NotificationMessageSource` uses
    the application context and `GarMediaPlay` starts music apps.
  - `ScreenshotSkill` dismisses the sheet before capturing.
  - The song skill's result launcher gets a registry that cancels at once and opens GAR.
- **Permissions.** While shown, the evaluator's requester is the sheet's. Granted permissions
  pass. Secure ones are refused, as on the home screen. Otherwise `GarPermissionHandOff.request`
  opens MainActivity with `EXTRA_PERMISSIONS` (through the unlock path when locked), dismisses
  the sheet and waits up to 60 s. The home screen calls `complete(granted)`, and the turn goes on
  to land in the home log. Cancelling the unlock completes the request with false.
- **Direct starts that bypass the wrapper** (static grep):
  - `AndroidSmsSender`, `AndroidMailer` and `NotificationMessageSource` take
    `context.applicationContext`, which unwraps `GarLeavingContext`. sms, email and messages are
    in `LEAVES_GAR`, so the focus safety net covers them.
  - PendingIntents (`CalendarReminderReceiver`, `StopwatchNotification`, `SongQueueWorker`) come
    from notifications, not while the sheet is up.
- **Checked on gartest (2026-11-02, API 35, debug build, GAR made the assistant with
  `cmd role add-role-holder`, shown with KEYCODE_ASSIST; screenshots in ~/.cache/gar/ui/).** The
  emulator ran starved (qemu at background priority, guest load 10 to 45), so every timing below
  is slow and only says the paths work.
  - The sheet shows over the launcher, not MainActivity; starter chips, Listening, Thinking with
    its timer, Speaking ("Speaking, tap to stop") and the answer card render in light and dark.
  - The keyboard button opens the in-sheet field with the IME under the sheet; a typed command is
    answered in the sheet.
  - Back (a key on API 35) dismisses and runs the teardown. "Open the camera" starts the camera
    and the sheet goes first. With nothing said the sheet goes by itself after the empty timeout.
  - A tap on the sheet while GAR spoke stopped the speech within about a second and did not open
    the request for editing (the tap was consumed).
  - Chips: "set a timer for 20 minutes" offered Cancel timer and Show timers in this build (now
    Show timers only, see "Home and chips"); "set an alarm for 7 am" offers Show alarms and Cancel
    it, and Cancel it sent "cancel it", which dismissed that alarm through the clock app (the
    chip now sends "cancel my 7 am alarm", see "Home and chips").
  - Found and fixed on this pass: the grey band of the translucent bars, the singleton fetch that
    never ran while the shell's spinner kept the main thread busy, the idle timer that dismissed a
    long turn as soon as its answer came, and timers and alarms set with `EXTRA_SKIP_UI`
    dismissing the sheet (they show nothing, so `GarLeavingContext` lets them pass, also over the
    lock screen, and `alarm` left `LEAVES_GAR`).
  - `jank.py sheet` (5 warm shows, emulator numbers, software rendering): first frame p50
    3554 ms, p90 5594 ms, max 5594 ms (4 of 5 shows logged within its 10 s window). Cold shows
    seen during the pass: 11.9 s, 14.4 s, 15.9 s; the first cold show then blocked the main
    thread 5.2 s while the singletons were built (Choreographer "Skipped 311 frames").
  - Not checked: the lock screen and the unlock trampoline, a wake word showing the sheet, the
    permission hand-off, the predictive back animation (API 35 here sends back as a key), the
    swipe-down dismiss, TalkBack, and `dumpsys meminfo` with and without prewarm.

## Keeping the screen on

"Keep the screen on while GAR is open" (GAR settings, Screen section, on by default) keeps the
phone awake while a GAR screen is in the foreground, so it doesn't fall asleep while the user
reads an answer or waits for one. `GarKeepScreenOn.follow(activity)` runs from Dicio's
`BaseActivity.onCreate` (one GAR-marked line), so it covers `MainActivity`, the speech popup
(`SttPopupActivity`) and `ErrorActivity`. GAR has no window of its own outside these activities.

- **How:** the window's `FLAG_KEEP_SCREEN_ON`, never a wake lock. It is set while the activity is
  at least STARTED (visible), the setting is on and either the keyguard is not locked or the user
  is talking to GAR (below), and cleared otherwise. The pure
  `shouldKeepScreenOn(state, settingOn, keyguardLocked, interacting)` decides; it is checked again
  whenever `Lifecycle.currentStateFlow`, the GarSettings DataStore, the interaction state or the
  lock state changes (unlocks through `ACTION_USER_PRESENT`, a receiver registered for the
  activity's lifetime). In the background the flag is cleared and the phone sleeps after its
  usual timeout.
- **Lock screen:** the wake word turns the screen on over the keyguard, and the user then speaks
  and waits for the answer hands-free, so the flag is on there while they talk to GAR: while
  `SttInputDeviceWrapper.uiState` is loading, listening, transcribing or waiting for an external
  recognizer, or `SkillEvaluator.state` has a pending question (`GarKeepScreenOn.busy`), and for
  30 s (`LOCKED_WINDOW_MILLIS`) after that or after the last answer arrived. Then it is cleared,
  so a false trigger in a pocket ends with the usual timeout once listening times out. Opening
  GAR over the lock screen without speaking starts no window. Unlocking with GAR open turns the
  flag on for good.
- **Setting:** stored inverted, `bool screen_may_sleep = 40` in `gar_settings.proto`, so the
  switch reads as on for settings files written before it existed, not only for new installs.
- Tests: `GarKeepScreenOnTest` (default on, off survives a write and read, the decision over every
  `Lifecycle.State` with and without the lock and an interaction, which states count as talking
  to GAR). The 30 s window itself is not unit-tested. Not checked on the phone.

## Home and chips

The home screen and the assistant sheet share one picture of what the microphone is doing, the
chips and the cards (spec: gar-notes/ui-makeover-design.md, sections 2 to 4; code in `gar/ui/`).

- **Mic state** (`GarMicState`, pure `garMicState()`; all times are SystemClock.elapsedRealtime):
  - Precedence: Listening > Starting > Transcribing > Thinking > Speaking > Idle/Unavailable.
  - Listening carries the end of a continued-listening window. `garBriefEndsAtElapsed` converts
    the wrapper's System.nanoTime clock.
  - Starting is `Loading(thenStartListening)`. Transcribing also covers WaitingForResult.
  - Unavailable covers every state the mic can't listen in: off (null), no permission, not
    downloaded, downloading, errors, NotInitialized. Like SttFab, `rememberGarMicState` treats a
    missing RECORD_AUDIO permission as NoMicrophonePermission, so the home never offers a mic tap
    that can't listen.
  - Thinking: a pending question at least 300 ms old (`GarThinking.SHOW_AFTER_MILLIS`, so quick
    answers show none), or a model route at once. `GarThinking.routing` is set around
    `routerSource.route` in GarFallbackImpl.tryRoute. `loadingModel` only counts during a route
    (GarModelManager.modelState is Loading). `skill` is the pending question's
    `skillBeingEvaluated`.
  - Speaking: `GarSpeaking.speaking`, true while any GarUtteranceTracker has queued utterances.
    Trackers are held weakly, and AndroidTtsSpeechDevice.cleanup clears its tracker (one GAR
    line, `utterances.stopped()`), so a replaced TTS can't leave it stuck on.
  - `GarMicState.tag` names the state for `GarJank.state(window, "mic", tag)`. The home screen
    calls it once per change.
- **Labels and accessibility** (`GarStateLabel`):
  - Thinking text: "Thinking", then "Thinking… N s" from 1.5 s. With a model loading, "Loading
    the model… N s". Once a skill is chosen, the skill's name with the same timer.
  - The label is a polite live region only for Thinking past 1.5 s and for Unavailable, and never
    while Listening or Starting: TalkBack speaking during a capture would be transcribed. Idle,
    Speaking and Transcribing are not announced, to avoid noise after every turn. The spoken
    text counts in steps of 5 s ("Still thinking", then "Thinking, 10 seconds").
    GarThinkingCard's own label never announces; the pill or the state row does.
  - The mic's content description follows the state: Start listening, Stop listening, Cancel
    transcription, Stop speaking, or the Unavailable text.
  - The display-only mic (the sheet's state row) is hidden from accessibility, since the label
    next to it says the state.
- **Mic drawing** (`GarMicIndicator`): everything moving is read in the draw phase, so nothing
  recomposes while the user talks.
  - Listening: 4 bars (weights 0.6, 1, 0.8, 0.5) follow Whisper's level with a small sine
    wobble; in a continued-listening window an arc empties around them.
  - Starting: bars at rest plus a spinner. Transcribing: a spinner. Thinking: three dots pulsing
    over 1.2 s. Speaking: a stop square. Idle: the mic.
  - Reduced motion (`rememberGarReducedMotion`: animator duration scale 0 or animators off,
    followed live) turns off the wobble, the dot pulse and the moving progress bar.
- **Barge-in**: `GarBargeIn.start(stt, speech)` is the one process-wide collector
  (Main.immediate; its logic is `GarBargeInCollector`, tested). On each transition into
  Listening it calls `GarTurnElement.markCaptureStart(isSpeaking)`, then `stopSpeaking()`. The
  home view model and the sheet both call it, and only the first call starts it, so the two can
  no longer overwrite each other's mark.
- **Tap stops speech** (`Modifier.garTapStopsSpeech`, on the home list and the sheet):
  - On the Initial pass, a down while GAR speaks stops the speech, and that down and its up are
    consumed, so the tap reaches no chip, bubble or button.
  - Drags past touch slop are left alone (scroll and swipe still work), and nothing is consumed
    while GAR is silent.
  - The home mic while Speaking only stops the speech.
- **Chips under an answer**: shown only under the latest answer while nothing is pending, and
  disabled after a tap.
  - `GarChips.forOutput` uses the output's own `GarSuggestions`, or GAR's table for Dicio's
    outputs, at most 4 (`MAX_CHIPS`). Dicio's outputs are TimerOutput.Set and
    WeatherOutput.Success, plus MediaOutput after Play, Next or Previous (`performedAction` made
    public for this, GAR-marked). Dicio's MediaOutput after a Pause gets no Resume chip: the spec
    lists Resume only for GarMediaOutput.
  - GAR outputs that implement it: AlarmOutput.Set (Show alarms, and Cancel it, which sends
    "cancel my 7 am alarm" with `ClockFollowUps.spokenTime`: a bare "cancel it" reaches the alarm
    only as a follow-up within 60 s, while the chip stays under the answer), AlarmOutput.Show
    (Set an alarm), WeatherAnswerOutput (Tomorrow and This weekend, minus the day the answer was
    about; none for NoForecast).
  - GarMediaOutput: Pause and Next after "Playing ...", Resume after "Paused"; none for cards
    with their own button.
  - CallLogOutput: "Call back" sends "call <name> back", only for one named, not hidden caller
    (LastCaller, From, or Missed from one caller), so CallBack can't pick a newer call.
  - MessagesReadOutput: for one non-group sender, Reply (only if the app takes replies) and
    "Call" sends "call <name>".
  - Outputs with their own buttons get none.
  - The per-output choice goes through `GarChipTable` builders and `GarChipStrings`, so it is
    tested with fake strings (`GarChipsTest`).
  - Chips are sent like a card button: voice-log start "button", typed input.
  - The inputs are English string resources. Chips appear only when `gar_chips_language` equals
    the sentences' language, so a translation must translate the inputs and set the language.
  - `GarChipRow` ignores taps for 300 ms after its chips first appear or change, and while
    `enabled` is false.
  - When chips appear, the home list scrolls just enough to show them, unless the user has
    scrolled above them.
- **Starter chips** (`GarStarterChips`): shown on the empty home above the mic and on the sheet
  before the first turn; none over the lock screen.
  - They are learned from the voice log by an **allowlist**, not a blocklist. A turn counts only
    if all of these hold:
    - answered and not suspect;
    - 2 to 6 words;
    - matched by a skill's sentences (`match.path` == "sentence": no model route, no follow-up
      answer, no `follow_up` record);
    - not started by a card button (otherwise a chip would teach itself);
    - answered by weather, current_time, world_time, date, battery, calculator, units or timer
      (timer only with a duration word; the spec's `unit_conversion` is `units` in code);
    - within the last 30 days.
  - Why an allowlist: a starter is one tap away from running again, so only read-only or harmless
    skills may be offered, and nothing that carries names, queries or app titles.
  - One chip per skill. A skill's turns are grouped by a key (lowercase, no punctuation, no
    apostrophes, so STT's "whats" meets "what's"). A group that is another cut off (its words
    start the other's, the last one possibly cut: "what time is", "what time is i") is not
    offered and counts for the longer one. A skill's chip that is a cut-off of another skill's
    chip or of a default is dropped: gartest's log had "whats my batter" answered by the
    calculator twice. The skill's chip is
    the group asked most (then the longest, then the newest), if at least 2 times; skills rank by
    that count, then by the newest. A chip sends the newest raw input, never the key, except
    when its key is a default's or starts it: then the default's wording.
  - The defaults carry their skill ids (weather, timer, current_time, battery) and fill only for
    skills no learned chip is from.
  - Before showing, each candidate is scored again against its skill's sentences, off the main
    thread, with its own MatchHelper so the evaluator's isn't touched. It must beat the ranker's
    last-round threshold for its specificity (0.7 high, 0.8 medium, 0.9 low); otherwise it is
    dropped.
  - The rest are filled from the defaults ("What's the weather", "Set a timer for 5 minutes",
    "What time is it", "What's my battery"). Flashlight was dropped because it acts.
  - They never read the whole log: it is up to 10 MB, and parsing it when GAR opens (which is
    when the user talks) cost 3-4 CPU-s and 11-14 s of wall time on gartest and slowed the
    first commands (see "Routing speed"). Instead the voice log's thread keeps
    `starters.jsonl` (`VoiceStarterIndex`): as each turn is written,
    `GarStarterChips.indexLine` keeps it, as a line of about 150 bytes with only the fields
    `parse` reads, if it is `allowed` when asked; the 1000 newest lines stay. A phone updated
    from before the index builds it once from the log, 60 s after the start, on the log's
    lowest-priority thread; until then the defaults show.
  - The index is read with kotlinx.serialization, the same library that writes the log (the
    spec said org.json, which is a stub in JVM unit tests), so `parse` is tested against
    VoiceRecord.toJson. Lines cut short are skipped.
  - All of it, the re-check included, runs on the starters' own thread at the lowest priority
    (`GarStarterChips.dispatcher`), not on Dispatchers.IO.
  - Voice log off or unreadable: the defaults. Results are cached for 6 hours, or until the
    index changes (its size and time) or the setting does.
- **Home layout**:
  - 16 dp gutter: one GAR edit to MessageCard's horizontal padding (8 to 16 dp), which covers the
    first-run cards, WakeWordWidget and WhatICanDo (those files are untouched).
  - The first-run cards share one item, 12 dp apart; WhatICanDo is its own item.
  - 8 dp from question to answer, 24 dp between interactions.
  - The user's bubble is right-aligned, at most 80% wide, primaryContainer, corners 20/20/4/20,
    at least 48 dp tall, and a tap edits it. Pending text is italic at 0.7 alpha.
  - The answer is a GarAnswerCard: surfaceContainerLow, 16 dp padding and corners, with the
    skill's icon (20 dp, primary) and name on top, and no header for error-only QAs.
  - GarThinkingCard replaces LoadingAnswerCard and the 72 dp spacer.
  - The mic moved from Dicio's floating button into the Scaffold's bottom bar, so the list no
    longer runs under it (its bottom spacer shrinks from 84 dp to 16 dp): the state pill sits over
    the 72 dp mic, with a 48 dp keyboard button that opens the text field. In the Unavailable
    states, Dicio's SttFab takes the mic's place (it owns the permission launcher and the
    download states).
- **Hand-offs from the sheet** (`GarAppHandOffs`, read once per intent, the launch intent and
  each new one). It is composed by `Navigation`, not by the home screen, so a hand-off is
  answered whatever destination MainActivity was left on (settings, About); the home-only
  listener lost it, and the turn waited 60 s:
  - `cc.aaro.gar.extra.TYPE` pops the back stack to Home and opens the text field (detected with
    `hasExtra`, whatever its value type; a saveable flag that HomeScreen clears once handled).
  - `cc.aaro.gar.extra.PERMISSIONS` asks for those permissions with a second launcher and calls
    `GarPermissionHandOff.complete(granted)`. Dicio's launcher feeds a rendezvous channel that
    only the evaluator's permission requester receives from, so it can't be reused.
- **Checked on gartest (2026-11-02):** the home screen in light and dark (bubbles, answer
  cards, the state pill over the 72 dp mic, starter chips learned from the emulator's voice log:
  "What time is it", "What time is", "Whats my battery"). That build showed near-duplicates and
  STT spellings; one chip per skill, the prefix rule and the default's wording came after (see
  above). A tap on a starter chip sends its raw input.
  - GAR's timer skill answers with `TimerAnswerOutput`, not Dicio's `TimerOutput.Set`, so the
    first build offered no timer chips; `GarChips` now maps `TimerAnswer.Started` to Show timers
    only, because the clock app's API can't cancel a timer (the Cancel timer chip answered "I
    can't change Clock's timers by voice"). `GarChipsTest` covers it.
  - Not checked: weather, media, call log and messages chips (no network, media session, calls
    or SMS on gartest), the starter re-match against a changed sentence, and reduced motion.

## Speech recognition (Whisper)

Whisper replaced Vosk as the default speech input in English (design brief:
`gar-notes/whisper-design.md`). whisper.cpp runs FUTO's ACFT English models (fine-tuned so a
short `audio_ctx` works on short clips), end-pointed by Silero VAD. Vosk stays selectable and is
what other languages get. No partial text: a level meter while listening, then "Transcribing…".

### Data flow

```
tap / wake word / assist / SttService
  -> GarWhisperInputDevice.tryLoad(listener) or onClick: state Listening, SttModels.acquire()
       (Whisper loads in the background while the user talks; the mic opens at once)
  -> capture thread "GarSttCapture": PcmSource (AudioRecord float 16 kHz mono, PCM16 fallback;
       debug builds: files/gar-stt-test/next.wav) in 512-sample frames
       -> SileroVad.probability(frame) -> Endpointer.accept(p)        [:gar:stt, pure Kotlin]
       -> level (RMS, -60..-10 dBFS -> 0..1, smoothed) -> voiceActivity -> ring / popup bar
  -> end of speech, 28 s cap or a tap (finishListening): mic released, state Transcribing
  -> SttModels.use { WhisperEngine.transcribe(clip, n, AudioCtx.forSamples(n)) }
       greedy, no timestamps, one segment, max 64 tokens, on the "gar-whisper" thread
  -> SpokenForm.of(text) -> spoken ("set a timer for five minutes"), written ("Set a timer for 5 minutes.")
  -> TranscriptFilter: spoken empty, or a silence phrase ("thank you", "you", "bye", ...) with
       no_speech > 0.6 and under 600 ms of speech -> None
  -> state Ready, then InputEvent.Final(listOf(spoken to confidence), written)
  -> the usual path ("Data flow" above): Dicio's matcher and GAR's router see the spoken form
```

- **Spoken form.** Whisper writes digits, symbols and punctuation; Dicio's sentences, GAR's
  resolvers, guards and the eval cases all expect Vosk's lowercase words. `SpokenForm`
  (`:gar:router`, English) bridges that: it drops markers (`[BLANK_AUDIO]`, `(music)`, `*sighs*`,
  `♪`), reads symbols (`50%`, `$4.50`, `8 x 7`, `72°F`), abbreviations (a.m., OK, Dr.), times
  (`7:30`, `7.30 pm`), ordinals, decimals, years and phone-like numbers (digit by digit, also 911
  and a number after "call" or "dial", which `spokenPhoneNumber` reads) in words, then runs
  `Utterances.normalize`. Typed input and buttons skip it. The user bubble shows the spoken form,
  what GAR matched. Other apps (`SttService`, the STT popup) get the written text.
  `ExternalPopupInputDevice` runs other apps' English results through it too
  (`garExternalFinal`; alternatives left without words are dropped, and the written text is
  the first kept one's). Free-text slots (an SMS body, a reminder) still take the spoken words;
  taking Whisper's written span is later work.
- **End-pointing** (`Endpointer`, 32 ms frames): speech starts when 3 of the last 4 frames reach
  0.5, and ends after 800 ms below 0.35. Under 250 ms of speech is forgotten. No speech within
  Dicio's `stt_silence_duration` x 3 s (`NO_SPEECH_MILLIS_PER_STEP`, 6 s by default) sends None,
  counted from when the mic opens, as the earcon plays. It was x 2 s (4 s) until 2026-11-02:
  Google Assistant waits about 5 to 8 s, and two starts that day ended `no_speech`. The clip runs from 300 ms before the speech
  start to 200 ms after the last speech frame, so the earcon is cut and `audio_ctx` stays small.
- **audio_ctx**: `min(1500, max(128, round(samples / 320) + 8))`, FUTO's formula with a floor of
  128 (2.56 s). Only ACFT files are offered: a stock model with a short context writes garbage.
- **Native departures from the brief**, found by the host test at whisper.cpp `21ba6d26`:
  flash attention is off (its decoder read stale padded cross K/V when `audio_ctx` isn't a
  multiple of 256, so results depended on earlier calls); the no-speech probability is computed
  by `gar_whisper.cpp` with one decoder step on SOT (whisper.cpp's own value reads stale logits
  with `no_timestamps`) and `no_speech_thold` is 1, so whisper.cpp never drops a segment itself;
  a cancel takes effect only after the encoder (it checks the abort flag after the encoder and
  between decoder steps; the caller is released at once); clips under 100 ms return "" without
  calling whisper_full (which would reuse the previous mel).
- **Contact names** (whisper-design.md sections 14 and 16b; "aaron" came out as "erin"): the
  decoder gets an initial prompt of names, `NamePrompt` (`:gar:stt`): " Aaron, Mom, Sarah Lee."
  (a leading space, as OpenAI's `initial_prompt`, so a name is the same token as in a command),
  at most 16 tokens (`NamePrompt.MAX_TOKENS`), since each costs a pass of the output layer: on the
  Pixel a 40-token prompt added about 100 ms to a 216 ms command, a 2-token one none.
  `GarSttPromptNames` (`:app`) picks them only with `READ_CONTACTS` and the setting on: the
  owner's first name, always first, then the first names of starred contacts and the last 10
  callers (only if `READ_CALL_LOG` is granted already; GAR doesn't declare `READ_SMS`, so no
  SMS), then their full names when short (3 words, 24 characters). No other contacts: they would
  be whoever sorts first (`SORT_KEY_PRIMARY` order, not a usage signal; `LAST_TIME_CONTACTED` is
  zeroed since Android 10), and on the Pixel 13 of them filling a 40-token prompt without the
  owner turned a clear "Call Aaron." (0.53 without a prompt) into "Call Youran" (0.42); the
  prompt "Aaron" alone gave "Call Aaron." at 0.77.
- **The owner's name** (`ContactPromptNames.owner`) comes from the first of three sources that
  gives one (`OwnerSource`): GAR's own contact (`own_contact_name`, the one "text myself" uses),
  the typed name (`stt_own_name`), then the phone's profile (`ContactsContract.Profile`, often
  empty, as on the Pixel). The typed name is before the profile since the user chose it. A
  placeholder the user saved their own number as ("Me", "My number", "Home" and a few more)
  gives none, so the next source counts and it takes no prompt slot.
- **"You"**, the first row of GAR's settings page, is the one place that sets who the user is
  (`GarYou`, `GarYouSetting`); the page's entry in Dicio's settings says so ("You, speech
  recognition and the language model"). Its summary says who GAR thinks they are and where that
  came from, in the hint's order (`GarYou.who`, `GarYou.summary`): "Alex Herbert, 435-555-0100"
  (the contact and its first number), "Me (Alex), 435-555-0100" (a contact that names nobody,
  and the typed name), "Alex (name only)", "Alex Herbert (your card in Contacts)" or "Not set".
  A contact GAR can't find says "(not in Contacts any more)", and without `READ_CONTACTS`
  "(allow contacts access)". When "text myself" really goes to the profile (the contact has no
  number, or only a name was typed, and the profile has one, as `OwnContact.cards` picks), the
  summary adds "Texts go to your card in Contacts", so it never names one person while texts go
  to another. A tap opens a dialog with a search field over the contacts (name, first number
  below, else first address, else "No number or email"; the current one marked), "Use just a
  name" with a text field and "Pick a contact" back, and Clear (only for GAR's own contact or a
  typed name: after it the profile would still show, or there is nothing to clear). For the
  profile it says "From your card in Contacts. Pick a contact or a name to change it." Without
  `READ_CONTACTS` it shows the name field and a button that asks for it; once Android stops
  asking (two "Don't allow"s), the button opens App info, and the dialog checks again on resume.
  Every change goes through `GarYou.set`, which keeps both fields in step: a contact that names
  someone sets `own_contact_name` and `stt_own_name` to its first name
  (`ContactPromptNames.ownerFirstName`); a contact like "Me" keeps the typed name and leaves
  `stt_own_name_asked` alone, and the dialog then asks "What's your first name?". A typed name
  clears a contact that names someone and keeps "Me", so "text myself" and the hint both work.
  Clear empties both and `stt_own_name_asked`. The home screen's card and "Which contact is
  you?" (`AndroidOwnContact.remember`) call it too. Over the lock screen (a wake word opens GAR
  there) the summary has no number and the dialog doesn't open or closes
  (`GarSettingsViewModel.locked`, `GarYou.canOpen`), as for the home screen's card. The two rows
  it replaced ("Your contact" under Language model, "Your name" under speech) meant clearing two
  settings in two sections, then going back to the home screen to be asked again.
- When no source gives a name, Whisper is in use and the contacts were read, the
  home screen asks once "What's your first name?" (`GarSttOwnNameCard`, typed since speech is
  what gets it wrong); saving or skipping sets `stt_own_name_asked`. A saved name that is a
  contact's ("Alex" for Alex Herbert) offers that contact as GAR's own contact. The card shows
  nothing over the lock screen (`KeyguardManager.isDeviceLocked`, read again at every unlock and
  on resume), where anyone could see contact names or change who "text myself" texts.
- **Building the prompt:** the names are read at the first capture, again when contacts change
  (a `ContentObserver`, 5 s debounce), the setting or the owner changes, a permission changes,
  or after 10 minutes. `WhisperEngine` fits and
  tokenizes a list once (a new model is a new engine), on its own thread while the user talks:
  the input device calls `Transcriber.prepare` with the names during capture, so the first
  command after start or a contacts change doesn't wait for it. `fit` tokenizes each name once
  (" Sarah Lee") and adds a token per comma and the full stop, stopping when no name fits; the
  whole prompt is then tokenized and cut from the end if a name ending in punctuation made the
  sum short. `Transcript.promptNames` says which went in. Prompt words can leak into unclear
  audio: the no-speech probability is computed without the prompt, and `TranscriptFilter` drops
  a transcript of prompt names only when its confidence is under 0.5, no_speech is over 0.6, or
  it is a list: not exactly one prompt name and more than 3 words, a word repeated, or a
  confidence under 0.8 (so "sarah lee" said clearly passes when only "Sarah" and "Lee" fitted).
- **Eval** (`gar/stt/eval/`, README there; whisper-design.md 16b): 85 `say` clips in 5 voices
  (43 with names, half with noise and a level drop, cut like the Endpointer) through
  `gar_whisper_eval`, whisper.cpp at the pin with `gar_whisper.cpp`'s exact parameters, the app's
  `audio_ctx`, and the prompt the app would send (`ContactPromptNames.names` and `NamePrompt.fit`
  to 16 tokens, the owner "Aaron" always first); scored on GAR's own `SpokenForm` on the JVM.
  Modes: no prompt; the clip's 15 contacts with the name said (the best case); the same without
  it; "phone", a 300-contact book as the device reads it (owner, 2 starred, 10 recent callers
  with the name said among them for half the clips, nobody else), where the name said was in the
  prompt for 28 of 43 clips; and "others-40", the old prompt that failed on the Pixel (no owner,
  the clip's other contacts, 40 tokens). 2026-11-02, Mac M1, 4 threads:

  | model | prompt | WER | WER, clips with names | WER, the rest | names right | exact clips | ms median |
  |---|---|---|---|---|---|---|---|
  | base.en | none | 12.8 | 22.9 | 4.1 | 21/43 | 53/85 | 27 |
  | base.en | names | 10.4 | 18.8 | 3.1 | 29/43 | 60/85 | 33 |
  | base.en | names, not the one said | 11.7 | 21.8 | 3.1 | 24/43 | 55/85 | 33 |
  | base.en | phone book | 10.1 | 17.1 | 4.1 | 29/43 | 60/85 | 33 |
  | base.en | others, 40 tokens | 14.2 | 24.7 | 5.1 | 21/43 | 51/85 | 41 |
  | tiny.en | none | 15.3 | 24.1 | 7.6 | 20/43 | 47/85 | 17 |
  | tiny.en | names | 12.0 | 17.6 | 7.1 | 31/43 | 57/85 | 22 |
  | tiny.en | names, not the one said | 16.1 | 26.5 | 7.1 | 18/43 | 46/85 | 21 |
  | tiny.en | phone book | 11.7 | 17.6 | 6.6 | 28/43 | 57/85 | 21 |
  | tiny.en | others, 40 tokens | 15.8 | 25.3 | 7.6 | 19/43 | 45/85 | 27 |

  The owner-first 16-token prompt beats none on base.en even when the name said is never in it
  (break-even below 0: the owner "Aaron" alone fixes clips); tiny.en needs it in the prompt for
  a fifth of named commands (0.20 on WER, 0.09 on exact clips). The old prompt is worse than
  none on both models and costs 6 to 8 ms more (median) than the 16-token one on the Mac. It doesn't
  reproduce "Call Youran" on these `say` voices (base.en gets the bench clip right in every
  mode), but it turns "remind me to call aaron" into "erin", "call ian" into "colleen" and "send
  a text to erin" into "aaron"; the owner-first prompts get all three right. Swept over 200
  random 40-token prompts of the 52 other contacts, base.en gets the bench clip wrong 37 times
  ("Call Erin." 32, 33 of them with Erin in the prompt; "Colin, Colin," 5); 200 owner-first
  16-token prompts of the same contacts get it right 197 times, the 3 misses a "Colin, Colin,"
  loop from prompts ending in " Dr." (the first name of "Dr. Patel"). On the Pixel (base.en, the
  bench's `call.wav`, `SttBenchReceiver`, 2026-11-02):

  | prompt | text | confidence | ms median (Pixel) |
  |---|---|---|---|
  | none | "Call Aaron." | 0.53 | 216 |
  | 40 tokens of contacts, no owner | "Call Youran" | 0.42 | 311 |
  | " Aaron." | "Call Aaron." | 0.77 | 133 |
  | " Aaron, Mom, Dad." | "Call Aaron." | 0.76 | 177 |

  **Decision**: base.en stays the default and the prompt is on by default
  (`GarSttPromptNames.ON_BY_DEFAULT`). base.en is better without a prompt and on commands
  without names (3.1 to 5.1 against 6.6 to 7.6 for tiny.en); tiny.en's name gains are on
  `say` voices, and it gets both TTS "call aaron" clips wrong in every mode ("galerin",
  "collieran"); neither is a recording from the phone. On base.en the prompt fixes "send a
  message to aaron" (was "rm"), "mom" (was "mum") when Mom is in it, "dad", "aaron diaz" and
  "linh nguyen"; "call erin" is "call aaron" in every mode (also without a prompt).
  `SpokenForm` reads "alarm for 8.15" as a time, so the alarm clips are clean in every base.en
  mode. The prompt format, the 16-token cap and this decision were all tuned on these clips,
  with no held-out set: " {names}." against " Contacts: {names}." was a tie (29 and 29 of 43 on
  base.en, before the owner was added). Next: the Pixel bench with the owner-first prompt
  over more clips (`SttBenchReceiver`, `--ez bias false` against the default), first names of
  "Mary Jane", "Uncle Steve" and "Dr. Patel" (`ContactPromptNames` sends "Mary", "Uncle", "Dr"
  first; a prompt ending in " Dr." loops), and
  real recordings of Aaron's voice (`wav:`) as a held-out set never used for choosing.
- **Two ggml copies**: `libgar_whisper.so` links its own static ggml with hidden visibility and
  `--exclude-libs,ALL`, as `libgar_llama.so` does, so the two never interpose (1.7 MB, arm64-v8a
  only).

### Which device runs

`GarSttDeviceResolver` maps Dicio's Input method setting: VOSK, WHISPER, external popup and
nothing stay as chosen. The default (UNSET) gives Vosk for a non-English locale, else Whisper
once its files are installed, else Vosk if Vosk's model is already unzipped (phones that used it
before), else Whisper (new installs never download Vosk). The input device wrapper rebuilds the
device when the result changes (locale, Whisper files), and Settings > Input method shows the
resolved device for the default.

The device's states (`WhisperState`, shown as Dicio's `SttState` plus `Transcribing`):
NotDownloaded (a tap downloads model and VAD), Downloading, ErrorDownloading (a tap retries),
Ready, ErrorLoading (a tap retries the load and starts listening), Listening (a tap finishes and
transcribes; `stopListening` discards), Transcribing (a tap or `stopListening` cancels). The
state is always set before the listener runs, since the evaluator may reopen the mic. A failed
model load gives ErrorLoading plus an Error event; a Whisper failure, a mic failure or a silenced
capture gives Ready plus an Error ("Speech recognition failed.", "I couldn't use the
microphone.").

### Models, lifecycle and settings

`SttModelCatalog` pins three files, kept in `filesDir/gar-stt/` and fetched with
`GarModelDownloader` (resume, size and sha256 check):

| File | Source | Bytes | sha256 |
|---|---|---|---|
| `ggml-base.en-acft-q8_0.bin` (default) | `dl.voiceinput.futo.org/VoiceInput/base_en_acft_q8_0.bin` | 81,781,811 | `e9b4b7b8…f1c8` |
| `ggml-tiny.en-acft-q8_0.bin` | `dl.voiceinput.futo.org/VoiceInput/tiny_en_acft_q8_0.bin` | 43,550,795 | `4b5480aa…fbfc` |
| `ggml-silero-v6.2.0.bin` | `huggingface.co/ggml-org/whisper-vad` @ `9ffd54a1…` | 885,098 | `2aa269b7…6987` |

FUTO's server has no revision in its paths; size and sha256 make a replaced file fail as
damaged. Before the Obtainium release the files move to GAR's own GitHub release assets.

`GarSttModelManager` (`@Singleton`, implements `SttModels`) holds Whisper in a
`GarEngineHolder` with a `GarLoadGuard` (`filesDir/gar-stt-loading`), like the language model:

| Moment | Whisper |
|---|---|
| MainActivity started | acquired; loaded if Whisper is (or will be) the input device |
| Capture start (tap, wake word, assist, SttService) | acquired; loads while the user talks |
| Final, None or Error sent | released |
| Last release | unloaded after 30 s, or 6 s when the process may be frozen |
| `TRIM_MEMORY_BACKGROUND`, `onLowMemory`, `reinitializeToReleaseResources` | unloaded unless held |
| Model switched | the new file is downloaded and verified first, then selected; the old one is unloaded and deleted |
| Model deleted | unloaded, all speech files removed |

The VAD is loaded by the device at its first capture (milliseconds) and freed in `destroy()`, so
capture never waits for Whisper. A load that fails is not repeated until a tap (the device's
ErrorLoading or the settings' status row); two loads that stopped the app block the file the
same way. `tryLoad(null)` (MainActivity created, permission granted) only preloads and never
forgets a failure, so re-creating the activity can't clear the crash guard. The device's
ErrorLoading lasts only while the model file and the retry it failed with do
(`SttModels.loadGeneration`): a switch, a new download or a retry in Settings brings back Ready.
Whisper and the language model both load when MainActivity starts; `GarNativeLoadLock` runs one
native load at a time (each `GarLoadGuard`'s begin to end, the LLM's warm-up included), speech
loads first, so a process death during a load leaves only the loading model's marker. The first
load also warms `SpokenForm` (dicio-numbers loads its English data once).

Settings: GAR's settings page (Settings > Language model) starts with "You" (above), then "Home
and work" ("Places, camera and screen"), then "Speech recognition": the model (base.en, tiny.en), status and progress, Download, Cancel, Delete, "Runs
on this phone; audio never leaves it.", and in debug builds "Keep recordings" (the last 50 clips
with their raw and spoken text in `filesDir/gar-stt-recordings/`, off by default).
`gar_settings.proto`: `stt_model = 10`, `stt_keep_recordings = 11`, `stt_offer_hidden = 12` (9
was already `own_contact_name`), `stt_contact_names = 13` (DEFAULT, ON, OFF: DEFAULT follows
`GarSttPromptNames.ON_BY_DEFAULT`, on since the eval above; the switch "Help recognise contact
names" sits under "Private"). The home screen offers the download once while Whisper is wanted
and no model is installed ("Better speech recognition", same style as the assistant card); the
mic button itself says "Download speech model (83 MB)" then.

Each transcription logs one line under the tag `GarStt`: clip and speech seconds, `audio_ctx`,
encode and total ms, tokens, no_speech, confidence, prompt tokens, end of speech to Final ms. Debug builds
register `SttBenchReceiver` (broadcast `cc.aaro.gar.STT_BENCH`, DUMP permission, so only adb):
every WAV in `files/gar-stt-test/bench/` through the loaded model, for choosing the model,
`MIN_AUDIO_CTX` and the hang on the phone. Its prompt has the contacts' names as the device's
would, or `--es names "Aaron, Erin, ..."`; `--ez bias false` runs without one.

Not yet measured on the Pixel: load time, memory, end of speech to text (aim: 400 ms median for
2 to 5 s commands with base.en), whether `MIN_AUDIO_CTX` 128 and the 800 ms hang are right for
real voices, and whether the LLM's warm-up slows a first command. Host numbers (M1, 4 threads,
base.en): encode 20 to 50 ms and 35 to 110 ms in total at `audio_ctx` 128.

## Voice log

GAR keeps a log of each voice command on the phone, so the developer can see from daily use at
which stage a command worked or failed. One JSON object per turn (one activation through the
reply) goes to `files/gar-voice-log/voice-log.jsonl`, which rotates at 2 MB into
`voice-log.1.jsonl` .. `voice-log.4.jsonl`, so 5 files are kept (`VoiceLogFiles`).

- **Setting:** "Voice log" in GAR's settings, Speech recognition section, one switch: "Keeps the
  text, timings and short audio clips of each command on this phone only, shared only when you
  tap Share". It keeps what the user says, so it is **off by default in release builds** and on
  by default in debug builds (`BuildConfig.DEBUG`, `GarVoiceLog.settingOn`).
  - Stored as two flags in `gar_settings.proto`: `bool voice_log_off = 41` (the user turned it
    off; it came first, when the log was on everywhere) and `bool voice_log_on = 42` (the user
    turned it on). With neither set (proto3 can't tell false from unset), the build decides. So
    a debug user who never touched the switch keeps the log, one who turned it off keeps it off,
    and a release user has no log until they turn it on. The switch sets both flags
    (`GarVoiceLog.withSetting`).
  - Off records nothing (no turn opens, so the Whisper device copies no clip) and deletes the
    kept clips (`wav/`) on the log's thread, also at a start with the log off (a release user
    of 1.0.0, where it was on). The text log stays until Clear.
- **Share voice log** zips the logs and WAVs into `cacheDir/gar-voice-log-share/`, only when
  tapped, and opens the share sheet through a FileProvider (`${applicationId}.garvoicelog`,
  cache-path `gar-voice-log-share/` only). Only the newest zip is kept.
- **Clear voice log** asks first, then deletes the folder, the WAVs, the shared zip and the turns
  held in memory, off the main thread (on a thread of its own if the log failed to start), and
  says whether it worked. Clear and turning the log off start a new generation (`VoiceTurns`):
  a turn opened before, even one still being matched or transcribed, is never written and its
  WAV never saved.

### Pipeline

`GarVoiceLog` (`gar/voicelog/`, set up from `App.onCreate`) is a set of static hooks, each one
GAR-marked line, over `VoiceTurns`:

- Hooks never throw and only touch memory. A hook that fails is logged once per process (tag
  `GarVoice`).
- One background thread ("GarVoiceLog", lowest priority, daemon) does everything else: JSON
  building, file writes, WAVs and their levels, the keyguard check and the end of holds.
- The evaluator carries the turn in its coroutine context:
  `scope.launch(GarVoiceLog.input(event))` in `SkillEvaluator.processInputEvent`, through the
  answer, the permissions card, both error catches and `GarVoiceLog.listen(FOLLOW_UP)` when the
  mic reopens.
- Code that isn't a coroutine records into the turn being matched: Dicio's ranker (one line in
  `SkillRanker.getBestForSpecificity`), the model's call rewrite
  (`GarVoiceLog.watchRewrite(MediaControlRewrite)` in `GarModelManager`, the router's default
  rewrite, so behaviour is unchanged) and TTS `onStart`. This is safe because GAR matches one
  input at a time.
- Other hooks: `garMatchUtterances` (candidates, GarMatchCheck's verdict, the path that won),
  `GarFallback.tryRoute` (model output, why it was skipped, the lock screen, latency),
  `GarWhisperInputDevice` and `CaptureSession.voice` (speech start, end reason, lengths, prompt,
  transcript, filter reason, clip). `TranscriptFilter.rejection()` says why a transcript is
  dropped; `accept()` is built on it.

**How a turn starts.** A turn opens when the Whisper device starts capturing for the evaluator
(`GarVoiceLog::captureStarted`). For other input it opens when the evaluator gets a Final: typed
text, buttons, Vosk, or another app's recognizer. The start comes from the last hint: `mic`
(HomeScreen's mic button), `assist` (`MainActivity.onAssistIntentReceived`), `hotword`
(`WakeService.onWakeWordDetected`), `typed` (HomeScreen's text field), `follow_up` (the evaluator
reopening the mic) or `button` (a Final with no hint, from a card's button). The four voice
starts call `GarVoiceLog.listen(start) { sttInputDevice.tryLoad or onClick }` (one GAR edit
each): it sets the hint and marks its thread while the device starts, and the Whisper device
starts its capture on that thread or not at all. A capture outside `listen` (keyboard dictation
for another app through `SttService`, the speech popup) has no turn, nothing of it is recorded,
and `VoiceTurns.captureStarted` also opens none without a fresh hint. A hint lasts 30 s
(`HINT_MILLIS`), long enough for Vosk's whole capture. While a Whisper capture runs, hints are
ignored, since that tap ends the capture. A Final whose capture has no transcript yet is a
button tapped while listening, so it gets a turn of its own. `other` remains for an error event
with neither a capture nor a hint.

### What is recorded

- `id`: the start time plus a sequence number, e.g. `20261102-081500-120-1`; also `seq`, `t`
  (local ISO time) and `start`.
- `locked`: `isDeviceLocked`, read on the log thread.
- `stages`: ms since the start for speech_start, speech_end, text, input, route, answer,
  first_word and end.
- `stt`: engine (`whisper`, or `other` for Vosk and other recognizers, which aren't hooked) and
  model file.
- `capture`: `speech_start_ms`; `end` from `GarWhisperInputDevice.endReason` (vad_silence,
  max_length, user_tap for a tap or SttService's finish, audio_end, no_speech, too_little,
  cancel, error), with `cancelled` when a tap cancelled transcription; `speech_ms`, `audio_ms`
  (all captured) and `clip_ms`; the clip's `peak_dbfs` and `mean_dbfs` (RMS); `audio_ctx`.
- `prompt`: on/off, tokens, names, owner, owner_included and owner_source (from
  `GarSttPromptNames.state`).
- `transcript`: raw, confidence, no_speech, decode_ms, encode_ms, tokens, filter and
  filter_reason (empty, silence_phrase or prompt_names).
- `wav`, `spoken` and `written` (SpokenForm's output), `input` (the evaluator's utterance) and
  `edited_from`.
- `match`: the first alternative's top 3 Dicio candidates (each skill with its best score);
  `rank_ms` and `rank_cpu_ms`, the ranker's wall and thread CPU time summed over alternatives,
  and `rank_model_loading` when the model loaded or warmed up meanwhile (wall far above CPU means
  the ranker waited for a core, see "Routing speed");
  GarMatchCheck's verdict (accept, reject or off; a reject is kept unless a later match is
  accepted); the path that won: `sentence` (accepted by `acceptDefault`), `follow_up_answer`
  (any other batch match), `online_gate` (a `GarOnlineGateSkill` won), `model` or `fallback`.
- `model`: `outcome` (call, dropped, no_match when the model abstained, failed, locked, or
  skipped with why: off, no_model, no_tools, not_english or an error); the call's tool, args and
  missing slots; `rewrite` as "from -> to"; the call filter's verdict (keep or drop, from
  `RouterOutput`) with its reason; `ms`.
- `answer`: outcome (answered, missing_permissions, no_input, stt_error, error, not_evaluated),
  skill, speech, card (the output's class name), permission_card, error_class and error_message.
- `latency`: to_text_ms, to_route_ms and to_first_word_ms, from the end of speech, or from the
  input for typed turns and other recognizers. The first word comes from one GAR line in
  `AndroidTtsSpeechDevice`'s `UtteranceProgressListener.onStart`.
- `suspect` and `suspect_by`. NaN numbers are written as `null`.

### Suspect signals

`VoiceSignals` (pure) marks a turn suspect from what came next. Signals are applied before
writing, so each turn is still exactly one JSON line:

- **Hold.** A turn with input is held in memory after its reply until the next turn's input
  arrives, or for 60 s (`HOLD_MILLIS`). The next Final takes it, and once that next turn is
  answered (or failed) it may mark the held turn suspect, with `suspect_by` set to the next
  turn's id, and the held turn is written; judging on the answer lets the signals see which skill
  and path took the next input. If the next turn never ends, the held turn is written 60 s after
  that Final without signals. A turn without input (nothing said, dropped by the filter, STT
  error) is written at once and doesn't replace the held turn, so "call erin", silence, "call
  aaron" still marks the first command. A held turn is lost if the process dies within the hold.
- **Trouble** (`VoiceSignals.troubled`): a turn went wrong or was unsure when its answer isn't
  `answered` (an error, the permissions card), its path is `model` or `fallback`, GarMatchCheck
  rejected, the model's call was dropped, no_match or failed, its card asks or offers a pick (a
  class name with "Ask" or "Pick": a contact not found, several found), or it is
  `not_understood`, `asked_to_repeat` or `error`.
- **Comparing two inputs.** As words: lowercase, punctuation dropped except apostrophes, leading
  and trailing fillers (oh, uh, um, ok, hey, please, sorry) removed, a word said twice in a row
  kept once.
- **Repeat and rephrase**, only when the next turn starts at most 20 s after the reply:
  - `repeat`: a near-repeat, character similarity (1 minus Levenshtein distance over the longer
    length) of at least 0.75 ("five minutes" against "ten minutes" is 0.86), or one word
    changed, added or dropped in commands of two words or more ("call erin" against "call aaron"
    is only 0.7 similar), that retried the first turn: the first showed trouble, or another
    skill answered the second; the same words again only when the second showed trouble too
    ("volume up" or "next" twice are two commands); other words only when a word of a prompt name
    (either turn's prompt) came in for one that isn't, which catches the misheard-name retry
    ("call erin" then "call aaron" with Aaron in the prompt) and not "call mom" then "call dad"
    or a second timer.
  - `rephrase`: otherwise, at least 60% of the shorter input's content words appear in the other
    (content words exclude articles, prepositions, "my", "please", "can" and the like; each input
    needs at least two).
- **Explicit signals**, anywhere within the hold: `said_no` (no, nope, nah, "no no"),
  `said_cancel` (cancel, never mind, forget it, don't) and `said_stop`, unless the next turn took
  it as a command without trouble: a pending GAR question answered (`follow_up_answer`, "no" to
  "Want to reply?") or a ringing timer, a ringing alarm or the music stopped (`timer_control`,
  `alarm`, `media_controls`, see "Stop: who takes it"); a "stop" that GAR's conversation took
  still counts. `said_wrong` (wrong, not that, that's not what I said, you misheard),
  `i_said` ("I said ...", "no I said ..."), `cancelled_confirmation` (a no or cancel after a
  `GarConfirmOutput` card, its "No" button included), `edited_transcript` (the user tapped their
  bubble, one GAR line in HomeScreen's `onConfirmedQuestionClick`, and typed something, and the
  bubble's text equals the previous turn's input).
- **On the turn itself:** `asked_to_repeat` or `not_understood` when the answer is
  `TextFallbackOutput`, and `error` when matching or the skill threw.

### Retention

- Logs: 5 files of up to 2 MB.
- Starter index: `starters.jsonl` in the same folder, one short line per turn that may teach a
  starter chip (see "Home and chips"); deleted by Clear, not shared.
- Recordings: while the log is on, the Whisper device copies each transcribed clip once (shared
  with the debug-only "Keep recordings", which stays separate) to
  `files/gar-voice-log/wav/<turn id>.wav`. The 20 newest are kept (`KEEP_RECENT_WAVS`). A suspect
  turn's WAV is renamed `<id>.suspect.wav` and kept, the newest up to 200 (`KEEP_SUSPECT_WAVS`)
  and 30 MB in all (`MAX_SUSPECT_WAV_BYTES`, about 980 s of audio: 196 clips of 5 s, or 35 at
  the 28 s capture cap); older ones go when a new WAV is saved. Clear deletes them all.
- A log line left without its newline (a full disk, a crash) is ended before the next append,
  so only that line is lost.
- Logcat: one info line per turn under `GarVoice` when it is written, with stage outcomes and
  timings only, never what was said or any name, e.g. `turn 20261102-081500-120-1 start=mic
  locked=false stt=whisper end=vad_silence speech=1120ms level=-27dBFS prompt=9tok filter=accept
  decode=230ms route=sentence check=accept rank=60ms/cpu=55ms answer=answered:telephone text=230ms routed=239ms
  word=700ms suspect=repeat`.

### Reading it

`gar/tools/voice-log.py` (see `gar/tools/README.md`, Python 3, standard library only) pulls the
log over `adb -s <serial> exec-out run-as cc.aaro.gar.gar`, or reads `--file` (the JSONL files, a
folder, or the shared zip). It prints a table (time, start, heard, route, answer, latency,
suspect) and a per-stage summary, filters with `--suspect`, `--since` and `--skill`, prints raw
turns with `--json`, and `--pull-wavs DIR` copies suspect WAVs plus a `clips.yml` for
`gar/stt/eval`, kept apart as a held-out set. That file has `owner` (the turns' prompt owner),
empty `starred` and `contacts`, and per clip the prompt names the phone sent and the WAV's
absolute path. A clip's `text` is the retry's words when a repeat, an edit or "I said" marked it
(to check against the recording), else it is a `# text: TODO` comment, so the eval refuses the
file until every clip says what was said. Replay it with `gar/stt/eval/run.py --manifest
DIR/clips.yml --modes none,recorded --label voice-log`: `recorded` gives Whisper the clip's
prompt as the phone did; `make_clips.py` leaves clips with an absolute `wav` alone.

- Tests: `GarVoiceLogTest` (a full Whisper turn's JSON and logcat line; the ranker's times; the
  setting's default per build and the user's choice; turning it off deletes the clips; the model's call, rewrite
  and filter; typed and button starts; turns without input; each signal and the similarity
  measure; commands said again and a stop or no a skill took, against the misheard-name retry;
  errors and the permissions card; hint edge cases; a capture without a hint (another app's
  dictation) and the button after it; Clear and the log going off with a turn in flight; the log
  off; never-throw with a broken folder, clock, keyguard, settings and prompt source; rotation;
  a line cut short; WAV pruning by count and bytes, zip and clear; NaN; the device's end
  reasons), `TranscriptFilterTest` (the rejection reasons) and `python3 -m unittest discover -s
  gar/tools -p 'test_*.py'` (the reader on `testdata/voice-log-sample.jsonl`, a fake adb, a
  shared zip, the clip text from the retry, and the clips.yml read back by
  `gar/stt/eval/manifest.py`).
- Not checked on the phone: the share sheet and its grant, the TTS `onStart` timing, run-as on
  GrapheneOS, and the hint for the assist path when MainActivity is recreated.

## Frame rate

What GAR measures about its own frames, and how the cold-start main-thread stall gets found and
fixed (spec: gar-notes/ui-makeover-design.md, section 5). All emulator numbers come from gartest
(arm64, 4 vCPU, API 35, `hw.gpu.enabled=no`). It renders in software at 60 Hz, so they compare a
build before and after a change and never stand for the Pixel.

- **JankStats** (`gar/perf/GarJank`, androidx.metrics 1.0.0):
  - `GarJank.track(window, surface)` runs on the home screen (MainActivity, "home") and
    the assistant sheet ("sheet"). Each frame's duration is the total on API 31+ and UI plus
    RenderThread CPU before that. `janky` comes from that same duration
    (`GarFrameStats.isJanky`): over twice the frame's deadline, which is the duration minus
    JankStats' `frameOverrunNanos` on API 31+ and the budget at the display's refresh rate before
    that (or when the derived deadline isn't positive). JankStats' own `isJank` isn't used.
  - The last 4096 frames of all tracked windows are kept in a ring.
  - Logcat: a `stop` line when tracking ends, and in debug builds (or after `adb shell setprop
    log.tag.GarJank DEBUG`) an info line `GarJank: window surface=.. frames=120 janky=..
    p50_ms=.. p90_ms=.. p99_ms=.. max_ms=.. hz=..` every 120 frames. The ring and the voice
    log's summary run in every build.
  - A debug line `GarJank: slow surface=.. ms=.. states=key:value,..` for each frame over three
    budgets, with the states set through `GarJank.state(window, key, value)` (meant for the mic
    state; nothing sets one yet, so `states=` is empty).
  - Limit: JankStats starts once the window exists, so it never sees a stall before the first
    frame. Startup is measured with `am start -W` and a trace, not JankStats.
- **Voice log**:
  - Each turn records `frames` = `GarJank.summary(turn start, turn end)`, both on the voice
    log's System.nanoTime clock: `{"surface","n","janky","p50_ms","p90_ms","p99_ms","max_ms",
    "hz"}`. The surface joins several windows with "+", and `hz` is the highest refresh rate seen.
  - The field is absent when no tracked window drew during the turn, or when reading the frames
    failed (that warns once and keeps the rest of the line).
  - `voice-log.py` shows a `jank` column (janky share per turn) and a frames line in the summary
    (all frames, janky share, slowest frame, refresh rates). The verdict for the Pixel comes from
    here.
- **Trace sections**: `Gar:` sections cover:
  - App.onCreate's GAR lines (`Gar:GarOldTimers.drop`, `Gar:GarStartedTimers.get`,
    `Gar:GarNotes.get`, `Gar:GarVoiceLog.install`)
  - `Gar:BaseActivity.locale`, `Gar:BaseActivity.super.onCreate` (Hilt injection) and
    `Gar:BaseActivity.theme` (the runBlocking reads)
  - `Gar:MainActivity.super.onCreate`
  - `Gar:SttInputDeviceWrapper.init` and `Gar:WakeDeviceWrapper.init`
  - `Gar:GarStartupCache` on its own thread, once something starts it
  - async sections (API 29+) `Gar:MainActivity.firstComposition` (setContent to the first applied
    composition), `Gar:loadModel` and `Gar:loadAndWarmUp`

  `Fully drawn` comes from activity-compose's `ReportDrawn()` in MainActivity's content, which
  reports after the first frame is drawn (after the first composition and the first measure).
  Before 2026-11-02 it was reported at the first composition, so it always equalled `Displayed`.

  Debug builds turn on StrictMode disk-read and disk-write logging on the main thread only after
  `adb shell setprop log.tag.GarStrictMode DEBUG` (read at process start), so measured starts
  don't pay for its stack traces.
- **The trace is the gate**: no startup fix lands before a Perfetto trace of a cold start shows
  the cause.
  - Run `adb -s emulator-5580 shell perfetto -o /data/misc/perfetto-traces/gar-start.pftrace -t
    20s -a cc.aaro.gar.gar sched freq idle am wm gfx view dalvik binder_driver disk memory` with
    `am force-stop` then `am start -W` while it runs.
  - For the long main-thread slice, record the thread-state breakdown (Running, Runnable, D,
    Sleeping, monitor contention) and the `Gar:` slice that contains it.
  - Runnable means CPU starvation. D means disk. Sleeping inside a `Gar:` runBlocking section
    means a blocked read. Running in class loading or JIT means debuggable-build cost.
  - A Pixel trace (debug build, taken by Aaron) decides the cause for the phone.
- **Fixes, each only if the trace names it**:
  - BaseActivity reads theme and locale from `GarStartupCache` (runBlocking only while it is
    empty).
  - App.onCreate's GAR lines move onto the GarStartup thread.
  - Hilt singletons get built there first.
  - The two wrappers are seeded from the cache.
  - MainActivity acquires the model with `GarModelManager.acquireAfterFirstFrame(view)` instead
    of at onStart.
  - The model loads on a dedicated `GarModelThread`, with warm-up at low ggml threadpool priority
    and `route()` at normal.

  Each commit message names what the trace showed.
- **Shared pieces already in place**:
  - `GarStartupCache.start(context)`: a "GarStartup" thread reads `UserSettings` and
    `GarSettings` once, then follows them; the values stay null until read. App.onCreate doesn't
    start it, so an unread thread doesn't skew the measured starts; since 2026-11-02 its first
    reader, the assistant sheet, starts it from the voice service's prewarm.
  - `GarModelManager.acquireAfterFirstFrame(view)` acquires after the view's next draw, the
    following frame and a main-thread idle. Every call is its own hold and returns an
    `AutoCloseable` that the caller closes exactly once: before it acquired that cancels it,
    after it ends the hold, a second close does nothing. It doesn't share `release()`, which only
    ends a plain `acquire()` (MainActivity's lifecycle hold). The assistant sheet uses it (closed in
    its teardown); MainActivity switches only if the trace says so.
- **Measuring script**: `gar/tools/jank.py` (emulator only), see gar/tools/README.md.
  - `startup`: cold and warm `am start -W` with TotalTime, WaitTime, Displayed and Fully drawn.
    A cold run whose process is back before `am start` (`pidof` after up to three force-stops)
    counts as `process_warm`, summarized apart: only true cold runs include App.onCreate.
  - `session`: three typed commands and three scrolls. gfxinfo is reset again once the text
    field shows, so the session's numbers from `dumpsys gfxinfo` leave the start out (its frames
    are printed apart); the `framestats` rows (last 120 frames only) list flagged frames apart; the
    `GarJank:` lines are shown too.
  - `sheet`: `GarSheet: first_frame_ms=` over the assist key and Back. The shell may not call
    `cmd voiceinteraction show` on API 35 (it lacks ACCESS_VOICE_INTERACTION_SERVICE), so the
    script and the profile generator press KEYCODE_ASSIST instead.
- **Baseline profile**:
  - `:gar:baselineprofile` (UiAutomator) covers a cold start to `interaction_list` as the startup
    profile, plus a typed "what time is it", scrolling, and the assistant session shown and
    hidden. For the session part it makes the release build the emulator's assistant, after
    reading the holder once before the passes; it gives the role back to that holder at the end
    (or removes the release build when there was none).
  - UiAutomator finds Compose views by `testTagsAsResourceId`, which MainActivity sets in every
    build type but `release` (the profile runs on `nonMinifiedRelease`), so the shipped app
    doesn't expose its test tags to accessibility services.
  - Generate with `ANDROID_SERIAL=emulator-5580 ./gradlew :app:generateReleaseBaselineProfile`,
    which writes `app/src/release/generated/baselineProfiles/`. Not generated yet.
  - Debug builds ignore profiles. The `benchmark` build type (release code, debug key,
    `profileable shell`, not debuggable, id `cc.aaro.gar.benchmark`) uses the release profile.
- **First emulator check (2026-11-02, debug build, no fix in)**:
  - Cold `am start -W` hit its timeout (WaitTime 22878 ms, LaunchState UNKNOWN) on a fresh
    install. The Davey and "Skipped frames" lines in that window came from the system and the
    launcher, not GAR's process, so the emulator itself was saturated.
  - StrictMode flags a 232 ms main-thread disk read in `GarOldTimers.drop` from App.onCreate.
  - `GarJank: slow surface=home` lines of 0.5 to 1.6 s around a typed command.
  - These only show the tooling works; the trace (not run yet) decides the fixes.
- **Before and after (2026-11-02, emulator numbers only)**: "before" is gar-b7 (the last commit),
  "after" the working tree's debug build of that day with no fix yet but three additions: the
  trace sections, StrictMode logging on the main thread (then always on in debug), and the
  GarStartupCache thread, which built the DataStore singletons through Hilt and read them next to
  BaseActivity's own read while nothing used its values. The last two may account for part of
  after's slower first frame; both are now off for measuring (see above). Each was
  installed over the other, opened once and left 40 s, then `jank.py startup --runs 3` and three
  `jank.py session` runs, each from a force-stopped app. Startup ran in rounds before, after,
  before, after, after against the host's drifting load (the Mac sat at a load of 7 to 12).
  - Cold, `Displayed` (ms): before 17986 to 26691, median 18.9 s (n=6); after 13625 to 23052,
    median 20.7 s (n=8). `am start -W` gives up at 10 s, so WaitTime and LaunchState UNKNOWN
    say nothing; `Displayed` is the measure. After's `Fully drawn` equals its `Displayed`
    (reportFullyDrawn was then at the first composition; it now waits for the first frame).
  - The debug build is gartest's assistant, so the voice interaction service brings the process
    back right after `am force-stop`: most "cold" runs are process-warm (LaunchState WARM when
    am reports one) and leave App.onCreate out. The medians above mix both kinds in unknown
    shares; `jank.py startup` now counts them apart.
  - Warm (HOME, then start): 1.2 to 7 s for both, with one 26.6 s outlier (after) where the
    process had gone.
  - Session frames (`dumpsys gfxinfo`, about 200 frames a run, 3 runs): before janky 94.9 to
    97.1 %, p50 500 to 700 ms, p90 1100 to 1550 ms, p99 2000 to 4950 ms; after janky 96.5 to
    98.0 %, p50 500 to 650 ms, p90 1050 to 1700 ms, p99 1850 to 4950 ms. GAR's process logs
    "Skipped 416 to 843 frames" at each cold start in both builds (the first-check note above
    missed these; they are GAR's, next to the system's). These totals include the start's
    frames, which dominate p90 and p99; `jank.py session` now leaves them out.
  - Verdict: no difference beyond the noise. The after build adds no fix, and the stall is still
    there.
- **What the traces show** (atrace text, `sched am wm gfx view dalvik disk binder_driver res`
  plus the app's sections, parsed for the main thread's states; Perfetto's trace processor is
  not installed here):
  - The first frame is one `Choreographer#doFrame` of 7.0 s (before) and 8.2 s (after), of which
    5.7 s and 6.7 s are Running on the main thread. Runnable is 0.4 and 0.7 s, D 1 and 8 ms. So
    it is neither CPU starvation from the model load (`Gar:loadModel` runs 6.8 s next to it on
    its own thread) nor disk.
  - Inside it: first composition (`Compose:recompose`, 1.0 to 1.5 s) and the first measure
    (3.4 to 3.9 s), with 250 `TextStringSimpleNode::measure` taking 1.4 to 2.2 s. The largest
    block of them is the home screen's "Here is what I can do" card: one LazyColumn item with
    every skill's two lines (59 skills on gartest, so 118 of the 250), so all of them compose
    and measure, on screen or not. Since 2026-11-02 the home's card shows four skills and a
    "See all N" button (a 48 dp TextButton, "Show fewer" once open; the small talk "what can you
    do" answer still lists all); not traced again yet.
  - `Lock contention on Jit code cache` costs the main thread 1.7 to 2.1 s, and the JIT thread
    logs over 12000 `Compiling baseline` sections: a debuggable build runs interpreted and
    JIT-compiles Compose as it goes, and ignores the baseline profile.
  - Before the frame, `activityStart` takes 4.9 s (after), 2.9 s of it in
    `Gar:MainActivity.super.onCreate`: `Gar:BaseActivity.super.onCreate` (Hilt builds the
    singletons) 1.6 s, mostly Running; `Gar:BaseActivity.locale` 0.3 s, mostly Sleeping (the
    blocking DataStore read in LocaleManager); `Gar:SttInputDeviceWrapper.init` 77 ms;
    `Gar:BaseActivity.theme` 4 ms. StrictMode's `~duration` values on these reads (up to
    607 ms) are the rest of the message, not the read.
  - Cross-check, the `benchmark` build (release code, not debuggable, no baseline profile yet,
    no models in its data): cold `Displayed` 6931, 7621 and 9585 ms (true cold, LaunchState
    COLD), first frame 2.3 s (Running 1.8 s), measure 0.7 s, the same 240 text measures in
    0.64 s. Confounded: no `Gar:loadModel` competed for the 4 vCPUs and it ran true cold while
    the debug runs were mostly process-warm, so not all of the gain is debuggability. A fair
    cross-check gives both builds the same model (or neither one).
- **Cause and what fixes it**:
  - Cause of the first frame: main-thread CPU work of a debuggable build drawing Dicio's full
    home screen, on a software-rendered emulator. The non-debuggable build drew it about 3x
    faster and reached cold `Displayed` about 2.5x sooner, but that cross-check is confounded
    (above); the planned baseline profile should cut more.
  - Not causes of the first frame: the model load (Runnable 0.4 to 0.7 s), disk (D 1 to 8 ms),
    the theme read (4 ms).
  - Untested, not ruled out: App.onCreate's GAR lines. The trace names neither `bindApplication`
    nor their `Gar:` slices, so the traced start was most likely process-warm, and StrictMode
    flagged a 232 ms main-thread read in `GarOldTimers.drop`. Next: trace one start verified
    true cold (no pid before `am start`) and record `bindApplication` with
    `Gar:GarOldTimers.drop` and `Gar:GarNotes.get`.
  - Named by the trace, not fixed yet: Hilt building the singletons in
    `Gar:BaseActivity.super.onCreate` (1.6 s, mostly Running) and the locale read (0.3 s).
  - Open, for the lead: install the `benchmark` build (with a generated baseline profile) on
    the phone instead of the debug one, which needs its own data (new application id); move
    Hilt's singletons and the locale read to GarStartup (`GarStartupCache`); the true-cold trace
    above. The Pixel trace still decides for the phone.
  - In that run GarJank's window lines said 28 to 42 janky of 120 frames while their p50 was 600
    to 740 ms: they used JankStats' `isJank`, which measures another duration. Janky now comes
    from the recorded duration (above).
- **jank.py fixes from this run**: `session` waits up to 40 s for GAR's window and opens the
  text field with the top bar's button (Dicio shows it only after that); `startup` waits for
  `Displayed` up to 30 s past `am start -W`'s timeout and reads API 35's "for user 0" form.

## Routing speed

On the Pixel 9a, builds with the sheet and the starter chips took 2-19 s from input to route for
plain sentence matches (0.3-0.9 s before), with frame p50 doubled. Dicio's ranker runs once per
input and costs 30-90 ms of CPU once warm; on cold turns its wall time was 3-4x its CPU time:
the thread waited while other work ran. What changed (`GarMatchPriority`):

- **Starter chips** read a small index (`starters.jsonl`, "Home and chips") on a thread of their
  own at the lowest priority, not the whole voice log (up to 10 MB: 3-4 CPU-s on gartest) on
  Dispatchers.IO each time GAR opened after 10 minutes.
- **The ranker** runs at `THREAD_PRIORITY_DISPLAY` (its thread's priority comes back after),
  in the trace section `Gar:rank`.
- **The model's load and warm-up** wait for a running match and run at nice 9 ("Model lifecycle
  and settings").
- **The voice log** records `match.rank_ms`, `rank_cpu_ms` and `rank_model_loading`, and the
  logcat line `rank=<wall>ms/cpu=<cpu>ms[/model_loading]`, so one phone turn shows whether the
  ranker waited (wall far above CPU) and whether the model was loading.

Measured on gartest (2026-11-10, debug builds, typed commands "set alarm for seven thirty am",
"show my alarms", "what time is it", "flip a coin", "whats the weather", 3 rounds after 90 s,
input to route in ms):

| Build | Median | Cold first command (2 s after start) | Cold, 7 s after start |
|---|---|---|---|
| b9 (before the sheet) | 241 | 1430, 1828, 1706 | 725, 814, 855 |
| b11 (sheet), from the measure step | 347-361 | 2682, 2973, 4274 | |
| HEAD with these changes | 207 | 2601, 2875 | 1348, 1462, 1192 |

- Warm turns are back at b9's speed or better (per command 72-421 ms, 1414 for the first alarm).
- Cold first commands are still about 1.5x b9 on gartest. Their ranker wall time stayed about
  3x its CPU (2574/798 ms and 2854/706 ms with the model loading, 1153/368 ms at 7 s without),
  while `top -H` showed the raised ranker thread runnable at 24-40% CPU with CPU left idle: the
  emulator's virtual CPUs, busy with software rendering and the model's page faults, rather than
  threads in the app. b9 loads the model at the same moment, so that overlap is not new.
- With a 10 MB synthetic log, b11's cold first command was 2946-3269 ms and HEAD's 3022-3390 ms:
  on gartest's 4 cores the log parse never competed. Whether it did on the phone (the likely
  cause of its 9-19 s) needs a phone turn with the new `rank_*` fields.
- Not checked: the phone, Whisper turns, a release or benchmark build (debug builds run cold
  code interpreted, with JIT at about 40% of CPU).

## Tests

- `:gar:router` (Kotest, JVM): catalog loading/validation, prompt rendering, grammar generation,
  parsing of good and malformed outputs (mock engine), router flow, call filters, resolvers with a
  fixed `now` (UTC, except the daylight saving test, which runs in America/Denver and is on).
  Invariants are checked over inputs: normalizing twice changes nothing (also with a phone
  number's `+`), any part of a normalized input normalizes to itself, and every word span a
  grammar allows passes the router's validation. The golden `request.json` tools must equal what
  `ToolSchemas` sends.
- `:app` unit test: the real `tools.yml` + `skill_definitions.yml` load, every sentence-backed
  tool binds to a generated `Sentences` class, and the call filter keeps every positive eval case
  (its keywords and its expected slot values). `GarRankingTest` ranks every English skill of the
  app like the evaluator, for inputs that GAR's sentences must win from Dicio's (and the ones they
  must not take).
- `:app` glue: `GarMatchCheckTest` pins each rule of the match check on its own (with the
  calculator's operator gaps and trailing words). `GarContextSizeTest` pins the remembered
  context size (one load per start, a longer prompt, a shorter one, a prompt that can't be
  counted, a load cancelled while counting, only the last model's size), `GarEngineHolderTest`
  that a start's preloads and a first command that comes during the load load once,
  `GarModelDownloaderTest` the restart after a wrong `Content-Range` and a server that is always
  wrong, `CalendarTimeTest`, `CalendarValuesTest` and `CalendarSkillTest` the calendar's offset
  in the hour the clock goes back (provider millis, and a reminder's time that has passed as a
  moment), and `GarCallFlowTest` the "Which day in December?" answers (and a passed day of this
  month). `TelephoneSkill`'s GAR line (`SmsNumbers.distinct` over a contact's numbers) isn't
  JVM-tested, since it needs a `ContentResolver`; the merging itself is pinned in `SmsSkillTest`.
  Catalog checks
  that would otherwise fail only on the phone: every LLM-only tool's skill takes slots
  (`GarToolSkill`), every slot that feeds a capture has a kind that capture always holds, and
  typed slots whose values a capture can't always hold (numbers, dates, date-times and times) are
  LLM-only.
- `:app` phase 2 (PARITY batches 1 and 2): `TimerClockTest` and `TimerControlTest` ("Timers"),
  `AlarmSkillTest` and `AlarmRingingTest` ("Alarm: ringing, change, remove all"),
  `ConversationSkillTest` and `GarConversationTest` ("Conversation"), a test per everyday answer
  plus `EverydayAnswersRankingTest` and `GarEverydayCallsTest` ("Everyday offline answers"), and
  `GarStopRankingTest`, which ranks every English skill while nothing, the clock app's timer, its
  alarm or both ring ("Stop: who takes it"), and the inputs that two of these groups could
  both take.
- `:app` phase 2 (PARITY batches 3 and 4), each ranking test over every English skill:
  `DeviceSettingsTest` (logic, the brightness curve, pages, intents), `DeviceSettingsSkillTest`
  (the model's calls, levels and questions, the access card and its answers, `open_app` pages, the
  resolver hook), `DeviceSettingCallsTest` (the checks of the model's device_setting and volume
  calls, with the eval's wrong calls) and `DeviceSettingsRankingTest` (also the match check,
  connectivity's without tools, questions about commands) ("Device settings"); `CallBackTest`, `CallHistoryTest`, `CallLogSkillTest`,
  `CallControlSkillTest` and `CallsRankingTest` ("Calls: call log and call controls");
  `MessagesTest`, `EmailTest`, `MessagesCallsTest` (the real catalog) and `MessagesRankingTest`
  ("Messages and email"); `TelephoneContactsTest` and `GarChatReplayTest` (Aaron's chat replayed
  through the evaluator's matching step) ("Contacts: myself, no match, corrections"); `MediaWordsTest`, `MediaPlaySkillTest`, `MediaControlsSkillTest`,
  `MediaRankingTest` (also while media plays or something rings), `GarMediaPlayTest`,
  `GarMediaControlsTest` and `GarSleepTimerTest` ("Media: play by name, controls, sleep timer").
  `:gar:router`'s `ToolCatalogTest` checks `confirm_text`'s `{slot, map}` form, and
  `CallRewriteTest` the `media_play` -> `media_control` rewrite with the eval's commands. The Android parts
  (the settings and telephony services, the media sessions, the sleep timer's alarm and receiver,
  the notifications' reply actions, the cards) have no JVM tests.
- `:app` batches 5 and 6: `WeatherTest`, `WeatherSkillTest` ("Weather"); `KnowledgeTest`,
  `KnowledgeOnlineSkillsTest`, `DictionaryTest`, `CurrencyTest`, `OfflineTranslatorTest` ("Online
  answers"); `WebAddressTest`, `PlacesTest`, `ScreenAndCameraTest` ("Places, camera and
  screen"); `NotesWordsTest`, `NotesSkillsTest`, `NotesCallsTest`, `NotesRankingTest`,
  `AgendaTest`, `ContactInfoTest` ("Notes, lists, facts, ..."); `GarBatch56RankingTest` for the
  inputs two groups could take ("Batches 5 and 6 together").
- Speech ("Speech recognition (Whisper)"): `SpokenFormTest` in `:gar:router` (the golden table,
  every rule group, no digit left, `normalize(spoken) == spoken`, every eval and holdout text and
  `GarRankingTest` input unchanged, dicio-numbers and `EnglishTime` reading the words back);
  `AudioCtxTest`, `EndpointerTest`, `TranscriptFilterTest` (also prompt names copied into
  unclear audio), `NamePromptTest` and `WavReaderTest` in `:gar:stt`;
  `WhisperEngineHostTest` in `:gar:stt`, on the host JNI build with base.en and the VAD file
  (skipped without them; gar/BUILD.md): the `say` WAVs and the JFK sample transcribed, the same
  text at `audio_ctx` 128 and 1500 and after a full-context run, cancel, VAD on speech and
  silence. In `:app`: `GarWhisperInputDeviceTest` (every state and event against a fake mic, VAD
  and models), `GarSttDeviceResolverTest`, `GarSttModelManagerTest` (catalog pins, download,
  switch, a failed switch, cancel, delete, the load guard, unload after release, the offer card's
  rules, the popup's written text, the prompt names), `ContactPromptNamesTest` and
  `GarExternalSpokenFormTest`. The Whisper eval set
  (`gar/eval/stt/make-whisper-cases.sh`) speaks each case with three macOS voices, transcribes it
  with whisper-cli and the app's settings, applies SpokenForm and reports WER, for the router
  eval on what Whisper heard.
- Eval: `gar/eval/` utterances with expected `{tool, slots}` run through the real router against
  llama-server on the Mac (`gar/eval/run-eval.sh`), reporting tool, slot and abstention accuracy.
  The router gets the app's call filters (`GUARDS=none` turns them off); since they don't change
  what the model generates, the summary also scores the run without them and lists what each
  guard dropped (true negatives, misroutes on positives, lost positives).
  `-Pgar.eval.replay=<report.json>` (Gradle `:gar:router:eval`) scores the outputs of an earlier
  report again without llama-server, to try filters or keywords; it warns when the report was
  recorded with another prompt. `CASES=<file>` runs another cases file (`gar/eval/holdout.yml`,
  a blind held-out set). Each run starts its own llama-server: the server reuses the KV cache of
  the previous request's common prefix, so outputs depend a little on what ran before on the same
  server (a few probe cases changed tool when run after the eval cases), and only runs on fresh
  servers compare. On a busy machine the first request evaluates the whole prompt for minutes:
  set `TIMEOUT` (seconds), and `THREADS=1` if llama-server's threads starve each other.

## Step 3 skills

New skills follow Dicio's skill structure (`SkillInfo` + `Skill` + outputs in
`app/src/main/kotlin/org/stypox/dicio/skills/<skill>/`), are registered with one line each in
`SkillHandler`, get English sentences in `sentences/en/<skill>.yml` for the fast path where it
helps, and get tools in `tools.yml` so the model can reach them. Shared files have one
`gar:<group>` marker per skill group; a group replaces only its own marker. New strings go in
`res/values/strings_gar_<group>.xml`; permissions are already declared in the manifest (GAR
block); `Permission` objects live in the skill's own package, not in `util/PermissionUtils.kt`.

Where an upstream Dicio PR exists, the skill starts from it (credit the author in the commit
message) and keeps its sentence ids and captures where possible, so GAR converges if the PR is
merged upstream. Only offline Android APIs and intents; nothing here uses the network.

| Group | Skill ids | Upstream base | Notes |
|---|---|---|---|
| alarm | `alarm` | PR #376 (alarm part only; Dicio's timer stays) | `AlarmClock` intents to the clock app (GrapheneOS: DeskClock). Set with `EXTRA_SKIP_UI` and repeat days; list = `AlarmManager.getNextAlarmClock()` plus `ACTION_SHOW_ALARMS`; cancel = `ACTION_DISMISS_ALARM` (DeskClock opens and can't dismiss alarms more than 24 h away; say so). Also stops and snoozes the clock app's ringing alarm, changes an alarm (sets the new one, then dismisses the old one) and confirms removing all alarms ("Alarm: ringing, change, remove all"). |
| calendar | `calendar` (events + reminders) | PR #381 | Write events directly to the calendar provider (`WRITE_CALENDAR`) in a user-chosen writable calendar (default: first visible calendar with access level >= contributor; Aaron's phone has DAVx5 CalDAV calendars). Query events of a day. Reminders = events with a `METHOD_ALERT` reminder at 0 minutes; GAR notifies for its own reminders from an `EVENT_REMINDER` receiver when no other app on the device handles that broadcast (stock GrapheneOS Calendar can't: its editor and alert code were removed). `ACTION_INSERT` only as a fallback when no writable calendar exists, and never to `com.android.calendar`. |
| sms | `sms` | PR #375 (reuse the telephone skill's contact lookup, as its review asked) | Fully voice controlled, like Siri (Aaron's decision 2026-10-26, replacing "only a tap on Send sends"). Ask for a missing recipient or message; ask which number only for really different numbers (one number stored as "435-555-0167" and "+14355550167" is offered once, in its international form). Then a card with recipient, number, message and Send/Edit/Cancel buttons, whose speech reads back the name (never the number) and the message as it will be sent, "Your message to Aaron says: Hello. Ready to send it?", with the microphone open. Spoken answers (`SmsCardAnswers`, only short answers made of known words, so a new command goes to its own skill and ends the text's conversation unsent, while input no skill understands leaves the text waiting): yes/send it/"no you send it" sends via `SmsManager` and says "Sent." or "The text didn't go through." (waits up to 10 s for the sent report); after a no only an explicit go-ahead sends, so "no, it's fine" and "no go" decline, and "go" alone (a misheard "no"?) and "send a text" (a new text) are no answers; no/cancel/"don't send it" cancels; "change it" asks "What should it say?" and "change it to ..." takes the new text, "send it to dad instead" takes a new recipient, all read back again on a new card ("yes, send it to mom" for the current recipient sends); "read it again" or "did you send it?" repeats. `SEND_SMS` is in `neededPermissions`, so Dicio asks for it with the contacts on first use (and again before any sms step if it was revoked; denied, the skill doesn't run); if GAR can't send anyway (locked, sending throws) it opens the default SMS app pre-filled and says so. Edit opens the messages app. A locked phone gets "Unlock your phone first." Contacts match by whole words, "myself" is the owner, and "no, to Aaron" corrects the name ("Contacts: myself, no match, corrections"). |
| device | `volume`, `do_not_disturb`, `connectivity` | PR #384 (volume up/down) | Volume up/down/by steps/to a percentage/mute on the music stream. DND on via `NotificationManager.setInterruptionFilter` (ask for policy access); DND off: if GAR is the active VoiceInteractionService use `GarAssist.startVoiceActivity` with Settings' ZenModeVoiceActivity, otherwise open DND settings and say why. Wi-Fi: `Settings.Panel.ACTION_INTERNET_CONNECTIVITY`. Bluetooth on: `ACTION_REQUEST_ENABLE`; off and anything else: Bluetooth settings. The model reaches Wi-Fi and Bluetooth through `device_setting` (`gar:settings`); the connectivity sentences stay. |
| clock | `stopwatch`, `date` | none | Stopwatch in-app (start/stop/reset/lap/elapsed) with a chronometer card and an ongoing notification; its state survives process death. Today's date (and day of week, "what's the date tomorrow"). The stopwatch tool is LLM-only (no `sentence`), and the skill builds and runs without sentences, which is what languages other than English get. English has sentences (`sentences/en/stopwatch.yml`, one per action) only because Dicio's timer cancel sentence otherwise took "stop the stopwatch" and "cancel the stopwatch" as a timer called "stopwatch" (`StopwatchRankingTest`). They only match an input that names the stopwatch (or a lap, for the lap sentences): Dicio scores a sentence with a word left out above its thresholds, so "keep the music going" resumed the stopwatch and "set the timer to zero" reset it. |
| timer | `timer_control` | none (Dicio's `timer` skill is kept) | Timers are the clock app's (`AlarmClock` intents), so they ring with GAR closed. Dicio's timer sentences and the model's timer tools still set, stop and query them through Dicio's `TimerSkill`; `timer_control` (English sentences, no tool) stops a ringing timer, and its pause, resume, add time, list and cancel open the clock app's timers ("Timers"). |
| settings | `device_settings` | none | Brightness (the slider's curve), adaptive brightness, auto-rotate and the screen timeout through `WRITE_SETTINGS`; the ringer (silent with DND access); airplane mode and battery saver through Settings' voice activities when GAR is the assistant; pages or panels for what only Android switches; status questions; "open <page> settings". English sentences plus the LLM-only `device_setting` tool, which took the wifi and bluetooth tools' calls ("Device settings"). |
| calls | `call_log`, `call_control` | none | Who called, missed calls, call back and redial (the call log, then the telephone skill's confirmation; the model's `call_contact` reaches it through a GAR line in `TelephoneSkill`); answer, decline, hang up through Telecom, speaker on the call screen. English sentences, no tool ("Calls: call log and call controls"). |
| messages | `messages`, `email` | none | Messages among the notifications by person, group or app, replies through the notification's reply button with the SMS card's read-back, emails opened in the mail app. English sentences plus Kotlin resolution of `read_notifications` and `sms_send` calls, no tool ("Messages and email"). |
| weather | `weather` (Dicio's id, GAR's skill) | Dicio's weather skill, replaced | Forecasts by day and hour, rain, snow, wind, high and low; Breezy Weather's shared forecasts offline, Open-Meteo online behind the ask-once gate. English sentences plus the LLM-only, slotless `weather{}` tool ("Weather"). |
| knowledge | `knowledge`, `dictionary`, `currency`, `offline_translator` | none | Wikipedia, WordNet definitions, ECB rates (online skills) and Offline Translator's service; read in Kotlin, no sentences, no tool ("Online answers: knowledge, definitions, currency, translation"). |
| places | `places`, `share_location`, `website` | none | Where am I, nearby, home and work, sharing the location, websites; Kotlin resolution of `navigate` and `open_app` calls, no tool ("Places, camera and screen"). |
| screen | `camera`, `screenshot`, `clear_notifications` | none | Camera modes, a screenshot through an optional accessibility service, clearing notifications. English sentences, no tool ("Places, camera and screen"). |
| notes | `lists`, `notes`, `facts`, `agenda`, `reminders`, `contact_info` | none | GAR's own lists, notes and facts, the next event and event changes, GAR's reminders, contacts' details; Kotlin resolution of calendar, reminder, call and text calls, no tool ("Notes, lists, facts, calendar changes, reminders and contacts"). |
| media | `media_play`, `media_controls` | none (Dicio's `media` skill is kept) | Play by name through `MEDIA_PLAY_FROM_SEARCH` or a session's `playFromSearch`, shuffle, repeat, seek, restart, like, pausing on "stop" while media plays, and a media sleep timer. English sentences plus the LLM-only `media_play` tool ("Media: play by name, controls, sleep timer"). |

Every new tool states `confirm` / `confirm_text` / `on_lock_screen` deliberately: actions that send,
delete, call, or change device state confirm; reads that reveal private data (calendar events,
messages) are not allowed on the lock screen. Eval cases for each tool go in `gar/eval/cases.yml`
under the group's marker; negatives that become commands (e.g. "turn up the volume", "remind me to
buy milk") move to the right tool. A tool gets `keywords` only when its requests name what they
are about (see "Call filters"), together with a few held-out paraphrases in the eval that the
keywords must accept; `GarCallFilterTest` keeps every positive eval case through the filters.

Dicio's timer cancel sentence ("silence|(shut|turn off)|quiet|mute the? .name.? timer") scores
above Dicio's thresholds with "timer" left out, so it takes commands about other things that are
turned off or muted before the model sees them. GAR fixes that with its own sentences, never by
editing the timer's: the stopwatch's (above), GAR lines in the listening skill's `listening.yml`
(marked `# GAR`: "mute the mic", "turn the mic back on", "turn off the wake up word", "stop
listening for hey dicio") and the alarm skill, whose cancel sentence reads the next alarm's day as
the next alarm ("turn off tomorrow's alarm", `TypedSlotRewrites.alarmToCancel`) instead of giving
the input up. `GarRankingTest` ranks every English skill of the app on such inputs, on the
timer's own commands, and on inputs the new lines must not take.

Dicio also scores the listening lines with a word left out: "(turn|switch) off (the|my)? mic"
took "switch off the music", "switch on the lights", "switch off my phone", the wake up word line
"turn off my wake up alarm", and "change the wake word" stopped listening (the wake word service,
until it is started again). So in English `ListeningSkill.score` (GAR edit) only keeps a match
for an input that names the microphone, the wake word, listening or Dicio and has an on/off word;
upstream's bare "mute" and "unmute" stay. Those "switch" inputs go back to what took them before
GAR's lines: the connectivity sentences open the Wi-Fi panel for "switch off the music" (not
fixed here).

### Alarm: ringing, change, remove all

What the clock app does (AOSP DeskClock as GrapheneOS ships it, checked on its branch 17):

- `ACTION_DISMISS_ALARM` opens DeskClock's alarm list. A ringing or snoozed alarm is stopped. An
  upcoming alarm within 24 h only skips its next ring: a one-off alarm set with `SKIP_UI` is
  deleted, other one-off alarms are disabled, and a repeating alarm rings again on its next day.
  An alarm more than 24 h away is refused. More than one match outside `SEARCH_MODE_ALL` opens a
  picker.
- `SEARCH_MODE_NEXT` matches ringing alarms first (`FetchMatchingAlarmsAction`, since 2015), so
  the old worry that it would dismiss a backup alarm instead of the ringing one was wrong. The
  real risk is that what rings isn't DeskClock's alarm (a timer of any app, or another alarm
  app's alarm): then NEXT would skip the clock app's next alarm. That is why GAR sends NEXT
  for a ringing alarm only when it knows the ringing alarm is DeskClock's.
- `ACTION_SNOOZE_ALARM` snoozes only alarms that are ringing, shows no UI, ignores
  `EXTRA_ALARM_SNOOZE_DURATION` and uses DeskClock's own snooze length. It is harmless when
  nothing of DeskClock's rings.
- Known gap: dismissing a snoozed alarm silently does nothing. DeskClock's NEXT takes the snoozed
  time, then looks for alarms set at that hour and minute, and finds none.
- `HandleApiCalls` is a `singleInstance` activity: a second intent sent while the first one is
  still open is lost, hence `BETWEEN_INTENTS_MILLIS` (1.5 s).

How GAR knows that the alarm rings (`FiringAlarms` in `ClockApp.kt`): it reads the active
notifications of GAR's notification listener (`NotifyHandler.Instance`). DeskClock's ringing
alarm is `CATEGORY_ALARM` with a full-screen intent and a foreground service. Its ringing timer
has no category, its running timer has the category but neither of the others, and its upcoming,
snoozed and missed alarms are `CATEGORY_EVENT`. The foreground service flag counts on its own
too, because Android 14+ drops the full-screen intent when the user takes that permission away.
Only the package that `ACTION_DISMISS_ALARM` resolves to counts, so another app's notification
never looks like the alarm (the clock app's ringing timer: `FiringTimers`, "Timers"). Without notification access the answer is `NoAccess`: the sound
(`AudioPlaybackConfiguration` with `USAGE_ALARM`) can't tell an alarm from a timer.

- **Stop.** Sentences `stop` in `alarm.yml`: "stop", "turn it off", "shut up", "i'm up",
  "dismiss", "stop the alarm", "cancel (it/that)", "ok", "okay", "alarm off", "mute it" (not a
  bare "mute", which the listening skill keeps) and every `GarRinging` stop phrase with the
  conversation's fillers (`AlarmSkill.STOP_PHRASES`, read through `conversationWords`, which also
  drops "just", "uh", "um" and a word said twice: "just stop", "stop stop"). They score only while
  `FiringAlarms` says `Firing`, and `AlarmInfo` registers that same check with `GarRinging` under
  "alarm", with `AlarmSkill.isRingingCommand` as what GAR's questions leave to it. GAR sends
  dismiss NEXT, waits up to 2 s for the notification to go, then says "Alarm stopped." or "The
  alarm is still ringing. Turn it off on the clock screen." "Turn off my alarm" and "turn the
  alarm off" (cancels of the next alarm, from a sentence or from the model) do the same while the
  alarm rings. If something rings that GAR can't tell to be the alarm, it sends nothing and shows
  the Ringing card, with an "Allow notification access" button when access is missing (the song
  skill's `openListenerSettings`). Without notification access a bare "stop" is left to GAR's
  conversation skill, which shows that same Ringing card when an alarm sounds ("Conversation").
- **Snooze.** Sentences `snooze` (the duration as a string) are always taken when the input is
  made only of the snooze sentences' words and a duration said parses, so "snooze" with nothing
  ringing says "No alarm is ringing." instead of reaching the model. "Five more minutes" and
  "give me ten more minutes" are a snooze only while the alarm rings (`AlarmSkill.snoozeOf`, with
  the whole duration, since the sentence captures only the number). Without notification access
  GAR goes by the sound, since DeskClock only snoozes a ringing alarm anyway (a ringing timer's
  sound makes it send a snooze that does nothing, and it says the alarm still rings); with access,
  the clock app's ringing timer takes every snooze first ("Timers"). "Snoozed
  until 7:10 AM." comes from `AlarmManager`'s next alarm once it changes to a time soon after now
  (checked for up to 2 s); the card adds "The clock app used its own snooze length." when the
  asked length differs.
- **Change.** Sentences `change` (from, to): "change my seven am alarm to seven thirty", "move my
  alarm to eight", "push my wake up alarm back to eight". A missing old time ("next",
  "tomorrow's", "wake up") means the next alarm, checked like dismissing it. The old time's am or
  pm comes from the next alarm when it matches one of the readings; the new time's is the reading
  closer to the old time, across midnight too (`ChangeTimeResolver`); otherwise, and when both
  are as close (9 AM to "three"), GAR asks. A bare
  "twelve" is noon, as GAR's resolver reads it. It is confirmed through the internal tool
  `alarm_change`, which is not in `tools.yml` ("Change your 7:00 AM alarm to 7:30 AM?"). After a
  yes GAR sets the new alarm, pauses 1.5 s, then dismisses the old one by its time: setting first
  keeps GAR in the foreground for the second intent, and the new alarm may now be the next one,
  so NEXT would find it. The card says that a repeating old alarm still rings on its other days
  and, when the old alarm isn't the known next one, that DeskClock only turns it off within 24 h.
  The same time says "Your alarm is already at ..." and sends nothing.
- **Remove all.** `cancel_all` gained "every" and "each" lines. Before confirming, GAR checks for
  no alarms (`NoAlarms`) and for all alarms more than a day away (`TooFarAhead`, which opens the
  list), then confirms through the internal tool `alarm_cancel_all` ("Dismiss all your
  alarms?"). `AlarmTools.inputFromSlots` tells the internal tools apart by their slot names.
- `GarMatchCheck` never rejects the alarm's Stop and Snooze matches (they name no alarm, and
  `AlarmSkill.score` has checked them), and a change's captures count as captures for its
  keyword check.
- The model's `alarm_cancel(time: "all")` is still dropped by the router's
  `SlotPlausibilityFilter`. Making it work needs a router change (a `TypedSlotRewrites` value
  plus a resolver, or `GarToolSkill` seeing raw slots); the sentences cover "every" and "each"
  instead.
- Next step for a hotword-free "stop": `NotifyHandler.onNotificationPosted` could call
  `FiringAlarms` and listen briefly.

Tests: `AlarmRingingTest` (the notifications that tell DeskClock's ringing alarm, no listener,
the stop and snooze sentences with and without a ringing alarm, "five more minutes" only while
it rings, the ranking of every English skill while it rings, the match check, stop and snooze
with their checks and the snoozed time) and `AlarmSkillTest` (a cancel while something rings
that may not be the alarm, remove all checked before its confirmation, the change sentences, set
then dismiss after a yes, am or pm of both times and a tie asked, a change checked like a
dismissal, the same time and an unknown alarm).

## Step 4: song

"What song is this?" is the `song` skill (`skills/song/`, strings in `strings_gar_song.xml`, one
line under `gar:song` in `SkillHandler`). It is enabled by default: tier 1 is offline, and tier 2
only runs once the user sets up a service. English sentences (`sentences/en/song.yml`: "what song
is this", "what's playing", "who sings this", "who's singing", "shazam this", "what am i listening
to", ...) and the LLM tool `song_identify{}` (`confirm: false`, `on_lock_screen: true`, keywords
song, music, track, tune, playing, listen, sing, sang, singer, shazam). "Sings" and "sang" need
their object in the sentences ("who sings this"), else "who sings hello" was "who sings" plus a
title. Dicio's matcher also took "what is this", "who is this", "what's the name of this
restaurant", "what's the next song", "what movie is playing", "what's the music volume", "what is
music" and "who wrote this song" with a word left out, swapped or added, so in English
`SongSkill.score` keeps a match only when the input (`SongWords`) names one of those keywords (the
same list as the tool's, compared in `GarCatalogTest`), points at the sound (this, that, it, the,
playing, on, now, singing, listening, shazam: every sentence has one) and names no other thing
(lyrics, next, volume, movie, film, show, game, record, about, wrote, written, writer, produced,
producer, genre, tempo, year, yesterday, last, and media commands' words: skip, pause,
resume, stop, previous, repeat, replay, shuffle, mute, louder, quieter, continue, keep, turn), also
with the model off. `GarRankingTest` has the review probe's inputs ("who sings hello", "what's the
next track", ...). The model's `song_identify` calls get the same check (`SongCallFilter`, after
Guard A and B in `garCallFilter`; `GarCallFilterTest` keeps the 12 positive eval cases and drops
"skip this song", "keep playing", "sing me a song", "track my package", "who sings hello"), since
a wrong call can record and send: `media_control` commands name the music too, and the tool's
description shares "music ... that is playing" with `media_control`'s. Inputs it still takes that
ask about the past: "what was that song", "what song was that", "what was playing" (tier 1
answers if the track still plays). The tiers, in order (`SongLogic.plan`):

1. **The phone plays it (offline).** With notification access for Dicio's `NotifyHandler`,
   `MediaSessionManager.getActiveSessions(ComponentName(ctx, NotifyHandler))` lists the sessions
   in the system's priority order; the first one that is `STATE_PLAYING` with a title answers (GAR's
   own is ignored): title (or display title), artist (album artist, display subtitle), album and
   the app's label. "That's {title} by {artist}." ("That's {title}." without an artist) with a
   card. Notification access is not a `neededPermission`: without it tier 1 is skipped, and the
   settings show it with a tap to allow it; when the user comes back without access, the row says
   how to "Allow restricted settings" in GAR's App info (a sideloaded app's switch is grayed out
   until then on Android 13+). A session that plays without a title goes on to the next tiers.
2. **A recognition service (the network, opt-in).** AudD (`api_token`) or ACRCloud (host, access
   key, access secret) in the skill's own settings (`skill_settings_song.proto`, DataStore file
   `skill_settings_song.pb`). Secrets are shown as "Set"; their dialog starts empty (empty keeps
   the stored value, Clear removes it), hides what is typed unless the user taps Show, and asks for
   a password keyboard without autocorrect, so that the keyboard doesn't learn the token. The
   ACRCloud host is saved only when `https://<host>/v1/identify` is a valid URL with nothing but
   a host and maybe a port (else a toast); a host saved before that check gives "The ACRCloud host
   in the song skill's settings isn't valid." instead of an exception. Default: no service, so
   nothing is ever recorded or sent. The settings say "Sends a 10 second recording to AudD over
   the internet" on the choice and explain the tiers on top.
   - Recording starts right after the command: Vosk stops its AudioRecord before the evaluator
     gets the final input. `AudioRecord`, 10 s, mono 16-bit, 16 kHz where the device records it
     (else 44.1 kHz, which every device supports), from the VOICE_RECOGNITION source: the CDD has
     no noise suppression or gain control on it, and it is the source of Dicio's own capture. The
     wake word service (when a wake word is on) keeps its own VOICE_RECOGNITION capture running
     during the clip, which the audio HAL shares within one app (a different source, as
     UNPROCESSED was, could fail or silence one of them), and its detector hears the music.
     Pausing it would need an edit of upstream's `WakeService`; not done, untested on the Pixel
     with the wake word on. WAV rather than AAC in M4A: no MediaCodec/MediaMuxer, a header the
     unit tests check, and 320 KB (882 KB at 44.1 kHz) is far below AudD's 10 MB and ACRCloud's
     5 MB and 15 s; both services fingerprint well below 16 kHz.
   - The card shows "Listening..." with the 10 s progress, "Sends a 10 second recording to AudD
     over the internet." and a Stop button, then "Recognizing..." (Stop still there). Stop cancels
     the run: while listening nothing is sent or kept ("Stopped. Nothing was sent."), while sending
     the request is cancelled ("Stopped.", part of it may have arrived); nothing is spoken after
     Stop. Nothing is spoken and no earcon plays while GAR listens: the output's speech is blank,
     the run continues in the skill's own scope and the answer is spoken when it ends
     (`SongRunner`, one run at a time; `SongRunEnd` decides what is said). While a run is active
     the skill answers "I'm already listening." on the card without saying it, for every tier.
     Other skills can still speak into the clip (a typed command, the wake word): the evaluator's
     speech is upstream code, not held. The first answer whose recording reached the service
     starts with "I sent 10 seconds of audio to AudD over the internet." (`network_notice_spoken`).
   - Digital silence is not sent ("I couldn't hear anything."); a client Android silences
     (`isClientSilenced`, e.g. GAR in the background without the wake word's microphone service)
     gets "Android didn't let me use the microphone. Open GAR and ask again."; no RECORD_AUDIO
     (Dicio's own permission) "I need the microphone permission to listen."
   - Sent with Dicio's OkHttpClient (`SongEntryPoint`, Hilt) plus timeouts (connect 10 s,
     read/write 20 s, call 40 s). AudD: multipart `api_token`, `return=spotify`, `file`. ACRCloud:
     multipart `access_key`, `data_type=audio`, `signature_version=1`, `signature` (base64
     HMAC-SHA1 of "POST\n/v1/identify\n<key>\naudio\n1\n<timestamp>" with the secret),
     `timestamp`, `sample_bytes`, `sample`. Result codes as Audile maps them: AudD 900 bad token,
     901/902 limit, 300/400/500/700 bad recording; ACRCloud 1001 no match, 2000/2004 bad
     recording, 3001/3014 bad key, 3003/3015 limit, 3000/2005 service down; GAR adds ACRCloud's
     other 3xxx codes (3002 HTTP method, 3006 invalid arguments, ...) as the request's setup
     ("ACRCloud didn't accept the request. Check the song skill's settings.", not retried).
   - Result card: title, artist, album, release date and Spotify/YouTube buttons when the answer
     has their links (AudD: Spotify's track URL; ACRCloud: Spotify track id, YouTube video id).
     Only `https://open.spotify.com/` and `https://www.youtube.com/watch` links, opened with
     ACTION_VIEW; no images are downloaded. "That's {title} by {artist}.", "I couldn't recognize
     it.", or a short error ("AudD didn't accept the token in the song skill's settings.",
     "ACRCloud didn't accept the access key or secret in the song skill's settings.", "Your AudD
     account has reached its request limit.", "AudD answered with an error, HTTP 503.", "AudD had
     a problem. Try again later.").
3. **Offline.** The result of a recording that can't be sent now comes as a notification, so GAR
   only keeps one when it can show that notification (`SongNotifications.canShow`: the
   POST_NOTIFICATIONS permission, which GAR otherwise only asks for with the wake word, GAR's
   notifications on and the `gar_song` channel not blocked). Without it the clip is dropped and
   GAR says so: "You're offline, and GAR can't show notifications, so I couldn't tell you later.
   Allow them in the song skill's settings.", with an "Allow notifications" button (Android's
   permission dialog where it can still ask, else the system's notification settings); the
   settings have the same row ("Notifications for offline recordings", shown with a service).
   With notifications, the clip is saved when there is no network with internet
   (ConnectivityManager, checked before sending: "You're offline. I'll tell you when I recognize
   it.") or when sending fails with an I/O error while online (DNS, TLS, a timeout, a captive
   portal: "I couldn't reach AudD. I'll try again and tell you."). `SongClipQueue`:
   `filesDir/song_queue`, `<id>.wav` + `<id>.json` with the time, the service the card named and
   the failed attempts; at most 10 ("You're offline, and 10 recordings are already waiting.");
   a write that fails says "I couldn't save the recording for later" instead. WorkManager
   (`androidx.work` 2.10.1, under a GAR comment in the version catalog) runs `SongQueueWorker` as
   one unique work (`gar_song_queue`, NetworkType.CONNECTED, exponential backoff from 30 s): a new
   clip replaces the waiting work (REPLACE, which resets an earlier clip's backoff) or, while the
   work runs, is appended after it. `SongQueueProcessor` sends oldest first, with the credentials
   read from the settings at send time: a match or no match notifies; a lasting failure (token,
   limit, bad recording, bad host or setup, other 4xx) notifies; a transient failure (no
   connection, HTTP 429/5xx, ACRCloud 3000/2005) counts an attempt and leaves the rest for the
   retry, and the third one notifies. Each clip is deleted before its notification, so a crash
   in between loses the notification but never sends (and bills) the clip twice; a read timeout
   after the service got the clip still sends it again. A clip is dropped unsent, with a
   notification, when no service is set up anymore or the service isn't the one its card named,
   and when it waited 7 days: `SongQueueExpiryWorker` (`gar_song_queue_expiry`, no constraints,
   scheduled for the oldest clip's expiry) does that also without any network, e.g. with
   GrapheneOS's Network permission toggle off, which looks like no network to GAR. While the song
   skill is disabled or GAR can't show notifications, the workers delete the clips unsent and
   silently. Turning the service off or to another one in the settings (or clearing its token)
   deletes the waiting clips and cancels both works; the settings show waiting recordings
   ("Recordings to recognize: 2. Tap to delete them.", with a confirmation). An exception while
   sending drops that clip with a notification instead of failing the work. Notifications on
   GAR's own channel (`gar_song`, default importance); a give-up says "AudD had a problem."
   without "Try again later".
4. **Audile** (`com.mrsep.musicrecognizer`, or its `.debug` build; GPL-3.0-or-later), when no
   service is set up (`AudileHandOff`, entry points from `~/src/gar-notes/recon/audile.md`): as a
   shortcut host (GAR is the active assistant) `LauncherApps.startShortcut` of Audile's dynamic
   shortcut `RecognizeInBackgroundShortcutId` recognizes without Audile's UI (the result in
   Audile's notification; GAR shows "Audile is listening. Its notification will show the song."
   without saying it, since Audile records). Otherwise, or when the shortcut isn't published yet,
   the RECOGNIZE intent to Audile's MainActivity, which records at once, so "Opening Audile." is
   shown and not said (`SongMessages.speaksAudile`); else its launch intent ("Opening Audile. Tap
   its button to recognize the song.", said). When GAR isn't visible and isn't the assistant,
   Android would block the start silently, so GAR doesn't try: "I can't open Audile from the
   background. Open GAR and ask again." On a locked phone Audile's screen opens behind the lock
   screen, where it isn't shown yet, so "Opening Audile. Unlock your phone to see it." is said
   (not checked on the phone). GAR's own capture doesn't run then (tier 2 is off when Audile is
   used), but the wake word service's does, and whichever app isn't on top may record silence
   (recon): untested. Not handled (recon): a stale result on Audile's screen swallows a RECOGNIZE
   intent.

   **Audile's answer, said** (`SongAudileResult.kt`): after a hand-off that starts a recognition,
   GAR waits up to 2 minutes for Audile's result notification and says "That's ... by ...", so it
   works without looking. Dicio's notification listener (`NotifyHandler`, one GAR line) passes
   every posted notification to `AudileResults`, which keeps the latest Audile result: one with
   the `com.mrsep.musicrecognizer.track_metadata.*` extras (title, artist, album, release date,
   id, `sample_timestamp`), which Audile adds to every success notification "for scrobbling".
   Only a result recorded after the hand-off answers it (5 s slack; the post time without a
   recording time), since Audile updates older results' notifications. The card shows a progress
   bar while waiting, then the track with "Open in Audile" (the exported deep link
   `app://mrsep.musicrecognizer.com/track/{id}`); a new hand-off drops the previous wait, and a
   timeout ends it silently (Audile shows "no match" itself). Two opt-ins, both pointed out on the
   card and in the song settings ("Say Audile's answers"): GAR needs notification access, and a
   result shown on Audile's own screen goes to Audile's "Song results found in the foreground"
   channel (`com.mrsep.musicrecognizer.foreground_result`), which is IMPORTANCE_NONE by default,
   and Android doesn't pass notifications of a channel that is off to listeners. The button opens
   that channel's settings page (`ACTION_CHANNEL_NOTIFICATION_SETTINGS`); GAR can't read another
   app's channel, so the hint shows until GAR has heard a result from that channel once
   (`audile_foreground_heard`). The background shortcut's results go to Audile's IMPORTANCE_HIGH
   "result" channel, unless Audile's floating button is on.
5. **Nothing** (`SongPlan.NothingAvailable`): GAR asks "I can recognize songs with Audile, a free
   and open source app. Want to install it?" (`AudileInstallAskOutput`, microphone open).
   - The card adds "Audile is on F-Droid and GitHub. Once it's installed, GAR hands songs to it
     to recognize." Without notification access it also says that GAR needs it; for an app that
     plays without a title, it says the app doesn't say what it is. The spoken question is the
     same for every case, to keep TTS short.
   - "No thanks" / "Install Audile" buttons send their label as input, like the online skills'
     question. They only work while the card is the latest answer (also when the model's
     confirmation wrapped it, `GarFollowUpOutput`) and in the ranker the question was asked in
     (`SongEntryPoint.skillHandler()`).
   - Spoken answers (`AudileInstallAnswers`, English, built like `GarOnlineAnswers` on
     `GarYesNo`, with install verbs as go-aheads): yes ("sure", "install it", "get it", "download
     it", "go ahead", "I want it", "please do", "no wait, install it") opens Audile's install page
     and remembers nothing, so GAR asks again next time if Audile still isn't there.
   - No ("no thanks", "never mind", "I'm good", "don't") is remembered
     (`audile_install_declined` in `skill_settings_song.proto`) and answered "OK, I won't ask
     again. You can get Audile from F-Droid or GitHub."
   - Later ("not now", "maybe later", "not yet", "no, maybe later", checked before the
     negations) remembers nothing and is answered "OK, I'll ask next time." Saying "I won't ask
     again" there would contradict what the user just asked for.
   - Anything else is no answer and remembers nothing, including unclear replies ("it's fine",
     "all good", "not sure", "no wait", "I don't get it", a bare "do" or "go").
   - **Locked phone:** GAR doesn't ask and remembers nothing, like the online skills' question. A
     yes given after the phone locked says "Unlock your phone first."; a no is still remembered,
     and a later still remembers nothing.
   - **After a no, on a locked phone, and in other languages** (the answers are English only),
     GAR says without a question "Nothing is playing on this phone. To recognize other songs, I
     need Audile. You can get it from F-Droid or GitHub." There are variants for an untitled
     player and for no notification access. The card adds the AudD/ACRCloud option, which is no
     longer spoken, and an Install Audile button that opens the page directly
     (`AudileMissingOutput`).
   - The song settings show "Install Audile" while Audile isn't installed (checked again on
     resume), so there is a way back after a no.

   **The install page** (`AudileInstall`), best first, worked out from `queryIntentActivities`
   (the declared `QUERY_ALL_PACKAGES` allows it):
   - **Obtainium,** if any app opens `obtainium://` links (its F-Droid build and forks too). It
     gets Obtainium's documented add-app link `obtainium://add?url=<encoded
     https://github.com/AudileTeam/Audile>` (wiki.obtainium.imranr.dev/deep_links), which opens its
     Add App screen with the repository filled in, or Audile's page (with Install, no Add) when
     Obtainium already tracks Audile, for example after a yes whose install didn't finish. GAR
     says "Opening Audile in Obtainium.", which is true either way.
   - **An F-Droid app** that opens `market://details?id=com.mrsep.musicrecognizer`: F-Droid,
     F-Droid Basic, Droid-ify or Neo Store, set as the intent's package so there's no chooser.
     Google Play and Aurora Store also open market links but don't have Audile. GAR says "Opening
     Audile in Droid-ify." (the app's label).
   - **GitHub:** `https://github.com/AudileTeam/Audile/releases/latest` in the browser. GAR says
     "Opening Audile's download page on GitHub."

   Where Audile is published (checked 2026-10-27): GitHub (AudileTeam/Audile;
   aleksey-saenko/MusicRecognizer redirects there) and F-Droid's main repository (1.19.0). It is
   not on Google Play or IzzyOnDroid. Audile's README says F-Droid's build can't be updated to the
   GitHub one.

   From the background without the assistant role nothing opens: "I can't open the download page
   from the background. Open GAR and ask again." If nothing opens at all: "I couldn't open a
   download page. Audile is on F-Droid and GitHub." The device side sits behind
   `AudileInstallLauncher` and `AudileInstallHost` (Android versions `AndroidAudileInstallLauncher`
   and `AndroidAudileInstallHost`, which reuses `AndroidAudileLauncher.canStartActivities`), so the
   logic runs on the JVM. Not checked on the phone: Obtainium's link, and how GrapheneOS's F-Droid
   apps handle market links.

Code from Audile (core/recognition: the AudD and ACRCloud request fields, the signature and the
result code mapping) is adapted in `SongProviders.kt` with Audile's copyright notice; GAR's
recorder, queue and workers are its own (Audile's FIFO queue is unreleased master code).

Tests (Kotest, JVM): tier selection with fakes (`SongPlanTest`: a playing session, one without a
title, a service online, offline, unreachable while online, a full queue, a failed write, no
notifications, Stop while listening and while sending, silence, microphone failures, Audile,
nothing), `SongRunnerTest` (one run at a time, the answer said when the run ends, the network
notice said and saved once, nothing after Stop, Stop only for its own run) and
`SongMessagesTest` (the text of every outcome, failure and tier, and when GAR keeps quiet for
Audile), AudD and ACRCloud requests and responses (`SongProvidersTest`, with AudD's real answer to
its example clip, recorded once with the public `test` token, the ACRCloud signature against a
vector computed with Python's hmac and openssl, host validation, a bad host as a failure, and
when the settings discard the queue), the WAV header (`SongAudioTest`), the queue and its
processor (`SongQueueTest`: order, cap, failed writes, cleanup, expiry, the clip's service,
attempts, retry, give-up, no service, another service, skill off, an exception, delete before
notify), Audile's starts (`SongAudileTest`), reading and waiting for Audile's result
(`SongAudileResultTest`: the extras, other apps, stale results, the timeout, the hints), the
question about installing Audile (`SongAudileInstallTest`: the links, the order Obtainium, F-Droid
app, GitHub, Play and Aurora skipped, the first page that opens, the background, when GAR asks,
yes, no, other and unclear replies, a new command dropping the question, a phone locked before
the answer, and every result's text and card hint), and in `GarRankingTest` the sentences' own inputs
and the inputs they must not take. Not unit tested: the Android glue (recorder, media sessions,
WorkManager scheduling, notifications, the settings UI). Eval: 19 cases under `gar:song` in
`cases.yml` (12 `song_identify`, 7 out of scope: "sing me a song", "track my package" and "who
sings hello" added after the review), and, since Step 5, 8 commands and 5 near misses in
`holdout.yml`. The new tool is appended last, which changes the prompt every other tool is scored
with (the numbers in "Call filters" are from the 26 tools before it), and `song_identify`'s
description ("Identify the song or music that is playing right now.") shares "music ... that is
playing" with `media_control`'s, whose accuracy the description tuning above raised. Run on
2026-10-26 (Step 5, Qwen3-0.6B; gar/eval/README.md, "Latest results"): 10 of the 12 commands in
`cases.yml` ("what am i listening to" -> `wake_word`, "shazam this" -> `no_match`) and all 8 in
`holdout.yml`. The near misses get calls: 5 of the 7 in `cases.yml` call `song_identify`, and
`SongCallFilter` drops all 5 in the app ("track my package" -> `navigate` stays); of the
holdout's 5, it drops "who sings hotel california" but keeps "what's the number one song in the
country right now", and "put on dancing queen by abba" -> `open_app` stays. On the 342 cases from
before the tool, against the final row of the second round in "Call filters" (dev and held-out
together): tool 75.9% -> 74.5%, exact slots 61.3% -> 59.2%, abstention 76.7% -> 78.3% (14 -> 13
false calls); `media_control` 10 -> 9 of 13, and one of its misses, "play the last song again", now
calls `song_identify`.

## Online skills

The eight online skills (`GarOnlineSkill`, `gar/online/`: Dicio's five plus batch 5's three)
stay off on a new install, and on an existing install a new entry starts off too
(`UserSettingsSerializer`, whose list is `GarOnlineSkill.ids`; `GarOnlineSkillsTest` compares
it), but a request for one of them no longer gets "I don't understand": GAR asks once whether to
turn it on, naming what it contacts. What they contact (read from the code, 2026-11-02):

| Skill | Contacts | What is sent |
|---|---|---|
| weather (GAR's since batch 5, "Weather") | Open-Meteo's geocoding and forecast APIs (no key); nothing while Breezy Weather's shared forecast is fresh | the place name as said, coordinates rounded to about 1 km |
| search | DuckDuckGo (duckduckgo.com/html) | the words to search |
| lyrics | Genius (genius.com) | the song |
| translation | Lingva (lingva.ml, a frontend to Google Translate); nothing when Offline Translator on the phone can translate ("Online answers") | the text and the languages |
| Joke | official-joke-api.appspot.com in English, JokeAPI (v2.jokeapi.dev) in other languages | nothing but the request |
| knowledge | Wikipedia's REST API (en.wikipedia.org) | the subject's words |
| dictionary | GitHub, once: the Open English WordNet 2025 release (9.6 MB); then offline | nothing about the words looked up |
| currency | the ECB's daily reference rates (ecb.europa.eu), at most once a day | nothing about the request |

`GarOnlineSkills.rankedSkills` (one GAR call in `SkillHandler`) gives Dicio's ranker the enabled
skills plus, for each online skill that is off, a gate (`GarOnlineGateSkill`) around the skill
built as usual: the skill's own `SkillInfo` (the card shows its name and icon) and its own score.
An online skill that is on has no gate. When the gate wins:

- GAR hasn't asked yet: "Search uses the internet: it asks DuckDuckGo. Turn it on?"
  (`GarOnlineAskOutput`), with the microphone open. Weather's question names Open-Meteo; its card
  adds that Breezy Weather's shared forecasts need no internet. The card adds what is sent where and
  Keep it off / Turn it on buttons, which send their label as
  input like `GarConfirmOutput`'s. The buttons only work while the answer skill waits in the
  ranker the question was asked in (`GarOnlineAskOutput.askedRanker`): Dicio builds a new ranker
  when any switch in Settings > Skills changes, and a "Turn it on" sent there would go to the
  model as a new command. Spoken answers (`GarOnlineAnswers`, English, only short answers made of
  known words and the asked skill's own name, like `SmsCardAnswers`, with which it shares its
  yes/no core `GarYesNo`; Dicio's `util_yes_no` words are among them): yes ("sure", "turn it on",
  "go ahead", "why not", "please do") turns the skill on (Dicio's `enabledSkills`, the switch in
  Settings > Skills), remembers that the user chose and answers the request right away with the
  skill itself and the same match (`GarFollowUpOutput`); no ("not now", "keep it off", "maybe
  later", "never mind", "cancel", "stop", "I'm good") remembers the choice and says "OK, the
  weather skill stays off. You can turn it on in Settings, Skills."; anything else is no answer:
  a new command goes to its own skill, silence changes nothing, nothing is remembered and GAR
  asks again next time. Unclear replies are no answer too, so that only a clear yes turns a skill
  on and only a clear no is remembered: "it's fine", "that's okay" and "all good" (often a polite
  no), "not sure", "no wait" (often cut off before "turn it on"), "do" alone (a misheard "no"?),
  and a reply that names another online skill ("no, turn on search" to the weather question).
- GAR asked before (the user said no, or switched the skill off in Settings > Skills): "That
  needs the weather skill, which is off. You can turn it on in Settings, Skills.", no question.
- The skill was turned on meanwhile: it runs.
- The phone is locked (and GAR hasn't asked): "Unlock your phone first.", and nothing is
  remembered. Dicio's sentences also match on the lock screen (wake word), and turning on a skill
  that sends data to a third party is the owner's lasting choice, like the wake-word setting. A
  yes to a question asked before the phone locked gets the same answer; a no is still remembered.

Whether GAR asked is GAR's setting (`online_skills_asked` in `gar_settings.proto`); switching an
online skill on or off in Settings > Skills marks it too (one GAR line in
`SkillSettingsViewModel.setSkillEnabled`). Dicio builds a new ranker when the switches change, so
`GarOnlineSettingsImpl.setEnabled` waits for it (at most 3 s) before the skill answers: a
follow-up question of the skill (search's "What do you want to search?") would otherwise go to the
old ranker and be lost.

**Which inputs they take.** Dicio's matcher scores the online skills' sentences above its
thresholds for commands of other skills. With every skill on, over the eval and holdout cases
and a few more: the lyrics sentence "lyrics|text .song." with "lyrics" left out took "text dad",
"text mom i'm on my way" and "send a text" from the sms skill (and would have sent the text to
Genius), "show me my alarms" and "what's the time right now"; search's "find .what. online" took
"find my phone" and "find me a route to the nearest gas station"; translation "could you switch
the torch on for me" and "can you remind me in twenty minutes to check the oven"; weather "how late
is it". A gate taking those would ask about turning on lyrics for a text. So in English an online
skill, behind its gate or on (`GarCheckedOnlineSkill`, which only adds this check), only takes an
input that names what it does outside the sentence's captures (`GarOnlineInputs`): weather
(weather, outside, cold, hot, raining, ...), search (search, look up, seek, online, internet,
web), lyrics (a song captured, and "lyrics" or "song" with goes, says or sings), translation
("translate", or a source or target that is exactly a language's name; the skill's own lookup
also accepts "grandma's house") and jokes (joke, pun, laugh, funny, cheer, ...). Any other input
scores worst and goes where it went with the skill off. For a skill that is on this changes
upstream's behavior ("sing happy birthday" and "find my keys" no longer reach lyrics and search),
since "turn on all online skills" would otherwise break texting by voice. The gates are English
only, like their question and the check; in other languages a gate takes no input (the skill is
as off as before), and a skill that is on is unchecked.

The online skills are ranked last, not in upstream's order. Dicio's `StandardScore` compares
with other standard scores by its own `score()` and with any other score by `scoreIn01Range()`,
so which skill wins depends on where a worst score sits in the list: a gate scoring worst first
(weather is upstream's first skill) changed the winners among the other skills ("what month is
it" lost the current time, "tell me what day it is" went to the notifications). Last, a gate or
an online skill that doesn't take an input changes nothing, and every other skill ranks exactly
as on a new install before. Their matches carry the online skills' ids, which have no tools, so
`GarMatchCheck` never rejects them (the input check above does that, also with the model off).
The model routes only to weather among them: it is the one online skill with a tool
(`GarCatalogTest`), offered while the skill is off behind its gate (`GarToolsProvider`, "Weather").
"What I can do" and the home screen read `SkillHandler.enabledSkillsInfo`, which has no gates.

**Turning them all on.** The `online_skills` skill (`skills/online_skills/`,
`sentences/en/online_skills.yml`, `gar:online` markers, strings in `strings_gar_online.xml` with
the gates' strings) takes "turn on all online skills", "turn off all online skills" and variants
("enable all online skills", "activate the online skills", "switch on all internet skills", "turn
on online features", "turn on the skills that use the internet", "disable online services"). It
switches all of them (every `GarOnlineSkill`) on or off at once, without another question (the
user asked for exactly that), marks them asked and says "All online skills are on. They use the
internet." or "All online skills are off." (since batch 5 the answer names no skills, as the list
grows; the card lists each skill's label and what it contacts). English only, no tool. Like
`SongSkill.score`, `OnlineSkillsSkill.score` keeps a match only when the input names online or
internet and skills, features or services, and asks to turn them on or off like the matched
sentence, with a verb ("turn" or "switch" with "on" or "off" after it, "enable", "activate",
"allow", "disable", "deactivate", "block", "stop"): Dicio's matcher would otherwise run it for
"turn on all the lights", "turn on wifi", "turn off all alarms" or "turn on do not disturb".
Questions and negations are no match either (`OnlineSkillsWords`): Dicio scores "are the online
skills on" (0.80), "don't turn on the online skills" (0.83) and "why are the online skills off"
(0.73) above its thresholds, and running the sweep for them would switch on all five network
skills, or off, and mark them asked for good. So an input with "no", "not", "never" or a word
ending in "n't", or one starting with is, are, which, what, why, how, do, does, did or should
(after "so", "ok", "hey", ...) is no match; "can you", "could you", "will you", "would you" and
"please" are polite requests and still work. A negation also stops "no wait, turn on all online
skills", which is fine for a command that turns on five network skills. On a locked phone the
sweep doesn't turn them on ("Unlock your phone first."), for the same reason as the gates;
turning them off still works. In Settings > Skills the skill is listed as "Turn online skills on
or off", so that its checkbox doesn't read as a main switch for the five skills, which it isn't.

Upstream lines touched: `SkillHandler` (the ranker's skill list is `GarOnlineSkills.rankedSkills`,
and `OnlineSkillsInfo` under `gar:online`), `SkillSettingsViewModel.setSkillEnabled` (one line),
`skill_definitions.yml` (the `gar:online` block). Tests (JVM): `GarOnlineSkillsTest` (never asked:
ask; yes: on, asked, answered by the skill with the same match; no: asked, off, "stays off";
another reply: nothing remembered; asked and off: "is off" without a question; locked: no
question, nothing remembered, and a yes after the phone locked changes nothing; the ranker the
question was asked in; on: no gate; the settings switch's id filter
(`GarOnlineSettings.skillSwitched`, which the view model's line reaches); waiting for the new
ranker; the sweep on and off, and not on while locked; its words, with questions and negations;
the spoken answers, with the unclear ones and other skills' names), `GarRankingTest` (with the online skills off, their
requests go to the gates and, all on, to the skills; the inputs above stay with their skills or
go to the model; the sweep's sentences and polite requests take their inputs and none of "turn
on all the lights", "turn on wifi", "turn off all alarms", "turn on bluetooth", "turn on do not
disturb", "are the online skills on", "don't turn on the online skills", ...), `GarCatalogTest`
(no tools). Not tested: the card and its buttons, the GAR line in
`SkillSettingsViewModel.setSkillEnabled` (Hilt and the view model don't run on the JVM), and
`SkillHandler`'s rebuild on the phone.

## Timers

Timers are the clock app's (Aaron's decision after a week of use, 2026-11-01: "starting a timer
should be a real timer using the clock app"), like GAR's alarms ("Alarm: ringing, change, remove
all"). GAR's own timer engine (an exact alarm, a `systemExempted` ring service, notifications, a
ring screen, a boot receiver) is gone. Dicio's `TimerSkill` keeps its sentences in every language
and the model's `timer_set`, `timer_cancel` and `timer_query` tools, names and slots unchanged; its
body (a marked upstream edit) hands them to `TimerActions` (`skills/timer/TimerActions.kt`), and
so do GAR's timer controls (`timer_control`). `TimerSkill` got an optional fourth constructor
parameter (the actions), so code that builds `TimerSkill(info, data, yesNo)` compiles unchanged.
Strings in `strings_gar_timer.xml`.

What the clock app does (AOSP DeskClock as GrapheneOS ships it, branch 17, `HandleApiCalls`):

- `ACTION_SET_TIMER` with `EXTRA_LENGTH` (seconds; `AlarmClock` says 1 to 86400),
  `EXTRA_MESSAGE` (the label) and `EXTRA_SKIP_UI` starts the timer without showing the clock app.
  It reuses a reset timer of the same length and label.
- `ACTION_DISMISS_TIMER` without a data URI resets or deletes every expired (ringing) timer and
  leaves the others alone; with none ringing it only reports a voice failure. So it is harmless
  when nothing rings, or when an alarm rings instead.
- `ACTION_SHOW_TIMERS` opens the timers tab. There is no intent to pause, resume, cancel a running
  timer, add time or read a timer.
- `HandleApiCalls` is the same `singleInstance` activity as for alarms, started through the alarm
  skill's `ClockApp.start` (`AndroidClockApp`: a missing clock app, a refused start).

Voice:

- **Set** (Dicio's set sentences, named or not, "set a timer" asks how long, and the model's
  `timer_set`): `ACTION_SET_TIMER` with the length rounded to the second (at least one second),
  the name as the label (capitalized, without fillers: "the pasta" is "Pasta"), `SKIP_UI`. Answers
  like Google: "Ten minutes, starting now." and "Pasta timer, ten minutes, starting now." More
  than 24 hours says "Clock timers can't be longer than 24 hours." and sends nothing; no clock app
  says "No clock app found on this phone."
- **Stop a ringing timer** (`TimerWords.whileRinging`, unchanged words: every
  `GarRinging.isStopPhrase`, inputs made of stop words and fillers like "turn it off", "stop the
  timer", "ok thanks", and the name of a timer GAR started that has ended): `ACTION_DISMISS_TIMER`,
  then GAR waits up to 2 s for the ringing to go and says "Timer stopped." or "It's still ringing.
  Turn it off on the clock screen." Nothing ringing: "No timer is ringing." Dicio's cancel
  sentences ("cancel the timer", "stop the timer", "silence the bell") and the model's
  `timer_cancel` do the same while a timer rings, unless they name a timer GAR started that still
  counts (the dismiss would stop the ringing one instead).
- **Show, and what the clock app has no intent for** (pause, resume, add or take away time,
  delete one, cancel all, a cancel while nothing rings, "snooze" while a timer rings):
  `ACTION_SHOW_TIMERS`, with "Here are your timers." for "show my timers" and "I can't change
  Clock's timers by voice, here they are." for the rest.
- **How long is left** (Dicio's query sentences, the model's `timer_query`): answered from the
  timers GAR started (below), as an estimate: "About four minutes left on the pasta timer, unless
  it was changed in Clock." (to the minute, to the second under a minute), or "The pasta timer
  should be done by now." Without a name, the one that ends first. A timer GAR doesn't know opens
  the clock app's timers: "I can't check Clock's timers by voice, here they are."
- **Locked phone** (all three tools are `on_lock_screen: true`): set and stop work as above, since
  they show nothing. What opens the clock app's timers still sends `ACTION_SHOW_TIMERS`, which
  opens behind the lock screen and shows once it is unlocked (like Audile, "Song"), and says so
  instead of "here they are": "Unlock your phone to see your timers.", "I can't change Clock's
  timers by voice. Unlock your phone to see them." and "I can't check Clock's timers by voice.
  Unlock your phone to see them." (`KeyguardManager.isKeyguardLocked`; not checked on the phone).
- **Background**: Android drops an activity start from the background without an error, so before
  every intent GAR checks what the song skill checks (`AndroidAudileLauncher.canStartActivities`:
  GAR on screen or the active assistant). Otherwise it sends nothing, remembers no timer and says
  "I can't reach Clock from the background. Open GAR and ask again." The alarm skill shares
  `ClockApp.start` and doesn't check this yet.
- `timer_control`'s sentences and their checks are unchanged (`TimerWords.isAboutTimer`,
  `saysAll`, `allWhenPlural`); only what they do changed. No "which timer?" question any more: the
  clock app's list is where the user picks.

What GAR remembers (`GarStartedTimers`, `gar/timer/`): the name, length and end (a `Moment`:
elapsedRealtime within a boot, the wall clock across a reboot, as DeskClock counts) of each timer
GAR started, in SharedPreferences `gar_clock_timers`, at most 20, each forgotten an hour after its
end and when GAR stops the ringing timers. They feed "how long is left", the names of
`TimerWords` (`ended()`: "stop the pasta timer"), `isAboutTimer` (`current()`) and
`GarMatchCheck`'s `timerNames` through `GarFallback`. `score()` reads only `GarStartedTimers.peek()`,
never the disk; `App.onCreate` loads them (a GAR line, one small SharedPreferences read), since a
clock app timer rings while GAR's process may be fresh and "stop the pasta" needs the names. Timers set in the clock app by hand are unknown to GAR.

How GAR knows that a timer rings (`FiringTimers` in `ClockApp.kt`, next to `FiringAlarms`, the
same `FiringAlarmCheck` answers and the same notification listener): DeskClock posts its ringing
timers from its foreground `TimerService` (`TimerModel.updateHeadsUpNotification`) on the channel
`firingAlarmsAndTimersNotification`, with a full-screen intent and no category
(`TimerNotificationBuilder.buildHeadsUp`). Its ringing alarm shares that channel but has
`CATEGORY_ALARM`; its running timers (channel `timerNotification`) have `CATEGORY_ALARM` with
neither the full-screen intent nor the foreground service; its other alarm notifications have
`CATEGORY_EVENT`; its stopwatch is no foreground service. So a ringing timer is a notification of
the app that `ACTION_DISMISS_TIMER` reaches with no category and a full-screen intent or the
foreground service flag. Google Clock's channels and categories aren't public and weren't checked:
if its ringing timer carries `CATEGORY_ALARM`, GAR would take it for the alarm. Without
notification access GAR goes by the sound (`ClockApp.isAlarmRinging`) for a stop it was asked
for ("stop the timer"): the dismiss can't harm a ringing alarm, and the sound tells whether it
worked. `TimerControlInfo` registers `FiringTimers` with `GarRinging` under "timer" and
`TimerWords.whileRinging` as what GAR's questions leave to it ("Conversation").

Upgrade: `GarOldTimers.drop`, called from `App.onCreate` (a GAR line), finds
`shared_prefs/gar_timers.xml` and then cancels the old engine's alarm (its `PendingIntent` rebuilt
from the receiver's class name and action, `FLAG_NO_CREATE`), cancels its notifications and
deletes its channels and the preferences. It runs after `App.onCreate`'s channels. If the saved
state still held timers (counting, paused or ringing: cancelled ones were removed), they are lost,
so one notification on Dicio's error report channel says so: "GAR's timers moved to Clock. Your
pasta timer was stopped. Set it again." (or "Your 2 timers were stopped."). The state is read as
plain JSON (`GarOldTimers.lostTimers`), since the engine's classes are gone.

Upstream lines touched: `TimerSkill` (its body and constructor), `App.onCreate` (two lines),
`SkillHandler` and `skill_definitions.yml` (one `gar:timer` block each, last), the manifest
("Manifest permissions": no timer components or permissions any more). Tests (JVM):
`TimerClockTest` with a fake clock app, notifications and clock (the intents, set with its
rounding, 24 hour limit and missing clock app, Dicio's sentences, stop with its wait, still
ringing and nothing ringing, without notification access by the sound, cancel while ringing and
not, what opens the timers, on a locked phone and from the background, how long is left and its
rounding, forgetting started timers, broken state, the old engine's lost timers, DeskClock's ringing timer told from its alarm and running
timers); `TimerControlTest`,
which ranks every English skill with fake timers (the sentences and their rejections, Dicio's
sentences keeping set, cancel and query, a delete without all, the stop and snooze words while
the clock app's timer rings).

Not done: pause, resume, add time and cancel of a running timer by voice (no intent; a
notification action of the clock app could do it, but its actions aren't an API); "how long is
left" for timers set by hand in the clock app; Google Clock's notifications; not checked on the
Pixel yet.

## Conversation

The `conversation` skill (`skills/conversation/`, `sentences/en/conversation.yml`, strings in
`strings_gar_conversation.xml`, a `gar:conversation` block in `SkillHandler` and
`skill_definitions.yml`) is English only, sentence-only and has no tool, so the prompt and the
eval are unchanged. One skill id with two sentences, so one Settings entry ("Stop and repeat"):

- `stop`: "stop", "stop it/that/talking/speaking", "shut up", "be quiet", "quiet", "silence",
  "hush", "enough", "that's enough", "cancel (it/that)", "never mind", "forget it/that/about it",
  "that's it/all", "that'll be all", "I'm/we're/all done", and the declines that answer "What can
  I do for you?": "no thanks", "no thank you", "nope", "nah", "nothing", "I'm good", "not (right)
  now". The ending phrases may have "thanks" or "thank you" before or after them. The declines
  that start with "no" or end with "now", which `conversationWords` drops, are compared on the
  words as said (`ConversationWords.DECLINE_PHRASES`).
- `repeat`: "repeat that/it/yourself (again)", "say (that/it) again", "say that/it one more
  time", "what did you (just) say", "what was that (again)", "come again", "pardon (me)", "I
  didn't/did not hear/catch/get that/you/it", "I couldn't hear that/you/it", "I can't/cannot hear
  you", "I missed that", "repeat what you (just) said", "can/could/would you repeat". A bare
  "repeat" is left out, since media's repeat mode may want it, and so are a bare "one more time"
  (a song) and "I can't hear it" (the music is too quiet, more likely).

Every line allows a filler before (ok, okay, oh, hey, no, please, sorry, alright), then
"can/could/would you", and "please" or "now" after (`conversationWords`, which drops several
leading fillers, the hesitations "just", "uh" and "um" anywhere, and a word said twice: "ok
please stop", "uh stop", "stop stop"). Like `SongSkill.score`,
`ConversationSkill.score` keeps a match only for an input that is one of these phrases
(`ConversationWords`), because Dicio's matcher leaves a word out ("stop the music" ~ "stop it",
"say that again in french").

Stop stops TTS (a typed "stop"; listening has already stopped it), says nothing and shows "OK" on
its card. Its plan is `FinishInteraction`: as a default-batch match it drops pending questions
and the microphone stays closed. GAR's own questions still take "cancel", "never mind", "stop" and
"forget it" first (`GarCancelSkill`, which says "OK."); other questions' cancels reach the stop
skill. One exception: a `GarRinging` stop phrase ("stop", "shut up") while nothing is registered
as ringing but an alarm sounds (`ClockApp.isAlarmRinging`, so the clock app's alarm without
notification access: a clock app timer or alarm that GAR sees would have taken the input)
answers with the alarm's
Ringing card, "An alarm is ringing. Turn it off on the clock screen.", with the "Allow
notification access" button, instead of a silent "OK" while it goes on ringing. It sends no
intent. The match carries whether the input was such a phrase (`ConversationMatch.silences`),
since the output doesn't see the input.

Repeat says `ctx.previousOutput`'s speech again; after "stop" it repeats the answer that stop
interrupted (`ConversationStopOutput.before`), and with nothing said yet it says "I haven't said
anything yet." Its match keeps the pending batches (`GarConversationSkill.keepsQuestion`, read by
a GAR line in `SkillRanker`), and its plan is `Continue` with the previous answer's
`reopenMicrophone`, so a repeated question can still be answered. Answers spoken later outside the
evaluator (song results, Audile) aren't repeated, and the card shows the text, not the original
card.

`GarRinging` (`gar/conversation/`) is how the conversation knows that something rings. The timer
and the alarm register providers by skill id ("timer", "alarm"); a provider must answer at once,
on any thread, and one that throws counts as not ringing. While one rings, the words that stop it
(`GarRinging.isStopPhrase`: stop, stop it, stop that, shut up, be quiet, keep quiet, quiet,
silence, hush, enough, that's enough, with the same fillers) are left to its skill in the default
batch: `ConversationSkill`, `GarCancelSkill` and `GarAnswerSkill` score them worst. The
conversation also leaves "cancel (it/that)" (`GarRinging.isCancelWhileRinging`), which the
ringing timer and the clock app's alarm take, while a pending GAR question still takes it.
"Never mind", "that's all" and "stop talking" stay with GAR. The ringing skills take every stop
phrase and may take more (see "Stop: who takes it"): they register what they take
(`GarRinging.registerCommands`: `TimerWords.whileRinging`, `AlarmSkill.isRingingCommand`), and
`GarAnswerSkill` leaves all of it to them (`GarRinging.takes`), so "turn it off", "dismiss" or
"snooze" while a timer rings is no reminder's text or event title, and no answer to a question
of options.

Pending questions and new commands (`GarFollowUps`, `gar/conversation/`):

- Before: after "Which day in December?", "what is the score of the 49ers game" matched the
  calculator, `GarMatchCheck` rejected it, and the model called no tool. The text fallback asked
  to repeat with the question still pending, and the next commands went to the question. Now the
  rejected match ends the question ("Data flow"); the model and the text fallback still run, but
  the conversation starts fresh.
- An answer skill that takes any words (`GarOpenAnswer`: `GarAnswerSkill` for a
  `GarQuestion.Value` of a TEXT or CONTACT slot, built as `GarOpenAnswerSkill` by
  `GarAnswerSkill.forQuestion`) gives way to Dicio's default batch when that batch matches the
  input as a whole sentence (`scoreIn01Range() >= 0.95`: typed punctuation costs about 0.02 a
  mark, a word left out of a sentence of up to ten words, or one added, scores less).
- Contacts, places ("where") and calculations give way to any such command: "call mom" or "what
  time is it" in reply to "Who?". A rejected one (the 49ers question) is no match, so the
  question lets go and the model gets the input.
- Content slots (a reminder's text, an event's title) give way only to the conversation skill
  ("be quiet", "repeat that"), because "call mom" is a fine reminder, and to what a ringing
  timer or alarm takes (above). Such an answer, like a text's or a reply's message, is what the
  user said, with no correction detection ("no no no send a text aaron" is the message): GAR reads
  it back and asks before sending (Aaron's decision, 2026-10-27; "Contacts: myself, no match,
  corrections").
- APP slots aren't open: they resolve against the launcher labels. Answers that resolve ("the
  fifth", "5", "seven pm", "mom", "the airport") are taken as before.

Upstream lines touched: `SkillRanker.getBest` (an import, the open-answer check in the batch loop,
and the default batch's part moved into `takeDefault` with the `keepsBatches` check),
`SkillHandler` and `skill_definitions.yml` (one `gar:conversation` block each, after
`gar:online`: a skill that scores worst for most inputs changes Dicio's winners when it comes
first, see `GarOnlineSkills.forRanking`). Tests (JVM): `ConversationSkillTest` (the phrases and
non-phrases, whole-sentence scores, the ringing deferral, the stop and repeat outputs and plans,
every phrase winning over all English skills, other skills keeping "stop the timer", "stop
listening", "stop the stopwatch", "cancel my alarm", "pause the music", the Ringing card for an
alarm GAR can't see, the `GarRinging` registry); `GarConversationTest` (the real skills, the real
catalog's match check and a model that calls nothing: the 49ers bug and the December answers,
the conversation while GAR asks, "Who?", "Where to?" and a reminder's text, ringing deferral of
the stop phrases and of what the ringing skill takes, and the ranker rules on fake skills);
`GarFallbackTest` (a rejected match ends the question).

Not done: "that's all" can't close an assistant overlay
(there is none yet); Dicio's own free-text follow-ups (search's "What do you want to search?")
and the sms flow's text questions don't give way to commands, which would need upstream edits;
after a question is let go, the text fallback still says "Could you repeat?" for a command the
model can't do. Upstream quirk: `AndroidTtsSpeechDevice.stop()` doesn't run the
`runWhenFinishedSpeaking` runnables, so a "reopen the microphone" from an interrupted question
can fire after a later answer.

## Stop: who takes it

"Stop" means what is going on, in this order: the clock app's ringing timer, then its ringing
alarm, then media that plays, then GAR's own speech and conversation. Each skill decides in its
own `score`, all in Dicio's default batch:

1. **The clock app's ringing timer.** While `FiringTimers` sees it ("Timers"),
   `TimerControlSkill` takes its stop words with `AlwaysBestScore`, which no `FloatScore` beats,
   wherever the skill sits in `SkillHandler`, and sends `ACTION_DISMISS_TIMER`. So when a timer
   and the alarm ring together, "stop" stops the timers and a second "stop" the alarm, and
   "snooze" is the timer's (it opens the clock app's timers: no intent adds time; before, the
   alarm's snooze sentence took it and said that no alarm rang). DeskClock stops ringing a timer
   after a while and its notification goes, so it doesn't take the next morning's "stop" from
   the alarm. An input that says "alarm" and no timer word
   or ended timer's name ("stop the alarm", "turn off the alarm", "snooze the alarm") is the
   alarm's while the clock app's alarm rings too (`TimerWords.whileRinging`'s `alarmRinging`,
   read from `GarRinging`). With only a timer ringing it stops the timer, since people call a
   ringing timer an alarm. (Integration fix: without that check the timer took "stop the alarm"
   while both rang.)
2. **The clock app's ringing alarm.** `AlarmSkill` takes its stop sentences (1.0, HIGH) only while
   `FiringAlarms` sees DeskClock's ringing-alarm notification, and "snooze" always ("Alarm:
   ringing, change, remove all").
3. **Media that plays** (PARITY batch 4). `MediaControlsSkill` takes the
   `GarRinging.isStopPhrase` inputs ("stop", "stop it", "shut up", "be quiet", ...) plus "pause",
   "pause it/this/that" with `AlwaysBestScore` when nothing rings (`GarRinging.isRinging` false, so
   the clock app's ringing timer and alarm keep them) and a media session other than GAR's
   is in `STATE_PLAYING` (`GarMediaSessions.isPlaying`, through `NotifyHandler.Instance`; false
   without notification access and in JVM tests). It pauses every playing session (pause, not
   `TransportControls.stop`, which drops some apps' queue; Google pauses too), stops GAR's speech
   (for a typed "stop"), says nothing and shows "Paused." on the card. A pending GAR question
   still takes "stop" first (its batch ranks before the default batch); "never mind", "repeat
   that" and "stop talking" stay with the conversation. `GarStopRankingTest` is unchanged
   (nothing plays in tests); `MediaRankingTest` covers playing and ringing with a skill whose
   `isPlaying` and `isRinging` are fixed ("Media: play by name, controls, sleep timer").
4. **GAR.** While `GarRinging.isRinging()`, `ConversationSkill`, `GarCancelSkill` and
   `GarAnswerSkill` leave every `GarRinging.isStopPhrase` input to what rings, the conversation
   also "cancel", and `GarAnswerSkill` also the other commands the ringing skill registered.
   Otherwise a pending GAR question takes "stop" first (`GarCancelSkill`), then the conversation
   skill. Without notification access the clock app's timer and alarm register as not ringing,
   and the conversation's "stop" shows the alarm's Ringing card when an alarm or timer sounds
   ("Conversation"); "stop the timer" still dismisses the timer, checked by the sound ("Timers").

"Never mind", "that's all" and "repeat that" stay with GAR's conversation while anything rings.
"Cancel" stops the clock app's ringing timer (one of its stop words) and the clock app's ringing alarm (one
of its stop sentences): the conversation leaves it to them while something rings. A pending GAR
question still takes it, to cancel the question. If the conversation had kept "cancel", the alarm
would have won the tie anyway: in `SkillRanker` a later skill must score strictly better, and
`gar:alarm` comes before `gar:conversation` in `SkillHandler`. "Dismiss", "turn it off" and "i'm
up" are neither the timer's nor the alarm's when nothing rings. `GarStopRankingTest` ranks every
English skill for these inputs with nothing, a timer, the alarm and both ringing, with a pending
GAR question, and through the match check, plus inputs that two of the batch's groups could both
take: "add five minutes" (the timer controls, not units), "how much time is left on the timer"
(Dicio's timer, not the holidays' countdowns), "cancel all alarms" (the alarm, not the timer
controls), "thank you" and "ok thanks" (small talk) against "thanks, that's all" and "no thanks"
(the conversation), and "what time is it in tokyo" (the world clock) against "what time is
it".

"Cancel it" after a modifiable turn (PARITY batch 7, "Conversation memory"):

| Input | When | Who |
|---|---|---|
| "cancel it", "delete it", "turn it off" | the newest turn is an alarm, timer or stopwatch answered within 60 s, nothing was answered after it (small talk, the text fallback, an error; "repeat that" aside), the turn didn't start while GAR was speaking, nothing rings, no call | the follow-up skill (AlwaysBestScore): "cancel my 7 am alarm", "cancel the pasta timer"; in a continued turn it asks first ("Cancel your 7 am alarm?"). In every other case "cancel it" stays the conversation's stop. A ringing alarm or timer and media while playing still win (always-best skills later in the list). |

## Conversation memory

PARITY batch 7. "Turn it off", "what about tomorrow", "call her", "make it 10": a command the
user didn't have to say in full. Follow-ups are a Kotlin layer in front of Dicio's matching. The
model's prompt, tools.yml, the prompt formats and the grammar are unchanged (the router's prompt
goldens are untouched). English only.

### Shape
- `GarFollowUpSkill` (id `follow_up`, Settings "Follow-ups", default on) is the first entry of
  `SkillHandler.allSkillInfoList`, in a `gar:followup` block. It has no sentences and no
  skill_definitions.yml entry.
- Its `score`:
  - parses the input with `FollowUpForms.parse` (:gar:router). The whole input, once its fillers
    are gone, must be one form.
  - asks the remembered turns' adapters, through `GarFollowUpReach`, for a rewrite into the full
    command ("turn off the flashlight").
  - scores `FloatScore(0.94)`. Dicio's `getBestForSpecificity` keeps the first best and replaces
    it only with a better score. So any later whole sentence (>= `GarFollowUps.COMMAND_SCORE`,
    0.95) of a HIGH skill wins over it, and so does any later `AlwaysBestScore`. It still passes
    `HIGH_THRESHOLD_1` (0.85). That keeps the pronoun phrases skills already read themselves:
    `CallBack`, `MessageWords.meansLastRead`, media's "play it again", call_control's "send it
    to voicemail", a ringing timer, the conversation's "cancel it".
  - Telephone and sms are LOW, so "call her" and "text her" are the follow-up skill's.
- `garMatchUtterances` then:
  - Rewritten: drops every pending batch, sets `GarFollowUpSkill.rewritePass` (the follow-up,
    answer and confirmation skills score worst) and matches the rewrite once more through the
    normal path: sentences, GarMatchCheck, the online gate, the model, lock checks and
    confirmations. The input as said stays the chosen input. A rewrite nothing takes falls back
    to the text fallback (`unmatched`).
  - Direct: returns the target's own match.
  - Anything else (ask, say, confirm, locked): the follow-up skill answers.
- GarMatchCheck isn't asked about a follow-up match; it checks the rewrite. A follow-up never
  does more than the full command would.
- `score` is cheap: a parse, a walk over three turns, string building.

### Forms (`FollowUpForms`)
- Fillers dropped:
  - leading: ok, okay, oh, hey, please, just, alright, so, well, sorry, "can/could/would you",
    "never mind", "actually"
  - anywhere: uh, um
  - trailing: please, now, thanks, "thank you" (Whisper's "Turn it back off. Thank you.")
- Relative days (`FollowUpForms.relativeDays`, 2026-11-02): a WhatAbout's rest "the next day",
  "the day after (that)", "the following day" is +1 day, "the day before (that)", "the previous
  day" -1. A bare "and" takes one too ("and the day after"); "day" is no day word, so "and what
  day is it" is no form. "The day after tomorrow" names its own day. The holiday adapter writes
  no "when is" for one ("and the day after" after "when is thanksgiving").
  - a leading "no" or "nope" when the rest is a form other than a correction: "no, turn it back
    off" is Switch(off, back); "no 8 pm" stays a correction
- Pronouns: it, that, this, them for things; her, him, them for the remembered person; there
  for the place.
- Forms: Switch, Adjust, Remove, Snooze, Control, SetTo, AddMore, WhatAbout, AboutPerson,
  SendTo, AboutPlace, AboutSong, Another, Correction.
- "Turn it back up/down" is Adjust; "turn it back on/off" and "turn it off again" are
  Switch(back).
- Refused by design:
  - full commands
  - a bare "and" without a preposition or a day, holiday or "timer" word ("and denver", "and
    call mom")
  - "what/how about" with more than four words, a command verb, or only a pronoun ("what about
    you", "what about us")
  - a bare "no" or "no no", and the declines ("no thanks", "no don't", "no it's fine", "no
    that's all", "no that's okay")
  - corrections about the conversation: a value with wait, sorry, hold, hang, wrong, right,
    okay, ok, that's, that, it or mistake ("no wait", "no that's wrong", "I meant sorry")
  - "send it to voicemail" (call_control's)
  - "undo that", "put it back", "turn it back" (open question 5)
  - song titles ("another one bites the dust", "one more time")

### Memory (`GarConversationMemory`)
- RAM only, newest first, at most 3 turns of the last 5 minutes.
- `SkillEvaluator` brackets each turn:
  - `awaitSettled(utterances)` before the match lock. It waits, at most 10 s, only when the
    first alternative parses as a form and an earlier turn is still answering.
  - `begin(element, continuesStep)` under the lock, after matching. `continuesStep` is whether
    the ranker kept its batches, i.e. a pending question's batch matched; otherwise the questions
    of a step the user left ("Call Mom?" never answered) are forgotten.
  - `finish(token, answered, lockedNow)` in a finally after the output, plan and speech.
- A token never finished counts as in flight for 30 s. While a turn is in flight, a form asks
  instead of guessing ("Turn what off?"), or scores worst.
- Only an output that finishes a step is remembered (a plan that doesn't reopen the mic).
  Questions asked during the step are kept for the adapters ("Call Erin?", the sms card). A call
  takes the name of the "Call X?" whose number was called, whichever skill asked it (call_log's
  call back asks, the telephone's yes answers), else the number.
- The memory also knows whether its newest turn is the latest answer
  (`GarMemoryView.newestIsLatestAnswer`): every finished turn counts, remembered or not, except
  "repeat that". "Cancel it" needs it.
- Never remembered:
  - errors and the permissions card
  - `GarLockedOutput` and `GarCancelledOutput`
  - any `GarEndsConversation`
  - the conversation skill, small talk and the text fallback
  - the follow-up skill's own outputs
- A skill without an adapter leaves a bare turn (no facts; it ends the topic). An adapter
  returning null leaves the memory as it was.
- Model calls: `GarCallRunner.run` sets `GarTurnElement.call`, so a confirmed call is remembered
  by its slots, not by "yes".
- Outputs wrapped in `GarFollowUpOutput` are unwrapped before an adapter sees them.
- Cleared:
  - on the wake word and the assist gesture over a locked phone (`forgetInteractions`, which
    calls `GarConversationMemory.forget()`)
  - whenever `SkillHandler` rebuilds its skills (a change of language or of the enabled skills;
    `GarConversationMemory.enabled` is set there)
- "Stop", "never mind" and "repeat that" leave it alone.
- Per-turn lock filter: turns answered while unlocked are invisible while locked.

### Reach (`GarFollowUpReach`)
| Form | Reaches | Age |
|---|---|---|
| Remove, cancelling Switch(off) | the newest MODIFIABLE turn, when it is also the latest answer | 60 s |
| SetTo, AddMore, Control | the newest MODIFIABLE turn | 5 min |
| Switch, Adjust, SetTo on a setting | the newest REVERSIBLE turn, walking back over plain answers; the walk stops at a turn of media_play, media_controls, song, alarm, timer, timer_control or stopwatch (their own off), and at the newest REVERSIBLE turn even when it can't take the form ("turn it off" after the brightness was set asks) | 5 min |
| WhatAbout, Another (repeatable adapters only), AboutSong | the newest turn | 5 min |
| Correction | the newest turn | 60 s |
| AboutPerson | the newest person fact | 2 min |
| AboutPlace | the newest place fact | 5 min |
| SendTo | the newest turn's own content | 5 min |

When nothing fits, GAR asks:
- "Turn what off/on/up/down?"
- "Change what?", "Add time to what?"
- "Who should I call/text/email?", "Whose number/address?", "Where to?"

The answer skill is a `GarOpenAnswer` that gives way to any whole default-batch sentence ("what
time is it" after "Turn what off?" is the clock's). Its answer fills the template ("turn off
{answer}"). Otherwise the form scores worst (deferred).

Deferrals (worst score):
- `GarRinging.takes`
- a call ringing, waiting or on (`PhoneCallState` UNKNOWN, without READ_PHONE_STATE, doesn't
  defer)
- media playing: Control, AboutSong(PLAY_AGAIN) and Another, unless the newest turn (60 s) is
  the stopwatch, a timer or the song
- AboutPerson with "back" (call_log's `CallBack`, `MessageWords.meansLastRead`)
- the rewrite pass

Adjust without a kind while media plays becomes "turn the volume up/down" (a rewrite, not a
deferral), unless the brightness moved in the last 60 s. Adjust with a kind ("louder",
"brighter") needs no memory. Switch(off) while media plays and nothing remembered takes it
becomes "pause the music" instead of "Turn what off?"; a remembered setting still comes first.

### Continued turns and barge-ins
- "cancel it", "delete it" and a cancelling "turn it off" on a MODIFIABLE turn of the last 60 s,
  SetTo, AddMore and Correction on a MODIFIABLE turn, and every SendTo, become
  `GarFollowUpConfirmOutput` ("Cancel your 7 am alarm?", yes/no and buttons). The window may
  have heard the TV; "no" or "stop" ends it. The cancelling ones still win over the
  conversation's "cancel it" (AlwaysBestScore). Control ("pause it") isn't confirmed.
- A barge-in (the capture started while GAR was talking): "cancel it" is the conversation's
  stop.
- Calls and texts confirm through their target as always.

### Lock screen
- A rewrite runs locked only when its adapter's `onLockScreen` is true. Device, Clock and Answer
  are on-lock; People and Place are not. Otherwise the answer is `GarFollowUpLockedOutput`
  ("Unlock your phone first.", a `GarEndsConversation`).
- The rewritten command then meets its target's own lock checks.

### Values in text (`GarRewriteText.safe`)
- A remembered value goes into a rewrite only without a comma, a digit inside a name, or a slot
  word (to, and, saying, that, at, in, on, for, by, with, from, then).
- Otherwise it goes to Direct or a question ("Ben and Jerry", "meet me at 5 to talk",
  "Starbucks, 5th and Pine"). A sent text that can't go into "send it to mom too" becomes "send
  a text to mom", and the sms skill asks "What's the message?".

### Adapters
| Adapter | Skills | Kind | Rewrites |
|---|---|---|---|
| `DeviceFollowUps` | flashlight, connectivity, do_not_disturb, device_settings, volume | REVERSIBLE | switch, adjust, set: "turn off the flashlight", "turn on wifi", "turn off dark mode", "turn down the brightness", "set the brightness to 50 percent", "turn the ring volume up", "mute the volume". A volume question ("what's the volume", a new `volume_set` sentence without a level) is remembered too, so "turn it up" after it turns the volume up. |
| `ClockFollowUps` | alarm, timer, timer_control, stopwatch | MODIFIABLE | "cancel my 7 am alarm", "change my 7 am alarm to 8 am on weekdays" (days kept; the change drops the label, as the full command does), "cancel the pasta timer" / "cancel the 8 minute timer", "add 5 minutes to the timer", "pause the pasta timer", "how much time is left on the pasta timer", "stop the stopwatch". A control of an unnamed timer (also the rewrite of "make it 10") is remembered as "the timer", so "cancel it" after it is "cancel the timer". "Make it 10" within 15 s of "set a timer for 5 minutes" becomes "add 5 minutes to the timer" ("make it 3": "take 2 minutes off the timer"): the clock app has no intent that sets a running timer's length, and later the time already run would make the difference wrong, so GAR asks "Change what?". A bare number counts in minutes, and only after a timer of minutes; the result is at most 24 hours. |
| `AnswerFollowUps` | weather, current_time, world_time, date, holiday, units, currency, translation | ANSWER | weather keeps its topic and changes day and/or place ("what's the weather tomorrow in denver", "will it rain tomorrow in austin"; a correction's bare name is a place: "no, boston"); relative days count from the remembered day (now is today, a night its day): "what's the weather like tomorrow", then "what about the next day?" is "what's the weather on tuesday" (2026-11-02: it went to the model, which said the date); none after a weekend or a week, or for a day the sentences can't name (past, a week away); "next week" and "next weekend" are written so that they read back as the same days ("this week" before); "what time is it in london"; "when is easter" |
| `RepeatFollowUps` | random, joke | ANSWER, repeatable | "tell me a joke", "roll 2d6", "flip a coin", "pick a random number between 1 and 100" |
| `PeopleFollowUps` | telephone, contact_info, sms | ANSWER | the person (unique only when no chooser asked and the name is safe) and the user's own sent text; "I said erin" -> "call erin"; "send it to mom too" -> "send a text to mom saying i'm late"; "call her" after a sent text |
| `PlaceFollowUps`, `MediaFollowUps`, `NotesFollowUps` | | | phase 2: turns without facts |

Entity forms (`GarEntityCommands`):
- "call erin", "send a text to erin", "send an email to erin", "what's erin's number", "what's
  erin's address"
- "what time is it in paris", "what's the weather in paris", "navigate to paris"

Incoming message bodies are never facts, so "send it to mom" after "read my messages" isn't
rewritten. `GarFollowUpContext.skill(id)` returns null in phase 1, so no adapter answers with
Direct yet: two Erins, or a name with slot words, get a question.

### Tests
`FollowUpFormsTest` (:gar:router), `GarConversationMemoryTest`, the adapter tests with
adversarial fixtures (every Command rewrite passes `shouldBeTakenBy`: a whole sentence of the
target, accepted by GarMatchCheck, parsing back to the remembered slots),
`GarFollowUpRankingTest` (the real order, a pronoun corpus, every AlwaysBest skill),
`GarFollowUpReplayTest`, `GarFollowUpConcurrencyTest` (a latch-held "turn on wifi", then "turn it
off") and `GarFollowUpChatReplayTest`, which replays whole conversations through
`garMatchUtterances` and the memory as the evaluator brackets them: the flashlight and "turn it
back off", a timer and "make it 10", the weather and "what about friday" and "and in denver",
"text mom saying hi" (the real sms flow, sent) and "call her", "what's the volume" and "turn it
up", "flip a coin" and "another one", unrelated commands after a follow-up (no stale rewrite),
"turn it off" with nothing remembered (asks), "turn it off again" and "no, turn it back off",
"make it 10" then "cancel it", bluetooth then music then "turn it off" (pauses the music),
bluetooth then the brightness then "turn it off" (asks), a continued "cancel it" or "turn it
off" after an alarm (confirmed), small talk between an alarm and "cancel it" (the
conversation's), and "no wait" after the weather (no form). `GarFollowUpReplayTest` also has an
unanswered "Call Mom?" before call_log's "call him back" and "yes" ("text her" texts Erin).

### Voice log
`follow_up`: {form, turn_skill, turn_age_ms, rewrite, outcome, deferred_to}.
- Outcomes: rewritten, direct, said, asked (rewrite = the template), confirm (rewrite = the
  confirmed command), locked, declined, unmatched, deferred.
- `deferred_to` is one of: ringing, call, media, back, reach, in_flight.

## Continued conversation

After a spoken answer has been said, the microphone stays open for a follow-up ("what about
tomorrow", "turn it back off"). Silence ends it with no sound and no turn. Constants are in
`GarContinuedListening`:
- `WINDOW_MILLIS = 7_000`: time from opening to the start of speech. After that, normal
  end-pointing.
- `MIN_SPEECH_MILLIS = 300`: less voiced audio than this is silence, and Whisper never runs on
  it.
- `MAX_SPEECH_MILLIS = 6_000`: a window's capture ends after 6 s of speech.

**When a window opens.** `GarContinuedListening.decide(GarWindowFacts)` is a pure function
(`reason` names the first condition that fails). All of these must hold:
1. "Keep listening after answers" is on (`continued_listening_off = 50`, stored negated, so it
   defaults to on; open question 2 asks whether to default it off).
2. The turn's input was spoken (`GarTurnElement.mode`).
3. The plan didn't reopen the mic itself.
4. The answer was actually said (not blank, not shown only).
5. The output doesn't end the conversation (`endsWindow`): not `GarEndsConversation`
   (conversation stop, small talk, locked outputs), not `ErrorSkillOutput`, not
   `MissingPermissionsSkillOutput`, not `TextFallbackOutput`.
6. The answering skill is in `OPENS_AFTER`: weather, current_time, world_time, date, holiday,
   units, currency, translation, offline_translator, random, joke, knowledge, dictionary,
   calculator, math, battery, spelling, flashlight, connectivity, do_not_disturb,
   device_settings, volume, alarm, timer, timer_control, stopwatch, calendar, agenda,
   contact_info, follow_up. Excluded: telephone, sms, messages, email, call_log, notes, lists,
   reminders, share_location, navigation, open, media, song, camera, search, small_talk.
7. GAR is on screen (`MainActivity.isInForeground > 0`).
8. The phone is unlocked, or was already locked when the turn started.
9. Nothing rings (`GarRinging`), and there is no call: audio mode in call, in communication or
   ringtone, or `PhoneCallState` not IDLE.
10. No other app plays out loud (`speakerMusic`): a playing local session counts unless every
    output media is routed to (`getAudioDevicesForAttributes(USAGE_MEDIA)` on API 33, the
    connected outputs before) is a wired, USB or hearing-aid headset, or a Bluetooth device whose
    class says headphones or a headset (a Bluetooth speaker or car counts as out loud, and so does
    any Bluetooth device without the Bluetooth permission). Without notification access the
    sessions can't be seen, and `AudioManager.isMusicActive` under the same output rule stands
    in.
11. Whisper is the input device (`hasBriefListening`). Vosk and the popup get no window.
12. STT isn't already listening or transcribing.

**Never during GAR's own speech.**
- `AndroidTtsSpeechDevice` keeps a `GarUtteranceTracker`. `runWhenFinishedSpeaking` queues the
  runnable while the engine is speaking or any queued utterance id has not finished;
  `TextToSpeech.isSpeaking` is often still false right after `speak`.
- `onDone`, `onError` and `onStop` mark an id finished. When none is left, the runnables run, so
  a question still gets its mic after a TTS error.
- `stopSpeaking` marks everything finished and clears the runnables, so they never fire after a
  later utterance. `isSpeaking` also reports queued utterances.
- `SpeechOutputDeviceWrapper.speak` calls `GarContinuedListening.onSpeak()`, and the evaluator
  calls `onInput()` for every Final and in `forgetInteractions`. Both bump a sequence number and
  close an open window.
- A window is scheduled with the sequence number of the moment after the answer's speech, and
  does nothing if the number changed (newer speech, newer input, stop). While it is open, the
  screen turning off (`ACTION_SCREEN_OFF` receiver, registered only then) or new speech closes
  it through `SttInputDeviceWrapper.stopBriefListening`. A window whose speech is already being
  transcribed is left alone.

**What a window hears.**
- Less than 300 ms of speech, no speech within 7 s, or a cancelled window: no turn.
- `GarWhisperHallucinations.isSilence(locale, text)` is checked on the spoken and the written
  text, in windows only:
  - English exact phrases: "thank you", "thanks for watching", "thank you for watching", "you",
    "bye", "music", "blank audio".
  - Text starting with: untertitel, "vielen dank fürs zuschauen", sous-titres, "merci d'avoir
    regardé", subtítulos, "gracias por ver", sottotitoli, legendas, ondertiteling.
  - In every language: only tags and symbols ("[Music]", "(laughs)", "♪").
- Such windows write no voice-log turn. They are counted as `continued_silent` /
  `continued_hallucination` on the next turn. A real "thank you" in a window is one of them:
  Whisper writes it on silence too often to tell them apart, and the window ends either way.
- A real follow-up is a turn with `GarTurnElement.continued = true`, logged with `start:
  continued`.

**Ending words.**
- English: in a continued turn the conversation skill also takes a bare "thanks" / "thanks a
  lot" (`GarContinuedListening.matchingContinued`, set by the evaluator while it matches). It
  ends with the silent "OK" instead of small talk's "You're welcome!". A bare "thank you" never
  gets there: the window drops it as a hallucination (above).
- Other languages: `GarContinuedListening.endsConversation(locale, text)` (de danke, danke
  schön, das war's, nichts; fr merci, c'est tout, rien; es gracias, eso es todo, nada; it
  grazie, è tutto, niente; pt obrigado, obrigada, é tudo, nada; nl dank je, bedankt, dat was
  het, niets). The evaluator answers with `ConversationStopOutput` without matching.
- In a continued turn the text fallback is shown on the card only: not said, no reopened mic, no
  window.

**Indicator.** `WhisperState.Listening` has `brief` and `endsAt` (System.nanoTime in ms),
exposed as `SttInputDeviceWrapper.briefWindowEndsAt`. The capture's no-speech budget is what
is left until `endsAt` when it starts recording, so the mic never listens after the arc is
empty. The mic button reads "Listening for a
follow-up" (a GAR line in `ui/home/SttButton.kt`), and a thin arc around the mic empties over
7 s (`garLevelRing`). Windows have no start earcon and no no-input earcon. A tap on the mic ends
the window and transcribes whatever was heard.

## Speaking vs showing

- `InputEvent.Final.spoken` is true from Whisper, Vosk and other apps' recognizers
  (`garExternalFinal`). Typed text and card buttons keep false.
- The evaluator wraps each Final's coroutine in a `GarTurnElement` holding: the mode, continued,
  `startedWhileSpeaking` (HomeScreenViewModel calls `markCaptureStart(isSpeaking)` when STT
  starts listening, and the next spoken turn takes it), `lockedAtStart`, and `call` (set by
  `GarCallRunner`).
- Spoken input: the answer is said on the user's output device.
- Typed input and buttons: the card shows it; nothing is spoken, toasted or put in a snackbar.
  "Speak answers to typed requests" (`speak_typed_answers = 51`, default off) says them too.
- A question after typed input never reopens the mic, and neither does a button after a spoken
  question.
- There is no global gate. Every producer of speech calls `GarSpeechMode.speak(device, mode,
  text)` with its own turn's mode: the evaluator, and SongSkill's two late producers (the
  recording's result and Audile's "That's ..."), which capture `GarTurnElement.current()?.mode`
  in `generateOutput`. A later turn of the other mode changes nothing. `GarSpeechModeTest`
  checks that no source calls `speechOutputDevice.speak` directly. `stopSpeaking` is never
  gated.

Voice log additions for both: `start: "continued"`, `answer.spoken` (false when the answer was
shown only), `continued_silent`, `continued_hallucination`, the `follow_up` object above, and
four suspect signals: `follow_up_asked` and `follow_up_unmatched` on the turn itself;
`follow_up_corrected` on a rewritten or direct turn when the next turn is a correction form or
says no, cancel, that's wrong, or "I said"; `follow_up_confirm_declined` on a confirm turn when
the next turn says no, cancel or stop. `gar/tools/voice-log.py` shows `follow_up:<outcome>
<path>` in the route column, `heard => rewrite`, and `(shown)` on answers that were shown only.
Its summary counts follow-up outcomes, answers shown only, and windows without a turn.

## Everyday offline answers

Eight English-only skills for PARITY batch 2, no tools, registered under one `gar:answers` marker
in `SkillHandler`, `skill_definitions.yml` and `GarRankingTest`; strings in
`strings_gar_answers.xml`. Each has HIGH specificity, and its score is `AlwaysWorstScore` unless
its own Kotlin check accepts the input, because Dicio's matcher accepts a sentence with a word
left out. None has tools, so `GarMatchCheck` never rejects them (`keywordsFor` is null).

- **battery** (`skills/battery/`). Sentences level, charging, charge_time. Reads the sticky
  `ACTION_BATTERY_CHANGED` and `BatteryManager` (capacity, `isCharging`,
  `computeChargeTimeRemaining`); no permission, fine on the lock screen. `BatteryAnswer.of`
  picks the answer: level, level and charging, full, "plugged in but not charging" (adaptive
  charging), not charging, time to full, or no estimate. "Is my phone charged", "is it (fully)
  charged", a bare "battery" and "how long will my battery last" are level questions, answered
  "Your battery is full." or with the level (a charging-state answer would say yes to "is it
  charged" at 76 percent). Guard `BatteryWords`: the input names
  battery, charge, charging, charger, plugged or power (or percent with "phone") and nothing
  else; settings (saver, mode, settings, usage, fast, wireless), commands (turn, switch, open,
  off) and other devices (watch, earbuds, ...) are left out, so "turn on battery saver" stays
  with the device-settings batch. The level sentence also takes "where|where's|where is|wheres
  my|the|(my|the) phone's battery (level|percentage|...)?", which is how Vosk hears "what's my
  battery". It used to win as the navigation sentence "where is .where." ("Navigating to my
  battery"; navigation below).
- **world_time** (`skills/world_time/`). `WorldPlaces` maps places to zones: every IANA zone's
  city (areas Africa through Pacific, without abbreviations of 3 letters or fewer and
  north/south/east/west), US time zone names and abbreviations, utc and gmt, all US states,
  Canadian provinces, about 70 countries and big cities without a zone of their own, and what
  people call places (LA, Vegas, Philly, Maui, Kauai, Oahu, Bali, Cabo, "jersey" for New Jersey).
  Hand entries win over the IANA cities where the IANA one is the less likely place: San Juan is
  Puerto Rico's, Samoa the country (Pacific/Apia, not the Pacific/Samoa link, which is American
  Samoa, a day apart). A multi-zone country takes its main zone and names it ("Sydney,
  Australia"); "washington" alone is D.C.; a city with a US state or Canadian province uses the
  state's zone ("portland maine", "paris texas", "seattle washington", where "washington" is the
  state), except Georgia for its own cities ("tbilisi georgia"); a city with its country the
  city's ("perth australia"), named with the country's short name ("Perth, Australia"). Built
  with `java.time`, not `android.icu`, so it runs in JVM tests; renamed zones the phone's tz
  database lacks are skipped. Sentences time_in and difference capture `.place.`; the words are
  looked up as said, then without one more trailing filler ("tokyo right now") each time, so a
  zone name that ends in one ("eastern standard time", "central european time") is found. The
  skill takes a known place, and the input must say "time" (or "difference", "ahead",
  "behind"). Answers: "It's 3:12 AM tomorrow in Tokyo.", "London is 6 hours ahead of you.",
  "London is 5 hours ahead of New York.", "5 and a half hours". It outranks `current_time`'s
  near-miss with its own sentences, with no edit to `CurrentTimeSkill`. A whole "what time is it
  in <place>" sentence (0.95 or more) with a place GAR doesn't know answers "I don't know the
  time in Moab." instead of the local time, unless the capture may well be no place
  (`WorldTimePhrases.unknownPlace`: more than three words, numbers and durations, time formats,
  "my ...", "here", parts of the day); those still go to the local time.
- **units** (`UnitConversion`). Length, mass, US volume (plus imperial pint and gallon),
  temperature, speed, area, data (decimal kilobyte and so on, plus kibibyte and so on) and time
  (a year is 365 days, a month a twelfth of it). Amounts through dicio-numbers, plus "a", "half
  a", "one and a half", "a quarter of a". "Ounce" is mass or fluid ounce, whichever fits the
  other unit; a bare "degrees" is the other temperature scale. Sentences convert and how_many.
  "5 miles is 8.05 kilometers.": two decimals from 1 up, one from 100 up, three significant
  digits below 1.
- **math** (`SpokenMath`, no sentences of its own: a Kotlin parse of the whole input). Numbers
  through dicio-numbers between operator words (plus, minus, take away, times, x, multiplied by,
  divided by, over, power, percent of, percent tip on, squared, cubed, square and cube root),
  with the usual precedence, optional leads (what's, how much is, calculate, ...) and fillers
  (please, equals), and at least one operation. Answers through Dicio's `CalculatorOutput`
  ("15% of 80 ="). It yields to the calculator when the input has the calculator's lead, only
  + - x ÷ ^ and no extra words, so "what is two plus two" stays the calculator's, while "seven
  plus nine" (the model used to call `timer_set`), "what's fifteen percent of eighty", "what is
  the square root of sixty four" and "what is 8 times 7 please" are math's. A bare percent after
  plus or minus is of the value before it, as on a phone's calculator: "100 plus 20 percent" is
  120 and "fifty minus ten percent" 45 (tax, a discount); a percent before them, or under times
  and divided by, stays a fraction.
- **random**. `SecureRandom`. Coin (needs "coin", or "heads" and "tails"); dice through
  `RandomPhrases.dice` ("a die", "two dice", "a pair of dice", "a d twenty", "d20", "2d6", "a
  twenty sided die"; 1 to 20 dice, 2 to 1000 sides); a number needs "number" and random, pick,
  choose, generate, think, between or from, 1 to 10 by default. "You rolled an 8." with a or an.
- **spelling**. The input says spell, spelled, spelt or spelling, and the capture is 1 to 3 words
  of letters (not "check" or a pronoun). A leading "out" is dropped when a word follows ("spell
  out necessary"), and "the" followed by anything but word, words or name names a thing, not a
  word ("spell out the address", "spell the plural of mouse"), which goes to the model. "Necessary is spelled N, E, C, E, S, S, A, R, Y.": words
  apart with ". ", an apostrophe named; the card shows the word large.
- **holiday** (`Holidays`). About 35 US holidays: fixed dates, nth or last weekdays, Easter by the
  anonymous Gregorian computus (with Mardi Gras, Ash Wednesday, Palm Sunday, Good Friday and
  Easter Monday), Thanksgiving + 1 and + 4, Election Day, and Tax Day as the filing deadline
  (April 15, moved past a weekend and DC's Emancipation Day: April 18 in 2028). The next one from
  today; "this year", "next year" and "last year" are understood; no lunar holidays. Sentences
  until (countdowns to holidays, to personal days, "my birthday": "I don't know your birthday
  yet.", "our anniversary": "I don't know your anniversary yet.", and to any day the date
  resolver reads), when (holidays and personal days) and date_of ("what day is X", holidays only:
  "what day is the fourth of july" stays the date skill's). Each needs its own words
  (days/long/countdown, when, day/date). The year's end isn't a day a year from today, as the
  date resolver reads "next year": "how many days until next year" and "how many days are left
  in the year" count to New Year's Day, "until the end of the year" to December 31, and a span
  ("next week", "in the month") goes to the model.
- **small_talk**. Hello (by the part of day), goodbye (also "goodnight"), thanks (also "ok
  thanks", "perfect thanks", "thank you google"), who, how, help. Only an input made entirely of
  small-talk words with a match of at least 0.85. "No thanks" and "I'm good" are the
  conversation's. Hello and how-are-you reopen the
  microphone. "What can you do" names a few things, and its card shows `WhatICanDo` with the
  enabled skills, through a Hilt `@EntryPoint` (`SmallTalkEntryPoint`) in its own package.

Navigation (Dicio's skill, one GAR-marked edit): "where's my battery" and "where is my wifi" ask
about the phone, and no maps app finds them. `NavigationSkill.score` returns `AlwaysWorstScore`
when `NavigationWords.asksAboutPhone(input)` holds (`skills/navigation/NavigationWords.kt`, GAR's
own file, English; the two imports carry `// GAR`):

- The input opens with "where's", "wheres" or "where is|s". After dropping fillers (my, the, your,
  at, on, s, ...) and a trailing 's, every word is a phone thing or a detail word, and at least
  one is a phone thing.
- Phone things: phone, cellphone, battery, charge(r/ing), power, wifi/wi fi, internet, signal,
  network, data, bluetooth, hotspot, volume, ringer, ringtone, sound, brightness, screen,
  display, flashlight, torch, settings, sim, location, gps, notification(s), camera,
  mic(rophone), speaker, alarm(s), timer(s). Detail words: level, percentage, percent, status,
  life, cell, mobile, service, password, name, strength, connection, speed, saver, mode, plan,
  app, icon, button, switch. "Bars", "reception", "storage" and "service" (alone) are left out:
  people ask where the bars, a storage unit or a funeral service are.
- Places and other leads still navigate: "where is the nearest phone store", "where is the
  charging station", "where is battery park", "where's my car", "navigate to the battery", "take
  me to ...". Known trade-off: "where is the battery" (the park in Charleston or New York) now
  gets the battery level; "navigate to the battery" still navigates.
- "Where is my wifi", "where's my phone" and "where is my signal" go to whatever else matches,
  or to the model.
- The model's `navigate` call runs `NavigationSkill.generateOutput` without scoring it, so
  `garCallFilter` has `NavigationCallFilter` (`gar/call/GarCallFilters.kt`, between
  `SongCallFilter` and `DeviceSettingCalls.filter`): it drops a `navigate` call whose utterance
  is one of these questions ("'<utterance>' asks about the phone, not a place to navigate to").
  Without it, "where is my wifi" would come back as "Navigate to my wifi?". The eval doesn't run
  it.
- Since 2026-11-02 `NavigationCallFilter` also drops a `navigate` call whose utterance
  `NavigationWords.refusesDirections`: one that opens with narration about someone else
  ("you're", "you are", "he's", "she's", "they're": the model navigated "You're trying to get to
  the first half of your business.") or with a question about when, why or whether
  (`PlaceWords.asksNoDirections`), after fillers; or that `PlaceWords.isNoPlace` refuses
  (below). Reason: "doesn't ask for directions to navigate" or "names no place to navigate to".
  It is a deny-list on purpose: a first version kept only listed ways to ask the way and dropped
  the model's right calls on "what's the fastest way home", "how long does it take to get to the
  airport", "nearest gas station" and "I'm trying to get to the airport" (first person asks the
  way; "I'm trying to get to sleep" is dropped by `isNoPlace`). The dev set's `navigate` cases
  all pass it (`GarCallFilterTest`).

The model's calls of existing tools (prompt unchanged) go through `GarEverydayCalls`, called from
`GarCallRunner.run` (one marked line, after the telephone's `directOutput`): a `current_time`
whose utterance names a place after "in", or asks for a difference, gets the world clock's
answer; a `calculate` whose slot or utterance is a conversion gets the converter's; a
`date_query` for a holiday is answered as "Christmas is on ...", and one that asks how many days
or how long gets the countdown. `GarSlotRewrites` (a marked `gar:answers` branch) rewrites
`date_query`'s day from a holiday's name to its next date ("december 25 2026", `holidayDay`), so
the resolver and Guard A keep the call; without a date keyword ("when is christmas") Guard B
still drops it, and the holiday sentences take that one first. `calculate` gets "X squared", "X
cubed" and "a X percent tip on Y".

Tests (JVM): `EverydayAnswersRankingTest` (through `garRankingWinner`: the new skills take their
inputs, existing commands stay with their skills, about 35 near-misses go elsewhere), one test
per package, and `GarEverydayCallsTest` (the model calls, and the slot check keeping
`date_query(day: "christmas")`). `GarRankingTest`'s calculator list no longer has "what's the
date of easter", "what is the date of thanksgiving", "what's the time in london" and "what is the
square root of sixty four" as rejected calculator matches: they are the holidays', the world
clock's and math's now. All 543 eval and holdout utterances were ranked once against the new
skills; they only take their own kinds of input (two spelling false positives, "can you dial the
dentist" and "can you open firefox", were fixed that way). Out-of-scope eval cases such as "thank
you", "who are you", "how do you spell necessary" and "when is christmas this year" are now
answered by these skills before the model; the router eval (model only) is unaffected. Hilt's
processing of the entry point is checked by `assembleDebug`. `NavigationWordsTest` pins the
navigation guard (the predicate, places and other leads, the sentence score, the ranking over
every English skill, `NavigationCallFilter` on the real `navigate` tool), `BatteryTest` the
"where's my battery" inputs, and `VolumeTest` "set/change/adjust the volume" asking "What
level?" (answered "fifty percent" or "max"), `specOf` and questions that only report.

## Device settings

PARITY batch 3. One skill, `device_settings` (`skills/device_settings/`, English sentences in
`sentences/en/device_settings.yml`, strings in `strings_gar_settings.xml`, `gar:settings` markers
in `SkillHandler`, `skill_definitions.yml`, `tools.yml`, the manifest and the shared tests), and
one LLM-only tool, `device_setting`, which also takes the calls the `wifi` and `bluetooth` tools
had (net one tool fewer).

What it does (`DeviceSettingsLogic` over `DeviceSettingsControl`, so every decision is JVM-tested
with `FakeDeviceSettingsControl`):

- Brightness: up/down by 10 points of the slider (`BRIGHTNESS_STEP`) or by the amount said; set
  to a level (percent; a plain number up to ten is out of ten, as for the volume, through
  `VolumeLogic.levelIndex`), max, min, half; asked ("Brightness is at 40 percent.", the card adds
  "Adaptive brightness is on"). Android stores `Settings.System.SCREEN_BRIGHTNESS` linearly
  (1..255 in use) while the slider follows a hybrid log-gamma curve: `BrightnessCurve` uses
  SettingsLib's `BrightnessUtils` constants and formulas, so "fifty percent" is what Settings
  calls 50% (about 22/255). The dark end is coarse (one stored step is several slider points).
  Writing the brightness keeps adaptive brightness as it is, as the slider does; Android takes it
  as the user's adjustment.
- Adaptive brightness and auto-rotate on/off/toggle/status (`SCREEN_BRIGHTNESS_MODE`,
  `ACCELEROMETER_ROTATION`).
- Screen timeout (`SCREEN_OFF_TIMEOUT`): set to a duration ("keep the screen on for ten minutes"
  sets the timeout, it isn't temporary), clamped to 10 s .. 30 min (Settings' longest choice);
  up/down move along Settings' choices (15 s, 30 s, 1, 2, 5, 10, 30 min); asked. On a locked
  phone it only gets shorter (review): a longer one keeps the phone on and unlocked longer once
  the owner unlocks it, the keyguard uses its own short timeout so nobody would notice, and the
  lock screen's quick settings don't offer it.
- These three need "Modify system settings" (`WRITE_SETTINGS`, special app access). Without it
  GAR asks once per command, with a button, like the online skills' card
  (`DeviceSettingsAccessOutput`): "Changing the brightness needs permission to modify system
  settings. Open that page?"; the card says where Android lists it and that GAR only uses it for
  brightness, timeout and auto-rotate. Not now / Open settings send their label as input, and a
  spoken yes or no goes through Dicio's util_yes_no (`DeviceSettingsAccessAnswerSkill`). Yes opens
  `ACTION_MANAGE_WRITE_SETTINGS` with GAR's package and says "Turn on Allow modifying system
  settings, then ask me again."; no says "OK.". A no is not remembered (no new field in
  gar_settings.proto). In other languages (no yes/no sentences) the page opens directly.
- Ringer (`AudioManager.ringerMode`): vibrate, silent, ring, toggle, status. Going into or out of
  silent toggles Do Not Disturb, which needs notification policy access: without it GAR opens the
  access page as the DND skill does (its intent and check) and says "To put your phone on silent,
  allow Do Not Disturb access, then try again." If Android keeps the phone silent after "ring"
  (DND), it says so. The model's ringer up/down/set ("turn up the ringer") is the volume skill's
  `VolumeLogic` on the ring stream.
- Airplane mode and battery saver: while GAR is the active assistant, Settings' voice activities
  (`ACTION_VOICE_CONTROL_AIRPLANE_MODE` + `EXTRA_AIRPLANE_MODE_ENABLED`,
  `ACTION_VOICE_CONTROL_BATTERY_SAVER_MODE` + `EXTRA_BATTERY_SAVER_MODE_ENABLED`), started with
  `ActivityIntent.startAsVoiceActivity` (moved from the DND control, which now uses it too); the
  setting is read again afterwards. Otherwise, or when nothing changed (battery saver's
  first-time confirmation dialog; GrapheneOS untested), the page opens. Status from
  `Settings.Global.AIRPLANE_MODE_ON` and `PowerManager.isPowerSaveMode`.
- What only Android switches (hotspot, location, NFC, mobile data, dark theme, and the two above
  when the voice activity can't): "Here's airplane mode." (spoken), and the card adds "Android
  only lets you switch airplane mode yourself: tap its switch."; already as asked: "Location is
  already on." Pages (`SettingsPage.forSetting`, each a list tried in order): hotspot
  `com.android.settings.WIFI_TETHER_SETTINGS`, `android.settings.TETHER_SETTINGS` (AOSP Settings
  actions, not SDK), wireless settings; NFC `Settings.Panel.ACTION_NFC`, NFC settings; mobile data
  the internet panel (its mobile data switch), then mobile network settings; dark theme
  `android.settings.DARK_THEME_SETTINGS`, display settings. Status:
  `LocationManager.isLocationEnabled`, `NfcAdapter.isEnabled` ("This phone has no NFC."),
  `TelephonyManager.isDataEnabled` (`ACCESS_NETWORK_STATE` is enough), the configuration's night
  mode; the hotspot has no public state, so its status opens its page.
- Headphones (review): "are my earbuds connected", "is my headset connected" (status sentence,
  not a name of `settingIn`, so "turn on my headphones" switches nothing) answer Bluetooth's
  status with its audio devices ("Bluetooth is on, connected to Pixel Buds."), and with none "No
  Bluetooth headphones are connected." rather than "Bluetooth is on.", which sounds like a yes;
  the card adds that GAR only sees Bluetooth headphones (wired and USB-C ones aren't Bluetooth
  audio devices).
- Night light: Android lets only the user switch it, so "turn on night light" ("blue light
  filter", "eye comfort") opens its page: "Here's night light.", the card "Android only lets you
  switch night light yourself: tap its switch." (review: the flashlight's "turn on ... light" took
  it and lit the torch).
- Wi-Fi and Bluetooth: on/off/toggle/show run the connectivity skill's plan
  (`ConnectivitySkill.execute`, the same panel, request dialog and pages as its sentences).
  Status: Wi-Fi from the active network's transport (connected) and `WifiManager.isWifiEnabled`
  (`ACCESS_WIFI_STATE`): "Wi-Fi is on and connected." / "on, but not connected" / "off"; no
  network name (that needs location). Bluetooth from `BluetoothAdapter.isEnabled` (no permission
  on 31+) and the connected Bluetooth audio outputs' names (`AudioManager.getDevices`):
  "Bluetooth is on, connected to Pixel Buds." The model's Bluetooth "on" shows the system's
  enable dialog only once `BLUETOOTH_CONNECT` is granted (the skill declares no permissions, so
  Dicio doesn't ask for Bluetooth before a brightness command); before that it opens Bluetooth
  settings. The connectivity sentences ask for it as before.
- "Open <page> settings": `SettingsPage`, 35 pages with what people call them (display, sound,
  battery, battery saver, network, mobile network, data usage, airplane mode, hotspot, location,
  NFC, security, privacy, notifications, apps, default apps, storage, accessibility, date and
  time, language, keyboard, about phone, developer options, accounts, dark theme, night light, Do
  Not Disturb, cast, VPN, digital assistant, home app, users, printing, and Wi-Fi and Bluetooth for
  the model); "Opening battery settings." A name needs "settings" (or options, setting,
  preferences) after it unless it works alone ("open developer options", "open about phone", not
  "open storage"). Location needs "settings" too (review): "show my location" asks where the
  phone is (PARITY batch 6), not for the page. Wi-Fi and Bluetooth settings stay the connectivity
  sentences'.
- Lock screen: questions, brightness, ringer, auto-rotate and a shorter timeout work on a locked
  phone (nothing private, easy to undo); opening a page, a radio's controls or an access page,
  lengthening the timeout, or switching airplane mode or battery saver, answers "Unlock your phone
  first." (a locked, lost phone must not go into airplane mode). Hence the tool's
  `on_lock_screen: true`.

Sentences (English) and their check. Ids: turn_on, turn_off, status (the setting comes from the
input's words, `DeviceSettingWords.settingIn`, most specific name first: "wifi hotspot" is the
hotspot, "auto brightness" isn't the brightness; "data saver", "screen time" and "night light"
name no setting), open_page (.page.), brightness_up/down/set (.level.)/max/min,
ringer_silent/vibrate/normal, timeout_set (.duration.). Levels are string captures, resolved like
the model's levels. `DeviceSettingsSkill.score` keeps a match only when
`DeviceSettingWords.inputOf` takes it: the words name the setting (brightness sentences need
brightness/bright/dim/dark..., ringer sentences silent/mute/vibrate/ring..., timeout sentences
screen/display/timeout/"time out", not "time" alone: "set the time or for forty seconds" is a
timer), they name no other thing (sharing, calling, plan, sun, lights, tv, alarm, timer, music,
..., and keyboard, haptic, typing or feedback: "turn on keyboard vibration" isn't vibrate mode),
and a status is a question (is, are, what, how, ...: "ringer off" matched "is ... ringer off" with
its first word left out; integration fix). A command's sentence also matches a question about it
with the question's words left out, which switched the setting (review: "should i turn on
battery saver", "did i put my phone on vibrate"). So `DeviceSettingWords.openingOf` reads how the
input opens, after fillers: did, is, are, was and were ask how the setting is (its status: "did
i turn on airplane mode" -> "Airplane mode is on."); the other question words, "can i" and
"should i" ask something else, and the match is dropped (the input goes to the model or the
fallback); "can you", "could you", "would you", "will you" and "please" stay commands. This holds
for turn_on, turn_off, the ringer, brightness and timeout sentences, and the model's calls use
the same reading (`DeviceSettingCalls`). turn_on/turn_off only switch airplane mode,
mobile data, hotspot, NFC, location, battery saver, dark theme, adaptive brightness and
auto-rotate (the ringer has its own sentences: "turn on silent mode" is the ringer off; Wi-Fi and
Bluetooth are the connectivity sentences'). "Light mode/theme" is dark mode the other way round.
"Lower the brightness a bit" is a step down, "make the brightness automatic" adaptive brightness
on. Found in review and added: "brightness fifty percent" (a level without "to"; it went to the
model, which set the media volume instead), "is my phone muted/silenced" (names of the ringer
for the status), short ringer commands both ways ("ringer off" is silent, "silent mode off" and
"vibrate off" ring), the headphones' status and night light (above).

The model's tool (`tools.yml`, `gar:settings`, at the wifi tool's position):
`device_setting{setting: enum [wifi, bluetooth, airplane mode, mobile data, hotspot, nfc,
location, battery saver, dark mode, brightness, auto brightness, auto rotate, screen timeout,
ringer, vibrate], action: enum [on, off, toggle, up, down, set, show, status], level: text,
optional}`. Why a model tool: the merge is PARITY's plan ("Keeping the model's prompt small",
point 3): the wifi and bluetooth tools were already in the prompt, and one tool for every setting
costs one tool less than the two it replaces while it reaches 13 more settings for paraphrases
the sentences miss ("get me out of airplane mode", "it's too bright"). Enum values with spaces
read like the request and make the confirmation readable ("Turn on airplane mode?"). The level is
text, not number: what it means depends on the setting (a number for the brightness and ring
volume, a duration for the timeout), and Qwen3-0.6B fills optional slots with leftover words
("turn on wifi" -> level "wifi"), which Guard A would drop as a number slot. The skill reads it
(`DeviceSettingTool.levelSpec`, `cleanLevel`, `levelWord`) only for up/down/set of brightness,
timeout and ringer, ignores leftovers elsewhere, and a set command whose level it can't read goes
through `GarCallRunner` with a typed spec, which asks "What level?" or "How long?" (the volume
skill's pattern). `confirm: false` (the answer says what changed or opens a page); `confirm_text`
by the action slot through the loader's `{slot: action, map: {...}}` form (a tool with two enum
slots). Keywords: the wifi and bluetooth tools' keywords (first two lines) plus each setting's
words and "setting"; every setting's own enum value passes them (`DeviceSettingsSkillTest`).
"Screen" and "display" are keywords only next to on, off or turn ("screen on", "screen off",
"screen turn"): alone they kept "my screen is cracked" (-> screen timeout off) and would keep
"what's on my screen" or "record my screen"; the sentences keep them as `GarMatchCheck` sentence
keywords ("when does the screen turn off", "the screen is too dark"). The action values are
on/off/... rather than DND's verbs (enable/disable/check), although `tools.yml`'s DND comment
says verbs did better for Qwen3: no eval has run a verb variant yet (open).

The model's calls checked against the words (`DeviceSettingCalls`, review; no prompt change). The
2026-10-27 eval showed the slots aren't reliable ("what does airplane mode do" -> airplane mode
on, "is my headset connected" and "show me the settings" -> battery saver on), and the tool is
`confirm: false`, so a wrong call would switch battery saver or airplane mode through the voice
activities unasked:
- `DeviceSettingCalls.filter`, one of the app's call filters ("Call filters"): a `device_setting`
  call whose words name another setting (`DeviceSettingWords.namedSetting`: `settingIn`, or
  Bluetooth for headphones; the ringer and vibrate count as one, so do the brightness and
  adaptive brightness) or another thing ("turn on location sharing") is dropped, and so is a
  `volume` call about another thing's brightness ("how bright is the sun").
- In `GarCallRunner.next`, once the slots are resolved (a `gar:settings` statement, so a yes to
  the question below runs the call once): a call whose words are a question runs as the
  setting's status (`DeviceSettingCalls.checked`), and a call that switches airplane mode,
  battery saver, the ringer, vibrate, auto-rotate or adaptive brightness on, off or over, when
  the words name no setting, asks first with the tool's question ("Turn on airplane mode?",
  `needsConfirm`), even with GAR's confirmations off: GAR can't tell what to switch then. Mobile
  data, hotspot, NFC, location and dark mode only open a page, so they don't ask. GAR's own
  level calls (no words) are left alone.
- `volume` and `volume_set` calls about the brightness (all the held-out brightness commands went
  there) run as the brightness, in `GarCallRunner.run` next to `SettingsPageCalls`
  (`DeviceSettingCalls.output`): the action comes from the words, not the model's volume action
  (a question is the status, "turn on auto brightness" adaptive brightness, a level is set,
  lower/dim/up step).

Kotlin resolution of an existing tool, no prompt change: `open_app` calls for a settings page.
`DefaultGarSlotResolver` takes a `settingsPage` function (`gar:settings`, set in `forContext` to
`SettingsPage::appNameFor`) that reads a page's name before the launcher labels ("the battery
settings" -> "battery settings", "developer options"), and `GarCallRunner.run` (one `gar:settings`
statement after the `gar:messages` line) runs `SettingsPageCalls.output`, which opens that page
instead of the open skill's Settings start page. Adding the pages as `NameMatcher` candidates was
tried and dropped: "battery settings" tied with "battery usage settings" and "battery saver
settings" within NameMatcher's margin and became a question. The hook applies to any app slot
(also `media_play`'s `app`), only for page names.

`GarMatchCheck`: connectivity has no tools after the merge, so its sentence matches would no
longer be checked ("turn on wifi calling" would open the panel). `SENTENCE_KEYWORDS["connectivity"]`
holds the old wifi/bluetooth keywords (harmless duplicates if the tools come back).
`SENTENCE_KEYWORDS["device_settings"]` adds the ringer and page words that aren't tool keywords
("mute my phone", "make my phone ring again", "open developer options", "open about phone", "open
accessibility", ...).

What the new sentences take from others (ranked over every English skill in
`DeviceSettingsRankingTest` and `GarRankingTest`): "turn off airplane mode" and "silence the phone"
(rejected timer near-misses before), "turn on light mode" and "turn on the light theme" (rejected
flashlight near-misses; now the dark theme page), "open battery settings" and other "open X
settings" (the open skill's before, which opened the Settings app), "mute/unmute my phone" (now
the ringer, silent/ring, as Google does; "mute the music" stays the volume's).

Upstream lines touched: `SkillHandler` (`gar:settings` line), `skill_definitions.yml` (block), the
manifest (block). GAR files touched: `GarMatchCheck` (two SENTENCE_KEYWORDS entries, the
flashlight's "night light" and "blue light"), `GarCall.kt` (run and next hooks),
`GarCallFilters.kt` (`DeviceSettingCalls.filter`), `GarSlotResolver.kt` (settingsPage parameter),
`ActivityIntent` (optional data URI; `startAsVoiceActivity`), `ConnectivitySkill` (`execute`,
`bluetoothState` in the companion), `DoNotDisturbControl` (uses `startAsVoiceActivity`, same
behavior), `ToolCatalogLoader` (confirm_text selector form).

Not done: lock the phone (device admin or accessibility), "what's the volume" (volume check),
connecting a Bluetooth device, the Wi-Fi network's name, continuing a command after the user
grants `WRITE_SETTINGS`, other languages, and a check of all of it on the Pixel (the voice
activities for airplane mode and battery saver, the hotspot page actions on GrapheneOS 17,
BatterySaverUtils' first-time dialog).

Undoing the wifi/bluetooth merge (if the eval finds more than 2 points lost in tool accuracy or
abstention; done this way for the 2026-10-27 eval, which kept the merge, "Tool catalog"):

1. `app/src/main/gar/tools.yml`: restore the two old blocks verbatim from commit `b927a648` (from
   the comment "# Android doesn't let apps switch Wi-Fi or Bluetooth" through the bluetooth tool's
   examples) right after `do_not_disturb`, before `device_setting` (the old tools keep their old
   order). In `device_setting`: remove `wifi, bluetooth, ` from the setting values; remove the
   first two keyword lines (wifi ... speaker), so that its Guard B no longer keeps calls for
   "connect my earbuds"; update its comment. Its examples need no change.
2. Eval: every case with `tool: device_setting` and `setting: wifi|bluetooth` becomes
   `tool: wifi|bluetooth`, `setting` is dropped, action "on" -> enable, "off" -> disable, show ->
   show, status -> show (the 18 cases.yml and 10 holdout.yml cases relabelled from the committed
   files, plus "is wifi on" and "is bluetooth connected").
3. `ToolCatalogTest` pinned list: "do_not_disturb", "wifi", "bluetooth", "device_setting",
   "stopwatch", ...
4. `GarCatalogTest` confirm-false list: add "wifi", "bluetooth" back to the `gar:device` line (the
   lock-screen list is unchanged: they were false).
5. Nothing else: the connectivity skill kept its sentences, ids and `ConnectivityInfo`;
   `DeviceSettingTool.inputFromSlots` takes any subset of settings; `GarMatchCheck` merges the
   restored tools' keywords with its sentence keywords; `DeviceSettingsSkillTest` checks only the
   values the tool has.

## Calls: call log and call controls

PARITY batch 4 (calls part). Two English-only skills, no tool, in `skills/calls/` with sentences
in `sentences/en/call_log.yml` and `call_control.yml`, strings in `strings_gar_calls.xml`, and
`gar:calls` markers in `SkillHandler`, `skill_definitions.yml`, the manifest and `GarRankingTest`.
Both have HIGH specificity and score `AlwaysWorstScore` unless their whole-input reader takes the
input as the same request (`CallLogWords`, `CallControlWords`, after `conversationWords` drops
fillers and apostrophes), since Dicio's matcher leaves a word out ("who called the plumber", "call
back in five minutes", "end the meeting", "pick up milk"). Without tools, `GarMatchCheck` never
rejects them.

- **call_log** (`CallLogSkill`; needs only `READ_CALL_LOG`, review: before, the questions also
  needed the contacts and `CALL_PHONE`). Calling back checks the telephone skill's `CALL_PHONE`
  first, and its contacts before a name goes to the contacts' lookup (which reads them without
  asking), and answers with the telephone skill's permission card when one is missing
  (`CallBack.output`); the telephone skill's own path has them already. Sentences last_caller
  ("who called me", "who's been calling", "show my call log"), missed ("did I miss any calls",
  "any missed calls", "did anyone call"), from ("did mom call me", "has dad tried to call", "any
  calls from mom", "did I miss a call from mom", "when did mom call"; review: they went to the
  model, which would call that person) and call ("redial", "call back", "call mom back", "call
  the last number", "return mom's call").
  `AndroidCallHistory` reads the newest 50 rows of `CallLog.Calls` (voicemails and blocked calls
  left out). The answers: "Mom called you at 3:12 PM.", "You missed 2 calls from Mom, the last
  yesterday at 9:05 PM.", "You have no missed calls."; a caller without a name is "a number
  ending in 1 2 3 4" (digits read one by one), a hidden one "a hidden number". One person's calls:
  the newest call from a contact whose saved name has every word said (as for call backs), said
  like the last caller ("You missed a call from Mom at 3:12 PM."), or "I don't see a call from
  Mom in your recent calls." (the log has the last 50 calls and the phone app's saved names; the
  who is a name of up to 4 words, not a time, a pronoun or anyone). Missed calls are
  the ones the phone app still marks as new plus every missed call of the last 24 hours. The card
  lists the recent calls. "Who is calling" (the ringing call) isn't answered: the call log only
  has a call once it ended.
- **Calling back** (`CallBack`): `CallBack.parse` reads what follows the verb into a target: the
  last incoming call ("back", "whoever just called", "the last caller", "my missed call"), the
  last outgoing one ("redial", "again", "the last number I dialed"), the last call either way
  ("the last number"), or a named one ("mom back" is the newest call from Mom, "mom again" the
  newest to her; a name is up to 4 words and no time word: "back in five minutes" is no target).
  Then the telephone skill's own confirmation (`ConfirmCallOutput`, "Should I call Mom?") asks
  before calling. A named call the log doesn't have goes to the contacts as the telephone skill would; a
  hidden number says "The caller hid their number, so I can't call back."; nothing to call says
  so. The call log stays closed on a locked phone ("Unlock your phone first."), and a named call
  then goes to the contacts.
- **The model's path, no new tool**: a GAR line in Dicio's `TelephoneSkill` (marked `GAR`) runs
  `CallBack.telephoneOutput` on the who it was given, so the model's `call_contact(who: "back")`,
  `call_contact(who: "my last number")` and Dicio's own "call the last number" reach the call log
  too; any other who is looked up in the contacts as before. The eval's `gar:calls` cases ("get the
  last caller back on the line", "dial whoever rang me a minute ago") expect `call_contact` with
  such a who.
- **call_control** (`CallControlSkill`; needs `ANSWER_PHONE_CALLS` and `READ_PHONE_STATE`, both in
  the phone group). Sentences answer, decline ("decline", "ignore the call", "send it to
  voicemail", "don't answer"), hang_up ("hang up", "end the call"), speaker_on and speaker_off.
  The state comes from `TelephonyManager.callState` plus the audio mode (ringing while in call is
  a waiting call), since `TelecomManager.isRinging` needs `MODIFY_PHONE_STATE` outside the phone
  app. Answer: `acceptRingingCall`, then waits for the call to be on and shows the call screen
  (`showInCallScreen`); "Answered the call." or "The call is still ringing. Answer it on the call
  screen." Decline and hang up: `endCall` ("Call declined.", "Call ended."); a waiting call can't
  be declined that way (it would end the call that is on), and Android won't end emergency calls
  or self-managed calls (Signal, WhatsApp): GAR says to use the call screen, with a "Show call"
  button. Speaker: only the phone app (its `InCallService`) can switch the audio route, so GAR
  says "Only the phone app can switch the speaker. Tap Speaker on the call screen." with the
  button. No confirmation and fine on the lock screen: the user hears the call ring or is on it,
  and the phone's own lock screen allows the same. `acceptRingingCall` and `endCall` are
  deprecated since API 29 but not limited to the phone app (checked on AOSP's
  `TelecomServiceImpl`); hearing "answer" during a ring still needs GAR to be listening then.

Not done: "call mom on speaker" (`EXTRA_START_CALL_WITH_SPEAKERPHONE`, dialer support unknown on
GrapheneOS), switching the speaker, muting a call, and a check of all of it on the Pixel
(`acceptRingingCall`/`endCall` from an assistant, the call log's names).

## Messages and email

PARITY batch 4 (messages part). Two English-only skills with no tool, in `skills/messages/`,
strings in `strings_gar_messages.xml`, sentences in `sentences/en/messages.yml` and `email.yml`,
`gar:messages` markers. Each has HIGH specificity and scores `AlwaysWorstScore` unless
`MessageWords` accepts the input. Dicio's matcher swaps or drops words and captures take any
words: "text mom" matched "text .who. back", "send a text to mom" matched "send an email to
.who.", and "read my notifications" matched "read my messages".

- Read: needs a message word outside the captures. The source can't be a pronoun ("what did you
  say"), a time ("in the last hour") or more than 5 words, and the input can't name email, calls,
  voicemail or notifications. In the present tense it needs a message noun: "what does mom's
  message say" is a read, "what does the clock say" and "what does the stopwatch say" are not
  (integration fix: the eval's current_time and stopwatch cases).
- Reply: needs reply, respond or back outside the captures, and who can't start with me, I, us or
  you.
- Email: needs email or mail outside the captures.

**messages** (`neededPermissions`: notification access, so Dicio's permission card offers the
settings page).

- Reading: `NotificationMessageSource` reads `NotifyHandler.Instance.activeNotifications`. It uses
  `NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification` for the senders and
  drops the user's own messages (no person, or the style's user by key, else by name). Without
  the messaging style, `CATEGORY_MESSAGE` notifications count, with their title and big text.
  Ongoing notifications, GAR's own, and group summaries whose group has other notifications are
  left out. Chat names: the conversation title, then the single sender, then the notification
  title.
- `MessageFinder` matches the spoken source with `NameMatcher` against chat names, senders and app
  labels. "from X" tries a person first and then an app; "on/in X" tries an app first. "mom on
  signal" splits into person and app. A sender in a group keeps only that sender's part of the
  group. "Last text" means the newest message only.
- Speech: "You have messages from A, B and C." (up to 5 names, then "N more"), then a conversation
  at a time: "Mom said: ..." or "In Family, Dad said: ... Mom said: ...", the newest 3 messages.
  A step reads up to 3 conversations and stops at the first one with a reply button, then asks
  "Want to reply?". Otherwise it asks "Want to hear more?" when more remain, and nothing at the
  end. The microphone reopens only when GAR asks. The card shows every message.
- Answers (`ReadAnswers`, through `GarYesNo` and known words only; cancel phrases go to the
  conversation skill): a reply text, "reply" or "yes reply" to ask for it, next/skip/keep going,
  yes, no. A no after "Want to reply?" moves to the next conversation. "Who texted me" lists names
  and offers to read them.
- The conversation read last is kept in `ReadMemory.shared` for 10 minutes, so a later "reply ok"
  or "reply to her saying ..." goes to it. Without it, the reply goes to the newest chat that has
  a reply button.
- When the listener isn't bound, GAR calls `NotificationListenerService.requestRebind` and says
  "I can't see your notifications right now. Try again in a moment.", with an access button.
- The phone must be unlocked (`GarLockedOutput`).

- "Read the message again": the conversation read last, while its notification shows ("again"
  is no name: "read messages from mom again" reads Mom's). Review: it read messages "from again".

Replying: the reply button is a notification action, or a `WearableExtender` action, with a
free-form `RemoteInput`. Contextual actions (API 29+) and immutable PendingIntents (API 31+, which
would drop the text) don't count, and `SEMANTIC_ACTION_REPLY` is preferred. Sending looks the
chat up again (`Conversations.replyTarget`): the same app and chat name under the same key, else
under another key, else "gone"; never the other chat an app now shows under the confirmed
notification's key (review: apps without the messaging style often post every chat under one id,
so a confirmed reply to Mom could have gone to Dad). Then it calls
`RemoteInput.addResultsToIntent` and `setResultsSource(SOURCE_FREE_FORM_INPUT)`, then
`actionIntent.send`. "Sent." means the app took the reply; GAR can't confirm that it was
delivered. The card (`ReplyCardOutput`, a `ReplyDraft` sent at most once) reuses
`SmsCardAnswers.classify`, `SmsCardOutput.spokenMessage`, `SmsMessage.assemble` and
`SmsFlow.plan`, so a reply is confirmed like a text: "Your reply to Mom on Signal says: OK. Ready
to send it?" (person, then app: "Your Messaging reply" read badly), and yes, no, "change it to", "read it again", "open it" and "send it to dad instead"
work. Results said plainly: the notification is gone, "This Signal notification has no reply
button...", "Signal didn't take the reply.", no access. A named person with no conversation gets
"There's no message from X to reply to. Want to send a text instead?"; `TextInsteadSkill` runs
under `SmsInfo`, so Dicio asks for the SMS permissions, then `SmsFlow.withSpokenRecipient` takes
over. "reply to mom i'm on my way" is split with `SmsRecipients.splitMessage` against chat names,
"reply to her i'm late" too (`MessageWords.afterLastRead`).

**email** (`neededPermissions`: `READ_CONTACTS`). Contacts come from
`ContactsContract.CommonDataKinds.Email` rows, grouped by contact, duplicate addresses merged
ignoring case. Matching uses `NameMatcher` first, then the telephone skill's distance, keeping
only contacts that share at least as many letters as were said (so "grandma" without an address
is not found) and a whole word ("Contacts: myself, no match, corrections"). A typed address is used as is. "Send mom an email" names her before the email
word (review: GAR asked "Who should I email?"). GAR asks "Who should I email?", "Which one?" or
"Which address for X?" (by position, name or address), and "What should it say?". The subject is
what was said after "about", otherwise the text's first sentence up to 8 words plus "...". The
intent is `ACTION_SENDTO` to `mailto:<address>` (percent-encoded except letters, digits and
@.+-_~) with `EXTRA_SUBJECT` and `EXTRA_TEXT` (not as mailto query parameters, which some apps
would duplicate). Speech: "Your email to Sarah is ready. Press send in your mail app." The card
has an "Open again" button. GAR never sends an email itself.

Model calls (`MessagesCalls`), no prompt change. `GarFallback` decides before the call runs
(`MessagesCalls.rerouteInfo`, a `gar:messages` statement): the call runs under the info of the
skill that answers it, so Dicio asks for that skill's permissions (`READ_CONTACTS` for an email,
notification access for a reply, not `SEND_SMS`) and shows its icon, and only when that skill is
turned on (`GarToolsProvider.isSkillEnabled`, the settings `offeredTools` reads); otherwise the
tool's own skill takes the call as before. `GarCallRunner.run` (one marked line after
`GarEverydayCalls`) then follows that decision (`MessagesCalls.output` by target info). Review: the
hooks used to run with those skills off, and an email needed `SEND_SMS`.

- `read_notifications` whose utterance says messages becomes a filtered read (the source after the
  last from/on/in/by, or between did/has and the verb); "read my notifications" stays with Dicio's
  notify skill.
- `sms_send` whose own words say email becomes the email flow: email or mail outside the `to`
  slot and the message, with the message cleaned by `SmsMessage.fromModel` and without one made
  only of email and command words ("an email"). "Tell mom I'll check my email later" stays a text
  (review: it opened the mail app). This changes the costs noted in "Call filters": the model's
  "send an email to my boss" now opens the mail app instead of an SMS read-back.
- `sms_send` that starts with reply or respond, or says "write/text/message back", becomes the
  reply flow.

Not done: the SMS inbox for dismissed texts (`READ_SMS`), contact nicknames when the app shows
another name, and a check on the Pixel that Signal, Molly, WhatsApp and Messages take a
PendingIntent reply sent by GAR while it is in the foreground (service-based reply actions could
be refused by background start limits).

## Contacts: myself, no match, corrections

Seen on Aaron's phone (Vosk, 2026-10-27): "send a text to myself" listed five contacts that only
shared letters with "myself" (the tests use stand-ins: Gabi (Plumber...), Myrtle Seller 27,
Katerina Book Club, Joe's Work, Melody Feed Store). The answer "no to erin" ("no, to Aaron") then
picked the one with "erin" inside a word, because Dicio's `contactStringDistance` counts
letters anywhere. Texts, calls and emails now pick contacts like Google Assistant. English only,
like GAR's other answers, except the whole-word test, which needs no words of a language and
applies in every language.

**Whole words** (`ContactWords.matches`, `NameMatcher.sharesWord`). A contact matches only if
`NameMatcher` finds it, or if it shares a whole word with what was said. A whole word is the same
word or a small mishearing of it (ratio >= `MIN_SCORE`, so "jon" for John), or one word starting
the other from 3 letters on (`MIN_PREFIX`: "sam" for Samantha, "johnny" for John, "sara" for
Saralee), or another spelling of it: both words 4 letters or more (`LOOSE_LENGTH`), the same
first letter and ratio >= 0.6 (`LOOSE_SCORE`: "gabby" for Gabi, "steven" for Stephen, "shawn" for
Sean). Vosk writes a name the same way every time, so without that rule a contact like
Gabi could never be texted by voice, though Dicio's distance had found it. The first letter keeps
"erin" from Katerina and Aaron, and "myself" from Myrtle Seller and Melody. It lets in a few loose
matches (kathy finds Katie, erin finds Erica), which the read-back and the call's confirmation
catch. Filler words and single letters (the "s" of "mom's") don't count. The test is added
wherever Dicio's distance chose before:

- the sms fallback and its "Which one?" pick (`SmsRecipients.choose` and `pick`);
- email's fallback and pick, on top of its "as many letters as were said" rule;
- the telephone skill's sorted contacts (`TelephoneContacts.choose`, see the code notes);
- Dicio's `ContactChooserName` (GAR line).

The call log (`CallHistoryQueries.named`, which needs every word said) and the messages skill
(`NameMatcher`) already matched by whole words. `NameMatcher`'s own matching is unchanged.

**No match, nothing offered.** When no contact really matches, a text says "I couldn't find X in
your contacts. Who should I text?". A call says "I couldn't find X in your contacts. Who should I
call?", or "Who should I call?" with no name (`TelephoneAskOutput`, a Replace plan like
`ConfirmCallOutput`, instead of Dicio's "No contact found, try again"). Its answer skill,
`TelephoneAnswerSkill`, takes a name or a number spelled out digit by digit and drops a leading
"call", "dial", "ring", "phone", "to" or "try". Email keeps "I couldn't find an email address for
X." A short list is still offered when several contacts really match.

**Myself** (`ContactWords.ownRest`, `OwnContact`, `OwnContactAskOutput`). The whole recipient
"me", "myself", "my self", "my (own) number", "my (own) phone (number)" or "my cell (phone)" is
the owner, for text, call and email. Only "me", "myself" and "my self" may come before a message
("text myself buy milk" keeps "buy milk"): "my phone company" is a contact, not the owner and a
message. The owner's card is looked up in this order:

1. the contact the user said is them, `GarSettings.own_contact_name = 9`, matched by exact
   display name (the user's explicit and more recent choice). Every contact with that name makes
   one card (`OwnContact.card`), so when a friend is also saved as "Aaron", the numbers of both
   are offered ("Which number?") instead of the first one being taken silently;
2. the profile (`ContactsContract.Profile`): its name, and the DATA1 rows of the phone and email
   mimetypes.

Texts and calls need a card with a number, email one with an address. Without one, GAR asks
"Which contact is you?":

- The answer drops "it's", "I'm", "my name is" and takes corrections.
- `OwnContactAnswers.find` uses `NameMatcher`, or a contact sharing a whole word, over all the
  contact names. Several matches give "Which one is you? A, B" (answered by name or position),
  none gives "I couldn't find X in your contacts. Which contact is you?".
- A contact without what the target needs (`OwnNeed`: a number for texts and calls, an address
  for email) gets "Aaron has no number. Which contact is you?" ("no email address" for email)
  and isn't remembered.
- Once found, GAR remembers it by display name (`GarYou.set`, so the name hint gets its first
  name too) and says "OK, you're Aaron. You can change that in Settings, Language model, You." It
  then goes on as if that name had been said (`OwnContactRememberedOutput`, which wraps the
  target's output in `GarFollowUpOutput`). Only a sure answer is remembered
  (`OwnAnswer.One.sure`): picked from the options, or said as the whole name or one of its words.
  A name only close to what was heard ("erin" for an Eric, since Vosk writes "Aaron" as "erin")
  goes on to that contact this once, without "OK, you're" and without being remembered: the
  read-back or "Should I call Eric?" says the name, and the next "text myself" asks again. A real
  "Erin" contact is still remembered; "OK, you're Erin" is the safeguard there.
- GAR's "You" setting shows the contact and changes or clears it in place; after Clear the
  profile is used again.
- The answer skill is a `GarOpenAnswer` that gives way to commands. "Cancel" ends with the
  skill's own cancel ("OK, not sending." for a text, "OK." for an email) or the telephone's
  cancelled call.

Permissions: `READ_PROFILE` isn't in android-36's `Manifest.permission` (checked with javap on the
platform jar: it was dropped in API 23 and is no longer enforced), so `READ_CONTACTS`, which all
three skills already ask for, covers the profile (minSdk 28). A `SecurityException` reads as no
card, and GAR asks. Not checked on the Pixel: whether GrapheneOS Contacts has a profile set up,
and the Profile query itself.

**Corrections** (`ContactWords.correction`). An answer that starts with no, nope, nah, not or
wrong and names someone after the filler words ("no, to Aaron", "no I meant mom", "not him,
dad", "no no no send a text aaron") is a correction. "No thanks", "no don't", "no, that one" and
"no, none of them" name no one. The conversation skill's stop and repeat phrases
(`ConversationWords.isStop`, `isRepeat`: "no, I'm good", "no, forget it", "not now", "no, what
did you say") are never corrections, so in "Which one?" they still reach the conversation skill
and end the conversation, as before corrections existed.

What follows "not" says who it isn't and is left out: a position ("no, not the first one, the
second one" -> "second one"), the words up to a comma ("not him, dad"), a pronoun ("not him
dad"), the longest name of the list offered that leaves a word after it ("not sarah lee sarah
connor"), else one word ("not mom dad" -> "dad", as Vosk writes no commas). When nothing is left
("not the first one", "no, not him", `ContactWords.rejectsOnly`), "Which one?" is asked again with
the same list. `parseOptionIndex` (shared with GAR's other questions) also skips a position right
after "not" and takes the first position said, not the lowest.

- In "Which one?" (`SmsChooserAnswer.of`, `EmailChooserAnswer.of`), a position ("no, the second
  one") or the exact name of a listed contact picks from the list (`pick(..., looseNames =
  false)`). Any other name is looked up among all the contacts with the message kept, since the
  list was wrong: "no, to aaron" with "Aaron's Work" listed looks Aaron up.
- The telephone's "Which one?" gets `TelephoneCorrectionSkill` (GAR line in `TelephoneOutput`,
  HIGH specificity, English only). It knows the numbers offered: a position after a no picks
  there and is confirmed ("Should I call Sarah Connor?"), since Dicio's `ContactChooserIndex`
  reads the first number said, which in "no, not the first one, the second one" is the one not
  wanted. A position alone ("the second one") is left to `ContactChooserIndex`.
- In "Who should I text/email/call?" and GAR's own "Who?", the spoken name is corrected where it
  is looked up: `SmsFlow.withSpokenRecipient`, `EmailFlow.withSpokenRecipient` and the GAR line on
  `userContactName` in `TelephoneSkill` (`TelephoneContacts.spokenName`, English only). That
  covers GAR's "Who?" without an edit in `gar/call`. "Who should I text/email?" takes any words,
  so a stop phrase there cancels ("OK, not sending." / "OK."); the telephone's "Who should I
  call?" is a `GarOpenAnswer` and gives way to the conversation skill.
- A message is never corrected (Aaron's decision, 2026-10-27: "if a text says something, just
  read it out and ask"). "What's the message?" answered "no no no send a text aaron" is read back,
  "Your message to Aaron says: No no no send a text aaron. Ready to send it?", and sent only after
  a yes. The same holds for replies, reminders' texts and events' titles ("Conversation").

Code notes:

- `SmsFlow.start`/`withSpokenRecipient`, `EmailFlow.start`/`withSpokenRecipient` and
  `MessagesCalls.output` are suspend, because DataStore is read through `OwnContact.cards`. Every
  caller was already suspend; `GarCallRunner.run` needed no edit.
- `SmsFlow` and `EmailFlow` take `ownContact: (SkillContext) -> OwnContact` (tests pass
  `FakeOwnContact`), and `GarEntryPoint` has `garSettings()`. `SmsRecipients.single` and
  `EmailRecipients.single` are public, and `RecipientChoice.UnknownOwner` is new.
- Strings are in `strings_gar_sms.xml` and `strings_gar_calls.xml`.
- Upstream lines (marked GAR): `TelephoneSkill` (the corrected `userContactName`, the
  `TelephoneContacts.directOutput` line, and Dicio's choice among the found contacts moved to
  `TelephoneContacts.choose`, so that it runs in JVM tests without a `ContentResolver`; there it
  gained the whole-word filter, `TelephoneAskOutput` when nothing matches and `telephone` passed to
  `TelephoneOutput`, the last two in English only); `TelephoneOutput` (an optional `telephone`
  parameter and the line that adds the correction skill); `ContactChooserName` (an import and the
  filter).
- `TelephoneContacts.directOutput` dials a number spelled out with the command ("call five five
  five one two one two") as is, like the answer to "Who should I call?" and the model's path,
  before the owner and the contacts. Dicio's "call .who." captured the digits as a name before.

Tests (JVM): `NameMatcherTest` (`sharesWord`, also gabby/Gabi and steven/Stephen); `SmsSkillTest`
(the strangers list, whole words in the distance fallback, spellings Vosk writes, `ContactWords`
with "not ..." and stop phrases, myself with a profile, remembered, asked, several, not found,
only close or without a number, corrections in "Which one?" and "Who?"); `EmailTest` (whole
words, corrections, email myself); `TelephoneContactsTest` (`ContactChooserName` by whole words,
`TelephoneCorrectionSkill` with positions after "not", `ContactChooserIndex` for a position
alone, `TelephoneContacts.choose` with the strangers and in Italian, `directOutput`,
`TelephoneAskOutput` and `TelephoneAnswerSkill`, `ownOutput`); `GarChatReplayTest`, which replays
Aaron's chat through the evaluator's matching step with every English skill and the real match
check: "send a text to myself" asks "Which contact is you?" once and then goes to his contact,
"no to erin" after the strangers' "Which one?" looks up "erin" for a text, and for a call goes to
`TelephoneCorrectionSkill` with "erin", "no, I'm good", "no, forget it" and "not now" after that
list reach the conversation skill (text and call), "no no no send a text aaron" as the message is
read back and sent only after "yes", and "where's my battery" gets the battery level.

Not done: changing the owner by voice; owner words and corrections in other languages; a contact
found by name that has no number still gets "I couldn't find X in your contacts" for a call (as
Dicio's "No contact found" did); a remembered contact that gets renamed is asked about again
(storing `LOOKUP_KEY` would fix it, but the ask settles on a name, not one contact); nicknames
inside a longer name ("liz" for Elizabeth, which Dicio's distance found) need a nickname table.

## Media: play by name, controls, sleep timer

PARITY batch 4 (media part). Group `gar:media`; strings in `strings_gar_media.xml`; the engine in
`org.stypox.dicio.gar.media`; the skills in `skills/media/` next to Dicio's `MediaSkill` (as
`timer_control` sits next to `TimerSkill`). No upstream media file is edited. The skills' answer
class is `GarMediaOutput`: Dicio's media skill already has a `MediaOutput` in that package
(integration fix, the writer had compiled without it).

Skills and the tool:

- **media_play**: English sentences (`sentences/en/media_play.yml`, one sentence with string
  captures query and app) plus the LLM-only tool `media_play{query: text, app: app (optional)}`.
  `MediaPlaySkill` implements `GarToolSkill`; `inputFromSlots` goes through the same
  `MediaPlayWords` as the sentences, so both paths build the same `MediaPlayRequest`.
- **media_controls**: English sentences only, no tool: resume, pause, next, previous (Dicio's
  media keys in words its media sentences miss, below), shuffle_on/off, repeat_one/all/off,
  seek_forward/back (amount), restart, like, dislike, sleep_set (amount, optional),
  sleep_cancel, sleep_query.
- Both build only with English sentences (`Sentences.X[lang] ?: null`); `GarFallback` only routes
  English, so the tool is offered in English. No upstream sentence ids were added, so no other
  language files are needed.

Why `media_play` is a model tool: what to play is free text that no sentence can list ("put on
dancing queen by abba" went to `open_app` in the holdout), and it is the one new model tool of
batch 4 that PARITY planned ("Keeping the model's prompt small", point 4; the slots are
query/app rather than the planned `play_media{what}`). It is appended last so the earlier prompt
prefix and its KV cache stay the same. Confirmed ("Play {query}?", without {app}, since an
optional slot would leave its "on" behind in `GarCallRunner.fillTemplate`) like `media_control`,
because a wrong guess starts music; on the lock screen like `media_control`: playing reveals
nothing. Keywords: play, put on, throw on, listen, hear, queue, stream, blast, playlist, podcast,
album, episode. "play" also matches "playing"/"played", so "what's playing at the movie theater"
passes Guard B; the confirmation catches it.

Score guards: each skill keeps a match only when its Kotlin reader takes the whole input
(`MediaPlayWords.parse`, `MediaControlWords.parse`), else `AlwaysWorstScore`.

- `MediaPlayWords` strips polite openings ("can you", "i want to", "let's"), then needs a verb:
  play, stream, queue (up), blast, put on, throw on, listen to, hear, start playing. Bare "start"
  counts only for a playlist, podcast, album or episode. It drops "me"/"us" after the verb and
  "please", "for me", "now" or "again" at the end, splits the app at the last
  on/in/with/using/through/via/from with up to 4 words of app (never on "repeat", "shuffle", the
  speaker, the TV and similar non-apps), and rejects queries that name nothing ("music",
  "something", "it": Dicio's play key resumes), skips (next/previous/following, "the last song"),
  non-media (game, voicemail, message, recording, lottery), "something else" and other requests
  for a different track (else, different, another or other with only generic words: "play
  another song"; "play another brick in the wall" stays a title) and queries starting with
  with/by/it/back/on. With an app, a generic query means anything there ("play some music on
  spotify"). The model's call (`fromSlots`) plays anything only for a generic query; a skip or a
  non-media query answers "Sorry, I can't play that." (review: they played anything), and the
  router already gives transport controls to `media_control` ("Call filters"). An app the model
  filled in that the user didn't say after on, in, with, using, through, via or from ("put on the
  new kendrick lamar album" -> app "album") is left out before GAR resolves it
  (`MediaPlaySkill.unsaidAppSlot`, a `gar:media` line in `GarCallRunner.next`), instead of "Which
  app?".
- `MediaControlWords`: whole-phrase regexes after fillers. Some phrases count only while something
  plays (`MediaControlMatch.whilePlaying`): bare "repeat"/"loop"/"on repeat" (the conversation
  leaves bare "repeat" alone), "i like this", "thumbs up", "thumbs down", "i don't like this".
  Dicio's play, pause, next and previous in words its media sentences miss (review: they went to
  the model, which gave them to `media_play`): "resume", "resume playback", "keep playing",
  "continue the podcast", "unpause", "hold the music for a second", "paws the music", "next one
  (please)", "skip it", "play something else", "play another song", "previous one", "go back a
  song". They send Dicio's media key (`MediaControlsSkill.pressMediaKey`, no notification access,
  Dicio's answer). Phrases Dicio's sentences take exactly ("resume the music", "the next one",
  "skip this song") stay Dicio's; "skip ahead" is a near miss (a seek without its amount).
- `AlwaysBestScore` only where an upstream sentence takes the same words: "restart the song"
  (Dicio's media play sentence has "restart" as resume), the sleep timer phrases ("set a sleep
  timer for an hour" is Dicio's timer named "sleep", "cancel the sleep timer" its cancel), and bare
  "stop"/"pause" while something plays (below).

Play by name (`GarMediaPlay`):

- App: the model's app slot is resolved by `GarCallRunner` (launcher labels, ambiguity questions).
  Sentence apps go through `NameMatcher` over the launcher apps. An unresolved sentence app that
  is a known player (`KNOWN_PLAYERS`: spotify, youtube music, pocket casts, antennapod, ...)
  answers "Spotify isn't installed."; otherwise the whole phrase is the query ("play dancing on
  my own"). "play spotify" (exactly a player's label) plays anything in that player.
- Route, in order: the named app; Android's default handler for `MEDIA_PLAY_FROM_SEARCH` (the
  user's "Always" choice, via resolveActivity); the first session, in priority order, that can
  play by name; the only handler; Android's chooser ("Pick an app to play it.", the card says to
  tap Always); else "There's no app on this phone that plays music by name."
- A session advertising `ACTION_PLAY_FROM_SEARCH` gets `TransportControls.playFromSearch(query,
  extras)`: no player screen, works locked. GAR polls up to 3 s (every 250 ms) for it to start
  (playing now and not before, another title, or back near the start); if it doesn't, the app's
  activity gets the intent, and without one: "I asked Spotify to play X, but it didn't start."
  Otherwise the app's `MEDIA_PLAY_FROM_SEARCH` activity gets the intent (setPackage,
  `FLAG_ACTIVITY_NEW_TASK`). An app with neither: "<App> can't play by name.", and without
  notification access GAR adds that access might help, with the button.
- Extras: `EXTRA_MEDIA_FOCUS` artist, album (artist after "by"), song ("the song X (by Y)"),
  playlist (QUERY keeps "workout playlist" for players that ignore the focus); anything else is
  `vnd.android.cursor.item/*` with `SearchManager.QUERY`. Android defines no podcast focus, so
  podcasts and episodes are an unstructured search with the words kept. "X by Y" without a focus
  word stays as said ("stand by me").
- Answer: "Playing <what as said> on <App>." with "my" spoken as "your"; "Playing music on
  Spotify." for anything.

Controls (`GarMediaControls`, through `MediaSessionManager` with Dicio's `NotifyHandler`
listener). Target: the first playing session, else Android's top session.

- Shuffle and repeat: androidx.media's custom actions `SET_SHUFFLE_MODE` and `SET_REPEAT_MODE`
  with their `ARGUMENT_*` ints, when the platform state's actions carry the `PlaybackStateCompat`
  bits (compat and media3 sessions turn them into onSetShuffleMode/onSetRepeatMode). Otherwise a
  custom action named shuffle or repeat is pressed as a toggle ("I switched shuffle in Spotify.",
  the card says GAR can't tell the state); otherwise "<App> doesn't let me change shuffle."
- Seek (`ACTION_SEEK_TO`): position + (elapsedRealtime - lastPositionUpdateTime) * speed while
  playing; back past the start goes to 0; forward past a known duration: "That's past the end of
  this track."; up to 6 hours. Restart is seekTo(0).
- Like/dislike: `setRating` with `RATING_THUMB_UP_DOWN`, or `RATING_HEART` for a like, when
  `ACTION_SET_RATING` is set; else custom actions classified by id and name words. A button that
  only undoes the like means "You already like this one."
- No notification access: "I need notification access to do that.", with "Allow notification
  access" (the song skill's `openListenerSettings`).

Sleep timer (`GarSleepTimer`): the clock app's timers can't carry an action (every timer rings),
so the sleep timer is GAR's own small alarm: one timer, a `Moment` end saved with commit() in SharedPreferences
`gar_media_sleep_timer`, `setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` (else
`setAndAllowWhileIdle`), `GarSleepTimerReceiver` (not exported). It uses `Moment`, the clock, the
store and the scheduler of `gar/timer/GarTimerBasics.kt`. It keeps the exact alarm permissions
(the old timer engine's): an inexact alarm may come up to three quarters of its delay late,
twenty minutes on a thirty minute timer. When it ends it pauses every
playing session, or without notification access sends the PAUSE media key (as Dicio's media skill
does). An early alarm sets it again; a reboot ends it; the first use in a process sets the alarm
again (a force stop drops it), and a timer whose alarm was lost more than a minute ago is dropped
without pausing. Up to 24 hours. "Set a sleep timer" asks "For how long?" with an answer skill that
takes only a duration. No notification; the card has a Cancel button. "OK, I'll stop the music in
thirty minutes.", card "The music stops at 10:30 PM."

Stop while media plays: see "Stop: who takes it". Not done: split verbs ("put some jazz on"),
"shuffle my workout playlist" (play it shuffled) and "on shuffle"/"on repeat" in a play request
(played in order), a `MediaBrowserService` connection (Android Auto style), a podcast focus
(Android has none), fading out at the sleep timer's end, absolute seek ("go to two minutes"),
"unlike", and a check on the Pixel of Spotify and YouTube Music taking `playFromSearch` from GAR's
controller, which players advertise the compat set-mode actions, real custom action names, and the
play-from-search activity on a locked phone.

## Batches 3 and 4 together

The four groups were written at the same time and merged afterwards. Inputs that two of them (or
an older skill) could take, as ranked over every English skill:

- "turn on speaker", "put it on speaker", "speaker off": the call controls (the device settings
  sentences don't take the speaker; `device_setting` keeps the bluetooth tool's keyword "speaker",
  so the model's calls for "put it on speaker" pass Guard B as before).
- "mute my phone", "silence my phone", "unmute my phone": the ringer (`device_settings`); "mute
  the ringer", "silence the ringer" and "mute the music": the volume skill, as before.
- "stop" and "pause" while media plays: the media controls, unless something rings or GAR asks.
  "stop the call", "hang up": the call controls; "stop the music": Dicio's media skill (pause).
- "reply ...", "text mom back": messages; "text mom", "send a text to mom": sms. "play my
  messages": messages (`MediaPlayWords` rejects messages and voicemail); "play my voicemail" and
  "read my email" go to the model.
- "call mom back", "call back", "redial": the call log; the model's `call_contact(who: "back")`
  reaches the same `CallBack` through the telephone skill.
- "who called me", "did mom call me": the call log; "who texted me": messages; "what does the
  clock say": not messages (fixed here).
- "open notification settings", "open sound settings": the device settings pages; "open wifi
  settings", "open bluetooth settings": connectivity; "open settings": the open skill.
- "ringer off": no status question any more (fixed here); the ringer sentences take it as silent
  (review), and "silent mode off" and "vibrate off" as ring.
- "turn on the night light": the device settings' night light page, not the flashlight (review);
  "did mom call me": the call log (review), not messages or the telephone.

Integration check: every eval and holdout text (601) and about 240 phrasings around these groups
were ranked over every English skill with the match check. Outside the fixes above, the new skills
only took their own commands, plus out-of-scope eval cases they now answer before the model
("read my last text message", "send an email to my boss") and the holdout's "unmute my phone"
(volume in the eval, the ringer on the phone). The router eval runs the model only, so these don't
change it.

## Weather

`gar:weather`, PARITY batch 5. `GarWeatherSkill` (`skills/weather/`) replaces Dicio's
current-only OpenWeatherMap skill: `WeatherInfo.build` returns it (a GAR line), and upstream's
`WeatherSkill.kt` stays in the tree unused so upstream files change little. It keeps the
`weather` id, so it is the same online skill as before: off on a new install, asked once,
switched by "turn on/off all online skills".

**Sources.** Breezy Weather first, offline; else Open-Meteo, online behind the ask-once question.

- Breezy Weather sends an explicit broadcast after each refresh,
  `nodomain.freeyourgadget.gadgetbridge.ACTION_GENERIC_WEATHER` (its Gadgetbridge sharing),
  only to the apps the user picks in Breezy's Settings > Widgets & Live Wallpaper > Data sharing >
  "Send Gadgetbridge data". `BreezyWeatherReceiver` (exported: the sender can't be checked before
  API 34, and the payload is only shown) keeps the last payload in
  `filesDir/gar-weather/breezy.json` (`WeatherGz`, all locations, preferred over `WeatherJson`;
  gunzip capped at 4 MB unzipped). Since any app could send that broadcast, any app on the phone
  could fill the offline answer with made-up weather, credited to Breezy; the receiver ignores
  broadcasts while Breezy isn't installed and unzips and saves off the main thread (`goAsync`),
  which is hardening, not a check of the sender. Temperatures are Kelvin, wind km/h, codes OpenWeatherMap's.
  Breezy's content provider was not used: it needs a dangerous permission and Breezy marks it as
  still being tested.
- GAR answers from Breezy, with no question, when the place said is one of Breezy's locations (no
  place: its first location), the data has the day asked, and it is at most 2 h old for now and
  the next hours, 8 h for days. The gate asks the wrapped skill for an offline answer first
  (`GarOfflineFirstSkill`, one line in `GarOnlineGateSkill.generateOutput`), so Breezy answers
  even while the skill is off.
- Otherwise Open-Meteo (no key): its geocoding API gets the place name as said (the whole name,
  then without its last words matched against region or country: "logan utah"; the phone's
  country first, since "Logan" alone is Logan City, Australia), and its forecast API gets the
  coordinates rounded to 2 decimals (about 1 km): current, 24 hours and 8 days, Celsius and
  km/h. The card credits "Weather data by Open-Meteo.com" (CC BY 4.0) or "From Breezy Weather,
  updated <time>". OpenWeatherMap's shared key and IPinfo are gone.

**Place.** The place said, else the home city (Dicio's existing "Default city" setting), else the
approximate location if the user chose it (`GarSettings.weather_use_location = 20`), else "Which
city are you in? You can also say: use my location." once. A city answer (`WeatherPlaceAnswers`:
at most 5 words, no question or command) is geocoded, saved as the default city "Name, Region"
and answered. On a locked phone GAR doesn't ask and the answers don't save ("Unlock your phone
first."): both are lasting choices, as places' saved home is; a request with a place or a saved
city still answers there. "Use my location" (spoken or the card's button) runs `WeatherLocationAnswerSkill`,
whose info needs `ACCESS_COARSE_LOCATION`, so Dicio asks for it plainly first. The fix comes from
`LocationManager` (a fix of the last 30 minutes, else a new one within 15 s, else one up to 6 h
old); it has no name, so answers say "near you". Not merged yet with `gar:places`' saved home
(`home_place`, field 30): a user may be asked for both.

**Reading requests.** `WeatherWords` reads the topic (general, temperature, high, low, rain, snow,
umbrella, wind, when it stops or starts), the time (now, a day, a night, the weekend, the week,
the next hours; other days through `SlotResolvers.date`) and the place from the whole utterance,
for sentence matches and the model's call alike. In English it is the score guard: no weather
request scores worst ("play cold by coldplay", "set the temperature to 70", "it's raining men",
"is the coffee hot", "look up the weather" (search's), "is snow white a disney movie"). The GAR
sentences (23, a GAR block in `en/weather.yml`) reuse Dicio's `.where.` capture, so there is no new
sentence id for the other 14 languages to define; they keep current conditions in the captured
place, with English strings.

**Answers.** Short: "Right now in Logan: 54 and sunny, high of 60.", "Yes, rain is likely in
Logan today: 70 percent chance.", "The rain should stop around 3 PM in Logan." Rain or snow is
yes from 50%, maybe from 25%; snow also needs a snowy sky or 0.1 cm. The card shows the next 8
hours or 5 days, with the chance of rain from 25%. Units are Dicio's settings (the system default
follows `LocalePreferences`: a US locale gets Fahrenheit and mph); metric speech uses km/h.

**Why a model tool.** Weather is the one batch 5 and 6 request that gets a tool (PARITY,
"Keeping the model's prompt small", point 4): people ask it in endless forms ("do I need a
jacket", "how cold is it going to get tonight") that sentences miss, and without a tool the model
sent "will it rain today" to `date_query`. `weather{}` is LLM-only and slotless: the skill reads
the day and the place from the utterance (`GarToolSkill.inputFromCall(ctx, call)`, a new method
with a default, so the other tool skills are unchanged), which avoids optional slots the model
fills badly. Appended last (28 tools; Qwen3's static prefix 9713 characters, was 9503, 2548
tokens, was 2494), with the description the 2026-11-02 eval chose ("Tool catalog"). `confirm: false` (it only reads, and the question names the service), `on_lock_screen:
true` (a forecast is not private; the gate refuses to turn the skill on while locked). Keywords:
weather, forecast, rain, snow, umbrella, temperature, hot, cold, warm, chilly, freezing, sunny,
cloudy, wind, storm, humid, outside, jacket, coat, sleet, hail, drizzle, foggy and their forms,
and since the review degrees, fog, frost and freeze (words `WeatherWords` takes, which the keyword
guard dropped; "freeze" joined `WeatherWords` and a weather sentence too). Keywords only filter
and aren't in the prompt.
Tools are otherwise offered only for enabled skills, so `GarToolsProvider` offers the tools of
online skills that are off behind their `GarOnlineGateSkill` (generic: any online skill that gets
a tool asks once), and `GarCallRunner.run` unwraps the gate to find the `GarToolSkill`.
`WeatherCallFilter` (the same `WeatherWords` guard) drops weather calls that ask nothing about the
weather; the eval doesn't run it. The golden prompts render frozen toolsets
(`golden/toolsets/*.yml`), not `tools.yml`, so they are unchanged.

Tests: `WeatherTest` (parsing, near negatives, Open-Meteo parsing and rounded URLs, replies,
Breezy freshness and the gzip cap, the lookup order) and `WeatherSkillTest` (score guard,
`inputFromCall`, the tool offered behind the gate, the model's call through `GarCallRunner`
landing on the question or on Breezy, the filter, units, ranking).

Next week (2026-11-02): "what's the weather next week" (the seven days from next Monday) was
labelled "This week"; `DayLabel.NextWeek` says "Next week" (a later Monday than today).

Latency of "What's the weather like for this week?" (2026-11-02, 3.4 s to the first word against
about 1 s for other turns): no week-only cause in the code. It is a sentence match
(`WeatherSkillTest`), and the week takes the same lookup as a day: Breezy's data when it is
under `DAYS_MAX_AGE` (8 h) and has today, else Open-Meteo (a geocoding request for the home city
on every turn, then the forecast: two round trips), and when the gate's `offlineOutput` misses,
the skill reads and parses Breezy's file a second time before going online. The spoken answer is
longer ("This week: highs from ... to ..., rain or snow likely on Monday, Tuesday and Friday."),
which only matters if the TTS engine synthesizes a whole utterance before `onStart`. To measure
from that turn's voice log: `stages` text, route, answer and first_word (answer minus route is
the lookup, first_word minus answer is the TTS), the card's source (Breezy or Open-Meteo), and
the TTS engine's package; then the same question twice in a row.

## Online answers: knowledge, definitions, currency, translation

`gar:knowledge`, PARITY batch 5. Four English skills with no sentences and no tool: each reads the
whole input in Kotlin (math's pattern) and scores worst unless it is one of its requests, so a
sentence match with a word left out never runs them, and `GarMatchCheck` never rejects them.
Strings in `strings_gar_knowledge.xml`.

- **knowledge** (`skills/knowledge/`, online, `GarOnlineSkill.KNOWLEDGE`): "who/what is X",
  "tell me about X", "what do you know about X", "how tall/big/long/... is X", "how old is X",
  "when was X born/built/founded", "when did X die". `KnowledgeQuestions` rejects subjects about
  the user or the phone (pronouns, time, weather, alarms, messages, battery, songs, contacts'
  numbers, addresses and birthdays, nearby places), small talk taken whole ("what's up"),
  subjects starting with a preposition or a number, words of other skills (meaning, definition,
  synonym, spelling, exchange, rate, price) and questions ending "in <language>". Not "how far is
  X" (directions). HIGH with `FloatScore(0.86)`: just above Dicio's first HIGH threshold, so it
  beats the calculator's catch-all "what is .calculation." (MEDIUM, which `GarMatchCheck`
  rejects), and below a good match of any other HIGH skill. Wikipedia's REST summary
  (`en.wikipedia.org/api/rest_v1/page/summary/{Title}`), then its search for the first result;
  only the subject's words are sent, with the User-Agent Wikimedia asks for. Speaks the first
  sentence (two when short), a measure's sentence for "how tall", the years from the description
  for born, died and age. Card: summary, a link, "From Wikipedia, under CC BY-SA".
- **dictionary** (`skills/dictionary/`, `GarOnlineSkill.DICTIONARY`): "define X", "what does X
  mean", "the meaning of X", "synonyms for X", "another word for X". Offline from Open English
  WordNet 2025 (CC BY 4.0): a one-time 9.6 MB download from its GitHub release, pinned by size
  and SHA-256 through `GarModelDownloader`, about 25 MB unpacked in `filesDir/gar/dictionary/`.
  Lookups binary-search the sorted index files and seek into the data files; inflections go
  through the exception lists and Morphy's suffix rules. The download is its only network
  contact, so it is an online skill whose question names the download. Chosen over Wiktionary,
  which would receive every word looked up. Known trade-off: "turn off all online skills" also
  turns off definitions after the download. HIGH, `FloatScore(1.0)`; "how do you spell X" stays
  the spelling skill's.
- **currency** (`skills/currency/`, `GarOnlineSkill.CURRENCY`): "20 euros in dollars", "convert
  50 pounds to euros", "exchange rate for pesos". The ECB's `eurofxref-daily.xml` (no key,
  nothing about the request sent), fetched at most once a day; offline it answers from the kept
  rates and says their date. Every word must belong to the request, so "20 pounds in kilograms"
  stays the units skill's. A missing second currency is the phone's. The model's `calculate`
  calls about money reach it through `CurrencyCalls.output` (one line in `GarCallRunner.run`,
  after units), through the same gate.
- **offline_translator** (`skills/offline_translator/`, not online): Offline Translator
  (`dev.davidv.translator`) exports `AidlTranslationService`
  (`dev.davidv.translator.ITranslationService`, no permission; its AIDL is "GPL-3.0-or-later OR
  MIT"). GAR binds it with two hand-written binder calls (no AIDL in the build), waits up to 15 s
  and speaks "Good morning in Spanish is: buenos días."; on an error it opens the app's read-only
  PROCESS_TEXT popup ("Here's that in Offline Translator."). It scores with Dicio's translation
  sentences only while the app is installed and has the languages, and ranks with the offline
  skills, so it wins before Lingva's gate; Lingva stays the online fallback.

New online skills start off on existing installs too: `UserSettingsSerializer.readFrom` (GAR
lines) turns off any `GarOnlineSkill` id missing from `enabledSkills`, since a missing id means
on. Its list is `GarOnlineSkill.ids`, so a new enum entry joins the defaults and the sweep with
no other change. Tests: `KnowledgeTest`, `KnowledgeOnlineSkillsTest`, `DictionaryTest` (a WNDB
fixture with real offsets), `CurrencyTest`, `OfflineTranslatorTest`. Not JVM-tested: the binder
calls, the cards, the real download, live Wikipedia and ECB.

## Places, camera and screen

`gar:places` (places, share_location, website; `strings_gar_places.xml`) and `gar:screen`
(camera, screenshot, clear_notifications; `strings_gar_screen.xml`), PARITY batch 6. Six English
skills with sentences and no tool; each is HIGH and scores worst unless its own word check takes
the input (`PlaceWords`, `WebAddress.parse`, `ShareLocationWords`, `CameraWords`, `ScreenWords`,
`ClearWords`).

- **Navigation home and work** (no prompt change): `NavigationSkill`'s GAR line hands the
  destination to `NavigationPlaces`, for Dicio's sentences and the model's `navigate` calls
  alike. Home and work (`PlaceWords.savedPlace`, not "home depot") go to the saved place
  (`GarSettings.home_place = 30`, `work_place = 31`, message `GarSavedPlace`: an address, a point
  or both). With none saved, "take me home" asks "What's your home address? Say the address or
  a place name, or "here" if you're there now." (the card adds that Settings has it too: the
  user may not know the address by heart). A web address opens the browser; "coffee near me" becomes a maps search (done here
  rather than in `GarSlotRewrites`, so sentences get it too). `PlaceWords.isNoPlace` drops "how do
  I get rid of ants", "get me a coffee", "drive me crazy" (no "to", no saved place, no route
  word), and keeps "get me directions to grandma's house". Since 2026-11-02 it also drops
  questions that open with when, why, should or "what time" (`asksNoDirections`: "when should I
  go to bed" had won as navigation) and destinations that are only things no map has
  (`namesNoPlace`: bed, sleep, nap, bathroom, toilet, restroom, after the fillers and "early",
  "tonight", ...: "take me to bed", "where is the bathroom"); "the nearest restroom" and "bed
  bath and beyond" are places. `GarMatchCheck` adds the skipped-"how" check ("Checking Dicio's
  own matches").
- **Maps apps and "Navigate with"** (checked 2026-11-02 against each app's source at HEAD;
  `MapIntents.kt` lists the intents, `MapApps.kt` the choice). Package ids: OsmAnd
  `net.osmand.plus`/`net.osmand`, Organic Maps `app.organicmaps`, CoMaps `app.comaps.fdroid`,
  Magic Earth `com.generalmagic.magicearth`, Vela `app.vela`. `us.spotco.maps` is not Organic Maps
  but Divested's "Maps", a WebView around Google Maps on the web (online only;
  `MapAppKind.GMAPS_WEB`, listed last). Route intents, every one with `setPackage` so Android's
  picker never shows for a saved point:
  - OsmAnd: `google.navigation:q=<lat>,<lon>` (route planning from My Position,
    `IntentHelper.parseNavigationIntent`); a text address `osmand.api://navigate_search?dest_search_query=..&search_lat=..&search_lon=..&profile=car&show_search_results=false`
    (offline search near the last fix of the day or home, no API permission needed).
  - Organic Maps: `android.intent.action.NAVIGATE` with `geo:<lat>,<lon>` (`GoogleAssistantIntentHandler`,
    PR #11782, 2026 releases), the Uri built with `Uri.Builder` because its handler's
    `getQueryParameter` throws on an opaque `geo:` Uri and drops to a pin. Without that filter,
    `om://route` like CoMaps.
  - CoMaps: no phone intent routes from My Position (its NAVIGATE filter is Android Auto's).
    `cm://route?sll=<fix>&saddr=My%20location&dll=<lat>,<lon>&daddr=<label>&type=vehicle` (all five
    keys, in order, `mwm_url.cpp`) draws the route at once from a given start; GAR sends it only with
    a precise last-known fix at most 2 minutes old (`DeviceLocation.lastKnown`, no wait, no dialog),
    and Start then offers to reroute from the current position ("Tap Start to go."). Else a pin.
  - Magic Earth: `magicearth://?drive_to&lat=&lon=` and `magicearth://?daddr=<text>` (its developer
    page now answers 404). `queryIntentActivities` matches only the scheme, never `drive_to`, so
    both stay off (`MapLinks.MAGIC_EARTH_ROUTES_VERIFIED = false`, a pin and "Tap Route.") until
    seen routing on the Pixel.
  - Vela (GPL-3.0, github.com/PimpinPumpkin/Vela, checked at aa0ff8c, 2026-11-02):
    `google.navigation:q=<lat>,<lon>` for a point and `google.navigation:q=<text>` for a text
    address (`MapLinks.velaTextRoute`, percent encoded). Its `MainActivity` has a VIEW filter for
    the `google.navigation` scheme (`app/src/main/AndroidManifest.xml:124-128`), `handleIntent`
    hands the Uri to `MapLinkParser.parse` (`MainActivity.kt:176-182`), which marks it a
    directions link (`core/.../MapLinkParser.kt:105`, `parseNavigation` at 114); `openDeepLink`
    sends every directions link to `openDirectionsLink` (`MapViewModel.kt:3038`), which for a point
    selects it and calls `routeToSelected` (3156-3166: `directionsOpen = true`, the route chooser
    from where the user is, with Start), and for a text searches it and routes to the first hit
    (`openDirectionsOnResult`, 3170, taken at 2798-2800). So Vela is "Starts the route" for both,
    when `queryIntentActivities` finds the filter. The text search is Vela's own (Google's data,
    online; downloaded places offline), so a contact's address needs no Nominatim lookup. Its
    `https://www.google.com/maps/dir/?api=1&destination=` filter (manifest 133-143) reaches the
    same `openDirectionsLink`, but `google.navigation:` is the one OsmAnd takes too. Vela's own
    Share gives a `?cid=` link when the place has a feature id (`PlaceSheet.kt:3513-3519`,
    `sharePlaceUrl`), which has no point (`PlaceLinks` says so and to use its "geo: pin"), else
    `maps/search/?api=1&query=<name> <lat>,<lng>`, which has one.
  - Divested's Maps: `https://www.google.com/maps/dir/?api=1&destination=..&travelmode=driving`.
  What each app does is probed at run time (`AndroidMapLauncher.apps`: the `geo:` handlers, GAR
  excluded, each route intent resolved with its package): `RouteSupport` ROUTE, ROUTE_FROM_FIX or
  PIN, and `MapApp.text` for a text route.
  **GAR never picks a maps app for the user** (decided 2026-11-02; before, "Automatic" silently
  took OsmAnd by a fixed ranking). `MapAppChoice.choose` (pure, `MapAppChoiceTest`) reads
  `GarSettings.navigation_app = 60`: a package the user picked; "ask" (every time, nothing saved);
  or "" "Not chosen yet", the default. Not chosen (or the picked app uninstalled since) uses
  Android's default `geo:` app when one is set (the user chose that in Android; the setting shows
  "Vela (Android default)"), the only app when there is one (not saved, so a second app installed
  later asks), and otherwise asks: `MapAppPickOutput`, GAR's own "Open with". The card: "Which
  maps app?", a row per installed app with one line on what it does from GAR ("Starts the route",
  "Draws the route from where you are", "Shows the place, tap Route", "Google Maps on the web,
  online"), and "Always use this app" (a switch, on by default, like Android's Always / Just
  once). A tap does the action at once; with Always on it saves the app. Spoken: "Which maps app?
  OsmAnd, Vela or CoMaps?", answered by name ("osm and", "vella", "co maps", "use vela";
  `MapAppChoice.spokenNames`, each spelling matched on its own) or position; a spoken answer reads
  the switch too, so it saves unless the user turned Always off. A save adds "Saved. Change it in
  Settings, Navigate with." before the action's speech and under its card (`MapAppSavedOutput`),
  that once only. The rows take taps also when an earlier question led to the card ("Show it on
  the map?", "Which address?"), where the log holds it in a `GarFollowUpOutput` (`isLatest`).
  With "Ask every time" the switch isn't shown. The card lists apps only, so it
  shows over the lock screen too, and the route card after it still hides the address.
  "Automatic" was stored as "" as well, so a stored Automatic now reads as Not chosen yet and the
  user is asked once; nothing is rewritten. The list order is not a ranking: apps that start a
  route first, Google Maps on the web last, then by name. Every maps action asks the same way:
  saved places and contacts (`PlaceRoutes.withApp`), Dicio's plain search ("take me to the
  airport", `NavigationPlaces.search`, a GAR edit in `NavigationSkill`, which used to send an
  unpackaged `geo:0,0?q=` to Android's chooser), nearby, a contact search ("wendy's") and "Show it
  on the map?" after where am I; a search goes to the app as `geo:0,0?q=` with `setPackage`. Only
  the card's map buttons stay unpackaged. A pin is followed by "Tap Route."
  spoken once (`tap_route_said = 65`), the card always shows it. Searches ("take me to the
  airport", "coffee near me", a saved or contact address searched for) are `geo:0,0?q=`, percent
  encoded (`MapLinks.encode`), sent to the chosen app. A saved
  home or work address gets Dicio's number cleanup (`NavigationSkill.cleanPlace`, moved out of
  `generateOutput` unchanged) when it is saved and again when it is gone to, so "one twenty three
  main street" is "123 main street". Saving one reads it back ("Saved your home address: 123 main
  street."), so a misheard address is heard at once. None of the apps lets another app read its
  own Home and Work, so GAR keeps its own.
- **Saved points from map links** (`PlaceLinks.parse`, `Ge0.decode`, `PlaceLinksTest`): the share
  target "Save as Home or Work" (`GarPlaceShareActivity`, exported, `ACTION_SEND` `text/plain`,
  a dialog, not over the lock screen) and the setting's field ("An address, a place name, a map
  link or coordinates") take the point out of `geo:` links (the `q=` point and label of
  `geo:0,0?q=`), Organic Maps' and CoMaps' ge0 short links (`omaps.app/`, `comaps.at/`, `om://`,
  `cm://`, `ge0://`: a zoom char, 9 base64url chars interleaving 3 bits of latitude and longitude,
  then `/Name`; the API's `ll=`/`dll=`), OsmAnd's `osmand.net/map?pin=`, openstreetmap.org
  `?mlat=&mlon=` or `#map=`, Magic Earth's `magicearth.com/?show_on_map&lat=&lon=`, Google links
  with the point in them, and a bare "40.71278, -74.00597" (also with ° and N/S/E/W). The name is
  the link's label, else the first line (OsmAnd's and Organic Maps' share text) unless it is the
  coordinates, a link or the app's own sentence. `maps.app.goo.gl` and `osm.org/go/` need the
  network: "No map point in what was shared." The dialog: "Save this place?", the name and
  coordinates, Save as Home, Save as Work (each with "Now: <what it replaces>"), Cancel.
- **Contacts** (`ContactTrips.kt`, `ContactTripTest`): "take me to Sarah's", "navigate to Sarah's
  house", "directions to Sarah", "how do I get to Dave's place" are already `navigate` with that
  `where` (no sentence change). `NavigationPlaces.target` tries `PersonWords.person` after saved
  places, websites and nearby: possessives ("sarah's", "sarahs house", "sarah's work", "my mom's
  house") and bare names of up to 3 words, never with "the", "a", "my" (but "my mom's"), a number
  or a word places have ("park", "station"), so "take me to the airport" stays a search. "Where is
  .where." has its own sentence id (`where_is`) and never goes to a contact: "where is mom" is no
  route to mom's. A bare "X's" may be a shop ("McDonald's", "Trader Joe's", "Wendy's"), so only
  "X's house|home|place|work" or "my X's" is sure (`PersonTarget.sure`). `ContactTrip.decide`
  (pure): locked and a sure possessive "Unlock your phone first." (contacts not read), locked and
  anything else today's search; no `READ_CONTACTS` or no match, today's search (no permission
  prompt); a bare one-word "X's" matches first names only ("smith's" is not John Smith) and asks
  "Directions to Wendy Jones?" (Go / Search, "Say no to search the map for wendy's."), a bare
  "X Y's" needs NameMatcher's close match on the whole name ("trader joe's" is not Joe Smith); several matches
  "Which one? Sarah Lee or Sarah Connor?"; several addresses "Which address? Home, 9 Elm Rd or
  Work, 4 Oak Ave?" (home first, work first for "Sarah's work"; one line each, duplicates once);
  none "Sarah has no address in your contacts. Search the map for sarah's?" (Search / No).
  Sure possessives match like contact info (`ContactInfoSkill.find`: NameMatcher, then whole words,
  "sarahs" as "sarah"); a bare name needs NameMatcher's close match. One address goes to the
  route as a text address (below): "Getting directions to Sarah Lee."
- **Text addresses to points** (`PlaceRoutes`, `AddressPoints.kt`, `AddressPointsTest`): a saved
  address or a contact's goes, in order, to a point found before (with the saved place,
  `GarSavedPlace.point_looked_up = 5`, or in the cache `GarSettings.address_points = 63`), the
  app's own text route (OsmAnd, Magic Earth), "Find addresses online" (`address_lookup = 61`, off
  by default), and last the app's search ("Pick the place, then tap Route."). With the setting off
  and the phone unlocked GAR asks once (`address_lookup_asked = 62`): "Look up this address online
  to start the route?" (Look it up / Not now; yes turns the setting on). `NominatimLookup` follows
  the Nominatim usage policy: GAR's User-Agent (`GarHttp.USER_AGENT`), at most one request a
  second process-wide (`RateGate.NOMINATIM`), one address per command, never in the background or
  for a list, a found point kept for good and "not found" for 30 days (newest 100 entries), and
  after a 403, 429 or 509 no lookups for a day (`address_lookup_paused_until = 64`, which is also
  how the OSMF can switch GAR off without an update). Not found or no connection: the search,
  with "I couldn't find that address online, so here's a map search." or "I couldn't reach
  OpenStreetMap, ...". A looked-up point's card credits "Address found with Nominatim, ©
  OpenStreetMap contributors".
- **Lock screen**: a saved home or work routes on a locked phone (the maps app asks to unlock,
  as with Google); `NavigateSavedOutput.locked` makes the card show only the headline and "Unlock
  to see the route." (no address, no coordinates), and speech never says the address. The lookup
  question isn't asked while locked. "Sarah's house" gives "Unlock your phone first."; a bare
  "Sarah's" (maybe a shop) searches.
- **Contact card fallback** (`CardPlaces`, `CardAddresses`, `SavedPlaceSource.decide`): with
  nothing saved, "take me to work" reads the "You" contact's postal addresses
  (`StructuredPostal`: the contact `own_contact_name` names, every contact so called, then the
  profile when that has none of the kind). Home and Work rows count; an Other or custom row only
  when its label says home or work and not both ("Home office" is neither). The primary address
  comes first, duplicates (case, spaces) once, lines joined with ", ". The first one is offered:
  "Use the work address on your contact card, 1 Infinite Loop, Cupertino?" (`GarOfferOutput`,
  buttons Use it and No); with two or more, "Which is your work address? ...?" (`GarPickOutput`,
  plus None of these, which asks). Yes saves it and goes there ("Saved your work address: ..."), no asks
  as before (`GarOfferOutput.onNo`, otherwise "OK."). Without `READ_CONTACTS` it asks as before
  and doesn't ask for the permission. On a locked phone with nothing saved the answer is "Unlock
  your phone first." (the card isn't read, no address is said); a yes given after the phone locked
  saves nothing. A saved place always wins and the card isn't read then.
- **"Home and work" setting** (`GarPlacesSetting`, `GarPlacesViewModel`, `PlaceEditor`), right
  under "You" on GAR's settings page: a row each for Home and Work showing the address, "Current
  location (40.71278, -74.00597)" for a point, or "Not set". A tap opens an editor: a field to type
  or paste an address or place name (Save; pasted lines are joined with ", "), "Use where I am
  now" (`LocationLookup.locate` as "here" by voice, asking with Android's location dialog; no
  access, location off and no fix each show an error, the first two with a button to App info or
  location settings), "From my contact" (only when the card has such an address; a list when it
  has several), and Clear when something is saved. Every write goes through `SavedPlaces`
  (`GarSettingsSavedPlaces`), the store the voice flow uses, so voice and settings agree. Over the
  lock screen the rows say "Unlock to see" and don't open, and an open editor closes
  (`GarYou.canOpen`, as "You"). Below it, "Navigate with" (each installed maps app with what it
  does, Android's default marked "(Android default)", and Ask every time; the summary says "Not
  chosen yet" until one is picked or Android has a default) and "Find addresses online" (`GarNavigationSetting`;
  turning it on there also counts as GAR's question answered). Tests: `PlacesCardTest` (postal rows, the fallback, the offer's
  yes and no, the setting's writes read back by voice, the summaries).
- **Nearby** (`places.nearby`): "find coffee near me", "gas stations nearby", "the nearest
  pharmacy". `PlaceWords.searchable` rejects questions and things no map has (weather, time,
  news), and the input must say near, nearby, around, here, nearest or closest
  (`PlaceWords.saysNearby`): Dicio scored "get me directions to grandma's house" as "get me .what.
  near me" with "near me" left out.
- **Where am I**: location through `SkillEvaluator.permissionRequester` with COARSE and FINE
  together (either is enough, so no Dicio `neededPermissions`, whose all-granted check would
  refuse approximate-only forever). `LocationManager` fixes (2 min last-known, else a new one
  within 20 s). The place is named only when `Geocoder.isPresent()` (GrapheneOS has no backend),
  with the point rounded to about 1 km first; otherwise "I can't name this place offline. Show it
  on the map?", with the coordinates on the card only (five decimals read aloud were long and,
  for an approximate fix, falsely precise).
- **Share my location**: an OpenStreetMap link through `SmsFlow.start`, so the sms card reads it
  back and sends only on a yes. Locked: "Unlock your phone first."
- **Websites**: `WebAddress` reads spoken domains ("reddit dot com", "w w w dot example dot co dot
  uk"; a known or two-letter top-level domain, so "7.30" and "mr. smith" are not addresses).
  Whisper writes "reddit.com", and normalizing drops a dot between letters, so `SpokenForm`
  rewrites a written address the way Vosk says it ("reddit dot com", "aaro dot cc slash blog",
  "my dash site dot org") before anything else reads it; the top-level domains are one list,
  `SpokenForm.TOP_LEVEL_DOMAINS`. Every label needs a letter ("3.5mm" stays) and email addresses
  are left alone; digits in a label become words ("web3.io": "web three dot io", an accepted miss).
  Reached from `OpenSkill`'s GAR line, the website sentences, `NavigationPlaces`, and for the
  model a `website` hook in `DefaultGarSlotResolver` (after settings pages, before app labels).
- **Camera**: `STILL_IMAGE_CAMERA` (the `_SECURE` variant when locked), `VIDEO_CAMERA` (locked:
  "Unlock your phone first."). Selfie and timer extras are sent, but GAR claims them only when
  Google Camera is the default handler; GrapheneOS Camera reads none, so GAR says to flip the
  camera or use its timer.
- **Screenshot**: `GarScreenshotService`, an optional accessibility service that only performs
  `GLOBAL_ACTION_TAKE_SCREENSHOT` (no events, no window content, no `canTakeScreenshot`), off by
  default. The first request offers to open Accessibility settings and mentions "Allow
  restricted settings". With it on, the output says nothing, moves GAR's task to the back and
  shoots after 700 ms.
- **Clear notifications**: `NotifyHandler.Instance.cancelAllNotifications()` through Dicio's
  listener (like Clear all, ongoing ones stay).
- `GarOfferOutput` (`gar/offer/`) is a reusable yes/no offer card.

Tests: `WebAddressTest`, `PlacesTest`, `ScreenAndCameraTest`. Not checked on the Pixel: the
permission dialog, the CoMaps pin, OsmAnd routing, Google Camera's extras, the restricted
settings flow, the screenshot timing.

## Notes, lists, facts, calendar changes, reminders and contacts

`gar:notes`, PARITY batch 6. Six English skills with sentences and no tool (lists, notes, facts,
agenda, reminders, contact_info; strings in `strings_gar_notes.xml`, `strings_gar_agenda.xml`,
`strings_gar_contacts.xml`). Each scores worst unless its Words object (`ListWords`,
`NoteWords`, `FactWords`, `AgendaWords`, `ReminderWords`, `ContactInfoWords`) reads the whole
input as its request; the sentences only supply the score.

- **Lists, notes and facts** live in GAR's own file, `filesDir/gar_notes.json` (`GarNotes`,
  `AtomicFile`, loaded by one GAR line in `App.kt` so the facts skill's score reads memory, like
  `GarStartedTimers`). Lists: canonical names (groceries is shopping), items split on "and" and
  commas, singular matching; clearing asks first. Notes are read back ("Saved your note: Call the
  dentist"; "make a note to call mom" saves "call mom"); the card offers to also save in the
  first of Notally, FUTO Notes and Obsidian whose installed version takes `ACTION_SEND`
  text/plain (only Notally confirmed from source). Facts: "remember (that) ...", said back in the
  second person; a new fact replaces one about the same subject; a question is the facts skill's
  only when a remembered fact shares all of its naming words when it has one or two, half when
  more, so "what's the weather like" never is and "how do I turn on wifi" doesn't read out "my
  wifi password is hunter2". "Where did I park", "do you remember/recall ..." and "forget
  where/what/my ..." are always taken; "do you know ..." only like any question ("do you know the
  time" is the clock's: one GAR line in Dicio's `current_time.yml`; "do you know if it's going to
  rain" is the weather's, a weather sentence and a `WeatherWords` lead). A thing with nothing said about it ("remember my parking spot", "remember
  where I parked": a determiner, no linking verb, place, number or verb inside) isn't saved: GAR
  asks "What should I remember about your parking spot?" and keeps the answer with it ("level 3"
  -> "my parking spot is level 3", "on level 3" -> "I parked on level 3"). "Remember to ..." is
  never a fact (a reminder, for the model's `reminder_add`). The settings page "What
  GAR keeps on this phone" lists everything, with Share all and Delete all.
- **Agenda**: the next event (Instances, visible, not cancelled, not GAR's reminders), "when is
  my dentist appointment", move and delete, "clear my calendar tomorrow". Changes are written as
  an app, not a sync adapter, so DAVx5 syncs the dirty rows. Every move or delete is read back
  and asked first; several matches ask "Which one?" (`GarPickOutput`). Repeating events open in
  the calendar app, and so does one time of a series changed on its own (an exception row, with
  `ORIGINAL_ID` or `ORIGINAL_SYNC_ID`: deleting it as an app brings the series' time back);
  read-only calendars are left alone. Only plain event nouns (event, meeting, appointment, ...)
  leave the title words: "cancel my lunch" looks for lunch and says "I couldn't find lunch" rather
  than offering the next event, and "lunch with john" falls back to "john" when no title says
  lunch. "When is my dentist appointment" answers "Dentist is tomorrow at 2:00 PM." (not "your
  next event"), and "where is ..." says the place ("... is at 12 Main Street, ...", or "There's no
  place on it."). "Delete all ..." needs a day and "move/switch X to Y" a time (or "reschedule",
  "postpone"), so "delete all my voicemails" and "switch my call to speaker" aren't the
  calendar's. A change after the yes that fails (a permission taken away since, a provider error)
  is said ("I couldn't change your calendar.") instead of reaching Dicio's error card. "What's on
  my calendar tomorrow" stays the calendar skill's.
- **Reminders**: the calendar skill's tracked reminder events. A deleted event can't be undone and
  DAVx5 syncs the delete, so one match is read back and asked first ("Cancel your reminder: call
  mom, today at 5:00 PM?"), as are several and "delete all reminders"; one named from "Which
  reminder?" is cancelled at once, since naming it is the choice.
- **Contact info**: number, email, address and birthday through `ContactBook` ("what's mom's
  number", "what's my number" for the owner), numbers spoken in their groups. New contacts open
  the contacts app's editor (`ACTION_INSERT`); GAR never writes a contact. "Save this number as
  joe" takes the newest call's number. "What's my home address" is places', "what's my address"
  the owner card's.
- **Model calls** (`NotesCalls`, one line at the top of `GarRoutedSkill.generateOutput`, before
  slot questions): a `calendar_add`, `calendar_query` or `reminder_add` call whose words are an
  agenda, reminders, list, note or fact request runs that skill; a `call_contact` or `sms_send`
  call asking for a contact's details runs contact_info. A call caught here never reaches the
  `GarCallRunner.run` hooks (currency, weather); they only meet on calendar, telephone and sms
  calls, which those hooks don't take.
- `gar/ask/GarAskOutputs.kt` adds `GarYesNoOutput` and `GarPickOutput`, reusable by other skills.
- Lock screen: adding a list item, a note or a fact works; reading and every other change answer
  "Unlock your phone first."

Tests: `NotesWordsTest`, `NotesSkillsTest`, `NotesCallsTest`, `NotesRankingTest` (every English
skill with remembered facts), `AgendaTest`, `ContactInfoTest`.

## Batches 5 and 6 together

The four groups (weather, knowledge, places and screen, notes) were written at the same time and
merged on 2026-11-02. Inputs that two of them (or an older skill) could take, as ranked over every
English skill (`GarBatch56RankingTest`):

- "what is X": knowledge ("what is serendipity", "what's the capital of france", "who is ...",
  "how tall is ..."); the dictionary for "what does X mean", "the meaning of X", "define X",
  synonyms; the calculator for arithmetic; translation for "what is hello in spanish"; the world
  clock for "what time is it in tokyo"; the weather for "what's the temperature"; currency for
  money; contact_info for "what's mom's number" and "what's my address"; places for "what's my
  home address". Questions about the user ("who am i", "what is my name", "what's my locker
  number" with nothing remembered) are not knowledge's.
- "where is": navigation for places on a map ("where is paris", "where is the nearest gas
  station"); places for "where am i", "where do i live" and nearby searches; facts for "where did
  i park" and "where is my car"; weather for "what's the weather nearby" (places' `NO_PLACE`).
  Fixed at the merge: places' nearby sentence took "get me directions to grandma's house" with
  "near me" left out (now it needs a nearby word), and the navigation guard dropped it (route
  words now keep it); the nearby line ".what. near me|nearby|..." grouped as "near (me|nearby)",
  so "gas stations nearby" scored too low (now "(near me)|nearby|...").
- "remember": facts for "remember that ..."; "remember to ..." and "remind me to ..." are
  reminders (the calendar skill, or the model's `reminder_add`); notes for "take a note", "make a
  note to ..." (fixed at the merge: the note no longer starts with "to"), "note to self".
- "take a ...": the camera for pictures, selfies and videos, the screenshot skill, notes.
- "clear my ...": notifications, a list or a day of the calendar, by the word after it.
- "forget my work address" is places'; "forget where i parked" facts'.

The eval's three dev and two holdout weather cases labelled `no_match` (written when weather had
no tool) were relabeled `weather` at the merge: with the tool offered while the skill is off, a
weather call is the right answer. Every eval and holdout case names one of the 28 tools or
`no_match`.

## Manifest permissions

GAR's permissions sit in commented blocks after Dicio's own; each comment names the skill group
that needs them. Dicio's (microphone, notifications, boot, foreground service with its
microphone type, full-screen intent, notification listener, contacts, calls, all apps) stay as
upstream has them. Timers are the clock app's ("Timers"), so their only permission is the alarm
group's `SET_ALARM`; the old timer engine's `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `VIBRATE` and
`WAKE_LOCK` and its components are gone, and its exact alarm permissions stay for the media sleep
timer.

| Permission | Group | Why |
|---|---|---|
| `SET_ALARM` | alarm | `AlarmClock` intents to the clock app (a normal permission). |
| `READ_CALENDAR`, `WRITE_CALENDAR` | calendar | Reads a day's events, writes events and reminders to the calendar provider. |
| `SEND_SMS` | sms | Sends the text after the user's spoken or tapped yes. |
| `ACCESS_NOTIFICATION_POLICY` | device | Turns Do Not Disturb on. |
| `BLUETOOTH_CONNECT` | device | Bluetooth's state and its enable request. |
| `ACCESS_NETWORK_STATE` | song | Tells offline from online before a recording goes to the user's service. |
| `WRITE_SETTINGS` (`tools:ignore` ProtectedPermissions) | settings | Brightness, screen timeout and auto-rotate: the "Modify system settings" access, which the user allows on Android's page. |
| `ACCESS_WIFI_STATE` | settings | Whether Wi-Fi is on (a normal permission). |
| `SCHEDULE_EXACT_ALARM` (`maxSdkVersion` 32) | media | The sleep timer's exact alarm on Android 12, where the user can turn it off (then the alarm is inexact). |
| `USE_EXACT_ALARM` | media | The sleep timer's exact alarm from Android 13 on, granted at install. Play's policy for it doesn't apply to a sideloaded app on GrapheneOS. |
| `READ_CALL_LOG` | calls | Who called, missed calls and the numbers to call back (a runtime permission, asked by Dicio before the call log skill runs). |
| `ANSWER_PHONE_CALLS` | calls | `TelecomManager.acceptRingingCall` and `endCall` (runtime, phone group). |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | places (and weather) | Where am I, "here" for home or work, sharing the location: asked only when one of those runs, precise or approximate as the user picks; weather asks for coarse only after "use my location". `uses-feature` location and GPS with `required="false"`. |
| `READ_PHONE_STATE` | calls | Whether a call rings or is on (`TelephonyManager.callState`) and the call screen (`showInCallScreen`); `isRinging` would need `MODIFY_PHONE_STATE`. |

The `gar:timer` block has no components any more (timers are the clock app's). The alarm, the
conversation and the everyday answers need no new permission: notification access for the
ringing alarm and timer is the listener Dicio already declares.

The `gar:weather` block adds `BreezyWeatherReceiver` (exported, the Gadgetbridge weather action
Breezy Weather sends to the apps the user picks; the payload is only shown). The `gar:screen`
block adds `GarScreenshotService` (`BIND_ACCESSIBILITY_SERVICE`, not exported, off until the user
turns it on in Accessibility settings, no events or window content). Knowledge, notes, the camera
and clearing notifications need no new permission: the contacts, call log and calendar
permissions are the existing skills', notification access Dicio's listener.

The `gar:media` block adds `GarSleepTimerReceiver` (not exported), the media sleep timer's exact
alarm (`USE_EXACT_ALARM` above). Messages, email and the media sessions need no new
permission either: notification access is Dicio's listener, contacts Dicio's `READ_CONTACTS`.
