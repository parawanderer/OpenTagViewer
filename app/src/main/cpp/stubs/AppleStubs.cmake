# Builds the stand-ins for Apple's libCoreFoundation.so and libmediaplatform.so.
#
# Included by app/src/main/cpp/CMakeLists.txt, which puts them in the APK, and by
# app/src/test/cpp/CMakeLists.txt, which builds the same targets for the machine running the tests
# and checks what they export and return. One definition, so the tests cannot drift from what ships.
#
# Everything this needs is beside it; the including project only has to call add_apple_stub().

set(APPLE_STUBS_DIR "${CMAKE_CURRENT_LIST_DIR}")

function(add_apple_stub library)
    set(symbols "${APPLE_STUBS_DIR}/lib${library}.symbols")
    set(generated "${CMAKE_CURRENT_BINARY_DIR}/lib${library}_stub.c")

    # Optional: symbols ADI is known to call harmlessly, which log at INFO rather than raising
    # the alarm. A library without one treats every call as unexpected.
    set(expected "${APPLE_STUBS_DIR}/lib${library}.expected")

    # Optional: functions that cannot be generated, because they return a C++ object by value.
    # The generator skips whatever this file defines. See issue #232.
    set(handwritten "${APPLE_STUBS_DIR}/lib${library}_handwritten.cpp")

    set(inputs "${symbols}" "${APPLE_STUBS_DIR}/generate_stub.cmake")
    set(sources "${generated}")
    if (EXISTS "${expected}")
        list(APPEND inputs "${expected}")
    endif ()
    if (EXISTS "${handwritten}")
        list(APPEND inputs "${handwritten}")
        list(APPEND sources "${handwritten}")
    endif ()

    add_custom_command(
            OUTPUT "${generated}"
            COMMAND ${CMAKE_COMMAND}
            -DSYMBOLS=${symbols}
            -DEXPECTED=${expected}
            -DHANDWRITTEN=${handwritten}
            -DOUTPUT=${generated}
            -DLIBRARY=lib${library}.so
            -DSAFE=${library}
            -P "${APPLE_STUBS_DIR}/generate_stub.cmake"
            DEPENDS ${inputs}
            COMMENT "Generating lib${library}.so stub from lib${library}.symbols"
            VERBATIM)

    # The target name cannot collide with the output name, hence the _stub suffix here and
    # OUTPUT_NAME below. What ends up in the APK is lib<library>.so, which is what the linker
    # looks for.
    add_library(${library}_stub SHARED ${sources})
    set_target_properties(${library}_stub PROPERTIES
            OUTPUT_NAME "${library}"
            PREFIX "lib")
    if (ANDROID)
        target_link_libraries(${library}_stub log)
    endif ()
endfunction()
