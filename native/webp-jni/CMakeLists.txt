# libwmwebp: wm_webp_jni.c over a static libwebp, encoder and libwebpmux only
# (WebPAnimEncoder lives there). Driven by build.sh, which passes the libwebp
# tree in WEBP_SRC; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmwebp C)

set(CMAKE_C_STANDARD 11)
set(WEBP_SRC "" CACHE PATH "libwebp source tree (the repository root)")
option(WMWEBP_DESKTOP_TOOLS "Also build libwebp's webpinfo, for the desktop test" OFF)
if (NOT EXISTS "${WEBP_SRC}/src/webp/mux.h")
    message(FATAL_ERROR "WEBP_SRC must point at a libwebp checkout")
endif()

# Upstream's own build with every tool switched off: no cwebp, dwebp, gif2webp,
# img2webp, vwebp, webpmux or extras, so none of their image readers (libpng,
# libjpeg, giflib) is looked for. What is left is libsharpyuv, libwebp and
# libwebpmux. libwebp carries the decoder too, which the animation encoder
# calls to lay each finished frame onto its canvas; the linker keeps only what
# that reaches. The platform decodes WebP everywhere else.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_ANIM_UTILS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_CWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_DWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_GIF2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_IMG2WEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_VWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPINFO ${WMWEBP_DESKTOP_TOOLS} CACHE BOOL "" FORCE)
set(WEBP_BUILD_LIBWEBPMUX ON CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPMUX OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBP_JS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_FUZZTEST OFF CACHE BOOL "" FORCE)
set(WEBP_USE_THREAD ON CACHE BOOL "" FORCE)
set(WEBP_NEAR_LOSSLESS ON CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${WEBP_SRC} webp EXCLUDE_FROM_ALL)
foreach (target sharpyuv webpdecode webpdspdecode webputilsdecode webpencode webpdsp webputils webp libwebpmux)
    if (TARGET ${target})
        target_compile_options(${target} PRIVATE -Os -ffunction-sections -fdata-sections -fvisibility=hidden)
    endif()
endforeach()

add_library(wmwebp SHARED wm_webp_jni.c)
target_include_directories(wmwebp PRIVATE ${WEBP_SRC}/src)
target_link_libraries(wmwebp PRIVATE libwebpmux webp sharpyuv)
target_compile_options(wmwebp PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections -Wall -Wextra)
if (ANDROID)
    target_compile_definitions(wmwebp PRIVATE WMWEBP_ANDROID_BITMAP=1)
    target_link_libraries(wmwebp PRIVATE jnigraphics)
    target_link_options(wmwebp PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--icf=all
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,--strip-all
        -Wl,-z,max-page-size=16384)
endif()
