# libwmskottie: wm_skottie_jni.cpp over Skia's static libraries (core raster,
# Skottie, sksg, skresources, skshaper with HarfBuzz, skunicode on libgrapheme,
# FreeType, the PNG/JPEG/WebP decoders). build.sh builds those with gn and
# ninja first and passes their directory in SKIA_OUT, the tree in SKIA_SRC, and
# the defines Skia's public headers were compiled with in SKIA_DEFINES (read
# back from gn, so the two halves agree). See README.md.
cmake_minimum_required(VERSION 3.18)
project(wmskottie CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(SKIA_SRC "" CACHE PATH "Skia source tree")
set(SKIA_OUT "" CACHE PATH "gn output directory holding Skia's static libraries")
set(SKIA_DEFINES "" CACHE STRING "Defines of Skia's public configs, ;-separated")
if (NOT EXISTS "${SKIA_SRC}/modules/skottie/include/Skottie.h")
    message(FATAL_ERROR "SKIA_SRC must point at a Skia checkout")
endif()
if (NOT EXISTS "${SKIA_OUT}/libskottie.a")
    message(FATAL_ERROR "SKIA_OUT must hold Skia's built static libraries")
endif()

# Every archive gn left at the top of its output directory, in a group: Skia's
# own components (complete static libraries) and the third-party ones they
# reach. The linker keeps only what the JNI reaches.
file(GLOB SKIA_LIBS "${SKIA_OUT}/*.a")
list(SORT SKIA_LIBS)

add_library(wmskottie SHARED wm_skottie_jni.cpp)
target_include_directories(wmskottie PRIVATE ${SKIA_SRC})
target_compile_definitions(wmskottie PRIVATE ${SKIA_DEFINES})
target_compile_options(wmskottie PRIVATE -Os -fvisibility=hidden -fvisibility-inlines-hidden
    -ffunction-sections -fdata-sections -fno-exceptions -fno-rtti)
if (ANDROID)
    target_link_libraries(wmskottie PRIVATE -Wl,--start-group ${SKIA_LIBS} -Wl,--end-group
        jnigraphics log)
    target_link_options(wmskottie PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--icf=all
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,--strip-all
        -Wl,-z,max-page-size=16384)
else()
    target_link_libraries(wmskottie PRIVATE ${SKIA_LIBS})
    if (APPLE)
        target_link_options(wmskottie PRIVATE -Wl,-dead_strip)
        target_link_libraries(wmskottie PRIVATE "-framework CoreFoundation")
    else()
        target_link_options(wmskottie PRIVATE -Wl,--gc-sections)
    endif()
endif()
