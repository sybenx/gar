#!/usr/bin/env python3
"""GAR speech eval: Whisper accuracy on clips.yml, model x prompt mode, on the Mac.

Runs every clip through gar_whisper_eval (build-mac.sh), which calls whisper_full with exactly the
app's parameters (gar_whisper.cpp), 4 threads, and the app's audio_ctx for the clip's length
(AudioCtx.forSamples: min(1500, max(128, round(samples / 320) + 8)), Python's round is half to even
like the Kotlin code's). Modes:

  none            no prompt (the app without the setting)
  names           the owner (clips.yml `owner`), the clip's own names, then its other `prompt`
                  contacts, all as important ones (the best case: the name said is a starred
                  contact or a recent caller, so it is in)
  names-without   the same list without the clip's own names (what a prompt costs when the name
                  said isn't in it, and whether contact names leak into the text)
  phone           the contacts as the device reads them: the owner, clips.yml `starred`, then 10
                  recent callers drawn from `contacts` (seeded by the clip id) with the name said
                  among them for --phone-share of the clips; no other contacts
  others-40       the Pixel's failure of 2026-11-02 ("Call Aaron." heard as "Call Youran"): no
                  owner and the clip's `prompt` contacts without its own names, fitted to the old
                  40-token cap, as before the owner sources, the 16-token cap and the end of the
                  "other contacts" fill
  recorded        the clip's `prompt` as it is, not fitted: the names the phone gave Whisper, for
                  the voice log's recordings (gar/tools/voice-log.py --pull-wavs); not in the
                  default --modes

The prompt is built as the app builds it: the contacts go through ContactPromptNames.names
(cleaned display names, first names first, then the short full names), and NamePrompt.fit keeps
the names, in that order, whose prompt takes at most --max-prompt-tokens (NamePrompt.MAX_TOKENS, 16;
others-40 always 40) tokens as whisper_tokenize counts them (gar_whisper_eval --tokenize): each name after a space plus
one token per comma and the full stop, then the whole prompt checked as WhisperEngine does. The prompt text is --prompt-format with {names} replaced by the
names joined with ", " (default " {names}.", NamePrompt.text). names-without also drops every
candidate containing a name said (the first name "Aaron" of an "Aaron Diaz").

Scoring: the hypothesis and the reference both go through GAR's own SpokenForm (gar/router), run on
the JVM from the classes the last Gradle build left (SpokenFormFilter.java), so a clip is scored on
exactly what GAR would match. Without those classes (or with --normalizer python) a small Python
stand-in is used: lowercase, "a.m." -> "am", "%" -> "percent", "7:30" -> "seven thirty", whole
numbers below 1000 in words, punctuation dropped except apostrophes inside words.

Reported per model and mode: WER (all clips, clips with names, clips without), name accuracy (each
name said must appear as its exact words in the spoken form), exact clips, clips where a prompt
contact that wasn't said shows up in the text (intrusions; also counted without a prompt, as a
baseline), and decode time per clip on this Mac (total of whisper_full, as STAT_TOTAL_US).
Then WER by command type, and every clip whose name came out wrong. With names and names-without
both run, the break-even share: how often the name said must be in the prompt for the prompt to
match no prompt on WER and on exact clips (p * names + (1 - p) * names-without = none).

The prompt format, the token cap and the default were all chosen on these clips: there is no
held-out set yet, so small differences (" {names}." against " Contacts: {names}.") are ties.

Writes results/<stamp>.json and .md (and latest.json, latest.md) under ~/.cache/gar/stt-eval/.

usage: run.py [--models base,tiny] [--modes none,names,names-without,phone,others-40] [--threads 4]
              [--prompt-format " {names}."] [--normalizer jvm|python] [--label NAME]
              [--manifest PATH]    (default gar/stt/eval/clips.yml)
       run.py --manifest DIR/clips.yml --modes none,recorded --label voice-log   (the voice log's)
Needs make_clips.py's clips and the models in ~/.cache/gar/stt/. Stdlib only (plus `java` for the
SpokenForm scorer)."""
import argparse
import glob
import hashlib
import json
import os
import re
import statistics
import subprocess
import sys
import time
import wave

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts in the repo
import manifest  # noqa: E402

MODELS = {
    "base": "ggml-base.en-acft-q8_0.bin",
    "tiny": "ggml-tiny.en-acft-q8_0.bin",
}
MODES = ["none", "names", "names-without", "phone", "others-40"]
RECORDED = "recorded"  # not a default mode: only the voice log's clips have a real prompt

# NamePrompt.kt and ContactPromptNames (GarSttPromptNames.kt)
MAX_PROMPT_TOKENS = 16
MAX_CANDIDATES = 60
MAX_FULL_NAME_WORDS = 3
MAX_FULL_NAME_CHARS = 24
MAX_RECENT = 10
# the cap before 2026-11-02's phone bench, for the others-40 mode
OLD_MAX_PROMPT_TOKENS = 40

# AudioCtx.kt
MIN_AUDIO_CTX = 128
MAX_AUDIO_CTX = 1500
EXTRA_AUDIO_CTX = 8
SAMPLES_PER_POSITION = 320


def audio_ctx(samples, minimum=MIN_AUDIO_CTX):
    return min(MAX_AUDIO_CTX, max(minimum, round(samples / SAMPLES_PER_POSITION) + EXTRA_AUDIO_CTX))


def wav_samples(path):
    with wave.open(path, "rb") as w:
        return w.getnframes()


# ---------- spoken form ----------

def jvm_classpath():
    router = os.path.join(manifest.REPO, "gar", "router", "build", "classes", "kotlin", "main")
    numbers = os.path.join(manifest.REPO, "checkouts", "dicio-numbers", "numbers", "build", "libs", "numbers.jar")
    caches = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
    kotlin = None
    with open(os.path.join(manifest.REPO, "gradle", "libs.versions.toml"), encoding="utf-8") as f:
        m = re.search(r'^kotlin\s*=\s*"([^"]+)"', f.read(), re.M)
        if m:
            kotlin = glob.glob(f"{caches}/org.jetbrains.kotlin/kotlin-stdlib/{m.group(1)}/*/kotlin-stdlib-{m.group(1)}.jar")
    nanojson = sorted(glob.glob(f"{caches}/com.grack/nanojson/*/*/nanojson-*[0-9].jar"))
    parts = [router, numbers] + (kotlin[:1] if kotlin else []) + nanojson[-1:]
    if len(parts) < 4 or not all(os.path.exists(p) for p in parts) \
            or not os.path.exists(os.path.join(router, "cc", "aaro", "gar", "router", "SpokenForm.class")):
        return None
    return os.pathsep.join(parts)


def spoken_jvm(texts, classpath):
    filt = os.path.join(manifest.HERE, "SpokenFormFilter.java")
    lines = "".join(t.replace("\n", " ") + "\n" for t in texts)
    out = subprocess.run(["java", "-cp", classpath, filt], input=lines, capture_output=True,
                         text=True, encoding="utf-8", check=True).stdout.split("\n")
    if len(out) < len(texts):
        raise RuntimeError("SpokenFormFilter returned fewer lines than it got")
    return out[:len(texts)]


ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen " \
       "sixteen seventeen eighteen nineteen".split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()


def cardinal(n):
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else " " + ONES[n % 10])
    rest = n % 100
    return ONES[n // 100] + " hundred" + ("" if rest == 0 else " " + cardinal(rest))


def spoken_python(text):
    s = text.lower().replace("’", "'")
    s = re.sub(r"\b([ap])\.m\.", r"\1m", s)
    s = s.replace("%", " percent")
    s = re.sub(r"\b(\d{1,2}):(\d{2})\b", lambda m: cardinal(int(m.group(1))) + " "
               + ("o'clock" if m.group(2) == "00" else ("oh " if m.group(2)[0] == "0" else "")
                  + cardinal(int(m.group(2)))) if m.group(2) != "00" else cardinal(int(m.group(1))) + " o'clock", s)
    s = re.sub(r"\b\d{1,3}\b", lambda m: cardinal(int(m.group(0))), s)
    s = re.sub(r"(?<![a-z])'|'(?![a-z])", " ", s)
    s = re.sub(r"[^a-z0-9' ]+", " ", s)
    return " ".join(s.split())


# ---------- scoring ----------

def edit_distance(ref, hyp):
    row = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, row[0] = row[0], i
        for j, h in enumerate(hyp, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (r != h))
    return row[-1]


def contains(words, phrase):
    n = len(phrase)
    return n > 0 and any(words[i:i + n] == phrase for i in range(len(words) - n + 1))


def plain_name(name):
    return re.sub(r"[^a-z' ]+", " ", name.lower()).split()


def prompt_text(names, fmt):
    return fmt.replace("{names}", ", ".join(names)) if names else ""


def clean_name(display_name):
    """ContactPromptNames.clean."""
    s = re.sub(r"\([^)]*\)|\[[^]]*]", " ", display_name)
    s = "".join(c if c.isalpha() or c in "'-" else " " for c in s)
    words = [w.strip("'-") for w in s.split(" ")]
    name = " ".join(w for w in words if w)
    return name if sum(c.isalpha() for c in name) >= 2 else None


# ContactPromptNames.OWN_PLACEHOLDERS
OWN_PLACEHOLDERS = {"me", "myself", "mine", "my number", "my phone", "my cell", "my mobile", "home", "owner"}


def clean_own(display_name):
    """ContactPromptNames.cleanOwn: clean_name, and None for a placeholder ("Me")."""
    name = clean_name(display_name)
    return name if name and name.lower() not in OWN_PLACEHOLDERS else None


def candidate_names(important, owners=0):
    """ContactPromptNames.names: `important` is owner + starred + recent, most important first,
    the first `owners` of them the owner's names. No other contacts go in."""
    important = [n for n in [clean_own(n) for n in important[:owners]]
                 + [clean_name(n) for n in important[owners:]] if n]
    full = [n for n in important
            if " " in n and len(n.split(" ")) <= MAX_FULL_NAME_WORDS and len(n) <= MAX_FULL_NAME_CHARS]
    seen, out = set(), []
    for n in [n.split(" ")[0] for n in important] + full:
        if n.lower() not in seen:
            seen.add(n.lower())
            out.append(n)
    return out[:MAX_CANDIDATES]


class Tokens:
    """Counts prompt tokens with the model's tokenizer (gar_whisper_eval --tokenize)."""

    def __init__(self, binary, model_path):
        self.proc = subprocess.Popen([binary, model_path, "--tokenize"], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, text=True, encoding="utf-8")
        self.cache = {}

    def count(self, text):
        if text not in self.cache:
            self.proc.stdin.write(text + "\n")
            self.proc.stdin.flush()
            self.cache[text] = int(self.proc.stdout.readline())
        return self.cache[text]

    def close(self):
        self.proc.stdin.close()
        self.proc.wait()


def fit(names, max_tokens, fmt, tokens):
    """NamePrompt.fit: each name in order while it still fits, counted as " name" plus its comma
    or full stop; repeats left out. Then WhisperEngine's check of the whole prompt. Other formats
    than the app's are counted whole."""
    kept, seen, used = [], set(), 0
    for raw in names:
        if max_tokens - used < 2:
            break
        name = raw.strip()
        if not name or name.lower() in seen:
            continue
        seen.add(name.lower())
        if fmt == " {names}.":
            cost = tokens.count(" " + name) + 1
            if used + cost <= max_tokens:
                kept.append(name)
                used += cost
        elif tokens.count(prompt_text(kept + [name], fmt)) <= max_tokens:
            kept.append(name)
    while kept and tokens.count(prompt_text(kept, fmt)) > max_tokens:
        kept.pop()
    return kept


def seeded(clip_id, salt):
    return int(hashlib.sha256(f"{clip_id}/{salt}".encode()).hexdigest(), 16)


def owners(top):
    """clips.yml's `owner`, as a list: empty when the manifest has none."""
    return [top["owner"]] if top.get("owner") else []


def phone_book(clip, top, share):
    """The phone mode's contacts that go in: owner, starred, 10 recent callers. The rest of the
    book never does."""
    said = [plain_name(n) for n in clip["names"]]
    owner, starred = owners(top), list(top.get("starred", []))
    is_said = lambda c: any(contains(plain_name(c), n) for n in said)  # noqa: E731
    pool = [c for c in top.get("contacts", []) if c not in owner + starred and not is_said(c)]
    pool.sort(key=lambda c: seeded(clip["id"], c))
    recent = pool[:MAX_RECENT]
    own = [c for c in clip["prompt"] if is_said(c) and plain_name(c) in said]
    if own and seeded(clip["id"], "share") % 1000 < share * 1000:
        recent = own + recent[:MAX_RECENT - len(own)]
    return owner + starred + recent


def said_first(clip):
    """The clip's `prompt` contacts, those with a name said first (as starred or recent ones)."""
    said = [plain_name(n) for n in clip["names"]]
    is_said = lambda c: any(contains(plain_name(c), n) for n in said)  # noqa: E731
    return [c for c in clip["prompt"] if is_said(c)] + [c for c in clip["prompt"] if not is_said(c)]


def mode_candidates(clip, mode, top, share):
    if mode == "none":
        return []
    if mode == "phone":
        return candidate_names(phone_book(clip, top, share), owners=len(owners(top)))
    said = [plain_name(n) for n in clip["names"]]
    without_said = lambda names: [c for c in names  # noqa: E731
                                  if not any(contains(plain_name(c), n) for n in said)]
    if mode == "others-40":
        owner = [plain_name(o) for o in owners(top)]
        return [c for c in without_said(candidate_names(clip["prompt"]))
                if not any(contains(plain_name(c), o) for o in owner)]
    candidates = candidate_names(owners(top) + said_first(clip), owners=len(owners(top)))
    if mode == "names":
        return candidates
    return without_said(candidates)


def mode_max_tokens(mode, max_tokens):
    return OLD_MAX_PROMPT_TOKENS if mode == "others-40" else max_tokens


def break_even(summary, model):
    """The share of named commands whose name must be in the prompt for it to break even."""
    s = {m: summary.get(f"{model}/{m}") for m in ("none", "names", "names-without")}
    if not all(s.values()):
        return None

    def share(key, better_low):
        none, best, worst = s["none"][key], s["names"][key], s["names-without"][key]
        if best == worst:
            return None
        p = (none - worst) / (best - worst)
        return round(p, 2) if (best < worst) == better_low else None
    return {"wer": share("wer", True), "exact": share("exact", False)}


# ---------- running ----------

def decode(binary, model_path, threads, jobs):
    """jobs: (id, wav, audio_ctx, prompt). Two warm-up calls first, as the app's first command
    would otherwise pay the allocations."""
    warm = jobs[:2]
    lines = [f"warmup-{i}\t{j[1]}\t{j[2]}\t{j[3]}" for i, j in enumerate(warm)]
    lines += ["\t".join([j[0], j[1], str(j[2]), j[3]]) for j in jobs]
    proc = subprocess.run([binary, model_path, str(threads)], input="\n".join(lines) + "\n",
                          capture_output=True, text=True, encoding="utf-8")
    if proc.returncode != 0:
        raise RuntimeError(f"gar_whisper_eval failed: {proc.stderr.strip()}")
    results = {}
    for line in proc.stdout.splitlines():
        r = json.loads(line)
        if "error" in r:
            raise RuntimeError(f"gar_whisper_eval: {r}")
        if not r["id"].startswith("warmup-"):
            results[r["id"]] = r
    return results


def summarize(rows):
    def wer(subset):
        ref = sum(r["ref_words"] for r in subset)
        return round(100.0 * sum(r["edits"] for r in subset) / ref, 2) if ref else None

    named = [r for r in rows if r["names"]]
    times = [r["total_ms"] for r in rows]
    names_total = sum(len(r["names"]) for r in rows)
    names_right = sum(r["names_right"] for r in rows)
    return {
        "clips": len(rows),
        "wer": wer(rows),
        "wer_named": wer(named),
        "wer_plain": wer([r for r in rows if not r["names"]]),
        "names_right": names_right,
        "names_total": names_total,
        "name_accuracy": round(100.0 * names_right / names_total, 1) if names_total else None,
        "exact": sum(r["edits"] == 0 for r in rows),
        "intrusion_clips": sum(bool(r["intrusions"]) for r in rows),
        "named_clips_name_in_prompt": sum(r["said_in_prompt"] for r in named),
        "named_clips": len(named),
        "mean_ms": round(statistics.mean(times), 1),
        "median_ms": round(statistics.median(times), 1),
        "max_ms": round(max(times), 1),
        "mean_encode_ms": round(statistics.mean(r["encode_ms"] for r in rows), 1),
        "mean_prompt_tokens": round(statistics.mean(r["prompt_tokens"] for r in rows), 1),
        "max_prompt_tokens": max(r["prompt_tokens"] for r in rows),
        "by_type": {t: wer([r for r in rows if r["type"] == t]) for t in sorted({r["type"] for r in rows})},
    }


def markdown(report):
    out = [f"# GAR speech eval {report['stamp']}", "",
           f"{report['clips']} clips ({report['named_clips']} with names), normalizer {report['normalizer']}, "
           f"{report['threads']} threads on this Mac, prompt format `{report['prompt_format']}` "
           f"fitted to {report['max_prompt_tokens']} tokens (others-40: {OLD_MAX_PROMPT_TOKENS}), "
           f"audio_ctx min {MIN_AUDIO_CTX}. Times are whisper_full per clip on the Mac, not the phone.", "",
           "| model | mode | WER | WER names | WER plain | names right | name in prompt | exact | intrusions | mean ms | median ms | max ms | prompt tok |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for key, s in report["summary"].items():
        model, mode = key.split("/")
        out.append(f"| {model} | {mode} | {s['wer']} | {s['wer_named']} | {s['wer_plain']} | "
                   f"{s['names_right']}/{s['names_total']} ({s['name_accuracy']}%) | "
                   f"{s['named_clips_name_in_prompt']} | {s['exact']}/{s['clips']} | "
                   f"{s['intrusion_clips']} | {s['mean_ms']} | {s['median_ms']} | {s['max_ms']} | {s['mean_prompt_tokens']} |")
    even = {m: report["break_even"][m] for m in report.get("break_even", {}) if report["break_even"][m]}
    if even:
        out += ["", "Break-even share of named commands with the name said in the prompt (the prompt "
                "beats none above it):", ""]
        out += [f"- {m}: WER {e['wer']}, exact clips {e['exact']}" for m, e in even.items()]
    keys = list(report["summary"])
    types = sorted({t for s in report["summary"].values() for t in s["by_type"]})
    out += ["", "WER by command type:", "", "| type | " + " | ".join(keys) + " |",
            "|---|" + "---|" * len(keys)]
    for t in types:
        out.append(f"| {t} | " + " | ".join(str(report["summary"][k]["by_type"].get(t)) for k in keys) + " |")
    out += ["", "Names heard wrong (spoken form; `=` is right):", "",
            "| clip | name | " + " | ".join(keys) + " |", "|---|---|" + "---|" * len(keys)]
    by_clip = {}
    for key, rows in report["rows"].items():
        for r in rows:
            by_clip.setdefault(r["id"], {})[key] = r
    for clip_id, per_key in by_clip.items():
        first = next(iter(per_key.values()))
        if not first["names"] or all(r["names_right"] == len(r["names"]) for r in per_key.values()):
            continue
        cells = ["=" if r["names_right"] == len(r["names"]) else r["hyp"] for r in (per_key[k] for k in keys)]
        out.append(f"| {clip_id} | {', '.join(first['names'])} | " + " | ".join(cells) + " |")
    out += ["", "Other errors (clip text wrong, names right):", "",
            "| clip | reference | " + " | ".join(keys) + " |", "|---|---|" + "---|" * len(keys)]
    for clip_id, per_key in by_clip.items():
        first = next(iter(per_key.values()))
        rows = [per_key[k] for k in keys]
        if all(r["edits"] == 0 for r in rows) or any(r["names_right"] < len(r["names"]) for r in rows):
            continue
        out.append(f"| {clip_id} | {first['ref']} | " + " | ".join("=" if r["edits"] == 0 else r["hyp"] for r in rows) + " |")
    return "\n".join(out) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--models", default="base,tiny")
    parser.add_argument("--modes", default=",".join(MODES))
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--prompt-format", default=" {names}.")
    parser.add_argument("--max-prompt-tokens", type=int, default=MAX_PROMPT_TOKENS)
    parser.add_argument("--min-audio-ctx", type=int, default=MIN_AUDIO_CTX)
    parser.add_argument("--normalizer", choices=["auto", "jvm", "python"], default="auto")
    parser.add_argument("--binary", default=os.path.join(manifest.BUILD_DIR, "bin", "gar_whisper_eval"))
    parser.add_argument("--label", default="")
    parser.add_argument("--manifest", default=manifest.MANIFEST,
                        help="the clips (default %(default)s); voice-log.py --pull-wavs writes one")
    parser.add_argument("--phone-share", type=float, default=0.5,
                        help="phone mode: share of clips whose name said is a recent caller")
    args = parser.parse_args()
    models = args.models.split(",")
    modes = args.modes.split(",")
    for m in modes:
        if m not in MODES + [RECORDED]:
            sys.exit(f"unknown mode {m}, one of {MODES + [RECORDED]}")

    top, clips = manifest.load(os.path.expanduser(args.manifest))
    for clip in clips:
        path = manifest.clip_wav(clip)
        if not os.path.exists(path):
            sys.exit(f"{path} missing, run make_clips.py")
        clip["path"] = path
        clip["audio_ctx"] = audio_ctx(wav_samples(path), args.min_audio_ctx)
    if not os.path.exists(args.binary):
        sys.exit(f"{args.binary} missing, run build-mac.sh")

    classpath = jvm_classpath() if args.normalizer != "python" else None
    if args.normalizer == "jvm" and classpath is None:
        sys.exit("SpokenForm classes not found: build :gar:router once, or use --normalizer python")
    normalizer = "SpokenForm (jvm)" if classpath else "python stand-in"

    raw = {}
    prompts = {f"{model}/{mode}": {} for model in models for mode in modes}
    for model in models:
        model_path = os.path.join(manifest.MODELS_DIR, MODELS[model])
        jobs = []
        tokens = Tokens(args.binary, model_path)
        for mode in modes:
            for clip in clips:
                if mode == RECORDED:
                    names = list(clip["prompt"])  # fitted on the phone already
                else:
                    names = fit(mode_candidates(clip, mode, top, args.phone_share),
                                mode_max_tokens(mode, args.max_prompt_tokens), args.prompt_format,
                                tokens)
                prompts[f"{model}/{mode}"][clip["id"]] = names
                jobs.append((f"{mode}|{clip['id']}", clip["path"], clip["audio_ctx"],
                             prompt_text(names, args.prompt_format)))
        tokens.close()
        started = time.time()
        results = decode(args.binary, model_path, args.threads, jobs)
        print(f"{model}: {len(jobs)} decodes in {time.time() - started:.1f} s", file=sys.stderr)
        for mode in modes:
            raw[f"{model}/{mode}"] = {c["id"]: results[f"{mode}|{c['id']}"] for c in clips}

    texts = [c["text"] for c in clips] + [r["text"] for per in raw.values() for r in per.values()]
    spoken = spoken_jvm(texts, classpath) if classpath else [spoken_python(t) for t in texts]
    spoken_of = dict(zip(texts, spoken))

    report_rows, summary = {}, {}
    for key, per in raw.items():
        rows = []
        for clip in clips:
            r = per[clip["id"]]
            ref = spoken_of[clip["text"]].split()
            hyp = spoken_of[r["text"]].split()
            names = [n.split() for n in clip["names"]]
            names_sent = prompts[key][clip["id"]]
            said = [plain_name(n) for n in clip["names"]]
            # the contacts a phone would know, said or not; without a prompt as a baseline
            book = phone_book(clip, top, args.phone_share) if key.endswith("/phone") \
                else owners(top) + clip["prompt"]
            others = [c for c in candidate_names(book)
                      if not any(contains(plain_name(c), n) for n in said)]
            intrusions = [c for c in others
                          if contains(hyp, plain_name(c)) and not contains(ref, plain_name(c))]
            rows.append({
                "id": clip["id"], "type": clip["type"], "names": clip["names"],
                "ref": " ".join(ref), "hyp": " ".join(hyp), "written": r["text"].strip(),
                "ref_words": len(ref), "edits": edit_distance(ref, hyp),
                "names_right": sum(contains(hyp, n) for n in names), "intrusions": intrusions,
                "prompt_names": names_sent,
                "said_in_prompt": all(any(plain_name(p) == n for p in names_sent) for n in said),
                "audio_ctx": r["audio_ctx"], "prompt_tokens": r["prompt_tokens"], "tokens": r["tokens"],
                "confidence": r["confidence"], "encode_ms": r["encode_ms"], "total_ms": r["total_ms"],
            })
        report_rows[key] = rows
        summary[key] = summarize(rows)

    stamp = time.strftime("%Y%m%d-%H%M%S") + (f"-{args.label}" if args.label else "")
    report = {
        "stamp": stamp, "clips": len(clips), "named_clips": sum(bool(c["names"]) for c in clips),
        "normalizer": normalizer, "threads": args.threads, "prompt_format": args.prompt_format,
        "min_audio_ctx": args.min_audio_ctx, "max_prompt_tokens": args.max_prompt_tokens,
        "manifest": os.path.abspath(os.path.expanduser(args.manifest)),
        "owner": top.get("owner"), "phone_share": args.phone_share,
        "break_even": {m: break_even(summary, m) for m in models},
        "summary": summary, "rows": report_rows,
    }
    os.makedirs(manifest.RESULTS_DIR, exist_ok=True)
    text = markdown(report)
    for name in (stamp, "latest"):
        with open(os.path.join(manifest.RESULTS_DIR, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(report, f, indent=1)
        with open(os.path.join(manifest.RESULTS_DIR, name + ".md"), "w", encoding="utf-8") as f:
            f.write(text)
    print(text.split("\n\nWER by command type")[0])
    print(f"\nwritten: {os.path.join(manifest.RESULTS_DIR, stamp)}.json and .md")


if __name__ == "__main__":
    main()
