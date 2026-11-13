# libwmbergamot: bergamot_jni.cpp over Mozilla's bergamot-translator (the
# inference/ directory of mozilla/translations, which bundles its Marian fork),
# PCRE2 for the sentence splitter, and zstd's decompressor. Driven by build.sh,
# which passes the three trees and the per-ABI GEMM choice; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmbergamot C CXX)

set(BERGAMOT_SRC "" CACHE PATH "mozilla/translations checkout")
set(PCRE2_SRC "" CACHE PATH "PCRE2 checkout")
set(ZSTD_SRC "" CACHE PATH "zstd checkout")
set(WM_PREFIX_MAP "" CACHE STRING "-ffile-prefix-map flags, so no build path reaches the library")
set(WM_ARCH_FLAGS "" CACHE STRING "Instruction-set flags the NDK would have passed")
foreach(tree BERGAMOT_SRC PCRE2_SRC ZSTD_SRC)
    if (NOT IS_DIRECTORY "${${tree}}")
        message(FATAL_ERROR "${tree} must point at a checkout")
    endif()
endforeach()

# Marian replaces CMAKE_CXX_FLAGS wholesale, dropping the NDK's own, so the
# path map and the instruction set go in as directory options, which every
# target below this one inherits.
separate_arguments(prefix_map UNIX_COMMAND "${WM_PREFIX_MAP}")
separate_arguments(arch_flags UNIX_COMMAND "${WM_ARCH_FLAGS}")
add_compile_options(${prefix_map} ${arch_flags} -ffunction-sections -fdata-sections)

# PCRE2: the 8-bit library only, no JIT (sljit is not fetched).
set(PCRE2_BUILD_PCRE2_8 ON CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2_16 OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2_32 OFF CACHE BOOL "" FORCE)
set(PCRE2_SUPPORT_JIT OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2GREP OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PCRE2_STATIC_PIC ON CACHE BOOL "" FORCE)
set(PCRE2_SUPPORT_UNICODE ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory(${PCRE2_SRC} pcre2 EXCLUDE_FROM_ALL)
# ssplit-cpp looks PCRE2 up with find_library; preset answers skip the search.
set(PCRE2_LIBRARIES pcre2-8-static CACHE STRING "" FORCE)
set(PCRE2_INCLUDE_DIRS ${CMAKE_BINARY_DIR}/pcre2 CACHE PATH "" FORCE)

# Bergamot and Marian: the library alone, CPU only, static, no BLAS but ruy
# on arm64, no git, no network.
set(GIT_SUBMODULE OFF CACHE BOOL "" FORCE)
set(COMPILE_CUDA OFF CACHE BOOL "" FORCE)
set(COMPILE_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(SSPLIT_COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(USE_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(USE_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(USE_MKL OFF CACHE BOOL "" FORCE)
set(USE_MPI OFF CACHE BOOL "" FORCE)
set(USE_NCCL OFF CACHE BOOL "" FORCE)
set(USE_FBGEMM OFF CACHE BOOL "" FORCE)
set(USE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(USE_CCACHE OFF CACHE BOOL "" FORCE)
set(USE_APPLE_ACCELERATE OFF CACHE BOOL "" FORCE)
set(COMPILE_SERVER OFF CACHE BOOL "" FORCE)
set(COMPILE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
add_subdirectory(${BERGAMOT_SRC}/inference bergamot EXCLUDE_FROM_ALL)

# Marian decides its defines (ARM, SSE through simd_utils, the GEMM backend,
# SentencePiece) for its own directory only, partly through CMAKE_CXX_FLAGS.
# Bergamot's sources and this bridge include its headers, which read the
# same defines, so both get exactly Marian's set.
set(marian_dir ${BERGAMOT_SRC}/inference/marian-fork)
get_directory_property(marian_definitions DIRECTORY ${marian_dir} COMPILE_DEFINITIONS)
get_directory_property(marian_options DIRECTORY ${marian_dir} COMPILE_OPTIONS)
get_directory_property(marian_cxx_flags DIRECTORY ${marian_dir} DEFINITION CMAKE_CXX_FLAGS)
separate_arguments(marian_cxx_flags UNIX_COMMAND "${marian_cxx_flags}")
foreach(flag ${marian_cxx_flags})
    if (flag MATCHES "^-D(.+)$")
        list(APPEND marian_definitions ${CMAKE_MATCH_1})
    endif()
endforeach()
list(REMOVE_DUPLICATES marian_definitions)
list(FILTER marian_options INCLUDE REGEX "^-f(lax-vector-conversions|no-strict-aliasing)$")
message(STATUS "Marian defines for Bergamot and the bridge: ${marian_definitions} ${marian_options}")
foreach(target bergamot-translator-source)
    target_compile_definitions(${target} PUBLIC ${marian_definitions})
    target_compile_options(${target} PUBLIC ${marian_options})
endforeach()

# zstd's decompressor, for the .zst files Mozilla publishes the models as.
# The C decoder alone, no assembly.
file(GLOB zstd_sources ${ZSTD_SRC}/lib/common/*.c ${ZSTD_SRC}/lib/decompress/*.c)
add_library(wmzstd STATIC ${zstd_sources})
target_include_directories(wmzstd PUBLIC ${ZSTD_SRC}/lib)
target_compile_definitions(wmzstd PRIVATE ZSTD_DISABLE_ASM ZSTD_TRACE=0 ZSTD_MULTITHREAD=0 XXH_NAMESPACE=WM_ ZSTD_LEGACY_SUPPORT=0)
set_target_properties(wmzstd PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden)

add_library(wmbergamot SHARED bergamot_jni.cpp)
target_link_libraries(wmbergamot PRIVATE bergamot-translator-source wmzstd log)
set_target_properties(wmbergamot PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_link_options(wmbergamot PRIVATE
    -Wl,--gc-sections
    -Wl,--icf=all
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
