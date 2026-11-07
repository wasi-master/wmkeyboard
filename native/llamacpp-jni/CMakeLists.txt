# libwmllama: llamacpp_jni.cpp over a static llama.cpp + ggml, llama.cpp's
# own chat-template code (common/chat.cpp and its Jinja interpreter), so a
# GGUF's template renders the way its authors wrote it, and libmtmd, which
# reads a picture through a vision model's projector (its mmproj file).
# Driven by build.sh, which passes the llama.cpp tree in LLAMA_SRC, the
# instruction set per variant and, for the GPU variants, WM_GPU; see
# README.md. The same file builds the host library host-test.sh loads into a
# desktop JVM.
cmake_minimum_required(VERSION 3.18)
project(wmllama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(LLAMA_SRC "" CACHE PATH "llama.cpp source tree")
if (NOT EXISTS "${LLAMA_SRC}/include/llama.h")
    message(FATAL_ERROR "LLAMA_SRC must point at a llama.cpp checkout")
endif()
set(WM_LIB_NAME "wmllama" CACHE STRING "output library name")
# The GPU backend built in beside the CPU one: empty for none, "opencl"
# (Adreno), "vulkan" (every other phone GPU) or "metal" (the Mac host build,
# for measuring only). build.sh passes each backend's headers and tools.
set(WM_GPU "" CACHE STRING "GPU backend: empty, opencl, vulkan, hexagon or metal")

# Static, nothing but the library, the chat-template code and libmtmd.
set(BUILD_SHARED_LIBS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON       ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI           OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI    OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL            OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS         OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE         OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS       OFF CACHE BOOL "" FORCE)
# libmtmd alone, without the tools around it. Its video path needs ffmpeg as
# a subprocess, which LLAMA_SUBPROCESS=OFF already turns off.
set(LLAMA_BUILD_MTMD         ON  CACHE BOOL "" FORCE)
# The build number and commit go into build-info.cpp. From git they depend on
# how deep the clone is; fixed here so every checkout builds the same bytes.
set(LLAMA_BUILD_NUMBER       "${WM_LLAMA_BUILD}"  CACHE STRING "" FORCE)
set(LLAMA_BUILD_COMMIT       "${WM_LLAMA_COMMIT}" CACHE STRING "" FORCE)
# ggml's own thread pool rather than OpenMP, which would mean shipping libomp.
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
# Cross-compiling: the instruction set comes from build.sh, not this machine.
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          OFF CACHE BOOL "" FORCE)
# No compiler cache, whatever the machine has: the build is what it compiles.
set(GGML_CCACHE              OFF CACHE BOOL "" FORCE)
# One GPU backend at most, and only the one asked for: Metal would come along
# on the Mac host build otherwise.
set(GGML_BLAS                OFF CACHE BOOL "" FORCE)
set(GGML_METAL               OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN              OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL              OFF CACHE BOOL "" FORCE)
if (WM_GPU STREQUAL "opencl")
    # Qualcomm's backend: Adreno's own matrix kernels, compiled by the
    # phone's driver from the source embedded in the library. It refuses
    # every GPU but Adreno and Intel, so LlamaNative loads it on Adreno only.
    set(GGML_OPENCL                      ON  CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS        ON  CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS   ON  CACHE BOOL "" FORCE)
    set(GGML_OPENCL_PROFILING            OFF CACHE BOOL "" FORCE)
elseif (WM_GPU STREQUAL "vulkan")
    set(GGML_VULKAN                      ON  CACHE BOOL "" FORCE)
elseif (WM_GPU STREQUAL "hexagon")
    # Qualcomm's Hexagon NPU (HTP v73 and later): ggml's backend on the ARM
    # side and one skel per HTP generation for the DSP, built with the
    # Hexagon SDK (HEXAGON_SDK_ROOT, HEXAGON_TOOLS_ROOT), which only runs on
    # x86-64 Linux: .github/workflows/llama-hexagon.yml.
    set(GGML_HEXAGON                     ON  CACHE BOOL "" FORCE)
elseif (WM_GPU STREQUAL "metal")
    set(GGML_METAL                       ON  CACHE BOOL "" FORCE)
    set(GGML_METAL_EMBED_LIBRARY         ON  CACHE BOOL "" FORCE)
elseif (NOT WM_GPU STREQUAL "")
    message(FATAL_ERROR "WM_GPU must be empty, opencl, vulkan, hexagon or metal, not '${WM_GPU}'")
endif()
# Arm's KleidiAI matrix kernels for Q4_0, Q4_K, Q8_0 and F32 weights, arm64
# only. ggml picks among them at run time from the CPU's hwcaps (dot product,
# i8mm, SVE, SME), so the baseline ARMv8.0 build carries them safely too.
# ggml would download the source at configure time; build.sh fetches the same
# release archive first, checks its hash and hands it over as
# FETCHCONTENT_SOURCE_DIR_KLEIDIAI, so configuring needs no network.
set(WM_KLEIDIAI OFF CACHE BOOL "build in KleidiAI (arm64 only)")
set(GGML_CPU_KLEIDIAI        ${WM_KLEIDIAI} CACHE BOOL "" FORCE)
set(FETCHCONTENT_FULLY_DISCONNECTED ON CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SRC}" llama EXCLUDE_FROM_ALL)

# The chat-template code (Jinja interpreter, template analysis) is a good part
# of the library and runs once per prompt, so it is built for size. llama's
# own code stays at -O3 with ggml: it builds the graph for every token, and
# an -Os build of it looked slower on a small model (README.md), for 12% off
# the library.
target_compile_options(llama-common PRIVATE -Os)
# The projector code runs once per picture, and most of it is the dozens of
# vision architectures a given model does not use: built for size as well.
target_compile_options(mtmd PRIVATE -Os)

add_library(wmllama SHARED llamacpp_jni.cpp)
set_target_properties(wmllama PROPERTIES OUTPUT_NAME "${WM_LIB_NAME}")
target_link_libraries(wmllama PRIVATE llama-common mtmd llama)
target_compile_options(wmllama PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)

if (ANDROID)
    target_link_libraries(wmllama PRIVATE log)
    target_link_options(wmllama PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,-z,max-page-size=16384)
elseif (APPLE)
    # Host test build: only the JNI entry points are exported, as on Android.
    target_link_options(wmllama PRIVATE
        -Wl,-dead_strip
        -Wl,-exported_symbol,_Java_com_wasimaster_wmkeyboard_core_localllm_LlamaNative_*)
endif()
