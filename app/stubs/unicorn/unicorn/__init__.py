# Stub `unicorn` package - satisfies anisette's dependency without native code.
#
# FindMy.py >= 0.9 depends on `anisette`, which depends on `unicorn` (a CPU emulator
# used to run Apple's ADI blob for *local* Anisette). Chaquopy cannot build unicorn's
# native code for Android, so the whole dependency tree fails to resolve without this.
#
# OpenTagViewer only ever uses RemoteAnisetteProvider, so the local emulator - the only
# thing that instantiates Uc - is never reached at runtime. See AnisetteProviderGuard on
# the Java side, which fails fast with a legible message rather than letting anisette
# reach this stub.
#
# This package is built into a wheel at Gradle configuration time; see
# scripts/build_unicorn_stub_wheel.py. It is deliberately NOT checked in as a
# prebuilt .whl - a binary artifact that spoofs a real dependency's identity is a
# landmine for anyone reading the repo.

UC_ARCH_ARM = 1
UC_ARCH_ARM64 = 2
UC_ARCH_X86 = 4

UC_MODE_ARM = 0
UC_MODE_32 = 1 << 2
UC_MODE_64 = 1 << 3

UC_HOOK_CODE = 1 << 1
UC_HOOK_BLOCK = 1 << 3
UC_HOOK_MEM_READ_UNMAPPED = 1 << 4
UC_HOOK_MEM_WRITE_UNMAPPED = 1 << 5
UC_HOOK_MEM_FETCH_UNMAPPED = 1 << 6

UC_MEM_WRITE_UNMAPPED = 19
UC_MEM_FETCH_UNMAPPED = 20

from .unicorn import Uc

__all__ = [
    "UC_ARCH_ARM", "UC_ARCH_ARM64", "UC_ARCH_X86",
    "UC_MODE_ARM", "UC_MODE_32", "UC_MODE_64",
    "UC_HOOK_CODE", "UC_HOOK_BLOCK",
    "UC_HOOK_MEM_READ_UNMAPPED", "UC_HOOK_MEM_WRITE_UNMAPPED", "UC_HOOK_MEM_FETCH_UNMAPPED",
    "UC_MEM_WRITE_UNMAPPED", "UC_MEM_FETCH_UNMAPPED",
    "Uc",
]
