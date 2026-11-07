# libwmfasttext: fasttext_jni.cpp over fastText's prediction code and the
# lid.176.ftz language identification model, compiled in (model.S). build.sh
# passes the fastText tree (FASTTEXT_SRC) and the checked model (LID_MODEL).
cmake_minimum_required(VERSION 3.18)
project(wmfasttext CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(FASTTEXT_SRC "" CACHE PATH "fastText source tree")
set(LID_MODEL "" CACHE FILEPATH "lid.176.ftz")
if (NOT EXISTS "${FASTTEXT_SRC}/src/fasttext.h" OR NOT EXISTS "${LID_MODEL}")
    message(FATAL_ERROR "FASTTEXT_SRC and LID_MODEL must point at fastText and lid.176.ftz")
endif()

# Everything but the command-line tool.
set(FASTTEXT_SOURCES
    args.cc autotune.cc densematrix.cc dictionary.cc fasttext.cc loss.cc matrix.cc
    meter.cc model.cc productquantizer.cc quantmatrix.cc utils.cc vector.cc)
list(TRANSFORM FASTTEXT_SOURCES PREPEND "${FASTTEXT_SRC}/src/")

add_library(wmfasttext SHARED fasttext_jni.cpp model.S ${FASTTEXT_SOURCES})
target_include_directories(wmfasttext PRIVATE "${FASTTEXT_SRC}/src")
set_source_files_properties(model.S PROPERTIES
    COMPILE_DEFINITIONS "WM_LID_MODEL=\"${LID_MODEL}\""
    OBJECT_DEPENDS "${LID_MODEL}")
target_compile_options(wmfasttext PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-O2 -fvisibility=hidden -ffunction-sections -fdata-sections -Wno-deprecated-declarations>)
if (ANDROID)
    target_link_libraries(wmfasttext PRIVATE log)
    target_link_options(wmfasttext PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,-z,max-page-size=16384)
endif()
