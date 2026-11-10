#!/bin/sh
# GAR: builds libgar_whisper for this machine (macOS arm64; Linux should work too) as a JNI library
# for the JVM test in gar/stt/src/test. Same CMakeLists.txt, gar_whisper.cpp, whisper.cpp
# submodule and CPU baseline as the Android build.
#
# usage: gar/stt/host-test/build-host-jni.sh [build dir]
# Needs JAVA_HOME (JNI headers) and cmake on PATH or in $ANDROID_HOME/cmake. Prints the library
# path; run the test with it (models in ~/.cache/gar/stt/, see gar/BUILD.md):
#   GAR_STT_HOST_JNI_LIB=<printed path> \
#   GAR_STT_TEST_MODEL=$HOME/.cache/gar/stt/ggml-base.en-acft-q8_0.bin \
#   GAR_STT_TEST_VAD=$HOME/.cache/gar/stt/ggml-silero-v6.2.0.bin \
#       ./gradlew :gar:stt:testDebugUnitTest
set -eu

here=$(cd "$(dirname "$0")" && pwd)
build=${1:-$here/build}
: "${JAVA_HOME:?set JAVA_HOME to a JDK, its JNI headers are needed}"

cmake=$(command -v cmake || true)
if [ -z "$cmake" ] && [ -n "${ANDROID_HOME:-}" ]; then
    cmake=$(ls -d "$ANDROID_HOME"/cmake/*/bin/cmake 2>/dev/null | tail -n 1)
fi
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
"$cmake" -S "$here/../src/main/cpp" -B "$build" -DCMAKE_BUILD_TYPE=Release "$@" >&2
"$cmake" --build "$build" --target gar_whisper -j "${JOBS:-4}" >&2

# whisper.cpp's JFK sample as 16 kHz mono WAV next to the library, for the host test (macOS only;
# the test skips it if missing)
if command -v afconvert >/dev/null 2>&1 && [ ! -f "$build/jfk.wav" ]; then
    afconvert -f WAVE -d LEI16@16000 -c 1 "$here/../whisper.cpp/samples/jfk.mp3" "$build/jfk.wav" >&2 || true
fi

for lib in "$build/libgar_whisper.dylib" "$build/libgar_whisper.so"; do
    if [ -f "$lib" ]; then
        echo "$lib"
        exit 0
    fi
done
echo "libgar_whisper not found in $build" >&2
exit 1
