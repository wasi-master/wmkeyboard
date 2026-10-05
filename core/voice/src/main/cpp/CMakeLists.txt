cmake_minimum_required(VERSION 3.22.1)
project(cactus_whistle)

add_library(cactus_whistle SHARED whistle_jni.cpp)
target_include_directories(cactus_whistle PRIVATE include)
target_compile_features(cactus_whistle PRIVATE cxx_std_17)

if(CACTUS_DISABLED)
    target_compile_definitions(cactus_whistle PRIVATE CACTUS_UNSUPPORTED_ABI=1)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(NEEDLE_LIB "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}/libneedle.a")
    if(NOT EXISTS "${NEEDLE_LIB}")
        message(FATAL_ERROR "Missing official Cactus libneedle.a for ${ANDROID_ABI}")
    endif()
    add_library(needle STATIC IMPORTED)
    set_target_properties(needle PROPERTIES IMPORTED_LOCATION "${NEEDLE_LIB}")
    target_link_libraries(cactus_whistle PRIVATE needle log m)
else()
    # The upstream armeabi-v7a archive references libc++'s private
    # std::__ndk1::__hash_memory symbol, which is absent from supported NDK
    # runtimes. Keep a JNI stub for ARMv7 and x86_64 so Kotlin can cleanly
    # route those devices to Android's system recognizer.
    target_compile_definitions(cactus_whistle PRIVATE CACTUS_UNSUPPORTED_ABI=1)
endif()
