from __future__ import annotations

import base64
import csv
import hashlib
from io import StringIO
from pathlib import Path
import zipfile


WHEEL_PATH = Path("app/libs/unicorn-2.1.1-py3-none-any.whl")

FILES = {
    "unicorn/__init__.py": """# Stub unicorn package — satisfies the anisette dependency without native code.
# OpenTagViewer only uses RemoteAnisetteProvider; the local Anisette emulator
# (which is the only thing that instantiates Uc) is never reached at runtime.

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
""",
    "unicorn/arm64_const.py": """UC_ARM64_REG_FP = 1
UC_ARM64_REG_LR = 2
UC_ARM64_REG_SP = 3
UC_ARM64_REG_W13 = 163
UC_ARM64_REG_W14 = 164
UC_ARM64_REG_W15 = 165
UC_ARM64_REG_X0 = 190
UC_ARM64_REG_X1 = 191
UC_ARM64_REG_X2 = 192
""",
    "unicorn/unicorn.py": """class Uc:
    def __init__(self, arch, mode):
        raise NotImplementedError(
            "unicorn stub: local Anisette is not supported on Android. "
            "Use RemoteAnisetteProvider instead."
        )

    def mem_map(self, address, size, perms=7):
        raise NotImplementedError

    def mem_write(self, address, data):
        raise NotImplementedError

    def mem_read(self, address, length):
        raise NotImplementedError

    def reg_write(self, reg_id, value):
        raise NotImplementedError

    def reg_read(self, reg_id):
        raise NotImplementedError

    def hook_add(self, hook_type, callback, user_data=None, begin=1, end=0, arg1=0):
        raise NotImplementedError

    def emu_start(self, begin, until, timeout=0, count=0):
        raise NotImplementedError

    def emu_stop(self):
        raise NotImplementedError
""",
    "unicorn-2.1.1.dist-info/METADATA": """Metadata-Version: 2.1
Name: unicorn
Version: 2.1.1
Summary: Unicorn CPU emulator engine (Android stub - no native code)
License: GPL-2
""",
    "unicorn-2.1.1.dist-info/WHEEL": """Wheel-Version: 1.0
Generator: rebuild_unicorn_stub_wheel.py
Root-Is-Purelib: true
Tag: py3-none-any
""",
}


def record_line(path: str, data: bytes) -> tuple[str, str, str]:
    digest = hashlib.sha256(data).digest()
    digest_b64 = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    return path, f"sha256={digest_b64}", str(len(data))


def build_record(rows: list[tuple[str, str, str]]) -> bytes:
    output = StringIO()
    writer = csv.writer(output, lineterminator="\n")
    for row in rows:
        writer.writerow(row)
    writer.writerow(("unicorn-2.1.1.dist-info/RECORD", "", ""))
    return output.getvalue().encode("utf-8")


def main() -> int:
    WHEEL_PATH.parent.mkdir(parents=True, exist_ok=True)

    rows: list[tuple[str, str, str]] = []
    with zipfile.ZipFile(WHEEL_PATH, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for path, text in FILES.items():
            data = text.encode("utf-8")
            zf.writestr(path, data)
            rows.append(record_line(path, data))

        record_data = build_record(rows)
        zf.writestr("unicorn-2.1.1.dist-info/RECORD", record_data)

    print(f"Rebuilt {WHEEL_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
