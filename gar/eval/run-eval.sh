#!/usr/bin/env bash
# Runs the GAR router eval (gar/eval/cases.yml) against a local llama-server. See gar/eval/README.md.
#
# usage: gar/eval/run-eval.sh <model.gguf> <hermes|functiongemma> [port]
#        REPLAY=<report.json> gar/eval/run-eval.sh
#
# Environment:
#   LLAMA_SERVER  path to llama.cpp's llama-server, built from the gar/llm/llama.cpp submodule so
#                 tokenizer, grammar and sampling match the app (default: llama-server on PATH)
#   THREADS       CPU threads for llama-server (default 4)
#   TIMEOUT       seconds one request may take, and llama-server may take to load (default 120;
#                 the first request evaluates the whole prompt, which takes minutes on a busy
#                 machine)
#   GUARDS        the router's call filters: keywords,slots (default) or none
#   CASES         the cases file, relative to the repository root (default gar/eval/cases.yml)
#   REPLAY        an earlier report: score its recorded model outputs again without llama-server
#                 (for filters, keywords, resolvers or labels, not for prompt changes). Model and
#                 format come from the report, CASES from its .meta.json unless set.
#   EVAL_OUT      where reports go (default gar/router/build/eval, which ./gradlew clean deletes)
#   JAVA_HOME     a JDK 21 for Gradle
#
# Output, in EVAL_OUT, under <cases file name>/ for other CASES, replay/ for REPLAY and
# guards-<GUARDS>/ for other GUARDS: report-<model>-<format>.json (the next run with the same
# settings overwrites it), and next to it .log (the runner's output: misses, guard drops, summary),
# .server.log (llama-server's) and .meta.json (commit, file hashes, settings). runs/ keeps a copy
# of each run's files, named after its start time. Each run starts its own llama-server: the server
# reuses the KV cache of the previous request's common prefix, so outputs depend a little on what
# ran before on the same server, and a fresh one keeps runs comparable.
#
# Exit status: 0 for a complete run; 1 when Gradle failed or a case failed in the engine (a
# timeout, a dead server, an output the replayed report doesn't have), which the report scores as
# a failed case; 2 for bad arguments or setup.
set -euo pipefail

fail() {
    echo "$1" >&2
    exit "${2:-2}"
}

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CASES_SET=${CASES:+1}
CASES=${CASES:-gar/eval/cases.yml}
GUARDS=${GUARDS:-keywords,slots}
TIMEOUT=${TIMEOUT:-120}
THREADS=${THREADS:-4}
REPLAY=${REPLAY:-}
SERVER=
SERVER_VERSION=
MODEL=
PORT=

command -v python3 > /dev/null || fail "python3 is needed (for the run's metadata)"
JAVA_VERSION=$("${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 || true)
case "$JAVA_VERSION" in
    *'version "21'*) ;;
    *) fail "Gradle needs a JDK 21: set JAVA_HOME (macOS: export JAVA_HOME=\$(/usr/libexec/java_home -v 21))" ;;
esac

if [ -n "$REPLAY" ]; then
    [ $# -eq 0 ] || fail "usage: REPLAY=<report.json> $0 (model and format come from the report)"
    [ -f "$REPLAY" ] || fail "no report $REPLAY"
    REPLAY="$(cd "$(dirname "$REPLAY")" && pwd)/$(basename "$REPLAY")"
    # the replayed report's model and format, and its cases file if run-eval.sh recorded it
    INFO=$(python3 -c '
import json, os, sys
report = json.load(open(sys.argv[1]))
meta = sys.argv[1][:-len(".json")] + ".meta.json"
cases = json.load(open(meta)).get("cases") or "" if os.path.isfile(meta) else ""
print("\t".join([report["model"], report["format"], cases]))
' "$REPLAY") || fail "cannot read $REPLAY"
    IFS=$'\t' read -r NAME FORMAT REPLAY_CASES <<< "$INFO"
    if [ -z "$CASES_SET" ] && [ -n "$REPLAY_CASES" ]; then
        CASES=$REPLAY_CASES
    fi
else
    [ $# -ge 2 ] || fail "usage: $0 <model.gguf> <hermes|functiongemma> [port]"
    MODEL=$1
    FORMAT=$2
    PORT=${3:-18950}
    NAME=$(basename "$MODEL" .gguf)
    SERVER=${LLAMA_SERVER:-llama-server}
    [ -f "$MODEL" ] || fail "no model file $MODEL"
    # absolute: the metadata step runs from the repository root
    MODEL="$(cd "$(dirname "$MODEL")" && pwd)/$(basename "$MODEL")"
    command -v "$SERVER" > /dev/null || fail "no llama-server at '$SERVER': build it from the
gar/llm/llama.cpp submodule and set LLAMA_SERVER (see gar/eval/README.md)"
    SERVER_VERSION=$("$SERVER" --version 2>&1 | head -n 1 || true)
    # another server on the port (a stale run, another eval) would pass the health check, and
    # the eval would run on its model
    if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/"; then
        fail "port $PORT already answers (another llama-server?): stop it or pass another port"
    fi
fi
case "$FORMAT" in
    hermes | functiongemma) ;;
    *) fail "unknown format '$FORMAT' (hermes|functiongemma)" ;;
esac
case "$CASES" in
    /*) CASES_FILE=$CASES ;;
    *) CASES_FILE="$ROOT/$CASES" ;;
esac
[ -f "$CASES_FILE" ] || fail "no cases file $CASES (relative to the repository root)"

OUT=${EVAL_OUT:-$ROOT/gar/router/build/eval}
if [ "$CASES" != gar/eval/cases.yml ]; then
    OUT="$OUT/$(basename "$CASES" .yml)"
fi
if [ -n "$REPLAY" ]; then
    OUT="$OUT/replay"
fi
if [ "$GUARDS" != keywords,slots ]; then
    OUT="$OUT/guards-${GUARDS//,/-}"
fi
mkdir -p "$OUT/runs"
OUT=$(cd "$OUT" && pwd)
STEM="$OUT/report-$NAME-$FORMAT"
[ "$STEM.json" != "$REPLAY" ] || fail "the replay would overwrite $REPLAY: set EVAL_OUT"
STARTED=$(date +%Y-%m-%dT%H:%M:%S)

SERVER_PID=
stop_server() {
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
        SERVER_PID=
    fi
}
trap stop_server EXIT

if [ -z "$REPLAY" ]; then
    EXTRA=()
    if [ "$FORMAT" = functiongemma ]; then
        # FunctionGemma uses sliding-window attention; keep the full KV cache so prefix reuse is exact
        EXTRA+=(--swa-full)
    fi
    "$SERVER" -m "$MODEL" --host 127.0.0.1 --port "$PORT" -t "$THREADS" -c 4096 -np 1 \
        -cram 0 --no-webui ${EXTRA[@]+"${EXTRA[@]}"} > "$STEM.server.log" 2>&1 &
    SERVER_PID=$!

    for _ in $(seq 1 $((TIMEOUT * 2))); do
        if curl -sf "http://127.0.0.1:$PORT/health" > /dev/null; then
            break
        fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            fail "llama-server exited, see $STEM.server.log" 1
        fi
        sleep 0.5
    done
    if ! curl -sf "http://127.0.0.1:$PORT/health" > /dev/null; then
        fail "llama-server did not become ready in $TIMEOUT s, see $STEM.server.log" 1
    fi
    kill -0 "$SERVER_PID" 2>/dev/null || fail "llama-server exited, see $STEM.server.log" 1
fi

PROPS=(
    -Pgar.eval.format="$FORMAT"
    -Pgar.eval.model="$NAME"
    -Pgar.eval.guards="$GUARDS"
    -Pgar.eval.cases="$CASES"
    -Pgar.eval.out="$OUT"
)
if [ -n "$REPLAY" ]; then
    PROPS+=(-Pgar.eval.replay="$REPLAY")
else
    PROPS+=(-Pgar.eval.server="http://127.0.0.1:$PORT" -Pgar.eval.timeoutSeconds="$TIMEOUT")
fi

# settings.gradle.kts reads the version catalog relative to the working directory
cd "$ROOT"
# --configure-on-demand: only configure :gar:router (works in git worktrees, where :app's JGit
# branch lookup fails, and skips configuring the Android modules)
"$ROOT/gradlew" -p "$ROOT" --configure-on-demand --console=plain -q :gar:router:eval "${PROPS[@]}" \
    2>&1 | tee "$STEM.log"
stop_server

# the run's metadata next to the report, and a non-zero exit when cases failed in the engine
STATUS=0
GAR_ROOT=$ROOT GAR_STEM=$STEM GAR_STARTED=$STARTED GAR_CASES=$CASES GAR_CASES_FILE=$CASES_FILE \
    GAR_GUARDS=$GUARDS GAR_MODEL=$MODEL GAR_SERVER=$SERVER GAR_SERVER_VERSION=$SERVER_VERSION \
    GAR_PORT=$PORT GAR_THREADS=$THREADS GAR_TIMEOUT=$TIMEOUT GAR_REPLAY=$REPLAY python3 - <<'EOF' || STATUS=$?
import hashlib
import json
import os
import subprocess
import sys

env = os.environ
root = env["GAR_ROOT"]


def sha256(path):
    try:
        with open(path, "rb") as f:
            return hashlib.sha256(f.read()).hexdigest()
    except OSError:
        return None


def git(*args):
    try:
        return subprocess.run(["git", "-C", root] + list(args), capture_output=True, text=True,
                              check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


status = git("status", "--porcelain", "--untracked-files=no")
replay = env["GAR_REPLAY"] or None
meta = {
    "report": os.path.basename(env["GAR_STEM"]) + ".json",
    "started": env["GAR_STARTED"],
    "git_commit": git("rev-parse", "HEAD"),
    "git_dirty": None if status is None else bool(status),
    "cases": env["GAR_CASES"],
    "cases_sha256": sha256(env["GAR_CASES_FILE"]),
    "tools_sha256": sha256(os.path.join(root, "app/src/main/gar/tools.yml")),
    "definitions_sha256": sha256(os.path.join(root, "app/src/main/sentences/skill_definitions.yml")),
    "guards": env["GAR_GUARDS"],
    "replay_of": replay,
    "model_file": None if replay else env["GAR_MODEL"],
    "model_bytes": None if replay else os.path.getsize(env["GAR_MODEL"]),
    "llama_server": None if replay else env["GAR_SERVER"],
    "llama_server_version": None if replay else env["GAR_SERVER_VERSION"],
    "port": None if replay else int(env["GAR_PORT"]),
    "threads": None if replay else int(env["GAR_THREADS"]),
    "context": None if replay else 4096,
    "timeout_seconds": None if replay else int(env["GAR_TIMEOUT"]),
}
with open(env["GAR_STEM"] + ".meta.json", "w") as f:
    json.dump(meta, f, indent=2)
    f.write("\n")

# LlmRouter's failures that come from the engine, not from the model's output
cases = json.load(open(env["GAR_STEM"] + ".json"))["cases"]
failed = [c for c in cases if c["got"] == "failed"]
engine = [c for c in failed if c.get("failure", "").startswith(
    ("timed out", "engine error", "engine cancelled", "generation cancelled"))]
if engine:
    print("%d of %d cases failed in the engine (the report scores them as failed), e.g. %s: %s"
          % (len(engine), len(cases), engine[0]["text"], engine[0]["failure"]), file=sys.stderr)
    missing = sum("no recorded output" in c["failure"] for c in engine)
    if missing:
        print("%d have no recorded output in %s: was it recorded with another CASES file, or"
              " did they fail in the engine in that run?" % (missing, replay), file=sys.stderr)
    sys.exit(1)
if cases and len(failed) == len(cases):
    print("every case failed", file=sys.stderr)
    sys.exit(1)
EOF

STAMP=${STARTED//[-:]/}
for suffix in json meta.json log server.log; do
    if [ -f "$STEM.$suffix" ] && { [ "$suffix" != server.log ] || [ -z "$REPLAY" ]; }; then
        cp "$STEM.$suffix" "$OUT/runs/$STAMP-$(basename "$STEM").$suffix"
    fi
done
echo "Log: $STEM.log (copies in $OUT/runs/$STAMP-*)"
exit "$STATUS"
