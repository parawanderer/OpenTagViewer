# Host tests for the stand-ins we ship for Apple's libCoreFoundation.so and libmediaplatform.so.
#
# No Android and no emulator: the stubs are ours, plain C and C++, so they build for the machine
# running this. CI runs it on x86_64 and arm64 Linux (native-stubs.yml), which are the two ABIs
# the app ships and the two calling conventions a stub has to get right.
#
#   cmake -S app/src/test/cpp -B build/native-stubs && cmake --build build/native-stubs
#   ctest --test-dir build/native-stubs --output-on-failure
#
# ELF only. Mach-O prefixes every C symbol with an underscore and dlsym adds it for you, so a
# stub named by asm label cannot be looked up there under the name Apple's library uses.

cmake_minimum_required(VERSION 3.22)
project(opentagviewer_stub_tests C CXX)

if (APPLE OR WIN32)
    message(FATAL_ERROR "These tests check ELF exports and run on Linux only - see the note at "
            "the top of this file. Use a Linux machine or container.")
endif ()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MAIN_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp")

include("${MAIN_CPP}/stubs/AppleStubs.cmake")
add_apple_stub(CoreFoundation)
add_apple_stub(mediaplatform)

# The NDK's log header, faked. Only the stubs include it.
foreach (stub CoreFoundation_stub mediaplatform_stub)
    target_include_directories(${stub} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
endforeach ()

add_executable(stub_tests stub_tests.cpp)
target_include_directories(stub_tests PRIVATE "${MAIN_CPP}")
target_link_libraries(stub_tests PRIVATE ${CMAKE_DL_LIBS})
# Built first, but not linked: the tests dlopen them, as Apple's library is loaded against them.
add_dependencies(stub_tests CoreFoundation_stub mediaplatform_stub)

enable_testing()
add_test(NAME stubs
        COMMAND stub_tests
        "$<TARGET_FILE:CoreFoundation_stub>" "${MAIN_CPP}/stubs/libCoreFoundation.symbols"
        "$<TARGET_FILE:mediaplatform_stub>" "${MAIN_CPP}/stubs/libmediaplatform.symbols")
