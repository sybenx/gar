"""Tests for jank.py on saved emulator output in testdata/.

    python3 -m unittest discover -s gar/tools -p 'test_*.py'
"""

import importlib.util
import io
import json
import os
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "testdata")

_spec = importlib.util.spec_from_file_location("jank", os.path.join(HERE, "jank.py"))
jank = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(jank)


def read(name):
    with open(os.path.join(DATA, name), encoding="utf-8") as f:
        return f.read()


class ParseTest(unittest.TestCase):
    def test_am_start(self):
        self.assertEqual(jank.parse_am_start(read("am-start.txt")),
                         {"TotalTime": 6843, "WaitTime": 6851, "LaunchState": "COLD"})

    def test_durations(self):
        self.assertEqual(jank.parse_duration("+1s234ms"), 1234)
        self.assertEqual(jank.parse_duration("+850ms"), 850)
        self.assertEqual(jank.parse_duration("+1m2s3ms"), 62003)
        self.assertEqual(jank.parse_duration("+2s"), 2000)
        self.assertIsNone(jank.parse_duration("+"))

    def test_displayed_and_fully_drawn(self):
        self.assertEqual(jank.parse_displayed(read("garjank-logcat.txt"), "cc.aaro.gar.gar"),
                         {"displayed": 1234, "fully_drawn": 2005})
        self.assertEqual(jank.parse_displayed(read("garjank-logcat.txt"), "cc.aaro.gar"), {})
        api35 = ("I ActivityTaskManager: Displayed cc.aaro.gar.gar/org.stypox.dicio.MainActivity "
                 "for user 0: +12s373ms\n")
        self.assertEqual(jank.parse_displayed(api35, "cc.aaro.gar.gar"), {"displayed": 12373})

    def test_gfxinfo_whole_session(self):
        g = jank.parse_gfxinfo(read("gfxinfo.txt"))
        self.assertEqual(g["frames"], 412)
        self.assertEqual(g["janky"], 37)
        self.assertEqual(g["janky_pct"], 8.98)
        self.assertEqual((g["p50_ms"], g["p90_ms"], g["p95_ms"], g["p99_ms"]), (9, 19, 27, 81))
        self.assertEqual(g["slow_ui"], 21)
        self.assertEqual(g["deadline_missed"], 33)
        self.assertEqual(g["missed_vsync"], 6)
        # only the frame histogram, not the GPU one; empty buckets left out
        self.assertEqual(g["histogram"][5], 40)
        self.assertEqual(g["histogram"][81], 1)
        self.assertNotIn(21, g["histogram"])
        self.assertEqual(sum(g["histogram"].values()), 412)

    def test_framestats_reports_flagged_rows_apart(self):
        frames, flagged = jank.parse_framestats(read("gfxinfo-framestats.txt"))
        self.assertEqual([f["ms"] for f in frames], [8.33, 30.0, 9.0])
        self.assertEqual([f["janky"] for f in frames], [False, True, False])
        self.assertEqual([(f["ms"], f["flags"]) for f in flagged], [(60.0, 1), (46.0, 4)])

    def test_framestats_without_a_deadline_uses_one_and_a_half_intervals(self):
        text = "\n".join([
            "---PROFILEDATA---",
            "Flags,IntendedVsync,FrameCompleted,",
            "0,1000000000,1020000000,",  # 20 ms: within 1.5 x 16.7 ms
            "0,2000000000,2030000000,",  # 30 ms: janky
            "---PROFILEDATA---",
        ])
        frames, flagged = jank.parse_framestats(text)
        self.assertEqual([f["janky"] for f in frames], [False, True])
        self.assertEqual(flagged, [])

    def test_garjank_lines(self):
        items = jank.parse_garjank(read("garjank-logcat.txt"))
        self.assertEqual([i["kind"] for i in items], ["window", "slow", "stop"])
        self.assertEqual(items[0]["frames"], 120)
        self.assertEqual(items[0]["p90_ms"], 17.2)
        self.assertEqual(items[0]["surface"], "home")
        self.assertEqual(items[1]["states"], "mic:thinking")

    def test_sheet_lines(self):
        self.assertEqual(jank.parse_sheet(read("garjank-logcat.txt")),
                         [(212, "assist", False), (640, "hotword", True)])

    def test_percentile_and_spread(self):
        self.assertEqual(jank.percentile([3, 1, 2], 0.5), 2)
        self.assertEqual(jank.percentile(list(range(1, 11)), 0.9), 9)
        self.assertIsNone(jank.percentile([], 0.5))
        self.assertEqual(jank.spread([5, 1, 9]), {"n": 3, "p50": 5, "p90": 9, "max": 9})
        self.assertIsNone(jank.spread([]))

    def test_text_field_and_typed_text(self):
        xml = ('<hierarchy><node class="android.widget.TextView" bounds="[0,0][10,10]" />'
               '<node index="0" text="" class="android.widget.EditText" '
               'bounds="[42,2100][1038,2240]" /></hierarchy>')
        self.assertEqual(jank.find_text_field(xml), (540, 2170))
        self.assertIsNone(jank.find_text_field("<hierarchy/>"))
        self.assertEqual(jank.input_text_arg("what's my battery"), "'whats%smy%sbattery'")

    def test_text_toggle(self):
        xml = ('<hierarchy>'
               '<node class="android.widget.Button" clickable="true" bounds="[21,157][126,283]" />'
               '<node class="android.widget.Button" clickable="true" bounds="[954,168][1060,274]" />'
               '<node class="android.widget.Button" clickable="true" bounds="[466,2148][614,2294]" />'
               '<node class="android.view.View" clickable="false" bounds="[0,0][1080,2400]" />'
               '</hierarchy>')
        self.assertEqual(jank.find_text_toggle(xml), (1007, 221))
        self.assertIsNone(jank.find_text_toggle("<hierarchy/>"))


class FakeAdb:
    """Answers adb like gartest with GAR as its assistant."""

    def __init__(self):
        self.calls = []
        self.shown = 0
        self.pid = b""  # pidof: not running

    def __call__(self, command, stdout=None, stderr=None):
        self.calls.append(command)
        args = command[3:]
        out = b""
        if command[3:5] == ["logcat", "-d"]:
            out = read("garjank-logcat.txt").encode() if self.shown else b""
        elif args[:1] == ["shell"]:
            args = args[1:]
            if args[:2] == ["am", "start"]:
                out = read("am-start.txt").encode()
            elif args[:1] == ["pidof"]:
                out = self.pid
            elif args[:3] == ["settings", "get", "secure"]:
                out = b"cc.aaro.gar.gar/cc.aaro.gar.assist.GarVoiceInteractionService\n"
            elif args[:3] == ["input", "keyevent", "KEYCODE_ASSIST"]:
                self.shown += 1
        return subprocess.CompletedProcess(command, 0, out, b"")


class MainTest(unittest.TestCase):
    def test_refuses_a_phone(self):
        out = io.StringIO()
        self.assertEqual(jank.main(["startup", "-s", "4B091JEKB"], run=FakeAdb(), out=out,
                                   sleep=lambda s: None), 2)

    def test_startup_report_says_it_is_the_emulator(self):
        out = io.StringIO()
        adb = FakeAdb()
        adb.shown = 1  # logcat has the Displayed lines
        self.assertEqual(jank.main(["startup", "--runs", "2", "--label", "before"], run=adb,
                                   out=out, sleep=lambda s: None), 0)
        text = out.getvalue()
        self.assertIn("jank.py startup [before]: " + jank.NOTE, text)
        self.assertIn("cold TotalTime: p50 6843, p90 6843, max 6843 (2)", text)
        self.assertIn("warm fully_drawn: p50 2005", text)
        self.assertTrue(all(c[:3] == ["adb", "-s", "emulator-5580"] for c in adb.calls))
        self.assertIn(["adb", "-s", "emulator-5580", "shell", "am", "force-stop",
                       "cc.aaro.gar.gar"], adb.calls)

    def test_startup_counts_a_process_back_after_force_stop_apart(self):
        out = io.StringIO()
        adb = FakeAdb()
        adb.shown = 1
        adb.pid = b"4242\n"  # the voice interaction service brought it back
        self.assertEqual(jank.main(["startup", "--runs", "1", "--json"], run=adb, out=out,
                                   sleep=lambda s: None), 0)
        result = json.loads(out.getvalue())["result"]
        self.assertEqual(result["runs"]["cold"], [])
        self.assertEqual(len(result["runs"]["process_warm"]), 1)
        self.assertNotIn("cold", result["summary"])
        stops = [c for c in adb.calls if c[3:6] == ["shell", "am", "force-stop"]]
        self.assertEqual(len(stops), jank.COLD_TRIES)

    def test_sheet_json(self):
        out = io.StringIO()
        adb = FakeAdb()
        self.assertEqual(jank.main(["sheet", "--runs", "1", "--json"], run=adb, out=out,
                                   sleep=lambda s: None), 0)
        result = json.loads(out.getvalue())
        self.assertEqual(result["note"], jank.NOTE)
        self.assertEqual(result["result"]["runs"], [{"ms": 212, "source": "assist",
                                                     "cold": False}])
        self.assertIn(["adb", "-s", "emulator-5580", "shell", "input", "keyevent", "KEYCODE_BACK"],
                      adb.calls)


if __name__ == "__main__":
    unittest.main()
