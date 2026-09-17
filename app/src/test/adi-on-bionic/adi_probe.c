// Loads Apple's ADI libraries the way the app does, with Android's own linker running every
// constructor, and initialises them. No provisioning, no network, no Apple account.
//
//   adi_probe <stubs dir> <apple libs dir> <provisioning dir>
//
// Exit codes: 0 loaded and initialised; 2 a dlopen failed; 3 an ADI entry point is not exported;
// 4-6 an initialisation call failed; 7 ADIGetLoginCode said something other than "not provisioned";
// 8 a generated stub was called. A crash is reported by the shell as a signal.
//
// The order and flags follow LocalAnisette.openAndInitialise and NativeAdi.open: the stubs as
// System.loadLibrary loads them, then libc++_shared.so and libstoreservicescore.so by absolute path
// with RTLD_NOW | RTLD_GLOBAL. libCoreADI.so is opened by ADI itself, from the path it is given.

#define _GNU_SOURCE
#include <dlfcn.h>
#include <link.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <ucontext.h>

#include "adi_functions.h"

#define ADI_FUNCTION_COUNT (sizeof ADI_FUNCTIONS / sizeof ADI_FUNCTIONS[0])

/// ADIGetLoginCode's answer for a machine that has never been provisioned. See AdiError.java.
#define NOT_PROVISIONED (-45061)

static void *open_or_exit(const char *dir, const char *name, int flags) {
    char path[4096];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    printf("dlopen(%s)\n", path);
    fflush(stdout);
    dlerror();
    void *handle = dlopen(path, flags);
    if (handle == NULL) {
        const char *error = dlerror();
        printf("FAIL dlopen(%s): %s\n", path, error ? error : "(no dlerror)");
        exit(2);
    }
    return handle;
}

static void *adi_function(void *library, const char *name) {
    for (size_t i = 0; i < ADI_FUNCTION_COUNT; i++) {
        if (strcmp(ADI_FUNCTIONS[i].name, name) == 0) {
            return dlsym(library, ADI_FUNCTIONS[i].symbol);
        }
    }
    return NULL;
}

// Which library, and at what offset, a crash happened in - so a failure names a place in Apple's
// code that can be looked up in a disassembly, rather than just a signal. Not async-signal-safe; it
// only has to work once, on the way down.
struct where {
    unsigned long pc;
    const char *name;
    unsigned long base;
};

static int find_library(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    struct where *w = data;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
        unsigned long start = info->dlpi_addr + ph->p_vaddr;
        if (ph->p_type == PT_LOAD && w->pc >= start && w->pc < start + ph->p_memsz) {
            w->name = info->dlpi_name;
            w->base = info->dlpi_addr;
            return 1;
        }
    }
    return 0;
}

static void on_fault(int sig, siginfo_t *info, void *context) {
    ucontext_t *uc = context;
#if defined(__aarch64__)
    unsigned long pc = uc->uc_mcontext.pc, lr = uc->uc_mcontext.regs[30];
#elif defined(__x86_64__)
    unsigned long pc = uc->uc_mcontext.gregs[REG_RIP], lr = 0;
#else
    unsigned long pc = 0, lr = 0;
#endif
    struct where at = {pc, "?", 0};
    dl_iterate_phdr(find_library, &at);
    printf("CRASH signal %d code %d fault addr %p\n  pc %#lx = %s+%#lx\n", sig, info->si_code,
           info->si_addr, pc, at.name, pc - at.base);
    if (lr != 0) {
        struct where from = {lr, "?", 0};
        dl_iterate_phdr(find_library, &from);
        printf("  lr %#lx = %s+%#lx\n", lr, from.name, lr - from.base);
    }
    fflush(stdout);
    signal(sig, SIG_DFL);
    raise(sig);
}

static int stub_calls(void *library, const char *accessor) {
    int (*count)(void) = (int (*)(void)) dlsym(library, accessor);
    return count ? count() : -1;
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "usage: %s <stubs dir> <apple libs dir> <provisioning dir>\n", argv[0]);
        return 64;
    }
    const char *stubs = argv[1], *apple = argv[2], *provisioning = argv[3];

    struct sigaction action = {0};
    action.sa_sigaction = on_fault;
    action.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigaction(SIGSEGV, &action, NULL);
    sigaction(SIGBUS, &action, NULL);

    void *core_foundation = open_or_exit(stubs, "libCoreFoundation.so", RTLD_NOW);
    void *media_platform = open_or_exit(stubs, "libmediaplatform.so", RTLD_NOW);
    open_or_exit(apple, "libc++_shared.so", RTLD_NOW | RTLD_GLOBAL);
    // Issue #232 crashed here, inside this call, in libstoreservicescore.so's static constructors.
    void *store = open_or_exit(apple, "libstoreservicescore.so", RTLD_NOW | RTLD_GLOBAL);
    printf("libstoreservicescore.so loaded; its static constructors returned\n");

    for (size_t i = 0; i < ADI_FUNCTION_COUNT; i++) {
        if (dlsym(store, ADI_FUNCTIONS[i].symbol) == NULL) {
            printf("FAIL %s (%s) is not exported - see AdiFunction.java\n", ADI_FUNCTIONS[i].name,
                   ADI_FUNCTIONS[i].symbol);
            return 3;
        }
    }
    printf("all %zu ADI entry points resolved\n", ADI_FUNCTION_COUNT);
    fflush(stdout);

    int (*load_library_with_path)(const char *) = adi_function(store, "ADILoadLibraryWithPath");
    int (*set_provisioning_path)(const char *) = adi_function(store, "ADISetProvisioningPath");
    int (*set_android_id)(const char *, unsigned int) = adi_function(store, "ADISetAndroidID");
    int (*get_login_code)(unsigned long long) = adi_function(store, "ADIGetLoginCode");

    int rc = load_library_with_path(apple);
    printf("ADILoadLibraryWithPath = %d\n", rc);
    if (rc != 0) return 4;

    mkdir(provisioning, 0700);
    rc = set_provisioning_path(provisioning);
    printf("ADISetProvisioningPath = %d\n", rc);
    if (rc != 0) return 5;

    // A throwaway identifier, in the shape ADI accepts: 16 lowercase hex characters.
    rc = set_android_id("0000000000000000", 16);
    printf("ADISetAndroidID = %d\n", rc);
    if (rc != 0) return 6;

    rc = get_login_code((unsigned long long) -2LL);
    printf("ADIGetLoginCode(-2) = %d%s\n", rc, rc == NOT_PROVISIONED ? " (not provisioned)" : "");

    // The app's own tripwire (generate_stub.cmake): a generated stub being called at all means ADI
    // depends on something we only pretend to provide. It is also the only thing that fails for the
    // pre-#232 makeWorkQueue where the leftover stack happens to be zero - x86_64 on Android 14 bionic.
    const int cf = stub_calls(core_foundation, "CoreFoundation_adi_stub_calls");
    const int mp = stub_calls(media_platform, "mediaplatform_adi_stub_calls");
    printf("generated stubs called: CoreFoundation=%d mediaplatform=%d\n", cf, mp);
    fflush(stdout);
    if (cf != 0 || mp != 0) {
        printf("FAIL a generated stub was called\n");
        return 8;
    }
    return rc == NOT_PROVISIONED ? 0 : 7;
}
