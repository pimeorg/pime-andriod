cmake_minimum_required(VERSION 3.25)

project(pime_android_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED PIME_CORE_DIR)
    set(PIME_CORE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../pime-core")
endif()
if(NOT EXISTS "${PIME_CORE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "pime-core not found at ${PIME_CORE_DIR} (pass -DPIME_CORE_DIR=...)")
endif()

# The shell links the kernel statically: one .so, no duplicate packaging.
set(PIME_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(PIME_BUILD_WASM OFF CACHE BOOL "" FORCE)
set(PIME_BUILD_LUA OFF CACHE BOOL "" FORCE)
set(PIME_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PIME_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(PIME_SANITIZE_ADDRESS OFF CACHE BOOL "" FORCE)
set(PIME_SANITIZE_UNDEFINED OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF)

add_subdirectory("${PIME_CORE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/pime-core")

add_library(pime_jni SHARED pime_jni.cpp)
target_include_directories(pime_jni PRIVATE "${PIME_CORE_DIR}/include")
target_link_libraries(pime_jni PRIVATE pime_core android log)
target_compile_options(pime_jni PRIVATE -Wall -Wextra)
