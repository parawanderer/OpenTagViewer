# The emulated Mac's Anisette core: Apple's macOS CoreADI, x86_64, run under Unicorn.
#
# Defines the static library `macadi_core`. Included by app/src/main/cpp/CMakeLists.txt for the
# app, and by app/src/test/macadi-host/ for the host check - so both build exactly these sources
# against exactly this Unicorn, rather than two copies of how they are built.
#
# **Unicorn is GPL-2.0.** It is fetched at build time, pinned by hash, and linked only into
# libmacadi.so, never into libopentagviewer.so. An APK built with this carries GPL-2.0 code.
#
# Apple's library is never part of the build or the repository. The app downloads it from
# Apple at runtime - see MacCoreAdiFetcher.

include_guard(GLOBAL)
include(FetchContent)

# Only the x86 target: CoreADI is x86_64 code, and every other architecture is megabytes of
# QEMU that would never run.
set(UNICORN_ARCH "x86" CACHE STRING "Enabled unicorn architectures" FORCE)
set(UNICORN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(UNICORN_INSTALL OFF CACHE BOOL "" FORCE)
set(UNICORN_LEGACY_STATIC_ARCHIVE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        unicorn
        URL https://github.com/unicorn-engine/unicorn/archive/refs/tags/2.1.4.tar.gz
        URL_HASH SHA256=ea8863f095a0136388694e5a6063afd9bb7650e30243dd6251af59c5ce5601f4)
FetchContent_MakeAvailable(unicorn)

add_library(macadi_core STATIC
        ${CMAKE_CURRENT_LIST_DIR}/macho.cpp
        ${CMAKE_CURRENT_LIST_DIR}/vm.cpp
        ${CMAKE_CURRENT_LIST_DIR}/core_adi.cpp)
target_include_directories(macadi_core PUBLIC
        ${CMAKE_CURRENT_LIST_DIR}
        ${unicorn_SOURCE_DIR}/include)
target_compile_features(macadi_core PUBLIC cxx_std_17)
# Errors are C++ exceptions up to the JNI boundary, where they become Java ones.
target_compile_options(macadi_core PRIVATE -fexceptions)
set_target_properties(macadi_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(macadi_core PUBLIC unicorn)
