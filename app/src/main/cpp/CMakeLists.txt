cmake_minimum_required(VERSION 3.22.1)

project("llama-android")

# --- Pull llama.cpp on demand and build it as part of this app -----------------
# Pinned to tag b5600, which supports the Qwen3 architecture used by the
# recommended Qwen3-4B Q4_K_M GGUF. Bump GIT_TAG to update the engine, but if you
# do, re-check that llama-android.cpp still matches the llama.cpp C API.
include(FetchContent)
FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
        GIT_TAG        b5600
)

# Also provides the "common" helper target used by llama-android.cpp.
FetchContent_MakeAvailable(llama)

# --- Our JNI bridge ------------------------------------------------------------
add_library(${CMAKE_PROJECT_NAME} SHARED
        llama-android.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        common
        android
        log)
