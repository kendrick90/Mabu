cmake_minimum_required(VERSION 3.22)
project(mabuserial C CXX)

# ---------------------------------------------------------------------------
# llama.cpp configuration. Disable everything we don't have or don't want
# on RK3288 / armv7 -- no GPU backends, no BLAS, no examples/tests/server.
# Native CPU detection off because we're cross-compiling for Android.
# ---------------------------------------------------------------------------
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_BLAS            OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL          OFF CACHE BOOL "" FORCE)
set(GGML_CUDA            OFF CACHE BOOL "" FORCE)
set(GGML_METAL           OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

# llama.cpp lives under cpp/llama.cpp/ -- cloned by setup-llama.ps1 and
# .gitignored (too big to vendor in the repo).
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt)
    add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)
    set(MABU_HAS_LLAMA 1)
else()
    message(WARNING "llama.cpp source not found. Run setup-llama.ps1 to clone it. LLM features will be stubbed.")
    set(MABU_HAS_LLAMA 0)
endif()

# ---------------------------------------------------------------------------
# Our native library. serial.c (motor UART) + llama_jni.cpp (LLM bridge).
# ---------------------------------------------------------------------------
add_library(mabuserial SHARED
    serial.c
    llama_jni.cpp
)
target_link_libraries(mabuserial log)

if(MABU_HAS_LLAMA)
    target_include_directories(mabuserial PRIVATE
        llama.cpp/include
        llama.cpp/ggml/include
    )
    target_link_libraries(mabuserial llama)
    target_compile_definitions(mabuserial PRIVATE MABU_HAS_LLAMA=1)
endif()
