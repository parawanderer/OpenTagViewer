// scripts/adi-emulator-diff/adi-diff.rs, replayed against this repository's C++ port.
//
//   macadi_diff <CoreADI x86_64 slice> <dispatch offset, hex> [provisioned store dir]
//
// Makes the same calls as os-findmy-e's adi-core driver, with the same frozen clock, host UUID
// and synthetic spims, and prints the same lines in the same format - including Rust's {:?} for
// the replies - so `diff` between the two outputs is the whole comparison. Sends nothing anywhere.
//
// With a store directory (the files under files/anisette/mac/store/ of a provisioned install, or
// any provisioned adi-core store) it then also runs one header session on that machine with the
// clock still frozen: the OTP path, which an unprovisioned machine never reaches. adi-diff.rs
// takes the same optional argument.

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <dirent.h>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "core_adi.h"
#include "error.h"
#include "sha256.h"

namespace {

std::vector<uint8_t> readFile(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw macadi::Error("cannot read " + path);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

std::string hex(const std::vector<uint8_t> &b, size_t limit = SIZE_MAX) {
    static const char *digits = "0123456789abcdef";
    std::string out;
    for (size_t i = 0; i < b.size() && i < limit; i++) {
        out.push_back(digits[b[i] >> 4]);
        out.push_back(digits[b[i] & 15]);
    }
    return out;
}

// Rust's {:?} of adi_core::Reply.
std::string reply(const macadi::CoreAdi::Reply &r) {
    return "Reply { rc: " + std::to_string(r.rc) + ", ret: " + std::to_string(r.ret) + " }";
}

// adi-diff.rs's store_sha: every file in the VM, path then length (u64 LE) then bytes, in the
// map's order (BTreeMap there, std::map here: both sort by bytes).
std::string storeSha(macadi::CoreAdi &adi) {
    hostcheck::Sha256 h;
    for (const auto &f : adi.vmFiles()) {
        h.update(reinterpret_cast<const uint8_t *>(f.first.data()), f.first.size());
        uint8_t len[8];
        uint64_t n = f.second.size();
        for (int i = 0; i < 8; i++) len[i] = static_cast<uint8_t>(n >> (8 * i));
        h.update(len, 8);
        h.update(f.second.data(), f.second.size());
    }
    return hex(h.digest(), 8);
}

// A failure the Rust driver would print as Err(...). Never expected; any of these is a diff.
template <typename F>
void line(const std::string &label, F body) {
    try {
        std::printf("%s %s\n", label.c_str(), body().c_str());
    } catch (const std::exception &e) {
        std::printf("%s Err(%s)\n", label.c_str(), e.what());
    }
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: %s coreadi dispatch_hex [store_dir]\n", argv[0]);
        return 2;
    }
    try {
        const std::vector<uint8_t> image = readFile(argv[1]);
        macadi::CoreAdi adi(image, std::strtoull(argv[2], nullptr, 16));
        const uint8_t uuid[16] = {0x10, 0x32, 0x54, 0x76, 0x98, 0xba, 0xdc, 0xfe,
                                  0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef};
        adi.setHostUuid(uuid);
        adi.setFrozenTime(1790000000);

        line("begin_session", [&] { return "Ok(" + reply(adi.rawBeginSession()) + ")"; });
        line("is_machine_provisioned", [&] { return "Ok(" + reply(adi.isMachineProvisioned(-2)) + ")"; });
        line("request_otp(unprovisioned)", [&] {
            const auto o = adi.requestOtp(-2);
            return "Ok((" + reply(o.reply) + ", \"" + hex(o.mid) + "\", \"" + hex(o.otp) + "\"))";
        });
        line("get_routing", [&] {
            const auto r = adi.getRouting(-2);
            return "Ok((" + reply(r.first) + ", " + std::to_string(r.second) + "))";
        });
        std::printf("store %s\n", storeSha(adi).c_str());

        uint64_t seed = 0x9E3779B97F4A7C15ull;
        const size_t lengths[] = {0, 16, 256, 1024, 4096};
        for (size_t i = 0; i < 5; i++) {
            const size_t len = lengths[i];
            std::vector<uint8_t> spim;
            for (size_t j = 0; j < len; j++) {
                seed ^= seed << 13;
                seed ^= seed >> 7;
                seed ^= seed << 17;
                spim.push_back(static_cast<uint8_t>(seed));
            }
            line("begin_session", [&] { return "Ok(" + reply(adi.rawBeginSession()) + ")"; });
            const std::string label = "provisioning_start[" + std::to_string(i) + "] len=" +
                                      std::to_string(len);
            try {
                const auto p = adi.provisioningStart(-2, spim);
                std::printf("%s %s session=%u cpim(%zu)=%s\n", label.c_str(), reply(p.reply).c_str(),
                            p.session, p.cpim.size(), hex(hostcheck::sha256(p.cpim), 16).c_str());
            } catch (const std::exception &e) {
                std::printf("%s error %s\n", label.c_str(), e.what());
            }
            std::printf("store %s\n", storeSha(adi).c_str());
        }

        if (argc > 3) {
            // A provisioned machine, then one header session on it: the OTP path.
            DIR *dir = opendir(argv[3]);
            if (dir == nullptr) throw macadi::Error(std::string("cannot open store ") + argv[3]);
            while (const dirent *e = readdir(dir)) {
                const std::string name = e->d_name;
                if (name == "." || name == "..") continue;
                adi.storePut(name, readFile(std::string(argv[3]) + "/" + name));
            }
            closedir(dir);
            line("provisioned begin_session", [&] { return "Ok(" + reply(adi.rawBeginSession()) + ")"; });
            line("provisioned is_machine_provisioned",
                 [&] { return "Ok(" + reply(adi.isMachineProvisioned(-2)) + ")"; });
            line("provisioned request_otp", [&] {
                const auto o = adi.requestOtp(-2);
                return "Ok((" + reply(o.reply) + ", \"" + hex(o.mid) + "\", \"" + hex(o.otp) + "\"))";
            });
            line("provisioned get_routing", [&] {
                const auto r = adi.getRouting(-2);
                return "Ok((" + reply(r.first) + ", " + std::to_string(r.second) + "))";
            });
            std::printf("provisioned store %s\n", storeSha(adi).c_str());
        }
        return 0;
    } catch (const std::exception &e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }
}
