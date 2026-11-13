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

option(PIME_ENABLE_WASM "Build the WAMR WASM plugin runtime" ON)
set(PIME_WAMR_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/wamr"
    CACHE PATH "wasm-micro-runtime source")

# The shell links the kernel statically: one .so, no duplicate packaging.
set(PIME_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(PIME_BUILD_LUA OFF CACHE BOOL "" FORCE)
set(PIME_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PIME_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(PIME_SANITIZE_ADDRESS OFF CACHE BOOL "" FORCE)
set(PIME_SANITIZE_UNDEFINED OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF)

if(PIME_ENABLE_WASM AND EXISTS "${PIME_WAMR_DIR}/core/iwasm/include/wasm_export.h")
    set(WAMR_BUILD_PLATFORM "android")
    set(WAMR_BUILD_INTERP 1)
    set(WAMR_BUILD_FAST_INTERP 1)
    set(WAMR_BUILD_AOT 0)
    set(WAMR_BUILD_JIT 0)
    set(WAMR_BUILD_LIBC_BUILTIN 0)
    set(WAMR_BUILD_LIBC_WASI 0)
    set(WAMR_BUILD_MULTI_MODULE 0)
    set(WAMR_BUILD_LIB_PTHREAD 0)
    set(WAMR_BUILD_LIB_WASI_THREADS 0)
    set(WAMR_BUILD_APP_FRAMEWORK 0)
    set(WAMR_BUILD_SIMD 0)
    set(WAMR_BUILD_TAIL_CALL 1)
    set(WAMR_BUILD_BULK_MEMORY 1)
    set(WAMR_BUILD_REF_TYPES 0)
    include("${PIME_WAMR_DIR}/build-scripts/runtime_lib.cmake")

    add_library(vmlib STATIC ${WAMR_RUNTIME_LIB_SOURCE})
    target_include_directories(vmlib PUBLIC "${PIME_WAMR_DIR}/core/iwasm/include")
    target_link_libraries(vmlib PUBLIC log m dl)

    set(PIME_BUILD_WASM ON CACHE BOOL "" FORCE)
    set(PIME_WAMR_INCLUDE_DIR "${PIME_WAMR_DIR}/core/iwasm/include"
        CACHE PATH "" FORCE)
    set(PIME_WAMR_LIBRARY vmlib CACHE STRING "" FORCE)
else()
    set(PIME_BUILD_WASM OFF CACHE BOOL "" FORCE)
    if(PIME_ENABLE_WASM)
        message(WARNING "WAMR not found at ${PIME_WAMR_DIR}; WASM plugins disabled")
    endif()
endif()

# The kernel is consumed as a subproject here; its CMake install/export rules
# are meant for desktop packaging and would also require exporting vmlib.
set(CMAKE_SKIP_INSTALL_RULES ON)

add_subdirectory("${PIME_CORE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/pime-core")

add_library(pime_jni SHARED pime_jni.cpp)
target_include_directories(pime_jni PRIVATE "${PIME_CORE_DIR}/include")
target_link_libraries(pime_jni PRIVATE pime_core android log)
target_compile_options(pime_jni PRIVATE -Wall -Wextra)
