#!/usr/bin/env bash
# Makes Whisper versions of an eval cases file: each case (its `said`, else its `text`) spoken by
# macOS voices, transcribed by whisper-cli the way the app calls Whisper, put through SpokenForm,
# and written as a cases file with only the text changed. Prints the word error rate against the
# cases' text. See gar/eval/README.md, "The Whisper set".
#
# usage: gar/eval/stt/make-whisper-cases.sh <ggml model.bin> [cases file, default gar/eval/cases.yml]
#   e.g. gar/eval/stt/make-whisper-cases.sh ~/.cache/gar/stt/ggml-base.en-acft-q8_0.bin
#        gar/eval/stt/make-whisper-cases.sh ~/.cache/gar/stt/ggml-tiny.en-acft-q8_0.bin gar/eval/holdout.yml
#
# Environment:
#   WHISPER_CLI  whisper.cpp's whisper-cli, built from the gar/stt/whisper.cpp submodule (default
#                ~/.cache/gar/whisper.cpp/bin/whisper-cli; the build command is in the README)
#   VOICES       macOS voices, space separated (default "Samantha Daniel Karen": US, UK and
#                Australian English; `say -v '?'` lists them)
#   THREADS      whisper-cli's CPU threads (default 4)
#   STT_CACHE    clips, transcripts and work files, reused by later runs (default ~/.cache/gar/stt/eval)
#   JAVA_HOME    a JDK 21 for Gradle (SpokenForm runs through :gar:router:spokenForm)
#
# Output in gar/eval/stt/out/ (not in git), for cases file <name>.yml and model ggml-<model>.bin:
# <name>-whisper-<model>-<voice>.yml for each voice, the cases to run the eval on, and
# <name>-whisper-<model>.tsv, every transcript (voice, case, reference, Whisper's text, spoken
# form, word errors). A case Whisper heard nothing for is left out of its file and listed.
#
# Exit status: 0 when the files are written; 1 when a step failed; 2 for bad arguments or setup.
set -euo pipefail

fail() {
    echo "$1" >&2
    exit "${2:-2}"
}

ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
WHISPER_CLI=${WHISPER_CLI:-$HOME/.cache/gar/whisper.cpp/bin/whisper-cli}
VOICES=${VOICES:-Samantha Daniel Karen}
THREADS=${THREADS:-4}
STT_CACHE=${STT_CACHE:-$HOME/.cache/gar/stt/eval}

[ $# -ge 1 ] && [ $# -le 2 ] || fail "usage: $0 <ggml model.bin> [cases file]"
MODEL=$1
CASES=${2:-gar/eval/cases.yml}
[ -f "$MODEL" ] || fail "no model file $MODEL"
MODEL="$(cd "$(dirname "$MODEL")" && pwd)/$(basename "$MODEL")"
case "$CASES" in
    /*) ;;
    *) CASES="$ROOT/$CASES" ;;
esac
[ -f "$CASES" ] || fail "no cases file $CASES"
[ -x "$WHISPER_CLI" ] || fail "no whisper-cli at $WHISPER_CLI: build it (gar/eval/README.md) or set WHISPER_CLI"
command -v say > /dev/null || fail "macOS say is needed for the clips"
command -v python3 > /dev/null || fail "python3 is needed"
VOICE_LIST=$(say -v '?')
for voice in $VOICES; do
    grep -q "^$voice " <<< "$VOICE_LIST" || fail "no voice $voice (say -v '?' lists them)"
done
JAVA_VERSION=$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 || true)
case "$JAVA_VERSION" in
    *'version "21'*) ;;
    *) fail "Gradle needs a JDK 21: set JAVA_HOME (macOS: export JAVA_HOME=\$(/usr/libexec/java_home -v 21))" ;;
esac

NAME=$(basename "$MODEL" .bin)
NAME=${NAME#ggml-}
WORK="$STT_CACHE/work/$(basename "$CASES" .yml)-$NAME"
mkdir -p "$WORK"
cd "$ROOT"

python3 -I gar/eval/stt/whisper_cases.py transcribe --cases "$CASES" --model "$MODEL" \
    --whisper-cli "$WHISPER_CLI" --cache "$STT_CACHE" --work "$WORK" --threads "$THREADS" \
    --voices "$VOICES" || fail "transcription failed" 1

rm -f "$WORK/spoken.txt"
"$ROOT/gradlew" -p "$ROOT" --configure-on-demand --console=plain -q :gar:router:spokenForm \
    "-Pgar.spokenForm.in=$WORK/raw.txt" "-Pgar.spokenForm.out=$WORK/spoken.txt" \
    || fail "Gradle failed" 1

python3 -I gar/eval/stt/whisper_cases.py finish --cases "$CASES" --cases-label "${CASES#"$ROOT"/}" \
    --work "$WORK" --out gar/eval/stt/out --name "$NAME" || fail "writing the cases failed" 1
