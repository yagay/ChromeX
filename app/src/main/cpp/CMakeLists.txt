cmake_minimum_required(VERSION 3.22.1)
project(chromex_native LANGUAGES C CXX)

find_package(shadowhook REQUIRED CONFIG)

add_library(chromex_native SHARED
        native_bridge.cpp
        libchrome_resolver.cpp
        arm64_signature_scanner.cpp
        download_conflict_hook.cpp)

target_include_directories(chromex_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_features(chromex_native PRIVATE cxx_std_20)
target_compile_options(chromex_native PRIVATE
        -Wall -Wextra -Werror
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections)

target_link_options(chromex_native PRIVATE
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384)

target_link_libraries(chromex_native
        android
        log
        dl
        shadowhook::shadowhook)
