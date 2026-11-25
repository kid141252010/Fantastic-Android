cmake_minimum_required(VERSION 3.22.1)
project(fantastic_alac LANGUAGES C CXX)

file(GLOB_RECURSE ALAC_SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_LIST_DIR}/../vendor/alac/codec/*.c"
    "${CMAKE_CURRENT_LIST_DIR}/../vendor/alac/codec/*.cpp")
list(FILTER ALAC_SOURCES EXCLUDE REGEX "/ALACEncoder\\.cpp$")

add_library(fantastic_alac SHARED
    alac_jni.cpp
    ${ALAC_SOURCES})

target_include_directories(fantastic_alac PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../vendor/alac/codec")
target_compile_features(fantastic_alac PRIVATE cxx_std_17)
target_compile_options(fantastic_alac PRIVATE -Wall -Wextra -Werror=return-type)
target_compile_definitions(fantastic_alac PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
target_link_libraries(fantastic_alac PRIVATE log)
