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
# A delegate built beside XNNPACK (build.sh): "qnn", "vulkan" or "neuron",
# empty for the plain library, with its archives out of the ExecuTorch build
# tree and the name of the one that registers the backend.
set(WM_BACKEND "" CACHE STRING "delegate variant")
set(WM_BACKEND_LIBS "" CACHE STRING "the delegate's archives")
set(WM_BACKEND_WHOLE "" CACHE STRING "the archive that registers the delegate")

add_library(wmexecutorch SHARED executorch_jni.cpp)
set_target_properties(wmexecutorch PROPERTIES OUTPUT_NAME "${WM_LIB_NAME}")
if (WM_BACKEND)
    string(TOUPPER "${WM_BACKEND}" _backend)
    target_compile_definitions(wmexecutorch PRIVATE WM_${_backend} WM_DELEGATE="${WM_BACKEND}")
    # The delegate registers itself from a static initialiser: its archive
    # whole, the rest of the backend as a group, since its parts call each
    # other in no single order.
    set(_whole "")
    set(_rest "")
    foreach(lib IN LISTS WM_BACKEND_LIBS)
        get_filename_component(_name "${lib}" NAME)
        if (_name STREQUAL WM_BACKEND_WHOLE)
            list(APPEND _whole "${lib}")
        else()
            list(APPEND _rest "${lib}")
        endif()
    endforeach()
    if (NOT _whole)
        message(FATAL_ERROR "${WM_BACKEND_WHOLE} is not among WM_BACKEND_LIBS")
    endif()
    target_link_libraries(wmexecutorch PRIVATE
        "-Wl,--whole-archive" ${_whole} "-Wl,--no-whole-archive"
        "-Wl,--start-group" ${_rest} "-Wl,--end-group"
        dl)
    if (WM_BACKEND STREQUAL "neuron")
        # The adapter's AHardwareBuffer calls.
        target_link_libraries(wmexecutorch PRIVATE android)
    endif()
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
