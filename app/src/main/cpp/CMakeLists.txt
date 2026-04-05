cmake_minimum_required(VERSION 3.10)
project(whisper-jni)

set(CMAKE_CXX_STANDARD 17)
# whisper.cpp lives in third_party/ at the project root
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# whisper.cpp source
set(SOURCE_FILES
    ${WHISPER_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/whisper_jni.c
)

find_library(LOG_LIB log)

function(build_library target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="1.8.4")

    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    endif()

    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
        target_compile_options(${target_name} PRIVATE -ffunction-sections -fdata-sections)
        target_link_options(${target_name} PRIVATE -Wl,--gc-sections)
        target_link_options(${target_name} PRIVATE -Wl,--exclude-libs,ALL)
        target_link_options(${target_name} PRIVATE -flto)
    endif()

    include(FetchContent)
    FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_DIR}/ggml)
    FetchContent_MakeAvailable(ggml)
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
endfunction()

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")
endif()

build_library("whisper")

include_directories(${WHISPER_DIR})
include_directories(${WHISPER_DIR}/src)
include_directories(${WHISPER_DIR}/include)
include_directories(${WHISPER_DIR}/ggml/include)
include_directories(${WHISPER_DIR}/ggml/src)
include_directories(${WHISPER_DIR}/ggml/src/ggml-cpu)
