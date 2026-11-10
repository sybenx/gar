#!/usr/bin/env python3
"""Reads GAR's voice log (one JSON object per turn, gar/DESIGN.md "Voice log").

Pulls the log from the phone over adb (run-as, debug builds), or reads local files: the JSONL
files or the zip that "Share voice log" makes. Prints one row per turn, a per-stage failure
summary, and with --pull-wavs copies the WAVs of suspect turns plus a clips.yml snippet for
gar/stt/eval. Python 3 standard library only.

    python3 gar/tools/voice-log.py                      # adb, ANDROID_SERIAL or the only device
    python3 gar/tools/voice-log.py -s 4B091JEKB --suspect --since 2h
    python3 gar/tools/voice-log.py --file gar-voice-log-20261102.zip --skill telephone
    python3 gar/tools/voice-log.py --suspect --pull-wavs ~/.cache/gar/stt-eval/clips/voice-log
"""

import argparse
import datetime as dt
import json
import os
import re
import statistics
import subprocess
import sys
import zipfile
from collections import Counter

PACKAGE = "cc.aaro.gar.gar"  # the debug build of branch gar
LOG_DIR = "files/gar-voice-log"
LOG_NAME = "voice-log.jsonl"
ROTATED = re.compile(r"^voice-log\.(\d+)\.jsonl$")


# ---- sources ----

class Adb:
    """The phone, through `adb -s SERIAL exec-out run-as PACKAGE ...` (no root needed)."""

    def __init__(self, serial, package, run=subprocess.run):
        self.serial = serial
        self.package = package
        self.run = run

    def command(self, *args):
        base = ["adb"]
        if self.serial:
            base += ["-s", self.serial]
        return base + ["exec-out", "run-as", self.package] + list(args)

    def read(self, *args):
        result = self.run(self.command(*args), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode != 0:
            raise OSError(result.stderr.decode("utf-8", "replace").strip()
                          or "adb exited with %d" % result.returncode)
        return result.stdout

    def log_names(self):
        names = self.read("ls", LOG_DIR).decode("utf-8", "replace").split()
        return order_logs(names)

    def log_text(self):
        return "".join(self.read("cat", LOG_DIR + "/" + name).decode("utf-8", "replace")
                       for name in self.log_names())

    def wav(self, name):
        return self.read("cat", LOG_DIR + "/wav/" + name)


class LocalLog:
    """JSONL files, a folder of them (an unzipped share), or the shared zip."""

    def __init__(self, paths):
        self.paths = paths

    def _zip(self, path):
        return zipfile.ZipFile(path)

    def log_text(self):
        parts = []
        for path in self.paths:
            if zipfile.is_zipfile(path):
                with self._zip(path) as z:
                    names = {os.path.basename(n): n for n in z.namelist() if n.endswith(".jsonl")}
                    for name in order_logs(names):
                        parts.append(z.read(names[name]).decode("utf-8", "replace"))
            elif os.path.isdir(path):
                for name in order_logs(os.listdir(path)):
                    with open(os.path.join(path, name), encoding="utf-8") as f:
                        parts.append(f.read())
            else:
                with open(path, encoding="utf-8") as f:
                    parts.append(f.read())
        return "".join(p if p.endswith("\n") or not p else p + "\n" for p in parts)

    def wav(self, name):
        for path in self.paths:
            if zipfile.is_zipfile(path):
                with self._zip(path) as z:
                    for entry in z.namelist():
                        if entry.endswith("/wav/" + name):
                            return z.read(entry)
            else:
                folder = path if os.path.isdir(path) else os.path.dirname(path)
                candidate = os.path.join(folder, "wav", name)
                if os.path.exists(candidate):
                    with open(candidate, "rb") as f:
                        return f.read()
        raise OSError("no recording " + name)


def order_logs(names):
    """The log files among `names`, oldest first: voice-log.4.jsonl .. voice-log.jsonl."""
    rotated = sorted((int(m.group(1)), n) for n in names for m in [ROTATED.match(n)] if m)
    ordered = [n for _, n in reversed(rotated)]
    if LOG_NAME in names:
        ordered.append(LOG_NAME)
    return ordered


def parse(text, warn=lambda message: print(message, file=sys.stderr)):
    turns = []
    for number, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line:
            continue
        try:
            turn = json.loads(line)
        except ValueError:
            warn("skipping line %d: not JSON" % number)
            continue
        if isinstance(turn, dict):
            turns.append(turn)
    return turns


# ---- reading a turn ----

def when(turn):
    try:
        return dt.datetime.fromisoformat(turn.get("t", ""))
    except ValueError:
        return None


def heard(turn):
    """raw -> spoken for speech, the input for typed text and buttons; a follow-up's rewrite
    after "=>"."""
    raw = (turn.get("transcript") or {}).get("raw")
    spoken = turn.get("spoken") or turn.get("input")
    if raw is None:
        text = spoken or ""
    elif spoken is None or spoken == raw:
        text = raw
    else:
        text = "%s -> %s" % (raw, spoken)
    rewrite = (turn.get("follow_up") or {}).get("rewrite")
    return "%s => %s" % (text, rewrite) if rewrite else text


def follow_up(turn):
    """The follow-up skill's outcome: `rewritten`, `asked`, `deferred:media`, ...; None if the
    input wasn't a follow-up form."""
    f = turn.get("follow_up")
    if not f:
        return None
    outcome = f.get("outcome", "?")
    if f.get("deferred_to"):
        outcome += ":" + f["deferred_to"]
    return outcome


def route(turn):
    """The path that won, with the follow-up's outcome first when the input was one."""
    f = follow_up(turn)
    return "follow_up:%s %s" % (f, _path(turn)) if f else _path(turn)


def _path(turn):
    match = turn.get("match") or {}
    model = turn.get("model") or {}
    path = match.get("path") or "-"
    if path == "model" and model.get("tool"):
        return "model:" + model["tool"]
    if model.get("outcome") in ("dropped", "no_match", "failed"):
        return "%s (model %s)" % (path, model["outcome"])
    if match.get("check") == "reject" and path != "sentence":
        return "%s (rejected %s)" % (path, match.get("checked_skill", "?"))
    return path


def answer(turn):
    """The skill that answered ("(shown)" when its speech was only shown: typed input), or why
    there was none."""
    a = turn.get("answer") or {}
    outcome = a.get("outcome", "-")
    if outcome == "answered":
        return a.get("skill", "?") + (" (shown)" if a.get("spoken") is False else "")
    if a.get("skill"):
        return "%s:%s" % (outcome, a["skill"])
    if outcome == "no_input":
        end = (turn.get("capture") or {}).get("end")
        filtered = (turn.get("transcript") or {}).get("filter_reason")
        return "no_input" + ("(%s)" % (filtered or end) if (filtered or end) else "")
    return outcome


def latency(turn):
    l = turn.get("latency") or {}
    for key in ("to_first_word_ms", "to_route_ms", "to_text_ms"):
        if key in l:
            return "%d ms" % l[key]
    return "-"


def jank(turn):
    """The share of janky frames GAR's windows drew during the turn (GarJank), or "-"."""
    f = turn.get("frames") or {}
    if not f.get("n"):
        return "-"
    return "%d%%" % round(100.0 * f.get("janky", 0) / f["n"])


def suspect(turn):
    return ",".join(turn.get("suspect") or [])


def skills(turn):
    """Every skill or tool the turn touched, for --skill."""
    found = set()
    a = turn.get("answer") or {}
    if a.get("skill"):
        found.add(a["skill"])
    m = turn.get("model") or {}
    if m.get("tool"):
        found.add(m["tool"])
    candidates = (turn.get("match") or {}).get("candidates") or []
    if candidates and candidates[0].get("skill"):
        found.add(candidates[0]["skill"])  # Dicio's best guess, also when another path won
    return found


# ---- filters ----

RELATIVE = re.compile(r"^(\d+)\s*([smhd])$")


def parse_since(text, now=None):
    """`2h`, `30m`, `1d`, `2026-11-02`, `2026-11-02T14:00`; local time unless it has an offset."""
    now = now or dt.datetime.now().astimezone()
    m = RELATIVE.match(text.strip())
    if m:
        seconds = int(m.group(1)) * {"s": 1, "m": 60, "h": 3600, "d": 86400}[m.group(2)]
        return now - dt.timedelta(seconds=seconds)
    value = dt.datetime.fromisoformat(text.strip())
    if value.tzinfo is None:
        value = value.astimezone()
    return value


def select(turns, only_suspect=False, since=None, skill=None):
    out = []
    for turn in turns:
        if only_suspect and not turn.get("suspect"):
            continue
        if since is not None:
            t = when(turn)
            if t is None or t < since:
                continue
        if skill and skill not in skills(turn):
            continue
        out.append(turn)
    return out


# ---- output ----

def clip(text, width):
    return text if len(text) <= width else text[:width - 1] + "…"


def table(turns):
    rows = [("time", "start", "heard", "route", "answer", "latency", "jank", "suspect")]
    for turn in turns:
        t = when(turn)
        rows.append((
            t.strftime("%m-%d %H:%M:%S") if t else "?",
            turn.get("start", "?"),
            clip(heard(turn), 60),
            clip(route(turn), 28),
            clip(answer(turn), 26),
            latency(turn),
            jank(turn),
            suspect(turn),
        ))
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    lines = []
    for i, row in enumerate(rows):
        lines.append("  ".join(cell.ljust(widths[j]) for j, cell in enumerate(row)).rstrip())
        if i == 0:
            lines.append("  ".join("-" * w for w in widths))
    return "\n".join(lines)


def percentile(values, share):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(share * (len(ordered) - 1))))]


def summary(turns):
    """Counts per stage: where turns stopped or went wrong, and the latencies."""
    total = len(turns)
    lines = ["%d turns, %d suspect" % (total, sum(1 for t in turns if t.get("suspect")))]
    if not total:
        return "\n".join(lines)

    def section(title, counter):
        if counter:
            lines.append(title + ": " + ", ".join("%s %d" % (k, v) for k, v in counter.most_common()))

    section("start", Counter(t.get("start", "?") for t in turns))
    section("capture end", Counter((t.get("capture") or {}).get("end")
                                   for t in turns if (t.get("capture") or {}).get("end")))
    section("transcript filter", Counter(
        (t.get("transcript") or {}).get("filter_reason") or "accept"
        for t in turns if t.get("transcript")))
    section("route", Counter((t.get("match") or {}).get("path") for t in turns
                             if (t.get("match") or {}).get("path")))
    section("match check", Counter((t.get("match") or {}).get("check") for t in turns
                                   if (t.get("match") or {}).get("check")))
    section("model", Counter((t.get("model") or {}).get("outcome") for t in turns
                             if t.get("model")))
    section("model drops", Counter(
        (t.get("model") or {}).get("filter_reason", "?")
        for t in turns if (t.get("model") or {}).get("outcome") == "dropped"))
    section("follow_up", Counter(follow_up(t) for t in turns if t.get("follow_up")))
    section("answer", Counter((t.get("answer") or {}).get("outcome", "none") for t in turns))
    shown = sum(1 for t in turns if (t.get("answer") or {}).get("spoken") is False)
    if shown:
        lines.append("answers shown only: %d" % shown)
    silent = sum(t.get("continued_silent", 0) for t in turns)
    hallucinated = sum(t.get("continued_hallucination", 0) for t in turns)
    if silent or hallucinated:
        lines.append("continued windows without a turn: silent %d, hallucination %d"
                     % (silent, hallucinated))
    section("errors", Counter((t.get("answer") or {}).get("error_class") for t in turns
                              if (t.get("answer") or {}).get("error_class")))
    section("suspect", Counter(r for t in turns for r in t.get("suspect") or []))
    for key in ("to_text_ms", "to_route_ms", "to_first_word_ms"):
        values = [t["latency"][key] for t in turns if key in (t.get("latency") or {})]
        if values:
            lines.append("%s: median %d, p90 %d (%d turns)" % (
                key, statistics.median(values), percentile(values, 0.9), len(values)))
    framed = [t["frames"] for t in turns if (t.get("frames") or {}).get("n")]
    if framed:
        frames = sum(f["n"] for f in framed)
        janky = sum(f.get("janky", 0) for f in framed)
        worst = max(f.get("max_ms") or 0 for f in framed)
        rates = sorted({f["hz"] for f in framed if f.get("hz")})
        lines.append("frames: %d in %d turns, janky %d (%.1f%%), slowest %.0f ms%s" % (
            frames, len(framed), janky, 100.0 * janky / frames, worst,
            ", at %s Hz" % "/".join(str(r) for r in rates) if rates else ""))
    return "\n".join(lines)


def yaml_string(text):
    return json.dumps(text, ensure_ascii=False)  # a JSON string is a valid YAML scalar


# the retry says again what the suspect turn should have heard
RETRY_REASONS = ("repeat", "edited_transcript", "i_said")


def said_text(turn, by_id):
    """What the suspect `turn` likely said, from the turn that marked it (`suspect_by`): the
    retry's input for a repeat, an edit or "no I said ...", else None. A rephrase uses other
    words, so it says nothing about this recording."""
    reasons = turn.get("suspect") or []
    retry = by_id.get(turn.get("suspect_by"))
    if not retry or not retry.get("input") or not any(r in RETRY_REASONS for r in reasons):
        return None
    words = retry["input"].split()
    if "i_said" in reasons:
        lowered = [re.sub(r"[^a-z']", "", w.lower()) for w in words]
        start = 1 if lowered[:1] == ["no"] else 0
        if lowered[start:start + 2] == ["i", "said"]:
            words = words[start + 2:]
    return " ".join(words) or None


def pull_wavs(turns, source, target, all_turns=None):
    """Copies the suspect turns' WAVs into `target` and writes target/clips.yml for gar/stt/eval
    (run.py --manifest target/clips.yml --modes none,recorded). `all_turns` (default `turns`)
    finds the retry that marked each one, which is rarely suspect itself.

    A clip's `text` is the retry's words when there is one, to check; else it is left out, so
    that run.py refuses the file until every clip says what was said."""
    os.makedirs(target, exist_ok=True)
    by_id = {t.get("id"): t for t in (all_turns if all_turns is not None else turns)}
    entries = []
    owners = Counter()
    for turn in turns:
        name = turn.get("wav")
        if not turn.get("suspect") or not name:
            continue
        try:
            data = source.wav(name)
        except OSError as e:
            print("no WAV for %s: %s" % (turn.get("id"), e), file=sys.stderr)
            continue
        path = os.path.abspath(os.path.join(target, name))
        with open(path, "wb") as f:
            f.write(data)
        prompt = turn.get("prompt") or {}
        if prompt.get("owner"):
            owners[prompt["owner"]] += 1
        heard_text = turn.get("spoken") or turn.get("input") or ""
        guess = said_text(turn, by_id)
        if guess is not None:
            text_line = ("    # text is the retry's words (%s): check it against the recording\n"
                         "    text: %s\n" % (turn.get("suspect_by"), yaml_string(guess)))
        else:
            text_line = "    # text: TODO what was said (run.py refuses the file until it is set)\n"
        entries.append(
            "  - id: vl-%s\n"
            "    # heard %s; suspect: %s\n"
            "%s"
            "    type: voice-log\n"
            "    names: []\n"
            "    prompt: [%s]\n"
            "    wav: %s\n" % (
                turn["id"], yaml_string(heard_text), suspect(turn), text_line,
                ", ".join(yaml_string(n) for n in prompt.get("names") or []), yaml_string(path)))
    with open(os.path.join(target, "clips.yml"), "w", encoding="utf-8") as f:
        f.write("# from gar/tools/voice-log.py --pull-wavs: real recordings, a held-out set.\n"
                "# Replay: gar/stt/eval/run.py --manifest %s --modes none,recorded --label voice-log\n"
                % yaml_string(os.path.join(os.path.abspath(target), "clips.yml")))
        f.write("# names: the contact names said in each clip, for name accuracy (fill in)\n")
        owner = owners.most_common(1)[0][0] if owners else ""
        f.write("owner: %s\n" % yaml_string(owner))
        f.write("starred: []\n")
        f.write("contacts: []\n")
        f.write("clips:\n")
        f.writelines(entries)
    return len(entries)


def main(argv=None, run=subprocess.run, out=sys.stdout):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL"),
                        help="adb device serial (default: ANDROID_SERIAL, else adb's only device)")
    parser.add_argument("--package", default=PACKAGE, help="app id (default %(default)s)")
    parser.add_argument("--file", action="append", metavar="PATH",
                        help="read a JSONL file, a folder or the shared zip instead of adb")
    parser.add_argument("--suspect", action="store_true", help="only suspect turns")
    parser.add_argument("--since", help="only turns after this: 2h, 30m, 1d or an ISO date")
    parser.add_argument("--skill", help="only turns that touched this skill id or tool")
    parser.add_argument("--summary-only", action="store_true", help="no table")
    parser.add_argument("--pull-wavs", metavar="DIR",
                        help="copy the selected suspect turns' WAVs to DIR, with a clips.yml")
    parser.add_argument("--json", action="store_true", help="print the selected turns as JSONL")
    args = parser.parse_args(argv)

    source = LocalLog(args.file) if args.file else Adb(args.serial, args.package, run)
    try:
        turns = parse(source.log_text())
    except OSError as e:
        print("could not read the voice log: %s" % e, file=sys.stderr)
        return 1
    since = parse_since(args.since) if args.since else None
    every_turn = turns
    turns = select(turns, args.suspect, since, args.skill)

    if args.json:
        for turn in turns:
            out.write(json.dumps(turn, ensure_ascii=False) + "\n")
        return 0
    if not args.summary_only:
        out.write(table(turns) + "\n\n")
    out.write(summary(turns) + "\n")
    if args.pull_wavs:
        count = pull_wavs(turns, source, os.path.expanduser(args.pull_wavs), every_turn)
        out.write("%d recordings in %s\n" % (count, args.pull_wavs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
