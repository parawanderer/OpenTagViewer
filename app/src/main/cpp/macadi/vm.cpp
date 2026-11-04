#include "vm.h"

#include <algorithm>
#include <cstring>
#include <ctime>

#include "error.h"
#include "macho.h"

namespace macadi {

const char *const STORE_DIR = "/Users/Shared/adi";

namespace {

constexpr uint64_t PAGE = 0x1000;
constexpr uint64_t RETURN_ADDRESS = 0x40000000;
constexpr uint64_t HEAP_ADDRESS = 0x40010000;
constexpr uint64_t HEAP_SIZE = 0x01000000;
constexpr uint64_t STACK_ADDRESS = 0x50000000;
constexpr uint64_t STACK_SIZE = 0x00200000;
constexpr uint64_t STUB_ADDRESS = 0x60000000;
constexpr uint64_t STUB_SIZE = 0x20000;
constexpr uint64_t DATA_ADDRESS = 0x60020000;
constexpr uint64_t DATA_SIZE = 0x20000;
constexpr uint64_t SCRATCH_ADDRESS = 0x60040000;
constexpr uint64_t SCRATCH_SIZE = 0x40000;
constexpr uint64_t OBJECT_ADDRESS = 0x60080000;
constexpr uint64_t OBJECT_SIZE = 0x40000;
constexpr uint64_t SLOT = 0x10;

constexpr size_t STAT_SIZE = 144;
constexpr size_t STATFS_SIZE = 2168;
constexpr uint16_t S_IFDIR_ = 0040000;
constexpr uint16_t S_IFREG_ = 0100000;
constexpr uint64_t O_CREAT_ = 0x0200;  // Darwin's values, not Linux's
constexpr uint64_t O_TRUNC_ = 0x0400;
constexpr uint64_t ENOENT_ = 2;
constexpr uint64_t EBADF_ = 9;
constexpr uint64_t EEXIST_ = 17;
constexpr uint64_t EISDIR_ = 21;
constexpr uint64_t FAILED = ~static_cast<uint64_t>(0);

const char *const DATA_SYMBOLS[] = {
        "stack_chk_guard",       "kCFAllocatorDefault",    "kCFBooleanFalse",
        "kCFBooleanTrue",        "kCFErrorDomainOSStatus", "kCFURLFileSecurityKey",
        "kCFURLIsDirectoryKey",  "kCFURLIsHiddenKey",      "kCFURLTypeIdentifierKey",
        "kUTTypePlainText",
};

const int ARG_REGS[] = {UC_X86_REG_RDI, UC_X86_REG_RSI, UC_X86_REG_RDX,
                        UC_X86_REG_RCX, UC_X86_REG_R8,  UC_X86_REG_R9};

std::string norm(const std::string &s) {
    const size_t i = s.find_first_not_of('_');
    return i == std::string::npos ? std::string() : s.substr(i);
}

bool isDataSymbol(const std::string &normalised) {
    for (const char *d : DATA_SYMBOLS) {
        if (normalised == d) return true;
    }
    return false;
}

// CRC-32, as the Python harness does (zlib.crc32), so inodes are stable per path.
uint64_t inode(const std::string &path) {
    uint32_t crc = ~0u;
    for (const unsigned char b : path) {
        crc ^= b;
        for (int i = 0; i < 8; i++) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
    }
    return 0x10000 + ((~crc) & 0xFFFFF);
}

void put16(uint8_t *b, uint16_t v) { std::memcpy(b, &v, 2); }
void put32(uint8_t *b, uint32_t v) { std::memcpy(b, &v, 4); }
void put64(uint8_t *b, uint64_t v) { std::memcpy(b, &v, 8); }

}  // namespace

bool Vm::handlerFor(const std::string &n, Handler &out) {
    auto set = [&](Kind k, uint64_t v = 0) {
        out = Handler{k, v};
        return true;
    };
    if (n == "stack_chk_fail") return set(Kind::Abort);
    if (n == "pthread_rwlock_init" || n == "pthread_rwlock_rdlock" ||
        n == "pthread_rwlock_wrlock" || n == "pthread_rwlock_unlock" ||
        n == "pthread_rwlock_destroy")
        return set(Kind::Ret, 0);
    if (n == "getuid") return set(Kind::Ret, 501);
    if (n == "umask") return set(Kind::Ret, 0022);
    if (n == "arc4random") return set(Kind::Ret, 0x12345678);
    if (n == "arc4random_buf") return set(Kind::Arc4Buf);
    if (n == "gethostuuid") return set(Kind::HostUuid);
    if (n == "gettimeofday") return set(Kind::TimeOfDay);
    if (n == "error") return set(Kind::Errno);  // ___error, i.e. errno's address
    if (n == "objc_msgSend") return set(Kind::ObjcMsgSend);
    if (n == "NSVersionOfRunTimeLibrary") return set(Kind::Ret, 0x100000000ull);
    if (n == "CFErrorGetCode" || n == "CFErrorGetDomain" || n == "CFRelease" ||
        n == "CSBackupIsItemExcluded" || n == "notify_post")
        return set(Kind::Ret, 0);
    if (n == "CSBackupSetItemExcluded" || n == "CFFileSecuritySetMode" ||
        n == "CFURLSetResourcePropertyForKey")
        return set(Kind::Ret, 1);
    if (n == "malloc") return set(Kind::Malloc);
    if (n == "free" || n == "fchmod" || n == "fcntl" || n == "lockf") return set(Kind::Ret, 0);
    if (n == "open") return set(Kind::Open);
    if (n == "close") return set(Kind::Close);
    if (n == "read") return set(Kind::Read);
    if (n == "write") return set(Kind::Write);
    if (n == "lstat$INODE64" || n == "stat$INODE64") return set(Kind::Lstat);
    if (n == "fstat$INODE64") return set(Kind::Fstat);
    if (n == "statfs$INODE64") return set(Kind::Statfs);
    if (n == "mkdir") return set(Kind::Mkdir);
    if (n == "unlink") return set(Kind::Unlink);
    if (n == "chmod") return set(Kind::Chmod);
    if (n == "ftruncate") return set(Kind::Ftruncate);
    if (n == "getenv") return set(Kind::Getenv);
    if (n == "CFURLCreateFromFileSystemRepresentation") return set(Kind::CfurlCreate);
    if (n == "CFURLCopyResourcePropertyForKey") return set(Kind::CfurlCopyProp);
    return false;
}

void Vm::check(uc_err err, const char *what) const {
    if (err != UC_ERR_OK) {
        throw Error(std::string("unicorn: ") + what + ": " + uc_strerror(err));
    }
}

Vm::Vm(const std::vector<uint8_t> &image) {
    const MachO macho = MachO::parse(image);
    check(uc_open(UC_ARCH_X86, UC_MODE_64, &uc_), "open");

    try {
        const std::pair<uint64_t, uint64_t> regions[] = {
                {RETURN_ADDRESS, PAGE},       {HEAP_ADDRESS, HEAP_SIZE},
                {STACK_ADDRESS, STACK_SIZE},  {STUB_ADDRESS, STUB_SIZE},
                {DATA_ADDRESS, DATA_SIZE},    {SCRATCH_ADDRESS, SCRATCH_SIZE},
                {OBJECT_ADDRESS, OBJECT_SIZE},
        };
        for (const auto &r : regions) check(uc_mem_map(uc_, r.first, r.second, UC_PROT_ALL), "map");

        for (const Segment &seg : macho.segments()) {
            const uint64_t size = (seg.vmsize + PAGE - 1) / PAGE * PAGE;
            if (size == 0) continue;
            check(uc_mem_map(uc_, seg.vmaddr, size, UC_PROT_ALL), "map segment");
            if (seg.filesize != 0) {
                if (seg.fileoff + seg.filesize > image.size()) {
                    throw Error("mach-o: segment outside the file");
                }
                check(uc_mem_write(uc_, seg.vmaddr, image.data() + seg.fileoff, seg.filesize),
                      "load segment");
            }
        }
        const std::vector<uint8_t> rets(STUB_SIZE, 0xC3);
        check(uc_mem_write(uc_, STUB_ADDRESS, rets.data(), rets.size()), "write stubs");

        std::unordered_map<std::string, uint64_t> stubAddr;
        std::unordered_map<std::string, uint64_t> dataCell;
        const std::vector<std::string> symbols = macho.bindSymbols();
        for (size_t i = 0; i < symbols.size(); i++) {
            const std::string &sym = symbols[i];
            const std::string n = norm(sym);
            if (isDataSymbol(n)) {
                const uint64_t cell = DATA_ADDRESS + i * SLOT;
                const uint64_t obj = OBJECT_ADDRESS + i * SLOT;
                writeU64(cell, obj);
                dataCell[sym] = cell;
                objectOf_[n] = obj;
                keyByObject_[obj] = sym;
            } else {
                const uint64_t addr = STUB_ADDRESS + i * SLOT;
                stubAddr[sym] = addr;
                Handler h{};
                if (handlerFor(n, h)) handlers_[addr] = h;
            }
        }
        for (const Bind &b : macho.binds()) {
            uint64_t target;
            if (auto it = dataCell.find(b.symbol); it != dataCell.end()) {
                target = it->second;
            } else if (auto st = stubAddr.find(b.symbol); st != stubAddr.end()) {
                target = st->second;
            } else {
                throw Error("mach-o: unbound symbol " + b.symbol);
            }
            writeU64(b.address, target + static_cast<uint64_t>(b.addend));
        }

        heapCur_ = HEAP_ADDRESS;
        scratchCur_ = SCRATCH_ADDRESS;
        objCur_ = OBJECT_ADDRESS;
        nextUrl_ = OBJECT_ADDRESS;
        nextFd_ = 3;
        const uint8_t defaultUuid[16] = {0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                                         0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF};
        std::memcpy(hostUuid_, defaultUuid, 16);
        dirs_ = {"/", "/Users", "/Users/Shared", STORE_DIR};

        // Long-lived cells (errno, getenv strings) live below the per-call scratch base.
        errnoAddr_ = bumpScratch(8);
        const std::pair<const char *, const char *> env[] = {
                {"HOME", "/Users/aditest"}, {"USER", "aditest"}, {"TMPDIR", "/tmp/"}};
        std::vector<std::pair<uint64_t, std::string>> cells;
        for (const auto &kv : env) {
            const std::string v = kv.second;
            const uint64_t a = bumpScratch(v.size() + 1);
            getenvAddr_[kv.first] = a;
            cells.emplace_back(a, v);
        }
        scratchBase_ = scratchCur_;
        for (const auto &c : cells) {
            std::vector<uint8_t> bytes(c.second.begin(), c.second.end());
            bytes.push_back(0);
            write(c.first, bytes);
        }

        check(uc_hook_add(uc_, &stubHook_, UC_HOOK_CODE, reinterpret_cast<void *>(&Vm::onStub),
                          this, STUB_ADDRESS, STUB_ADDRESS + STUB_SIZE - 1),
              "hook stubs");
        check(uc_hook_add(uc_, &unmappedHook_, UC_HOOK_MEM_UNMAPPED,
                          reinterpret_cast<void *>(&Vm::onUnmapped), this, 1, 0),
              "hook unmapped");

        for (const Segment &seg : macho.segments()) {
            if (seg.writable && seg.vmsize != 0) {
                snapshot_.segments.emplace_back(seg.vmaddr, read(seg.vmaddr, seg.vmsize));
            }
        }
        snapshot_.cells = read(DATA_ADDRESS, PAGE);
        snapshot_.heapCur = heapCur_;
        snapshot_.objCur = objCur_;
        snapshot_.nextUrl = nextUrl_;
        snapshot_.nextFd = nextFd_;
        zeros_.assign(STACK_SIZE, 0);
    } catch (...) {
        uc_close(uc_);
        uc_ = nullptr;
        throw;
    }
}

Vm::~Vm() {
    if (uc_ != nullptr) uc_close(uc_);
}

void Vm::onStub(uc_engine *uc, uint64_t address, uint32_t, void *self) {
    Vm *vm = static_cast<Vm *>(self);
    const auto it = vm->handlers_.find(address);
    if (it == vm->handlers_.end()) return;  // an import nothing needed answering: just `ret`
    // Nothing may throw past this frame: it is called from inside Unicorn's C code.
    try {
        uint64_t result = 0;
        if (vm->call(it->second, result)) {
            uc_reg_write(uc, UC_X86_REG_RAX, &result);
        }
    } catch (...) {
        vm->fault_ = "a stand-in for an imported function failed";
        uc_emu_stop(uc);
    }
}

bool Vm::onUnmapped(uc_engine *uc, uc_mem_type type, uint64_t address, int size, int64_t,
                    void *self) {
    Vm *vm = static_cast<Vm *>(self);
    uint64_t rip = 0;
    uc_reg_read(uc, UC_X86_REG_RIP, &rip);
    const char *kind = type == UC_MEM_READ_UNMAPPED    ? "read"
                       : type == UC_MEM_WRITE_UNMAPPED ? "write"
                                                       : "fetch";
    char message[160];
    std::snprintf(message, sizeof message, "%s-unmapped at %#llx (size %d) rip=%#llx", kind,
                  static_cast<unsigned long long>(address), size,
                  static_cast<unsigned long long>(rip));
    vm->fault_ = message;
    return false;
}

uint64_t Vm::arg(int i) const {
    uint64_t v = 0;
    uc_reg_read(uc_, ARG_REGS[i], &v);
    return v;
}

std::string Vm::cstr(uint64_t addr) const {
    std::string out;
    while (out.size() < 4096) {
        uint8_t b = 0;
        if (uc_mem_read(uc_, addr + out.size(), &b, 1) != UC_ERR_OK || b == 0) break;
        out.push_back(static_cast<char>(b));
    }
    return out;
}

uint64_t Vm::fail(uint64_t err) {
    poke32(errnoAddr_, static_cast<uint32_t>(err));
    return FAILED;
}

// Writes made on the library's behalf from inside a hook. Failure is ignored, as in the reference:
// the library supplied the address, and an unmapped one faults on its own at the next access.
void Vm::poke32(uint64_t addr, uint32_t v) { uc_mem_write(uc_, addr, &v, 4); }

void Vm::poke64(uint64_t addr, uint64_t v) { uc_mem_write(uc_, addr, &v, 8); }

void Vm::fillStat(uint64_t addr, const std::string &path, bool isDir, int64_t size) {
    uint8_t b[STAT_SIZE] = {};
    put32(b + 0, 0x1000003);
    put16(b + 4, static_cast<uint16_t>((isDir ? S_IFDIR_ : S_IFREG_) | 0755));
    put16(b + 6, isDir ? 2 : 1);
    put64(b + 8, inode(path));
    put32(b + 16, 501);
    put32(b + 20, 20);
    put64(b + 96, static_cast<uint64_t>(size));
    put64(b + 104, static_cast<uint64_t>((size + 511) / 512));
    put32(b + 112, 4096);
    uc_mem_write(uc_, addr, b, sizeof b);
}

uint64_t Vm::bumpScratch(uint64_t size) {
    const uint64_t addr = scratchCur_;
    scratchCur_ += (size + 0xF) & ~static_cast<uint64_t>(0xF);
    if (scratchCur_ > SCRATCH_ADDRESS + SCRATCH_SIZE) throw Error("emulation: scratch exhausted");
    return addr;
}

bool Vm::call(const Handler &h, uint64_t &r) {
    switch (h.kind) {
        case Kind::Abort:
            fault_ = "stack check failed";
            uc_emu_stop(uc_);
            return false;
        case Kind::Ret:
            r = h.value;
            return true;
        case Kind::Arc4Buf: {
            const uint64_t buf = arg(0);
            const size_t n = static_cast<size_t>(arg(1));
            std::vector<uint8_t> bytes(n);
            for (size_t i = 0; i < n; i++) bytes[i] = static_cast<uint8_t>(i * 7 + 3);
            uc_mem_write(uc_, buf, bytes.data(), bytes.size());
            r = 0;
            return true;
        }
        case Kind::HostUuid:
            uc_mem_write(uc_, arg(0), hostUuid_, 16);
            r = 0;
            return true;
        case Kind::TimeOfDay: {
            const uint64_t tv = arg(0);
            if (tv != 0) {
                uint64_t s, us;
                if (frozenTime_ >= 0) {
                    s = static_cast<uint64_t>(frozenTime_);
                    us = 0;
                } else {
                    timespec now{};
                    clock_gettime(CLOCK_REALTIME, &now);
                    s = static_cast<uint64_t>(now.tv_sec);
                    us = static_cast<uint64_t>(now.tv_nsec / 1000);
                }
                poke64(tv, s);
                poke64(tv + 8, us);
            }
            r = 0;
            return true;
        }
        case Kind::Errno:
            r = errnoAddr_;
            return true;
        case Kind::ObjcMsgSend: {
            const std::string sel = cstr(arg(1));
            if (sel == "processInfo") {
                r = objCur_;
                objCur_ += 0x100;
            } else if (sel == "operatingSystem") {
                r = 0x0D;
            } else if (sel == "isOperatingSystemAtLeastVersion:") {
                r = 1;
            } else {
                r = 0;
            }
            return true;
        }
        case Kind::Malloc: {
            // A bump allocator, like the harness Apple has accepted. Nothing is freed: the VM is
            // put back to its post-load snapshot between sessions instead.
            const uint64_t size = (std::max<uint64_t>(arg(0), 0x20) + 0xF) & ~static_cast<uint64_t>(0xF);
            const uint64_t addr = heapCur_;
            if (addr + size > HEAP_ADDRESS + HEAP_SIZE) {
                fault_ = "emulated heap exhausted";
                uc_emu_stop(uc_);
                return false;
            }
            heapCur_ = addr + size;
            r = addr;
            return true;
        }
        case Kind::Open: {
            std::string path = cstr(arg(0));
            const uint64_t flags = arg(1);
            if (auto it = files_.find(path); it != files_.end()) {
                if (flags & O_TRUNC_) it->second.clear();
            } else if (flags & O_CREAT_) {
                files_[path] = {};
            } else if (dirs_.count(path) != 0) {
                r = fail(EISDIR_);
                return true;
            } else {
                r = fail(ENOENT_);
                return true;
            }
            const uint64_t fd = nextFd_++;
            fds_[fd] = Fd{std::move(path), 0};
            r = fd;
            return true;
        }
        case Kind::Close:
            fds_.erase(arg(0));
            r = 0;
            return true;
        case Kind::Read: {
            const uint64_t fd = arg(0), buf = arg(1);
            const size_t n = static_cast<size_t>(arg(2));
            auto it = fds_.find(fd);
            if (it == fds_.end()) {
                r = fail(EBADF_);
                return true;
            }
            const auto f = files_.find(it->second.path);
            static const std::vector<uint8_t> empty;
            const std::vector<uint8_t> &data = f == files_.end() ? empty : f->second;
            const size_t start = std::min(it->second.off, data.size());
            const size_t len = std::min(n, data.size() - start);
            if (len != 0) uc_mem_write(uc_, buf, data.data() + start, len);
            it->second.off += len;
            r = len;
            return true;
        }
        case Kind::Write: {
            const uint64_t fd = arg(0), buf = arg(1);
            const size_t n = static_cast<size_t>(arg(2));
            auto it = fds_.find(fd);
            if (it == fds_.end()) {
                r = fail(EBADF_);
                return true;
            }
            std::vector<uint8_t> payload(n);
            if (n != 0 && uc_mem_read(uc_, buf, payload.data(), n) != UC_ERR_OK) payload.clear();
            std::vector<uint8_t> &data = files_[it->second.path];
            const size_t off = it->second.off;
            if (off > data.size()) data.resize(off, 0);
            const size_t end = off + payload.size();
            if (end > data.size()) data.resize(end, 0);
            std::copy(payload.begin(), payload.end(), data.begin() + static_cast<long>(off));
            it->second.off = end;
            r = n;
            return true;
        }
        case Kind::Lstat: {
            const std::string path = cstr(arg(0));
            const uint64_t buf = arg(1);
            if (auto f = files_.find(path); f != files_.end()) {
                fillStat(buf, path, false, static_cast<int64_t>(f->second.size()));
                r = 0;
            } else if (dirs_.count(path) != 0) {
                fillStat(buf, path, true, 0);
                r = 0;
            } else {
                r = fail(ENOENT_);
            }
            return true;
        }
        case Kind::Fstat: {
            const uint64_t fd = arg(0), buf = arg(1);
            auto it = fds_.find(fd);
            if (it == fds_.end()) {
                r = fail(EBADF_);
                return true;
            }
            const auto f = files_.find(it->second.path);
            const int64_t size = f == files_.end() ? 0 : static_cast<int64_t>(f->second.size());
            const std::string path = it->second.path;
            fillStat(buf, path, false, size);
            r = 0;
            return true;
        }
        case Kind::Statfs: {
            std::vector<uint8_t> raw(STATFS_SIZE, 0);
            put32(raw.data(), 0x1000003);
            uc_mem_write(uc_, arg(1), raw.data(), raw.size());
            r = 0;
            return true;
        }
        case Kind::Mkdir: {
            std::string path = cstr(arg(0));
            if (files_.count(path) != 0 || dirs_.count(path) != 0) {
                r = fail(EEXIST_);
                return true;
            }
            dirs_.insert(std::move(path));
            r = 0;
            return true;
        }
        case Kind::Unlink: {
            const std::string path = cstr(arg(0));
            r = files_.erase(path) != 0 ? 0 : fail(ENOENT_);
            return true;
        }
        case Kind::Chmod: {
            const std::string path = cstr(arg(0));
            r = (files_.count(path) != 0 || dirs_.count(path) != 0) ? 0 : fail(ENOENT_);
            return true;
        }
        case Kind::Ftruncate: {
            const uint64_t fd = arg(0);
            const size_t len = static_cast<size_t>(arg(1));
            auto it = fds_.find(fd);
            if (it == fds_.end()) {
                r = fail(EBADF_);
                return true;
            }
            files_[it->second.path].resize(len, 0);
            r = 0;
            return true;
        }
        case Kind::Getenv: {
            const auto it = getenvAddr_.find(cstr(arg(0)));
            r = it == getenvAddr_.end() ? 0 : it->second;
            return true;
        }
        case Kind::CfurlCreate: {
            const uint64_t buf = arg(1);
            const size_t len = static_cast<size_t>(arg(2));
            std::string path(len, '\0');
            if (len != 0 && uc_mem_read(uc_, buf, path.data(), len) != UC_ERR_OK) path.clear();
            nextUrl_ += 0x10;
            urls_[nextUrl_] = path;
            r = nextUrl_;
            return true;
        }
        case Kind::CfurlCopyProp: {
            const uint64_t url = arg(0), key = arg(1), out = arg(2);
            const auto u = urls_.find(url);
            const std::string path = u == urls_.end() ? std::string() : u->second;
            const auto k = keyByObject_.find(key);
            const std::string name = k == keyByObject_.end() ? std::string() : norm(k->second);
            auto obj = [&](const char *n) {
                const auto it = objectOf_.find(n);
                return it == objectOf_.end() ? 0 : it->second;
            };
            if (name == "kCFURLIsDirectoryKey") {
                poke64(out, dirs_.count(path) != 0 ? obj("kCFBooleanTrue") : obj("kCFBooleanFalse"));
                r = 1;
            } else if (name == "kCFURLIsHiddenKey") {
                poke64(out, obj("kCFBooleanFalse"));
                r = 1;
            } else if (name == "kCFURLFileSecurityKey" || name == "kCFURLTypeIdentifierKey") {
                poke64(out, OBJECT_ADDRESS);
                r = 1;
            } else {
                r = 0;
            }
            return true;
        }
    }
    r = 0;
    return true;
}

uint64_t Vm::invoke(uint64_t address, const std::vector<uint64_t> &args) {
    for (size_t i = 0; i < args.size() && i < 6; i++) {
        check(uc_reg_write(uc_, ARG_REGS[i], &args[i]), "set argument");
    }
    uint64_t rsp = STACK_ADDRESS + STACK_SIZE - 0x1000;
    writeU64(rsp, RETURN_ADDRESS);
    check(uc_reg_write(uc_, UC_X86_REG_RSP, &rsp), "set rsp");
    uint64_t zero = 0;
    check(uc_reg_write(uc_, UC_X86_REG_RBP, &zero), "set rbp");
    fault_.clear();

    const uc_err err = uc_emu_start(uc_, address, RETURN_ADDRESS, 0, 0);
    if (!fault_.empty()) {
        const std::string message = fault_;
        fault_.clear();
        throw Error("emulation: " + message);
    }
    if (err != UC_ERR_OK) {
        uint64_t rip = 0;
        uc_reg_read(uc_, UC_X86_REG_RIP, &rip);
        char message[160];
        std::snprintf(message, sizeof message, "emulation failed at rip=%#llx: %s",
                      static_cast<unsigned long long>(rip), uc_strerror(err));
        throw Error(message);
    }
    uint64_t rax = 0;
    check(uc_reg_read(uc_, UC_X86_REG_RAX, &rax), "read rax");
    return rax;
}

void Vm::resetSession() {
    for (const auto &seg : snapshot_.segments) write(seg.first, seg.second);
    write(DATA_ADDRESS, snapshot_.cells);
    check(uc_mem_unmap(uc_, HEAP_ADDRESS, HEAP_SIZE), "unmap heap");
    check(uc_mem_map(uc_, HEAP_ADDRESS, HEAP_SIZE, UC_PROT_ALL), "map heap");
    check(uc_mem_write(uc_, STACK_ADDRESS, zeros_.data(), zeros_.size()), "clear stack");
    const uint64_t base = scratchBase_;
    const size_t dirty = static_cast<size_t>(scratchCur_ - scratchBase_);
    heapCur_ = snapshot_.heapCur;
    objCur_ = snapshot_.objCur;
    nextUrl_ = snapshot_.nextUrl;
    nextFd_ = snapshot_.nextFd;
    scratchCur_ = scratchBase_;
    fds_.clear();
    urls_.clear();
    fault_.clear();
    writeU64(errnoAddr_, 0);
    if (dirty != 0) {
        check(uc_mem_write(uc_, base, zeros_.data(), std::min(dirty, zeros_.size())),
              "clear scratch");
    }
}

uint64_t Vm::scratch(size_t size) { return bumpScratch(size); }

void Vm::scratchReset() { scratchCur_ = scratchBase_; }

void Vm::write(uint64_t addr, const std::vector<uint8_t> &data) {
    if (data.empty()) return;
    check(uc_mem_write(uc_, addr, data.data(), data.size()), "write");
}

void Vm::writeU32(uint64_t addr, uint32_t v) {
    check(uc_mem_write(uc_, addr, &v, 4), "write");
}

void Vm::writeU64(uint64_t addr, uint64_t v) {
    check(uc_mem_write(uc_, addr, &v, 8), "write");
}

std::vector<uint8_t> Vm::read(uint64_t addr, size_t len) const {
    std::vector<uint8_t> out(len);
    if (len != 0) check(uc_mem_read(uc_, addr, out.data(), len), "read");
    return out;
}

uint32_t Vm::readU32(uint64_t addr) const {
    uint32_t v = 0;
    check(uc_mem_read(uc_, addr, &v, 4), "read");
    return v;
}

uint64_t Vm::readU64(uint64_t addr) const {
    uint64_t v = 0;
    check(uc_mem_read(uc_, addr, &v, 8), "read");
    return v;
}

void Vm::setHostUuid(const uint8_t uuid[16]) { std::memcpy(hostUuid_, uuid, 16); }

}  // namespace macadi
