cmake_minimum_required(VERSION 3.22.1)
project(fantastic_alac LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ALAC_CODEC_DIR "${CMAKE_CURRENT_LIST_DIR}/../vendor/alac/codec")
set(ALAC_SOURCES
    "${ALAC_CODEC_DIR}/ALACBitUtilities.c"
    "${ALAC_CODEC_DIR}/ALACDecoder.cpp"
    "${ALAC_CODEC_DIR}/EndianPortable.c"
    "${ALAC_CODEC_DIR}/ag_dec.c"
    "${ALAC_CODEC_DIR}/dp_dec.c"
    "${ALAC_CODEC_DIR}/matrix_dec.c"
)

add_library(fantastic_alac SHARED alac_jni.cpp ${ALAC_SOURCES})
target_include_directories(fantastic_alac PRIVATE "${ALAC_CODEC_DIR}")
target_compile_options(fantastic_alac PRIVATE -fvisibility=hidden)
set_target_properties(fantastic_alac PROPERTIES OUTPUT_NAME "fantastic_alac")
