cmake_minimum_required(VERSION 3.22)
project(useeplus LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# Official CMake wrapper around libusb. Supports Android NDK out of the box
# and produces a static libusb target we can link into our JNI shared object.
set(LIBUSB_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LIBUSB_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(LIBUSB_INSTALL_TARGETS OFF CACHE BOOL "" FORCE)
set(LIBUSB_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    libusb_cmake
    GIT_REPOSITORY https://github.com/libusb/libusb-cmake.git
    GIT_TAG v1.0.29-0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(libusb_cmake)

add_library(useeplus SHARED
    supercamera_core.cpp
    jni_bridge.cpp
)

target_include_directories(useeplus PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# libusb-cmake exposes the `usb-1.0` target. Link the static flavor.
target_link_libraries(useeplus
    PRIVATE
        usb-1.0
        android
        log
)

target_compile_options(useeplus PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fvisibility=hidden
)

# Only export JNI symbols
set_target_properties(useeplus PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
