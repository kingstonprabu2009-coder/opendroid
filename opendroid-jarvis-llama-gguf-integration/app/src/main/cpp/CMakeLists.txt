cmake_minimum_required(VERSION 3.22.1)
project(opendroid_llama_jni LANGUAGES C CXX)
include(FetchContent)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(ANDROID_STL c++_shared)

# Portable Android arm64 build. llama.cpp is fetched at a pinned release tag
# during the first native build; no native binary is checked into the APK source.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
FetchContent_Declare(llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b7600
    GIT_SHALLOW TRUE
    GIT_SUBMODULES ggml
    GIT_SUBMODULES_RECURSE TRUE)
FetchContent_MakeAvailable(llama_cpp)

add_library(opendroid_llama SHARED llama_jni.cpp)
target_include_directories(opendroid_llama PRIVATE ${llama_cpp_SOURCE_DIR}/include)
target_link_libraries(opendroid_llama PRIVATE llama android log)
