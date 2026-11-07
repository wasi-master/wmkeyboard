# libwmimagegen: wm_imagegen_jni.cpp and imagegen.cpp over a static MNN (the
# CPU backend, and on arm64 the OpenCL and Vulkan ones). Driven by build.sh, which builds MNN first and passes its
# source and build trees in MNN_SRC and MNN_BUILD; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmimagegen CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(wmimagegen SHARED wm_imagegen_jni.cpp imagegen.cpp)
target_include_directories(wmimagegen PRIVATE ${MNN_SRC}/include)
# MNN registers its operators and backends from static initialisers, which a
# plain static link would drop: the whole archive goes in.
target_link_libraries(wmimagegen PRIVATE -Wl,--whole-archive ${MNN_BUILD}/libMNN.a -Wl,--no-whole-archive log)
target_compile_options(wmimagegen PRIVATE -O2 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(wmimagegen PRIVATE
    -Wl,--no-undefined
    -Wl,--gc-sections
    -Wl,--icf=all
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    -Wl,--strip-all
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
