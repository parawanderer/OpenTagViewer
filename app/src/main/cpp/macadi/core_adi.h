// The CoreADI dispatcher, `_vdfut768ig(function_code, CoreADIParameters *)`, and the handful of
// operations Anisette needs from it.
//
// Requests are big-endian; pointers inside a message are 8-byte big-endian addresses *inside the
// emulated process*. After Dadoum/apple-crates adi/src/{core_adi,calls}.rs, by way of
// sacca97/os-findmy-e native/adi-core/src/adi.rs (MIT), which this ports.
//
// **One high-level call is one session.** Each starts by putting the VM back to its post-load
// state and calling Initialize, the way a fresh process would. Nothing accumulates between calls,
// which is what lets a bump allocator stand in for malloc. The one exception is provisioning:
// provisioningBegin and provisioningFinish must share a session, with nothing in between.

#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "vm.h"

namespace macadi {

struct Headers {
    std::vector<uint8_t> mid;  // X-Apple-I-MD-M, raw
    std::vector<uint8_t> otp;  // X-Apple-I-MD, raw
    uint64_t routing;          // X-Apple-I-MD-RINFO
};

class CoreAdi {
public:
    // `dispatch` is the offset of _vdfut768ig in this build. Java knows it, by the build's sha256,
    // and refuses any file it does not recognise before this is ever called.
    CoreAdi(const std::vector<uint8_t> &image, uint64_t dispatch);

    void setHostUuid(const uint8_t uuid[16]) { vm_.setHostUuid(uuid); }
    void setFrozenTime(int64_t secs) { vm_.setFrozenTime(secs); }

    // The store, as (file name, bytes). Persist it after every call that can change it - which is
    // every call, since RequestOTP rewrites it.
    void storePut(const std::string &name, const std::vector<uint8_t> &data);
    std::vector<std::pair<std::string, std::vector<uint8_t>>> storeFiles();

    // One session: what a machine puts on one request.
    Headers headers(int64_t dsId);

    // Start provisioning with Apple's spim; returns (cpim, session).
    std::pair<std::vector<uint8_t>, uint32_t> provisioningBegin(int64_t dsId,
                                                                const std::vector<uint8_t> &spim);
    // Finish with what Apple returned for the cpim. Same session as provisioningBegin.
    void provisioningFinish(int64_t dsId, uint32_t session, const std::vector<uint8_t> &ptm,
                            const std::vector<uint8_t> &tk, uint64_t routing);

    // Answer Apple's resync request (a 434 carrying X-Apple-I-MD-DATA): returns (mid, srm).
    std::pair<std::vector<uint8_t>, std::vector<uint8_t>> sync(int64_t dsId,
                                                               const std::vector<uint8_t> &sim);

private:
    struct Reply {
        int32_t rc;   // the dispatcher's status
        int32_t ret;  // the operation's own, negative on failure
    };

    void beginSession();
    Reply call(uint32_t code, const std::vector<uint8_t> &message);
    uint64_t cell(uint64_t v);
    uint64_t input(const std::vector<uint8_t> &data);
    std::vector<uint8_t> slice(uint64_t ptr, uint64_t len);
    static void check(const char *op, Reply r);

    Reply initialize();
    Reply isMachineProvisioned(int64_t dsId);
    Reply storageDispose(uint64_t ptr);
    Reply setRouting(int64_t dsId, uint64_t routing);

    Vm vm_;
    uint64_t dispatch_;
};

}  // namespace macadi
