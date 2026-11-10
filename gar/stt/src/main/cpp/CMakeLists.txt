# GAR: libgar_whisper, the JNI layer of cc.aaro.gar.stt.WhisperEngine and SileroVad, with
# whisper.cpp and its own copy of ggml linked in statically. Used by the Android build
# (gar/stt/build.gradle.kts) and by the macOS host build for JVM tests
# (gar/stt/host-test/build-host-jni.sh). Modelled on gar/llm's. See gar/DESIGN.md.
cmake_minimum_required(VERSION 3.22.1)
project(gar_whisper C CXX)

set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../whisper.cpp)
if (NOT EXISTS ${WHISPER_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp not found in ${WHISPER_CPP_DIR}, run: git submodule update --init --depth 1 gar/stt/whisper.cpp")
endif()

# Debug builds of whisper.cpp are unusably slow, so the Gradle build passes CMAKE_BUILD_TYPE=Release
# for every variant. Catch builds that don't.
if (NOT CMAKE_BUILD_TYPE STREQUAL "Release")
    message(WARNING "gar_whisper: CMAKE_BUILD_TYPE is '${CMAKE_BUILD_TYPE}', whisper.cpp will be slow")
endif()

# let the option() calls in whisper.cpp/ggml pick up the plain variables set below (whisper.cpp
# declares an old cmake_minimum_required, which would otherwise make option() win)
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# One shared library with whisper.cpp and ggml linked in statically, as libgar_llama does. Its ggml
# is a separate copy from libgar_llama's: hidden visibility and --exclude-libs keep the two from
# binding to each other's symbols. whisper.cpp defaults BUILD_SHARED_LIBS to ON.
set(BUILD_SHARED_LIBS OFF)

# No tests, examples, server or downloads: the APK gets no networking code from whisper.cpp.
set(WHISPER_BUILD_TESTS OFF)
set(WHISPER_BUILD_EXAMPLES OFF)
set(WHISPER_BUILD_SERVER OFF)
set(WHISPER_ALL_WARNINGS OFF)
set(WHISPER_CURL OFF)
set(WHISPER_SDL2 OFF)
set(WHISPER_COREML OFF)
set(WHISPER_OPENVINO OFF)

# CPU only, same ggml settings as gar/llm. OpenMP off: ggml's own thread pool is used and no libomp
# has to be shipped.
set(GGML_NATIVE OFF)
set(GGML_OPENMP OFF)
set(GGML_METAL OFF)
set(GGML_BLAS OFF)
set(GGML_VULKAN OFF)
set(GGML_BACKEND_DL OFF)
set(GGML_CCACHE OFF)
# KleidiAI is downloaded at configure time, which breaks offline builds; measure before enabling
set(GGML_CPU_KLEIDIAI OFF)

# CPU baseline for arm64, as in gar/llm: ARMv8.2-A with dot product and fp16 arithmetic (Tensor G4,
# Apple M1). i8mm stays off until measured (whisper-design.md section 14).
if (CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$" OR ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16")
endif()

# No ggml target exists here, so whisper.cpp adds its own ggml/. EXCLUDE_FROM_ALL plus Gradle's
# targets += "gar_whisper" keep the parakeet library and the rest unbuilt.
add_subdirectory(${WHISPER_CPP_DIR} whisper.cpp EXCLUDE_FROM_ALL)

# whisper.cpp commit, for the load log
execute_process(
    COMMAND git rev-parse --short=12 HEAD
    WORKING_DIRECTORY ${WHISPER_CPP_DIR}
    OUTPUT_VARIABLE GAR_WHISPER_COMMIT
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)
if (NOT GAR_WHISPER_COMMIT)
    set(GAR_WHISPER_COMMIT "unknown")
endif()
# Re-run configure when the submodule moves, so the load log never reports an old pin
execute_process(
    COMMAND git rev-parse --absolute-git-dir
    WORKING_DIRECTORY ${WHISPER_CPP_DIR}
    OUTPUT_VARIABLE GAR_WHISPER_GIT_DIR
    OUTPUT_STRIP_TRAILING_WHITESPACE
    ERROR_QUIET
)
if (GAR_WHISPER_GIT_DIR AND EXISTS "${GAR_WHISPER_GIT_DIR}/HEAD")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${GAR_WHISPER_GIT_DIR}/HEAD")
endif()

add_library(gar_whisper SHARED gar_whisper.cpp)
target_compile_features(gar_whisper PRIVATE cxx_std_17)
target_compile_definitions(gar_whisper PRIVATE GAR_WHISPER_COMMIT="${GAR_WHISPER_COMMIT}")
set_target_properties(gar_whisper PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_link_libraries(gar_whisper PRIVATE whisper)

if (ANDROID)
    target_link_libraries(gar_whisper PRIVATE log)
    target_link_options(gar_whisper PRIVATE
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
    target_include_directories(gar_whisper PRIVATE ${GAR_JNI_INCLUDE_DIRS})
    if (APPLE)
        target_link_options(gar_whisper PRIVATE -Wl,-dead_strip)
    endif()
endif()
