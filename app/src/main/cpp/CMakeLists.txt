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
        adi_probe.cpp)

# dlopen/dlsym are in libc on Android, so there is no libdl to link explicitly.
target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)
