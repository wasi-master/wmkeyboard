# libwmdocscan: wm_docscan_jni.cpp, docscan.cpp and themecopy.cpp over a static OpenCV
# (core, imgproc and geometry only). Driven by build.sh, which builds and installs
# OpenCV first and passes its install in OpenCV_DIR; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmdocscan CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(OpenCV REQUIRED COMPONENTS core imgproc geometry)

add_library(wmdocscan SHARED wm_docscan_jni.cpp docscan.cpp themecopy.cpp)
target_include_directories(wmdocscan PRIVATE ${OpenCV_INCLUDE_DIRS})
target_link_libraries(wmdocscan PRIVATE ${OpenCV_LIBS} jnigraphics)
target_compile_options(wmdocscan PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(wmdocscan PRIVATE
    -Wl,--no-undefined
    -Wl,--gc-sections
    -Wl,--icf=all
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    # Stripped at the link, so the build id is a hash of the bytes that ship.
    # Linked unstripped, it hashed symbol and debug sections that still carry
    # a trace of the build machine, and two builds differed in nothing else.
    -Wl,--strip-all
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
