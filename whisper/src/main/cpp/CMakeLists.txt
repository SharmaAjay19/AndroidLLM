cmake_minimum_required(VERSION 3.22.1)

project("whisper-android")

# Pull whisper.cpp and build it (provides the "whisper" target, which links its own ggml).
# Kept in this isolated module so its bundled ggml does not collide with llama.cpp's ggml
# target in the :app module.
include(FetchContent)

set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        whisper
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp
        GIT_TAG        v1.7.4
)
FetchContent_MakeAvailable(whisper)

add_library(${CMAKE_PROJECT_NAME} SHARED whisper-jni.c)

# whisper links its own static ggml; llama.cpp ships libggml*.so. Hide all non-JNI
# symbols so this .so doesn't re-export ggml_* and clash with llama's ggml at runtime.
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -fvisibility=hidden)
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE -Wl,--exclude-libs,ALL)

target_link_libraries(${CMAKE_PROJECT_NAME}
        whisper
        android
        log)
