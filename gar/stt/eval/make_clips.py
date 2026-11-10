#!/usr/bin/env python3
"""GAR speech eval: synthesizes clips.yml's clips with macOS `say` into ~/.cache/gar/stt-eval/clips/.

Each clip is cut the way the app's Endpointer cuts one: the speech (samples above -45 dBFS in 10 ms
windows) plus 300 ms before and 200 ms after, 16 kHz mono PCM16. Clips marked `noise: true` get a
level change (-14 to -3 dB) and white noise 18 to 28 dB below the speech, seeded by the clip id so
every run makes the same file. Clips with a `wav` (the Pixel bench's four, from
gar/stt/src/test/resources/wav) are copied as they are.

Clips whose `wav` is an absolute path (the voice log's, from gar/tools/voice-log.py --pull-wavs) are
recordings already in place: nothing is made for them.

usage: make_clips.py [--force] [--manifest PATH]    (existing files are kept unless --force)
Stdlib only."""
import argparse
import array
import math
import os
import random
import shutil
import subprocess
import sys
import tempfile
import wave

sys.dont_write_bytecode = True  # no __pycache__ next to the scripts in the repo
import manifest  # noqa: E402

RATE = 16000
BEFORE_MS = 300  # Endpointer: the clip starts 300 ms before the speech
AFTER_MS = 200  # and ends 200 ms after its last speech frame
WINDOW = RATE // 100
THRESHOLD = 32768 * 10 ** (-45 / 20)
BENCH_SOURCE = os.path.join(manifest.REPO, "gar", "stt", "src", "test", "resources", "wav")


def read_pcm(path):
    with wave.open(path, "rb") as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2 or w.getframerate() != RATE:
            raise ValueError(f"{path}: not 16 kHz mono PCM16")
        samples = array.array("h")
        samples.frombytes(w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        samples.byteswap()
    return samples


def write_pcm(path, samples):
    out = array.array("h", samples)
    if sys.byteorder == "big":
        out.byteswap()
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(out.tobytes())


def speech_bounds(samples):
    loud = [i for i in range(0, len(samples), WINDOW)
            if max(abs(s) for s in samples[i:i + WINDOW]) >= THRESHOLD]
    if not loud:
        return 0, len(samples)
    return loud[0], min(len(samples), loud[-1] + WINDOW)


def endpointed(samples):
    start, end = speech_bounds(samples)
    speech = samples[start:end]
    return [0] * (RATE * BEFORE_MS // 1000) + list(speech) + [0] * (RATE * AFTER_MS // 1000)


def noisy(samples, seed):
    rng = random.Random(seed)
    gain = 10 ** (rng.uniform(-14, -3) / 20)
    speech = [s * gain for s in samples]
    loud = [s for s in speech if abs(s) >= THRESHOLD * gain]
    rms = math.sqrt(sum(s * s for s in loud) / len(loud)) if loud else 1000.0
    noise_rms = rms * 10 ** (-rng.uniform(18, 28) / 20)
    return [max(-32768, min(32767, round(s + rng.gauss(0, noise_rms)))) for s in speech]


def synthesize(clip, path):
    text = clip.get("say") or clip["text"]
    with tempfile.TemporaryDirectory() as tmp:
        raw = os.path.join(tmp, "say.wav")
        subprocess.run(["say", "-v", clip["voice"], "-o", raw, "--file-format=WAVE",
                        "--data-format=LEI16@16000", text], check=True)
        samples = endpointed(read_pcm(raw))
    if clip["noise"]:
        samples = noisy(samples, clip["id"])
    write_pcm(path, samples)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--force", action="store_true", help="remake existing clips")
    parser.add_argument("--manifest", default=manifest.MANIFEST,
                        help="the clips (default %(default)s)")
    args = parser.parse_args()
    _, clips = manifest.load(os.path.expanduser(args.manifest))
    os.makedirs(manifest.CLIPS_DIR, exist_ok=True)
    made = 0
    for clip in clips:
        path = manifest.clip_wav(clip)
        if clip.get("wav") and os.path.isabs(clip["wav"]):
            continue  # a recording, in place
        if os.path.exists(path) and not args.force:
            continue
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if clip.get("wav"):
            shutil.copyfile(os.path.join(BENCH_SOURCE, os.path.basename(clip["wav"])), path)
        else:
            synthesize(clip, path)
        made += 1
    print(f"{made} clips made, {len(clips)} in {manifest.CLIPS_DIR}")


if __name__ == "__main__":
    main()
