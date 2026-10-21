// Apple's macOS CoreADI, x86_64, run under Unicorn - plus the few pieces of macOS userland it
// asks for.
//
// The dylib's preferred base is 0, so every segment is mapped at its literal vmaddr and nothing
// is rebased. Each imported function gets a stub address holding a single `ret`; a code hook on
// the stub page runs the handler in C++, sets rax, and the `ret` returns to the caller. Imported
// data symbols point at per-symbol cells.
//
// Ported from sacca97/os-findmy-e (MIT), native/adi-core/src/vm.rs, which in turn mirrors the
// Python harness its results were measured with. Behaviour is kept identical on purpose, down to
// the deterministic arc4random: that harness is what Apple has been seen to accept, and a
// "tidier" stand-in is an untested one.

#pragma once

#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

#include <unicorn/unicorn.h>

namespace macadi {

// Where the library keeps its provisioning state. A virtual directory: the files live in memory
// here, and Java persists them between sessions.
extern const char *const STORE_DIR;

class Vm {
public:
    explicit Vm(const std::vector<uint8_t> &image);
    ~Vm();
    Vm(const Vm &) = delete;
    Vm &operator=(const Vm &) = delete;

    // Run address(args...) until it returns to our sentinel; the result is rax.
    uint64_t invoke(uint64_t address, const std::vector<uint64_t> &args);

    // Back to the state right after loading, as if a fresh process had started. The store, the
    // host UUID and the clock are kept; library memory, descriptors and allocator counters are
    // not. Translated code stays cached, which is what makes this cheap. Between sessions only -
    // a provisioning session id lives in library memory.
    void resetSession();

    // Per-call scratch for request buffers and out-cells, reset between top-level calls.
    uint64_t scratch(size_t size);
    void scratchReset();

    void write(uint64_t addr, const std::vector<uint8_t> &data);
    void writeU32(uint64_t addr, uint32_t v);
    void writeU64(uint64_t addr, uint64_t v);
    std::vector<uint8_t> read(uint64_t addr, size_t len) const;
    uint32_t readU32(uint64_t addr) const;
    uint64_t readU64(uint64_t addr) const;

    std::map<std::string, std::vector<uint8_t>> &files() { return files_; }

    void setHostUuid(const uint8_t uuid[16]);

    // Pin gettimeofday to `secs` for tests; negative is the real clock.
    //
    // Never in the app. A frozen clock makes the OTP a constant, and Apple then accepts the
    // machine for about a minute and refuses it after (os-findmy-e EVIDENCE.md sections 8-9).
    void setFrozenTime(int64_t secs) { frozenTime_ = secs; }

private:
    enum class Kind {
        Abort, Ret, Arc4Buf, HostUuid, TimeOfDay, Errno, ObjcMsgSend, Malloc, Open, Close, Read,
        Write, Lstat, Fstat, Statfs, Mkdir, Unlink, Chmod, Ftruncate, Getenv, CfurlCreate,
        CfurlCopyProp,
    };
    struct Handler {
        Kind kind;
        uint64_t value;
    };
    struct Fd {
        std::string path;
        size_t off;
    };
    struct Snapshot {
        std::vector<std::pair<uint64_t, std::vector<uint8_t>>> segments;
        std::vector<uint8_t> cells;
        uint64_t heapCur;
        uint64_t objCur;
        uint64_t nextUrl;
        uint64_t nextFd;
    };

    static bool handlerFor(const std::string &name, Handler &out);
    static void onStub(uc_engine *uc, uint64_t address, uint32_t size, void *self);
    static bool onUnmapped(uc_engine *uc, uc_mem_type type, uint64_t address, int size,
                           int64_t value, void *self);

    // Runs the stand-in for one imported function. Returns false when it stopped the emulation
    // instead of returning a value.
    bool call(const Handler &h, uint64_t &result);

    uint64_t arg(int i) const;
    std::string cstr(uint64_t addr) const;
    uint64_t fail(uint64_t err);
    void poke32(uint64_t addr, uint32_t v);
    void poke64(uint64_t addr, uint64_t v);
    void fillStat(uint64_t addr, const std::string &path, bool isDir, int64_t size);
    uint64_t bumpScratch(uint64_t size);
    void check(uc_err err, const char *what) const;

    uc_engine *uc_ = nullptr;
    uc_hook stubHook_ = 0;
    uc_hook unmappedHook_ = 0;

    std::unordered_map<uint64_t, Handler> handlers_;
    std::unordered_map<std::string, uint64_t> objectOf_;
    std::unordered_map<uint64_t, std::string> keyByObject_;
    uint64_t heapCur_ = 0;
    uint64_t scratchCur_ = 0;
    uint64_t scratchBase_ = 0;
    uint64_t objCur_ = 0;
    uint64_t nextUrl_ = 0;
    uint64_t errnoAddr_ = 0;
    std::map<std::string, uint64_t> getenvAddr_;
    std::map<std::string, std::vector<uint8_t>> files_;
    std::set<std::string> dirs_;
    std::map<uint64_t, Fd> fds_;
    uint64_t nextFd_ = 3;
    std::map<uint64_t, std::string> urls_;
    uint8_t hostUuid_[16];
    int64_t frozenTime_ = -1;
    std::string fault_;

    Snapshot snapshot_;
    std::vector<uint8_t> zeros_;
};

}  // namespace macadi
