// Checks on the emulated Mac's core that the byte-for-byte comparison cannot make.
//
//   macadi_selftest <CoreADI x86_64 slice> <dispatch offset, hex> [<provisioned store dir>
//                   <host uuid, 32 hex> <calls>]
//
// Always: a call into guest code that never returns is stopped by the call timeout and reported
// as an error, and the same engine answers normally afterwards.
//
// With a store: <calls> header sets in a row from one engine, as MacAdiService serves them for the
// life of its process. Each must succeed with the same machine id, and resident memory must not
// keep growing. One engine faulting after a handful of calls has been seen in another port.

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#if defined(__APPLE__)
#include <mach/mach.h>
#endif

#include "core_adi.h"
#include "error.h"

namespace {

std::vector<uint8_t> readFile(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw macadi::Error("cannot read " + path);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

long residentKb() {
#if defined(__APPLE__)
    mach_task_basic_info info;
    mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
    if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO, reinterpret_cast<task_info_t>(&info),
                  &count) != KERN_SUCCESS) {
        return -1;
    }
    return static_cast<long>(info.resident_size / 1024);
#else
    std::ifstream statm("/proc/self/statm");
    long size = 0, resident = 0;
    statm >> size >> resident;
    return resident * 4;
#endif
}

int fail(const std::string &why) {
    std::fprintf(stderr, "FAIL: %s\n", why.c_str());
    return 1;
}

int checkTimeout(macadi::CoreAdi &adi) {
    adi.setCallTimeout(500 * 1000);
    const uint64_t loop = adi.vm().scratch(16);
    adi.vm().write(loop, {0xEB, 0xFE});  // jmp $
    const auto start = std::chrono::steady_clock::now();
    try {
        adi.vm().invoke(loop, {});
        return fail("a guest loop returned");
    } catch (const macadi::Error &e) {
        const double took = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
        if (std::string(e.what()).find("no return within") == std::string::npos) {
            return fail(std::string("a guest loop failed some other way: ") + e.what());
        }
        if (took > 5) return fail("the timeout took " + std::to_string(took) + " s");
        std::printf("ok: a guest loop stopped after %.2f s: %s\n", took, e.what());
    }
    adi.vm().scratchReset();
    adi.setCallTimeout(30ULL * 1000 * 1000);
    try {
        adi.headers(-2);
        std::printf("ok: the engine answers after a timeout (provisioned)\n");
    } catch (const macadi::NotProvisioned &) {
        std::printf("ok: the engine answers after a timeout (not provisioned)\n");
    }
    return 0;
}

constexpr int SETTLED = 50;

int checkRepeated(macadi::CoreAdi &adi, const std::string &store, int calls) {
    DIR *dir = opendir(store.c_str());
    if (!dir) return fail("cannot open store " + store);
    while (const dirent *e = readdir(dir)) {
        const std::string name = e->d_name;
        if (name == "." || name == "..") continue;
        adi.storePut(name, readFile(store + "/" + name));
    }
    closedir(dir);

    std::vector<uint8_t> mid;
    long settled = 0;
    for (int i = 0; i < calls; i++) {
        const macadi::Headers h = adi.headers(-2);
        if (h.otp.empty()) return fail("call " + std::to_string(i + 1) + " returned no OTP");
        if (i == 0) mid = h.mid;
        else if (h.mid != mid) return fail("call " + std::to_string(i + 1) + " changed the machine id");
        if (i == SETTLED - 1) settled = residentKb();
    }
    const long end = residentKb();
    std::printf("ok: %d header sets from one engine; resident %ld KB after %d, %ld KB after %d\n",
                calls, settled, SETTLED, end, calls);
    // Two things grow early and stop: QEMU's TLB doubles over the first dozen calls (about 60 MB,
    // to its built-in ceiling), and the translation cache fills to its cap. Past that, growth is
    // a leak. Without the cap set in Vm this was ~20 KB a call, so 1000 calls fail it.
    if (calls > SETTLED && end - settled > 8 * 1024) return fail("resident memory kept growing");
    return 0;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc != 3 && argc != 6) {
        std::fprintf(stderr, "usage: %s coreadi dispatch_hex [store_dir host_uuid_hex calls]\n", argv[0]);
        return 2;
    }
    try {
        const std::vector<uint8_t> image = readFile(argv[1]);
        macadi::CoreAdi adi(image, std::strtoull(argv[2], nullptr, 16));
        if (argc == 6) {
            const std::string uuidHex = argv[4];
            if (uuidHex.size() != 32) throw macadi::Error("host uuid must be 32 hex characters");
            uint8_t uuid[16];
            for (int i = 0; i < 16; i++) uuid[i] = static_cast<uint8_t>(std::stoul(uuidHex.substr(i * 2, 2), nullptr, 16));
            adi.setHostUuid(uuid);
        }
        if (const int rc = checkTimeout(adi)) return rc;
        if (argc == 6) return checkRepeated(adi, argv[3], std::atoi(argv[5]));
        return 0;
    } catch (const std::exception &e) {
        return fail(e.what());
    }
}
