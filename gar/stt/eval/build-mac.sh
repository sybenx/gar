#!/bin/sh
# GAR: builds the speech eval's decoder (gar_whisper_eval) and whisper.cpp's whisper-cli for this Mac
# from the pinned submodule, CPU only, with gar/stt's ggml settings (eval/native/CMakeLists.txt).
#
# usage: gar/stt/eval/build-mac.sh [build dir, default ~/.cache/gar/whisper-mac]
# Needs cmake and ninja on PATH, or the Android SDK's (ANDROID_HOME, or Homebrew's
# android-commandlinetools). Prints the build dir; the binaries are bin/gar_whisper_eval and
# bin/whisper-cli in it.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
build=${1:-$HOME/.cache/gar/whisper-mac}

cmake=$(command -v cmake || true)
for sdk in "${ANDROID_HOME:-}" /opt/homebrew/share/android-commandlinetools; do
    if [ -z "$cmake" ] && [ -n "$sdk" ]; then
        cmake=$(ls -d "$sdk"/cmake/*/bin/cmake 2>/dev/null | tail -n 1)
    fi
done
if [ -z "$cmake" ]; then
    echo "cmake not found, install it or set ANDROID_HOME to an SDK with cmake" >&2
    exit 1
fi
ninja=$(command -v ninja || true)
if [ -z "$ninja" ] && [ -x "$(dirname "$cmake")/ninja" ]; then
    ninja=$(dirname "$cmake")/ninja
fi
if [ -n "$ninja" ]; then
    set -- -G Ninja -DCMAKE_MAKE_PROGRAM="$ninja"
else
    set --
fi

"$cmake" -S "$here/native" -B "$build" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_RUNTIME_OUTPUT_DIRECTORY="$build/bin" "$@" >&2
"$cmake" --build "$build" --target gar_whisper_eval whisper-cli -j "${JOBS:-8}" >&2
echo "$build"
