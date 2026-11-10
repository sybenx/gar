# GAR: libgar_llama, the JNI layer of cc.aaro.gar.llm.LlamaEngine, with llama.cpp linked in
# statically. Used by the Android build (gar/llm/build.gradle.kts) and by the macOS host build for
# JVM tests (gar/llm/host-test/build-host-jni.sh). See gar/DESIGN.md.
cmake_minimum_required(VERSION 3.22.1)
project(gar_llama C CXX)

set(LLAMA_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../llama.cpp)
if (NOT EXISTS ${LLAMA_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp not found in ${LLAMA_CPP_DIR}, run: git submodule update --init gar/llm/llama.cpp")
endif()

# Debug builds of llama.cpp are unusably slow, so the Gradle build passes CMAKE_BUILD_TYPE=Release
# for every variant. Catch builds that don't.
if (NOT CMAKE_BUILD_TYPE STREQUAL "Release")
    message(WARNING "gar_llama: CMAKE_BUILD_TYPE is '${CMAKE_BUILD_TYPE}', llama.cpp will be slow")
endif()

# let the option() calls in llama.cpp/ggml pick up the plain variables set below
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# One shared library with llama.cpp and ggml linked in statically: no extra .so files to load in
# order, libc++ linked statically once (ANDROID_STL defaults to c++_static), unused code dropped by
# --gc-sections, and only the JNI functions exported. The price is a single CPU baseline instead
# of GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS, see GGML_CPU_ARM_ARCH below.
set(BUILD_SHARED_LIBS OFF)

# No llama-common (it bundles cpp-httplib and BoringSSL), no tools, tests, examples or server:
# the APK gets no networking code from llama.cpp. LLAMA_CURL no longer exists at this version.
set(LLAMA_BUILD_COMMON OFF)
set(LLAMA_BUILD_TESTS OFF)
set(LLAMA_BUILD_TOOLS OFF)
set(LLAMA_BUILD_EXAMPLES OFF)
set(LLAMA_BUILD_SERVER OFF)
set(LLAMA_BUILD_APP OFF)
set(LLAMA_OPENSSL OFF)
set(LLAMA_ALL_WARNINGS OFF)

# CPU only. OpenMP off: ggml's own thread pool is used and no libomp has to be shipped.
set(GGML_NATIVE OFF)
set(GGML_OPENMP OFF)
set(GGML_METAL OFF)
set(GGML_BLAS OFF)
set(GGML_VULKAN OFF)
set(GGML_BACKEND_DL OFF)
set(GGML_CCACHE OFF)
# KleidiAI is downloaded at configure time, which breaks offline builds; measure before enabling
set(GGML_CPU_KLEIDIAI OFF)

# CPU baseline for arm64: ARMv8.2-A with dot product and fp16 arithmetic. The Pixel 9a's Tensor G4
# (Cortex-X4/A720/A520) and the Apple M1 (host builds, arm64 emulator) both have these; i8mm and
# SVE are not required because nothing dispatches them at runtime in this build.
if (CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$" OR ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16")
endif()

add_subdirectory(${LLAMA_CPP_DIR} llama.cpp EXCLUDE_FROM_ALL)

# llama.cpp commit, part of the prefix state cache key
execute_process(
    COMMAND git rev-parse --short=12 HEAD
    WORKING_DIRECTORY ${LLAMA_CPP_DIR}
    OUTPUT_VARIABLE GAR_LLAMA_COMMIT
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)
if (NOT GAR_LLAMA_COMMIT)
    set(GAR_LLAMA_COMMIT "unknown")
endif()

add_library(gar_llama SHARED gar_llama.cpp)
target_compile_features(gar_llama PRIVATE cxx_std_17)
target_compile_definitions(gar_llama PRIVATE GAR_LLAMA_COMMIT="${GAR_LLAMA_COMMIT}")
set_target_properties(gar_llama PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_link_libraries(gar_llama PRIVATE llama)

if (ANDROID)
    target_link_libraries(gar_llama PRIVATE log)
    target_link_options(gar_llama PRIVATE
        -Wl,--gc-sections
        # keep the symbols of the static libraries out of the dynamic symbol table
        -Wl,--exclude-libs,ALL
        # 16 KB pages (already the default with NDK r28+)
        -Wl,-z,max-page-size=16384)
else()
    # host build for JVM tests: JNI headers of the JDK running Gradle
    if (NOT GAR_JNI_INCLUDE_DIRS)
        if (NOT DEFINED ENV{JAVA_HOME})
            message(FATAL_ERROR "Set JAVA_HOME or GAR_JNI_INCLUDE_DIRS for the host build")
        endif()
        file(GLOB GAR_JNI_MD_DIR LIST_DIRECTORIES true "$ENV{JAVA_HOME}/include/*")
        set(GAR_JNI_INCLUDE_DIRS "$ENV{JAVA_HOME}/include")
        foreach (dir ${GAR_JNI_MD_DIR})
            if (IS_DIRECTORY ${dir})
                list(APPEND GAR_JNI_INCLUDE_DIRS ${dir})
            endif()
        endforeach()
    endif()
    target_include_directories(gar_llama PRIVATE ${GAR_JNI_INCLUDE_DIRS})
    if (APPLE)
        target_link_options(gar_llama PRIVATE -Wl,-dead_strip)
    endif()
endif()
