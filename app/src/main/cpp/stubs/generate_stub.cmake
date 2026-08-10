# Turn a .symbols list into a .c file exporting exactly those names.
#
# Run with: cmake -DSYMBOLS=<in> -DOUTPUT=<out> -DLIBRARY=<name> -P generate_stub.cmake
#
# Each function logs its own name and returns zero. That matters more than it looks: it is how
# we find out whether ADI ever actually calls into CoreFoundation or mediaplatform. A stub that
# aborted would tell us only that something went wrong; one that logs and returns tells us
# precisely which symbols are reached, and lets the run continue to see what else is.
#
# Returning long rather than void is deliberate. The real signatures vary and we do not know
# them, but on both ABIs an integer return lands in the same register a pointer would, so
# callers expecting a pointer see NULL and callers expecting an int see 0 - the two harmless
# answers. Functions that return structs by value would not be handled correctly; if any turn
# out to be called, they need writing by hand.

if (NOT DEFINED SYMBOLS OR NOT DEFINED OUTPUT OR NOT DEFINED LIBRARY OR NOT DEFINED SAFE)
    message(FATAL_ERROR
            "generate_stub.cmake needs -DSYMBOLS, -DOUTPUT, -DLIBRARY and -DSAFE "
            "(SAFE is the library name without lib/.so, used to make the call-count "
            "accessor unique across stub libraries)")
endif ()

file(STRINGS "${SYMBOLS}" lines)

set(body
        "// Generated from ${LIBRARY}.symbols by generate_stub.cmake. Do not edit.\n"
        "//\n"
        "// This stands in for Apple's ${LIBRARY}, so that bionic can satisfy\n"
        "// libstoreservicescore.so's DT_NEEDED without downloading it and the 20 MB of ICU,\n"
        "// curl and libxml2 that sits behind it. The linker matches on SONAME and does not\n"
        "// care who built the file.\n"
        "//\n"
        "// The bet being made here is that ADI never calls into this library. That was true\n"
        "// when the symbol list was generated, and Apple can change it at any time without\n"
        "// telling anyone. So every stub reports itself, and the count is readable from Java\n"
        "// via ${SAFE}_adi_stub_calls() - a non-zero count after a real ADI operation means\n"
        "// the bet has been lost and the result cannot be trusted.\n"
        "\n"
        "#include <android/log.h>\n"
        "#include <stdatomic.h>\n"
        "\n"
        "static atomic_int calls = 0\;\n"
        "\n"
        "static void stub_called(const char *name) {\n"
        "    atomic_fetch_add(&calls, 1)\;\n"
        "    __android_log_print(ANDROID_LOG_ERROR, \"adi-stub\",\n"
        "        \"%s was called, but this app ships a do-nothing stub for it in place of \"\n"
        "        \"Apple's ${LIBRARY}, and it just returned 0.\\n\"\n"
        "        \"  What this means: Apple's ADI now depends on a function we assumed it \"\n"
        "        \"never called, so anything Anisette produces from here on may be silently \"\n"
        "        \"wrong rather than merely broken. Do not trust this session - fall back to \"\n"
        "        \"the remote Anisette server.\\n\"\n"
        "        \"  How to fix: implement this function for real in \"\n"
        "        \"app/src/main/cpp/stubs/, or stop stubbing ${LIBRARY} and download Apple's \"\n"
        "        \"copy instead. Run scripts/update_adi_stub_symbols.py --check to see \"\n"
        "        \"whether their libraries have changed.\", name)\;\n"
        "}\n"
        "\n"
        "// Deliberately not named the same in both stub libraries: they are loaded with\n"
        "// RTLD_GLOBAL, so a shared name would collide and one would silently shadow the other.\n"
        "int ${SAFE}_adi_stub_calls(void) { return atomic_load(&calls)\; }\n"
        "\n")

set(index 0)
set(functions 0)
set(objects 0)

foreach (line IN LISTS lines)
    # Skip comments and blanks.
    if (line MATCHES "^#" OR line STREQUAL "")
        continue()
    endif ()

    string(SUBSTRING "${line}" 0 1 kind)
    string(SUBSTRING "${line}" 2 -1 name)

    if (kind STREQUAL "F")
        # The asm label has to be on a declaration that precedes the definition.
        list(APPEND body
                "extern long stub_${index}(void) __asm__(\"${name}\")\;\n"
                "long stub_${index}(void) { stub_called(\"${name}\")\; return 0\; }\n")
        math(EXPR functions "${functions} + 1")
    else ()
        # Size is a guess - these are things like kCFTypeArrayCallBacks, which are read by
        # code we are also stubbing out. Generous and zeroed is the safe shape.
        list(APPEND body
                "extern char data_${index}[256] __asm__(\"${name}\")\;\n"
                "char data_${index}[256] = {0}\;\n")
        math(EXPR objects "${objects} + 1")
    endif ()

    math(EXPR index "${index} + 1")
endforeach ()

string(JOIN "" contents ${body})
file(WRITE "${OUTPUT}" "${contents}")

message(STATUS "${LIBRARY}: generated ${functions} function stubs and ${objects} data stubs")
