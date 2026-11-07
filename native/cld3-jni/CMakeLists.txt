# libwmcld3: cld3_jni.cpp over Google's CLD3 language identifier and a static
# protobuf-lite, which CLD3 reads its feature specification with. build.sh
# passes the CLD3 tree (CLD3_SRC), the protobuf tree (PROTOBUF_SRC) and the
# directory protoc wrote CLD3's three .proto files to (CLD3_GEN, laid out
# cld_3/protos/*.pb.{h,cc} as CLD3's includes expect). See README.md.
cmake_minimum_required(VERSION 3.18)
project(wmcld3 CXX)

set(CMAKE_CXX_STANDARD 17)
set(CLD3_SRC "" CACHE PATH "CLD3 source tree")
set(PROTOBUF_SRC "" CACHE PATH "protobuf source tree")
set(CLD3_GEN "" CACHE PATH "protoc output for CLD3's protos")

# protobuf-lite alone: no protoc, no tests, no zlib.
set(protobuf_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_PROTOC_BINARIES OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(protobuf_WITH_ZLIB OFF CACHE BOOL "" FORCE)
set(protobuf_INSTALL OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_LIBPROTOC OFF CACHE BOOL "" FORCE)
add_subdirectory("${PROTOBUF_SRC}/cmake" protobuf EXCLUDE_FROM_ALL)

set(CLD3_SOURCES
    base.cc embedding_feature_extractor.cc embedding_network.cc feature_extractor.cc
    feature_types.cc fml_parser.cc language_identifier_features.cc lang_id_nn_params.cc
    nnet_language_identifier.cc registry.cc relevant_script_feature.cc sentence_features.cc
    script_span/fixunicodevalue.cc script_span/generated_entities.cc script_span/generated_ulscript.cc
    script_span/getonescriptspan.cc script_span/offsetmap.cc script_span/text_processing.cc
    script_span/utf8statetable.cc task_context.cc task_context_params.cc unicodetext.cc
    utils.cc workspace.cc)
list(TRANSFORM CLD3_SOURCES PREPEND "${CLD3_SRC}/src/")
file(GLOB CLD3_PB "${CLD3_GEN}/cld_3/protos/*.pb.cc")

add_library(wmcld3 SHARED cld3_jni.cpp ${CLD3_SOURCES} ${CLD3_PB})
target_include_directories(wmcld3 PRIVATE "${CLD3_SRC}/src" "${CLD3_GEN}" "${PROTOBUF_SRC}/src")
target_link_libraries(wmcld3 PRIVATE libprotobuf-lite)
target_compile_options(wmcld3 PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections -Wno-deprecated-declarations)
if (ANDROID)
    target_link_libraries(wmcld3 PRIVATE log)
    target_link_options(wmcld3 PRIVATE
        -Wl,--no-undefined
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni.map
        -Wl,--build-id=sha1
        -Wl,-z,max-page-size=16384)
endif()
