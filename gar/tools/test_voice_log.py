"""Tests for voice-log.py on testdata/voice-log-sample.jsonl.

    python3 -m unittest discover -s gar/tools -p 'test_*.py'
"""

import datetime as dt
import importlib.util
import io
import os
import subprocess
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLE = os.path.join(HERE, "testdata", "voice-log-sample.jsonl")

_spec = importlib.util.spec_from_file_location("voice_log", os.path.join(HERE, "voice-log.py"))
vl = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vl)

# gar/stt/eval's reader, to check that its clips.yml can be replayed
_manifest_spec = importlib.util.spec_from_file_location(
    "manifest", os.path.join(HERE, "..", "stt", "eval", "manifest.py"))
manifest = importlib.util.module_from_spec(_manifest_spec)
_manifest_spec.loader.exec_module(manifest)


def sample_turns():
    with open(SAMPLE, encoding="utf-8") as f:
        return vl.parse(f.read(), warn=lambda message: None)


class ParseTest(unittest.TestCase):
    def test_skips_lines_that_are_not_json(self):
        warnings = []
        with open(SAMPLE, encoding="utf-8") as f:
            turns = vl.parse(f.read(), warn=warnings.append)
        self.assertEqual(len(turns), 7)
        self.assertEqual(warnings, ["skipping line 5: not JSON"])

    def test_orders_rotated_files_oldest_first(self):
        names = ["voice-log.jsonl", "wav", "voice-log.1.jsonl", "voice-log.3.jsonl", "x.txt"]
        self.assertEqual(vl.order_logs(names),
                         ["voice-log.3.jsonl", "voice-log.1.jsonl", "voice-log.jsonl"])


class TableTest(unittest.TestCase):
    def test_rows(self):
        text = vl.table(sample_turns())
        lines = text.splitlines()
        self.assertEqual(lines[0].split(), ["time", "start", "heard", "route", "answer",
                                            "latency", "jank", "suspect"])
        self.assertEqual(len(lines), 2 + 7)
        first = lines[2]
        self.assertIn("11-02 08:15:00", first)
        self.assertIn("Call Erin. -> call erin", first)
        self.assertIn("telephone", first)
        self.assertIn("700 ms", first)
        self.assertTrue(first.rstrip().endswith("repeat"))
        self.assertIn("what time is it", lines[4])
        self.assertIn("no_input(no_speech)", lines[5])
        self.assertIn("model:media_control", lines[6])
        self.assertIn("no_input(prompt_names)", lines[7])
        self.assertIn("fallback (model dropped)", lines[8])

    def test_heard_route_answer(self):
        turns = {t["seq"]: t for t in sample_turns()}
        self.assertEqual(vl.heard(turns[3]), "what time is it")
        self.assertEqual(vl.route(turns[5]), "model:media_control")
        self.assertEqual(vl.answer(turns[7]), "text")
        self.assertEqual(vl.latency(turns[5]), "700 ms")


class FilterTest(unittest.TestCase):
    def test_suspect(self):
        self.assertEqual([t["seq"] for t in vl.select(sample_turns(), only_suspect=True)], [1, 7])

    def test_skill(self):
        self.assertEqual([t["seq"] for t in vl.select(sample_turns(), skill="telephone")], [1, 2])
        self.assertEqual([t["seq"] for t in vl.select(sample_turns(), skill="song_identify")], [7])

    def test_since(self):
        tz = dt.timezone(dt.timedelta(hours=-6))
        now = dt.datetime(2026, 11, 2, 17, 30, tzinfo=tz)
        self.assertEqual([t["seq"] for t in vl.select(sample_turns(),
                                                       since=vl.parse_since("1h", now))],
                         [5, 6, 7])
        since = vl.parse_since("2026-11-02T12:00:00-06:00")
        self.assertEqual([t["seq"] for t in vl.select(sample_turns(), since=since)], [4, 5, 6, 7])


class SummaryTest(unittest.TestCase):
    def test_counts_per_stage(self):
        text = vl.summary(sample_turns())
        self.assertIn("7 turns, 2 suspect", text)
        self.assertIn("capture end: vad_silence 4, no_speech 1, user_tap 1", text)
        self.assertIn("transcript filter: accept 4, prompt_names 1", text)
        self.assertIn("model: call 1, dropped 1", text)
        self.assertIn("model drops: 'track my package' doesn't ask about what plays now 1", text)
        self.assertIn("answer: answered 5, no_input 2", text)
        self.assertIn("suspect: repeat 1, asked_to_repeat 1", text)
        self.assertIn("to_first_word_ms: median 695, p90 1000 (4 turns)", text)

    def test_empty(self):
        self.assertEqual(vl.summary([]), "0 turns, 0 suspect")


class FollowUpTest(unittest.TestCase):
    """The batch 7 fields: follow_up, start continued, answer.spoken, silent windows."""

    TURNS = [
        {"seq": 1, "start": "mic", "input": "turn it back off",
         "match": {"path": "sentence"},
         "follow_up": {"form": "switch_off_back", "turn_skill": "flashlight", "turn_age_ms": 42000,
                       "rewrite": "turn off the flashlight", "outcome": "rewritten"},
         "answer": {"outcome": "answered", "skill": "flashlight", "spoken": True}},
        {"seq": 2, "start": "continued", "input": "play it again", "continued_silent": 2,
         "continued_hallucination": 1, "match": {"path": "sentence"},
         "follow_up": {"form": "about_song_play_again", "outcome": "deferred",
                       "deferred_to": "media"},
         "answer": {"outcome": "answered", "skill": "media_controls"}},
        {"seq": 3, "start": "typed", "input": "what time is it", "match": {"path": "sentence"},
         "answer": {"outcome": "answered", "skill": "current_time", "spoken": False}},
    ]

    def test_heard_and_route(self):
        first, second, third = self.TURNS
        self.assertEqual(vl.heard(first), "turn it back off => turn off the flashlight")
        self.assertEqual(vl.route(first), "follow_up:rewritten sentence")
        self.assertEqual(vl.route(second), "follow_up:deferred:media sentence")
        self.assertEqual(vl.route(third), "sentence")
        self.assertEqual(vl.answer(third), "current_time (shown)")
        self.assertEqual(vl.answer(first), "flashlight")

    def test_summary(self):
        text = vl.summary(self.TURNS)
        self.assertIn("start: mic 1, continued 1, typed 1", text)
        self.assertIn("follow_up: rewritten 1, deferred:media 1", text)
        self.assertIn("answers shown only: 1", text)
        self.assertIn("continued windows without a turn: silent 2, hallucination 1", text)


class FramesTest(unittest.TestCase):
    """The frames GarJank counted during each turn."""

    TURNS = [
        {"seq": 1, "start": "mic", "input": "what time is it",
         "frames": {"surface": "sheet", "n": 40, "janky": 2, "p50_ms": 8.1, "p90_ms": 9.0,
                    "p99_ms": 31.0, "max_ms": 44.6, "hz": 120}},
        {"seq": 2, "start": "typed", "input": "battery",
         "frames": {"surface": "home", "n": 60, "janky": 0, "max_ms": 12.0, "hz": 60}},
        {"seq": 3, "start": "hotword", "input": "stop"},
    ]

    def test_jank_column(self):
        self.assertEqual([vl.jank(t) for t in self.TURNS], ["5%", "0%", "-"])
        lines = vl.table(self.TURNS).splitlines()
        self.assertIn("5%", lines[2])

    def test_summary(self):
        text = vl.summary(self.TURNS)
        self.assertIn("frames: 100 in 2 turns, janky 2 (2.0%), slowest 45 ms, at 60/120 Hz", text)
        self.assertNotIn("frames:", vl.summary(self.TURNS[2:]))


class FakeRun:
    """Answers adb like a phone with the sample as voice-log.1.jsonl and one WAV."""

    def __init__(self, sample):
        self.sample = sample
        self.calls = []

    def __call__(self, command, stdout=None, stderr=None):
        self.calls.append(command)
        args = command[command.index("run-as") + 2:]
        if args[:1] == ["ls"]:
            out = b"voice-log.1.jsonl\nwav\n"
        elif args == ["cat", "files/gar-voice-log/voice-log.1.jsonl"]:
            out = self.sample
        elif args[0] == "cat" and args[1].startswith("files/gar-voice-log/wav/"):
            if not args[1].endswith("-1.suspect.wav"):
                return subprocess.CompletedProcess(command, 1, b"", b"No such file")
            out = b"RIFF-wav"
        else:
            return subprocess.CompletedProcess(command, 1, b"", b"unexpected")
        return subprocess.CompletedProcess(command, 0, out, b"")


class AdbTest(unittest.TestCase):
    def test_reads_over_run_as_and_pulls_suspect_wavs(self):
        with open(SAMPLE, "rb") as f:
            run = FakeRun(f.read())
        out = io.StringIO()
        with tempfile.TemporaryDirectory() as target:
            code = vl.main(["-s", "PIXEL9A", "--suspect", "--pull-wavs", target],
                           run=run, out=out)
            self.assertEqual(code, 0)
            self.assertEqual(run.calls[0], ["adb", "-s", "PIXEL9A", "exec-out", "run-as",
                                            "cc.aaro.gar.gar", "ls", "files/gar-voice-log"])
            wav = os.path.join(target, "20261102-081500-120-1.suspect.wav")
            with open(wav, "rb") as f:
                self.assertEqual(f.read(), b"RIFF-wav")
            with open(os.path.join(target, "clips.yml"), encoding="utf-8") as f:
                clips = f.read()
            self.assertIn("id: vl-20261102-081500-120-1", clips)
            # heard "call erin", the retry (not suspect itself) said "call aaron"
            self.assertIn('heard "call erin"', clips)
            self.assertIn('text: "call aaron"', clips)
            self.assertIn('prompt: ["Aaron", "Mom", "Erin"]', clips)
            self.assertIn("wav: \"%s\"" % wav, clips)
            self.assertNotIn("vl-20261102-171000-000-7", clips)  # its WAV wasn't there
            # gar/stt/eval reads it as run.py's modes need it
            top, read = manifest.load(os.path.join(target, "clips.yml"))
            self.assertEqual(top["owner"], "Aaron")
            self.assertEqual(top["contacts"], [])
            self.assertEqual(read[0]["prompt"], ["Aaron", "Mom", "Erin"])
            self.assertEqual(read[0]["text"], "call aaron")
            self.assertEqual(read[0]["wav"], wav)
        self.assertIn("1 recordings in", out.getvalue())

    def test_serial_from_environment(self):
        os.environ["ANDROID_SERIAL"] = "ENVSERIAL"
        try:
            run = FakeRun(b"")
            vl.main(["--summary-only"], run=run, out=io.StringIO())
            self.assertEqual(run.calls[0][:3], ["adb", "-s", "ENVSERIAL"])
        finally:
            del os.environ["ANDROID_SERIAL"]


class SaidTextTest(unittest.TestCase):
    def test_from_the_retry(self):
        retry = {"id": "b", "input": "no I said call aaron"}
        by_id = {"b": retry}
        turn = {"suspect": ["i_said"], "suspect_by": "b"}
        self.assertEqual(vl.said_text(turn, by_id), "call aaron")
        retry["input"] = "call aaron"
        self.assertEqual(vl.said_text({"suspect": ["repeat"], "suspect_by": "b"}, by_id),
                         "call aaron")
        # a rephrase is other words; said_no says nothing of what was said
        self.assertIsNone(vl.said_text({"suspect": ["rephrase"], "suspect_by": "b"}, by_id))
        self.assertIsNone(vl.said_text({"suspect": ["said_no"], "suspect_by": "b"}, by_id))
        self.assertIsNone(vl.said_text({"suspect": ["repeat"], "suspect_by": "gone"}, by_id))


class ManifestTest(unittest.TestCase):
    def test_quoted_flow_items(self):
        self.assertEqual(manifest._scalar('["Aaron", "Smith, Jo", Mom]'),
                         ["Aaron", "Smith, Jo", "Mom"])
        self.assertEqual(manifest._scalar("[Aaron, Dr. Patel]"), ["Aaron", "Dr. Patel"])
        self.assertEqual(manifest._scalar("[]"), [])


class LocalTest(unittest.TestCase):
    def test_shared_zip(self):
        with tempfile.TemporaryDirectory() as tmp:
            zipped = os.path.join(tmp, "gar-voice-log-20261102.zip")
            with open(SAMPLE, "rb") as f:
                sample = f.read()
            with zipfile.ZipFile(zipped, "w") as z:
                z.writestr("gar-voice-log/voice-log.1.jsonl", sample)
                z.writestr("gar-voice-log/voice-log.jsonl", b"")
                z.writestr("gar-voice-log/wav/20261102-171000-000-7.suspect.wav", b"RIFF7")
            out = io.StringIO()
            target = os.path.join(tmp, "clips")
            vl.main(["--file", zipped, "--skill", "text", "--pull-wavs", target], out=out)
            self.assertIn("Track my package. -> track my package", out.getvalue())
            with open(os.path.join(target, "20261102-171000-000-7.suspect.wav"), "rb") as f:
                self.assertEqual(f.read(), b"RIFF7")
            # no retry to take the words from: the eval refuses it until someone fills them in
            with open(os.path.join(target, "clips.yml"), encoding="utf-8") as f:
                self.assertIn("# text: TODO", f.read())
            with self.assertRaises(ValueError):
                manifest.load(os.path.join(target, "clips.yml"))

    def test_json_output(self):
        out = io.StringIO()
        vl.main(["--file", SAMPLE, "--suspect", "--json"], out=out)
        self.assertEqual(len(out.getvalue().splitlines()), 2)


if __name__ == "__main__":
    unittest.main()
