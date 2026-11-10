# GAR: Mac build of the speech eval's decoder (gar_whisper_eval) and whisper.cpp's whisper-cli, from
# the pinned submodule with the same ggml settings as gar/stt/src/main/cpp/CMakeLists.txt (CPU only,
# same arm64 baseline), so the Mac runs what the phone runs. Built by gar/stt/eval/build-mac.sh.
cmake_minimum_required(VERSION 3.22.1)
project(gar_whisper_eval C CXX)

set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../whisper.cpp)
if (NOT EXISTS ${WHISPER_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp not found in ${WHISPER_CPP_DIR}, run: git submodule update --init --depth 1 gar/stt/whisper.cpp")
endif()

set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(BUILD_SHARED_LIBS OFF)

# whisper-cli for spot checks; the other examples stay unbuilt (EXCLUDE_FROM_ALL below)
set(WHISPER_BUILD_TESTS OFF)
set(WHISPER_BUILD_EXAMPLES ON)
set(WHISPER_BUILD_SERVER OFF)
set(WHISPER_ALL_WARNINGS OFF)
set(WHISPER_CURL OFF)
set(WHISPER_SDL2 OFF)
set(WHISPER_COREML OFF)
set(WHISPER_OPENVINO OFF)

# as gar/stt's CMakeLists.txt
set(GGML_NATIVE OFF)
set(GGML_OPENMP OFF)
set(GGML_METAL OFF)
set(GGML_BLAS OFF)
set(GGML_VULKAN OFF)
set(GGML_BACKEND_DL OFF)
set(GGML_CCACHE OFF)
set(GGML_CPU_KLEIDIAI OFF)
if (CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16")
endif()

add_subdirectory(${WHISPER_CPP_DIR} whisper.cpp EXCLUDE_FROM_ALL)

add_executable(gar_whisper_eval gar_whisper_eval.cpp)
target_compile_features(gar_whisper_eval PRIVATE cxx_std_17)
target_link_libraries(gar_whisper_eval PRIVATE whisper)
