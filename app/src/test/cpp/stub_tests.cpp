// What our stand-ins for Apple's libraries export, and what they hand back to Apple's code.
//
// Usage: stub_tests <lib.so> <lib.symbols> [<lib.so> <lib.symbols> ...]
//
// Each check is here because its failure is silent on most devices and fatal on some:
//
// - Every symbol in a .symbols list is exported. One that is not makes dlopen of
//   libstoreservicescore.so fail outright - everywhere, but only at runtime, and the build is
//   green. A hand-written stub whose return type had internal linkage did exactly this.
// - Every generated function stub returns 0. That is the contract generate_stub.cmake documents:
//   NULL to a caller expecting a pointer, 0 to one expecting an integer.
// - makeWorkQueue returns an empty shared_ptr through the caller's buffer. Issue #232: the
//   generated stub never wrote it, and Apple's constructor incremented through the leftover.

#include <dlfcn.h>

#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "stubs/stub_checks.h"

namespace {

int failures = 0;

void fail(const std::string &message) {
    std::fprintf(stderr, "FAIL: %s\n", message.c_str());
    ++failures;
}

struct Symbol {
    bool function;
    std::string name;
};

std::vector<Symbol> read_symbols(const std::string &path) {
    std::ifstream in(path);
    if (!in) {
        fail("cannot read " + path);
        return {};
    }
    std::vector<Symbol> out;
    std::string line;
    while (std::getline(in, line)) {
        if (line.size() < 3 || line[0] == '#') {
            continue;
        }
        out.push_back({line[0] == 'F', line.substr(2)});
    }
    return out;
}

void check_library(const std::string &library_path, const std::string &symbols_path) {
    void *library = dlopen(library_path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) {
        const char *error = dlerror();
        fail("dlopen(" + library_path + "): " + (error ? error : "unknown error"));
        return;
    }

    const std::vector<Symbol> symbols = read_symbols(symbols_path);
    if (symbols.empty()) {
        fail(symbols_path + " lists no symbols");
    }

    int functions = 0;
    int called = 0;
    for (const Symbol &symbol : symbols) {
        void *address = dlsym(library, symbol.name.c_str());
        if (address == nullptr) {
            fail(library_path + " does not export " + symbol.name +
                 ", so libstoreservicescore.so would fail to load against it");
            continue;
        }
        if (!symbol.function) {
            continue;
        }
        ++functions;

        // Hand-written stubs have their own contracts, checked below.
        if (symbol.name == opentagviewer::stubs::MAKE_WORK_QUEUE) {
            continue;
        }

        const auto stub = reinterpret_cast<long (*)()>(address);
        const long result = stub();
        ++called;
        if (result != 0) {
            fail(symbol.name + " returned " + std::to_string(result) + " rather than 0");
        }
    }

    if (dlsym(library, opentagviewer::stubs::MAKE_WORK_QUEUE) != nullptr) {
        const std::string problem = opentagviewer::stubs::check_make_work_queue(
                dlsym(library, opentagviewer::stubs::MAKE_WORK_QUEUE));
        if (!problem.empty()) {
            fail(problem);
        } else {
            std::printf("  makeWorkQueue returned an empty shared_ptr through the caller's buffer\n");
        }
    }

    std::printf("%s: %zu symbols exported, %d of %d functions called and returned 0\n",
                library_path.c_str(), symbols.size(), called, functions);
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 3 || argc % 2 != 1) {
        std::fprintf(stderr, "usage: %s <lib.so> <lib.symbols> [...]\n", argv[0]);
        return 2;
    }

    bool saw_make_work_queue = false;
    for (int i = 1; i + 1 < argc; i += 2) {
        for (const Symbol &symbol : read_symbols(argv[i + 1])) {
            saw_make_work_queue |= symbol.name == opentagviewer::stubs::MAKE_WORK_QUEUE;
        }
        check_library(argv[i], argv[i + 1]);
    }

    // Guards the check above against quietly having nothing to check, if the list or the name
    // ever changes.
    if (!saw_make_work_queue) {
        fail("no .symbols list names makeWorkQueue, so its check did not run");
    }

    if (failures != 0) {
        std::fprintf(stderr, "%d failure(s)\n", failures);
        return 1;
    }
    std::printf("all stub checks passed\n");
    return 0;
}
