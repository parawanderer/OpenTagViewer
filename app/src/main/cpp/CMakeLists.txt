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

function(add_apple_stub library)
    set(symbols "${CMAKE_CURRENT_SOURCE_DIR}/stubs/lib${library}.symbols")
    set(generated "${CMAKE_CURRENT_BINARY_DIR}/lib${library}_stub.c")

    # Optional: symbols ADI is known to call harmlessly, which log at INFO rather than raising
    # the alarm. A library without one treats every call as unexpected.
    set(expected "${CMAKE_CURRENT_SOURCE_DIR}/stubs/lib${library}.expected")

    set(inputs "${symbols}" "${CMAKE_CURRENT_SOURCE_DIR}/stubs/generate_stub.cmake")
    if (EXISTS "${expected}")
        list(APPEND inputs "${expected}")
    endif ()

    add_custom_command(
            OUTPUT "${generated}"
            COMMAND ${CMAKE_COMMAND}
            -DSYMBOLS=${symbols}
            -DEXPECTED=${expected}
            -DOUTPUT=${generated}
            -DLIBRARY=lib${library}.so
            -DSAFE=${library}
            -P "${CMAKE_CURRENT_SOURCE_DIR}/stubs/generate_stub.cmake"
            DEPENDS ${inputs}
            COMMENT "Generating lib${library}.so stub from lib${library}.symbols"
            VERBATIM)

    # The target name cannot collide with the output name, hence the _stub suffix here and
    # OUTPUT_NAME below. What ends up in the APK is lib<library>.so, which is what the linker
    # looks for.
    add_library(${library}_stub SHARED "${generated}")
    set_target_properties(${library}_stub PROPERTIES
            OUTPUT_NAME "${library}"
            PREFIX "lib")
    target_link_libraries(${library}_stub log)
endfunction()

add_apple_stub(CoreFoundation)
add_apple_stub(mediaplatform)
