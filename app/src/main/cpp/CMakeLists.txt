cmake_minimum_required(VERSION 3.22.1)

project("core")

if (NOT "${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
    add_compile_options(-O3 -flto -g0 -fno-exceptions -fno-rtti)
    add_link_options(-flto -Wl,--gc-sections,--strip-all)
endif ()

set(LIB_CLASH_PATH "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libclash.so")
set(LIB_CLASH_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/includes/${ANDROID_ABI}")
set(LIB_CLASH_HEADER_PATH "${LIB_CLASH_INCLUDE_DIR}/libclash.h")
set(BRIDE_HEADER_PATH "${LIB_CLASH_INCLUDE_DIR}/bride.h")

if (NOT EXISTS ${LIB_CLASH_PATH})
    message(FATAL_ERROR "Missing ${LIB_CLASH_PATH}. Run scripts/build-flclash-core.sh first.")
endif ()

if (NOT EXISTS ${LIB_CLASH_HEADER_PATH} OR NOT EXISTS ${BRIDE_HEADER_PATH})
    message(FATAL_ERROR "Missing libclash headers for ${ANDROID_ABI}. Run scripts/build-flclash-core.sh first.")
endif ()

add_library(${CMAKE_PROJECT_NAME} SHARED
    jni_helper.cpp
    core.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${LIB_CLASH_INCLUDE_DIR})
target_link_directories(${CMAKE_PROJECT_NAME} PRIVATE "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
target_link_libraries(${CMAKE_PROJECT_NAME} clash)
