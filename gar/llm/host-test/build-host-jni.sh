#!/bin/sh
# GAR: builds libgar_llama for this machine (macOS arm64; Linux should work too) as a JNI library
# for the JVM test in gar/llm/src/test. Same CMakeLists.txt, gar_llama.cpp, llama.cpp submodule and
# CPU baseline as the Android build.
#
# usage: gar/llm/host-test/build-host-jni.sh [build dir]
# Needs JAVA_HOME (JNI headers) and cmake on PATH or in $ANDROID_HOME/cmake. Prints the library
# path; run the test with it:
#   GAR_HOST_JNI_LIB=<printed path> GAR_TEST_MODEL=/path/to/Qwen3-0.6B-Q4_K_M.gguf \
#       ./gradlew :gar:llm:testDebugUnitTest
# GAR_TEST_MODEL_FG=/path/to/functiongemma-270m-it-q8_0.gguf adds the FunctionGemma checks.
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
"$cmake" --build "$build" --target gar_llama -j "${JOBS:-4}" >&2

for lib in "$build/libgar_llama.dylib" "$build/libgar_llama.so"; do
    if [ -f "$lib" ]; then
        echo "$lib"
        exit 0
    fi
done
echo "libgar_llama not found in $build" >&2
exit 1
