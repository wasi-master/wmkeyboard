# libwmwhispercpp: whispercpp_jni.cpp over a static whisper.cpp + ggml (CPU
# backend), libwmwhispercpp_opencl: the same with ggml's OpenCL backend for
# Adreno GPUs beside it (WM_GPU=opencl), and libwmwhispercpp_vulkan: with
# ggml's Vulkan backend for the other phone GPUs (WM_GPU=vulkan). Driven by build.sh, which passes
# the whisper.cpp tree in WHISPER_SRC, the instruction set per variant and the
# OpenCL headers and link library; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmwhispercpp C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_SRC "" CACHE PATH "whisper.cpp source tree")
if (NOT EXISTS "${WHISPER_SRC}/include/whisper.h")
    message(FATAL_ERROR "WHISPER_SRC must point at a whisper.cpp checkout")
endif()

# The GPU backend built in beside the CPU one: empty for none, "opencl" or
# "vulkan".
set(WM_GPU "" CACHE STRING "GPU backend: empty, opencl or vulkan")

# Arm's KleidiAI kernels (Q8_0 and F32 matrix products, picked at run time
# from the CPU's hwcaps), arm64 only. build.sh fetches and checks the archive
# and passes it as FETCHCONTENT_SOURCE_DIR_KLEIDIAI, so configuring needs no
# network.
set(WM_KLEIDIAI OFF CACHE BOOL "build in KleidiAI (arm64 only)")
set(GGML_CPU_KLEIDIAI        ${WM_KLEIDIAI} CACHE BOOL "" FORCE)
set(FETCHCONTENT_FULLY_DISCONNECTED ON CACHE BOOL "" FORCE)

# Static, nothing but the library.
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2             OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL             OFF CACHE BOOL "" FORCE)
# ggml's own thread pool rather than OpenMP, which would mean shipping libomp.
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
# Cross-compiling: the instruction set comes from build.sh, not this Mac.
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "" FORCE)
# One GPU backend at most, and only the one asked for: Metal would come along
# on a Mac host build otherwise.
set(GGML_METAL               OFF CACHE BOOL "" FORCE)
set(GGML_BLAS                OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN              OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL              OFF CACHE BOOL "" FORCE)
if (WM_GPU STREQUAL "opencl")
    # Qualcomm's backend: Adreno's own matrix and attention kernels, compiled
    # by the phone's driver from the source embedded in the library. It
    # refuses every GPU but Adreno and Intel, so WhisperCppGpu loads it on
    # Adreno only.
    set(GGML_OPENCL                      ON  CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS        ON  CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS   ON  CACHE BOOL "" FORCE)
    set(GGML_OPENCL_PROFILING            OFF CACHE BOOL "" FORCE)
elseif (WM_GPU STREQUAL "vulkan")
    # Every Vulkan 1.2 GPU, Mali, Xclipse and PowerVR among them; WhisperCppGpu
    # loads it on phones that are not Adreno.
    set(GGML_VULKAN                      ON  CACHE BOOL "" FORCE)
elseif (NOT WM_GPU STREQUAL "")
    message(FATAL_ERROR "WM_GPU must be empty, opencl or vulkan, not '${WM_GPU}'")
endif()

add_subdirectory("${WHISPER_SRC}" whisper EXCLUDE_FROM_ALL)

add_library(wmwhispercpp SHARED whispercpp_jni.cpp)
set_target_properties(wmwhispercpp PROPERTIES OUTPUT_NAME "${WM_LIB_NAME}")
target_link_libraries(wmwhispercpp PRIVATE whisper log)
target_compile_options(wmwhispercpp PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
if (NOT WM_GPU STREQUAL "")
    # Its own JNI class, so it can be loaded beside a CPU build. Both GPU
    # builds bind the same one: a phone loads one or the other, never both.
    target_compile_definitions(wmwhispercpp PRIVATE WM_GPU WM_JNI_CLASS=WhisperCppGpu)
endif()
target_link_options(wmwhispercpp PRIVATE
    -Wl,--no-undefined
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
