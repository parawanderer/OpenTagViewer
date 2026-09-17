// Stubs for libmediaplatform.so that cannot be generated, because of what they return.
//
// generate_stub.cmake writes `long f(void) { return 0; }` for every symbol, which is right for a
// function returning a pointer or an integer: the answer lands in the return register. It is
// wrong for one returning a C++ object by value. Any symbol given a definition in this file is
// skipped by the generator - it reads the asm labels below - so there is one definition of each.
//
// See issue #232.

#include <android/log.h>

// **libc++'s std::shared_ptr, as far as its caller can tell: two pointers, returned through the
// caller's buffer.**
//
// A type with a non-trivial destructor is returned indirectly on both ABIs this app ships: the
// caller passes the address of a buffer (in x8 on arm64, as a hidden first argument on x86_64)
// and the callee writes the result into it. std::shared_ptr has one, so that is how
// libstoreservicescore.so calls makeWorkQueue.
//
// The generated stub returned 0 in a register and never wrote that buffer. A static constructor
// in libstoreservicescore.so (the one for storeservicescore::URLBagRequest::_queue) then read
// the "returned" control-block pointer out of it - whatever an earlier call had left on the
// stack - and incremented a reference count through it. Where that leftover was 0, the increment
// was skipped and nothing happened, which is every device it was tested on. On a Pixel 5 and a
// Xiaomi Redmi Note 11 Pro+ 5G it was a live, misaligned pointer, and dlopen died with SIGBUS.
//
// **The destructor below is the fix, not decoration.** Without it this struct is 16 trivially
// copyable bytes, which both ABIs return in two registers - leaving the caller's buffer exactly as
// unwritten as before.
//
// **Not in an anonymous namespace**, though nothing else uses it. A function whose return type has
// internal linkage gets internal linkage itself, so the symbol would not be exported and
// libstoreservicescore.so would fail to load everywhere instead of on two phones.
struct OpenTagViewerEmptySharedPtr {
    void *object = nullptr;
    void *control = nullptr;

    ~OpenTagViewerEmptySharedPtr() {}
};

// mediaplatform::WorkQueue::makeWorkQueue(std::string const&, WorkQueueType)
extern OpenTagViewerEmptySharedPtr make_work_queue(const void *name, int type) __asm__("_ZN13mediaplatform9WorkQueue13makeWorkQueueERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEENS0_13WorkQueueTypeE");

OpenTagViewerEmptySharedPtr make_work_queue(const void *, int) {
    __android_log_print(ANDROID_LOG_INFO, "adi-stub",
                        "mediaplatform::WorkQueue::makeWorkQueue was called and returned an empty "
                        "shared_ptr, which is expected here "
                        "(see app/src/main/cpp/stubs/libmediaplatform_handwritten.cpp)");
    return {};
}
