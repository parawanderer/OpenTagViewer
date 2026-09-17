# Builds our stand-ins for Apple's libraries and a small probe executable with the NDK, to run Apple's
# real ADI libraries against Android's own linker and libc on a plain Linux machine - no emulator.
# See README.md here, and .github/workflows/adi-on-bionic.yml, which is what runs it.
#
#   cmake -S app/src/test/adi-on-bionic -B out \
#         -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
#         -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 [-DSTUBS_DIR=<a copy of stubs/>]

cmake_minimum_required(VERSION 3.22)
project(adi_on_bionic C CXX)

if (NOT ANDROID)
    message(FATAL_ERROR "Build this with the NDK's android.toolchain.cmake - see the top of this file.")
endif ()

# What app/build.gradle.kts passes to the app's own native build.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MAIN "${CMAKE_CURRENT_SOURCE_DIR}/../../main")

# Defaults to the stubs that ship. The negative control in run_probe.sh points this at a copy with
# libmediaplatform_handwritten.cpp removed, which is makeWorkQueue as it was before #232 was fixed.
set(STUBS_DIR "${MAIN}/cpp/stubs" CACHE PATH "Directory holding AppleStubs.cmake and the symbol lists")

include("${STUBS_DIR}/AppleStubs.cmake")
add_apple_stub(CoreFoundation)
add_apple_stub(mediaplatform)

# The ADI entry points, read out of AdiFunction.java rather than copied: Apple obfuscates the names
# per APK build, and two tables of them is one that goes stale. Re-read whenever the file changes.
set(ADI_FUNCTIONS "${MAIN}/java/dev/wander/android/opentagviewer/anisette/AdiFunction.java")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${ADI_FUNCTIONS}")
file(READ "${ADI_FUNCTIONS}" adi_source)
string(REGEX MATCHALL "\\(\"ADI[A-Za-z]+\", \"[A-Za-z0-9]+\"\\)" adi_entries "${adi_source}")
list(LENGTH adi_entries adi_count)
if (adi_count LESS 11)
    message(FATAL_ERROR "Found ${adi_count} entries in ${ADI_FUNCTIONS}, expected 11 - has it been restructured?")
endif ()
set(adi_header "// Generated from AdiFunction.java by CMakeLists.txt. Do not edit.\n\n")
string(APPEND adi_header "static const struct { const char *name; const char *symbol; } ADI_FUNCTIONS[] = {\n")
foreach (entry IN LISTS adi_entries)
    string(REGEX REPLACE "\\(\"([A-Za-z]+)\", \"([A-Za-z0-9]+)\"\\)" "    {\"\\1\", \"\\2\"},\n" line "${entry}")
    string(APPEND adi_header "${line}")
endforeach ()
string(APPEND adi_header "};\n")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/generated/adi_functions.h" "${adi_header}")

add_executable(adi_probe adi_probe.c)
target_include_directories(adi_probe PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/generated")
target_link_libraries(adi_probe log)
