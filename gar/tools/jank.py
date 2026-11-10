#!/usr/bin/env python3
"""Measures GAR's startup and frame times on the emulator (gar/DESIGN.md, "Frame rate").

Emulator only: gartest renders in software at 60 Hz, so its numbers are for comparing a build
before and after a change, never a verdict on the phone (that comes from the voice log's
`frames`, GarJank on the Pixel). Python 3 standard library only.

    python3 gar/tools/jank.py startup --runs 5 --label before
    python3 gar/tools/jank.py session --json > after.json
    python3 gar/tools/jank.py sheet --runs 10
"""

import argparse
import json
import math
import re
import subprocess
import sys
import time

SERIAL = "emulator-5580"
PACKAGE = "cc.aaro.gar.gar"  # the debug build of branch gar
ACTIVITY = "org.stypox.dicio.MainActivity"
NOTE = "emulator, software rendering, 60 Hz: compare before/after only"
POLL_SECONDS = 0.5
DISPLAYED_SECONDS = 30
UI_WAIT_SECONDS = 40  # a cold debug start on gartest takes 10 to 20 s to draw
COLD_TRIES = 3  # force-stops before a run counts as process_warm
COMMANDS = ["what time is it", "what's my battery", "what is 12 times 7"]


# ---- parsing (pure, tested on testdata/) ----

def percentile(values, share):
    """Nearest rank, like GarFrameStats: the smallest value with `share` of them at or below."""
    ordered = sorted(values)
    if not ordered:
        return None
    # the margin keeps a product like 0.9 * 10 from rounding up past its rank
    rank = math.ceil(share * len(ordered) - 1e-9)
    return ordered[min(max(rank, 1), len(ordered)) - 1]


def spread(values):
    """p50, p90 and max of `values`, or None for none."""
    if not values:
        return None
    return {"n": len(values), "p50": percentile(values, 0.5), "p90": percentile(values, 0.9),
            "max": max(values)}


def parse_am_start(text):
    """`am start -W` output: TotalTime, WaitTime (ms) and LaunchState."""
    out = {}
    for key in ("TotalTime", "WaitTime"):
        m = re.search(r"^%s: (\d+)" % key, text, re.M)
        if m:
            out[key] = int(m.group(1))
    m = re.search(r"^LaunchState: (\w+)", text, re.M)
    if m:
        out["LaunchState"] = m.group(1)
    return out


DURATION = re.compile(r"\+(?:(\d+)m)?(?:(\d+)s)?(?:(\d+)ms)?")


def parse_duration(text):
    """ActivityTaskManager's `+1s234ms`, `+850ms`, `+1m2s3ms` in milliseconds."""
    m = DURATION.fullmatch(text.strip())
    if not m or not any(m.groups()):
        return None
    minutes, seconds, millis = (int(g) if g else 0 for g in m.groups())
    return (minutes * 60 + seconds) * 1000 + millis


def parse_displayed(logcat, package):
    """The `Displayed` and `Fully drawn` times (ms) for `package` in a logcat dump."""
    out = {}
    for key, word in (("displayed", "Displayed"), ("fully_drawn", "Fully drawn")):
        # API 35 adds " for user 0" before the colon
        m = re.search(r"%s %s/\S+(?: for user \d+)?: (\+\S+)" % (word, re.escape(package)),
                      logcat)
        if m:
            value = parse_duration(m.group(1))
            if value is not None:
                out[key] = value
    return out


def parse_gfxinfo(text):
    """The whole-session numbers of `dumpsys gfxinfo <pkg>` (the first, process-wide block)."""
    out = {}

    def first(pattern, convert=int):
        m = re.search(pattern, text, re.M)
        return convert(m.group(1)) if m else None

    out["frames"] = first(r"^Total frames rendered: (\d+)")
    out["janky"] = first(r"^Janky frames: (\d+)")
    out["janky_pct"] = first(r"^Janky frames: \d+ \(([\d.]+)%\)", float)
    for p in (50, 90, 95, 99):
        out["p%d_ms" % p] = first(r"^%dth percentile: (\d+)ms" % p)
    for key, label in (("missed_vsync", "Missed Vsync"), ("slow_ui", "Slow UI thread"),
                       ("slow_bitmap", "Slow bitmap uploads"),
                       ("slow_draw", "Slow issue draw commands"),
                       ("deadline_missed", "Frame deadline missed")):
        out[key] = first(r"^Number %s: (\d+)" % re.escape(label))
    m = re.search(r"^HISTOGRAM: (.*)$", text, re.M)
    histogram = {}
    if m:
        for item in m.group(1).split():
            bucket, _, count = item.partition("=")
            if bucket.endswith("ms") and count.isdigit() and int(count):
                histogram[int(bucket[:-2])] = int(count)
    out["histogram"] = histogram
    return {k: v for k, v in out.items() if v is not None}


def parse_framestats(text, default_interval_ns=16_666_667):
    """The PROFILEDATA rows of `dumpsys gfxinfo <pkg> framestats` (the last 120 frames per
    window). Rows with Flags != 0 (first frame, layout or window size change) are returned
    apart, with their durations, not dropped."""
    frames, flagged = [], []
    header = None
    inside = False
    for line in text.splitlines():
        line = line.strip()
        if line == "---PROFILEDATA---":
            inside = not inside
            header = None
            continue
        if not inside or not line:
            continue
        cells = line.split(",")
        if cells and cells[-1] == "":
            cells = cells[:-1]
        if header is None:
            header = cells
            continue
        row = dict(zip(header, cells))
        try:
            flags = int(row["Flags"])
            intended = int(row["IntendedVsync"])
            completed = int(row["FrameCompleted"])
        except (KeyError, ValueError):
            continue
        if intended <= 0 or completed <= 0:
            continue
        duration = completed - intended
        deadline = int(row.get("FrameDeadline") or 0)
        interval = int(row.get("FrameInterval") or 0) or default_interval_ns
        budget = deadline - intended if deadline > intended else int(1.5 * interval)
        frame = {"ms": round(duration / 1e6, 2), "janky": duration > budget, "flags": flags}
        (flagged if flags else frames).append(frame)
    return frames, flagged


GARJANK = re.compile(r"GarJank: (window|stop|slow) (.*)$")


def parse_garjank(logcat):
    """The `GarJank:` lines of a logcat dump, as dicts with their kind and fields."""
    out = []
    for line in logcat.splitlines():
        m = GARJANK.search(line)
        if not m:
            continue
        item = {"kind": m.group(1)}
        for field in m.group(2).split():
            key, sep, value = field.partition("=")
            if not sep:
                continue
            try:
                item[key] = int(value)
            except ValueError:
                try:
                    item[key] = float(value)
                except ValueError:
                    item[key] = value
        out.append(item)
    return out


SHEET = re.compile(r"GarSheet: first_frame_ms=(\d+) source=(\w+) cold=(\w+)")


def parse_sheet(logcat):
    """The `GarSheet: first_frame_ms=` lines: (ms, source, cold) each."""
    return [(int(m.group(1)), m.group(2), m.group(3) == "true")
            for m in SHEET.finditer(logcat)]


def find_text_field(xml):
    """The centre of the first EditText in a `uiautomator dump`, or None."""
    for m in re.finditer(r"<node [^>]*>", xml):
        node = m.group(0)
        if 'class="android.widget.EditText"' not in node:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = (int(v) for v in b.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def find_text_toggle(xml):
    """The centre of the top bar's rightmost button (Dicio's "type instead" button), or None.

    Dicio shows the text field only after that button; the top bar is the top tenth of the
    screen, below the status bar."""
    nodes = []
    for m in re.finditer(r"<node [^>]*>", xml):
        node = m.group(0)
        if 'clickable="true"' not in node and 'class="android.widget.Button"' not in node:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            nodes.append(tuple(int(v) for v in b.groups()))
    if not nodes:
        return None
    bottom = max(y2 for _, _, _, y2 in nodes)
    top_bar = [n for n in nodes if n[1] > 0 and n[3] <= bottom // 10 + 200]
    if not top_bar:
        return None
    x1, y1, x2, y2 = max(top_bar, key=lambda n: n[2])
    return (x1 + x2) // 2, (y1 + y2) // 2


def input_text_arg(text):
    """`input text` wants %s for spaces and no shell specials."""
    return "'" + text.replace("'", "").replace(" ", "%s") + "'"


# ---- the emulator ----

class Emulator:
    """adb -s <emulator serial>; refuses anything that isn't an emulator."""

    def __init__(self, serial, package, run=subprocess.run, sleep=time.sleep):
        if not serial.startswith("emulator-"):
            raise ValueError("jank.py only runs on the emulator, not %r" % serial)
        self.serial = serial
        self.package = package
        self.run = run
        self.sleep = sleep

    def shell(self, *args, check=False):
        result = self.run(["adb", "-s", self.serial, "shell"] + list(args),
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        text = result.stdout.decode("utf-8", "replace")
        if check and result.returncode != 0:
            raise OSError(result.stderr.decode("utf-8", "replace").strip() or text.strip())
        return text

    def logcat(self):
        result = self.run(["adb", "-s", self.serial, "logcat", "-d"],
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        return result.stdout.decode("utf-8", "replace")

    def pid(self):
        """The package's process id, or "" when it isn't running."""
        return self.shell("pidof", self.package).strip()

    def clear_logcat(self):
        self.run(["adb", "-s", self.serial, "logcat", "-c"],
                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def start(self):
        return self.shell("am", "start", "-W", "-a", "android.intent.action.MAIN",
                          "-c", "android.intent.category.LAUNCHER",
                          "-n", "%s/%s" % (self.package, ACTIVITY))

    def wait_for(self, find, seconds):
        """Polls logcat until `find(logcat)` is truthy, at most `seconds`; returns the dump."""
        for _ in range(int(seconds / POLL_SECONDS)):
            text = self.logcat()
            if find(text):
                return text
            self.sleep(POLL_SECONDS)
        return self.logcat()


def run_startup(emu, runs, out):
    """Cold runs whose process was back before `am start` (the voice interaction service restarts
    it after `am force-stop` while GAR is the assistant) count as process_warm, not cold."""
    results = {"cold": [], "process_warm": [], "warm": []}
    for kind in ("cold", "warm"):
        for _ in range(runs):
            bucket = kind
            if kind == "cold":
                for _ in range(COLD_TRIES):
                    emu.shell("am", "force-stop", emu.package)
                    emu.sleep(2)
                    alive = emu.pid()
                    if not alive:
                        break
                if alive:
                    bucket = "process_warm"
            else:
                emu.shell("input", "keyevent", "KEYCODE_HOME")
                emu.sleep(2)
            emu.clear_logcat()
            run = parse_am_start(emu.start())
            # a cold debug start on gartest can draw well after `am start -W` gives up (10 s)
            emu.wait_for(lambda t: "Displayed %s/" % emu.package in t, DISPLAYED_SECONDS)
            text = emu.wait_for(lambda t: "Fully drawn %s/" % emu.package in t, 5)
            run.update(parse_displayed(text, emu.package))
            results[bucket].append(run)
            out.write("  %s: %s\n" % (bucket, run))
    summary = {}
    for kind, items in results.items():
        if items:
            summary[kind] = {key: spread([r[key] for r in items if key in r])
                             for key in ("TotalTime", "WaitTime", "displayed", "fully_drawn")}
    return {"runs": results, "summary": summary}


def wait_for_text_field(emu, seconds):
    """The text field's centre, at most `seconds` after a cold start; opens it with the top bar's
    button once if GAR is on screen without it. None if it never shows."""
    dump_path = "/sdcard/gar-jank-ui.xml"
    toggled = False
    for _ in range(int(seconds)):
        emu.shell("rm", "-f", dump_path)  # a failed dump leaves the last one
        emu.shell("uiautomator", "dump", dump_path)
        xml = emu.shell("cat", dump_path)
        field = find_text_field(xml)
        if field is not None:
            return field
        toggle = find_text_toggle(xml) if 'package="%s"' % emu.package in xml else None
        if toggle is not None and not toggled:
            emu.shell("input", "tap", str(toggle[0]), str(toggle[1]))
            toggled = True
        emu.sleep(1)
    return None


def run_session(emu, commands, answer_seconds, out):
    emu.shell("dumpsys", "gfxinfo", emu.package, "reset")
    emu.clear_logcat()
    emu.start()
    emu.sleep(3)
    startup = None
    for command in commands:
        field = wait_for_text_field(emu, UI_WAIT_SECONDS)
        if field is None:
            raise OSError("no text field on screen to type %r into" % command)
        if startup is None:
            # the start's frames apart, so that the session's are the conversation's only
            startup = parse_gfxinfo(emu.shell("dumpsys", "gfxinfo", emu.package))
            emu.shell("dumpsys", "gfxinfo", emu.package, "reset")
        emu.shell("input", "tap", str(field[0]), str(field[1]))
        emu.sleep(0.5)
        emu.shell("input", "text", input_text_arg(command))
        emu.shell("input", "keyevent", "KEYCODE_ENTER")
        out.write("  typed %r\n" % command)
        emu.sleep(answer_seconds)
    size = re.search(r"Physical size: (\d+)x(\d+)", emu.shell("wm", "size"))
    width, height = (int(size.group(1)), int(size.group(2))) if size else (1080, 2400)
    for _ in range(3):
        emu.shell("input", "swipe", str(width // 2), str(height * 3 // 10),
                  str(width // 2), str(height * 7 // 10), "300")
        emu.sleep(1)
    gfx = parse_gfxinfo(emu.shell("dumpsys", "gfxinfo", emu.package))
    frames, flagged = parse_framestats(emu.shell("dumpsys", "gfxinfo", emu.package, "framestats"))
    return {
        "startup_gfxinfo": startup or {},
        "gfxinfo": gfx,
        "framestats": {
            "note": "last 120 frames per window only",
            "frames": len(frames),
            "janky": sum(1 for f in frames if f["janky"]),
            "ms": spread([f["ms"] for f in frames]),
            "flagged": flagged,
        },
        "garjank": parse_garjank(emu.logcat()),
    }


def run_sheet(emu, runs, out):
    service = emu.shell("settings", "get", "secure", "voice_interaction_service").strip()
    if not service.startswith(emu.package + "/"):
        raise OSError("GAR is not the emulator's assistant (voice_interaction_service=%s)"
                      % (service or "unset"))
    times = []
    for _ in range(runs):
        emu.clear_logcat()
        # the shell may not call `cmd voiceinteraction show` (ACCESS_VOICE_INTERACTION_SERVICE
        # on API 35), so the assist key opens the session like the corner swipe does
        emu.shell("input", "keyevent", "KEYCODE_ASSIST")
        text = emu.wait_for(lambda t: parse_sheet(t), 10)
        found = parse_sheet(text)
        if found:
            times.append(found[0])
            out.write("  first_frame_ms=%d source=%s cold=%s\n" % found[0])
        else:
            out.write("  no GarSheet line within 10 s\n")
        emu.shell("input", "keyevent", "KEYCODE_BACK")
        emu.sleep(2)
    return {"runs": [{"ms": t[0], "source": t[1], "cold": t[2]} for t in times],
            "first_frame_ms": spread([t[0] for t in times])}


# ---- output ----

def fmt(stats):
    if not stats:
        return "-"
    return "p50 %s, p90 %s, max %s (%d)" % (stats["p50"], stats["p90"], stats["max"], stats["n"])


def report(mode, result, label):
    lines = ["jank.py %s%s: %s" % (mode, " [%s]" % label if label else "", NOTE)]
    if mode == "startup":
        for kind, keys in result["summary"].items():
            for key, stats in keys.items():
                lines.append("%s %s: %s" % (kind, key, fmt(stats)))
    elif mode == "session":
        g = result["startup_gfxinfo"]
        lines.append("start to the text field (gfxinfo, apart): %s frames, %s janky, p90 %s ms"
                     % (g.get("frames"), g.get("janky"), g.get("p90_ms")))
        g = result["gfxinfo"]
        lines.append("session after the start (gfxinfo): %s frames, %s janky (%s%%), p50 %s ms, p90 %s ms, "
                     "p99 %s ms" % (g.get("frames"), g.get("janky"), g.get("janky_pct"),
                                    g.get("p50_ms"), g.get("p90_ms"), g.get("p99_ms")))
        lines.append("  slow UI thread %s, deadline missed %s, missed vsync %s" % (
            g.get("slow_ui"), g.get("deadline_missed"), g.get("missed_vsync")))
        if g.get("histogram"):
            lines.append("  histogram: " + " ".join(
                "%dms=%d" % (k, v) for k, v in sorted(g["histogram"].items())))
        f = result["framestats"]
        lines.append("framestats (%s): %d frames, %d janky, %s ms" % (
            f["note"], f["frames"], f["janky"], fmt(f["ms"])))
        if f["flagged"]:
            lines.append("  flagged frames (first frame, layout or size change): " + ", ".join(
                "%.1f ms (flags %d)" % (x["ms"], x["flags"]) for x in f["flagged"]))
        for item in result["garjank"]:
            lines.append("  GarJank %s: %s" % (item["kind"], " ".join(
                "%s=%s" % (k, v) for k, v in item.items() if k != "kind")))
    elif mode == "sheet":
        lines.append("sheet first frame: %s" % fmt(result["first_frame_ms"]))
    return "\n".join(lines)


def main(argv=None, run=subprocess.run, out=sys.stdout, sleep=time.sleep):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("mode", choices=["startup", "session", "sheet"])
    parser.add_argument("-s", "--serial", default=SERIAL,
                        help="emulator serial (default %(default)s); phones are refused")
    parser.add_argument("--package", default=PACKAGE, help="app id (default %(default)s)")
    parser.add_argument("--runs", type=int, default=5, help="runs per kind (startup, sheet)")
    parser.add_argument("--command", action="append",
                        help="a command to type in session (repeatable; default three)")
    parser.add_argument("--answer-seconds", type=float, default=6,
                        help="wait after each typed command (default %(default)s)")
    parser.add_argument("--label", help="a name for this run, kept in the output")
    parser.add_argument("--json", action="store_true", help="print the result as JSON")
    args = parser.parse_args(argv)

    try:
        emu = Emulator(args.serial, args.package, run, sleep)
    except ValueError as e:
        print(e, file=sys.stderr)
        return 2
    progress = sys.stderr if args.json else out
    try:
        if args.mode == "startup":
            result = run_startup(emu, args.runs, progress)
        elif args.mode == "session":
            result = run_session(emu, args.command or COMMANDS, args.answer_seconds, progress)
        else:
            result = run_sheet(emu, args.runs, progress)
    except OSError as e:
        print("jank.py %s: %s" % (args.mode, e), file=sys.stderr)
        return 1
    if args.json:
        out.write(json.dumps({"mode": args.mode, "label": args.label, "note": NOTE,
                              "result": result}, indent=1) + "\n")
    else:
        out.write(report(args.mode, result, args.label) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
