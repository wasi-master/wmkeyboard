# libwmct2: ct2_jni.cpp over CTranslate2 (CPU only, Google's ruy for every
# GEMM, no BLAS, no OpenMP) and SentencePiece. Driven by build.sh, which
# passes both trees; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmct2 C CXX)

set(CT2_SRC "" CACHE PATH "OpenNMT/CTranslate2 checkout")
set(SPM_SRC "" CACHE PATH "google/sentencepiece checkout")
set(WM_PREFIX_MAP "" CACHE STRING "-ffile-prefix-map flags, so no build path reaches the library")
foreach(tree CT2_SRC SPM_SRC)
    if (NOT IS_DIRECTORY "${${tree}}")
        message(FATAL_ERROR "${tree} must point at a checkout")
    endif()
endforeach()

separate_arguments(prefix_map UNIX_COMMAND "${WM_PREFIX_MAP}")
add_compile_options(${prefix_map} -ffunction-sections -fdata-sections)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# CTranslate2: the library alone. ruy runs both the int8 and the float GEMM
# (backend.cc picks it for each when nothing else is compiled in) and brings
# its own thread pool, so OpenMP is not needed either. arm64 runs NEON.
set(WITH_MKL OFF CACHE BOOL "" FORCE)
set(WITH_DNNL OFF CACHE BOOL "" FORCE)
set(WITH_ACCELERATE OFF CACHE BOOL "" FORCE)
set(WITH_OPENBLAS OFF CACHE BOOL "" FORCE)
set(WITH_RUY ON CACHE BOOL "" FORCE)
set(WITH_CUDA OFF CACHE BOOL "" FORCE)
set(WITH_CUDNN OFF CACHE BOOL "" FORCE)
set(WITH_HIP OFF CACHE BOOL "" FORCE)
set(WITH_TENSOR_PARALLEL OFF CACHE BOOL "" FORCE)
set(WITH_FLASH_ATTN OFF CACHE BOOL "" FORCE)
set(OPENMP_RUNTIME NONE CACHE STRING "" FORCE)
set(BUILD_CLI OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_PROFILING OFF CACHE BOOL "" FORCE)
add_subdirectory(${CT2_SRC} ctranslate2 EXCLUDE_FROM_ALL)

# SentencePiece: the encoder and decoder, static, its bundled protobuf-lite.
# Its CMake would clone abseil itself at configure time (FetchContent, which
# ignores every "disconnected" switch in the form it uses); build.sh clones
# the same tag beforehand and abseil is added here instead, so configuring
# touches no network.
set(SPM_ABSL_PROVIDER none CACHE STRING "" FORCE)
set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
set(ABSL_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory(${SPM_SRC}/third_party/abseil-cpp abseil EXCLUDE_FROM_ALL)
# SentencePiece includes "third_party/absl/...": the link its own CMake makes.
if (NOT EXISTS ${SPM_SRC}/third_party/absl)
    file(CREATE_LINK ${SPM_SRC}/third_party/abseil-cpp/absl ${SPM_SRC}/third_party/absl SYMBOLIC)
endif()
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_NFKC_COMPILE OFF CACHE BOOL "" FORCE)
add_subdirectory(${SPM_SRC} sentencepiece EXCLUDE_FROM_ALL)

add_library(wmct2 SHARED ct2_jni.cpp)
target_include_directories(wmct2 PRIVATE ${SPM_SRC}/src ${SPM_SRC})
target_link_libraries(wmct2 PRIVATE ctranslate2 sentencepiece-static)
set_target_properties(wmct2 PROPERTIES CXX_VISIBILITY_PRESET hidden CXX_STANDARD 17)
if (NOT ANDROID)
    # The desktop test build (see README.md) loads into a JVM.
    find_package(JNI REQUIRED COMPONENTS JVM)
    target_include_directories(wmct2 PRIVATE ${JAVA_INCLUDE_PATH} ${JAVA_INCLUDE_PATH2})
else()
    target_link_libraries(wmct2 PRIVATE log)
    target_link_options(wmct2 PRIVATE
        -Wl,--gc-sections
        -Wl,--icf=all
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,-z,max-page-size=16384)
endif()
