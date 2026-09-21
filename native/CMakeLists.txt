cmake_minimum_required(VERSION 3.28)
project(metalmod LANGUAGES C CXX OBJC OBJCXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_OBJCXX_STANDARD 20)
set(CMAKE_OBJCXX_STANDARD_REQUIRED ON)

# Enforce macOS 26+ target on Apple Silicon
if(NOT APPLE)
    message(FATAL_ERROR "MetalMod requires macOS on Apple Silicon.")
endif()

set(CMAKE_OSX_DEPLOYMENT_TARGET "26.0" CACHE STRING "Minimum macOS version" FORCE)
set(CMAKE_OSX_ARCHITECTURES "arm64" CACHE STRING "Target architecture" FORCE)

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

set(SOURCES
    src/metalmod_bridge.mm
    src/metalmod_spatial.mm
    src/metalmod_temporal.mm
    src/metalmod_interpolator.mm
    src/metalmod_compositor.mm
    src/metalmod_pacer.mm
)

add_library(metalmod SHARED ${SOURCES})

find_library(METAL_FRAMEWORK Metal REQUIRED)
find_library(METALFX_FRAMEWORK MetalFX REQUIRED)
find_library(QUARTZCORE_FRAMEWORK QuartzCore REQUIRED)
find_library(COCOA_FRAMEWORK Cocoa REQUIRED)

target_link_libraries(metalmod PRIVATE
    ${METAL_FRAMEWORK}
    ${METALFX_FRAMEWORK}
    ${QUARTZCORE_FRAMEWORK}
    ${COCOA_FRAMEWORK}
)

target_compile_options(metalmod PRIVATE
    -Wall
    -Wextra
    -fobjc-arc
)

set_target_properties(metalmod PROPERTIES
    OUTPUT_NAME "metalmod"
    PREFIX "lib"
    SUFFIX ".dylib"
    BUILD_WITH_INSTALL_RPATH TRUE
    INSTALL_RPATH "@loader_path"
)
