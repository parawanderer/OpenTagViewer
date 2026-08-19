"""
Turn off Control Flow Guard on a built Windows executable, so a JIT can run inside it.

**Why this exists.** The exporter signs in by emulating Apple's ADI library, which `unicorn` does
by compiling ARM64 into native code at runtime and jumping to it. Control Flow Guard is a Windows
mitigation that checks every indirect call against a table of targets the linker knew about, and
code that did not exist at link time is not in that table. So the jump is rejected and the process
is terminated by `__fastfail` - **no exception, no traceback, nothing in any log**, because the
whole point of the mechanism is to stop the process before anything can handle it.

That is exactly what a user reported as `OpenTagViewer.exe parou de funcionar`, and what CI
reproduced: `import`, construct, `mem_map` and `mem_write` all succeed, and `emu_start` returns
exit code `0xC0000409`.

PyInstaller's bootloader is built with CFG enabled and the flag applies to the whole process, so
nothing about how the Python side is packaged can avoid it. The flag lives in one bit of the PE
header, and clearing it after the build is the smallest change that makes the program work.

**This is a real trade, not a formality.** CFG is a genuine hardening measure and this gives it up
for the whole executable. It is given up because the alternative is an exporter that cannot sign in
on Windows at all: the emulator is not an optional component, it is how the login works without
handing the exchange to a third-party Anisette server. Every program that JITs - browsers, .NET,
every language runtime with a tracing compiler - either disables CFG or registers its targets with
`SetProcessValidCallTargets`, and the second is not reachable from here.

Usage:

    python scripts/clear_windows_cfg.py path/to/OpenTagViewer.exe
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

# IMAGE_DLLCHARACTERISTICS_GUARD_CF. One bit, and the difference between a program that runs and
# one that is killed on its first jump into compiled code.
GUARD_CF = 0x4000

PE32 = 0x10B
PE32_PLUS = 0x20B

# Offset of DllCharacteristics within the optional header. The same for PE32 and PE32+: the two
# differ earlier - PE32+ drops BaseOfData and widens ImageBase to eight bytes - and the layouts
# realign at SectionAlignment, well before this field.
DLL_CHARACTERISTICS_OFFSET = 0x46


class NotAPortableExecutable(Exception):
    """The file is not a PE image, so there is no flag here to change."""


def clear_guard_cf(path: Path) -> bool:
    """
    Clear the CFG bit in a PE image, in place.

    :returns: Whether the bit was set to begin with, so a caller can tell "turned it off" from
        "there was nothing to turn off" - which are different, and only the first is expected.
    :raises NotAPortableExecutable: If the file does not have the headers this needs. Raised
        rather than ignored: silently doing nothing to the wrong file would produce a build that
        looks patched and still dies.
    """
    data = bytearray(path.read_bytes())

    if data[:2] != b"MZ":
        msg = f"{path} does not start with MZ, so it is not a PE image."
        raise NotAPortableExecutable(msg)

    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe_offset:pe_offset + 4] != b"PE\0\0":
        msg = f"{path} has no PE signature where its DOS header points."
        raise NotAPortableExecutable(msg)

    # COFF header is 20 bytes, then the optional header begins.
    optional = pe_offset + 4 + 20
    magic = struct.unpack_from("<H", data, optional)[0]

    if magic not in (PE32, PE32_PLUS):
        msg = f"{path} has optional header magic {magic:#06x}, which is neither PE32 nor PE32+."
        raise NotAPortableExecutable(msg)

    field = optional + DLL_CHARACTERISTICS_OFFSET
    characteristics = struct.unpack_from("<H", data, field)[0]

    if not characteristics & GUARD_CF:
        return False

    struct.pack_into("<H", data, field, characteristics & ~GUARD_CF)
    path.write_bytes(bytes(data))

    return True


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print(__doc__)
        return 2

    path = Path(argv[0])

    try:
        cleared = clear_guard_cf(path)
    except (OSError, NotAPortableExecutable) as e:
        print(f"Could not clear Control Flow Guard on {path}: {e}", file=sys.stderr)
        return 1

    if cleared:
        print(f"Control Flow Guard turned off on {path}")
    else:
        # Worth saying rather than passing quietly. It means either the toolchain stopped setting
        # it - in which case this script has become unnecessary - or the wrong file was patched,
        # and those want very different follow-ups.
        print(f"Control Flow Guard was already off on {path}; nothing changed")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
