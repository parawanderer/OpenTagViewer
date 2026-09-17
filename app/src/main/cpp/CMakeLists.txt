# Native code for this app.
#
# Currently one thing: support for running Apple's ADI (Anisette) libraries in-process, so
# logins do not have to go through somebody else's Anisette server.
#
# Those libraries are Apple's. They are never redistributed by this project - they are fetched
# at runtime from Apple's own CDN, which is the same position every public Anisette server is
# in. Nothing here links against them: they are opened with dlopen() and called through
# resolved function pointers, because their symbol names are obfuscated and change between
# APK builds.

cmake_minimum_required(VERSION 3.22.1)

project("opentagviewer")

add_library(${CMAKE_PROJECT_NAME} SHARED
        adi.cpp)

# dlopen/dlsym are in libc on Android, so there is no libdl to link explicitly.
target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)

# --------------------------------------------------------------------------------------------
# Stand-ins for two of Apple's libraries.
#
# libstoreservicescore.so declares DT_NEEDED on libCoreFoundation.so and libmediaplatform.so,
# and those two pull in ICU, curl, libxml2, libdispatch and BlocksRuntime - 20 MB that nothing
# ever references, because libstoreservicescore imports no symbol from any of them.
#
# bionic satisfies DT_NEEDED by SONAME against libraries that are already loaded, and does not
# check who built them. So a few kB of generated stubs carrying the right SONAMEs keep the
# linker happy and make the 20 MB unreachable.
#
# The symbol lists are checked in as text and the C is generated here, so no binary artifact of
# Apple's ever enters the repository.
# --------------------------------------------------------------------------------------------

# Defined in stubs/AppleStubs.cmake, which app/src/test/cpp/ includes too - so the host tests
# build exactly the stubs that ship, rather than a copy of how they are built.
include("${CMAKE_CURRENT_SOURCE_DIR}/stubs/AppleStubs.cmake")

add_apple_stub(CoreFoundation)
add_apple_stub(mediaplatform)
