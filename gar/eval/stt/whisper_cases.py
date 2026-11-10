#!/usr/bin/env python3
"""Helper of make-whisper-cases.sh (standard library only); see that script and gar/eval/README.md.

  transcribe: reads a cases file, speaks each case (`said`, else `text`) with macOS `say` in each
              voice and transcribes the clips with whisper-cli, as the app calls Whisper (greedy, no
              fallback, no timestamps, English, audio_ctx from the clip length). Clips and
              transcripts are cached by text, voice and model. Writes raw.txt: one transcript per
              line, voice by voice, case by case.
  finish:     reads raw.txt and spoken.txt (the same lines through SpokenForm), writes one cases file
              per voice with each text replaced by the spoken form of its transcript, a TSV of every
              transcript and prints the word error rate against the cases' spoken text.

Cases files are YAML, but only the conventions of gar/eval/cases.yml (one flow mapping per line)
and holdout.yml (block mappings starting with "- text:") are read, line by line, so that the rest
of each line stays as it was.
"""

import argparse
import datetime
import difflib
import hashlib
import json
import os
import re
import subprocess
import sys
import wave

# AudioCtx.forSamples in gar/stt (cc.aaro.gar.stt.AudioCtx); Python's round() rounds half to even
# like Kotlin's
MIN_AUDIO_CTX = 128
MAX_AUDIO_CTX = 1500
EXTRA_AUDIO_CTX = 8
SAMPLES_PER_POSITION = 320

FLOW_CASE = re.compile(r"^(\s*-\s*\{\s*text:\s*)([^,}]*?)(\s*[,}].*)$")
BLOCK_CASE = re.compile(r"^(\s*-\s+text:\s*)(.*?)\s*$")
FLOW_SAID = re.compile(r",\s*said:\s*([^,}]*?)\s*[,}]")
FLOW_STT = re.compile(r",\s*stt:\s*([^,}]*?)\s*[,}]")
BLOCK_KEY = re.compile(r"^(\s+)([a-z_]+):\s*(.*?)\s*$")
NUMBER_WORDS = {
    "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
    "twelve", "fifteen", "twenty", "thirty", "forty", "fifty", "oh", "o'clock",
}


def audio_ctx(samples):
    return min(MAX_AUDIO_CTX, max(MIN_AUDIO_CTX, round(samples / SAMPLES_PER_POSITION) + EXTRA_AUDIO_CTX))


def unquote(value):
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def read_cases(path):
    """The cases in file order: text, said (or None), style and the lines they span."""
    lines = open(path, encoding="utf-8").read().split("\n")
    cases = []
    current = None
    for i, line in enumerate(lines):
        stripped = line.strip()
        flow = FLOW_CASE.match(line)
        block = BLOCK_CASE.match(line) if not flow else None
        if flow:
            said = FLOW_SAID.search(flow.group(3))
            stt = FLOW_STT.search(flow.group(3))
            cases.append({"text": unquote(flow.group(2)), "said": unquote(said.group(1)) if said else None,
                          "stt": unquote(stt.group(1)) if stt else None,
                          "style": "flow", "start": i, "end": i + 1})
            current = None
        elif block:
            current = {"text": unquote(block.group(2)), "said": None, "stt": None, "style": "block",
                       "start": i, "end": i + 1, "indent": len(line) - len(line.lstrip())}
            cases.append(current)
        elif current and stripped and not stripped.startswith("#") and \
                len(line) - len(line.lstrip()) > current["indent"]:
            current["end"] = i + 1
            key = BLOCK_KEY.match(line)
            if key and key.group(2) in ("said", "stt"):
                current[key.group(2)] = unquote(key.group(3))
        else:
            current = None
    return lines, cases


def to_say(text):
    """What `say` reads: "seven am" as "seven A.M.", not the verb."""
    words = text.split(" ")
    for i in range(1, len(words)):
        if words[i] in ("am", "pm") and words[i - 1] in NUMBER_WORDS:
            words[i] = words[i][0].upper() + ".M."
    return " ".join(words)


def key(*parts):
    return hashlib.sha1("\n".join(parts).encode("utf-8")).hexdigest()[:20]


def transcribe(args):
    _, cases = read_cases(args.cases)
    if not cases:
        sys.exit(f"no cases in {args.cases}")
    voices = args.voices.split()
    # a replaced model file of the same name gets new transcripts
    model_name = f"{os.path.basename(args.model)}-{os.path.getsize(args.model)}"
    os.makedirs(args.work, exist_ok=True)
    jobs = []  # (voice, case index, wav, transcript base)
    for voice in voices:
        wav_dir = os.path.join(args.cache, "wav", voice)
        txt_dir = os.path.join(args.cache, "txt", model_name, voice)
        os.makedirs(wav_dir, exist_ok=True)
        os.makedirs(txt_dir, exist_ok=True)
        made = 0
        for index, case in enumerate(cases):
            text = to_say(case["said"] or case["text"])
            k = key(voice, text)
            wav = os.path.join(wav_dir, k + ".wav")
            if not os.path.isfile(wav):
                subprocess.run(["say", "-v", voice, "--file-format=WAVE", "--data-format=LEI16@16000",
                                "-o", wav + ".tmp.wav", text], check=True)
                os.replace(wav + ".tmp.wav", wav)
                made += 1
            jobs.append((voice, index, wav, os.path.join(txt_dir, k)))
        print(f"{voice}: {len(cases)} clips ({made} new)", file=sys.stderr)

    # whisper-cli takes one audio_ctx per run: one run per audio_ctx, several clips each
    pending = {}
    for job in jobs:
        if not os.path.isfile(job[3] + ".txt"):
            with wave.open(job[2], "rb") as w:
                if w.getframerate() != 16000 or w.getnchannels() != 1:
                    sys.exit(f"{job[2]} is not 16 kHz mono")
                pending.setdefault(audio_ctx(w.getnframes()), []).append(job)
    total = sum(len(group) for group in pending.values())
    done = 0
    for ctx, group in sorted(pending.items()):
        for start in range(0, len(group), 40):
            chunk = group[start:start + 40]
            command = [args.whisper_cli, "-m", args.model, "-l", "en", "-nt", "-nf", "-bs", "1", "-bo", "1",
                       "-t", str(args.threads), "-np", "-otxt", "-ac", str(ctx)]
            for _, _, wav, base in chunk:
                command += ["-f", wav, "-of", base]
            result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
            if result.returncode != 0:
                sys.exit(f"{result.stderr}\nwhisper-cli failed ({result.returncode})")
            done += len(chunk)
            print(f"transcribed {done}/{total}", file=sys.stderr)

    with open(os.path.join(args.work, "raw.txt"), "w", encoding="utf-8") as raw:
        for _, _, _, base in jobs:
            with open(base + ".txt", encoding="utf-8") as f:
                raw.write(" ".join(line.strip() for line in f if line.strip()) + "\n")
    with open(os.path.join(args.work, "jobs.json"), "w", encoding="utf-8") as f:
        json.dump({"voices": voices, "cases": len(cases)}, f)


def word_errors(reference, hypothesis):
    ref, hyp = reference.split(), hypothesis.split()
    row = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        previous, row[0] = row[0], i
        for j, h in enumerate(hyp, 1):
            previous, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, previous + (r != h))
    return row[len(hyp)]


def substitutions(text, said):
    """Word replacements from the text to what was said ("urine" -> "aaron")."""
    a, b = text.split(), said.split()
    return [(" ".join(a[i1:i2]), " ".join(b[j1:j2]))
            for op, i1, i2, j1, j2 in difflib.SequenceMatcher(a=a, b=b, autojunk=False).get_opcodes()
            if op == "replace"]


def apply(subs, line):
    for old, new in subs:
        line = re.sub(r"(?<![\w'])" + re.escape(old) + r"(?![\w'])", new, line)
    return line


def write_cases(lines, cases, spoken, header):
    """The cases file with each text replaced; slot values of `said` cases follow what was said."""
    out = list(lines)
    dropped = []
    for index, case in enumerate(cases):
        start, end = case["start"], case["end"]
        if not spoken[index]:
            dropped.append(case)
            for i in range(start, end):
                out[i] = None
            continue
        quoted = json.dumps(spoken[index], ensure_ascii=False)
        subs = substitutions(case["text"], case["said"]) if case["said"] else []
        if case["style"] == "flow":
            m = FLOW_CASE.match(out[start])
            rest = m.group(3)
            body, tags = (rest.split(", stt:", 1) + [None])[:2]
            rest = apply(subs, body) + (", stt:" + tags if tags is not None else "")
            out[start] = m.group(1) + quoted + rest
        else:
            m = BLOCK_CASE.match(out[start])
            out[start] = m.group(1) + quoted
            for i in range(start + 1, end):
                k = BLOCK_KEY.match(out[i])
                if not (k and k.group(2) in ("said", "stt", "tool")):
                    out[i] = apply(subs, out[i])
    return header + "\n".join(line for line in out if line is not None), dropped


def finish(args):
    lines, cases = read_cases(args.cases)
    with open(os.path.join(args.work, "jobs.json"), encoding="utf-8") as f:
        jobs = json.load(f)
    voices = jobs["voices"]
    if jobs["cases"] != len(cases):
        sys.exit(f"{args.cases} changed since the transcription: run the script again")
    raw = open(os.path.join(args.work, "raw.txt"), encoding="utf-8").read().split("\n")[:-1]
    spoken = open(os.path.join(args.work, "spoken.txt"), encoding="utf-8").read().split("\n")[:-1]
    if not len(raw) == len(spoken) == len(voices) * len(cases):
        sys.exit("raw.txt and spoken.txt don't match: run the script again")
    os.makedirs(args.out, exist_ok=True)
    stem = os.path.splitext(os.path.basename(args.cases))[0]
    tsv_path = os.path.join(args.out, f"{stem}-whisper-{args.name}.tsv")
    written = []
    rows = []
    totals = {}
    with open(tsv_path, "w", encoding="utf-8") as tsv:
        tsv.write("voice\tcase\treference\twhisper\tspoken\terrors\n")
        for v, voice in enumerate(voices):
            heard = spoken[v * len(cases):(v + 1) * len(cases)]
            stats = {"words": 0, "errors": 0, "exact": 0, "empty": 0, "vosk_words": 0, "vosk_errors": 0}
            for index, case in enumerate(cases):
                reference = case["said"] or case["text"]
                errors = word_errors(reference, heard[index])
                stats["words"] += len(reference.split())
                stats["errors"] += errors
                stats["exact"] += heard[index] == reference
                stats["empty"] += not heard[index]
                if case["stt"] == "vosk":
                    stats["vosk_words"] += len(reference.split())
                    stats["vosk_errors"] += errors
                fields = [voice, str(index + 1), reference, raw[v * len(cases) + index], heard[index], str(errors)]
                tsv.write("\t".join(field.replace("\t", " ") for field in fields) + "\n")
            totals[voice] = stats
            header = (f"# Generated by gar/eval/stt/make-whisper-cases.sh on {datetime.date.today()} from "
                      f"{args.cases_label}:\n# each text is what Whisper {args.name} heard from macOS voice "
                      f"{voice}, through SpokenForm. Don't edit;\n# expectations are the source's (slot values "
                      f"of `said` cases follow what was said).\n")
            content, dropped = write_cases(lines, cases, heard, header)
            path = os.path.join(args.out, f"{stem}-whisper-{args.name}-{voice.lower()}.yml")
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            written.append(path)
            rows.append((voice, stats, dropped))

    print(f"{args.cases_label}, Whisper {args.name}: {len(cases)} cases, voices {', '.join(voices)}")
    print(f"{'voice':<12} {'WER':>6} {'exact':>7} {'empty':>6} {'stt: vosk WER':>14}")
    words = errors = exact = empty = vosk_words = vosk_errors = 0
    for voice, s, _ in rows:
        print(f"{voice:<12} {pct(s['errors'], s['words']):>6} {pct(s['exact'], len(cases)):>7} {s['empty']:>6} "
              f"{pct(s['vosk_errors'], s['vosk_words']):>14}")
        words += s["words"]; errors += s["errors"]; exact += s["exact"]; empty += s["empty"]
        vosk_words += s["vosk_words"]; vosk_errors += s["vosk_errors"]
    print(f"{'all':<12} {pct(errors, words):>6} {pct(exact, len(cases) * len(voices)):>7} {empty:>6} "
          f"{pct(vosk_errors, vosk_words):>14}")
    for voice, _, dropped in rows:
        for case in dropped:
            print(f"{voice}: heard nothing for '{case['text']}' (left out of its file)")
    print(f"\nTranscripts: {os.path.relpath(tsv_path)}")
    for path in written:
        print(f"Cases:       {os.path.relpath(path)}")
    print("\nRun the router eval on one (see gar/eval/README.md):")
    print(f"  CASES={os.path.relpath(written[0])} gar/eval/run-eval.sh "
          "~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf hermes")


def pct(part, whole):
    return f"{100.0 * part / whole:.1f}%" if whole else "-"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    t = sub.add_parser("transcribe")
    t.add_argument("--cases", required=True)
    t.add_argument("--model", required=True)
    t.add_argument("--whisper-cli", required=True)
    t.add_argument("--cache", required=True)
    t.add_argument("--work", required=True)
    t.add_argument("--threads", type=int, default=4)
    t.add_argument("--voices", required=True)
    f = sub.add_parser("finish")
    f.add_argument("--cases", required=True)
    f.add_argument("--cases-label", required=True)
    f.add_argument("--work", required=True)
    f.add_argument("--out", required=True)
    f.add_argument("--name", required=True)
    args = parser.parse_args()
    transcribe(args) if args.command == "transcribe" else finish(args)


if __name__ == "__main__":
    main()
