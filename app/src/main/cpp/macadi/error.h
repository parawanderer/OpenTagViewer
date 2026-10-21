#pragma once

#include <stdexcept>
#include <string>

namespace macadi {

// Everything this library can fail with. The message is what reaches Java, and through it the
// log the user is asked to attach, so it says which layer failed: the Mach-O, the emulation, or
// Apple's library answering with an error of its own.
class Error : public std::runtime_error {
public:
    explicit Error(const std::string &message) : std::runtime_error(message) {}
};

// The library is not provisioned yet. Distinct because it is the one failure that has a remedy:
// provisioning.
class NotProvisioned : public Error {
public:
    NotProvisioned() : Error("the machine is not provisioned") {}
};

class AlreadyProvisioned : public Error {
public:
    AlreadyProvisioned() : Error("the machine is already provisioned") {}
};

}  // namespace macadi
