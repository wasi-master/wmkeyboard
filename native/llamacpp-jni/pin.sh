# The llama.cpp release libwmllama is built from, sourced by build.sh and
# host-test.sh. Bump all four together, then rebuild and commit the libraries.
LLAMA_TAG=b11951
LLAMA_COMMIT=a71c00f0bb2d60a4a52837d80baa534b651ebd91
LLAMA_SHORT=a71c00f
LLAMA_BUILD=11951
# What the OpenCL variant (libwmllama_opencl.so) compiles and links against:
# Khronos's headers, and its ICD loader built only to have a libOpenCL.so to
# link to. Neither ships: on the phone the library finds the vendor's own
# libOpenCL.so by that name.
OPENCL_HEADERS_TAG=v2026.05.29
OPENCL_HEADERS_COMMIT=6fe718c31a45fe25151362a72ef041c3a1047cbd
OPENCL_LOADER_TAG=v2026.05.29
OPENCL_LOADER_COMMIT=b7bd2803acc779c03d96588e9ca9e9568a18698a
# KleidiAI, Arm's matrix kernels, built into every arm64 libwmllama: the
# release archive ggml's own CMake names for this llama.cpp (its
# KLEIDIAI_COMMIT_TAG), fetched by build.sh and checked against this hash
# rather than downloaded at configure time. Bump with llama.cpp.
KLEIDIAI_TAG=v1.32.0
KLEIDIAI_SHA256=a48d373e7c5d721a0aecbf5ddaf0bdf245bc8064e6b84086f13db0e102b553a6
# What the Vulkan variant (libwmllama_vulkan.so) compiles against is pinned in
# native/vulkan.sh, shared with whisper.cpp's build.
