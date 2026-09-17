// Checks on our stand-ins for Apple's libraries, shared by the app and the host tests.
//
// adi.cpp runs these on a device (MakeWorkQueueStubTest); app/src/test/cpp/ runs them on ordinary
// x86_64 and arm64 Linux in CI. One implementation, so the two cannot check different things.

#pragma once

#include <cstdio>
#include <cstring>
#include <memory>
#include <new>
#include <string>

namespace opentagviewer::stubs {

inline constexpr const char *MAKE_WORK_QUEUE =
        "_ZN13mediaplatform9WorkQueue13makeWorkQueueERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEENS0_13WorkQueueTypeE";

/// Calls a makeWorkQueue implementation exactly as libstoreservicescore.so does, and says whether
/// it left the caller's buffer holding a real answer. Empty when it did.
///
/// Issue #232: the generated stub returned 0 in a register, but the real function returns a
/// std::shared_ptr by value - through a buffer the caller provides - and a static constructor in
/// Apple's library read that buffer back as a control block. Leftover stack there crashed dlopen
/// on some phones and not others. The buffer is filled with garbage first, so a stub that does not
/// write it fails here on every machine and ABI rather than on unlucky ones.
///
/// Declared with the real return type, std::shared_ptr, so the call is made the way Apple's code
/// makes it rather than the way our stub's source happens to describe itself.
inline std::string check_make_work_queue(void *symbol) {
    if (symbol == nullptr) {
        return "makeWorkQueue is not exported";
    }

    using MakeWorkQueue = std::shared_ptr<int> (*)(const std::string &, int);
    const auto make_work_queue = reinterpret_cast<MakeWorkQueue>(symbol);

    // The object is constructed in place (guaranteed elision since C++17), so this buffer is the
    // one the stub is handed to write into.
    unsigned char storage[sizeof(std::shared_ptr<int>)];
    std::memset(storage, 0xA5, sizeof storage);

    const std::string name = "URLBagRequest";
    ::new (static_cast<void *>(storage)) std::shared_ptr<int>(make_work_queue(name, 0));

    void *words[2] = {};
    std::memcpy(words, storage, sizeof words);
    // Not destroyed: a stub that failed left garbage there, and releasing it is what crashed.

    if (words[0] == nullptr && words[1] == nullptr) {
        return {};
    }
    char message[160];
    std::snprintf(message, sizeof message,
                  "makeWorkQueue left {object=%p, control=%p} in the caller's buffer", words[0],
                  words[1]);
    return message;
}

}  // namespace opentagviewer::stubs
