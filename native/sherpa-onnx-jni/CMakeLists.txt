# libwmsherpa: sherpa_jni.cpp over a static sherpa-onnx (C API, speech
# recognition only). Driven by build.sh, which passes the sherpa-onnx tree in
# SHERPA_SRC and points sherpa-onnx's own CMake at ONNX Runtime's headers and
# a link stub through SHERPA_ONNXRUNTIME_INCLUDE_DIR / _LIB_DIR; see README.md.
cmake_minimum_required(VERSION 3.18)
project(wmsherpa C CXX)

set(CMAKE_CXX_STANDARD 17)
set(SHERPA_SRC "" CACHE PATH "sherpa-onnx source tree")
# The second library, libwmsherpa_qnn (qnn_jni.cpp): the same sherpa-onnx with
# its QNN recognizers switched on, which needs QNN_SDK_ROOT in the environment
# (only its include/QNN is read). A tree of its own, since the switch changes
# how all of sherpa-onnx compiles; build.sh configures it apart.
option(WMSHERPA_QNN "Build libwmsherpa_qnn instead of libwmsherpa" OFF)
if (NOT EXISTS "${SHERPA_SRC}/sherpa-onnx/c-api/c-api.h")
    message(FATAL_ERROR "SHERPA_SRC must point at a sherpa-onnx checkout")
endif()

# Static: recognition and offline TTS, no diarization, audio I/O, servers or
# tools. The TTS is for Kokoro voices (kokoro_jni.cpp): since sherpa-onnx 2.0
# it phonemises from a model's own lexicons and links no espeak-ng, so no GPL
# code comes with it. MMS VITS voices (vits_jni.cpp) run on ONNX Runtime
# directly, their text frontend being one token per character.
# The Omnilingual model (omnilingual_jni.cpp) runs on it directly as well, so
# its logits can be held to one script before the CTC pick, and so do the
# token classifiers (tagger_jni.cpp) that punctuate dictation, and FireRedVAD
# and Smart Turn (vad_jni.cpp, smart_turn_jni.cpp), which hear a phrase end.
# Speech enhancement is always built: dpdfnet_jni.cpp drives its streaming
# denoiser.
set(BUILD_SHARED_LIBS                      OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_C_API               ON  CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_JNI                 OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PYTHON              OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TESTS               OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_CHECK               OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_PORTAUDIO           OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_WEBSOCKET           OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_BINARY              OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_TTS                 ON  CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_BUILD_C_API_EXAMPLES       OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_GPU                 OFF CACHE BOOL "" FORCE)
set(SHERPA_ONNX_USE_PRE_INSTALLED_ONNXRUNTIME_IF_AVAILABLE ON CACHE BOOL "" FORCE)
set(SHERPA_ONNX_ENABLE_QNN                 ${WMSHERPA_QNN} CACHE BOOL "" FORCE)
if (WMSHERPA_QNN)
    # Recognition only: the QNN library reads no text aloud.
    set(SHERPA_ONNX_ENABLE_TTS OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${SHERPA_SRC}" sherpa-onnx EXCLUDE_FROM_ALL)

# FireRedVAD (vad_jni.cpp) reads the same Kaldi filterbanks sherpa-onnx
# computes for its recognizers, so it uses the kaldi-native-fbank that
# sherpa-onnx fetched and already links; FetchContent's properties are global,
# so its source directory can be read from here.
include(FetchContent)
FetchContent_GetProperties(kaldi_native_fbank)
if (NOT EXISTS "${kaldi_native_fbank_SOURCE_DIR}/kaldi-native-fbank/csrc/online-feature.h")
    message(FATAL_ERROR "sherpa-onnx did not fetch kaldi-native-fbank")
endif()

if (WMSHERPA_QNN)
    # sherpa-onnx's C++ API; its QNN code dlopens Qualcomm's libraries at run
    # time, so nothing of Qualcomm's is linked.
    add_library(wmsherpa_qnn SHARED qnn_jni.cpp)
    target_include_directories(wmsherpa_qnn PRIVATE "${SHERPA_SRC}" "$ENV{SHERPA_ONNXRUNTIME_INCLUDE_DIR}")
    target_link_libraries(wmsherpa_qnn PRIVATE sherpa-onnx-core log dl)
    target_compile_options(wmsherpa_qnn PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
    target_link_options(wmsherpa_qnn PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni_qnn.map
        -Wl,--build-id=sha1
        -Wl,-z,max-page-size=16384)
    return()
endif()

add_library(wmsherpa SHARED sherpa_jni.cpp vits_jni.cpp kokoro_jni.cpp omnilingual_jni.cpp dpdfnet_jni.cpp
    tagger_jni.cpp vad_jni.cpp smart_turn_jni.cpp)
target_include_directories(wmsherpa PRIVATE "${SHERPA_SRC}" "$ENV{SHERPA_ONNXRUNTIME_INCLUDE_DIR}"
    "${kaldi_native_fbank_SOURCE_DIR}")
target_link_libraries(wmsherpa PRIVATE sherpa-onnx-c-api kaldi-native-fbank-core log)
target_compile_options(wmsherpa PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(wmsherpa PRIVATE
    -Wl,--no-undefined
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384)
