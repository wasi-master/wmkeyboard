# libwmexecutorch: executorch_jni.cpp over a selective, static ExecuTorch
# build (the ops in ops.txt, optimized kernels where they exist and portable
# ones otherwise, the XNNPACK delegate, the Module extension). build.sh
# builds and installs ExecuTorch first and passes its prefix in ET_PREFIX; see
# README.md. The same file builds a macOS dylib for testing on the desktop.
cmake_minimum_required(VERSION 3.24)
project(wmexecutorch CXX)

set(CMAKE_CXX_STANDARD 17)
set(ET_PREFIX "" CACHE PATH "ExecuTorch install prefix")
# NO_CMAKE_FIND_ROOT_PATH: the NDK toolchain confines package lookups to its
# sysroot, and the ExecuTorch install is outside it.
find_package(executorch CONFIG REQUIRED PATHS "${ET_PREFIX}/lib/cmake/ExecuTorch"
    NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)

set(WM_LIB_NAME "wmexecutorch" CACHE STRING "output library name")
# The Qualcomm backend's archives, out of the ExecuTorch build tree (build.sh),
# for libwmexecutorch_qnn; empty for the plain library.
set(WM_QNN_LIBS "" CACHE STRING "Qualcomm backend archives")

add_library(wmexecutorch SHARED executorch_jni.cpp)
set_target_properties(wmexecutorch PROPERTIES OUTPUT_NAME "${WM_LIB_NAME}")
if (WM_QNN_LIBS)
    target_compile_definitions(wmexecutorch PRIVATE WM_QNN)
    # The delegate registers itself from a static initialiser: its archive
    # whole, the rest of the backend as a group, since its parts call each
    # other in no single order.
    set(_qnn_whole "")
    set(_qnn_rest "")
    foreach(lib IN LISTS WM_QNN_LIBS)
        if (lib MATCHES "libqnn_executorch_backend\\.a$")
            list(APPEND _qnn_whole "${lib}")
        else()
            list(APPEND _qnn_rest "${lib}")
        endif()
    endforeach()
    if (NOT _qnn_whole)
        message(FATAL_ERROR "libqnn_executorch_backend.a is not among WM_QNN_LIBS")
    endif()
    target_link_libraries(wmexecutorch PRIVATE
        "-Wl,--whole-archive" ${_qnn_whole} "-Wl,--no-whole-archive"
        "-Wl,--start-group" ${_qnn_rest} "-Wl,--end-group"
        dl)
endif()
if (JNI_INCLUDE)
    target_include_directories(wmexecutorch PRIVATE "${JNI_INCLUDE}")
endif()
target_include_directories(wmexecutorch PRIVATE "${ET_PREFIX}/include")
# Kernels and the delegate register themselves from static initialisers, which
# a plain static link would drop as unreferenced; both targets carry their own
# whole-archive link options, so they are named plainly here.
target_link_libraries(wmexecutorch PRIVATE
    executorch_selected_kernels
    xnnpack_backend
    extension_module_static
    extension_tensor
    extension_data_loader
    extension_flat_tensor
    extension_named_data_map
    executorch
    executorch_core
)
target_compile_options(wmexecutorch PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
if (ANDROID)
    target_link_libraries(wmexecutorch PRIVATE log)
    target_link_options(wmexecutorch PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,-z,max-page-size=16384)
endif()
