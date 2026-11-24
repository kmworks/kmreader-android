# waifu2x upscaling via ncnn + Vulkan (see docs/waifu2x-upscaling-plan.md §4.3)
cmake_minimum_required(VERSION 3.22.1)
project(waifu2x-jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Prebuilt ncnn android-vulkan SDK, fetched by the fetchNcnnSdk Gradle task (gitignored)
set(NCNN_SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ncnn/ncnn-20260113-android-vulkan/${ANDROID_ABI})
if(NOT EXISTS ${NCNN_SDK_DIR}/lib/libncnn.a)
    message(FATAL_ERROR "ncnn SDK not found at ${NCNN_SDK_DIR}. Run ./gradlew fetchNcnnSdk first.")
endif()

add_library(waifu2x-jni SHARED waifu2x_jni.cpp)

# ncnn public headers live in include/ncnn/, sources include them as "net.h" etc.
target_include_directories(waifu2x-jni PRIVATE ${NCNN_SDK_DIR}/include/ncnn)

# Static prebuilt libraries from the ncnn SDK
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION ${NCNN_SDK_DIR}/lib/libncnn.a)

# glslang is linked into libncnn.a's online SPIR-V compilation path; pull in its parts
foreach(glslang_lib SPIRV glslang MachineIndependent GenericCodeGen OSDependent glslang-default-resource-limits)
    add_library(${glslang_lib} STATIC IMPORTED)
    set_target_properties(${glslang_lib} PROPERTIES IMPORTED_LOCATION ${NCNN_SDK_DIR}/lib/lib${glslang_lib}.a)
endforeach()

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(vulkan-lib vulkan)

# ncnn's prebuilt android-vulkan build uses the real LLVM OpenMP runtime
# (__kmpc_* symbols); the NDK ships it under lib/clang/<ver>/lib/linux/<abi>/.
# Link the static archive so no extra .so is bundled into the APK.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CLANG_LINUX_ABI aarch64)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CLANG_LINUX_ABI arm)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(CLANG_LINUX_ABI x86_64)
elseif(ANDROID_ABI STREQUAL "x86")
    set(CLANG_LINUX_ABI i386)
endif()
file(GLOB CLANG_RT_DIRS LIST_DIRECTORIES true
    ${ANDROID_NDK}/toolchains/llvm/prebuilt/*/lib/clang/*)
find_library(omp-lib NAMES libomp.a omp
    PATHS ${CLANG_RT_DIRS}
    PATH_SUFFIXES lib/linux/${CLANG_LINUX_ABI}
    NO_DEFAULT_PATH)
if(NOT omp-lib)
    message(FATAL_ERROR "libomp not found in NDK for ${ANDROID_ABI}")
endif()

# The prebuilt ncnn SDK is compiled with NCNN_DISABLE_RTTI (no typeinfo for ncnn::Pipeline), so
# deriving a Pipeline subclass only links when we match that: keep RTTI off here too.
target_compile_options(waifu2x-jni PRIVATE -ffunction-sections -fdata-sections -fno-rtti)
target_link_options(waifu2x-jni PRIVATE -Wl,--gc-sections)
target_link_options(waifu2x-jni PRIVATE $<$<CONFIG:Release>:-s>)

target_link_libraries(
    waifu2x-jni
    ncnn
    -Wl,--start-group
    SPIRV glslang MachineIndependent GenericCodeGen OSDependent glslang-default-resource-limits
    -Wl,--end-group
    ${vulkan-lib}
    ${android-lib}
    ${log-lib}
    ${jnigraphics-lib}
    ${omp-lib}
)
